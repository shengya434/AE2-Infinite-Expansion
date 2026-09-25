package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.network.QianJiSearchRequestPacket;
import com.ae2addon.network.QianJiSearchResultPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 千机·阿比舒 样板管理界面
 * <p>
 * 翻页通过 handleInventoryButtonClick 发送服务端包，
 * 服务端响应后同步槽位数据回客户端。
 * <p>
 * 2026-09-21（v244）：加**千机自有样板搜索**。设计取舍：
 * <ul>
 *   <li>搜索在**服务端**做（{@link QianJiSearchRequestPacket}），客户端只发关键词、只收命中 ——
 *       1280 个槽绝不能整包同步（本项目铁律）。</li>
 *   <li>面板做成**覆盖层**而不是往界面里加一行：GUI 已经排满了
 *       （样板网格 6 行到 y=146、玩家背包从 y=140 起、快捷栏 y=198、总高 222），
 *       硬塞输入框就得把所有槽位坐标下移，回归面太大。覆盖层零改动原有布局。</li>
 *   <li>面板底边停在 y=138（玩家背包从 140 起），搜索时**仍然能拖物品**。</li>
 * </ul>
 */
public class QianJiScreen extends AbstractContainerScreen<QianJiMenu> {

    private static final int W = 176;
    private static final int H = 222;
    private static final int BTN_PREV = 0;
    private static final int BTN_NEXT = 1;
    /** 与 {@code QianJiMenu.JUMP_BASE} 必须一致：跳页按钮 id = JUMP_BASE + 页号 */
    private static final int JUMP_BASE = 100;
    /** 每页槽数（与菜单一致） */
    private static final int PAGE_SIZE = 54;
    /** 样板总槽数（只用于算总页数） */
    private static final int PATTERN_SLOTS = 1280;

    // ── 搜索面板几何（覆盖层，全部相对 GUI 左上角） ──
    private static final int PANEL_X = 6;
    private static final int PANEL_Y = 24;
    private static final int PANEL_W = W - 12;
    private static final int PANEL_H = 114;
    private static final int ROW_H = 11;
    private static final int LIST_TOP = PANEL_Y + 16;
    /** 面板里一次最多列几行命中（放不下的靠滑条/滚轮看） */
    private static final int MAX_ROWS = (PANEL_H - 16 - 14) / ROW_H;
    /** 结果列表的可视高度 */
    private static final int LIST_H = MAX_ROWS * ROW_H;
    /** 右侧滑条：宽度与离列表右缘的间距 */
    private static final int BAR_W = 4;
    private static final int BAR_GAP = 3;

    /**
     * 覆盖层的 z 偏移。**这个值不是随便定的**：MC 渲染槽位里的物品时会把位姿上移
     * {@code translate(x, y, 100)}（见 {@code AbstractContainerScreen#renderSlot}），
     * 所以任何画在 z=0 的文字/底板都会被**物品贴图压住**（2026-09-21 sensei 实测：
     * "搜索列表的文字被样板贴图盖住"）。抬到 100 以上就能稳定盖住物品。
     */
    private static final float OVERLAY_Z = 300.0F;

    private Button prevBtn;
    private Button nextBtn;
    private Button searchBtn;
    private EditBox searchBox;

    /** 搜索面板是否展开 */
    private boolean searchOpen = false;
    /** 最近一次搜索的关键词 */
    private String searchTerm = "";
    /** 最近一次搜索的命中（用于结果列表、绿框高亮、点击定位） */
    private List<QianJiSearchResultPacket.Hit> hits = List.of();
    /** 面板里的状态文字（还没搜 / 搜了 0 条 / 请输入关键词） */
    private String searchStatus = "";
    /** 结果列表滚动位置：第一条可见命中的下标（0 = 顶部） */
    private int scrollRow = 0;
    /** 是否正在拖右侧滑条（拖拽期间忽略列表行的点击） */
    private boolean draggingScrollbar = false;

