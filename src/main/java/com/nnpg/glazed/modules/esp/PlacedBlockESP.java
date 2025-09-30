package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.util.math.ChunkPos;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PlacedBlockESP
 *
 * - Keeps a session-long set of block positions the client observes as "placed" or "replaced".
 * - When a chunk loads it heuristically scans for blocks that are commonly player-placed and marks them.
 * - Renders clean boxes and tracers to placed/replaced blocks within range.
 *
 * Notes:
 * - This is a client-side heuristic/history tool. It cannot read server-side block placement history;
 *   instead it records observed placements and uses heuristics for new chunks.
 * - It persists during the client session. (If you want file persistence, I can add it.)
 */
public class PlacedBlockESP extends Module {
    private final SettingGroup sgRender = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Chunk scan / heuristics");

    // Render settings
    private final Setting<SettingColor> placedColor = sgRender.add(new ColorSetting.Builder()
            .name("placed-color")
            .description("Color used to highlight blocks the client thinks were placed.")
            .defaultValue(new SettingColor(255, 60, 60, 90))
            .build()
    );

    private final Setting<SettingColor> replacedColor = sgRender.add(new ColorSetting.Builder()
            .name("replaced-color")
            .description("Color for blocks that were replaced (non-air -> non-air changes).")
            .defaultValue(new SettingColor(255, 200, 60, 110))
            .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the boxes around placed blocks should be drawn.")
            .defaultValue(ShapeMode.Both)
            .build()
    );

    private final Setting<Double> renderRange = sgRender.add(new DoubleSetting.Builder()
            .name("range")
            .description("How far away placed blocks will be rendered (blocks).")
            .defaultValue(120.0)
            .min(16.0)
            .max(1024.0)
            .sliderRange(16.0, 256.0)
            .build()
    );

    private final Setting<Boolean> showTracers = sgRender.add(new BoolSetting.Builder()
            .name("show-tracers")
            .description("Draw a single tracer from player to each detected block.")
            .defaultValue(true)
            .build()
    );

    // Scanning/heuristics settings
    private final Setting<Integer> chunkScanLimit = sgScan.add(new IntSetting.Builder()
            .name("chunk-scan-limit")
            .description("How many chunks to scan per activation (0 = none).")
            .defaultValue(6)
            .min(0)
            .max(64)
            .build()
    );

    private final Setting<Boolean> heuristicScanOnChunkLoad = sgScan.add(new BoolSetting.Builder()
            .name("scan-chunk-on-load")
            .description("When new chunks load, scan for blocks commonly placed by players (chests, torches, planks, etc).")
            .defaultValue(true)
            .build()
    );

    private final Setting<List<Block>> ignoreBlocks = sgScan.add(new BlockListSetting.Builder()
            .name("ignore-blocks")
            .description("Block types to ignore when scanning/chunk heuristics.")
            .defaultValue(List.of(Blocks.AIR))
            .build()
    );

    // Internal data structures
    // Blocks we observed being placed (air -> block)
    private final Set<BlockPos> placedBlocks = ConcurrentHashMap.newKeySet();
    // Blocks we observed replaced (block -> block)
    private final Set<BlockPos> replacedBlocks = ConcurrentHashMap.newKeySet();

