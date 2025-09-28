package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;

public class WindMaceAuto extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("Maximum distance to hit a player.")
            .defaultValue(5.0)
            .min(1.0)
            .max(20.0)
            .sliderMax(20.0)
            .build()
    );

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
            .name("cooldown-ticks")
            .description("Cooldown between Wind Charge uses.")
            .defaultValue(10)
            .min(1)
            .max(50)
            .build()
    );

    private int cooldown = 0;
    private boolean usedWindCharge = false;
    private int oldSlot = -1;
    private Float originalPitch = null;

    public WindMaceAuto() {
        super(GlazedAddon.pvp, "wind-mace-auto", "Uses Wind Charge then hits with Mace repeatedly.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        PlayerEntity target = getNearestPlayer(range.get());
        if (target == null) return;

        if (oldSlot == -1) oldSlot = mc.player.getInventory().selectedSlot;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // Step 1: Use Wind Charge if not used in current jump
        if (!usedWindCharge) {
            int windSlot = findItemInHotbar("Wind Charge");
            if (windSlot != -1) {
                mc.player.getInventory().selectedSlot = windSlot;
                if (originalPitch == null) {
                    originalPitch = mc.player.getPitch();
                    mc.player.setPitch(90f); // look down
                }
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                usedWindCharge = true;
                cooldown = 5;
                return;
            }
        }

        // Step 2: Use Mace while airborne
        if (usedWindCharge && mc.player.getVelocity().y <= 0) { // descending
            int maceSlot = findMaceInHotbar();
            if (maceSlot != -1) {
                mc.player.getInventory().selectedSlot = maceSlot;
                lookAtPlayer(target);
                mc.interactionManager.attackEntity(mc.player, target);
                cooldown = cooldownTicks.get();
            }
        }

        // Step 3: Reset when player is on the ground
        if (mc.player.isOnGround()) {
            usedWindCharge = false;
            mc.player.getInventory().selectedSlot = oldSlot;
            originalPitch = null;
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

    private int findItemInHotbar(String itemName) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && stack.getItem().getName(stack).getString().equalsIgnoreCase(itemName)) return i;
        }
        return -1;
    }

    private int findMaceInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && stack.getItem().getName(stack).getString().toLowerCase().contains("mace")) return i;
        }
        return -1;
    }

    private void lookAtPlayer(PlayerEntity player) {
        double dx = player.getX() - mc.player.getX();
        double dz = player.getZ() - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        mc.player.setYaw(yaw);
    }
}
