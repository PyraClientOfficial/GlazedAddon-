package com.nnpg.glazed.modules.main;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;
import java.util.List;

public class RTPBaseFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> rtpDirection = sgGeneral.add(new StringSetting.Builder()
        .name("rtp-direction")
        .description("Which direction to /rtp in (east or west).")
        .defaultValue("east")
        .build()
    );

    private final Setting<Integer> baseDetectionAmount = sgGeneral.add(new IntSetting.Builder()
        .name("base-detection-amount")
        .description("How many storages must be found to count as a base.")
        .defaultValue(5)
        .min(1)
        .sliderMax(50)
        .build()
    );

    private final Setting<Integer> yMin = sgGeneral.add(new IntSetting.Builder()
        .name("y-min")
        .description("Lowest Y level before forcing another /rtp.")
        .defaultValue(-50)
        .min(-64)
        .max(320)
        .build()
    );

    private final Setting<Integer> yMax = sgGeneral.add(new IntSetting.Builder()
        .name("y-max")
        .description("Highest Y level before forcing another /rtp.")
        .defaultValue(20)
        .min(-64)
        .max(320)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("Speed of player rotation (degrees per tick).")
        .defaultValue(10.0)
        .min(1.0)
        .max(90.0)
        .sliderMax(45.0)
        .build()
    );

    private boolean digging = false;
    private boolean clutching = false;
    private boolean rotating = false;

    private float targetPitch = 90f;

    private BlockPos miningBlock = null;
    private float breakProgress = 0f;

    private final Set<Block> storages = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX
    );

    private final List<net.minecraft.item.Item> pickaxePriority = List.of(
        Items.NETHERITE_PICKAXE,
        Items.DIAMOND_PICKAXE,
        Items.IRON_PICKAXE,
        Items.STONE_PICKAXE,
        Items.GOLDEN_PICKAXE,
        Items.WOODEN_PICKAXE
    );

    public RTPBaseFinder() {
        super(com.nnpg.glazed.GlazedAddon.CATEGORY, "rtp-base-finder", "Uses /rtp and digs to detect bases.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;
        sendRtp();
    }

    private void sendRtp() {
        if (mc.player != null) {
            mc.player.networkHandler.sendChatCommand("rtp " + rtpDirection.get().toLowerCase());
            digging = false;
            clutching = false;
            rotating = true;
            miningBlock = null;
            breakProgress = 0f;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (rotating) {
            float currentPitch = mc.player.getPitch();
            float step = rotationSpeed.get().floatValue();

            if (Math.abs(targetPitch - currentPitch) <= step) {
                mc.player.setPitch(targetPitch);
                rotating = false;
                digging = true;
            } else {
                float newPitch = currentPitch + Math.signum(targetPitch - currentPitch) * step;
                mc.player.setPitch(newPitch);
            }
        }

        if (mc.player.fallDistance > 3 && mc.player.getVelocity().y < -0.5 && !clutching) {
            if (tryMLG()) clutching = true;
            return;
        }

        if (clutching) {
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
            mineLookingAt();
            detectBase();

            int y = mc.player.getBlockY();
            if (y <= yMin.get()) {
                sendRtp();
            }
        }
    }

    private void mineLookingAt() {
        // Raycast up to 5 blocks
        HitResult result = mc.player.raycast(5.0, 0.0f, false);
        if (!(result instanceof BlockHitResult bhr)) {
            miningBlock = null;
            breakProgress = 0f;
            return;
        }

        BlockPos targetPos = bhr.getBlockPos();
        Direction face = bhr.getSide();

        if (mc.world.getBlockState(targetPos).isAir()) {
            miningBlock = null;
            breakProgress = 0f;
            return;
        }

        // Creative → instant mine
        if (mc.player.getAbilities().creativeMode) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, targetPos, face
            ));
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, targetPos, face
            ));
            mc.player.swingHand(Hand.MAIN_HAND);
            miningBlock = null;
            breakProgress = 0f;
            return;
        }

        // Survival mining
        if (miningBlock == null || !miningBlock.equals(targetPos)) {
            miningBlock = targetPos;
            breakProgress = 0f;
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, targetPos, face
            ));
        }

        // Pick best pickaxe
        int pickSlot = -1;
        for (net.minecraft.item.Item pick : pickaxePriority) {
            pickSlot = findHotbarSlot(pick);
            if (pickSlot != -1) break;
        }
        if (pickSlot != -1) mc.player.getInventory().selectedSlot = pickSlot;

        float delta = mc.world.getBlockState(targetPos).calcBlockBreakingDelta(mc.player, mc.world, targetPos);
        if (delta <= 0) return;

        breakProgress += delta;

        mc.player.swingHand(Hand.MAIN_HAND);

        // 🔑 keep sending CONTINUE each tick while breaking
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.CONTINUE_DESTROY_BLOCK, targetPos, face
        ));

        if (breakProgress >= 1f) {
            mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, targetPos, face
            ));
            miningBlock = null;
            breakProgress = 0f;
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
        mc.player.setPitch(90);
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