    public QianJiScreen(QianJiMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = W;
        imageHeight = H;
        inventoryLabelY = 10000;
        titleLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        int cx = leftPos + W / 2;

        prevBtn = addRenderableWidget(Button.builder(
                Component.literal("◀"),
                btn -> sendButtonClick(BTN_PREV)
        ).bounds(cx - 52, topPos + 5, 20, 16).build());

        nextBtn = addRenderableWidget(Button.builder(
                Component.literal("▶"),
                btn -> sendButtonClick(BTN_NEXT)
        ).bounds(cx + 32, topPos + 5, 20, 16).build());

        // 搜索入口：顶行最左边那块空位（◀ 在 cx-52，左边空着）
        searchBtn = addRenderableWidget(Button.builder(
                Component.literal("搜"),
                btn -> toggleSearch()
        ).bounds(leftPos + 6, topPos + 5, 20, 16).build());

        // 输入框：占顶行整条（搜索展开时把 ◀/▶/搜 三个按钮藏起来，避免重叠）
        searchBox = new EditBox(font, leftPos + 8, topPos + 4, 120, 16, Component.literal("搜索样板"));
        searchBox.setMaxLength(64);
        addRenderableWidget(searchBox);

        applySearchVisibility();
    }

    /** 按 {@link #searchOpen} 摆平四个控件的可见性与焦点（init 与开关面板都要调） */
    private void applySearchVisibility() {
        // 1.20.1 的 AbstractWidget 只有公开字段 visible，没有 setVisible()（EditBox 才有那个方法）
        // ——统一用字段赋值，免得按钮那份编译不过
        if (searchBox != null) searchBox.visible = searchOpen;
        if (prevBtn != null) prevBtn.visible = !searchOpen;
        if (nextBtn != null) nextBtn.visible = !searchOpen;
        if (searchBtn != null) searchBtn.visible = !searchOpen;
        if (searchOpen && searchBox != null) {
            searchBox.setFocused(true);
            setFocused(searchBox);
        }
    }

    /** 发送翻页按钮点击到服务端 */
    private void sendButtonClick(int buttonId) {
        Minecraft.getInstance().gameMode
                .handleInventoryButtonClick(menu.containerId, buttonId);
    }

    // ── 搜索 ──

    private void toggleSearch() {
        searchOpen = !searchOpen;
        if (searchOpen) {
            searchBox.setValue("");
            searchTerm = "";
            searchStatus = "";
            hits = List.of();
            scrollRow = 0;
            draggingScrollbar = false;
        }
        applySearchVisibility();
    }

    /** 收起面板但**保留命中**：绿框继续留在槽位上，方便对照 */
    private void closeSearch() {
        searchOpen = false;
        draggingScrollbar = false;
        applySearchVisibility();
    }

    private void doSearch() {
        String term = searchBox == null ? "" : searchBox.getValue().trim();
        searchTerm = term;
        scrollRow = 0;               // 新一次搜索从列表顶部看起
        draggingScrollbar = false;
        if (term.isEmpty()) {
            hits = List.of();
            searchStatus = "§e先输入关键词";
            return;
        }
        searchStatus = "§7搜索中…";
        AE2Addon.NETWORK.sendToServer(new QianJiSearchRequestPacket(term));
    }

    /**
     * 网络回包入口（服务端 → 客户端）。由 {@link QianJiSearchResultPacket#handle} 调用。
     * <p>
     * 收到结果后**自动跳到第一条命中所在的页**：搜索的意义就是"找东西"，
     * 让用户再手动翻 24 页找绿框不合理。
     *
     * @param term   本次关键词
     * @param result 命中列表（可能为空）
     */
    public void acceptSearchResult(String term, List<QianJiSearchResultPacket.Hit> result) {
        this.searchTerm = term;
        this.hits = result == null ? List.of() : result;
        this.searchStatus = this.hits.isEmpty()
                ? "§c没有命中的样板 §8（试试产物名 / 机器名 / 注册名）"
                : "";
        this.scrollRow = 0;
        if (!this.hits.isEmpty()) {
            jumpToSlot(this.hits.get(0).slot());
        }
    }

    /** 跳到某个样板槽所在的页（走现有按钮通道，服务端会夹到合法范围） */
    private void jumpToSlot(int slot) {
        int page = Math.max(0, slot) / PAGE_SIZE;
        sendButtonClick(JUMP_BASE + page);
    }

