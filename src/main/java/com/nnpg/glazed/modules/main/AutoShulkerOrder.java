package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.item.Item;
import net.minecraft.item.TooltipContext;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoShulkerOrder extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private enum Stage {NONE, SHOP, SHOP_END, SHOP_SHULKER, SHOP_CONFIRM, SHOP_CHECK_FULL, SHOP_EXIT, WAIT, TARGET_ORDERS, ORDERS, ORDERS_SELECT, ORDERS_CONFIRM, ORDERS_FINAL_EXIT, CYCLE_PAUSE}
    private Stage stage = Stage.NONE;
    private long stageStart = 0;

    private int shulkerMoveIndex = 0;
    private long lastShulkerMoveTime = 0;
    private int exitCount = 0;
    private int finalExitCount = 0;
    private long finalExitStart = 0;

    private String targetPlayer = "";
    private boolean isTargetingActive = false;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Player Targeting");

    private final Setting<String> minPrice = sgGeneral.add(new StringSetting.Builder()
            .name("min-price")
            .description("Minimum price to deliver shulkers for (supports K, M, B).")
            .defaultValue("850")
            .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
            .name("notifications")
            .description("Show detailed notifications.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> speedMode = sgGeneral.add(new BoolSetting.Builder()
            .name("speed-mode")
            .description("Removes most delays (may be unstable).")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> enableTargeting = sgTargeting.add(new BoolSetting.Builder()
            .name("enable-targeting")
            .description("Target a specific player for orders.")
            .defaultValue(false)
            .build()
    );

    private final Setting<String> targetPlayerName = sgTargeting.add(new StringSetting.Builder()
            .name("target-player")
            .description("Specific player to target.")
            .defaultValue("")
            .visible(enableTargeting::get)
            .build()
    );

    private final Setting<Boolean> targetOnlyMode = sgTargeting.add(new BoolSetting.Builder()
            .name("target-only-mode")
            .description("Only check orders from the targeted player.")
            .defaultValue(false)
            .visible(enableTargeting::get)
            .build()
    );

    public AutoShulkerOrder() {
        super(GlazedAddon.CATEGORY, "AutoShulkerOrder", "Automatically buys shulkers and sells them in orders for profit with targeting");
    }

    @Override
    public void onActivate() {
        double parsedPrice = parsePrice(minPrice.get());
        if (parsedPrice == -1.0 && !enableTargeting.get()) {
            if (notifications.get()) ChatUtils.error("Invalid minimum price format!");
            toggle();
            return;
        }

        updateTargetPlayer();

        stage = Stage.SHOP;
        stageStart = System.currentTimeMillis();
        shulkerMoveIndex = 0;
        lastShulkerMoveTime = 0;
        exitCount = 0;
        finalExitCount = 0;
        finalExitStart = 0;

        if (notifications.get()) {
            String modeInfo = isTargetingActive ? " | Targeting: " + targetPlayer : "";
            info("🚀 AutoShulkerOrder activated! Minimum: %s%s", minPrice.get(), modeInfo);
        }
    }

    @Override
    public void onDeactivate() {
        stage = Stage.NONE;
    }

    private void updateTargetPlayer() {
        targetPlayer = "";
        isTargetingActive = false;

        if (enableTargeting.get() && !targetPlayerName.get().trim().isEmpty()) {
            targetPlayer = targetPlayerName.get().trim();
            isTargetingActive = true;
            if (notifications.get()) info("🎯 Targeting player: %s", targetPlayer);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();

        switch (stage) {
            case SHOP -> {
                ChatUtils.sendPlayerMsg("/shop");
                stage = Stage.SHOP_END;
                stageStart = now;
            }

            case SHOP_END -> handleShopEnd(now);
            case SHOP_SHULKER -> handleShopShulker(now);
            case SHOP_CONFIRM -> handleShopConfirm(now);
            case SHOP_CHECK_FULL -> handleShopCheckFull(now);
            case SHOP_EXIT -> handleShopExit(now);
            case WAIT -> handleWait(now);
            case TARGET_ORDERS -> {
                ChatUtils.sendPlayerMsg("/orders " + targetPlayer);
                stage = Stage.ORDERS;
                stageStart = now;
                if (notifications.get()) info("🔍 Checking orders for: %s", targetPlayer);
            }
            case ORDERS -> handleOrders(now);
            case ORDERS_SELECT -> handleOrdersSelect(now);
            case ORDERS_CONFIRM -> handleOrdersConfirm(now);
            case ORDERS_FINAL_EXIT -> handleOrdersFinalExit(now);
            case CYCLE_PAUSE -> {
                if (now - stageStart >= (speedMode.get() ? 10 : 25)) {
                    updateTargetPlayer();
                    stage = Stage.SHOP;
                    stageStart = now;
                }
            }
            case NONE -> {}
        }
    }

    // --- SHOP HANDLERS ---
    private void handleShopEnd(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
        boolean foundEndStone = false;
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && isEndStone(stack)) {
                mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                stage = Stage.SHOP_SHULKER;
                stageStart = now;
                return;
            }
        }
        if (now - stageStart > (speedMode.get() ? 1000 : 3000)) {
            mc.player.closeHandledScreen();
            stage = Stage.SHOP;
            stageStart = now;
        }
    }

    private void handleShopShulker(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;

        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && isShulkerBox(stack)) {
                int clickCount = speedMode.get() ? 10 : 5;
                for (int i = 0; i < clickCount; i++) {
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                }
                stage = Stage.SHOP_CONFIRM;
                stageStart = now;
                return;
            }
        }

        if (now - stageStart > (speedMode.get() ? 500 : 1500)) {
            mc.player.closeHandledScreen();
            stage = Stage.SHOP;
            stageStart = now;
        }
    }

    private void handleShopConfirm(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && isGreenGlass(stack)) {
                int clicks = speedMode.get() ? 3 : 2;
                for (int i = 0; i < clicks; i++)
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                stage = Stage.SHOP_CHECK_FULL;
                stageStart = now;
                return;
            }
        }
        if (now - stageStart > (speedMode.get() ? 200 : 800)) {
            stage = Stage.SHOP_SHULKER;
            stageStart = now;
        }
    }

    private void handleShopCheckFull(long now) {
        if (now - stageStart > (speedMode.get() ? 100 : 200)) {
            if (isInventoryFull()) {
                mc.player.closeHandledScreen();
                stage = Stage.SHOP_EXIT;
                stageStart = now;
            } else {
                stage = Stage.SHOP_SHULKER;
                stageStart = now;
            }
        }
    }

    private void handleShopExit(long now) {
        if (mc.currentScreen == null) {
            stage = Stage.WAIT;
            stageStart = now;
        }
    }

    private void handleWait(long now) {
        if (now - stageStart >= (speedMode.get() ? 25 : 50)) {
            if (isTargetingActive && !targetPlayer.isEmpty())
                stage = Stage.TARGET_ORDERS;
            else {
                ChatUtils.sendPlayerMsg("/orders shulker");
                stage = Stage.ORDERS;
            }
            stageStart = now;
        }
    }

    // --- ORDERS HANDLERS ---
    private void handleOrders(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
        if (speedMode.get() && now - stageStart < 200) return;

        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && isShulkerBox(stack) && isPurple(stack)) {
                String orderPlayer = getOrderPlayerName(stack);
                boolean isTargetedOrder = isTargetingActive && orderPlayer != null && orderPlayer.equalsIgnoreCase(targetPlayer);
                boolean shouldTakeOrder = false;

                if (isTargetedOrder) shouldTakeOrder = true;
                else if (!targetOnlyMode.get() && getOrderPrice(stack) >= parsePrice(minPrice.get())) shouldTakeOrder = true;

                if (shouldTakeOrder) {
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                    stage = Stage.ORDERS_SELECT;
                    stageStart = now + (speedMode.get() ? 100 : 50);
                    shulkerMoveIndex = 0;
                    lastShulkerMoveTime = 0;
                    return;
                }
            }
        }
        if (now - stageStart > (speedMode.get() ? 3000 : 5000)) {
            mc.player.closeHandledScreen();
            stage = Stage.SHOP;
            stageStart = now;
        }
    }

    private void handleOrdersSelect(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
        ScreenHandler handler = screen.getScreenHandler();

        int totalSlots = 36; // inventory + hotbar
        if (shulkerMoveIndex >= totalSlots) {
            mc.player.closeHandledScreen();
            stage = Stage.ORDERS_CONFIRM;
            stageStart = now;
            shulkerMoveIndex = 0;
            return;
        }

        long moveDelay = speedMode.get() ? 10 : 100;
        if (now - lastShulkerMoveTime >= moveDelay) {
            int batchSize = speedMode.get() ? 3 : 1;

            for (int batch = 0; batch < batchSize && shulkerMoveIndex < totalSlots; batch++) {
                ItemStack stack = mc.player.getInventory().getStack(shulkerMoveIndex);
                if (isShulkerBox(stack)) {
                    int playerSlotId = -1;
                    for (Slot slot : handler.slots) {
                        if (slot.inventory == mc.player.getInventory() && slot.getIndex() == shulkerMoveIndex) {
                            playerSlotId = slot.id;
                            break;
                        }
                    }
                    if (playerSlotId != -1)
                        mc.interactionManager.clickSlot(handler.syncId, playerSlotId, 0, SlotActionType.QUICK_MOVE, mc.player);
                }
                shulkerMoveIndex++;
            }
            lastShulkerMoveTime = now;
        }
    }

    private void handleOrdersConfirm(long now) {
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && isGreenGlass(stack)) {
                int clicks = speedMode.get() ? 15 : 5;
                for (int i = 0; i < clicks; i++)
                    mc.interactionManager.clickSlot(screen.getScreenHandler().syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
                stage = Stage.ORDERS_FINAL_EXIT;
                stageStart = now;
                finalExitCount = 0;
                finalExitStart = now;
                return;
            }
        }
        if (now - stageStart > (speedMode.get() ? 2000 : 5000)) {
            mc.player.closeHandledScreen();
            stage = Stage.SHOP;
            stageStart = now;
        }
    }

    private void handleOrdersFinalExit(long now) {
        long exitDelay = speedMode.get() ? 50 : 200;
        if (finalExitCount < 2 && now - finalExitStart >= exitDelay) {
            mc.player.closeHandledScreen();
            finalExitCount++;
            finalExitStart = now;
        } else if (finalExitCount >= 2) {
            finalExitCount = 0;
            stage = Stage.CYCLE_PAUSE;
            stageStart = now;
        }
    }

    // --- HELPERS ---
    private boolean isEndStone(ItemStack stack) {
        return stack.getItem() == Items.END_STONE || stack.getName().getString().toLowerCase(Locale.ROOT).contains("end");
    }

    private boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().getName().getString().toLowerCase(Locale.ROOT).contains("shulker box");
    }

    private boolean isPurple(ItemStack stack) {
        return stack.getItem() == Items.SHULKER_BOX;
    }

    private boolean isGreenGlass(ItemStack stack) {
        return stack.getItem() == Items.LIME_STAINED_GLASS_PANE || stack.getItem() == Items.GREEN_STAINED_GLASS_PANE;
    }

    private boolean isInventoryFull() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    private void info(String message, Object... args) {
        if (notifications.get()) ChatUtils.info(String.format(message, args));
    }

    private String getOrderPlayerName(ItemStack stack) {
        if (stack.isEmpty()) return null;
        TooltipContext ctx = TooltipContext.Default.NORMAL;
        List<Text> tooltip = stack.getTooltip(ctx, mc.player, null);
        for (Text line : tooltip) {
            String text = line.getString();
            Pattern[] patterns = {
                    Pattern.compile("(?i)player\\s*:\\s*([a-zA-Z0-9_]+)"),
                    Pattern.compile("(?i)from\\s*:\\s*([a-zA-Z0-9_]+)"),
                    Pattern.compile("(?i)by\\s*:\\s*([a-zA-Z0-9_]+)")
            };
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    String playerName = matcher.group(1);
                    if (playerName.length() >= 3 && playerName.length() <= 16) return playerName;
                }
            }
        }
        return null;
    }

    private double getOrderPrice(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        TooltipContext ctx = TooltipContext.Default.NORMAL;
        List<Text> tooltip = stack.getTooltip(ctx, mc.player, null);
        return parseTooltipPrice(tooltip);
    }

    private double parseTooltipPrice(List<Text> tooltip) {
        if (tooltip == null || tooltip.isEmpty()) return -1;
        Pattern pattern = Pattern.compile("([\\d,]+)([kKmMbB]?)");
        for (Text line : tooltip) {
            Matcher matcher = pattern.matcher(line.getString());
            if (matcher.find()) {
                String num = matcher.group(1).replace(",", "");
                String suffix = matcher.group(2).toLowerCase();
                double val = Double.parseDouble(num);
                if ("k".equals(suffix)) val *= 1_000;
                if ("m".equals(suffix)) val *= 1_000_000;
                if ("b".equals(suffix)) val *= 1_000_000_000;
                return val;
            }
        }
        return -1;
    }

    private double parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) return -1;
        String s = priceStr.toLowerCase().replace(",", "");
        double multiplier = 1.0;
        if (s.endsWith("k")) { multiplier = 1_000; s = s.substring(0, s.length() - 1); }
        if (s.endsWith("m")) { multiplier = 1_000_000; s = s.substring(0, s.length() - 1); }
        if (s.endsWith("b")) { multiplier = 1_000_000_000; s = s.substring(0, s.length() - 1); }
        return Double.parseDouble(s) * multiplier;
    }
}
