package com.nnpg.glazed.modules.main;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

public class RTPBaseFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> baseDetectionAmount = sgGeneral.add(new IntSetting.Builder()
        .name("base-detection-amount")
        .description("How many storages must be found to count as a base.")
        .defaultValue(5)
        .min(1)
        .sliderMax(50)
        .build()
    );

    private final Setting<Integer> yLevelRtp = sgGeneral.add(new IntSetting.Builder()
        .name("y-level-rtp")
        .description("Y level to trigger another /rtp east.")
        .defaultValue(20)
        .min(0)
        .max(256)
        .build()
    );

    private long lastRtpTime = 0;
    private boolean digging = false;
    private boolean clutching = false;

    private final Set<Block> storages = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX
    );

    public RTPBaseFinder() {
        super(com.nnpg.glazed.GlazedAddon.CATEGORY, "rtp-base-finder", "Uses /rtp east and digs down to detect bases.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        sendRtp();
    }

    private void sendRtp() {
        if (mc.player != null) {
            mc.player.networkHandler.sendChatMessage("/rtp east");
            lastRtpTime = System.currentTimeMillis();
            digging = false;
            clutching = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        long sinceRtp = System.currentTimeMillis() - lastRtpTime;

        // Wait 6s before digging
        if (!digging && sinceRtp > 6000) {
            rotateDown();
            digging = true;
        }

        // Detect if falling and trigger clutch
        if (mc.player.fallDistance > 3 && !clutching) {
            if (tryMLG()) clutching = true;
            return;
        }

        if (clutching) {
            // After landing, pick up water
            BlockPos feet = mc.player.getBlockPos();
            if (mc.world.getBlockState(feet).getBlock() == Blocks.WATER) {
                int bucketSlot = findHotbarSlot(Items.BUCKET);
                if (bucketSlot != -1) {
                    mc.player.getInventory().selectedSlot = bucketSlot;
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                clutching = false;
                digging = true;
            }
            return;
        }

        if (digging) {
            mineDown();
            detectBase();

            if (mc.player.getBlockY() <= yLevelRtp.get()) {
                sendRtp();
            }
        }
    }

    private void rotateDown() {
        if (mc.player != null) {
            mc.player.setPitch(90); // look straight down
        }
    }

    private void mineDown() {
        BlockPos below = mc.player.getBlockPos().down();

        if (mc.world.getBlockState(below).isAir()) return;

        int pickSlot = findHotbarSlot(Items.NETHERITE_PICKAXE);
        if (pickSlot == -1) pickSlot = findHotbarSlot(Items.DIAMOND_PICKAXE);

        if (pickSlot != -1) {
            mc.player.getInventory().selectedSlot = pickSlot;
            mc.interactionManager.updateBlockBreakingProgress(below, mc.player.getHorizontalFacing());
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void detectBase() {
        int found = 0;
        BlockPos playerPos = mc.player.getBlockPos();

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, 6, 6, 6)) {
            Block block = mc.world.getBlockState(pos).getBlock();
            if (storages.contains(block)) found++;
        }

        if (found >= baseDetectionAmount.get()) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal("[RtpBaseFinder] Base Found"));
        }
    }

    private boolean tryMLG() {
        int waterSlot = findHotbarSlot(Items.WATER_BUCKET);
        if (waterSlot == -1) return false;

        mc.player.getInventory().selectedSlot = waterSlot;
        mc.player.setPitch(90); // look straight down
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        return true;
    }

    private int findHotbarSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }
}
