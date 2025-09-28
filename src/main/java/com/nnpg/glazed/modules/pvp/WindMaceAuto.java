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
import net.minecraft.util.math.Vec3d;

public class WindMaceAuto extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to a target player to attack.")
        .defaultValue(5.0)
        .min(1.0).max(20.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Keybind> triggerKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("trigger-key")
        .description("Press once to use Wind Charge and begin the mace drop sequence.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> endCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("end-cooldown")
        .description("Ticks to wait after hitting the ground before restoring state.")
        .defaultValue(20) // 1 second
        .min(0).max(100)
        .sliderMax(100)
        .build()
    );

    // state
    private boolean active = false;
    private boolean usedWindCharge = false;
    private int oldSlot = -1;
    private float savedPitch = Float.NaN;
    private boolean prevKeyPressed = false;
    private int groundTicks = 0;

    public WindMaceAuto() {
        super(GlazedAddon.pvp, "wind-mace-auto", "Use Wind Charge and hit with mace while falling down.");
    }

    @Override
    public void onDeactivate() {
        restoreState(true);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // detect key press
        boolean keyPressed = triggerKey.get().isPressed();
        if (keyPressed && !prevKeyPressed && !active) {
            if (mc.player.isOnGround()) startSequence();
        }
        prevKeyPressed = keyPressed;

        if (!active) return;

        // Step 1: use Wind Charge
        if (!usedWindCharge) {
            int windSlot = findItemInHotbar("wind");
            if (windSlot == -1) {
                info("No Wind Charge found.");
                restoreState(true);
                return;
            }
            oldSlot = mc.player.getInventory().selectedSlot;
            savedPitch = mc.player.getPitch();
            mc.player.getInventory().selectedSlot = windSlot;
            mc.player.setPitch(90f);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            usedWindCharge = true;
            info("Wind Charge used.");
            return;
        }

        // Step 2: attack only while falling
        if (mc.player.getVelocity().y < -0.05) {
            PlayerEntity target = getNearestPlayer(range.get());
            if (target != null) {
                if (mc.player.getAttackCooldownProgress(0) >= 1.0f) {
                    int maceSlot = findMaceInHotbar();
                    if (maceSlot != -1) {
                        mc.player.getInventory().selectedSlot = maceSlot;
                        lookAtEntity(target);

                        // Attack
                        mc.interactionManager.attackEntity(mc.player, target);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        info("Attacked " + target.getName().getString() + " with mace.");
                    }
                }
            }
        }

        // Step 3: reset after cooldown when on ground
        if (mc.player.isOnGround()) {
            groundTicks++;
            if (groundTicks >= endCooldown.get()) {
                restoreState(false);
            }
        } else {
            groundTicks = 0;
        }
    }

    private void startSequence() {
        active = true;
        usedWindCharge = false;
        oldSlot = -1;
        savedPitch = Float.NaN;
        groundTicks = 0;
        info("Sequence started.");
    }

    private void restoreState(boolean force) {
        if (!active && !force) return;
        if (mc.player == null) return;
        if (oldSlot != -1) mc.player.getInventory().selectedSlot = oldSlot;
        if (!Float.isNaN(savedPitch)) mc.player.setPitch(savedPitch);
        active = false;
        usedWindCharge = false;
        oldSlot = -1;
        savedPitch = Float.NaN;
        prevKeyPressed = false;
        groundTicks = 0;
        info("Sequence ended.");
    }

    private PlayerEntity getNearestPlayer(double maxRange) {
        PlayerEntity nearest = null;
        double closest = maxRange * maxRange;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isDead() || p.isSpectator()) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d <= closest) {
                closest = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private int findItemInHotbar(String keyword) {
        String needle = keyword.toLowerCase();
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s != null && !s.isEmpty()) {
                String name = s.getItem().getName(s).getString().toLowerCase();
                if (name.contains(needle)) return i;
            }
        }
        return -1;
    }

    private int findMaceInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s != null && !s.isEmpty()) {
                String name = s.getItem().getName(s).getString().toLowerCase();
                if (name.contains("mace")) return i;
            }
        }
        return -1;
    }

    private void lookAtEntity(PlayerEntity e) {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d targetEyes = e.getEyePos();
        double dx = targetEyes.x - eyes.x;
        double dy = targetEyes.y - eyes.y;
        double dz = targetEyes.z - eyes.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }
}
