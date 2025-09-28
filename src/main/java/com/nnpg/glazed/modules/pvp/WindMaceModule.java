package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.Hand;

public class WindMaceCombo extends Module {
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

    private final Setting<Integer> hitCooldown = sgGeneral.add(new IntSetting.Builder()
            .name("hit-cooldown")
            .description("Cooldown in ticks between hits.")
            .defaultValue(10)
            .min(1)
            .max(100)
            .sliderMax(50)
            .build()
    );

    private final Setting<Boolean> loopHits = sgGeneral.add(new BoolSetting.Builder()
            .name("loop-hits")
            .description("Keep performing combos repeatedly.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> doubleHit = sgGeneral.add(new BoolSetting.Builder()
            .name("double-hit-combo")
            .description("Perform sword then mace hit while descending.")
            .defaultValue(true)
            .build()
    );

    private int cooldown = 0;
    private boolean usedWindCharge = false;
    private boolean swordHitDone = false;
    private int oldSlot = -1;
    private Float originalPitch = null;

    public WindMaceCombo() {
        super(GlazedAddon.pvp, "wind-mace-combo", "Boosts with Wind Charge and performs a sword+mace combo on the nearest player.");
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

        // Step 1: Use Wind Charge to boost
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

        // Step 2: Sword + Mace combo
        if (usedWindCharge) {
            // Check if descending
            if (mc.player.getVelocity().y < 0) {
                if (!swordHitDone && doubleHit.get()) {
                    int swordSlot = findSwordInHotbar();
                    if (swordSlot != -1) mc.player.getInventory().selectedSlot = swordSlot;
                    lookAtPlayer(target);
                    mc.interactionManager.attackEntity(mc.player, target);
                    swordHitDone = true;
                    cooldown = hitCooldown.get();
                    return;
                }

                if (swordHitDone) {
                    int maceSlot = findMaceInHotbar();
                    if (maceSlot != -1) mc.player.getInventory().selectedSlot = maceSlot;
                    lookAtPlayer(target);
                    mc.interactionManager.attackEntity(mc.player, target);
                    cooldown = hitCooldown.get();
                    if (!loopHits.get()) resetModule();
                }
            }
        }
    }

    private void resetModule() {
        mc.player.getInventory().selectedSlot = oldSlot;
        oldSlot = -1;
        usedWindCharge = false;
        swordHitDone = false;
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
            if (stack != null && stack.getItem().getName(stack).getString().equalsIgnoreCase(itemName)) return i;
        }
        return -1;
    }

    private int findSwordInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof SwordItem) return i;
        }
        return -1;
    }

    private int findMaceInHotbar() {
        // Replace with your Mace item class
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item.getName(mc.player.getInventory().getStack(i)).getString().toLowerCase().contains("mace")) return i;
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
