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
import net.minecraft.block.Blocks;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Locale;

/**
 * AutoMiner (fixed)
 *
 * - Mines only inside the specified cuboid (corner A/B)
 * - Mines layer-by-layer (top-down)
 * - Rotates to face block, waits until rotation is completed, then walks if needed
 * - Breaks blocks until the entire area is air (skips bedrock)
 * - Visual particles to highlight corners and upcoming targets
 */
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
        .description("Base ticks to hold attack to break block (randomized)")
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

    private final Setting<Boolean> showParticles = sgVisual.add(new BoolSetting.Builder()
        .name("show-particles")
        .description("Use small particles to highlight area and next targets")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> particleIntervalTicks = sgVisual.add(new IntSetting.Builder()
        .name("particle-interval")
        .description("Ticks between particle updates")
        .defaultValue(6)
        .min(1)
        .max(40)
        .build()
    );

    private final Setting<Integer> highlightTargets = sgVisual.add(new IntSetting.Builder()
        .name("highlight-targets")
        .description("How many upcoming targets to mark with particles")
        .defaultValue(6)
        .min(0)
        .max(64)
        .build()
    );

    // runtime
    private BlockPos min = null;
    private BlockPos max = null;
    private final List<BlockPos> targets = new ArrayList<>();
    private int currentTargetIndex = 0;
    private boolean mining = false;
    private int breakHoldTicksLeft = 0;
    private final Random rnd = ThreadLocalRandom.current();
    private int tickCounter = 0;

    // rotation/movement gating
    private double desiredYaw = 0;
    private double desiredPitch = 0;
    private boolean waitingForRotation = false; // wait until facing before move/break
    private final double ROTATION_EPS = 3.0; // degrees tolerance

    public AutoMiner() {
        super(GlazedAddon.CATEGORY, "auto-miner", "Layered area miner — mines everything in the cuboid except bedrock.");
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
        waitingForRotation = false;

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

        // Skip if already air or bedrock or otherwise ignored
        if (mc.world.getBlockState(target).isAir() || !shouldMineTarget(target)) {
            currentTargetIndex++;
            breakHoldTicksLeft = 0;
            waitingForRotation = false;
            return;
        }

        double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(target));

        // If outside reach, rotate first — then only move when facing desired angle
        computeDesiredLook(target);
        if (waitingForRotation) {
            // rotate gradually; movement will start once facing within tolerance
            smoothLook(desiredYaw, desiredPitch);
            if (isFacingDesired()) {
                waitingForRotation = false;
            } else {
                // while rotating, ensure player isn't moving forward
                stopMovement();
                return;
            }
        }

        if (dist > reach.get()) {
            // we are facing desired yaw/pitch (waitingForRotation == false)
            // start moving forward (humanize occasionally with tiny strafes)
            smoothLook(desiredYaw, desiredPitch); // keep facing while walking
            KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);

            if (rnd.nextInt(100) < 6) {
                boolean left = rnd.nextBoolean();
                KeyBinding.setKeyPressed(left ? mc.options.leftKey.getDefaultKey() : mc.options.rightKey.getDefaultKey(), true);
            }
            return;
        } else {
            // within reach: stop movement keys
            stopMovement();
        }

        // Now within reach and facing target. Ensure sneak if chosen
        KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), useSneakWhenMining.get());

        // Continue break hold if present
        if (breakHoldTicksLeft > 0) {
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
            breakHoldTicksLeft--;
            if (breakHoldTicksLeft == 0) {
                KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
                // advance to next target; next tick will verify block removed
                currentTargetIndex++;
            }
            return;
        }

        // Ensure we have a pickaxe
        if (!hasValidTool()) { stop("[AutoMiner] No pickaxe found."); return; }

        // Start a new break action — but first ensure we are facing the exact desired angles
        // If not facing yet, set waitingForRotation and return (we will rotate next tick)
        if (!isFacingDesired()) {
            waitingForRotation = true;
            smoothLook(desiredYaw, desiredPitch);
            return;
        }

        // Start break hold with some randomness
        breakHoldTicksLeft = baseBreakTicks.get() + rnd.nextInt(breakRandomness.get() + 1);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
    }

    // Compute the yaw/pitch to look at block center and set waitingForRotation flag
    private void computeDesiredLook(BlockPos target) {
        Vec3d center = Vec3d.ofCenter(target);
        Vec3d dir = center.subtract(mc.player.getPos());
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double pitch = Math.toDegrees(-Math.asin(dir.y / Math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z)));

        desiredYaw = yaw;
        desiredPitch = pitch;
        waitingForRotation = true; // require rotation completion before moving/breaking
    }

    // Particle helpers (cheap visual feedback)
    private void spawnAreaParticles() {
        if (min == null || max == null || mc.world == null) return;
        // corners
        spawnParticleAt(min);
        spawnParticleAt(new BlockPos(min.getX(), min.getY(), max.getZ()));
        spawnParticleAt(new BlockPos(min.getX(), max.getY(), min.getZ()));
        spawnParticleAt(new BlockPos(min.getX(), max.getY(), max.getZ()));
        spawnParticleAt(new BlockPos(max.getX(), min.getY(), min.getZ()));
        spawnParticleAt(new BlockPos(max.getX(), min.getY(), max.getZ()));
        spawnParticleAt(new BlockPos(max.getX(), max.getY(), min.getZ()));
        spawnParticleAt(max);
    }

    private void spawnTargetParticles() {
        if (mc.world == null) return;
        int count = Math.min(highlightTargets.get(), Math.max(0, targets.size() - currentTargetIndex));
        for (int i = 0; i < count; i++) {
            BlockPos p = targets.get(currentTargetIndex + i);
            spawnParticleAt(p);
        }
    }

    private void spawnParticleAt(BlockPos pos) {
        if (mc.world == null) return;
        mc.world.addParticle(ParticleTypes.END_ROD,
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            0.0, 0.0, 0.0);
    }

    private void smoothLook(double yawWanted, double pitchWanted) {
        if (!rotateSmoothing.get() || mc.player == null) {
            if (mc.player != null) {
                mc.player.setYaw((float) yawWanted);
                mc.player.setPitch((float) pitchWanted);
            }
            return;
        }

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

    private boolean isFacingDesired() {
        float curYaw = mc.player.getYaw();
        float curPitch = mc.player.getPitch();
        float yawDiff = Math.abs(wrapDegrees((float)(desiredYaw - curYaw)));
        float pitchDiff = Math.abs((float)(desiredPitch - curPitch));
        return yawDiff <= ROTATION_EPS && pitchDiff <= ROTATION_EPS;
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
        return new int[] {
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim())
        };
    }

    // Build target list top-down, left-right, front-back (layer-by-layer)
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

    // Now: mine everything except bedrock
    private boolean shouldMineTarget(BlockPos pos) {
        if (mc.world == null) return false;
        var b = mc.world.getBlockState(pos).getBlock();
        if (b == Blocks.BEDROCK) return false; // never mine bedrock
        return true;
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
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!mc.world.getBlockState(p).isAir() && mc.world.getBlockState(p).getBlock() != Blocks.BEDROCK) return false;
                }
            }
        }
        return true;
    }

    private void stopAllKeys() {
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.sneakKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
    }

    // ✅ Added helper to stop only movement keys
    private void stopMovement() {
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.leftKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.rightKey.getDefaultKey(), false);
    }

    private void stop(String message) {
        ChatUtils.warning(message);
        toggle();
    }
}
