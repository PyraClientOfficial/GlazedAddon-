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
        .description("Maximum distance to a target player to attempt the attack.")
        .defaultValue(5.0)
        .min(1.0)
        .max(20.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Keybind> triggerKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("trigger-key")
        .description("Press this key to perform one Wind Charge -> Mace sequence.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> attackCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("attack-cooldown-ticks")
        .description("Minimum ticks between mace swings while falling (prevents micro-spam).")
        .defaultValue(3)
        .min(0)
        .max(20)
        .build()
    );

    // state
    private boolean active = false;            // sequence in progress for this keypress
    private boolean usedWindCharge = false;    // wind charge was used this sequence
    private int oldSlot = -1;
    private float savedPitch = Float.NaN;
    private int attackTickTimer = 0;           // cooldown between swings
    private boolean prevKeyPressed = false;    // detect rising edge

    public WindMaceAuto() {
        super(GlazedAddon.pvp, "wind-mace-auto", "Use Wind Charge then repeatedly hit with a mace while falling (one press = one sequence).");
    }

    @Override
    public void onDeactivate() {
        // restore slot/pitch if disabling mid-sequence
        restoreState();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // rising-edge key detection (one activation per press)
        boolean keyPressed = triggerKey.get().isPressed();
        if (keyPressed && !prevKeyPressed && !active) {
            // start a sequence only if on ground (prevents mid-air accidental triggers)
            if (mc.player.isOnGround()) {
                startSequence();
            } else {
                info("WindMaceAuto: You are mid-air — must be on ground to trigger.");
            }
        }
        prevKeyPressed = keyPressed;

        if (!active) return; // nothing to do until a sequence is active

        // Find a nearby target — we only proceed when there's a player in range
        PlayerEntity target = getNearestPlayer(range.get());
        if (target == null) {
            // If no target, we still allow the sequence to continue (you might want that).
            // Optionally you can cancel the sequence here.
        }

        // If we haven't used wind charge yet, use it once (then wait for motion)
        if (!usedWindCharge) {
            int windSlot = findItemInHotbarByName("wind");
            if (windSlot == -1) {
                info("WindMaceAuto: No Wind Charge found in hotbar — cancelling sequence.");
                restoreState();
                return;
            }

            // store old slot & pitch
            if (oldSlot == -1) oldSlot = mc.player.getInventory().selectedSlot;
            if (Float.isNaN(savedPitch)) savedPitch = mc.player.getPitch();

            // switch to wind charge and use it
            mc.player.getInventory().selectedSlot = windSlot;
            mc.player.setPitch(90f); // look down for consistent usage
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

            usedWindCharge = true;
            attackTickTimer = 0;
            return;
        }

        // Once wind charge used, wait until we're descending (velocity.y < 0) to begin mace swings
        if (mc.player.getVelocity().y < -0.03) {
            // While descending, attempt to hit if target exists and in range
            if (target != null) {
                double distSq = mc.player.squaredDistanceTo(target);
                if (distSq <= range.get() * range.get()) {
                    int maceSlot = findMaceInHotbar();
                    if (maceSlot == -1) {
                        // no mace found — just continue until landing and restore
                        // optionally, you might want to try fists/other weapons
                    } else {
                        // attack cooldown between swings (simple tick timer)
                        if (attackTickTimer <= 0) {
                            mc.player.getInventory().selectedSlot = maceSlot;

                            // face the target quickly (small, immediate rotation)
                            lookAtEntity(target);

                            // perform the attack
                            mc.interactionManager.attackEntity(mc.player, target);
                            mc.player.swingHand(Hand.MAIN_HAND);

                            // reset small cooldown so we don't spam every client tick
                            attackTickTimer = Math.max(0, attackCooldownTicks.get());
                        } else {
                            attackTickTimer--;
                        }
                    }
                }
            }
        }

        // When we hit the ground, finish the sequence and restore original slot & pitch
        if (mc.player.isOnGround()) {
            restoreState();
        }
    }

    private void startSequence() {
        active = true;
        usedWindCharge = false;
        oldSlot = -1;
        savedPitch = Float.NaN;
        attackTickTimer = 0;
        prevKeyPressed = true; // avoid immediate retrigger while holding
        info("WindMaceAuto: Sequence started — using Wind Charge.");
    }

    private void restoreState() {
        if (mc.player == null) return;
        if (oldSlot != -1) mc.player.getInventory().selectedSlot = oldSlot;
        if (!Float.isNaN(savedPitch)) mc.player.setPitch(savedPitch);
        active = false;
        usedWindCharge = false;
        oldSlot = -1;
        savedPitch = Float.NaN;
        attackTickTimer = 0;
        prevKeyPressed = false;
        info("WindMaceAuto: Sequence ended.");
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

    private int findItemInHotbarByName(String nameSubstr) {
        if (mc.player == null) return -1;
        String needle = nameSubstr.toLowerCase();
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
        // look for any hotbar item with "mace" in its display name
        return findItemInHotbarByName("mace");
    }

    private void lookAtEntity(PlayerEntity e) {
        // immediate yaw+pitch snap to face entity (helps ensure client-side hit)
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
