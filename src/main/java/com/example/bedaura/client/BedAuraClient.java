package com.example.bedaura.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.PlayerInventory;

@Environment(EnvType.CLIENT)
public class BedAuraClient implements ClientModInitializer {
    private KeyBinding key;
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // state machine for multi-tick sequence
    private int state = 0;
    private int ticks = 0;
    private int prevHotbarSlot = -1;
    private int cobbleSlot = -1;
    private int bedSlot = -1;
    private BlockPos footPos = null;
    private BlockPos headPos = null;
    private BlockPos cobblePos = null;

    @Override
    public void onInitializeClient() {
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bed_aura.activate",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.bed_aura"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick();
            } catch (Throwable t) {
                t.printStackTrace();
                resetState();
            }
        });
    }

    private void tick() {
        if (mc.player == null || mc.world == null) {
            resetState();
            return;
        }

        // start sequence on key press if not already running
        if (key.wasPressed() && state == 0) {
            HitResult hr = mc.crosshairTarget;
            if (!(hr instanceof BlockHitResult)) {
                // not looking at a block
                return;
            }
            BlockHitResult bhr = (BlockHitResult) hr;
            BlockPos target = bhr.getBlockPos();
            Direction facing = mc.player.getHorizontalFacing();

            // Define foot/head positions: foot at the block you're looking at, head one block further in facing
            footPos = target;
            headPos = footPos.offset(facing);

            // cobblestone placed between player and foot (toward player)
            cobblePos = footPos.offset(facing.getOpposite());

            // find cobble and bed in hotbar
            PlayerInventory inv = mc.player.getInventory();
            cobbleSlot = findHotbarSlot(inv, Items.COBBLESTONE);
            bedSlot = findHotbarBedSlot(inv); // any bed

            if (cobbleSlot == -1 || bedSlot == -1) {
                // need both items in hotbar
                mc.player.sendMessage(net.minecraft.text.Text.of("Put cobblestone and a bed in your hotbar!"), true);
                resetState();
                return;
            }

            prevHotbarSlot = inv.selectedSlot;
            state = 1;
            ticks = 0;
        }

        // run state machine across ticks to sequence actions (allow server to process each interact)
        if (state != 0) {
            ticks++;
            switch (state) {
                case 1:
                    // switch to cobble and place it at cobblePos
                    if (ticks == 1) {
                        setHotbarSlot(cobbleSlot);
                        placeBlockAt(cobblePos);
                    } else if (ticks > 4) {
                        state = 2;
                        ticks = 0;
                    }
                    break;
                case 2:
                    // switch to bed and place foot/head at footPos/headPos
                    if (ticks == 1) {
                        setHotbarSlot(bedSlot);
                        // try to place bed foot at footPos
                        placeBlockAt(footPos);
                    } else if (ticks > 4) {
                        state = 3;
                        ticks = 0;
                    }
                    break;
                case 3:
                    // interact with bed (right click) to trigger explosion in End
                    if (ticks == 1) {
                        interactBlock(footPos);
                    } else if (ticks > 4) {
                        // restore hotbar and reset
                        restoreHotbar();
                        resetState();
                    }
                    break;
            }
        }
    }

    private void resetState() {
        state = 0;
        ticks = 0;
        prevHotbarSlot = -1;
        cobbleSlot = -1;
        bedSlot = -1;
        footPos = null;
        headPos = null;
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
        // inform server by updating held item
        mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
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
}