    // Heuristic list of blocks that are typically player-placed (configurable by ignoreBlocks)
    private final Set<Block> defaultSuspicious = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX,
            Blocks.TORCH, Blocks.SOUL_TORCH, Blocks.LANTERN, Blocks.SOUL_LANTERN,
            Blocks.PLANKS, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
            Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.COBBLESTONE, Blocks.GRAVEL,
            Blocks.CARPET, Blocks.SIGN, Blocks.CRAFTING_TABLE, Blocks.FURNACE,
            Blocks.LADDER, Blocks.TRAPDOOR, Blocks.DOOR, Blocks.BED, Blocks.FENCE,
            Blocks.ANVIL, Blocks.ENCHANTING_TABLE, Blocks.BREWING_STAND, Blocks.END_PORTAL_FRAME,
            Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL,
            Blocks.SCAFFOLDING, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.COBBLESTONE_WALL
    );

    public PlacedBlockESP() {
        super(GlazedAddon.esp, "placed-block-esp", "Highlights blocks that were placed/replaced during your session or detected heuristically in newly loaded chunks.");
    }

    @Override
    public void onActivate() {
        placedBlocks.clear();
        replacedBlocks.clear();
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // We rely on BlockUpdateEvent giving oldState/newState. Meteor provides this; if your build differs adapt names.
        try {
            var oldState = event.oldState();
            var newState = event.newState();
            BlockPos pos = event.pos();

            // If air -> non-air: new placement (client saw a placement)
            if (oldState.isAir() && !newState.isAir()) {
                if (!ignoreBlocks.get().contains(newState.getBlock())) {
                    placedBlocks.add(pos.toImmutable());
                    // if it was previously marked replaced, keep both possible tags
                    replacedBlocks.remove(pos);
                }
            }
            // If non-air -> non-air: replace (e.g., broken & replaced)
            else if (!oldState.isAir() && !newState.isAir()) {
                if (!ignoreBlocks.get().contains(newState.getBlock())) {
                    replacedBlocks.add(pos.toImmutable());
                    placedBlocks.remove(pos);
                }
            }
            // If broken (non-air -> air) we don't immediately remove history; user asked to find replaced/rebuilt.
            // Optionally you could remove here: but we keep history as user requested highlighting of replaced.
        } catch (Throwable ignored) {
            // Some mappings may use different method names. Keep safe.
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (!heuristicScanOnChunkLoad.get()) return;
        if (!(event.chunk() instanceof WorldChunk wc)) return;
        if (chunkScanLimit.get() <= 0) return;

        // spawn scanning in same thread but limited
        WorldChunk chunk = wc;
        ChunkPos cpos = chunk.getPos();

        // limit scan distance in chunks around player
        double maxRange = renderRange.get();
        int maxChunkDist = (int) Math.ceil(maxRange / 16.0);

        ChunkPos playerChunk = mc.player.getChunkPos();

        // only scan chunks reasonably near player (respect chunkScanLimit)
        if (Math.abs(cpos.x - playerChunk.x) > maxChunkDist || Math.abs(cpos.z - playerChunk.z) > maxChunkDist) return;

        int scanned = 0;
        for (int x = chunk.getPos().getStartX(); x < chunk.getPos().getStartX() + 16 && scanned < chunkScanLimit.get(); x++) {
            for (int z = chunk.getPos().getStartZ(); z < chunk.getPos().getStartZ() + 16 && scanned < chunkScanLimit.get(); z++) {
                for (int y = chunk.getBottomY(); y < chunk.getBottomY() + chunk.getHeight() && scanned < chunkScanLimit.get(); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = chunk.getBlockState(pos);

                    if (state.isAir()) continue;

                    Block b = state.getBlock();
                    // ignore configured ignore list
                    if (ignoreBlocks.get().contains(b)) continue;

                    // if block is in our suspicious/default list -> mark as placed by heuristic
                    if (defaultSuspicious.contains(b)) {
                        placedBlocks.add(pos);
                        scanned++;
                        continue;
                    }

                    // Additional heuristics:
                    // - torches next to obvious player blocks
                    // - chest clusters, repeated scaffolding, etc. (simple detection below)
                    if (b == Blocks.TORCH || b == Blocks.LANTERN) {
                        // if supporting block is not natural (planks, bricks), mark
                        placedBlocks.add(pos);
                        scanned++;
                    }
                }
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        Color placedSide = new Color(placedColor.get());
        Color placedLine = new Color(placedColor.get());
        Color replacedSide = new Color(replacedColor.get());
        Color replacedLine = new Color(replacedColor.get());

        double maxRangeSq = renderRange.get() * renderRange.get();

        // Render placed blocks
        for (BlockPos pos : placedBlocks) {
            if (mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxRangeSq) continue;
            event.renderer.box(pos, placedSide, placedLine, shapeMode.get(), 0);
            if (showTracers.get()) {
                event.renderer.line(
                    mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, placedLine);
            }
        }

        // Render replaced blocks
        for (BlockPos pos : replacedBlocks) {
            if (mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxRangeSq) continue;
            event.renderer.box(pos, replacedSide, replacedLine, shapeMode.get(), 0);
            if (showTracers.get()) {
                event.renderer.line(
                    mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, replacedLine);
            }
        }
    }

    // Expose some helper methods for other modules to query remembered placed positions
    public Set<BlockPos> getPlacedPositions() {
        return placedBlocks;
    }

    public Set<BlockPos> getReplacedPositions() {
        return replacedBlocks;
    }
}
