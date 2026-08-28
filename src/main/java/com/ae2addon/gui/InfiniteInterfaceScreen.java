package com.ae2addon.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/**
 * ME 接口（无限级）配置界面（简洁版）：
 * 3×3 样板槽（上）+ 蓄水池状态行（中）+ 玩家背包（下）。
 * <p>
 * 自绘背景（无贴图依赖）：暗色面板 + 槽位底框。
 * <p>
 * 布局（2026-08-28 v3）：窗口压回标准高度 176——此前 214 在 GUI 缩放 ≥6
 * 或小窗口下会超出屏幕（sensei 全屏模式反馈）。状态行精简为 4 行。
 */
public class InfiniteInterfaceScreen extends AbstractContainerScreen<InfiniteInterfaceMenu> {

    private static final int W = 176;
    private static final int H = 176;
    private static final int STATUS_X = 8;
    private static final int STATUS_Y = 58;
    private static final int STATUS_LINE_H = 8;

    /** 蓄水池状态行（服务端 FeederStatusPacket → 主线程）。 */
    public static void handleStatus(List<String> lines) {
        var mc = net.minecraft.client.Minecraft.getInstance();
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
        inventoryLabelY = -1000; // 隐藏原版「背包」标签（状态行占位，布局自明）
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        renderBackground(g);
        int x = leftPos;
        int y = topPos;

        // 面板底
        g.fill(x, y, x + W, y + H, 0xF0101010);
        g.fill(x, y, x + W, y + 1, 0xFF555555);          // 上边框
        g.fill(x, y + H - 1, x + W, y + H, 0xFF555555);  // 下边框
        g.fill(x, y, x + 1, y + H, 0xFF555555);          // 左边框
        g.fill(x + W - 1, y, x + W, y + H, 0xFF555555);  // 右边框

        // 槽位底框（样板槽 + 玩家背包槽）
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

        // 样板槽标签（右上角，3×3 格右侧空位；防误认成投掷器）
        g.drawString(font,
                Component.translatable("gui.ae2addon.infinite_interface.patterns"),
                104, 6, 0xAAAAAA, false);

        // 蓄水池状态行（样板槽下方、背包上方；最多 4 行，超长截断）
        List<String> lines = menu.statusLines;
        int lineY = STATUS_Y;
        for (int i = 0; i < Math.min(lines.size(), 4); i++) {
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
        // ⚠️ 不能自己先调 renderBg：AbstractContainerScreen.render 内部会调（m_7286_），
        // 手动再调 = 背景画两遍（四周纯黑）。renderTooltip 同理由 super 统一处理。
        super.render(g, mouseX, mouseY, partialTick);
    }
}