    /** 点击落在第几条命中上（**绝对下标**，已含滚动）；-1 = 没落在任何一行 */
    private int hitRow(double mx, double my) {
        if (hits.isEmpty()) return -1;
        int x0 = leftPos + PANEL_X;
        int x1 = x0 + PANEL_W;
        // 滑条那一条竖带不算列表行，免得拖滑条时误触
        if (mx < x0 + 2 || mx >= barTrackX() - 2) return -1;
        int listTop = topPos + LIST_TOP;
        int shown = Math.min(hits.size() - scrollRow, MAX_ROWS);
        if (shown <= 0) return -1;
        if (my < listTop - 1 || my >= listTop + shown * ROW_H) return -1;
        int local = (int) ((my - (listTop - 1)) / ROW_H);
        if (local < 0 || local >= shown) return -1;
        int idx = scrollRow + local;
        return idx < hits.size() ? idx : -1;
    }

    // ── 结果列表滚动 / 滑条 ──

    /** 还能往下滚几行（0 = 全都看得见，不需要滑条） */
    private int maxScroll() {
        return Math.max(0, hits.size() - MAX_ROWS);
    }

    private void clampScroll() {
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow));
    }

    /** 滑条轨道的 x（贴在列表右缘内側） */
    private int barTrackX() {
        return leftPos + PANEL_X + PANEL_W - BAR_GAP - BAR_W;
    }

    private int barTrackY() {
        return topPos + LIST_TOP;
    }

    /** 滑块高度：按"可见行 / 总行数"比例缩放，最小 10px 免得点不到 */
    private int barThumbH() {
        if (hits.size() <= MAX_ROWS) return LIST_H;
        return Math.max(10, LIST_H * MAX_ROWS / Math.max(1, hits.size()));
    }

    private int barThumbY() {
        int span = Math.max(1, LIST_H - barThumbH());
        int max = maxScroll();
        return barTrackY() + (max == 0 ? 0 : span * scrollRow / max);
    }

    /** 鼠标在滑条竖带上（稍微放宽 2px，方便点中） */
    private boolean insideBar(double mx, double my) {
        int x = barTrackX();
        return mx >= x - 2 && mx <= x + BAR_W + 2
                && my >= barTrackY() && my <= barTrackY() + LIST_H;
    }

    /** 拖滑条：把鼠标位置换算成滚动位置（以滑块中心对齐鼠标） */
    private void setScrollFromThumb(double my) {
        int span = Math.max(1, LIST_H - barThumbH());
        double rel = (my - barTrackY() - barThumbH() / 2.0) / span;
        scrollRow = (int) Math.round(rel * maxScroll());
        clampScroll();
    }

    private boolean insidePanel(double mx, double my) {
        return mx >= leftPos + PANEL_X && mx < leftPos + PANEL_X + PANEL_W
                && my >= topPos + PANEL_Y && my < topPos + PANEL_Y + PANEL_H;
    }

    private boolean insideBox(double mx, double my) {
        return searchBox != null && searchBox.isMouseOver(mx, my);
    }

    // ── 输入 ──

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (searchOpen) {
            // 滑条优先：点在滑条竖带上就进入拖拽状态（并立刻把滑块吸附到鼠标）
            if (insideBar(mx, my)) {
                draggingScrollbar = true;
                setScrollFromThumb(my);
                return true;
            }
            int row = hitRow(mx, my);
            if (row >= 0) {
                jumpToSlot(hits.get(row).slot());
                closeSearch();          // 跳过去就收起，直接看绿框
                return true;
            }
            if (insideBox(mx, my)) {
                searchBox.mouseClicked(mx, my, btn);
                setFocused(searchBox);
                return true;
            }
            if (insidePanel(mx, my)) {
                return true;            // 面板内空白处：吃掉点击，别落到后面的槽位
            }
            closeSearch();              // 面板外点击 = 收起，同样不落到槽位
            return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dragX, double dragY) {
        if (searchOpen && draggingScrollbar) {
            setScrollFromThumb(my);
            return true;
        }
        return super.mouseDragged(mx, my, btn, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mx, my, btn);
    }

    /** 滚轮翻结果（鼠标在面板上时才吃事件，免得抢了别处的滚轮） */
    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (searchOpen && insidePanel(mx, my) && maxScroll() > 0) {
            scrollRow -= (int) Math.signum(delta);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchOpen && searchBox != null) {
            return searchBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchOpen) {
            if (keyCode == 257 || keyCode == 335) {   // Enter / 小键盘 Enter
                doSearch();
                return true;
            }
            if (keyCode == 256) {                     // Esc：只收面板，不关界面
                closeSearch();
                return true;
            }
            if (searchBox != null) {
                return searchBox.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 渲染 ──

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        renderBackground(g);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);

        // 面板展开时，原有的顶行文字与标签全部不画（面板盖住它们，画了只会露边）
        if (!searchOpen) {
            int page = menu.getCurrentPage();
            int maxPage = (PATTERN_SLOTS + PAGE_SIZE - 1) / PAGE_SIZE - 1;
            String pageText = (hits.isEmpty() ? "" : "§a" + hits.size() + "命中 §8| ")
                    + "§7" + (page + 1) + " / " + (maxPage + 1);
            g.drawString(font, Component.literal(pageText),
                    leftPos + W / 2 - font.width(pageText) / 2, topPos + 7, 0xFFFFFF, false);

            g.drawString(font, Component.literal("§7催化剂"),
                    leftPos + 28, topPos + 22, 0x888888, false);

            boolean cpuOnline = menu.isIntegratedCpuOnline();
            g.drawString(font, Component.literal(cpuOnline ? "§7集成CPU：§a在线" : "§7集成CPU：§c未在线"),
                    leftPos + 84, topPos + 22, 0xFFFFFF, false);
            g.drawString(font, Component.literal("§7当前并行数：" + menu.parallelText()),
                    leftPos + 84, topPos + 32, 0xFFFFFF, false);

            g.drawString(font, Component.literal("§7样板"),
                    leftPos + 8, topPos + 42, 0x555555, false);
        }

        // 覆盖层整体抬到物品之上（见 OVERLAY_Z 注释）：
        // 绿框与搜索面板都必须抬，否则会被样板槽里的物品贴图盖住
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, OVERLAY_Z);
        renderHitHighlights(g);
        if (searchOpen) renderSearchPanel(g, mx, my, partialTick);
        g.pose().popPose();

        // 面板展开时**不画**下层槽位的 tooltip：1.20.1 的 render 链不自动调 renderTooltip
        // （项目 2026-08-28 反编译实锤过），所以这一处就是唯一调用点 —— 不调就等于拦截成功。
        // 改画"悬停那一行命中"自己的 tooltip（内容 = 样板物品的 tooltip，见 renderHitTooltip）。
        if (searchOpen) {
            int hover = hitRow(mx, my);
            if (hover >= 0) renderHitTooltip(g, mx, my, hits.get(hover));
        } else {
            renderTooltip(g, mx, my);
        }
    }

    /** 给"当前页里命中"的样板槽画绿框（跨页的命中要翻过去才看得到） */
    private void renderHitHighlights(GuiGraphics g) {
        if (hits.isEmpty()) return;
        int page = menu.getCurrentPage();
        for (var hit : hits) {
            if (hit.slot() / PAGE_SIZE != page) continue;
            int idx = hit.slot() % PAGE_SIZE;
            int x = leftPos + 8 + (idx % 9) * 18;
            int y = topPos + 40 + (idx / 9) * 18;
            outline(g, x - 1, y - 1, 18, 18, 0xFF00E000);
        }
    }

    private void renderSearchPanel(GuiGraphics g, int mx, int my, float partialTick) {
        int x0 = leftPos + PANEL_X;
        int y0 = topPos + PANEL_Y;
        int x1 = x0 + PANEL_W;
        int y1 = y0 + PANEL_H;

        g.fill(x0, y0, x1, y1, 0xF0101010);
        outline(g, x0, y0, PANEL_W, PANEL_H, 0xFF9AA0A6);

        g.drawString(font, "§7搜索 §8(输入/产物/机器 · Enter 搜索 · Esc 收起)",
                x0 + 4, y0 + 3, 0xFFFFFF, false);

        int listTop = topPos + LIST_TOP;
        if (hits.isEmpty()) {
            g.drawString(font,
                    searchStatus.isEmpty() ? "§8在这台千机的 1280 个槽里搜样板" : searchStatus,
                    x0 + 4, listTop + 2, 0xFFFFFF, false);
        } else {
            int shown = Math.min(hits.size() - scrollRow, MAX_ROWS);
            int hoverRow = hitRow(mx, my);              // 绝对下标（已含滚动）
            int listRight = barTrackX() - 2;            // 文字不许压到滑条上
            for (int i = 0; i < shown; i++) {
                int idx = scrollRow + i;
                var hit = hits.get(idx);
                int rowY = listTop + i * ROW_H;
                if (idx == hoverRow) {
                    g.fill(x0 + 2, rowY - 1, listRight + 6, rowY + ROW_H - 1, 0x40FFFFFF);
                }
                String line = "§8[" + (hit.slot() / PAGE_SIZE + 1) + "页 槽" + hit.slot() + "] §f" + hit.label();
                g.drawString(font, font.plainSubstrByWidth(line, listRight - (x0 + 4)),
                        x0 + 4, rowY, 0xFFFFFF, false);
            }
            renderScrollbar(g, mx, my);
        }

        String bottom = hits.isEmpty()
                ? "§8点结果跳页 · 命中槽画绿框"
                : "§7命中 §e" + hits.size() + " §7条 §8(" + (scrollRow + 1) + "-"
                  + Math.min(hits.size(), scrollRow + MAX_ROWS) + ")"
                  + (maxScroll() > 0 ? " §8· 滚轮或拖滑条" : "")
                  + " §8· 点击跳页";
        g.drawString(font, bottom, x0 + 4, y1 - 11, 0xFFFFFF, false);

        if (searchBox != null) {
            searchBox.render(g, mx, my, partialTick);
        }
    }

    /** 画一个 1px 边框（用 4 条 fill 拼：不依赖 renderOutline 这类版本相关 API） */
    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 画"鼠标悬停的那条命中"的 tooltip。
     * <p>
     * 内容 = 服务端用 {@code QianJiPatternData#describe()} 拼好的那些行（**与样板物品本身的
     * tooltip 同源**：输入 / 主产物 / 概率产出+概率值 / 来源机器），第一行额外标注它落在哪一页哪一槽。
     * 这样搜索结果里几条看着相似时，悬停就能分辨出差别（2026-09-21 sensei：
     * "只显示机器名和产物，区分效果不太强"）。
     *
     * @param g   绘制上下文
     * @param mx  鼠标 x
     * @param my  鼠标 y
     * @param hit 悬停的命中条目
     */
    private void renderHitTooltip(GuiGraphics g, int mx, int my, QianJiSearchResultPacket.Hit hit) {
        var lines = new ArrayList<Component>();
        lines.add(Component.literal("§8[第 " + (hit.slot() / PAGE_SIZE + 1) + " 页 · 槽 " + hit.slot() + "]"));
        if (hit.tooltip() != null && !hit.tooltip().isEmpty()) {
            for (String line : hit.tooltip().split("\n")) {
                if (!line.isEmpty()) lines.add(Component.literal(line));
            }
        } else {
            lines.add(Component.literal(hit.label()));
        }
        // renderComponentTooltip 内部会把位姿抬到 z=400 —— 必然盖在面板（我们抬到 300）之上
        g.renderComponentTooltip(font, lines, mx, my);
    }

    /**
     * 右侧滚动滑条。命中数不超过可视行数时**不画**——没什么可滚的，画了只是噪音。
     * 拖拽中或鼠标悬停时滑块提亮，给个"能拖"的反馈。
     *
     * @param mx 当前鼠标 x（{@code Screen} 在 1.20.1 没存鼠标位置，得从 render 传进来）
     * @param my 当前鼠标 y
     */
    private void renderScrollbar(GuiGraphics g, int mx, int my) {
        if (maxScroll() <= 0) return;
        int x = barTrackX();
        int y = barTrackY();
        g.fill(x, y, x + BAR_W, y + LIST_H, 0x40FFFFFF);
        int thumbY = barThumbY();
        boolean hot = draggingScrollbar || insideBar(mx, my);
        g.fill(x, thumbY, x + BAR_W, thumbY + barThumbH(), hot ? 0xFFF0F0F0 : 0xFFA8A8A8);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {}
}
