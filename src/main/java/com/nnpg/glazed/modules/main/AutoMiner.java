package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * Improved AutoMiner:
 * - Mines only inside the specified cuboid (corner A/B)
 * - Loops until the entire area is air
 * - Human-like rotation, delays and safety checks
 *
 * NOTE: No 3D render included (to avoid renderer API mismatches). You can enable a chat "show-area" message.
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

    private final Setting<Boolean> showArea = sgArea.add(new BoolSetting.Builder()
        .name("show-area")
        .description("Print area coordinates in chat on start")
        .defaultValue(true)
        .build()
    );

    // Internal runtime fields
    private BlockPos min = null;
    private BlockPos max = null;
    private final List<BlockPos> targets = new ArrayList<>();
    private int currentTargetIndex = 0;
    private boolean mining = false;
    private int breakHoldTicksLeft = 0;
    private final Random rnd = ThreadLocalRandom.current();

    public AutoMiner() {
        super(GlazedAddon.CATEGORY, "auto-miner", "Human-like auto miner using typed coordinates (snake pattern).");
    }

    @Override
    public void onActivate() {
        if (!parseCorners()) {
            ChatUtils.error("[AutoMiner] Invalid corner coordinates. Use format: x,y,z");
            toggle();
            return;
        }

        buildTargetList();
        currentTargetIndex = 0;
        mining = true;
        breakHoldTicksLeft = 0;

        if (showArea.get()) {
            ChatUtils.info(String.format("[AutoMiner] Area: min=%s max=%s totalTargets=%d", min.toShortString(), max.toShortString(), targets.size()));
        } else {
            ChatUtils.info("[AutoMiner] Started.");
        }
    }

    @Override
    public void onDeactivate() {
        mining = false;
        targets.clear();
        stopAllKeys();
        ChatUtils.info("[AutoMiner] Stopped.");
    }

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

        // If we've consumed all targets in this pass, verify area and either rebuild or stop
        if (currentTargetIndex >= targets.size()) {
            if (isAreaCleared()) {
                ChatUtils.info("[AutoMiner] Area cleared (all air). Stopping.");
                toggle();
                return;
            } else {
                // Rebuild a fresh target list of remaining non-air blocks and continue
                buildTargetList(); // will repopulate targets for remaining blocks
                currentTargetIndex = 0;
                if (targets.isEmpty()) {
                    // double-check - if still empty, stop
                    ChatUtils.info("[AutoMiner] No valid targets after rebuild. Stopping.");
                    toggle();
                    return;
                }
            }
        }

        // Advance to next non-air & matching target if current is invalid
        BlockPos target = targets.get(currentTargetIndex);
        if (mc.world.getBlockState(target).isAir() || !shouldMineTarget(target)) {
            currentTargetIndex++;
            breakHoldTicksLeft = 0;
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
            // Do not attempt to mine until within reach
            return;
        } else {
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        }

        // Sneak option
        KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), useSneakWhenMining.get());

        // Look at block center and start/continue breaking
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
                // avoid Thread.sleep in tick loop; simulate short pause by skipping a tick or two:
                // We'll simply advance index and let next tick validate if block is air.
                currentTargetIndex++;
            }
            return;
        }

        // Need a pickaxe?
        if (!hasValidTool()) {
            ChatUtils.warning("[AutoMiner] No pickaxe found in hotbar. Stopping.");
            toggle();
            return;
        }

        // Start a new break action
        int tickHold = baseBreakTicks.get() + rnd.nextInt(breakRandomness.get() + 1);
        breakHoldTicksLeft = Math.max(1, tickHold);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
    }

    // Smoothly rotate player toward yaw/pitch at configured speed
    private void smoothLook(double yawWanted, double pitchWanted) {
        if (!rotateSmoothing.get() || mc.player == null) {
            if (mc.player != null) {
                mc.player.setYaw((float) yawWanted);
                mc.player.setPitch((float) pitchWanted);
            }
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

    // Build snake-pattern target list between min and max, only non-air and matching filter
    private void buildTargetList() {
        targets.clear();
        if (min == null || max == null || mc.world == null) return;

        int yStart = max.getY();
        int yEnd = min.getY();

        boolean xForward;
        for (int y = yStart; y >= yEnd; y--) {
            xForward = true;
            for (int x = min.getX(); x <= max.getX(); x++) {
                if (xForward) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!mc.world.getBlockState(p).isAir() && shouldMineTarget(p)) targets.add(p);
                    }
                } else {
                    for (int z = max.getZ(); z >= min.getZ(); z--) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!mc.world.getBlockState(p).isAir() && shouldMineTarget(p)) targets.add(p);
                    }
                }
                xForward = !xForward;
            }
        }
    }

    private boolean shouldMineTarget(BlockPos pos) {
        if (mc.world == null) return false;
        net.minecraft.block.Block b = mc.world.getBlockState(pos).getBlock();

        if (mode.get() == Mode.ALL) return true;

        if (mode.get() == Mode.ORES) {
            return isOreBlock(b);
        }

        if (mode.get() == Mode.CUSTOM) {
            String name = b.getName().getString().toLowerCase(Locale.ROOT);
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

    private boolean isOreBlock(net.minecraft.block.Block b) {
        return b == net.minecraft.block.Blocks.COAL_ORE || b == net.minecraft.block.Blocks.IRON_ORE || b == net.minecraft.block.Blocks.COPPER_ORE ||
               b == net.minecraft.block.Blocks.GOLD_ORE || b == net.minecraft.block.Blocks.REDSTONE_ORE || b == net.minecraft.block.Blocks.LAPIS_ORE ||
               b == net.minecraft.block.Blocks.DIAMOND_ORE || b == net.minecraft.block.Blocks.EMERALD_ORE || b == net.minecraft.block.Blocks.NETHER_QUARTZ_ORE ||
               b == net.minecraft.block.Blocks.ANCIENT_DEBRIS || b == net.minecraft.block.Blocks.NETHER_GOLD_ORE;
    }

    private boolean isInventoryFull() {
        for (int i = 0; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st.isEmpty()) return false;
        }
        return true;
    }

    private boolean hasValidTool() {
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st != null && !st.isEmpty()) {
                Item it = st.getItem();
                if (it instanceof PickaxeItem) return true;
            }
        }
        return false;
    }

    private boolean isAreaCleared() {
        if (min == null || max == null || mc.world == null) return true;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!mc.world.getBlockState(p).isAir() && shouldMineTarget(p)) return false;
                }
            }
        }
        return true;
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
