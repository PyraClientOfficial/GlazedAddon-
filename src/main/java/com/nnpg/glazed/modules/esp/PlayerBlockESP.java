package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

public class PlayerBlockESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Set<BlockPos> placedBlocks = new HashSet<>();

    public PlayerBlockESP() {
        super(GlazedAddon.CATEGORY, "player-block-esp", "Highlights blocks that were recently placed by players.");
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // When a block appears (not air), assume it was placed
        if (!event.state.isAir()) {
            placedBlocks.add(event.pos.toImmutable());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (BlockPos pos : placedBlocks) {
            RenderUtils.drawBox(event.matrixStack, pos, 1, 0, 0, 0.5f); // red box highlight
        }
    }
}
