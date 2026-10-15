package com.example.bedaura.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Environment(EnvType.CLIENT)
public class BedAuraClient implements ClientModInitializer {
    private KeyBinding key;
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // config
    private Config config = new Config();

    // runtime state
    private boolean enabled = false; // toggle mode
    private boolean announced = false;
    private int state = 0; // 0 idle, 1 place cobble, 2 place bed, 3 interact bed
    private int ticks = 0;
    private int prevHotbarSlot = -1;
    private int cobbleSlot = -1;
    private int bedSlot = -1;
    private BlockPos footPos = null;
    private BlockPos cobblePos = null;
    private int cooldown = 0;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitializeClient() {
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bed_aura.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.bed_aura"
        ));

        loadConfig();

        // apply start_enabled from config
        enabled = config.start_enabled;

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick();
            } catch (Throwable t) {
                t.printStackTrace();
                resetState();
            }
        });
    }

    private void loadConfig() {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path path = configDir.resolve("bed_aura.json");
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                Config c = gson.fromJson(json, Config.class);
                if (c != null) config = c;
            } else {
                // write default
                Files.createDirectories(path.getParent());
                Files.writeString(path, gson.toJson(config), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveConfig() {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path path = configDir.resolve("bed_aura.json");
            Files.writeString(path, gson.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void tick() {
        if (mc.player == null || mc.world == null) {
            resetState();
            return;
        }

        // announce auto-start if enabled from config
        if (enabled && !announced) {
            mc.player.sendMessage(Text.of("Bed Aura: auto-start enabled"), true);
            announced = true;
        }

        // toggle key
        while (key.wasPressed()) {
            enabled = !enabled;
            mc.player.sendMessage(Text.of("Bed Aura " + (enabled ? "enabled" : "disabled")), true);
        }

        if (!enabled) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // find target player in radius
        Optional<PlayerEntity> opt = findTargetNearby(mc.player, config.radius);
        if (opt.isEmpty()) return;
        PlayerEntity target = opt.get();

        // compute positions: foot at block target stands on, bed should face the target
        BlockPos targetPos = target.getBlockPos();
        // compute approximate facing from player to target
        Vec3d tVec = target.getPos();
        Vec3d meVec = mc.player.getPos();
        double dx = tVec.x - meVec.x;
        double dz = tVec.z - meVec.z;
        Direction towardTarget;
        if (Math.abs(dx) > Math.abs(dz)) {
            towardTarget = dx > 0 ? Direction.WEST : Direction.EAST; // approximate
        } else {
            towardTarget = dz > 0 ? Direction.NORTH : Direction.SOUTH;
        }

        Direction bedFacing = target.getHorizontalFacing().getOpposite();
        if (bedFacing == null) bedFacing = towardTarget;

        BlockPos candidate = targetPos.offset(bedFacing);
        footPos = candidate;
        cobblePos = footPos.offset(bedFacing.getOpposite());

        // ensure hotbar items
        PlayerInventory inv = mc.player.getInventory();
        cobbleSlot = findHotbarSlot(inv, Items.COBBLESTONE);
        bedSlot = findHotbarBedSlot(inv);
        if (cobbleSlot == -1 || bedSlot == -1) {
            mc.player.sendMessage(Text.of("Bed Aura: put cobblestone and a bed in your hotbar!"), true);
            enabled = false;
            return;
        }

        prevHotbarSlot = inv.selectedSlot;
        // start sequence
        state = 1;
        ticks = 0;

        // run sequence across ticks
        runStateMachine();
    }

    private void runStateMachine() {
        ticks++;
        switch (state) {
            case 1:
                if (ticks == 1) {
                    setHotbarSlot(cobbleSlot);
                    placeBlockAt(cobblePos);
                } else if (ticks > config.delays.place_cobble_ticks) {
                    state = 2;
                    ticks = 0;
                }
                break;
            case 2:
                if (ticks == 1) {
                    setHotbarSlot(bedSlot);
                    placeBlockAt(footPos);
                } else if (ticks > config.delays.place_bed_ticks) {
                    state = 3;
                    ticks = 0;
                }
                break;
            case 3:
                if (ticks == 1) {
                    interactBlock(footPos);
                } else if (ticks > config.delays.interact_bed_ticks) {
                    restoreHotbar();
                    resetState();
                    cooldown = config.delays.sequence_cooldown_ticks;
                }
                break;
            default:
                resetState();
        }
    }

    private Optional<PlayerEntity> findTargetNearby(PlayerEntity self, int radius) {
        double bestDist = Double.MAX_VALUE;
        PlayerEntity best = null;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == self) continue;
            if (p.isSpectator() || p.isCreative()) continue;
            double d = p.squaredDistanceTo(self);
            if (d <= radius * radius && d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    private void resetState() {
        state = 0;
        ticks = 0;
        prevHotbarSlot = -1;
        cobbleSlot = -1;
        bedSlot = -1;
        footPos = null;
        cobblePos = null;
    }

    private void restoreHotbar() {
        if (prevHotbarSlot >= 0 && mc.player != null) {
            setHotbarSlot(prevHotbarSlot);
        }
    }

    private int findHotbarSlot(PlayerInventory inv, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (st != null && st.getItem() == item && st.getCount() > 0) return i;
        }
        return -1;
    }

    private int findHotbarBedSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (st == null) continue;
            Item it = st.getItem();
            if (it == Items.RED_BED || it == Items.WHITE_BED || it == Items.BLACK_BED
                || it == Items.BLUE_BED || it == Items.GREEN_BED || it == Items.YELLOW_BED
                || it == Items.BROWN_BED || it == Items.CYAN_BED || it == Items.GRAY_BED
                || it == Items.LIGHT_BLUE_BED || it == Items.LIGHT_GRAY_BED || it == Items.LIME_BED
                || it == Items.MAGENTA_BED || it == Items.ORANGE_BED || it == Items.PINK_BED
                || it == Items.PURPLE_BED) {
                return i;
            }
        }
        return -1;
    }

    private void setHotbarSlot(int slot) {
        if (mc.player == null) return;
        mc.player.getInventory().selectedSlot = slot;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void placeBlockAt(BlockPos pos) {
        if (mc.player == null || mc.world == null) return;
        Vec3d hitVec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Direction side = Direction.UP;
        BlockHitResult bhr = new BlockHitResult(hitVec, side, pos, false);
        mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void interactBlock(BlockPos pos) {
        if (mc.player == null || mc.world == null) return;
        Vec3d hitVec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Direction side = Direction.UP;
        BlockHitResult bhr = new BlockHitResult(hitVec, side, pos, false);
        mc.interactionManager.interactBlock(mc.player, mc.world, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // config classes
    static class Config {
        boolean start_enabled = true;
        int radius = 6;
        Delays delays = new Delays();
        String mode = "auto"; // auto or manual
        boolean destroy = true;
    }

    static class Delays {
        int place_cobble_ticks = 2;
        int place_bed_ticks = 2;
        int interact_bed_ticks = 2;
        int sequence_cooldown_ticks = 10;
    }
}
