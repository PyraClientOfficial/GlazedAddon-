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
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

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
        .defaultValue(-64)
        .min(-64).max(320).sliderRange(-64, 320)
        .build());

    private final Setting<Integer> maxY = sgRange.add(new IntSetting.Builder()
        .name("max-y")
        .defaultValue(128)
        .min(-64).max(320).sliderRange(-64, 320)
        .build());

    private final SettingGroup sgThreading = settings.createGroup("Threading");

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("enable-threading")
        .defaultValue(true)
        .build());

    private final Setting<Integer> threadPoolSize = sgThreading.add(new IntSetting.Builder()
        .name("thread-pool-size")
        .defaultValue(2)
        .min(1).max(8)
        .sliderRange(1, 8)
        .visible(useThreading::get)
        .build());

    // Storage
    private final Set<BlockPos> downwardDeepslatePositions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ExecutorService threadPool;

    public DownwardDeepslateESP() {
        super(GlazedAddon.esp, "downward-deepslate-esp", "Highlights deepslate blocks that are facing downward.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        downwardDeepslatePositions.clear();

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        // Run async scan for loaded chunks
        for (var chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk worldChunk) {
                submitScan(worldChunk);
            }
        }
    }

    @Override
    public void onDeactivate() {
        downwardDeepslatePositions.clear();

        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    private void submitScan(WorldChunk chunk) {
        Runnable scanTask = () -> scanChunk(chunk);

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(scanTask);
        } else {
            scanTask.run();
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (mc.world == null) return;
        submitScan(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState newState = event.newState;

        if (isDownwardDeepslate(newState, pos.getY())) {
            if (downwardDeepslatePositions.add(pos) && chat.get()) {
                info("§b[Downward Deepslate] Found at " + pos.toShortString());
            }
        } else {
            downwardDeepslatePositions.remove(pos);
        }
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> found = new HashSet<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = yMax; y >= yMin; y--) { // Top-down for consistency
                    BlockPos pos = new BlockPos(cpos.getStartX() + x, y, cpos.getStartZ() + z);
                    BlockState state = chunk.getBlockState(pos);

                    if (isDownwardDeepslate(state, y)) {
                        found.add(pos);
                    }
                }
            }
        }

        downwardDeepslatePositions.removeIf(p -> new ChunkPos(p).equals(cpos) && !found.contains(p));
        downwardDeepslatePositions.addAll(found);
    }

    private boolean isDownwardDeepslate(BlockState state, int y) {
        if (y < minY.get() || y > maxY.get()) return false;
        String blockName = state.getBlock().toString().toLowerCase();

        if (!blockName.contains("deepslate")) return false;

        if (state.contains(Properties.FACING))
            return state.get(Properties.FACING) == Direction.DOWN;

        if (state.contains(Properties.AXIS))
            return state.get(Properties.AXIS) == Direction.Axis.Y;

        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (downwardDeepslatePositions.isEmpty()) return;

        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color side = new Color(color.get());
        Color outline = new Color(color.get());
        Color tracerCol = new Color(tracerColor.get());

        for (BlockPos pos : Set.copyOf(downwardDeepslatePositions)) {
            event.renderer.box(pos, side, outline, shapeMode.get(), 0);

            if (tracers.get()) {
                Vec3d blockCenter = Vec3d.ofCenter(pos);
                Vec3d eyePos = playerPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
                event.renderer.line(eyePos.x, eyePos.y, eyePos.z, blockCenter.x, blockCenter.y, blockCenter.z, tracerCol);
            }
        }
    }
}
