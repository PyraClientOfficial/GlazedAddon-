package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.Chunk;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SuspiciousActivity
 *
 * Detects many heuristics that suggest an area has player activity.
 * Does not render blocks, only logs suspicious chunks once.
 */
public class SuspiciousActivity extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> torchThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("torch-threshold")
        .description("Number of torches in a chunk to mark it suspicious.")
        .defaultValue(48)
        .min(1)
        .max(4096)
        .build()
    );

    private final Setting<Integer> storageThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("storage-threshold")
        .description("Number of chests/shulkers in a chunk to mark suspicious.")
        .defaultValue(6)
        .min(1)
        .max(128)
        .build()
    );

    private final Setting<Integer> obsidianColumnLength = sgGeneral.add(new IntSetting.Builder()
        .name("obsidian-column-length")
        .description("Min vertical obsidian column length that triggers suspicion.")
        .defaultValue(12)
        .min(4)
        .max(256)
        .build()
    );

    // Internal: set of suspicious chunk coords (string "cx,cz")
    private final Set<String> suspiciousChunks = ConcurrentHashMap.newKeySet();

    // Timer for periodic re-log
    private long lastLogTime = 0;

    public SuspiciousActivity() {
        super(GlazedAddon.esp, "suspicious-activity", "Detects patterns that indicate an active base/area (no rendering).");
    }

    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        lastLogTime = 0;
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (!(event.chunk() instanceof WorldChunk wc)) return;
        analyzeChunk(wc);
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.world == null) return;
        BlockPos pos = event.pos();
        if (mc.player.getBlockPos().isWithinDistance(pos, 48)) {
            Chunk c = mc.world.getChunk(pos);
            if (c instanceof WorldChunk wc) analyzeChunk(wc);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive() || mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        if (now - lastLogTime >= 10000) { // every 10s
            if (!suspiciousChunks.isEmpty()) {
                ChatUtils.info("[SuspiciousActivity] Currently suspicious chunks: " + suspiciousChunks);
            }
            lastLogTime = now;
        }
    }

    private void analyzeChunk(WorldChunk chunk) {
        int torchCount = 0;
        int storageCount = 0;
        int signCount = 0;
        int cropCount = 0;
        int stoneBrickCount = 0;

        ChunkPos cp = chunk.getPos();
        int cx = cp.x;
        int cz = cp.z;

        int bottomY = chunk.getBottomY();
        int topY = bottomY + chunk.getHeight();

        for (int x = cx * 16; x < cx * 16 + 16; x++) {
            for (int z = cz * 16; z < cz * 16 + 16; z++) {
                for (int y = Math.max(bottomY, -64); y < Math.min(topY, 320); y += 4) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block b = chunk.getBlockState(pos).getBlock();

                    if (b == Blocks.TORCH || b == Blocks.SOUL_TORCH || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN) torchCount++;
                    else if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.SHULKER_BOX || b == Blocks.BARREL) storageCount++;
                    else if (b == Blocks.SIGN || b == Blocks.OAK_SIGN || b == Blocks.OAK_WALL_SIGN) signCount++;
                    else if (b == Blocks.WHEAT || b == Blocks.CARROTS || b == Blocks.POTATOES || b == Blocks.BEETROOTS) cropCount++;
                    else if (b == Blocks.STONE_BRICKS || b == Blocks.CRACKED_STONE_BRICKS || b == Blocks.MOSSY_STONE_BRICKS) stoneBrickCount++;
                }
            }
        }

        boolean suspicious = false;
        StringBuilder reason = new StringBuilder();

        if (torchCount >= torchThreshold.get()) {
            suspicious = true;
            reason.append("many torches(").append(torchCount).append(") ");
        }
        if (storageCount >= storageThreshold.get()) {
            suspicious = true;
            reason.append("storage cluster(").append(storageCount).append(") ");
        }
        if (signCount >= 4) {
            suspicious = true;
            reason.append("signs(").append(signCount).append(") ");
        }
        if (cropCount >= 12) {
            suspicious = true;
            reason.append("crops(").append(cropCount).append(") ");
        }
        if (stoneBrickCount >= 40) {
            suspicious = true;
            reason.append("stonebuild(").append(stoneBrickCount).append(") ");
        }

        boolean obsColumnFound = false;
        outer:
        for (int x = cx * 16; x < cx * 16 + 16; x += 4) {
            for (int z = cz * 16; z < cz * 16 + 16; z += 4) {
                int run = 0;
                for (int y = 0; y < 320; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (chunk.getBlockState(p).isOf(Blocks.OBSIDIAN)) {
                        run++;
                        if (run >= obsidianColumnLength.get()) {
                            obsColumnFound = true;
                            break outer;
                        }
                    } else {
                        run = 0;
                    }
                }
            }
        }
        if (obsColumnFound) {
            suspicious = true;
            reason.append("obsidian column ");
        }

        String chunkKey = cx + "," + cz;

        if (suspicious) {
            if (!suspiciousChunks.contains(chunkKey)) {
                suspiciousChunks.add(chunkKey);
                ChatUtils.warning("[SuspiciousActivity] suspicious chunk " + chunkKey + " -> " + reason.toString().trim());
            }
        } else {
            suspiciousChunks.remove(chunkKey);
        }
    }

    public Set<String> getSuspiciousChunks() {
        return Collections.unmodifiableSet(new HashSet<>(suspiciousChunks));
    }
}
