package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ME 接口（无限级）配置界面：
 * 3×3 样板槽（左）+ 3×3 标记槽（右）+ 蓄水池状态行（可点击 [✎] 内联改参数）。
 * <p>
 * 内联设置（2026-08-28 sensei 要求）：状态行带 §e[✎] 标记的行可点击，
 * 弹出输入框（支持 MAX / 1e12 / K/M/G/T/P/E 后缀），回车发送 FeederSettingPacket
 * 到服务端改配置 + 热加载，无需进 mods 配置界面。
 */
public class InfiniteInterfaceScreen extends AbstractContainerScreen<InfiniteInterfaceMenu> {

    private static final int W = 176;
    private static final int H = 180;
    private static final int STATUS_X = 8;
    private static final int STATUS_Y = 58;
    private static final int STATUS_LINE_H = 8;
    private static final int MAX_STATUS_LINES = 5;

    /** 当前编辑中的设置项 key（null = 未编辑）。 */
    private String editingKey;
    private EditBox editBox;

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

        // 槽位底框（样板槽 + 标记槽 + 玩家背包槽）——renderBg 无 translate，须手动加 leftPos/topPos
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

        g.drawString(font,
                Component.translatable("gui.ae2addon.infinite_interface.patterns"),
                26, 6, 0xAAAAAA, false);
        g.drawString(font,
                Component.translatable("gui.ae2addon.infinite_interface.markers"),
                96, 6, 0xAAAAAA, false);

        // 蓄水池状态行（最多 5 行，超长截断）
        List<String> lines = menu.statusLines;
        int lineY = STATUS_Y;
        for (int i = 0; i < Math.min(lines.size(), MAX_STATUS_LINES); i++) {
            String line = lines.get(i);
            String stripped = line.replaceAll("§.", "");
            if (font.width(stripped) > W - 16) {
                line = font.plainSubstrByWidth(line, W - 20) + "…";
            }
            g.drawString(font, Component.literal(line), STATUS_X, lineY, 0xFFFFFF, false);
            lineY += STATUS_LINE_H;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // ⚠️ 不能自己先调 renderBg：AbstractContainerScreen.render 内部会调（背景画两遍）；
        // 也不能重复 renderTooltip（super 统一处理）
        super.render(g, mouseX, mouseY, partialTick);
    }

    // ── 内联设置编辑 ──

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (editBox != null) {
            return super.mouseClicked(mx, my, button); // 输入框交互优先
        }
        if (button == 0) {
            String key = hitEditMarker(mx, my);
            if (key != null) {
                startEdit(key, my);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editBox != null && editBox.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
                applyEdit();
                return true;
            }
            if (keyCode == 256) { // Esc
                cancelEdit();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 命中 [✎] 标记 → 返回设置 key（stockTarget/restockInterval/feedBudget），未命中返回 null。 */
    private String hitEditMarker(double mx, double my) {
        // ⚠️ 鼠标坐标是屏幕坐标，状态行绘制是 GUI 相对坐标（renderLabels 在 translate 后）
        double rx = mx - leftPos;
        double ry = my - topPos;
        List<String> lines = menu.statusLines;
        for (int i = 0; i < Math.min(lines.size(), MAX_STATUS_LINES); i++) {
            int lineY = STATUS_Y + i * STATUS_LINE_H;
            if (ry < lineY || ry >= lineY + STATUS_LINE_H) {
                continue;
            }
            String stripped = lines.get(i).replaceAll("§.", "");
            // 逐字符定位所有 [✎] 的 x 坐标（相对坐标）
            List<Integer> markerXs = new ArrayList<>();
            int x = STATUS_X;
            for (int c = 0; c < stripped.length(); c++) {
                if (stripped.startsWith("[✎]", c)) {
                    markerXs.add(x);
                    x += font.width("[✎]");
                    c += 2;
                    continue;
                }
                x += font.width(String.valueOf(stripped.charAt(c)));
            }
            if (markerXs.isEmpty()) {
                continue;
            }
            String key = switch (i) {
                case 1 -> "stockTarget";
                case 2 -> {
                    // 两个标记：第一个 = 补货间隔，第二个 = 喂出预算
                    yield markerXs.size() >= 2 && rx >= markerXs.get(1) - 6
                            ? "feedBudget" : "restockInterval";
                }
                default -> null;
            };
            if (key != null) {
                for (int markerX : markerXs) {
                    if (rx >= markerX - 6 && rx <= markerX + 18) {
                        return key;
                    }
                }
            }
        }
        return null;
    }

    private void startEdit(String key, double my) {
        String current = extractCurrentValue(key);
        editingKey = key;
        editBox = new EditBox(font, leftPos + 30, (int) my - 8, 120, 14,
                Component.literal(key));
        editBox.setMaxLength(40);
        editBox.setValue(current);
        editBox.setFocused(true);
        addWidget(editBox);
    }

    /** 从状态行文本提取当前值（服务端固定格式）。 */
    private String extractCurrentValue(String key) {
        List<String> lines = menu.statusLines;
        if (lines.size() > 1) {
            String line = lines.get(1).replaceAll("§.", "");
            if ("stockTarget".equals(key)) {
                Matcher m = Pattern.compile("(\\d+)").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        if (lines.size() > 2) {
            String line = lines.get(2).replaceAll("§.", "");
            if ("restockInterval".equals(key)) {
                Matcher m = Pattern.compile("间隔:\\s*(\\d+)").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
            if ("feedBudget".equals(key)) {
                Matcher m = Pattern.compile("预算:\\s*(\\d+)").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return "";
    }

    private void applyEdit() {
        if (editBox == null || editingKey == null) {
            return;
        }
        String text = editBox.getValue().trim();
        try {
            long value = parseSetting(text);
            AE2Addon.NETWORK.sendToServer(new com.ae2addon.network.FeederSettingPacket(editingKey, value));
        } catch (NumberFormatException e) {
            // 输入无效：忽略（保留原值）
        }
        cancelEdit();
    }

    private void cancelEdit() {
        editingKey = null;
        if (editBox != null) {
            removeWidget(editBox);
            editBox = null;
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
    public void onClose() {
        cancelEdit();
        super.onClose();
    }
}
