package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;

public class TunnelBaseFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetect = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("ESP");

    // General
    private final Setting<Boolean> discordNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("discord-notification")
        .description("Send notification to Discord (requires webhook system).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoWalkMine = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-walk-mine")
        .description("Automatically walk forward and mine when underground (Y between -64 and 0).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rotationSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("rotation-speed")
        .description("How fast yaw turns per tick (degrees).")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> hazardDistance = sgGeneral.add(new IntSetting.Builder()
        .name("hazard-distance")
        .description("Distance to detect lava, water, or drops (blocks).")
        .defaultValue(5)
        .min(1)
        .sliderMax(15)
        .build()
    );

    // Detection
    private final Setting<Integer> baseThreshold = sgDetect.add(new IntSetting.Builder()
        .name("base-threshold")
        .description("How many selected blocks before base is detected.")
        .defaultValue(50)
        .min(1)
        .sliderMax(500)
        .build()
    );

    private final Setting<Boolean> detectChests = sgDetect.add(new BoolSetting.Builder().name("detect-chests").defaultValue(true).build());
    private final Setting<Boolean> detectShulkers = sgDetect.add(new BoolSetting.Builder().name("detect-shulkers").defaultValue(true).build());
    private final Setting<Boolean> detectBarrels = sgDetect.add(new BoolSetting.Builder().name("detect-barrels").defaultValue(true).build());
    private final Setting<Boolean> detectSpawners = sgDetect.add(new BoolSetting.Builder().name("detect-spawners").defaultValue(true).build());
    private final Setting<Boolean> detectFurnaces = sgDetect.add(new BoolSetting.Builder().name("detect-furnaces").defaultValue(false).build());
    private final Setting<Boolean> detectRedstone = sgDetect.add(new BoolSetting.Builder().name("detect-redstone").defaultValue(false).build());

    // ESP colors
    private final Setting<SettingColor> chestColor = sgRender.add(new ColorSetting.Builder().name("chest-color").defaultValue(new SettingColor(255, 165, 0, 80)).build());
    private final Setting<SettingColor> shulkerColor = sgRender.add(new ColorSetting.Builder().name("shulker-color").defaultValue(new SettingColor(255, 0, 255, 80)).build());
    private final Setting<SettingColor> barrelColor = sgRender.add(new ColorSetting.Builder().name("barrel-color").defaultValue(new SettingColor(139, 69, 19, 80)).build());
    private final Setting<SettingColor> spawnerColor = sgRender.add(new ColorSetting.Builder().name("spawner-color").defaultValue(new SettingColor(0, 0, 255, 80)).build());
    private final Setting<SettingColor> furnaceColor = sgRender.add(new ColorSetting.Builder().name("furnace-color").defaultValue(new SettingColor(128, 128, 128, 80)).build());
    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder().name("redstone-color").defaultValue(new SettingColor(255, 0, 0, 80)).build());

    private final Setting<Boolean> espOutline = sgRender.add(new BoolSetting.Builder().name("esp-outline").defaultValue(true).build());

    // State
    private FacingDirection currentDirection;
    private FacingDirection lastDirection; // prevent going backwards
    private boolean rotatingToSafeYaw = false;
    private float targetYaw;
    private int rotationCooldownTicks = 0;

    private boolean hazardActive = false; // prevents multiple rotations on the same hazard

    private final Map<BlockPos, SettingColor> detectedBlocks = new HashMap<>();
    private final int minY = -64;
    private final int maxY = 0;

    public TunnelBaseFinder() {
        super(GlazedAddon.CATEGORY, "TunnelBaseFinder", "Finds tunnel bases with ESP and smart detection.");
    }

    @Override
    public void onActivate() {
        currentDirection = getInitialDirection();
        lastDirection = currentDirection;
        targetYaw = mc.player.getYaw();
        rotationCooldownTicks = 0;
        rotatingToSafeYaw = false;
        hazardActive = false;
        detectedBlocks.clear();
    }

    @Override
    public void onDeactivate() {
        GameOptions options = mc.options;
        options.leftKey.setPressed(false);
        options.rightKey.setPressed(false);
        options.forwardKey.setPressed(false);
        detectedBlocks.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || currentDirection == null) return;

        mc.player.setPitch(2.0f);
        updateYaw();

        if (rotationCooldownTicks > 0) {
            mc.options.forwardKey.setPressed(false);
            rotationCooldownTicks--;
            return;
        }

        if (rotatingToSafeYaw) {
            mc.options.forwardKey.setPressed(false);
            float currentYaw = mc.player.getYaw();
            if (Math.abs(targetYaw - currentYaw) < 1f) {
                mc.player.setYaw(targetYaw);
                rotatingToSafeYaw = false;

                // center player
                BlockPos bp = mc.player.getBlockPos();
                mc.player.setPosition(bp.getX() + 0.5, mc.player.getY(), bp.getZ() + 0.5);

                rotationCooldownTicks = 5;

                mc.options.forwardKey.setPressed(true);
                mineForward();
                hazardActive = true; // finished handling hazard/drop
            }
            return;
        }

        if (autoWalkMine.get()) {
            int y = mc.player.getBlockY();
            if (y <= maxY && y >= minY) {
                if (!hazardActive && (detectHazards() || detectFloorDrop())) {
                    mc.options.forwardKey.setPressed(false);
                    smartAvoid();
                } else {
                    mc.options.forwardKey.setPressed(true);
                    mineForward();

                    if (hazardActive && !(detectHazards() || detectFloorDrop())) {
                        hazardActive = false;
                    }
                }
            } else {
                mc.options.forwardKey.setPressed(false);
            }
        }

        notifyFound();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        detectedBlocks.forEach((pos, color) -> {
            event.renderer.box(pos, color, color, ShapeMode.Both, 0);
        });
    }

    private void updateYaw() {
        float currentYaw = mc.player.getYaw();
        float delta = targetYaw - currentYaw;
        delta = ((delta + 180) % 360 + 360) % 360 - 180;
        float step = rotationSpeed.get();
        if (Math.abs(delta) <= step) {
            mc.player.setYaw(targetYaw);
        } else {
            mc.player.setYaw(currentYaw + Math.signum(delta) * step);
        }
    }

    private void mineForward() {
        if (mc.player == null) return;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos target = playerPos.offset(currentDirection.toMcDirection());

        if (mc.world == null || mc.interactionManager == null) return;
        BlockState state = mc.world.getBlockState(target);

        if (!state.isAir() && state.getBlock() != Blocks.BEDROCK) {
            mc.interactionManager.updateBlockBreakingProgress(target, currentDirection.toMcDirection());
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private boolean detectHazards() {
        BlockPos playerPos = mc.player.getBlockPos();
        int dist = hazardDistance.get();

        for (BlockPos pos : BlockPos.iterateOutwards(playerPos, dist, dist, dist)) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.WATER) {
                return true;
            }
        }
        return false;
    }

    private boolean detectFloorDrop() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos ahead = playerPos.offset(currentDirection.toMcDirection());

        BlockPos floor1 = ahead.down();
        BlockPos floor2 = ahead.down(2);

        return mc.world.getBlockState(floor1).isAir() || mc.world.getBlockState(floor2).isAir();
    }

    /**
     * Smart avoidance system:
     * - Hazards: choose only safe dirs.
     * - Drops: try ±90°, then check new left side to wrap around hole.
     */
    private void smartAvoid() {
        FacingDirection left = turnLeft(currentDirection);
        FacingDirection right = turnRight(currentDirection);

        // Prefer left or right if safe
        if (isSafe(left)) {
            // wrap-around: check left side after turning
            if (isSafe(turnLeft(left))) {
                turnTo(turnLeft(left));
            } else {
                turnTo(left);
            }
        } else if (isSafe(right)) {
            if (isSafe(turnRight(right))) {
                turnTo(turnRight(right));
            } else {
                turnTo(right);
            }
        } else {
            // if both unsafe, turn back
            turnTo(opposite(currentDirection));
        }
    }

    private boolean isSafe(FacingDirection dir) {
        BlockPos ahead = mc.player.getBlockPos().offset(dir.toMcDirection());
        BlockState aheadBlock = mc.world.getBlockState(ahead);

        if (aheadBlock.getBlock() == Blocks.LAVA || aheadBlock.getBlock() == Blocks.WATER) return false;

        BlockPos floor1 = ahead.down();
        BlockPos floor2 = ahead.down(2);

        return !(mc.world.getBlockState(floor1).isAir() || mc.world.getBlockState(floor2).isAir());
    }

    private void turnTo(FacingDirection dir) {
        lastDirection = currentDirection;
        currentDirection = dir;
        targetYaw = getYawForDirection(dir);
        rotatingToSafeYaw = true;
    }

    private FacingDirection getInitialDirection() {
        float yaw = mc.player.getYaw() % 360.0f;
        if (yaw < 0.0f) yaw += 360.0f;

        if (yaw >= 45.0f && yaw < 135.0f) return FacingDirection.WEST;
        if (yaw >= 135.0f && yaw < 225.0f) return FacingDirection.NORTH;
        if (yaw >= 225.0f && yaw < 315.0f) return FacingDirection.EAST;
        return FacingDirection.SOUTH;
    }

    private float getYawForDirection(FacingDirection dir) {
        return switch (dir) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
        };
    }

    // --- Existing ESP + notify logic stays unchanged ---
    private void notifyFound() {
        int storage = 0;
        detectedBlocks.clear();

        int viewDist = mc.options.getViewDistance().getValue();
        BlockPos playerPos = mc.player.getBlockPos();

        for (int dx = -viewDist; dx <= viewDist; dx++) {
            for (int dz = -viewDist; dz <= viewDist; dz++) {
                WorldChunk chunk = mc.world.getChunkManager().getChunk(
                    (playerPos.getX() >> 4) + dx,
                    (playerPos.getZ() >> 4) + dz,
                    ChunkStatus.FULL,
                    false
                );

                if (chunk == null) continue;

                for (BlockPos pos : chunk.getBlockEntityPositions()) {
                    BlockEntity be = mc.world.getBlockEntity(pos);
                    if (be == null) continue;

                    SettingColor color = null;
                    if (detectSpawners.get() && be instanceof MobSpawnerBlockEntity) color = spawnerColor.get();
                    if (detectChests.get() && be instanceof ChestBlockEntity) color = chestColor.get();
                    if (detectBarrels.get() && be instanceof BarrelBlockEntity) color = barrelColor.get();
                    if (detectFurnaces.get() && be instanceof FurnaceBlockEntity) color = furnaceColor.get();
                    if (detectShulkers.get() && be instanceof ShulkerBoxBlockEntity) color = shulkerColor.get();
                    if (detectRedstone.get() && be instanceof PistonBlockEntity) color = redstoneColor.get();

                    if (color != null) {
                        storage++;
                        detectedBlocks.put(pos, color);
                    }
                }
            }
        }

        if (storage > baseThreshold.get()) {
            Vec3d p = mc.player.getPos();
            notifyFound("Base found", (int) p.x, (int) p.y, (int) p.z);
        }
    }

    private void notifyFound(String msg, int x, int y, int z) {
        if (discordNotification.get()) {
            info("[Discord notify] " + msg + " at " + x + " " + y + " " + z);
        }
        disconnectWithMessage(Text.of(msg));
        toggle();
    }

    private void disconnectWithMessage(Text text) {
        if (mc.player != null && mc.player.networkHandler != null) {
            MutableText literal = Text.literal("[TunnelBaseFinder] ").append(text);
            mc.player.networkHandler.getConnection().disconnect(literal);
        }
    }

    private FacingDirection turnLeft(FacingDirection dir) {
        return switch (dir) {
            case NORTH -> FacingDirection.WEST;
            case WEST -> FacingDirection.SOUTH;
            case SOUTH -> FacingDirection.EAST;
            case EAST -> FacingDirection.NORTH;
        };
    }

    private FacingDirection turnRight(FacingDirection dir) {
        return switch (dir) {
            case NORTH -> FacingDirection.EAST;
            case EAST -> FacingDirection.SOUTH;
            case SOUTH -> FacingDirection.WEST;
            case WEST -> FacingDirection.NORTH;
        };
    }

    private FacingDirection opposite(FacingDirection dir) {
        return switch (dir) {
            case NORTH -> FacingDirection.SOUTH;
            case SOUTH -> FacingDirection.NORTH;
            case EAST -> FacingDirection.WEST;
            case WEST -> FacingDirection.EAST;
        };
    }

    enum FacingDirection {
        NORTH, SOUTH, EAST, WEST;

        public Direction toMcDirection() {
            return switch (this) {
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                case EAST -> Direction.EAST;
                case WEST -> Direction.WEST;
            };
        }
    }
}
