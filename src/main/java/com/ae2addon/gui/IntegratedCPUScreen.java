package com.ae2addon.gui;

import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.style.StyleManager;
import net.minecraft.ChatFormatting;
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
    private static final int ORDER_PANEL_WIDTH = 150;
    private static final int VISIBLE_ORDERS = 8;

    private int panelX;
    private int panelY;
    /** 面板位置是否已初始化（drawFG 每帧重算会重置拖动——2026-09-04 sensei：
     *  面板做成可拖动，位置只在首帧初始化）。 */
    private boolean panelPosInitialized;
    /** 拖动的面板：0=无 1=量子分裂线程面板（巨型订单面板联动跟随） */
    private int draggingPanel;
    /** 拖动抓取点（面板内相对偏移，避免跳变） */
    private double dragGrabX;
    private double dragGrabY;
    private int scrollOffset;
    private boolean draggingScrollbar = false;
    private int orderScrollOffset;
    private boolean draggingOrderScrollbar = false;
    private final int[] laneRowY = new int[8];
    private final int[] orderRowY = new int[64];

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

        // ── 量子分裂线程面板（可拖动；位置首帧初始化，拖动后保持）──
        if (!panelPosInitialized) {
            int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            panelX = screenWidth / 2 - PANEL_WIDTH / 2 - 300;
            panelY = 170;
            panelPosInitialized = true;
        }
        drawLanePanel(graphics);
        drawOrderPanel(graphics);
    }

    /** 巨型订单管理面板（线程面板左侧）：滚动窗口 + 滑条，点击行取消整个订单 */
    private void drawOrderPanel(GuiGraphics graphics) {
        var orders = menu.fullOrders;
        if (orders == null || orders.isEmpty()) {
            return;
        }
        int orderPanelX = orderPanelLeft();
        int visible = Math.min(VISIBLE_ORDERS, orders.size());
        int height = 12 + ROW_HEIGHT * visible + 4;

        graphics.fill(orderPanelX - 3, panelY - 3,
                orderPanelX + ORDER_PANEL_WIDTH, panelY + height, 0xCC000000);
        graphics.fill(orderPanelX - 3, panelY - 3,
                orderPanelX + ORDER_PANEL_WIDTH, panelY + 2, 0xFF666666);
        graphics.drawString(font,
                Component.translatable("gui.ae2addon.order.title"),
                orderPanelX, panelY, 0xFFFFAA, false);

        int maxScroll = Math.max(0, orders.size() - VISIBLE_ORDERS);
        orderScrollOffset = Math.max(0, Math.min(orderScrollOffset, maxScroll));

        int start = orderScrollOffset;
        int end = Math.min(start + VISIBLE_ORDERS, orders.size());
        int y = panelY + 12;
        for (int row = 0; row < VISIBLE_ORDERS; row++) {
            orderRowY[row] = y;
            int index = start + row;
            if (index >= end) {
                y += ROW_HEIGHT;
                continue;
            }
            String raw = orders.get(index);
            if (raw == null || raw.isEmpty()) {
                y += ROW_HEIGHT;
                continue;
            }
            Component line;
            try {
                line = Component.Serializer.fromJson(raw);
            } catch (Exception e) {
                line = Component.literal(raw);
            }
            graphics.drawString(font, line, orderPanelX + 2, y, 0xFFFFFF, false);
            // 右侧取消按钮
            Component cancel = Component.translatable("gui.ae2addon.order.cancel");
            graphics.drawString(font, cancel,
                    orderPanelX + ORDER_PANEL_WIDTH - font.width(cancel) - 6, y, 0xFF5555, false);
            y += ROW_HEIGHT;
        }

        // 竖向滚动条
        if (orders.size() > VISIBLE_ORDERS) {
            int trackTop = panelY + 16;
            int trackBottom = panelY + height - 4;
            int trackH = trackBottom - trackTop;
            graphics.fill(orderPanelX + ORDER_PANEL_WIDTH - 6, trackTop,
                    orderPanelX + ORDER_PANEL_WIDTH - 3, trackBottom, 0xFF444444);
            int thumbH = Math.max(12, trackH * VISIBLE_ORDERS / orders.size());
            int thumbY = trackTop + (trackH - thumbH) * orderScrollOffset / maxScroll;
            graphics.fill(orderPanelX + ORDER_PANEL_WIDTH - 6, thumbY,
                    orderPanelX + ORDER_PANEL_WIDTH - 3, thumbY + thumbH, 0xFFAAAAAA);
        }
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

        graphics.drawString(font, Component.translatable("gui.ae2addon.cpu.quantum_split"),
                panelX, panelY, 0xFFFFFF, false);
        int y = panelY + 12;

        Component overview = Component.translatable("gui.ae2addon.cpu.overview",
                laneCount, menu.activeJobs,
                menu.formed
                        ? Component.translatable("gui.ae2addon.cpu.formed")
                        : Component.translatable("gui.ae2addon.cpu.unformed"));
        graphics.drawString(font, overview, panelX, y, 0xFFFFFF, false);
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
            String raw = menu.lane(index);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            // lane 是服务端 JSON 序列化的 translatable Component，反序列化后本地化渲染
            Component laneC;
            try {
                laneC = Component.Serializer.fromJson(raw);
            } catch (Exception e) {
                laneC = Component.literal(raw);
            }
            // 选中高亮
            if (index == menu.selectedLaneIndex) {
                graphics.fill(panelX - 1, y - 1, panelX + PANEL_WIDTH - 7, y + ROW_HEIGHT - 2,
                        0x66FFFFFF);
            }
            // 空闲灰色 / 忙碌绿色（按序列化 JSON 里的固定 key 判断）
            boolean idle = raw.contains("gui.ae2addon.cpu.lane.idle");
            Component line = laneC.copy()
                    .withStyle(s -> s.withColor(idle ? ChatFormatting.GRAY : ChatFormatting.GREEN));
            if (index == menu.selectedLaneIndex) {
                line = Component.literal("§f▶ ").append(line);
            }
            graphics.drawString(font, line, panelX + 2, y, 0xFFFFFF, false);
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
            // 面板标题条（拖动手柄，y-3..y+11）：优先于行点击/滑条
            int handle = hitPanelHandle(lx, ly);
            if (handle != 0) {
                draggingPanel = handle;
                dragGrabX = lx - (handle == 1 ? panelX : orderPanelLeft());
                dragGrabY = ly - panelY;
                return true;
            }
            boolean inPanel = isInPanel(lx, ly);
            // 滑条区域：按下即开始拖拽（优先于行点击，避免误切线程）
            if (inPanel && isOnScrollbar(lx, ly)) {
                draggingScrollbar = true;
                updateScrollFromDrag(ly);
                return true;
            }
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
            // 巨型订单面板（线程面板左侧）：Shift+点击行 = 取消整个订单（防误触）
            var orders = menu.fullOrders;
            if (orders != null && !orders.isEmpty()) {
                if (isOnOrderScrollbar(lx, ly)) {
                    draggingOrderScrollbar = true;
                    updateOrderScroll(ly);
                    return true;
                }
                if (isInOrderPanel(lx, ly) && hasShiftDown()) {
                    for (int r = 0; r < VISIBLE_ORDERS; r++) {
                        if (ly >= orderRowY[r] - 1 && ly < orderRowY[r] + ROW_HEIGHT - 1) {
                            menu.cancelOrder(orderScrollOffset + r);
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
            double dragX, double dragY) {
        if (button == 0 && draggingPanel != 0) {
            double lx = mouseX - leftPos;
            double ly = mouseY - topPos;
            panelX = (int) Math.round(lx - dragGrabX);
            panelY = (int) Math.round(ly - dragGrabY);
            return true;
        }
        if (button == 0 && draggingScrollbar) {
            updateScrollFromDrag(mouseY - topPos);
            return true;
        }
        if (button == 0 && draggingOrderScrollbar) {
            updateOrderScroll(mouseY - topPos);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingPanel != 0) {
            draggingPanel = 0;
            return true;
        }
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        if (button == 0 && draggingOrderScrollbar) {
            draggingOrderScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 鼠标是否在订单面板内 */
    private boolean isInOrderPanel(double mx, double my) {
        var orders = menu.fullOrders;
        if (orders == null || orders.isEmpty()) {
            return false;
        }
        int orderPanelX = orderPanelLeft();
        int visible = Math.min(VISIBLE_ORDERS, orders.size());
        int height = 12 + ROW_HEIGHT * visible + 4;
        return mx >= orderPanelX - 3 && mx <= orderPanelX + ORDER_PANEL_WIDTH
                && my >= panelY - 3 && my <= panelY + height;
    }

    /** 鼠标是否在订单滑条轨道上 */
    private boolean isOnOrderScrollbar(double mx, double my) {
        var orders = menu.fullOrders;
        if (orders == null || orders.size() <= VISIBLE_ORDERS) {
            return false;
        }
        int orderPanelX = orderPanelLeft();
        int height = 12 + ROW_HEIGHT * VISIBLE_ORDERS + 4;
        int trackTop = panelY + 16;
        int trackBottom = panelY + height - 4;
        return mx >= orderPanelX + ORDER_PANEL_WIDTH - 7 && mx <= orderPanelX + ORDER_PANEL_WIDTH - 2
                && my >= trackTop - 1 && my <= trackBottom + 1;
    }

    /** 按滑块位置（鼠标 Y）更新订单滚动偏移 */
    private void updateOrderScroll(double my) {
        var orders = menu.fullOrders;
        if (orders == null || orders.size() <= VISIBLE_ORDERS) {
            return;
        }
        int height = 12 + ROW_HEIGHT * VISIBLE_ORDERS + 4;
        int trackTop = panelY + 16;
        int trackBottom = panelY + height - 4;
        int trackH = trackBottom - trackTop;
        int maxScroll = Math.max(0, orders.size() - VISIBLE_ORDERS);
        int thumbH = Math.max(12, trackH * VISIBLE_ORDERS / orders.size());
        double ratio = (my - trackTop - thumbH / 2.0) / Math.max(1, trackH - thumbH);
        orderScrollOffset = (int) Math.round(ratio * maxScroll);
        orderScrollOffset = Math.max(0, Math.min(orderScrollOffset, maxScroll));
    }

    /** 鼠标是否在滑条轨道上（含滑块） */
    private boolean isOnScrollbar(double mouseX, double mouseY) {
        int laneCount = Math.max(0, menu.laneCount);
        if (laneCount <= VISIBLE_LANES) {
            return false;
        }
        int height = panelHeight(laneCount);
        int trackTop = panelY + 20;
        int trackBottom = panelY + height - 4;
        return mouseX >= panelX + PANEL_WIDTH - 7 && mouseX <= panelX + PANEL_WIDTH - 2
                && mouseY >= trackTop - 1 && mouseY <= trackBottom + 1;
    }

    /** 按滑块位置（鼠标 Y）更新滚动偏移，滑块中心对齐 */
    private void updateScrollFromDrag(double mouseY) {
        int laneCount = Math.max(0, menu.laneCount);
        if (laneCount <= VISIBLE_LANES) {
            return;
        }
        int height = panelHeight(laneCount);
        int trackTop = panelY + 20;
        int trackBottom = panelY + height - 4;
        int trackH = trackBottom - trackTop;
        int maxScroll = Math.max(0, laneCount - VISIBLE_LANES);
        int thumbH = Math.max(12, trackH * VISIBLE_LANES / laneCount);
        double ratio = (mouseY - trackTop - thumbH / 2.0) / Math.max(1, trackH - thumbH);
        scrollOffset = (int) Math.round(ratio * maxScroll);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private int panelHeight(int laneCount) {
        int visible = Math.min(VISIBLE_LANES, Math.max(0, laneCount));
        return 24 + 13 + ROW_HEIGHT * visible + 4;
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
        // 巨型订单面板滚轮
        if (isInOrderPanel(lx, ly) && menu.fullOrders.size() > VISIBLE_ORDERS) {
            orderScrollOffset -= (int) Math.signum(delta);
            int maxScroll = Math.max(0, menu.fullOrders.size() - VISIBLE_ORDERS);
            orderScrollOffset = Math.max(0, Math.min(orderScrollOffset, maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** 巨型订单面板左缘（联动跟随线程面板）。 */
    private int orderPanelLeft() {
        return panelX - ORDER_PANEL_WIDTH - 10;
    }

    /** 面板把手命中：1=线程面板 2=巨型订单面板（标题条 y-3..y+11）。 */
    private int hitPanelHandle(double mx, double my) {
        if (mx >= panelX - 3 && mx <= panelX + PANEL_WIDTH
                && my >= panelY - 3 && my <= panelY + 11) {
            return 1;
        }
        var orders = menu.fullOrders;
        if (orders != null && !orders.isEmpty()) {
            int ox = orderPanelLeft();
            int visible = Math.min(VISIBLE_ORDERS, orders.size());
            int height = 12 + ROW_HEIGHT * visible + 4;
            if (mx >= ox - 3 && mx <= ox + ORDER_PANEL_WIDTH
                    && my >= panelY - 3 && my <= panelY + 11) {
                return 2;
            }
        }
        return 0;
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
