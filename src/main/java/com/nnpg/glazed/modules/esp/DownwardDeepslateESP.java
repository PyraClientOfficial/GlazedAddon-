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

    private final Setting<SettingColor> deepslateColor = sgGeneral.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Downward-facing deepslate box color")
        .defaultValue(new SettingColor(0, 255, 255, 100))
        .build());

    private final Setting<ShapeMode> deepslateShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Downward-facing deepslate box render mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to downward-facing deepslate blocks")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Downward-facing deepslate tracer color")
        .defaultValue(new SettingColor(0, 255, 255, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> deepslateChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce downward-facing deepslate in chat")
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

    private final Set<BlockPos> downwardDeepslatePositions = ConcurrentHashMap.newKeySet();
    private ExecutorService threadPool;

    public DownwardDeepslateESP() {
        super(GlazedAddon.esp, "DownwardDeepslateESP", "ESP for deepslate blocks rotated facing down.");
    }

    @Override
    public void onActivate() {
        if (mc.world == null) return;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadPoolSize.get());
        }

        downwardDeepslatePositions.clear();

        if (useThreading.get()) {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk worldChunk) {
                    threadPool.submit(() -> scanChunkForDownwardDeepslate(worldChunk));
                }
            }
        } else {
            for (Chunk chunk : Utils.chunks()) {
                if (chunk instanceof WorldChunk worldChunk) scanChunkForDownwardDeepslate(worldChunk);
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
        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(() -> scanChunkForDownwardDeepslate(event.chunk()));
        } else {
            scanChunkForDownwardDeepslate(event.chunk());
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        Runnable updateTask = () -> {
            boolean isDownward = isDownwardDeepslate(state, pos.getY());
            if (isDownward) {
                boolean wasAdded = downwardDeepslatePositions.add(pos);
                if (wasAdded && deepslateChat.get() && (!useThreading.get() || !limitChatSpam.get())) {
                    String blockType = getBlockTypeName(state);
                    info("§3[§bDown Deepslate§3] §b" + blockType + " at " + pos.toShortString());
                }
            } else {
                downwardDeepslatePositions.remove(pos);
            }
        };

        if (useThreading.get() && threadPool != null && !threadPool.isShutdown()) {
            threadPool.submit(updateTask);
        } else {
            updateTask.run();
        }
    }

    private void scanChunkForDownwardDeepslate(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> chunkDownwardDeepslate = new HashSet<>();

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (isDownwardDeepslate(state, y)) {
                        chunkDownwardDeepslate.add(pos);
                    }
                }
            }
        }

        downwardDeepslatePositions.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos);
            return blockChunk.equals(cpos) && !chunkDownwardDeepslate.contains(pos);
        });

        downwardDeepslatePositions.addAll(chunkDownwardDeepslate);
    }

    private boolean isDownwardDeepslate(BlockState state, int y) {
        if (y < minY.get() || y > maxY.get()) return false;
        if (!state.contains(Properties.AXIS)) return false;

        Direction.Axis axis = state.get(Properties.AXIS);
        if (axis != Direction.Axis.Y) return false; // only Y axis

        // Optional: you can check actual facing direction if needed (some blocks have rotation)
        // For standard deepslate, axis Y means top-down or bottom-up; we'll assume Y axis means downward rotation.

        return state.isOf(Blocks.DEEPSLATE) ||
               state.isOf(Blocks.POLISHED_DEEPSLATE) ||
               state.isOf(Blocks.DEEPSLATE_BRICKS) ||
               state.isOf(Blocks.DEEPSLATE_TILES) ||
               state.isOf(Blocks.CHISELED_DEEPSLATE);
    }

    private String getBlockTypeName(BlockState state) {
        if (state.isOf(Blocks.DEEPSLATE)) return "Downward Deepslate";
        if (state.isOf(Blocks.POLISHED_DEEPSLATE)) return "Downward Polished Deepslate";
        if (state.isOf(Blocks.DEEPSLATE_BRICKS)) return "Downward Deepslate Bricks";
        if (state.isOf(Blocks.DEEPSLATE_TILES)) return "Downward Deepslate Tiles";
        if (state.isOf(Blocks.CHISELED_DEEPSLATE)) return "Downward Chiseled Deepslate";
        return "Downward Deepslate Block";
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color side = new Color(deepslateColor.get());
        Color outline = new Color(deepslateColor.get());
        Color tracerCol = new Color(tracerColor.get());

        for (BlockPos pos : downwardDeepslatePositions) {
            event.renderer.box(pos, side, outline, deepslateShapeMode.get(), 0);

            if (tracers.get()) {
                Vec3d blockCenter = Vec3d.ofCenter(pos);
                Vec3d startPos = new Vec3d(playerPos.x, playerPos.y + mc.player.getEyeHeight(mc.player.getPose()), playerPos.z);
                event.renderer.line(startPos.x, startPos.y, startPos.z, blockCenter.x, blockCenter.y, blockCenter.z, tracerCol);
            }
        }
    }
}
