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

    public enum PotionType {
        SPLASH_HEAL(Items.SPLASH_POTION),
        SPLASH_FIRE_RESISTANCE(Items.SPLASH_POTION),
        SPLASH_STRENGTH(Items.SPLASH_POTION),
        SPLASH_POISON(Items.SPLASH_POTION),
        LINGERING_HEAL(Items.LINGERING_POTION),
        LINGERING_POISON(Items.LINGERING_POTION);

        public final Item item;

        PotionType(Item item) {
            this.item = item;
        }
    }

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How close a player must be to throw a potion.")
        .defaultValue(5.0)
        .min(1.0)
        .max(20.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<List<PotionType>> potions = sgGeneral.add(new MultiEnumSetting.Builder<PotionType>()
        .name("potions")
        .description("Which potions to throw at your feet.")
        .defaultValue(new ArrayList<>(List.of(PotionType.SPLASH_HEAL)))
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
        for (PotionType type : potions.get()) allowedItems.add(type.item);

        for (int i = 0; i < 9; i++) {
            if (allowedItems.contains(mc.player.getInventory().getStack(i).getItem())) return i;
        }

        return -1;
    }
}

