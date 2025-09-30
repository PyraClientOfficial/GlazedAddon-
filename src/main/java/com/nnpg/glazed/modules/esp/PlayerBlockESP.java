package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

public class PlayerBlockESP extends Module {
    private final Set<BlockPos> placedBlocks = new HashSet<>();

    public PlayerBlockESP() {
        super(GlazedAddon.CATEGORY, "player-block-esp", "Highlights blocks that were recently placed by players.");
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // If a new solid block appears, track it
        if (!event.newState.isAir()) {
            placedBlocks.add(event.pos.toImmutable());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (BlockPos pos : placedBlocks) {
            // Expand BlockPos into full cube coordinates
            double x1 = pos.getX();
            double y1 = pos.getY();
            double z1 = pos.getZ();
            double x2 = x1 + 1;
            double y2 = y1 + 1;
            double z2 = z1 + 1;

            event.renderer.box(
                x1, y1, z1, x2, y2, z2,
                1, 0, 0, 0.5f,   // Fill color (red, 50% opacity)
                1, 0, 0, 0.8f,   // Outline color (solid red)
                true             // Draw outline + filled
            );
        }
    }
}
