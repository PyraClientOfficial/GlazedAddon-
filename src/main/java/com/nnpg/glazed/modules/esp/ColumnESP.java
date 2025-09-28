package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class ColumnESP extends Module {
    private final SettingGroup sgRender = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Detection");

    // Rendering
    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("ESP Color")
        .description("Color for the ESP boxes")
        .defaultValue(new SettingColor(0, 100, 255, 100))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .description("Rendering mode for the ESP boxes")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
        .name("Show Tracers")
        .description("Draw tracer lines to detected columns")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("Tracer Color")
        .description("Color for the tracer lines")
        .defaultValue(new SettingColor(0, 100, 255, 200))
        .visible(showTracers::get)
        .build()
    );

    // Detection
    private final Setting<List<Block>> detectBlocks = sgScan.add(new BlockListSetting.Builder()
        .name("Detect Blocks")
        .description("Blocks that should form columns to be detected")
        .defaultValue(Collections.singletonList(net.minecraft.block.Blocks.OBSIDIAN))
        .build()
    );

    private final Setting<Integer> minColumnLength = sgScan.add(new IntSetting.Builder()
        .name("Min Column Length")
        .description("Minimum vertical length of a column to highlight")
        .defaultValue(5)
        .min(2)
        .max(100)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> minY = sgScan.add(new IntSetting.Builder()
        .name("Min Y")
        .description("Minimum Y level to scan")
        .defaultValue(-64)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Integer> maxY = sgScan.add(new IntSetting.Builder()
        .name("Max Y")
        .description("Maximum Y level to scan")
        .defaultValue(320)
        .min(-64)
        .max(320)
        .sliderRange(-64, 320)
        .build()
    );

    private final Set<BlockPos> detectedColumns = Collections.synchronizedSet(new HashSet<>());

    public ColumnESP() {
        super(GlazedAddon.esp, "ColumnESP", "Detects and highlights vertical block columns.");
    }

    @Override
    public void onActivate() {
        detectedColumns.clear();

        if (mc.world == null) return;

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk worldChunk) {
                scanChunk(worldChunk);
            }
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        scanChunk(event.chunk());
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = Math.max(chunk.getBottomY(), minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> newDetected = new HashSet<>();

        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                int length = 0;
                BlockPos columnStart = null;

                for (int y = yMin; y < yMax; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);

                    if (detectBlocks.get().contains(state.getBlock())) {
                        if (length == 0) columnStart = pos;
                        length++;
                    } else {
                        if (length >= minColumnLength.get() && columnStart != null) {
                            newDetected.add(columnStart);
                        }
                        length = 0;
                        columnStart = null;
                    }
                }

                if (length >= minColumnLength.get() && columnStart != null) {
                    newDetected.add(columnStart);
                }
            }
        }

        detectedColumns.removeIf(pos -> {
            ChunkPos blockChunk = new ChunkPos(pos);
            return blockChunk.equals(cpos) && !newDetected.contains(pos);
        });

        detectedColumns.addAll(newDetected);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color sideColor = new Color(espColor.get());
        Color lineColor = new Color(espColor.get());
        Color tracerColorValue = new Color(tracerColor.get());

        synchronized (detectedColumns) {
            for (BlockPos pos : detectedColumns) {
                event.renderer.box(pos, sideColor, lineColor, shapeMode.get(), 0);

                if (showTracers.get()) {
                    Vec3d blockCenter = Vec3d.ofCenter(pos);

                    Vec3d startPos;
                    if (mc.options.getPerspective().isFirstPerson()) {
                        Vec3d lookDirection = mc.player.getRotationVector();
                        startPos = new Vec3d(
                            playerPos.x + lookDirection.x * 0.5,
                            playerPos.y + mc.player.getEyeHeight(mc.player.getPose()) + lookDirection.y * 0.5,
                            playerPos.z + lookDirection.z * 0.5
                        );
                    } else {
                        startPos = new Vec3d(
                            playerPos.x,
                            playerPos.y + mc.player.getEyeHeight(mc.player.getPose()),
                            playerPos.z
                        );
                    }

                    event.renderer.line(startPos.x, startPos.y, startPos.z,
                        blockCenter.x, blockCenter.y, blockCenter.z, tracerColorValue);
                }
            }
        }
    }
}
