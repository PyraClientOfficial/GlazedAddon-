package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

public class WindMaceAuto extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to hit a player.")
        .defaultValue(4.5)
        .min(1.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Keybind> triggerKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("trigger-key")
        .description("Keybind to activate the wind+mace combo.")
        .defaultValue(Keybind.none())
        .build()
    );

    private boolean triggered = false;
    private boolean usedWindCharge = false;
    private int oldSlot = -1;
    private Float originalPitch = null;

    public WindMaceAuto() {
        super(GlazedAddon.pvp, "wind-mace-auto", "Boosts with Wind Charge then repeatedly hits with a Mace until you land.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Step 1: Key pressed -> trigger combo
        if (triggerKey.get().isPressed() && !triggered) {
            triggered = true;
        }

        if (!triggered) return;

        PlayerEntity target = getNearestPlayer(range.get());
        if (target == null) return;

        if (oldSlot == -1) oldSlot = mc.player.getInventory().selectedSlot;

        // Step 2: Use Wind Charge once
        if (!usedWindCharge) {
            int windSlot = findItemInHotbar("Wind Charge");
            if (windSlot != -1) {
                mc.player.getInventory().selectedSlot = windSlot;
                if (originalPitch == null) {
                    originalPitch = mc.player.getPitch();
                    mc.player.setPitch(90f); // look straight down
                }
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                usedWindCharge = true;
                return;
            }
        }

        // Step 3: While falling -> attack with mace repeatedly
        if (usedWindCharge && mc.player.getVelocity().y < -0.05) {
            int maceSlot = findMaceInHotbar();
            if (maceSlot != -1 && mc.player.getAttackCooldownProgress(0) >= 1.0f) {
                mc.player.getInventory().selectedSlot = maceSlot;
                lookAtPlayer(target);
                mc.interactionManager.attackEntity(mc.player, target);
                mc.player.resetLastAttackedTicks();
            }
        }

        // Step 4: Reset when touching ground
        if (mc.player.isOnGround()) {
            reset();
        }
    }

    private void reset() {
        if (oldSlot != -1) mc.player.getInventory().selectedSlot = oldSlot;
        oldSlot = -1;
        triggered = false;
        usedWindCharge = false;
        originalPitch = null;
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
            if (!stack.isEmpty() && stack.getItem().getName(stack).getString().equalsIgnoreCase(itemName)) return i;
        }
        return -1;
    }

    private int findMaceInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem().getName(stack).getString().toLowerCase().contains("mace")) return i;
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
