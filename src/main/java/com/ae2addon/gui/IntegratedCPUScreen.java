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
    /** 巨型订单面板独立位置（2026-09-04：两面板各自拖动，互不跳变） */
    private int orderPanelX;
    private int orderPanelY;
    /** 拖动的面板：0=无 1=量子分裂线程面板 2=巨型订单面板 */
    private int draggingPanel;
    /** 拖动抓取点（面板内相对偏移，避免跳变） */
    private double dragGrabX;
    private double dragGrabY;
    private static final java.io.File UI_STATE_FILE = new java.io.File(
            net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().toFile(),
            "ae2addon-cpu-ui.json");
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

    /** 左按钮宽 106 + 间距 4 + 右按钮宽 40 = 150 = 线程面板宽度 */
    private static final int ACTION_BTN_WIDTH = 106;
    private static final int RESTORE_BTN_OFFSET = 110;
    private static final int RESTORE_BTN_WIDTH = 40;
    private static final int BTN_HEIGHT = 16;

    // ── 一键成型 / 一键回收 / 结构形态（2026-09-25 sensei）──
    // 形态 / 朝向同排，成型与回收各一排；急停 / 恢复保留最下排。
    // 整行统一左移 4px（BUILD_BTN_LEAD），x 与 y 全部跟随面板移动。
    private static final int BUILD_BTN_WIDTH = 146;
    private static final int FORM_BTN_WIDTH = 70;
    private static final int FACING_BTN_OFFSET = 72;
    private static final int FACING_BTN_WIDTH = 70;
    private static final int RECYCLE_BTN_WIDTH = 146;
    private static final int BUILD_BTN_LEAD = 4;         // 整行向左移 4px

    @Override
    protected void init() {
        super.init();
        // ── 两个按钮（2026-09-19 sensei 定稿）──
        // 左：急停/删除 —— 未急停显示「急停所有线程」，按下后变红显示「删除」；
        // 右：恢复 —— **独立按钮**，点它把左边切回「急停所有线程」并恢复线程工作。
        //
        // ⚠ 2026-09-19 教训：这里**必须把 addRenderableWidget 的返回值存回字段**。
        //   上一版写成 `ae2addon$actionButton = null;` 然后 `addRenderableWidget(new …)`，
        //   字段永远为 null → drawFG 里的位置同步被 null 判断挡掉 → 两个按钮停在创建时的
        //   (0,0)（屏幕左上角）并互相重叠（sensei 报「按钮叠在一起，还挤在左上角」）。
        ensurePanelPos();
        ae2addon$actionButton = addRenderableWidget(
                new ActionButton(ae2addon$btnX(), ae2addon$btnY(),
                        ACTION_BTN_WIDTH, BTN_HEIGHT, this::getMenu));
        ae2addon$restoreButton = addRenderableWidget(
                new RestoreButton(ae2addon$btnX() + RESTORE_BTN_OFFSET, ae2addon$btnY(),
                        RESTORE_BTN_WIDTH, BTN_HEIGHT, this::getMenu));
        // Three rows above the halt controls. Keep these offsets in syncButtonPos as well.
        ae2addon$buildButton = addRenderableWidget(
                new BuildButton(ae2addon$btnX() - BUILD_BTN_LEAD, ae2addon$btnY() - BTN_HEIGHT * 2 - 4,
                        BUILD_BTN_WIDTH, BTN_HEIGHT, this::getMenu));
        ae2addon$formButton = addRenderableWidget(
                new FormVariantButton(ae2addon$btnX() - BUILD_BTN_LEAD,
                        ae2addon$btnY() - BTN_HEIGHT * 3 - 6,
                        FORM_BTN_WIDTH, BTN_HEIGHT, this::getMenu));
        ae2addon$facingButton = addRenderableWidget(
                new BuildFacingButton(ae2addon$btnX() - BUILD_BTN_LEAD + FACING_BTN_OFFSET,
                        ae2addon$btnY() - BTN_HEIGHT * 3 - 6,
                        FACING_BTN_WIDTH, BTN_HEIGHT, this::getMenu));
        ae2addon$recycleButton = addRenderableWidget(
                new RecycleButton(ae2addon$btnX() - BUILD_BTN_LEAD,
                        ae2addon$btnY() - BTN_HEIGHT - 2,
                        RECYCLE_BTN_WIDTH, BTN_HEIGHT, this::getMenu));
    }

    private net.minecraft.client.gui.components.AbstractWidget ae2addon$actionButton;
    private net.minecraft.client.gui.components.AbstractWidget ae2addon$restoreButton;
    private net.minecraft.client.gui.components.AbstractWidget ae2addon$buildButton;
    private net.minecraft.client.gui.components.AbstractWidget ae2addon$recycleButton;
    /** 结构形态切换按钮（2026-09-25 sensei A1：含拓展 / 无拓展） */
    private net.minecraft.client.gui.components.AbstractWidget ae2addon$formButton;
    private net.minecraft.client.gui.components.AbstractWidget ae2addon$facingButton;

    /**
     * 两个按钮的命中判定 + 点击处理。
     * <p>
     * 坐标系 = drawFG 的**界面局部坐标**（lx = mouseX - leftPos）。
     * 自己判定是为了保证「画在哪就点在哪」，并且点击一定归我们（不依赖 widget 分发顺序）。
     */
    private boolean ae2addon$hitButton(double lx, double ly) {
        var m = getMenu();
        if (m == null) {
            return false;
        }
        int by = panelY - 21;
        if (ly < by || ly >= by + BTN_HEIGHT) {
            return false;
        }
        if (lx >= panelX && lx < panelX + ACTION_BTN_WIDTH) {
            if (m.halted) {
                com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] 界面点击「删除」→ 强制取消所有订单");
                m.cancelAllOrders();
            } else {
                com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] 界面点击「急停所有线程」");
                m.requestHalt(true);
            }
            return true;
        }
        if (lx >= panelX + RESTORE_BTN_OFFSET && lx < panelX + RESTORE_BTN_OFFSET + RESTORE_BTN_WIDTH) {
            com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] 界面点击「恢复」");
            if (m.halted) {
                m.requestHalt(false);
            }
            return true;
        }
        return false;
    }

    /** 面板位置初始化（幂等）：优先读存档，无存档用默认位。 */
    private void ensurePanelPos() {
        if (panelPosInitialized) {
            return;
        }
        loadUiState();
        if (!panelPosInitialized) {
            // 无存档：默认位（线程面板屏幕左侧；订单面板在其左）
            int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            panelX = screenWidth / 2 - PANEL_WIDTH / 2 - 300;
            panelY = 170;
            orderPanelX = panelX - ORDER_PANEL_WIDTH - 10;
            orderPanelY = panelY;
            panelPosInitialized = true;
        }
    }

    /** 按钮左上角的**绝对屏幕坐标**（vanilla 的 widget 由 Screen 直接绘制，不经过 drawFG 的平移） */
    private int ae2addon$btnX() {
        return leftPos + panelX;
    }

    private int ae2addon$btnY() {
        return topPos + panelY - 21;
    }

    /** 每帧把按钮贴回线程面板标题上方（面板可拖动，所以位置要跟着走） */
    private void ae2addon$syncButtonPos() {
        if (ae2addon$actionButton != null) {
            ae2addon$actionButton.setX(ae2addon$btnX());
            ae2addon$actionButton.setY(ae2addon$btnY());
        }
        if (ae2addon$restoreButton != null) {
            ae2addon$restoreButton.setX(ae2addon$btnX() + RESTORE_BTN_OFFSET);
            ae2addon$restoreButton.setY(ae2addon$btnY());
        }
        if (ae2addon$buildButton != null) {
            ae2addon$buildButton.setX(ae2addon$btnX() - BUILD_BTN_LEAD);
            ae2addon$buildButton.setY(ae2addon$btnY() - BTN_HEIGHT * 2 - 4);
        }
        if (ae2addon$recycleButton != null) {
            ae2addon$recycleButton.setX(ae2addon$btnX() - BUILD_BTN_LEAD);
            ae2addon$recycleButton.setY(ae2addon$btnY() - BTN_HEIGHT - 2);
        }
        if (ae2addon$formButton != null) {
            ae2addon$formButton.setX(ae2addon$btnX() - BUILD_BTN_LEAD);
            ae2addon$formButton.setY(ae2addon$btnY() - BTN_HEIGHT * 3 - 6);
        }
        if (ae2addon$facingButton != null) {
            ae2addon$facingButton.setX(ae2addon$btnX() - BUILD_BTN_LEAD + FACING_BTN_OFFSET);
            ae2addon$facingButton.setY(ae2addon$btnY() - BTN_HEIGHT * 3 - 6);
        }
    }

    /**
     * 「结构形态」切换按钮（2026-09-25 sensei 选 A1）。
     * <p>
     * 决定**一键成型放哪一份结构**：
     * <ul>
     *   <li>含拓展单元 —— 31×53×41，2249 格（含 27 个巨型存储 + 装配处理器）</li>
     *   <li>无拓展单元 —— 27×44×42，约 1960 格（更小的基础形态）</li>
     * </ul>
     * 注意：结构**判定**两份都认（服务端 matchAny 依次试），这个按钮只管"放哪份"。
     */
    private static final class FormVariantButton extends HaltBase {
        private FormVariantButton(int x, int y, int w, int h,
                java.util.function.Supplier<IntegratedCPUMenu> menuSupplier) {
            super(w, menuSupplier);
            setX(x);
            setY(y);
        }

        @Override
        public void onClick(double mx, double my) {
            var m = menuSupplier.get();
            if (m != null) {
                // 在两种形态之间切换
                m.requestFormVariant(!m.noExpandForm);
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            var m = menuSupplier.get();
            boolean noExpand = m != null && m.noExpandForm;
            paint(g, noExpand ? "无拓展单元" : "含拓展单元", false);
        }
    }

    /** Cycle automatic, south, west, north and east for the next build. */
    private static final class BuildFacingButton extends HaltBase {
        private BuildFacingButton(int x, int y, int w, int h,
                java.util.function.Supplier<IntegratedCPUMenu> menuSupplier) {
            super(w, menuSupplier);
            setX(x);
            setY(y);
        }

        @Override
        public void onClick(double mx, double my) {
            var m = menuSupplier.get();
            if (m != null) m.requestBuildFacing(m.buildFacing >= 3 ? -1 : m.buildFacing + 1);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            var m = menuSupplier.get();
            int selected = m == null ? -1 : m.buildFacing;
            String label = switch (selected) {
                case 0 -> "朝向：南";
                case 1 -> "朝向：西";
                case 2 -> "朝向：北";
                case 3 -> "朝向：东";
                default -> "朝向：自动";
            };
            paint(g, label, false);
        }
    }

    /** 自绘按钮基类：本版 Forge 的 Button 只有 Builder 构造器，无法继承定制渲染 */
    private abstract static class HaltBase
            extends net.minecraft.client.gui.components.AbstractWidget {
        final java.util.function.Supplier<IntegratedCPUMenu> menuSupplier;

        HaltBase(int w, java.util.function.Supplier<IntegratedCPUMenu> menuSupplier) {
            super(0, 0, w, 16, Component.literal(""));
            this.menuSupplier = menuSupplier;
            this.active = true;
        }

        boolean haltedNow() {
            var m = menuSupplier.get();
            return m != null && m.halted;
        }

        void paint(GuiGraphics g, String text, boolean red) {
            int bg = red ? 0xFF8B1A1A : 0xFF3A3A3A;
            int border = red ? 0xFFFF4040 : 0xFF9A9A9A;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bg);
            g.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            var label = Component.literal(text);
            int tx = getX() + (getWidth() - font.width(label)) / 2;
            int ty = getY() + (getHeight() - 8) / 2;
            g.drawString(font, label, tx, ty, red ? 0xFFFFD0D0 : 0xFFE0E0E0, false);
        }

        @Override
        public void updateWidgetNarration(
                net.minecraft.client.gui.narration.NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /** 左按钮：急停所有线程 ⇄ 删除（红） */
    private static final class ActionButton extends HaltBase {
        private ActionButton(int x, int y, int w, int h,
                java.util.function.Supplier<IntegratedCPUMenu> menuSupplier) {
            super(w, menuSupplier);
            setX(x);
            setY(y);
        }

        @Override
        public void onClick(double mx, double my) {
            var m = menuSupplier.get();
            if (m == null) {
                return;
            }
            if (m.halted) {
                // 已急停 → 按钮显示「删除」：**强制取消所有订单**（不改变急停状态）
                m.cancelAllOrders();
            } else {
                m.requestHalt(true);   // 未急停 → 按下即停工，按钮变「删除」
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            boolean halted = haltedNow();
            paint(g, halted ? "删除" : "急停所有线程", halted);
        }
    }

    /** 右按钮：恢复（把左按钮切回「急停所有线程」并恢复线程工作） */
    private static final class RestoreButton extends HaltBase {
        private RestoreButton(int x, int y, int w, int h,
                java.util.function.Supplier<IntegratedCPUMenu> menuSupplier) {
            super(w, menuSupplier);
            setX(x);
            setY(y);
        }

        @Override
        public void onClick(double mx, double my) {
            var m = menuSupplier.get();
            if (m != null && m.halted) {
                m.requestHalt(false);       // 恢复线程工作
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            paint(g, "恢复", false);
        }
    }

    /**
     * 「一键成型」按钮（2026-09-24 sensei 第 3 阶段）。
     * <p>
     * 文案随状态变：
     * <ul>
     *   <li>未成型 / 空闲 → 「一键成型」；</li>
     *   <li>构建中 → 「放置中 512/2248」；</li>
     *   <li>已完成 → 「已摆放完成」，已成型时直接显示「已成型」；</li>
     *   <li>被阻挡 / 缺料 → 按钮变红，文案给出简短原因（完整原因在聊天栏）。</li>
     * </ul>
     */
    private static final class BuildButton extends HaltBase {
        private BuildButton(int x, int y, int w, int h,
                java.util.function.Supplier<IntegratedCPUMenu> menuSupplier) {
            super(w, menuSupplier);
            setX(x);
            setY(y);
        }

        @Override
        public void onClick(double mx, double my) {
            var m = menuSupplier.get();
            if (m != null) {
                m.requestBuild();
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            var m = menuSupplier.get();
            if (m == null) {
                paint(g, "一键成型", false);
                return;
            }
            String text;
            boolean red = false;
            if (m.formed && !"BUILDING".equals(m.buildState)) {
                text = "已成型，无需摆放";
            } else if ("BUILDING".equals(m.buildState)) {
                text = "放置中 " + m.buildPlaced + "/" + m.buildTotal;
            } else if ("DONE".equals(m.buildState)) {
                text = "已摆放完成 " + m.buildPlaced + "/" + m.buildTotal;
            } else if ("BLOCKED".equals(m.buildState)) {
                text = "放置被阻挡";
                red = true;
            } else if ("MISSING".equals(m.buildState)) {
                text = "缺少材料";
                red = true;
            } else {
                text = "一键成型";
            }
            paint(g, text, red);
        }
    }

    /**
     * 「一键回收」按钮（2026-09-25 sensei）。
     * <p>
     * 交互：**按一次变成「确定回收」，3 秒内再按一次才真的拆** ——
     * 第一次点击只进入待确认状态（不会误拆），超过 3 秒自动回到「一键回收」。
     * 执行内容：除控制器以外，把结构里所有方块拆掉，物品退回玩家背包（背包满则掉在脚下）。
     * <p>
     * 文案：回收中 → 「回收中 N/M」；刚完成 → 「已回收 N/M」（3 秒）；待确认 → 「确定？」（红）。
     */
    private static final class RecycleButton extends HaltBase {

        /** 待确认的截止时刻（毫秒）；0 = 没有待确认 */
        private long confirmUntil;
        /** 回收刚结束时显示「已回收 N」的截止时刻 */
        private long doneUntil;
        /** 本次"已回收"提示是否已经显示过（防止过期后无限重置） */
        private boolean doneShown;

        private RecycleButton(int x, int y, int w, int h,
                java.util.function.Supplier<IntegratedCPUMenu> menuSupplier) {
            super(w, menuSupplier);
            setX(x);
            setY(y);
        }

        /** 单调时钟（毫秒）。用 MC 自己的，省得每次渲染都 new 一个 Clock 对象 */
        private static long nowMs() {
            return net.minecraft.Util.getMillis();
        }

        @Override
        public void onClick(double mx, double my) {
            var m = menuSupplier.get();
            if (m == null) {
                return;
            }
            long now = nowMs();
            if (confirmUntil > 0 && now <= confirmUntil) {
                com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] 界面点击「确定回收」→ 拆除结构（控制器保留）");
                confirmUntil = 0;
                m.requestRecycle();
            } else {
                // 第一次点击：只进入"待确认"，3 秒内再点一次才会真拆
                confirmUntil = now + 3000;
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float partial) {
            var m = menuSupplier.get();
            long now = nowMs();
            if (m == null) {
                paint(g, "一键回收", false);
                return;
            }
            if (m.recycling) {
                doneShown = false;
                paint(g, "回收中 " + m.recycleDone + "/" + m.recycleTotal, false);
                return;
            }
            // 刚回收完：显示一次结果（3 秒）
            if (m.recycleFinished && m.recycleTotal > 0) {
                if (!doneShown) {
                    doneUntil = now + 3000;
                    doneShown = true;
                }
                if (now < doneUntil) {
                    paint(g, "已回收 " + m.recycleDone, false);
                    return;
                }
            }
            if (confirmUntil > 0 && now <= confirmUntil) {
                paint(g, "确定回收", true);   // 红字 = 待确认
                return;
            }
            confirmUntil = 0;                 // 超时/未点击：回到常态
            paint(g, "一键回收", false);
        }
    }

    @Override
    public void drawFG(GuiGraphics graphics, int offsetX, int offsetY,
            int mouseX, int mouseY) {
        super.drawFG(graphics, offsetX, offsetY, mouseX, mouseY);

        // ── 量子分裂线程面板 + 巨型订单面板（可拖动；位置持久化）──
        ensurePanelPos();
        drawLanePanel(graphics);
        drawOrderPanel(graphics);
        // 两个按钮：贴在线程面板**标题上方**（面板标题画在 panelY，背景从 panelY-3 起）
        ae2addon$syncButtonPos();
    }

    /** 巨型订单管理面板（线程面板左侧）：滚动窗口 + 滑条，点击行取消整个订单 */
    private void drawOrderPanel(GuiGraphics graphics) {
        var orders = menu.fullOrders;
        if (orders == null || orders.isEmpty()) {
            return;
        }
        int visible = Math.min(VISIBLE_ORDERS, orders.size());
        int height = 12 + ROW_HEIGHT * visible + 4;

        graphics.fill(orderPanelX - 3, orderPanelY - 3,
                orderPanelX + ORDER_PANEL_WIDTH, orderPanelY + height, 0xCC000000);
        graphics.fill(orderPanelX - 3, orderPanelY - 3,
                orderPanelX + ORDER_PANEL_WIDTH, orderPanelY + 2, 0xFF666666);
        graphics.drawString(font,
                Component.translatable("gui.ae2addon.order.title"),
                orderPanelX, orderPanelY, 0xFFFFAA, false);

        int maxScroll = Math.max(0, orders.size() - VISIBLE_ORDERS);
        orderScrollOffset = Math.max(0, Math.min(orderScrollOffset, maxScroll));

        int start = orderScrollOffset;
        int end = Math.min(start + VISIBLE_ORDERS, orders.size());
        int y = orderPanelY + 12;
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
            // 两个按钮：**自己命中并优先处理**（比交给 vanilla widget 确定，也不会被 AE2 的控件抢走）
            if (ae2addon$hitButton(lx, ly)) {
                return true;
            }
            // 面板标题条（拖动手柄，y-3..y+11）：优先于行点击/滑条
            int handle = hitPanelHandle(lx, ly);
            if (handle != 0) {
                draggingPanel = handle;
                if (handle == 1) {
                    dragGrabX = lx - panelX;
                    dragGrabY = ly - panelY;
                } else {
                    dragGrabX = lx - orderPanelX;
                    dragGrabY = ly - orderPanelY;
                }
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
                            // 2026-09-15：按**订单 id** 取消（行索引在多网络下会错位取消到别人的订单）
                            int rowIndex = orderScrollOffset + r;
                            var ids = menu.fullOrderIds;
                            if (ids != null && rowIndex >= 0 && rowIndex < ids.size()) {
                                menu.cancelOrderById(ids.get(rowIndex));
                            }
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
            if (draggingPanel == 1) {
                panelX = (int) Math.round(lx - dragGrabX);
                panelY = (int) Math.round(ly - dragGrabY);
            } else {
                orderPanelX = (int) Math.round(lx - dragGrabX);
                orderPanelY = (int) Math.round(ly - dragGrabY);
            }
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
            saveUiState();
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
        int visible = Math.min(VISIBLE_ORDERS, orders.size());
        int height = 12 + ROW_HEIGHT * visible + 4;
        return mx >= orderPanelX - 3 && mx <= orderPanelX + ORDER_PANEL_WIDTH
                && my >= orderPanelY - 3 && my <= orderPanelY + height;
    }

    /** 鼠标是否在订单滑条轨道上 */
    private boolean isOnOrderScrollbar(double mx, double my) {
        var orders = menu.fullOrders;
        if (orders == null || orders.size() <= VISIBLE_ORDERS) {
            return false;
        }
        int height = 12 + ROW_HEIGHT * VISIBLE_ORDERS + 4;
        int trackTop = orderPanelY + 16;
        int trackBottom = orderPanelY + height - 4;
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
        int trackTop = orderPanelY + 16;
        int trackBottom = orderPanelY + height - 4;
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
    public void onClose() {
        if (panelPosInitialized) {
            saveUiState();
        }
        super.onClose();
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

    /** 从 config/ae2addon-cpu-ui.json 读取面板位置（无存档/损坏则保持默认）。 */
    private void loadUiState() {
        try {
            if (UI_STATE_FILE.exists() && UI_STATE_FILE.length() > 0) {
                var gson = new com.google.gson.Gson();
                var json = new String(java.nio.file.Files.readAllBytes(
                        UI_STATE_FILE.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                var obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("px") && obj.has("py") && obj.has("ox") && obj.has("oy")) {
                    panelX = obj.get("px").getAsInt();
                    panelY = obj.get("py").getAsInt();
                    orderPanelX = obj.get("ox").getAsInt();
                    orderPanelY = obj.get("oy").getAsInt();
                    panelPosInitialized = true;
                }
            }
        } catch (Throwable t) {
            // 存档损坏/IO 失败：回落默认位，不阻塞界面
            panelPosInitialized = false;
        }
    }

    /** 保存面板位置（拖动结束/关屏时）。 */
    private void saveUiState() {
        try {
            var obj = new com.google.gson.JsonObject();
            obj.addProperty("px", panelX);
            obj.addProperty("py", panelY);
            obj.addProperty("ox", orderPanelX);
            obj.addProperty("oy", orderPanelY);
            java.nio.file.Files.write(UI_STATE_FILE.toPath(),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                            .toJson(obj).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // UI 状态保存失败不影响游戏
        }
    }

    /** 面板把手命中：1=线程面板 2=巨型订单面板（标题条 y-3..y+11）。 */
    private int hitPanelHandle(double mx, double my) {
        if (mx >= panelX - 3 && mx <= panelX + PANEL_WIDTH
                && my >= panelY - 3 && my <= panelY + 11) {
            return 1;
        }
        var orders = menu.fullOrders;
        if (orders != null && !orders.isEmpty()) {
            int visible = Math.min(VISIBLE_ORDERS, orders.size());
            int height = 12 + ROW_HEIGHT * visible + 4;
            if (mx >= orderPanelX - 3 && mx <= orderPanelX + ORDER_PANEL_WIDTH
                    && my >= orderPanelY - 3 && my <= orderPanelY + 11) {
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
