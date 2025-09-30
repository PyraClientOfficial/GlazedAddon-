package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PlacedBlockESP
 *
 * Highlights blocks that were placed by a player.
 * Designed for 1.21.4 Meteor. Users cannot modify settings.
 */
public class PlacedBlockESP extends Module {
    // Internal storage
    private final Set<BlockPos> placedBlocks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> processedChunks = ConcurrentHashMap.newKeySet();

    public PlacedBlockESP() {
        super(GlazedAddon.esp, "placed-block-esp", "Highlights all blocks that have been placed by players (locked module).");
    }

    @Override
    public void onActivate() {
        placedBlocks.clear();
        processedChunks.clear();
        ChatUtils.info("[PlacedBlockESP] Module activated.");
    }

    @Override
    public void onDeactivate() {
        placedBlocks.clear();
        processedChunks.clear();
        ChatUtils.info("[PlacedBlockESP] Module deactivated.");
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk wc)) return;

        ChunkPos cp = wc.getPos();
        if (processedChunks.contains(cp)) return;
        processedChunks.add(cp);

        analyzeChunk(wc);
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.world == null) return;

        // Update blocks near player
        BlockPos pos = event.pos;
        var oldState = event.old;
        var newState = event.state;

        if (!oldState.isAir() && newState.isAir()) {
            // broken block -> remove
            placedBlocks.remove(pos);
        } else if (oldState.isAir() && !newState.isAir()) {
            // placed block -> add
            placedBlocks.add(pos);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Optional: debug log count every 10s
        long now = System.currentTimeMillis();
        if (now % 10000 < 50 && !placedBlocks.isEmpty()) {
            ChatUtils.info("[PlacedBlockESP] Placed blocks tracked: " + placedBlocks.size());
        }
    }

    private void analyzeChunk(WorldChunk chunk) {
        int bottomY = chunk.getBottomY();
        int topY = bottomY + chunk.getHeight();
        ChunkPos cp = chunk.getPos();

        for (int x = cp.x * 16; x < cp.x * 16 + 16; x++) {
            for (int z = cp.z * 16; z < cp.z * 16 + 16; z++) {
                for (int y = Math.max(bottomY, -64); y < Math.min(topY, 320); y += 4) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block b = chunk.getBlockState(pos).getBlock();

                    if (isPlayerPlacedBlock(b)) {
                        placedBlocks.add(pos);
                    }
                }
            }
        }
    }

    private boolean isPlayerPlacedBlock(Block b) {
        // Modern check for 1.21.4 blocks
        return b instanceof PlanksBlock
                || b instanceof DoorBlock
                || b instanceof BedBlock
                || b instanceof FenceBlock
                || b instanceof TrapdoorBlock
                || b instanceof SignBlock
                || b instanceof LadderBlock
                || b instanceof AbstractButtonBlock
                || b instanceof CraftingTableBlock
                || b instanceof FurnaceBlock
                || b instanceof CarpetBlock;
    }

    /**
     * Expose tracked blocks for other modules (read-only)
     */
    public Set<BlockPos> getPlacedBlocks() {
        return Collections.unmodifiableSet(new HashSet<>(placedBlocks));
    }
}
