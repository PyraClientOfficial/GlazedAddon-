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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Setting<Boolean> chatFeedback = sgRender.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Announce column detections in chat")
        .defaultValue(true)
        .build()
    );

    // Detection
    // NOTE: BlockListSetting was used in earlier versions; if unavailable replace with a ListSetting or other.
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
        .max(200)
        .sliderMax(200)
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

    // Column record stores bottom-most BlockPos and length (number of blocks)
    private static final class Column {
        public final BlockPos start; // bottom block position (inclusive)
        public final int length; // number of contiguous blocks upwards
        public final Block block;

        public Column(BlockPos start, int length, Block block) {
            this.start = start;
            this.length = length;
            this.block = block;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Column)) return false;
            Column other = (Column) o;
            return length == other.length && start.equals(other.start) && block == other.block;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, length, block);
        }

        @Override
        public String toString() {
            return "Column{" + start.toShortString() + " len=" + length + " block=" + block + '}';
        }
    }

    // Map chunk -> columns inside that chunk
    private final Map<ChunkPos, Set<Column>> columnsByChunk = new ConcurrentHashMap<>();
    // columns that we've already notified about (to avoid chat spam)
    private final Set<Column> notified = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public ColumnESP() {
        super(GlazedAddon.esp, "ColumnESP", "Detects and highlights vertical block columns.");
    }

    @Override
    public void onActivate() {
        columnsByChunk.clear();

        if (mc.world == null) return;

        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk worldChunk) scanChunk(worldChunk);
        }
    }

    @Override
    public void onDeactivate() {
        columnsByChunk.clear();
        notified.clear();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (event.chunk() instanceof WorldChunk wc) scanChunk(wc);
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkPos cpos = chunk.getPos();
        int xStart = cpos.getStartX();
        int zStart = cpos.getStartZ();
        int yMin = Math.max(chunk.getBottomY(), this.minY.get());
        int yMax = Math.min(chunk.getBottomY() + chunk.getHeight(), this.maxY.get());

        Set<Column> newDetected = new HashSet<>();

        // scan columns in chunk
        for (int x = xStart; x < xStart + 16; x++) {
            for (int z = zStart; z < zStart + 16; z++) {
                int y = yMin;
                while (y < yMax) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    Block b = state.getBlock();

                    if (detectBlocks.get().contains(b)) {
                        // found bottom of a run (ensure it's the bottom-most contiguous block)
                        int startY = y;
                        int length = 0;
                        while (y < yMax && chunk.getBlockState(new BlockPos(x, y, z)).isOf(b)) {
                            length++;
                            y++;
                        }

                        if (length >= minColumnLength.get()) {
                            Column col = new Column(new BlockPos(x, startY, z), length, b);
                            newDetected.add(col);
                        }
                    } else {
                        y++;
                    }
                }
            }
        }

        // previous set for this chunk
        Set<Column> old = columnsByChunk.getOrDefault(cpos, Collections.emptySet());

        // Determine added columns (newDetected - old)
        for (Column col : newDetected) {
            if (!old.contains(col)) {
                // notify once
                if (chatFeedback.get() && !notified.contains(col)) {
                    info("§aColumnESP§f: Suspicious column at %s length=%d", col.start.toShortString(), col.length);
                    notified.add(col);
                }
            }
        }

        // Determine removed columns (old - newDetected) and cleanup notifications
        for (Column col : old) {
            if (!newDetected.contains(col)) {
                notified.remove(col);
            }
        }

        columnsByChunk.put(cpos, newDetected);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Vec3d playerPos = mc.player.getLerpedPos(event.tickDelta);
        Color sideColor = new Color(espColor.get());
        Color lineColor = new Color(espColor.get());
        Color tracerColorValue = new Color(tracerColor.get());

        // Flatten and render all columns
        for (Map.Entry<ChunkPos, Set<Column>> e : columnsByChunk.entrySet()) {
            for (Column col : e.getValue()) {
                // Re-validate column before rendering (world may have changed)
                if (!isColumnStillValid(col)) continue;

                BlockPos start = col.start;
                int length = col.length;

                // draw tall box (coordinates: minX,minY,minZ -> maxX,maxY,maxZ)
                double minX = start.getX();
                double minYd = start.getY();
                double minZ = start.getZ();

                double maxX = start.getX() + 1.0;
                double maxYd = start.getY() + length; // endY + 1
                double maxZ = start.getZ() + 1.0;

                event.renderer.box(minX, minYd, minZ, maxX, maxYd, maxZ, sideColor, lineColor, shapeMode.get(), 0);

                if (showTracers.get()) {
                    double midY = start.getY() + (length / 2.0);
                    Vec3d columnCenter = new Vec3d(start.getX() + 0.5, midY, start.getZ() + 0.5);

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

                    event.renderer.line(startPos.x, startPos.y, startPos.z, columnCenter.x, columnCenter.y, columnCenter.z, tracerColorValue);
                }
            }
        }
    }

    // Ensure every block in the column is still the expected block
    private boolean isColumnStillValid(Column col) {
        if (mc.world == null) return false;
        Block b = col.block;
        BlockPos start = col.start;
        for (int i = 0; i < col.length; i++) {
            BlockPos p = start.up(i);
            if (!mc.world.getBlockState(p).isOf(b)) return false;
        }
        return true;
    }
}
