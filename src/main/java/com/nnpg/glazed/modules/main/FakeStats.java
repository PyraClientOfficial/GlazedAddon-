package com.nnpg.glazed.modules.misc;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.RenderGameOverlayEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;

public class FakeStats extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<Integer> kills = sgGeneral.add(new IntSetting.Builder()
        .name("kills")
        .description("Fake kills count")
        .defaultValue(100)
        .min(0)
        .max(9999)
        .build()
    );

    private final Setting<Integer> deaths = sgGeneral.add(new IntSetting.Builder()
        .name("deaths")
        .description("Fake deaths count")
        .defaultValue(5)
        .min(0)
        .max(9999)
        .build()
    );

    private final Setting<Integer> coins = sgGeneral.add(new IntSetting.Builder()
        .name("coins")
        .description("Fake coins count")
        .defaultValue(1000)
        .min(0)
        .max(999999)
        .build()
    );

    public FakeStats() {
        super(GlazedAddon.misc, "FakeStats", "Display fake stats on scoreboard (client-side only).");
    }

    @EventHandler
    private void onRenderScoreboard(RenderGameOverlayEvent event) {
        // Replace the stats in the client scoreboard rendering
        // Only client-side changes, server unaffected
        // This example modifies the scoreboard text as it's rendered

        if (!(event instanceof RenderGameOverlayEvent.Text textEvent)) return;

        for (int i = 0; i < textEvent.lines.size(); i++) {
            String line = textEvent.lines.get(i);

            if (line.contains("Kills:")) {
                textEvent.lines.set(i, "Kills: " + kills.get());
            } else if (line.contains("Deaths:")) {
                textEvent.lines.set(i, "Deaths: " + deaths.get());
            } else if (line.contains("Coins:")) {
                textEvent.lines.set(i, "Coins: " + coins.get());
            }
        }
    }
}
