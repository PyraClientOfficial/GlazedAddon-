package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;

public class AutoPotion extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("How close a player must be to throw or drink a potion.")
            .defaultValue(5.0)
            .min(1.0)
            .max(20.0)
            .sliderMax(20.0)
            .build()
    );

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
            .name("potion-cooldown")
            .description("Cooldown in ticks between potion uses.")
            .defaultValue(20)
            .min(1)
            .max(100)
            .sliderMax(100)
            .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate-down")
            .description("Rotate down when throwing or drinking a potion.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> switchToSword = sgGeneral.add(new BoolSetting.Builder()
            .name("switch-to-sword")
            .description("Switches to the closest sword after using a potion.")
            .defaultValue(true)
            .build()
    );

    private int cooldown = 0;
    private Float originalPitch = null;

    public AutoPotion() {
        super(GlazedAddon.pvp, "auto-potion", "Automatically uses potions on nearby players.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        PlayerEntity nearest = getNearestPlayer(range.get());
        if (nearest == null) return;

        int oldSlot = mc.player.getInventory().selectedSlot;

        int potionSlot = findPotionInHotbar();
        boolean drinking = potionSlot != -1 && mc.player.getInventory().getStack(potionSlot).getItem() instanceof PotionItem &&
                           !(mc.player.getInventory().getStack(potionSlot).getItem() instanceof SplashPotionItem) &&
                           !(mc.player.getInventory().getStack(potionSlot).getItem() instanceof LingeringPotionItem);

        if (potionSlot == -1) return; // no potion found

        // Rotate down
        if (rotate.get() && originalPitch == null) {
            originalPitch = mc.player.getPitch();
            mc.player.setPitch(90f);
        }

        // Switch to potion slot
        mc.player.getInventory().selectedSlot = potionSlot;

        // Use potion
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        cooldown = cooldownTicks.get();

        // Rotate back
        if (rotate.get() && originalPitch != null) {
            mc.player.setPitch(originalPitch);
            originalPitch = null;
        }

        // Switch to closest sword
        if (switchToSword.get()) {
            int swordSlot = findClosestSwordSlot();
            mc.player.getInventory().selectedSlot = swordSlot != -1 ? swordSlot : oldSlot;
        } else {
            mc.player.getInventory().selectedSlot = oldSlot;
        }
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

    private int findPotionInHotbar() {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem || item instanceof PotionItem) {
                return i;
            }
        }
        return -1;
    }

    private int findClosestSwordSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof SwordItem) return i;
        }
        return -1;
    }
}
