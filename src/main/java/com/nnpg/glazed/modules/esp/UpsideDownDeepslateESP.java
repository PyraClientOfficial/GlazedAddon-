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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpsideDownDeepslateESP extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> deepslateColor = sgGeneral.add(new ColorSetting.Builder()
            .name("esp-color")
            .description("Upside-down deepslate box color")
            .defaultValue(new SettingColor(0, 255, 255, 100))
            .build());

    private final Setting<ShapeMode> deepslateShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("Upside-down deepslate box render mode")
            .defaultValue(ShapeMode.Both)
            .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
            .name("tracers")
            .description("Draw tracers to upside-down deepslate blocks")
            .defaultValue(true)
            .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
            .name("tracer-color")
            .description("Upside-down deepslate tracer color")
            .defaultValue(new SettingColor(0, 255, 255, 200))
            .visible(tracers::get)
            .build());

    private final Setting<Boolean> deepslateChat = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-feedback")
            .description("Announce upside-down deepslate in chat")
            .defaultValue(true)
            .build());

    private final SettingGroup sgRange = settings.createGroup("Range");

    private final Setting<Integer> minY = sgRange.add(new IntSetting.Builder()
            .name("min-y")
            .description("Minimum Y level to scan")
            .defaultValue(-64)
            .min(-64)
            .max(128)
            .sliderRange(-64, 128)
            .build());

    private final Setting<Integer> maxY = sgRange.add(new IntSetting.Builder()
            .name("max-y")
            .description("Maximum Y level to scan")
            .defaultValue(128)
            .min(-64)
            .max(320)
            .sliderRange(-64, 320)
            .build());

    private final SettingGroup sgThreading = settings.createGroup("Threading");

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
            .name("enable-threading")
            .description("Use multi-threading for chunk scanning (better performance)")
            .defaultValue(true)
            .build());

    private final Setting<Integer> threadPoolSize = sgThreading.add(new IntSetting.Builder()
            .name("thread-pool-size")
            .description("Number of threads to use for scanning")
            .defaultValue(2)
            .min(1)
            .max(8)
            .sliderRange(1, 8)
            .visible(useThreading::get)
            .build());

    private final Setting<Boolean> limitChatSpam = sgThreading.add(new BoolSetting.Builder()
            .name("limit-chat-spam")
            .description("Reduce chat spam when using threading")
            .defaultValue(true)
            .visible(useThreading::get)
            .build());

    private final Set<BlockPos> upsideDownDeepslatePositions = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public UpsideDownDeepslateESP() {
        super(GlazedAddon.esp, "UpsideDownDeepslateESP", "ESP for normal deepslate blocks placed upside-down.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;
        if (useThreading.get()) threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        upsideDownDeepslatePositions.clear();

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk worldChunk) {
                if (useThreading.get()) threadPool.submit(() -> scanChunk(worldChunk));
                else scanChunk(worldChunk);
            }
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            threadPool = null;
        }
        upsideDownDeepslatePositions.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) threadPool.submit(() -> scanChunk(event.chunk()));
        else scanChunk(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        Runnable task = () -> {
            boolean upsideDown = isUpsideDownDeepslate(state, pos);
            if (upsideDown) {
                boolean added = upsideDownDeepslatePositions.add(pos);
                if (added && deepslateChat.get() && (!useThreading.get() || !limitChatSpam.get())) {
                    info("§3[§bUpsideDown Deepslate§3] §bNormal Deepslate at " + pos.toShortString());
                }
            } else {
                upsideDownDeepslatePositions.remove(pos);
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) threadPool.submit(task);
        else task.run();
    }

    private void scanChunk(Chunk chunk) {
        if (!(chunk instanceof WorldChunk worldChunk)) return;
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> chunkPositions = new HashSet<>();

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (isUpsideDownDeepslate(state, pos)) chunkPositions.add(pos);
                }
            }
        }

        upsideDownDeepslatePositions.removeIf(pos -> new ChunkPos(pos).equals(cpos) && !chunkPositions.contains(pos));
        upsideDownDeepslatePositions.addAll(chunkPositions);
    }

    private boolean isUpsideDownDeepslate(BlockState state, BlockPos pos) {
        if (!state.isOf(Blocks.DEEPSLATE)) return false;

        // Approximate upside-down placement: air below, solid above (top on bottom)
        BlockState above = mc.world.getBlockState(pos.up());
        BlockState below = mc.world.getBlockState(pos.down());
        return !above.isAir() && below.isAir();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;
        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color side = new Color(deepslateColor.get());
        Color outline = new Color(deepslateColor.get());
        Color tracerCol = new Color(tracerColor.get());

        Vec3d crosshairPos = new Vec3d(
                playerPos.x + mc.player.getRotationVector().x * 2,
                playerPos.y + mc.player.getEyeHeight(mc.player.getPose()) + mc.player.getRotationVector().y * 2,
                playerPos.z + mc.player.getRotationVector().z * 2
        );

        for (BlockPos pos : upsideDownDeepslatePositions) {
            event.renderer.box(pos, side, outline, deepslateShapeMode.get(), 0);

            if (tracers.get()) {
                Vec3d blockCenter = Vec3d.ofCenter(pos);
                // Tracer from crosshair to block
                event.renderer.line(crosshairPos.x, crosshairPos.y, crosshairPos.z,
                        blockCenter.x, blockCenter.y, blockCenter.z, tracerCol);
            }
        }
    }
}
