package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

public class AutoPotion extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("How close a player must be to throw a potion.")
            .defaultValue(5.0)
            .min(1.0)
            .max(20.0)
            .sliderMax(20.0)
            .build()
    );

    // Individual potion selections
    private final Setting<Boolean> splashHeal = sgGeneral.add(new BoolSetting.Builder()
            .name("Splash Heal")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> splashStrength = sgGeneral.add(new BoolSetting.Builder()
            .name("Splash Strength")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> splashPoison = sgGeneral.add(new BoolSetting.Builder()
            .name("Splash Poison")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> lingeringHeal = sgGeneral.add(new BoolSetting.Builder()
            .name("Lingering Heal")
            .defaultValue(false)
            .build()
    );

    public AutoPotion() {
        super(GlazedAddon.pvp, "auto-potion", "Throws a potion at your feet when a player is nearby.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        PlayerEntity nearest = getNearestPlayer(range.get());
        if (nearest == null) return;

        int potionSlot = findPotionSlot();
        if (potionSlot == -1) return;

        int oldSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = potionSlot;

        // Throw potion at your feet
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        mc.player.getInventory().selectedSlot = oldSlot;
    }

    private PlayerEntity getNearestPlayer(double maxRange) {
        PlayerEntity nearest = null;
        double closest = maxRange * maxRange;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isDead() || player.isSpectator()) continue;
            double distSq = mc.player.squaredDistanceTo(player);
            if (distSq <= closest) {
                closest = distSq;
                nearest = player;
            }
        }

        return nearest;
    }

    private int findPotionSlot() {
        List<Item> allowedItems = new ArrayList<>();
        if (splashHeal.get()) allowedItems.add(Items.SPLASH_POTION);
        if (splashStrength.get()) allowedItems.add(Items.SPLASH_POTION);
        if (splashPoison.get()) allowedItems.add(Items.SPLASH_POTION);
        if (lingeringHeal.get()) allowedItems.add(Items.LINGERING_POTION);

        for (int i = 0; i < 9; i++) {
            if (allowedItems.contains(mc.player.getInventory().getStack(i).getItem())) return i;
        }

        return -1;
    }
}
