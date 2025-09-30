package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.settings.*;
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
        // When a new block appears (not air), assume it was placed
        if (!event.newState.isAir()) {
            placedBlocks.add(event.pos.toImmutable());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (BlockPos pos : placedBlocks) {
            event.renderer.box(
                pos,
                1, 0, 0, 0.5f,   // RGBA (red with 50% opacity)
                1, 0, 0, 0.2f,   // Outline color (darker red)
                true             // Draw both box and outline
            );
        }
    }
}
