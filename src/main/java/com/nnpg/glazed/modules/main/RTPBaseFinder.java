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

        // Smooth rotation to look down before digging
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

        // Falling check → try MLG
        if (mc.player.fallDistance > 3 && mc.player.getVelocity().y < -0.5 && !clutching) {
            if (tryMLG()) clutching = true;
            return;
        }

        // After MLG, pick up water
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

        // Normal digging
        if (digging) {
            mineLookingAt();
            detectBase();

            int y = mc.player.getBlockY();
            if (y <= yMin.get()) {
                sendRtp();
            }
        }
    }

    /**
     * Mine the block the player is currently looking at (up to 5 blocks).
     * Uses interactionManager.attackBlock(...) once when a new target is acquired
     * and then calls updateBlockBreakingProgress(...) every tick so vanilla progress occurs.
     */
    private void mineLookingAt() {
        // Raycast a short distance in the look direction (player is expected to be looking down)
        HitResult result = mc.player.raycast(5.0, 0.0f, false);
        if (!(result instanceof BlockHitResult bhr)) {
            miningBlock = null;
            breakProgress = 0f;
            return;
        }

        BlockPos targetPos = bhr.getBlockPos();
        Direction face = bhr.getSide();

        // Nothing to mine
        if (mc.world.getBlockState(targetPos).isAir()) {
            miningBlock = null;
            breakProgress = 0f;
            return;
        }

        // Equip best available pickaxe (if any)
        int pickSlot = -1;
        for (net.minecraft.item.Item pick : pickaxePriority) {
            int s = findHotbarSlot(pick);
            if (s != -1) {
                pickSlot = s;
                break;
            }
        }
        if (pickSlot != -1) mc.player.getInventory().selectedSlot = pickSlot;

        // Creative: break instantly using interaction manager (client side will reflect instantly)
        if (mc.player.getAbilities().creativeMode) {
            // attackBlock in creative should instantly break on client
            mc.interactionManager.attackBlock(targetPos, face);
            mc.player.swingHand(Hand.MAIN_HAND);
            miningBlock = null;
            breakProgress = 0f;
            return;
        }

        // If this is a new target, start the breaking action
        if (miningBlock == null || !miningBlock.equals(targetPos)) {
            miningBlock = targetPos;
            breakProgress = 0f;

            // Begin breaking (sends start packet internally via interactionManager)
            mc.interactionManager.attackBlock(targetPos, face);
        }

        // Update block breaking progress (vanilla client-side progression & packets)
        mc.interactionManager.updateBlockBreakingProgress(targetPos, face);
        mc.player.swingHand(Hand.MAIN_HAND);

        // If the block became air (broken), reset state
        if (mc.world.getBlockState(targetPos).isAir()) {
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
            // Disconnect with a literal message
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
