package com.nnpg.glazed.modules.render;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.entity.player.BlockPlaceEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.Renderer3D;

import java.util.HashSet;
import java.util.Set;

public class PlayerBlockESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the block is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> clearOnDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-disable")
        .description("Clear saved blocks when module is disabled.")
        .defaultValue(true)
        .build()
    );

    private final Set<BlockPos> playerBlocks = new HashSet<>();

    public PlayerBlockESP() {
        super(GlazedAddon.CATEGORY, "player-block-esp", "Highlights blocks placed by players.");
    }

    @EventHandler
    private void onBlockPlace(BlockPlaceEvent event) {
        if (event.blockPos != null) {
            playerBlocks.add(event.blockPos.toImmutable());
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        for (BlockPos pos : playerBlocks) {
            Renderer3D.boxWithLines(event, pos, shapeMode.get(), 0, 1, 0, 0.5f); // green highlight
        }
    }

    @Override
    public void onDeactivate() {
        if (clearOnDisable.get()) playerBlocks.clear();
    }
}
