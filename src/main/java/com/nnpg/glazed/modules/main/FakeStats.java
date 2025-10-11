package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.hud.InGameHud;

import java.util.List;

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
    private void onHudRender(net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback event) {
        InGameHud hud = event.getHud();
        if (hud == null) return;

        // Grab the scoreboard text lines and replace them client-side
        List<String> lines = hud.getScoreboard().getLines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("Kills:")) lines.set(i, "Kills: " + kills.get());
            else if (line.contains("Deaths:")) lines.set(i, "Deaths: " + deaths.get());
            else if (line.contains("Coins:")) lines.set(i, "Coins: " + coins.get());
        }
    }
}
