package com.ae2addon.gui;

import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.style.StyleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 集成 CPU 状态界面：继承原版 CraftingCPUScreen（保留全部原版渲染：
 * 任务条目列表、取消按钮、ETA），在屏幕固定位置追加量子分裂线程面板。
 * <p>
 * 面板功能：竖向滚动条（线程多时滚动）、点击线程行切换合成界面。
 */
public class IntegratedCPUScreen extends CraftingCPUScreen<IntegratedCPUMenu> {

    private static final int PANEL_WIDTH = 150;
    private static final int VISIBLE_LANES = 6;
    private static final int ROW_HEIGHT = 12;

    private int panelX;
    private int panelY;
    private int scrollOffset;
    private final int[] laneRowY = new int[8];

    private static boolean DIAG_CLICK_LOGGED;

    private static boolean DIAG_SCROLL_LOGGED;

    public IntegratedCPUScreen(IntegratedCPUMenu menu, Inventory playerInventory,
            Component title) {
        super(menu, playerInventory, title,
                StyleManager.loadStyleDoc("/screens/crafting_status.json"));
    }

    @Override
    public void drawFG(GuiGraphics graphics, int offsetX, int offsetY,
            int mouseX, int mouseY) {
        super.drawFG(graphics, offsetX, offsetY, mouseX, mouseY);

        // ── 量子分裂线程面板（屏幕固定位置，不随界面）──
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        panelX = screenWidth / 2 - PANEL_WIDTH / 2 - 300;
        panelY = 170;
        drawLanePanel(graphics);
    }

    private void drawLanePanel(GuiGraphics graphics) {
        int laneCount = Math.max(0, menu.laneCount);
        // 面板高度：标题 + 概览 + 可见行数
        int visible = Math.min(VISIBLE_LANES, laneCount);
        int height = 24 + 13 + ROW_HEIGHT * visible + 4;

        // 背景 + 顶部高亮条
        graphics.fill(panelX - 3, panelY - 3,
                panelX + PANEL_WIDTH, panelY + height, 0xCC000000);
        graphics.fill(panelX - 3, panelY - 3,
                panelX + PANEL_WIDTH, panelY + 2, 0xFF666666);

        graphics.drawString(font, Component.literal("§e⚡量子分裂线程"),
                panelX, panelY, 0xFFFFFF, false);
        int y = panelY + 12;

        String overview = "§f线程: " + laneCount
                + "  忙: " + menu.activeJobs
                + (menu.formed ? "  §a成型" : "  §c未成型")
                + "  §7[点击切换]";
        graphics.drawString(font, Component.literal(overview), panelX, y, 0xFFFFFF, false);
        y += 13;

        // 滚动范围
        int maxScroll = Math.max(0, laneCount - VISIBLE_LANES);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // lane 列表（滚动窗口）
        int start = scrollOffset;
        int end = Math.min(start + VISIBLE_LANES, laneCount);
        for (int row = 0; row < VISIBLE_LANES; row++) {
            int index = start + row;
            laneRowY[row] = y;
            if (index >= end) {
                continue;
            }
            String lane = menu.lane(index);
            if (lane == null || lane.isEmpty()) {
                continue;
            }
            // 选中高亮
            if (index == menu.selectedLaneIndex) {
                graphics.fill(panelX - 1, y - 1, panelX + PANEL_WIDTH - 7, y + ROW_HEIGHT - 2,
                        0x66FFFFFF);
            }
            String line = lane.contains("空闲") ? "§7" + lane : "§a" + lane;
            if (index == menu.selectedLaneIndex) {
                line = "§f▶ " + line;
            }
            graphics.drawString(font, Component.literal(line), panelX + 2, y, 0xFFFFFF, false);
            y += ROW_HEIGHT;
        }

        // 竖向滚动条
        if (laneCount > VISIBLE_LANES) {
            int trackTop = panelY + 20;
            int trackBottom = panelY + height - 4;
            int trackH = trackBottom - trackTop;
            graphics.fill(panelX + PANEL_WIDTH - 6, trackTop,
                    panelX + PANEL_WIDTH - 3, trackBottom, 0xFF444444);
            int thumbH = Math.max(12, trackH * VISIBLE_LANES / laneCount);
            int thumbY = trackTop + (trackH - thumbH) * scrollOffset / maxScroll;
            graphics.fill(panelX + PANEL_WIDTH - 6, thumbY,
                    panelX + PANEL_WIDTH - 3, thumbY + thumbH, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // drawFG 坐标系是相对界面的（AE2 translate），鼠标坐标需同步转换
            double lx = mouseX - leftPos;
            double ly = mouseY - topPos;
            boolean inPanel = isInPanel(lx, ly);
            int row = inPanel ? hitRow(ly) : -1;
            if (!DIAG_CLICK_LOGGED) {
                DIAG_CLICK_LOGGED = true;
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon] mouseClicked: abs=({},{}), local=({},{}), panel=({},{}), inPanel={}, row={}, laneCount={}",
                        mouseX, mouseY, lx, ly, panelX, panelY, inPanel, row, menu.laneCount);
            }
            if (row >= 0) {
                menu.selectLane(scrollOffset + row);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double lx = mouseX - leftPos;
        double ly = mouseY - topPos;
        boolean inPanel = isInPanel(lx, ly);
        if (!DIAG_SCROLL_LOGGED) {
            DIAG_SCROLL_LOGGED = true;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] mouseScrolled: local=({},{}), delta={}, inPanel={}, laneCount={}, scrollOffset={}",
                    lx, ly, delta, inPanel, menu.laneCount, scrollOffset);
        }
        if (inPanel && menu.laneCount > VISIBLE_LANES) {
            scrollOffset -= (int) Math.signum(delta);
            int maxScroll = Math.max(0, menu.laneCount - VISIBLE_LANES);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private boolean isInPanel(double mouseX, double mouseY) {
        int laneCount = Math.max(0, menu.laneCount);
        int visible = Math.min(VISIBLE_LANES, laneCount);
        int height = 24 + 13 + ROW_HEIGHT * visible + 4;
        return mouseX >= panelX - 3 && mouseX <= panelX + PANEL_WIDTH
                && mouseY >= panelY - 3 && mouseY <= panelY + height;
    }

    private int hitRow(double mouseY) {
        for (int row = 0; row < VISIBLE_LANES; row++) {
            if (mouseY >= laneRowY[row] - 1 && mouseY < laneRowY[row] + ROW_HEIGHT - 1) {
                return row;
            }
        }
        return -1;
    }
}
