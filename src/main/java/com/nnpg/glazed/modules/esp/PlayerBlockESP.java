package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

public class PlayerBlockESP extends Module {
    private final Set<BlockPos> placedBlocks = new HashSet<>();

    // Settings
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Max distance to render placed blocks.")
        .defaultValue(64)
        .min(8)
        .max(512)
        .sliderMax(256)
        .build()
    );

    private final Setting<Boolean> renderFill = sgGeneral.add(new BoolSetting.Builder()
        .name("render-fill")
        .description("Render a filled box.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderOutline = sgGeneral.add(new BoolSetting.Builder()
        .name("render-outline")
        .description("Render an outline.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> fillColor = sgGeneral.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Color of the filled box.")
        .defaultValue(new SettingColor(255, 0, 0, 50))
        .visible(renderFill::get)
        .build()
    );

    private final Setting<SettingColor> outlineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Color of the outline.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(renderOutline::get)
        .build()
    );

    public PlayerBlockESP() {
        super(GlazedAddon.CATEGORY, "player-block-esp", "Highlights all blocks ever placed by players.");
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        // Add block if it's not air (means placed/updated by player)
        if (!event.newState.isAir()) {
            placedBlocks.add(event.pos.toImmutable());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;

        for (BlockPos pos : placedBlocks) {
            // Only render within range
            if (mc.player.getBlockPos().isWithinDistance(pos, renderDistance.get())) {
                event.renderer.box(
                    pos,
                    renderFill.get() ? fillColor.get() : null,
                    renderOutline.get() ? outlineColor.get() : null,
                    getShapeMode(),
                    0
                );
            }
        }
    }

    private ShapeMode getShapeMode() {
        if (renderFill.get() && renderOutline.get()) return ShapeMode.Both;
        if (renderFill.get()) return ShapeMode.Sides;
        if (renderOutline.get()) return ShapeMode.Lines;
        return ShapeMode.Lines;
    }

    @Override
    public void onDeactivate() {
        placedBlocks.clear(); // Reset when module is toggled off
    }
}
