package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Hand;

public class WindMaceModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("Maximum distance to lock onto a player.")
            .defaultValue(5.0)
            .min(1.0)
            .max(20.0)
            .sliderMax(20.0)
            .build()
    );

    private final Setting<Boolean> loopHits = sgGeneral.add(new BoolSetting.Builder()
            .name("loop-hits")
            .description("Keep hitting the target repeatedly.")
            .defaultValue(true)
            .build()
    );

    private int oldSlot = -1;
    private Float originalPitch = null;

    public WindMaceModule() {
        super(GlazedAddon.pvp, "wind-mace", "Uses Wind Charge, switches to mace, and attacks the nearest player.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        PlayerEntity target = getNearestPlayer(range.get());
        if (target == null) return;

        // Save old slot
        if (oldSlot == -1) oldSlot = mc.player.getInventory().selectedSlot;

        // Rotate down to use Wind Charge
        if (originalPitch == null) {
            originalPitch = mc.player.getPitch();
            mc.player.setPitch(90f);
        }

        // Find Wind Charge in hotbar
        int windChargeSlot = findItemInHotbar("Wind Charge");
        if (windChargeSlot != -1) {
            mc.player.getInventory().selectedSlot = windChargeSlot;
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }

        // Switch to Mace
        int maceSlot = findItemInHotbar("Mace");
        if (maceSlot != -1) mc.player.getInventory().selectedSlot = maceSlot;

        // Lock onto target (face the player)
        lookAtPlayer(target);

        // Hit target
        mc.interactionManager.attackEntity(mc.player, target);

        // Loop or reset
        if (!loopHits.get()) {
            mc.player.getInventory().selectedSlot = oldSlot;
            if (originalPitch != null) {
                mc.player.setPitch(originalPitch);
                originalPitch = null;
            }
            oldSlot = -1;
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

    private void lookAtPlayer(PlayerEntity player) {
        double dx = player.getX() - mc.player.getX();
        double dz = player.getZ() - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        mc.player.setYaw(yaw);
    }
}
