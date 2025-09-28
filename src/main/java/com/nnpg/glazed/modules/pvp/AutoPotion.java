package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

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

    private final Setting<List<ItemStack>> potions = sgGeneral.add(new BlockListSetting.Builder()
        .name("potions")
        .description("Which potions to throw at your feet.")
        .defaultValue(List.of(
            new ItemStack(Items.SPLASH_POTION), // placeholder, we'll filter by type
            new ItemStack(Items.LINGERING_POTION)
        ))
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

        // Switch to potion slot
        int oldSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = potionSlot;

        // Throw potion at your feet
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        // Switch back to old slot
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
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.SPLASH_POTION || stack.getItem() == Items.LINGERING_POTION) {
                // You can add more filtering by potion type if needed
                return i;
            }
        }
        return -1;
    }
}
