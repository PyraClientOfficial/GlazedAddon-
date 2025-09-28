package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SpawnerProtectV2 extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-range")
            .description("Max range to detect spawners")
            .defaultValue(16)
            .min(1).max(50)
            .build()
    );

    private final Setting<Integer> emergencyDistance = sgGeneral.add(new IntSetting.Builder()
            .name("emergency-distance")
            .description("Disconnect if player is this close")
            .defaultValue(7)
            .min(1).max(20)
            .build()
    );

    private final Setting<Boolean> enableWhitelist = sgWhitelist.add(new BoolSetting.Builder()
            .name("enable-whitelist")
            .description("Whitelist players who won't trigger protection")
            .defaultValue(false)
            .build()
    );

    private final Setting<List<String>> whitelistPlayers = sgWhitelist.add(new StringListSetting.Builder()
            .name("whitelisted-players")
            .description("Names of whitelisted players")
            .defaultValue(new ArrayList<>())
            .visible(enableWhitelist::get)
            .build()
    );

    private final Setting<Boolean> webhook = sgWebhook.add(new BoolSetting.Builder()
            .name("webhook")
            .description("Send webhook notifications")
            .defaultValue(false)
            .build()
    );

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
            .name("webhook-url")
            .description("Discord webhook URL")
            .defaultValue("")
            .visible(webhook::get)
            .build()
    );

    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
            .name("self-ping")
            .description("Ping yourself in webhook")
            .defaultValue(false)
            .visible(webhook::get)
            .build()
    );

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
            .name("discord-id")
            .description("Discord ID for ping")
            .defaultValue("")
            .visible(() -> webhook.get() && selfPing.get())
            .build()
    );

    private enum State {IDLE, MINING, CHEST, DEPOSITING, DISCONNECTING}

    private State state = State.IDLE;
    private Vec3d startPos;
    private boolean sneaking = false;
    private BlockPos currentSpawner = null;
    private BlockPos targetChest = null;
    private String detectedPlayer = "";
    private long detectionTime = 0;

    public SpawnerProtectV2() {
        super(GlazedAddon.CATEGORY, "SpawnerProtectV2", "Improved spawner protection with auto deposit and kick.");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) {
            startPos = mc.player.getPos();
            state = State.IDLE;
            info("SpawnerProtectV2 activated! Start position: " + startPos);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (state) {
            case IDLE -> detectNearbyPlayers();
            case MINING -> mineSpawnerAndLoot();
            case CHEST -> moveToChest();
            case DEPOSITING -> depositItems();
            case DISCONNECTING -> disconnectSafely();
        }
    }

    private void detectNearbyPlayers() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (!(player instanceof AbstractClientPlayerEntity)) continue;
            String name = player.getGameProfile().getName();

            if (enableWhitelist.get() && whitelistPlayers.get().stream().anyMatch(n -> n.equalsIgnoreCase(name))) continue;

            double distance = mc.player.distanceTo(player);
            if (distance <= emergencyDistance.get()) {
                detectedPlayer = name;
                detectionTime = System.currentTimeMillis();
                info("EMERGENCY! Player " + name + " detected at distance " + distance);
                setSneaking(true);
                state = State.MINING;
                return;
            }
        }
    }

    private void mineSpawnerAndLoot() {
        if (currentSpawner == null) currentSpawner = findNearestSpawner();
        if (currentSpawner != null) {
            lookAt(currentSpawner);
            KeyBinding.setKeyPressed(mc.options.attackKey.getDefaultKey(), true);
            if (mc.world.getBlockState(currentSpawner).isAir()) {
                info("Spawner broken! Moving to ender chest to deposit items...");
                currentSpawner = null;
                state = State.CHEST;
            }
        } else {
            state = State.CHEST;
        }
    }

    private BlockPos findNearestSpawner() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(playerPos.add(-spawnerRange.get(), -spawnerRange.get(), -spawnerRange.get()),
                playerPos.add(spawnerRange.get(), spawnerRange.get(), spawnerRange.get()))) {
            if (mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
                if (dist < minDist) {
                    minDist = dist;
                    nearest = pos.toImmutable();
                }
            }
        }

        if (nearest != null) info("Found spawner at " + nearest);
        return nearest;
    }

    private void moveToChest() {
        if (targetChest == null) targetChest = findNearestEnderChest();
        if (targetChest == null) {
            info("No ender chest found. Disconnecting...");
            state = State.DISCONNECTING;
            return;
        }

        moveTowards(targetChest);

        if (mc.player.getBlockPos().isWithinDistance(targetChest, 2)) {
            state = State.DEPOSITING;
        }
    }

    private BlockPos findNearestEnderChest() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(playerPos.add(-16, -8, -16), playerPos.add(16, 8, 16))) {
            if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
                if (dist < minDist) {
                    minDist = dist;
                    nearest = pos.toImmutable();
                }
            }
        }

        if (nearest != null) info("Found ender chest at " + nearest);
        return nearest;
    }

    private void depositItems() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(targetChest), Direction.UP, targetChest, false));
            return;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
                mc.interactionManager.clickSlot(handler.syncId, i + handler.slots.size() - 36, 0, SlotActionType.QUICK_MOVE, mc.player);
                return; 
            }
        }

        info("All items deposited. Kicking player: " + detectedPlayer);
        kickPlayer(detectedPlayer);
        state = State.DISCONNECTING;
    }

    private void disconnectSafely() {
        setSneaking(false);
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);

        sendWebhook();

        if (mc.world != null) mc.world.disconnect();
        toggle();
    }

    private void moveTowards(BlockPos target) {
        Vec3d dir = Vec3d.ofCenter(target).subtract(mc.player.getPos()).normalize();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), true);
    }

    private void lookAt(BlockPos target) {
        Vec3d dir = Vec3d.ofCenter(target).subtract(mc.player.getEyePos()).normalize();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        mc.player.setPitch((float) Math.toDegrees(-Math.asin(dir.y)));
    }

    private void setSneaking(boolean sneak) {
        if (sneak && !sneaking) {
            mc.player.setSneaking(true);
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
            sneaking = true;
        } else if (!sneak && sneaking) {
            mc.player.setSneaking(false);
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            sneaking = false;
        }
    }

    private void kickPlayer(String playerName) {
        mc.player.networkHandler.sendCommand("kick " + playerName + " [SpawnerProtectV2] " + playerName + " were too close!");
    }

    private void sendWebhook() {
        if (!webhook.get() || webhookUrl.get().isEmpty()) return;

        String content = (selfPing.get() && !discordId.get().isEmpty()) ? "<@" + discordId.get() + ">" : "";
        String message = String.format("%s detected! Disconnected by SpawnerProtectV2.", detectedPlayer);

        String json = String.format("""
                {"content":"%s","embeds":[{"title":"SpawnerProtectV2 Alert","description":"%s","color":16766720,"timestamp":"%s"}]}""",
                content, message, Instant.now());

        new Thread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl.get()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) info("Webhook sent successfully!");
                else ChatUtils.error("Webhook failed: " + response.statusCode());
            } catch (Exception e) {
                ChatUtils.error("Webhook error: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onDeactivate() {
        setSneaking(false);
        KeyBinding.setKeyPressed(mc.options.forwardKey.getDefaultKey(), false);
        KeyBinding.setKeyPressed(mc.options.jumpKey.getDefaultKey(), false);
    }

    private void info(String message) {
        ChatUtils.info("[SpawnerProtectV2] " + message);
    }
}
