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

    // state
    private boolean active = false;        // sequence running
    private boolean usedWindCharge = false;
    private int oldSlot = -1;
    private float savedPitch = Float.NaN;
    private boolean prevKeyPressed = false;

    public WindMaceAuto() {
        super(GlazedAddon.pvp, "wind-mace-auto", "Use Wind Charge and hit with mace while falling down.");
    }

    @Override
    public void onDeactivate() {
        restoreState();
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

        // if not used wind charge yet, use it once
        if (!usedWindCharge) {
            int windSlot = findItemInHotbar("wind");
            if (windSlot == -1) {
                info("No Wind Charge found.");
                restoreState();
                return;
            }
            oldSlot = mc.player.getInventory().selectedSlot;
            savedPitch = mc.player.getPitch();
            mc.player.getInventory().selectedSlot = windSlot;
            mc.player.setPitch(90f); // look down
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            usedWindCharge = true;
            return;
        }

        // wait until falling (descending)
        if (mc.player.getVelocity().y < -0.03) {
            PlayerEntity target = getNearestPlayer(range.get());
            if (target != null && mc.interactionManager.getCurrentGameMode().isSurvivalLike()) {
                if (mc.player.getAttackCooldownProgress(0) >= 1.0f) {
                    int maceSlot = findItemInHotbar("mace");
                    if (maceSlot != -1) {
                        mc.player.getInventory().selectedSlot = maceSlot;
                        lookAtEntity(target);
                        mc.interactionManager.attackEntity(mc.player, target);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
            }
        }

        // when we land, end sequence
        if (mc.player.isOnGround()) restoreState();
    }

    private void startSequence() {
        active = true;
        usedWindCharge = false;
        oldSlot = -1;
        savedPitch = Float.NaN;
        info("WindMaceAuto: sequence started.");
    }

    private void restoreState() {
        if (mc.player == null) return;
        if (oldSlot != -1) mc.player.getInventory().selectedSlot = oldSlot;
        if (!Float.isNaN(savedPitch)) mc.player.setPitch(savedPitch);
        active = false;
        usedWindCharge = false;
        oldSlot = -1;
        savedPitch = Float.NaN;
        prevKeyPressed = false;
        info("WindMaceAuto: sequence ended.");
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
