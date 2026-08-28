package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.config.AE2AddonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.Locale;

/**
 * ME 接口（无限级）配置界面（v5 参数置顶版）：
 * 顶部三行参数（补货目标/补货间隔/喂出预算，常驻输入框+保存），
 * 中部 3×3 样板槽 + 3×3 标记槽，下方状态行 + 背包。
 * <p>
 * 2026-08-28 10:32 sensei 要求：参数行放到 UI 上方。
 */
public class InfiniteInterfaceScreen extends AbstractContainerScreen<InfiniteInterfaceMenu> {

    private static final int W = 176;
    private static final int H = 214;
    private static final int MAX_STATUS_LINES = 2;

    private static final String[] KEYS = {"stockTarget", "restockInterval", "feedBudget"};
    private static final String[] LABELS = {"§7补货目标", "§7补货间隔", "§7喂出预算"};
    /** 参数行 Y（顶部，位于样板槽上方）。 */
    private static final int[] ROW_YS = {17, 31, 45};
    /** 状态行 Y（样板槽下方）。 */
    private static final int STATUS_Y = 121;

    private final EditBox[] boxes = new EditBox[3];

    /** 蓄水池状态行（服务端 FeederStatusPacket → 主线程）。 */
    public static void handleStatus(List<String> lines) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof InfiniteInterfaceScreen screen) {
            screen.menu.statusLines = lines;
        }
    }

    public InfiniteInterfaceScreen(InfiniteInterfaceMenu menu, Inventory playerInventory,
            Component title) {
        super(menu, playerInventory, title);
        imageWidth = W;
        imageHeight = H;
        titleLabelY = 6;
        inventoryLabelY = -1000; // 隐藏原版「背包」标签（布局自明）
    }

    @Override
    protected void init() {
        super.init();
        long[] values = {
                AE2AddonConfig.feederStockTarget(),
                AE2AddonConfig.feederRestockInterval(),
                AE2AddonConfig.feederFeedBudget()
        };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            EditBox box = new EditBox(font, leftPos + 48, topPos + ROW_YS[i], 70, 14,
                    Component.literal(KEYS[i]));
            box.setMaxLength(40);
            box.setValue(String.valueOf(values[i]));
            addRenderableWidget(box);
            boxes[i] = box;

            addRenderableWidget(Button.builder(Component.literal("保存"),
                    b -> saveSetting(idx)
            ).bounds(leftPos + 122, topPos + ROW_YS[i] - 1, 32, 16).build());
        }
    }

    /** 读取输入框 → 解析 → 发送到服务端。 */
    private void saveSetting(int idx) {
        if (idx < 0 || idx >= KEYS.length || boxes[idx] == null) {
            return;
        }
        String text = boxes[idx].getValue().trim();
        try {
            long value = parseSetting(text);
            AE2Addon.LOGGER.info("[ae2addon][gui] 保存设置: {} = {}（文本 {}）", KEYS[idx], value, text);
            AE2Addon.NETWORK.sendToServer(
                    new com.ae2addon.network.FeederSettingPacket(KEYS[idx], value));
        } catch (NumberFormatException e) {
            AE2Addon.LOGGER.info("[ae2addon][gui] 解析失败: {} 文本={}", KEYS[idx], text);
        }
    }

    /** 客户端解析：MAX/INF → Long.MAX；支持 K/M/G/T/P/E 后缀与科学计数。 */
    private long parseSetting(String text) throws NumberFormatException {
        String t = text.trim().toUpperCase(Locale.ROOT);
        if (t.equals("MAX") || t.equals("INF") || t.equals("INFINITE")) {
            return Long.MAX_VALUE;
        }
        double unit = 1.0;
        if (!t.isEmpty()) {
            char last = t.charAt(t.length() - 1);
            switch (last) {
                case 'K' -> { unit = 1e3; t = t.substring(0, t.length() - 1); }
                case 'M' -> { unit = 1e6; t = t.substring(0, t.length() - 1); }
                case 'G' -> { unit = 1e9; t = t.substring(0, t.length() - 1); }
                case 'T' -> { unit = 1e12; t = t.substring(0, t.length() - 1); }
                case 'P' -> { unit = 1e15; t = t.substring(0, t.length() - 1); }
                case 'E' -> { unit = 1e18; t = t.substring(0, t.length() - 1); }
                default -> { }
            }
        }
        double v;
        try {
            v = Long.parseLong(t) * unit;
        } catch (NumberFormatException e) {
            v = Double.parseDouble(t) * unit;
        }
        if (Double.isNaN(v) || v < 0) {
            throw new NumberFormatException("无效");
        }
        return (long) Math.min(Long.MAX_VALUE, v);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        renderBackground(g);
        int x = leftPos;
        int y = topPos;

        // 面板底
        g.fill(x, y, x + W, y + H, 0xF0101010);
        g.fill(x, y, x + W, y + 1, 0xFF555555);
        g.fill(x, y + H - 1, x + W, y + H, 0xFF555555);
        g.fill(x, y, x + 1, y + H, 0xFF555555);
        g.fill(x + W - 1, y, x + W, y + H, 0xFF555555);

        // 槽位底框（renderBg 无 translate，须手动加 leftPos/topPos）
        for (Slot slot : menu.getSlotList()) {
            if (slot.isActive()) {
                drawSlotBg(g, x + slot.x - 1, y + slot.y - 1);
            }
        }
    }

    private void drawSlotBg(GuiGraphics g, int sx, int sy) {
        g.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
        g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF373737);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFAA, false);

        // 参数行标签（顶部，输入框左侧）
        for (int i = 0; i < 3; i++) {
            g.drawString(font, Component.literal(LABELS[i]), 8, ROW_YS[i] + 3, 0xFFFFFF, false);
        }

        // 样板槽/标记槽标签（格子正上方）
        g.drawString(font,
                Component.translatable("gui.ae2addon.infinite_interface.patterns"),
                26, 60, 0xAAAAAA, false);
        g.drawString(font,
                Component.translatable("gui.ae2addon.infinite_interface.markers"),
                96, 60, 0xAAAAAA, false);

        // 状态行（样板槽下方；最多 2 行，超长截断）
        List<String> lines = menu.statusLines;
        int lineY = STATUS_Y;
        for (int i = 0; i < Math.min(lines.size(), MAX_STATUS_LINES); i++) {
            String line = lines.get(i);
            String stripped = line.replaceAll("§.", "");
            if (font.width(stripped) > W - 16) {
                line = font.plainSubstrByWidth(line, W - 20) + "…";
            }
            g.drawString(font, Component.literal(line), 8, lineY, 0xFFFFFF, false);
            lineY += 8;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // ⚠️ 不能自己先调 renderBg（super 内部会调，画两遍）；renderTooltip 同理
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 输入框聚焦时回车 = 保存
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i] != null && boxes[i].isFocused()
                    && (keyCode == 257 || keyCode == 335)) {
                saveSetting(i);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
