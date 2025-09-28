package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShulkerBoxItem;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

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
                mc.player.sendMessage(Text.of("/shop"));
                stage = Stage.SHOP_END;
                stageStart = now;
            }
            // Other stages would follow as in your original code
        }
    }

    // --- HELPERS ---
    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof ShulkerBoxItem;
    }

    private boolean isInventoryFull() {
        for (int i = 0; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    public void info(String message, Object... args) {
        if (notifications.get()) ChatUtils.info(String.format(message, args));
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

    private String getOrderPlayerName(ItemStack stack) {
        if (stack.isEmpty()) return null;
        TooltipContext ctx = TooltipContext.Default.NORMAL;
        List<Text> tooltip = stack.getTooltip(mc.player, ctx);
        Pattern[] patterns = {
                Pattern.compile("(?i)player\\s*:\\s*([a-zA-Z0-9_]+)"),
                Pattern.compile("(?i)from\\s*:\\s*([a-zA-Z0-9_]+)"),
                Pattern.compile("(?i)by\\s*:\\s*([a-zA-Z0-9_]+)")
        };
        for (Text line : tooltip) {
            String text = line.getString();
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) return matcher.group(1);
            }
        }
        return null;
    }
}
