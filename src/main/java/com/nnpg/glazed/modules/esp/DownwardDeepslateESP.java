package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownwardDeepslateESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Color of downward-facing deepslate blocks.")
        .defaultValue(new SettingColor(0, 255, 255, 100))
        .build());

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Shape render mode.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to downward-facing deepslate blocks.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer color.")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce detected downward-facing deepslate blocks in chat.")
        .defaultValue(true)
        .build());

    private final SettingGroup sgRange = settings.createGroup("Range");

    private final Setting<Integer> minY = sgRange.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan.")
        .defaultValue(-64)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Integer> maxY = sgRange.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan.")
        .defaultValue(128)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build());

    private final SettingGroup sgThreading = settings.createGroup("Threading");

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("enable-threading")
        .description("Use multi-threading for chunk scanning.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> threadPoolSize = sgThreading.add(new IntSetting.Builder()
        .name("thread-pool-size")
        .description("Number of threads to use.")
        .defaultValue(2)
        .min(1)
        .max(8)
        .sliderRange(1, 8)
        .visible(useThreading::get)
        .build());

    // Storage
    private final Set<BlockPos> downwardDeepslatePositions = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public DownwardDeepslateESP() {
        super(GlazedAddon.esp, "DownwardDeepslateESP", "Highlights deepslate blocks that are facing downward.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;
        downwardDeepslatePositions.clear();

        if (useThreading.get()) threadPool = Executors.newFixedThreadPool(threadPoolSize.get());

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk worldChunk) {
                Runnable task = () -> scanChunk(worldChunk);
                if (useThreading.get()) threadPool.submit(task);
                else task.run();
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }
        downwardDeepslatePositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        Runnable task = () -> scanChunk(event.chunk());
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) threadPool.submit(task);
        else task.run();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        Runnable task = () -> {
            BlockPos pos = event.pos;
            BlockState state = event.newState;

            if (isDownwardDeepslate(state, pos.getY())) {
                if (downwardDeepslatePositions.add(pos) && chat.get()) {
                    info("§b[Downward Deepslate] Found at " + pos.toShortString());
                }
            } else {
                downwardDeepslatePositions.remove(pos);
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) threadPool.submit(task);
        else task.run();
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> chunkFound = new HashSet<>();

        for (int x = cpos.getStartX(); x < cpos.getStartX() + 16; x++) {
            for (int z = cpos.getStartZ(); z < cpos.getStartZ() + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (isDownwardDeepslate(state, y)) {
                        chunkFound.add(pos);
                    }
                }
            }
        }

        downwardDeepslatePositions.removeIf(pos -> {
            ChunkPos cp = new ChunkPos(pos);
            return cp.equals(cpos) && !chunkFound.contains(pos);
        });

        downwardDeepslatePositions.addAll(chunkFound);
    }

    private boolean isDownwardDeepslate(BlockState state, int y) {
        if (y < minY.get() || y > maxY.get()) return false;

        // Only process deepslate-related blocks
        if (!(state.getBlock().toString().toLowerCase().contains("deepslate"))) return false;

        // Check for facing/down
        if (state.contains(Properties.FACING)) {
            Direction facing = state.get(Properties.FACING);
            return facing == Direction.DOWN;
        }

        // Some blocks might use AXIS instead of FACING (e.g. pillars)
        if (state.contains(Properties.AXIS)) {
            Direction.Axis axis = state.get(Properties.AXIS);
            return axis == Direction.Y; // vertical
        }

        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color side = new Color(color.get());
        Color outline = new Color(color.get());
        Color tracerCol = new Color(tracerColor.get());

        for (BlockPos pos : downwardDeepslatePositions) {
            event.renderer.box(pos, side, outline, shapeMode.get(), 0);

            if (tracers.get()) {
                Vec3d blockCenter = Vec3d.ofCenter(pos);
                Vec3d start = playerPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
                event.renderer.line(start.x, start.y, start.z, blockCenter.x, blockCenter.y, blockCenter.z, tracerCol);
            }
        }
    }
}
