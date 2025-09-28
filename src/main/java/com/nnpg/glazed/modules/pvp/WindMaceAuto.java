package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
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
        .name("hit-cooldown")
        .description("Cooldown between hits (ticks).")
        .defaultValue(10)
        .min(1)
        .max(50)
        .sliderMax(50)
        .build()
    );

    private final Setting<Keybind> triggerKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("trigger-key")
        .description("Keybind to activate the wind+mace combo.")
        .defaultValue(Keybind.none())
        .build()
    );

    private boolean triggered = false;   // true only when key pressed
    private boolean usedWindCharge = false;
    private int cooldown = 0;
    private int oldSlot = -1;
    private Float originalPitch = null;

    public WindMaceAuto() {
        super(GlazedAddon.pvp, "wind-mace-auto", "Uses Wind Charge, then Mace when falling. Triggered by a keybind.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // check if trigger key is pressed
        if (triggerKey.get().isPressed() && !triggered) {
            triggered = true;
        }

        if (!triggered) return;

        PlayerEntity target = getNearestPlayer(range.get());
        if (target == null) return;

        if (oldSlot == -1) oldSlot = mc.player.getInventory().selectedSlot;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // Step 1: Use Wind Charge
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

        // Step 2: While falling, hit with Mace
        if (usedWindCharge && mc.player.getVelocity().y < 0) {
            int maceSlot = findMaceInHotbar();
            if (maceSlot != -1) {
                mc.player.getInventory().selectedSlot = maceSlot;
                lookAtPlayer(target);
                mc.interactionManager.attackEntity(mc.player, target);
                cooldown = cooldownTicks.get();
            }
        }

        // Step 3: Reset after landing
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
        cooldown = 0;
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
