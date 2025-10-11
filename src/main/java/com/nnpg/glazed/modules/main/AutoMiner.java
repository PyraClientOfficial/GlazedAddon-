package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Locale;

public class AutoMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArea = settings.createGroup("Area");
    private final SettingGroup sgBehavior = settings.createGroup("Behavior");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgVisual = settings.createGroup("Visual");

    private final Setting<String> cornerA = sgArea.add(new StringSetting.Builder()
        .name("corner-a")
        .description("Corner A coordinates (x,y,z)")
        .defaultValue("0,0,0")
        .build()
    );

    private final Setting<String> cornerB = sgArea.add(new StringSetting.Builder()
        .name("corner-b")
        .description("Corner B coordinates (x,y,z)")
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
        .description("If mode=CUSTOM, these block names will be mined.")
        .defaultValue(new ArrayList<>())
        .visible(() -> mode.get() == Mode.CUSTOM)
        .build()
    );

    private final Setting<Double> reach = sgBehavior.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Distance to target block")
        .defaultValue(4.5)
        .min(1.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Integer> baseBreakTicks = sgBehavior.add(new IntSetting.Builder()
        .name("base-break-ticks")
        .defaultValue(6)
        .min(1)
        .max(200)
        .build()
    );

    private final Setting<Integer> breakRandomness = sgBehavior.add(new IntSetting.Builder()
        .name("break-randomness")
        .defaultValue(4)
        .min(0)
        .max(40)
        .build()
    );

    private final Setting<Boolean> rotateSmoothing = sgBehavior.add(new BoolSetting.Builder()
        .name("rotate-smoothing")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rotateSpeed = sgBehavior.add(new DoubleSetting.Builder()
        .name("rotate-speed")
        .defaultValue(20.0)
        .min(1.0)
        .max(180.0)
        .build()
    );

    private final Setting<Double> healthThreshold = sgSafety.add(new DoubleSetting.Builder()
        .name("stop-health")
        .defaultValue(8.0)
        .min(0.0)
        .max(20.0)
        .build()
    );

    private final Setting<Boolean> stopOnHurt = sgSafety.add(new BoolSetting.Builder()
        .name("stop-on-hurt")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> stopIfInventoryFull = sgSafety.add(new BoolSetting.Builder()
        .name("stop-if-inv-full")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useSneakWhenMining = sgSafety.add(new BoolSetting.Builder()
        .name("sneak-when-mining")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showArea = sgArea.add(new BoolSetting.Builder()
        .name("show-area")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showParticles = sgVisual.add(new BoolSetting.Builder()
        .name("show-particles")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> particleIntervalTicks = sgVisual.add(new IntSetting.Builder()
        .name("particle-interval")
        .defaultValue(6)
        .min(1)
        .max(40)
        .build()
    );

    private final Setting<Integer> highlightTargets = sgVisual.add(new IntSetting.Builder()
        .name("highlight-targets")
        .defaultValue(6)
        .min(0)
        .max(64)
        .build()
    );

    private BlockPos min = null;
    private BlockPos max = null;
    private final List<BlockPos> targets = new ArrayList<>();
    private int currentTargetIndex = 0;
    private boolean mining = false;
    private int breakHoldTicksLeft = 0;
    private final Random rnd = ThreadLocalRandom.current();
    private int tickCounter = 0;
    private boolean walking = false;

    public AutoMiner() {
        super(GlazedAddon.CATEGORY, "auto-miner", "Mines a defined cuboid area intelligently.");
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
        tickCounter = 0;
        walking = false;

        if (showArea.get()) {
            ChatUtils.info(String.format("[AutoMiner] Area: min=%s max=%s total=%d",
                min.toShortString(), max.toShortString(), targets.size()));
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
        tickCounter++;

        // Safety
        if (stopOnHurt.get() && mc.player.hurtTime > 0) { stop("[AutoMiner] Stopped: player took damage."); return; }
        if (mc.player.getHealth() <= healthThreshold.get()) { stop("[AutoMiner] Stopped: low health."); return; }
        if (stopIfInventoryFull.get() && isInventoryFull()) { stop("[AutoMiner] Stopped: inventory full."); return; }

        // Rebuild targets if done
        if (currentTargetIndex >= targets.size()) {
            if (isAreaCleared()) { stop("[AutoMiner] Area cleared."); return; }
            buildTargetList();
            currentTargetIndex = 0;
            return;
        }

        // Visual particles
        if (showParticles.get() && tickCounter % particleIntervalTicks.get() == 0) {
            spawnAreaParticles();
            spawnTargetParticles();
        }

        BlockPos target = targets.get(currentTargetIndex);

        // Skip if already air or doesn't match filter
        if (mc.world.getBlockState(target).isAir() || !shouldMineTarget(target)) {
            currentTargetIndex++;
            breakHoldTicksLeft = 0;
            return;
        }

        double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(target));

        // Walk only when needed (outside reach)
        if (dist > reach.get()) {
            moveToward(target);
            return;
        } else {
            stopMovement();
        }

        // Face target
        lookAt(target);

        // Sneak while mining if enabled
        KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), useSneakWhenMining.get());

        // Continue break hold if present
        if (breakHoldTicksLeft > 0) {
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
            breakHoldTicksLeft--;
            if (breakHoldTicksLeft == 0) {
                KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
                // Let next tick validate block is gone; advance index to avoid re-holding same block
                currentTargetIndex++;
            }
            return;
        }

        // Ensure we have a pickaxe
        if (!hasValidTool()) { stop("[AutoMiner] No pickaxe found."); return; }

        // Start new break action with randomization
        breakHoldTicksLeft = baseBreakTicks.get() + rnd.nextInt(breakRandomness.get() + 1);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
    }

    private void spawnAreaParticles() {
        if (min == null || max == null || mc.world == null) return;
        BlockPos[] corners = {
            min, max,
            new BlockPos(min.getX(), min.getY(), max.getZ()),
            new BlockPos(min.getX(), max.getY(), min.getZ()),
            new BlockPos(max.getX(), min.getY(), min.getZ()),
            new BlockPos(max.getX(), max.getY(), min.getZ()),
            new BlockPos(min.getX(), max.getY(), max.getZ()),
            new BlockPos(max.getX(), min.getY(), max.getZ())
        };
        for (BlockPos pos : corners) spawnParticleAt(pos);
    }

    private void spawnTargetParticles() {
        if (mc.world == null) return;
        int count = Math.min(highlightTargets.get(), Math.max(0, targets.size() - currentTargetIndex));
        for (int i = 0; i < count; i++) {
            BlockPos p = targets.get(currentTargetIndex + i);
            spawnParticleAt(new BlockPos(p.getX(), p.getY(), p.getZ()));
        }
    }

    private void spawnParticleAt(BlockPos pos) {
        if (mc.world == null) return;
        mc.world.addParticle(ParticleTypes.END_ROD,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            0.0, 0.0, 0.0);
    }

    private void moveToward(BlockPos target) {
        Vec3d dir = Vec3d.ofCenter(target).subtract(mc.player.getPos()).normalize();
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double pitch = Math.toDegrees(-Math.asin(dir.y));
        smoothLook(yaw, pitch);
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
        walking = true;

        // occasional short strafe for humanization
        if (rnd.nextInt(100) < 6) {
            boolean left = rnd.nextBoolean();
            KeyBinding.setKeyPressed(left ? mc.options.leftKey.getDefaultKey() : mc.options.rightKey.getDefaultKey(), true);
        }
    }

    private void stopMovement() {
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
        walking = false;
    }

    private void lookAt(BlockPos target) {
        Vec3d center = Vec3d.ofCenter(target);
        double dx = center.x - mc.player.getX();
        double dz = center.z - mc.player.getZ();
        double dy = center.y - mc.player.getEyeY();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        smoothLook(yaw, pitch);
    }

    private void smoothLook(double yawWanted, double pitchWanted) {
        if (!rotateSmoothing.get() || mc.player == null) {
            mc.player.setYaw((float) yawWanted);
            mc.player.setPitch((float) pitchWanted);
            return;
        }

        float curYaw = mc.player.getYaw();
        float curPitch = mc.player.getPitch();
        double maxChange = rotateSpeed.get();

        float yawDiff = wrapDegrees((float) (yawWanted - curYaw));
        float pitchDiff = (float) (pitchWanted - curPitch);

        mc.player.setYaw(curYaw + clamp(yawDiff, (float) -maxChange, (float) maxChange));
        mc.player.setPitch(curPitch + clamp(pitchDiff, (float) -maxChange, (float) maxChange));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float wrapDegrees(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

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
        return new int[]{
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim())
        };
    }

    private void buildTargetList() {
        targets.clear();
        if (min == null || max == null || mc.world == null) return;

        for (int y = max.getY(); y >= min.getY(); y--) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!mc.world.getBlockState(p).isAir() && shouldMineTarget(p)) targets.add(p);
                }
            }
        }
    }

    private boolean shouldMineTarget(BlockPos pos) {
        if (mc.world == null) return false;
        var b = mc.world.getBlockState(pos).getBlock();

        if (mode.get() == Mode.ALL) return true;
        if (mode.get() == Mode.ORES) return isOreBlock(b);

        if (mode.get() == Mode.CUSTOM) {
            String name = b.getName().getString().toLowerCase(Locale.ROOT);
            for (String s : customBlocks.get()) {
                if (s != null && !s.trim().isEmpty()) {
                    String t = s.trim().toLowerCase(Locale.ROOT);
                    if (name.contains(t)) return true;
                }
            }
        }
        return false;
    }

    private boolean isOreBlock(net.minecraft.block.Block b) {
        return b == net.minecraft.block.Blocks.COAL_ORE ||
               b == net.minecraft.block.Blocks.IRON_ORE ||
               b == net.minecraft.block.Blocks.COPPER_ORE ||
               b == net.minecraft.block.Blocks.GOLD_ORE ||
               b == net.minecraft.block.Blocks.REDSTONE_ORE ||
               b == net.minecraft.block.Blocks.LAPIS_ORE ||
               b == net.minecraft.block.Blocks.DIAMOND_ORE ||
               b == net.minecraft.block.Blocks.EMERALD_ORE ||
               b == net.minecraft.block.Blocks.NETHER_QUARTZ_ORE ||
               b == net.minecraft.block.Blocks.ANCIENT_DEBRIS ||
               b == net.minecraft.block.Blocks.NETHER_GOLD_ORE;
    }

    private boolean isInventoryFull() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean hasValidTool() {
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty() && st.getItem() instanceof PickaxeItem) return true;
        }
        return false;
    }

    private boolean isAreaCleared() {
        if (min == null || max == null || mc.world == null) return true;
        for (int x = min.getX(); x <= max.getX(); x++)
            for (int y = min.getY(); y <= max.getY(); y++)
                for (int z = min.getZ(); z <= max.getZ(); z++)
                    if (!mc.world.getBlockState(new BlockPos(x, y, z)).isAir())
                        return false;
        return true;
    }

    private void stopAllKeys() {
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
    }

    private void stop(String message) {
        ChatUtils.warning(message);
        toggle();
    }

    public enum Mode {
        ALL, ORES, CUSTOM
    }
}
