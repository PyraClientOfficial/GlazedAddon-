package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * AutoMiner
 * - Settings allow entering two coords (x,y,z) as strings "x,y,z" for corner A and corner B.
 * - Modes: ALL, ORES, CUSTOM (list of block IDs/names).
 * - Walks to area, mines blocks in a snake pattern, uses simple human-like rotation & delays.
 * - Protection: stops on hurt, low health, inventory full, tool missing/broken.
 *
 * NOTE: This is deliberately simple path-wise (no baritone). It moves by pressing forward
 * while steering to the target block center. If you want real pathfinding, integrate with a
 * pathfinder library.
 */
public class AutoMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArea = settings.createGroup("Area");
    private final SettingGroup sgBehavior = settings.createGroup("Behavior");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<String> cornerA = sgArea.add(new StringSetting.Builder()
        .name("corner-a")
        .description("Corner A coordinates as x,y,z")
        .defaultValue("0,0,0")
        .build()
    );

    private final Setting<String> cornerB = sgArea.add(new StringSetting.Builder()
        .name("corner-b")
        .description("Corner B coordinates as x,y,z")
        .defaultValue("10,5,10")
        .build()
    );

    private final Setting<Mode> mode = sgBehavior.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Which blocks to mine")
        .defaultValue(Mode.ALL)
        .build()
    );

    private final Setting<List<String>> customBlocks = sgBehavior.add(new StringListSetting.Builder()
        .name("custom-blocks")
        .description("When mode is CUSTOM, these block registry names (or simple names) will be mined")
        .defaultValue(new ArrayList<>())
        .visible(() -> mode.get() == Mode.CUSTOM)
        .build()
    );

    private final Setting<Double> reach = sgBehavior.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Distance to target block to start mining")
        .defaultValue(4.5)
        .min(1.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Integer> baseBreakTicks = sgBehavior.add(new IntSetting.Builder()
        .name("base-break-ticks")
        .description("Base number of ticks to hold attack to break block (randomized)")
        .defaultValue(6)
        .min(1)
        .max(200)
        .build()
    );

    private final Setting<Integer> breakRandomness = sgBehavior.add(new IntSetting.Builder()
        .name("break-randomness")
        .description("Added random ticks to simulate human variation")
        .defaultValue(4)
        .min(0)
        .max(40)
        .build()
    );

    private final Setting<Boolean> rotateSmoothing = sgBehavior.add(new BoolSetting.Builder()
        .name("rotate-smoothing")
        .description("Smooth rotations to look more human")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rotateSpeed = sgBehavior.add(new DoubleSetting.Builder()
        .name("rotate-speed")
        .description("How quickly to rotate towards a target (degrees per tick)")
        .defaultValue(20.0)
        .min(1.0)
        .max(180.0)
        .build()
    );

    private final Setting<Double> healthThreshold = sgSafety.add(new DoubleSetting.Builder()
        .name("stop-health")
        .description("Stop mining if health drops below this value")
        .defaultValue(8.0)
        .min(0.0)
        .max(20.0)
        .build()
    );

    private final Setting<Boolean> stopOnHurt = sgSafety.add(new BoolSetting.Builder()
        .name("stop-on-hurt")
        .description("Stop immediately if player is damaged")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopIfInventoryFull = sgSafety.add(new BoolSetting.Builder()
        .name("stop-if-inv-full")
        .description("Stop if player's inventory becomes full")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useSneakWhenMining = sgSafety.add(new BoolSetting.Builder()
        .name("sneak-when-mining")
        .description("Sneak while mining to be safer (optional)")
        .defaultValue(false)
        .build()
    );

    // Internal runtime fields
    private BlockPos min = null;
    private BlockPos max = null;
    private final List<BlockPos> targets = new ArrayList<>();
    private int currentTargetIndex = 0;
    private boolean mining = false;
    private int breakHoldTicksLeft = 0;
    private Random rnd = ThreadLocalRandom.current();
    private float targetYaw = 0f;
    private float targetPitch = 0f;

    public AutoMiner() {
        super(GlazedAddon.CATEGORY, "auto-miner", "Human-like auto miner using typed coordinates (snake pattern).");
    }

    @Override
    public void onActivate() {
        // parse coords
        if (!parseCorners()) {
            ChatUtils.error("[AutoMiner] Invalid corner coordinates. Use format: x,y,z");
            toggle();
            return;
        }

        buildTargetList();
        currentTargetIndex = 0;
        mining = true;
        breakHoldTicksLeft = 0;
        ChatUtils.info("[AutoMiner] Started. Targets: " + targets.size());
    }

    @Override
    public void onDeactivate() {
        mining = false;
        targets.clear();
        stopAllKeys();
        ChatUtils.info("[AutoMiner] Stopped.");
    }

    // Tick handler - main loop
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!mining || mc.player == null || mc.world == null) return;

        // Safety checks
        if (stopOnHurt.get() && mc.player.hurtTime > 0) {
            ChatUtils.warning("[AutoMiner] Stopping: player took damage.");
            toggle();
            return;
        }

        if (mc.player.getHealth() <= healthThreshold.get()) {
            ChatUtils.warning("[AutoMiner] Stopping: health <= threshold.");
            toggle();
            return;
        }

        if (stopIfInventoryFull.get() && isInventoryFull()) {
            ChatUtils.warning("[AutoMiner] Stopping: inventory full.");
            toggle();
            return;
        }

        // If no more targets, stop
        if (currentTargetIndex >= targets.size()) {
            ChatUtils.info("[AutoMiner] Area cleared. Stopping.");
            toggle();
            return;
        }

        BlockPos target = targets.get(currentTargetIndex);

        // If block is already air (broken), advance
        if (mc.world.getBlockState(target).isAir()) {
            currentTargetIndex++;
            breakHoldTicksLeft = 0;
            return;
        }

        // If block doesn't match filter (in certain modes), skip
        if (!shouldMineTarget(target)) {
            currentTargetIndex++;
            return;
        }

        // Move to within reach
        double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(target));
        if (dist > reach.get()) {
            // walk towards block center
            Vec3d dir = Vec3d.ofCenter(target).subtract(mc.player.getPos()).normalize();
            double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
            double pitch = Math.toDegrees(-Math.asin(dir.y));
            smoothLook(yaw, pitch);
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
            // small chance to strafe or jump to appear human - keep conservative
            return;
        } else {
            // within reach — stop moving forward
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        }

        // Sneak option
        if (useSneakWhenMining.get()) {
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), true);
        } else {
            KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);
        }

        // Look at block center and start breaking
        Vec3d center = Vec3d.ofCenter(target);
        double dx = center.x - mc.player.getX();
        double dz = center.z - mc.player.getZ();
        double dy = center.y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        smoothLook(yaw, pitch);

        // If currently in break-hold, continue
        if (breakHoldTicksLeft > 0) {
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
            breakHoldTicksLeft--;
            if (breakHoldTicksLeft == 0) {
                KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
                // small random delay before considering block broken
                int pause = rnd.nextInt(1 + breakRandomness.get());
                try { Thread.sleep(Math.min(150, pause * 10L)); } catch (Exception ignored) {}
                // advance - next tick we'll check if block is broken, otherwise we will attempt again
                currentTargetIndex++;
            }
            return;
        }

        // Start a new break action if we have a valid pickaxe (or hand if allowed)
        if (!hasValidTool()) {
            ChatUtils.warning("[AutoMiner] No pickaxe found in hotbar. Stopping.");
            toggle();
            return;
        }

        // Decide break duration and start holding attack
        int tickHold = baseBreakTicks.get() + rnd.nextInt(breakRandomness.get() + 1);
        breakHoldTicksLeft = Math.max(1, tickHold);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
    }

    // Smoothly rotate player toward yaw/pitch at configured speed
    private void smoothLook(double yawWanted, double pitchWanted) {
        if (!rotateSmoothing.get()) {
            mc.player.setYaw((float) yawWanted);
            mc.player.setPitch((float) pitchWanted);
            return;
        }

        // clamp
        float curYaw = mc.player.getYaw();
        float curPitch = mc.player.getPitch();
        double maxChange = rotateSpeed.get();

        float yawDiff = wrapDegrees((float) (yawWanted - curYaw));
        float pitchDiff = (float) (pitchWanted - curPitch);

        float newYaw = curYaw + clamp(yawDiff, (float)-maxChange, (float)maxChange);
        float newPitch = curPitch + clamp(pitchDiff, (float)-maxChange, (float)maxChange);

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static float wrapDegrees(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    // parse corner strings to BlockPos min/max
    private boolean parseCorners() {
        try {
            int[] a = parseCoordString(cornerA.get());
            int[] b = parseCoordString(cornerB.get());
            min = new BlockPos(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]));
            max = new BlockPos(Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int[] parseCoordString(String s) {
        String[] parts = s.trim().split(",");
        if (parts.length != 3) throw new IllegalArgumentException();
        return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()) };
    }

    // Build snake-pattern target list between min and max
    private void buildTargetList() {
        targets.clear();
        if (min == null || max == null) return;

        // Decide ordering: top-down or bottom-up? We'll go top-down (so we don't cause falling)
        int yStart = max.getY();
        int yEnd = min.getY();

        boolean xForward;
        for (int y = yStart; y >= yEnd; y--) {
            xForward = true;
            for (int x = min.getX(); x <= max.getX(); x++) {
                if (xForward) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        targets.add(new BlockPos(x, y, z));
                    }
                } else {
                    for (int z = max.getZ(); z >= min.getZ(); z--) {
                        targets.add(new BlockPos(x, y, z));
                    }
                }
                xForward = !xForward;
            }
        }

        // Optionally shuffle a little to humanize (we won't shuffle because we want full coverage)
    }

    private boolean shouldMineTarget(BlockPos pos) {
        Block b = mc.world.getBlockState(pos).getBlock();
        if (mode.get() == Mode.ALL) return true;

        if (mode.get() == Mode.ORES) {
            return isOreBlock(b);
        }

        if (mode.get() == Mode.CUSTOM) {
            String name = b.getName().getString().toLowerCase(Locale.ROOT);
            // simple contains match against custom list
            for (String s : customBlocks.get()) {
                if (s == null) continue;
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) continue;
                if (name.contains(t) || b.getTranslationKey().toLowerCase(Locale.ROOT).contains(t) || b.toString().toLowerCase(Locale.ROOT).contains(t))
                    return true;
            }
            return false;
        }

        return false;
    }

    private boolean isOreBlock(Block b) {
        return b == Blocks.COAL_ORE || b == Blocks.IRON_ORE || b == Blocks.COPPER_ORE ||
               b == Blocks.GOLD_ORE || b == Blocks.REDSTONE_ORE || b == Blocks.LAPIS_ORE ||
               b == Blocks.DIAMOND_ORE || b == Blocks.EMERALD_ORE || b == Blocks.NETHER_QUARTZ_ORE ||
               b == Blocks.ANCIENT_DEBRIS || b == Blocks.NETHER_GOLD_ORE;
    }

    private boolean isInventoryFull() {
        // check main inventory slots 9-35 (player inventory excluding hotbar)
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st.isEmpty()) return false;
        }
        return true;
    }

    private boolean hasValidTool() {
        // look in hotbar for a pickaxe
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st != null && !st.isEmpty()) {
                Item it = st.getItem();
                if (it instanceof PickaxeItem) return true;
            }
        }
        return false;
    }

    private void stopAllKeys() {
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);
    }

    // Modes
    public enum Mode {
        ALL,
        ORES,
        CUSTOM
    }
}
