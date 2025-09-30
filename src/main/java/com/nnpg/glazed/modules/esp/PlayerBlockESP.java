package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.HashSet;
import java.util.Set;

public class PlayerBlockESP extends Module {
    private final Set<BlockPos> placedBlocks = new HashSet<>();

    public PlayerBlockESP() {
        super(GlazedAddon.CATEGORY, "player-block-esp", "Highlights blocks that were recently placed by players.");
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // Track new block placements (ignore air updates)
        if (!event.newState.isAir()) {
            placedBlocks.add(event.pos.toImmutable());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (BlockPos pos : placedBlocks) {
            event.renderer.box(
                pos,
                new Color(255, 0, 0, 60),   // Fill (transparent red)
                new Color(255, 0, 0, 200), // Outline (solid red)
                ShapeMode.Both,
                0                           // Line thickness
            );
        }
    }
}
