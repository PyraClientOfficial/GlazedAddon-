package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.potion.Potion;
import net.minecraft.util.Hand;

import java.util.List;

public class AutoPotion extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPotions = settings.createGroup("Potions");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("How close a player must be to throw or drink a potion.")
            .defaultValue(5.0)
            .min(1.0)
            .max(20.0)
            .sliderMax(20.0)
            .build()
    );

    private final Setting<Boolean> switchToSword = sgGeneral.add(new BoolSetting.Builder()
            .name("Switch To Sword After")
            .description("Switches to the closest sword in hotbar after throwing or drinking a potion.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
            .name("Potion Cooldown")
            .description("Cooldown in ticks between potion uses.")
            .defaultValue(20)
            .min(1)
            .max(100)
            .sliderMax(100)
            .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("Rotate Down")
            .description("Rotates player down when throwing a potion or drinking it, then back up.")
            .defaultValue(true)
            .build()
    );

    private final Setting<List<Potion>> potions = sgPotions.add(new EnumListSetting.Builder<Potion>()
            .name("potions")
            .description("Which potions to throw or drink")
            .defaultValue(List.of()) // empty by default
            .build()
    );

    private int cooldown = 0;
    private Float originalPitch = null;

    public AutoPotion() {
        super(GlazedAddon.pvp, "auto-potion", "Throws or drinks potions automatically near players.");
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

        int potionSlot = findPotionSlot();
        boolean drinking = false;

        if (potionSlot == -1) {
            potionSlot = findDrinkablePotionSlot();
            drinking = potionSlot != -1;
        }

        if (potionSlot == -1) return;

        if (rotate.get() && originalPitch == null) {
            originalPitch = mc.player.getPitch();
            mc.player.setPitch(90f); // look down
        }

        mc.player.getInventory().selectedSlot = potionSlot;

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        cooldown = cooldownTicks.get();

        if (rotate.get() && originalPitch != null) {
            mc.player.setPitch(originalPitch);
            originalPitch = null;
        }

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

    // Helper method to get Potion from a stack
    private Potion getPotionFromStack(ItemStack stack) {
        if (stack.getItem() instanceof PotionItem potionItem) {
            return potionItem.getDefaultPotion();
        }
        return null;
    }

    private int findPotionSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            Item item = stack.getItem();
            if (item instanceof SplashPotionItem || item instanceof LingeringPotionItem) {
                Potion potionType = getPotionFromStack(stack);
                if (potionType != null && potions.get().contains(potionType)) return i;
            }
        }
        return -1;
    }

    private int findDrinkablePotionSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            Item item = stack.getItem();
            if (item instanceof PotionItem && !(item instanceof SplashPotionItem) && !(item instanceof LingeringPotionItem)) {
                Potion potionType = getPotionFromStack(stack);
                if (potionType != null && potions.get().contains(potionType)) return i;
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
