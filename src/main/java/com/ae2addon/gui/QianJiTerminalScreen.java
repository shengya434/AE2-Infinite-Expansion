package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.network.QianJiTerminalPagePacket;
import com.ae2addon.network.QianJiTerminalQueryPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 千机·样板终端界面（2026-09-21 v249 骨架 → v254 列表面板）。
 * <p>
 * 布局：
 * <pre>
 *   [搜索框：输入/产物/机器，回车搜索]
 *   标题 / 网络空白样板家底
 *   [空白样板槽]  ↓（灰色向下箭头）  [编码样板槽]
 *   玩家背包
 *   ── 全网样板长列表（不分页，右侧滑条，滚轮可滚）──
 * </pre>
 * 列表数据来自服务端按窗口推送（{@link QianJiTerminalQueryPacket}）：
 * **只发当前这一段**，样板本体不下发。悬停某行显示该样板的完整 tooltip。
 */
public class QianJiTerminalScreen extends AbstractContainerScreen<QianJiTerminalMenu> {

    private static final int W = 176;
    /** 高度（2026-09-21 v255：标题/搜索栏在顶、列表居中、物品栏挪到最下） */
    private static final int H = 280;
    /**
     * 有"升级卡 / 量子缠绕奇点"这一条时的高度（2026-09-21 v274）：
     * 只有 AE2WTLib 形态的宿主（WTMenuHost）才有，用来插量子桥卡与奇点。
     */
    private static final int H_EXTRA = 322;
    /**
     * 通用终端（AE2WTLib 的 WUT）形态的高度：在 H_EXTRA 之上再留一行放
     * 「打开下一个终端」按钮（2026-09-21 v273 —— sensei：通用终端界面左下角那个按钮）。
     */
    private static final int H_UNIVERSAL = 346;

    private static final int LIST_X = 6;
    /** 列表紧跟"网络空白样板"那行文字，并与物品栏换位（原来物品栏在这） */
    private static final int LIST_Y = 124;
    private static final int LIST_W = W - 12;
    private static final int LIST_H = 72;
    private static final int ROW_H = 11;
    private static final int MAX_ROWS = LIST_H / ROW_H;      // 6 行
    private static final int BAR_W = 4;
    private static final int BAR_GAP = 3;

    private EditBox searchBox;

    /** 当前列表内容（服务端回包填充） */
    private List<QianJiTerminalPagePacket.Entry> entries = List.of();
    /** 结果总数（列表模式=全网样板数；搜索模式=命中数） */
    private int total = 0;
    /** 当前关键词（空 = 列表模式） */
    private String term = "";
    /** 网络里的空白样板数量（家底） */
    private long blankCount = 0;
    /** 滚动位置（第一条可见下标） */
    private int scrollRow = 0;
    /** 刷新后要恢复到的滚动位置（-1 = 不必恢复，从头看） */
    private int pendingScroll = -1;
    /** 轮询计时（每 40 tick 静默刷一次，覆盖自动推入/他人改动等外部变化） */
    private int tickCounter = 0;

    // ── 刚插入千机的样板：跳转 + 高亮（2026-09-22 v277）──

    /** 高亮持续 tick 数（3 秒） */
    private static final int FOCUS_TICKS = 60;
    /** 目标样板所在千机坐标（null = 没有要高亮的） */
    private net.minecraft.core.BlockPos focusPos = null;
    /** 目标槽位 */
    private int focusSlot = -1;
    /** 剩余高亮 tick */
    private int focusTicks = 0;
    /** 目标在当前列表里的行下标（-1 = 没找到 / 还没加载到） */
    private int focusRow = -1;
    /** 已经认过的"最近一次推入"序号（v280：同一次推入只跳一次） */
    private long lastFocusSeq = 0L;
    private boolean dragging = false;

    public QianJiTerminalScreen(QianJiTerminalMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = W;
        // 高度随形态变：基础 280 / 加"升级卡+奇点"条 322 / 再加"下一个终端"按钮行 346
        imageHeight = menuHeight(menu);
        inventoryLabelY = 10000;
        titleLabelY = 10000;
    }

    /** 这个形态的界面高度（菜单与屏幕共用同一套判断，别各写一份） */
    private static int menuHeight(QianJiTerminalMenu menu) {
        if (menu.isUniversal()) return H_UNIVERSAL;
        return menu.hasExtras() ? H_EXTRA : H;
    }

    @Override
    protected void init() {
        super.init();
        // 搜索框放在最上面一行（终端没有翻页按钮，整条都可以给它）
        searchBox = new EditBox(font, leftPos + 8, topPos + 18, W - 16, 16, Component.literal("搜索样板"));
        searchBox.setMaxLength(64);
        addRenderableWidget(searchBox);
        setFocused(searchBox);
        // 通用终端形态：左下角那个"打开下一个终端"按钮（AE2WTLib 在才加；按下=服务端切下一个状态）
        if (menu.isUniversal()
                && com.ae2addon.compat.ae2wtlib.AE2WTLibCompat.isLoaded()) {
            var cycleBtn = com.ae2addon.compat.ae2wtlib.AE2WTLibClientCompat.createCycleButton();
            cycleBtn.setX(leftPos + 6);
            cycleBtn.setY(topPos + menuHeight(menu) - 18);
            addRenderableWidget(cycleBtn);
        }
        // 打开就拉一次列表（关键词空 = 列表模式）
        requestPage("", 0);
    }

    /** 向服务端要一段数据（offset 起，最多 MAX_PAGE 条） */
    private void requestPage(String term, int offset) {
        AE2Addon.NETWORK.sendToServer(new QianJiTerminalQueryPacket(
                term, offset, QianJiTerminalQueryPacket.MAX_PAGE));
    }

    /** 服务端回包入口（由 {@link QianJiTerminalPagePacket#handle} 调用） */
    public void acceptPage(QianJiTerminalPagePacket page) {
        // 列表数据顺带带来的"最近一次推入"（v280）：不依赖玩家对象/菜单状态，任何形态都有效。
        // 只在序号变大时认一次（同一次推入不重复跳）。
        if (page.focusPos != null && page.focusSeq > lastFocusSeq) {
            lastFocusSeq = page.focusSeq;
            this.focusPos = page.focusPos;
            this.focusSlot = page.focusSlot;
            this.focusTicks = FOCUS_TICKS;
            this.focusRow = -1;
            this.pendingScroll = -1;
        }
        // 只有关键词与当前模式一致时才接受，避免旧包把新结果顶掉
        if (!page.term.equals(this.term) && !(page.term.isEmpty() && this.total == 0)) {
            // 首次加载：term 都是空，正常接受
            if (!page.term.equals(this.term)) return;
        }
        this.term = page.term;
        this.total = page.total;
        this.blankCount = page.blankPatterns;
        if (page.offset == 0) {
            this.entries = new ArrayList<>(page.entries);
            // 刚放进千机的样板：跳过去 + 高亮（v277/v280）。**优先于**"保住滚动位置"。
            this.focusRow = indexOfFocus();
            if (focusRow >= 0) {
                boolean visible = focusRow >= scrollRow && focusRow < scrollRow + MAX_ROWS;
                if (!visible) {
                    this.scrollRow = Math.max(0, focusRow - MAX_ROWS / 2);
                    clampScroll();
                }
                this.pendingScroll = -1;
            } else if (focusTicks > 0) {
                this.pendingScroll = -1;
                if (!term.isEmpty()) {
                    // 目标被关键词过滤掉了 → 先清掉关键词重来，否则"跳过去"无从谈起
                    term = "";
                    if (searchBox != null) searchBox.setValue("");
                    requestPage("", 0);
                } else if (this.total > this.entries.size()) {
                    // 目标还没被加载到（长列表按需分段拉）→ 继续要下一段，直到找着或拉完
                    requestPage(term, this.entries.size());
                }
            } else if (pendingScroll >= 0) {
                // 刷新（动作后 / 轮询）要**保住滚动位置**，否则每次刷新都跳回顶部
                this.scrollRow = pendingScroll;
                this.pendingScroll = -1;
                clampScroll();
            } else {
                this.scrollRow = 0;
            }
        } else if (page.offset >= this.entries.size()) {
            this.entries.addAll(page.entries);
            if (focusTicks > 0) {
                this.focusRow = indexOfFocus();
                if (focusRow >= 0) {
                    this.scrollRow = Math.max(0, focusRow - MAX_ROWS / 2);
                    clampScroll();
                    this.pendingScroll = -1;
                } else if (this.total > this.entries.size()) {
                    requestPage(term, this.entries.size());
                }
            }
        }
    }

    /**
     * 服务端说"样板刚插进千机了"（v277）：把列表跳到那个栏位并高亮 3 秒。
     * <p>
     * 定位靠**坐标 + 槽号**（列表里每一行都带这两个信息），所以不用多同步任何样板数据。
     * 如果当前有关键词、而目标不在命中集里，就**先清掉关键词**再定位 —— 否则"跳过去"根本无从谈起。
     */
    public void acceptFocus(net.minecraft.core.BlockPos pos, int slot) {
        this.focusPos = pos;
        this.focusSlot = slot;
        this.focusTicks = FOCUS_TICKS;
        this.focusRow = -1;
        if (indexOfFocus() < 0 && !term.isEmpty()) {
            term = "";
            if (searchBox != null) searchBox.setValue("");
        }
        requestPage(term, 0);
    }

    /** 目标（坐标+槽号）在当前已加载列表里的行下标；-1 = 没有 / 还没加载到 */
    private int indexOfFocus() {
        if (focusPos == null || focusSlot < 0) return -1;
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            if (e.slot() == focusSlot && e.pos().equals(focusPos)) return i;
        }
        return -1;
    }

    /**
     * 重新拉取当前视图（关键词不变、从第 0 条起），并记住滚动位置。
     * <p>
     * 2026-09-21 sensei：**存入/取出样板后列表没有实时变化** → 动作后立即调它；
     * 另外每 40 tick 也静默调一次，覆盖"编码槽自动推入千机""别的玩家改动"这类
     * 不是由本界面发起的变化。
     */
    private void refreshList() {
        pendingScroll = scrollRow;
        requestPage(term, 0);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 插入高亮只亮 3 秒（v277）
        if (focusTicks > 0 && --focusTicks == 0) {
            focusPos = null;
            focusSlot = -1;
            focusRow = -1;
        }
        if (++tickCounter >= 40) {   // ≈2 秒
            tickCounter = 0;
            if (!dragging) refreshList();
        }
    }

    // ── 滚动 ──

    private int maxScroll() {
        return Math.max(0, entries.size() - MAX_ROWS);
    }

    private void clampScroll() {
        scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow));
    }

    private int barX() {
        return leftPos + LIST_X + LIST_W - BAR_GAP - BAR_W;
    }

    private int barY() {
        return topPos + LIST_Y;
    }

    private int thumbH() {
        if (entries.size() <= MAX_ROWS) return LIST_H;
        return Math.max(10, LIST_H * MAX_ROWS / Math.max(1, entries.size()));
    }

    private int thumbY() {
        int span = Math.max(1, LIST_H - thumbH());
        int max = maxScroll();
        return barY() + (max == 0 ? 0 : span * scrollRow / max);
    }

    /** 鼠标悬停在第几行（绝对下标）；-1 = 不在列表行上 */
    private int hoverRow(double mx, double my) {
        int x0 = leftPos + LIST_X;
        if (mx < x0 || mx >= barX() - 2) return -1;
        int listTop = topPos + LIST_Y;
        int shown = Math.min(entries.size() - scrollRow, MAX_ROWS);
        if (shown <= 0 || my < listTop || my >= listTop + shown * ROW_H) return -1;
        int local = (int) ((my - listTop) / ROW_H);
        int idx = scrollRow + local;
        return local >= 0 && local < shown && idx < entries.size() ? idx : -1;
    }

    private boolean inList(double mx, double my) {
        return mx >= leftPos + LIST_X && mx < leftPos + LIST_X + LIST_W
                && my >= topPos + LIST_Y && my < topPos + LIST_Y + LIST_H;
    }

    private boolean inBar(double mx, double my) {
        int x = barX();
        return mx >= x - 2 && mx <= x + BAR_W + 2 && my >= barY() && my <= barY() + LIST_H;
    }

    private void scrollFromThumb(double my) {
        int span = Math.max(1, LIST_H - thumbH());
        double rel = (my - barY() - thumbH() / 2.0) / span;
        scrollRow = (int) Math.round(rel * maxScroll());
        clampScroll();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inList(mx, my)) {
            scrollRow -= (int) Math.signum(delta);
            clampScroll();
            // 滚到已加载数据末尾附近 → 继续向服务端要下一段（长列表按需拉取）
            if (scrollRow + MAX_ROWS + 2 >= entries.size() && entries.size() < total) {
                requestPage(term, entries.size());
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (inBar(mx, my)) {
            dragging = true;
            scrollFromThumb(my);
            return true;
        }
        // 2026-09-21 v256 sensei：「列表没有取出和插入的方式」→
        // 左键点一行 = 从那一行的槽位取出到背包；右键点一行 = 把手上的千机样板放进那台千机
        int row = hoverRow(mx, my);
        if (row >= 0) {
            var entry = entries.get(row);
            AE2Addon.NETWORK.sendToServer(
                    new com.ae2addon.network.QianJiTerminalActionPacket(entry.pos(), entry.slot(), btn == 1));
            // 动作 + 查询在同一个通道里**按顺序**到达服务端，所以这里紧跟一次刷新是安全的
            refreshList();
            return true;
        }
        if (inList(mx, my)) {
            return true;   // 列表空白处吃掉点击，别落到后面的槽位
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging) {
            scrollFromThumb(my);
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {          // Enter：搜索
            String t = searchBox == null ? "" : searchBox.getValue().trim();
            term = t;
            requestPage(t, 0);
            return true;
        }
        if (keyCode == 256) {
            // Esc：关键词非空 → 先清空（回到列表模式）；已空 → **关界面**。
            // 2026-09-21 sensei 实测 bug：点过搜索栏之后 Esc 关不掉界面 ——
            // 原来是这里无条件把按键交给 EditBox，Esc 就再也到不了 super 的关闭逻辑。
            if (searchBox != null && !searchBox.getValue().isEmpty()) {
                searchBox.setValue("");
                term = "";
                requestPage("", 0);
                return true;
            }
            this.onClose();
            return true;
        }
        if (searchBox != null && searchBox.isFocused()) {
            // ⚠ 2026-09-22 v284：搜索框有焦点时**吃掉所有按键**（不再把 EditBox 的结果原样返回）。
            // 之前写的是 `return searchBox.keyPressed(...)`：EditBox 不认的键会返回 false，
            // 那个键就继续往下走（原版/其它 mod 的界面快捷键）→ sensei 实测"搜索栏没有拦截
            // 其他 mod 的关 GUI 快捷键"，打字打到一半界面被别的 mod 关掉。
            // 这里把按键在界面这一层拦下（字符输入走 charTyped，不受影响）。
            searchBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null) return searchBox.charTyped(codePoint, modifiers);
        return super.charTyped(codePoint, modifiers);
    }

    // ── 渲染 ──

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        renderBackground(g);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);

        // 标题在**搜索栏上方**（sensei 2026-09-21 要求）
        String title = "§7千机·样板终端";
        g.drawString(font, Component.literal(title),
                leftPos + W / 2 - font.width(title) / 2, topPos + 4, 0xFFFFFF, false);

        // 两个槽（菜单坐标：空白 y=40、编码 y=78）+ 中间灰色向下箭头
        g.drawString(font, Component.literal("§7空白样板"), leftPos + 100, topPos + 44, 0xAAAAAA, false);
        g.drawString(font, Component.literal("§7编码样板"), leftPos + 100, topPos + 82, 0xAAAAAA, false);
        drawDownArrow(g, leftPos + 88, topPos + 58, 0xFF8A8A8A);

        // 空白样板槽 = 网络里空白样板的"映射"（2026-09-22 v282，sensei 要求）：
        // 槽里不放东西（放进去就送进网络），这里把**网络存量**画在那个槽位上 ——
        // 于是原来那行"网络空白样板：N"就不需要了。
        renderNetworkBlankSlot(g);

        // 升级卡 / 量子缠绕奇点那一条（只有 AE2WTLib 形态的宿主才有，2026-09-21 v274）
        if (menu.hasExtras()) {
            g.drawString(font, Component.literal("§7升级卡 / 量子缠绕奇点§8（量子桥：远处也能连网）"),
                    leftPos + 4, topPos + QianJiTerminalMenu.EXTRA_LABEL_Y, 0xFFFFFF, false);
        }

        // 列表上方的状态行（跟着列表一起挪到"网络空白样板"下面）
        String status = (term.isEmpty()
                ? "§7全网样板 §e" + total + " §7张"
                : "§7搜索 §f" + term + " §7→ 命中 §e" + total + " §7张")
                + (entries.isEmpty() ? " §8(空)" : " §8(已载入 " + entries.size() + ")")
                + " §8· 左键取出 / 右键放入该千机 · 悬停看详情"
                + (focusTicks > 0 ? " §e◀ 已定位到刚放入的样板" : "");
        g.drawString(font, Component.literal(status), leftPos + 8, topPos + 112, 0xFFFFFF, false);

        renderList(g, mx, my);

        // 悬停列表行时**不画下方槽位的 tooltip**（会盖住列表），改画该行的样板 tooltip
        int row = hoverRow(mx, my);
        if (row >= 0) {
            renderRowTooltip(g, mx, my, entries.get(row));
        } else if (hoveringBlankSlot()) {
            // 空白样板槽：精确数量 + 操作提示（v283）
            renderBlankSlotTooltip(g, mx, my);
        } else {
            renderTooltip(g, mx, my);
        }
    }

    private void renderList(GuiGraphics g, int mx, int my) {
        int x0 = leftPos + LIST_X;
        int y0 = topPos + LIST_Y;

        // 覆盖层要抬到物品之上（同千机面板的教训：物品在 z=100）
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);

        g.fill(x0, y0, x0 + LIST_W, y0 + LIST_H, 0xF0101010);
        outline(g, x0, y0, LIST_W, LIST_H, 0xFF9AA0A6);

        int shown = Math.min(entries.size() - scrollRow, MAX_ROWS);
        int right = barX() - 2;
        int hover = hoverRow(mx, my);
        for (int i = 0; i < shown; i++) {
            int idx = scrollRow + i;
            int rowY = y0 + i * ROW_H;
            // 刚插入的那个栏位：金色高亮 3 秒（v277）—— 画在 hover 之下，鼠标划过仍能看出 hover
            if (focusTicks > 0 && idx == focusRow) {
                g.fill(x0 + 1, rowY, right + 6, rowY + ROW_H, 0x80B8860B);
                outline(g, x0 + 1, rowY, right + 6 - (x0 + 1), ROW_H, 0xFFFFE08A);
                g.fill(x0 + 1, rowY, x0 + 3, rowY + ROW_H, 0xFFFFE08A);
            }
            if (idx == hover) g.fill(x0 + 1, rowY, right + 6, rowY + ROW_H, 0x40FFFFFF);
            g.drawString(font, font.plainSubstrByWidth(entries.get(idx).label(), right - (x0 + 4)),
                    x0 + 4, rowY + 1, 0xFFFFFF, false);
        }
        if (shown == 0) {
            g.drawString(font, Component.literal("§8（这台网络上的千机里还没有样板）"),
                    x0 + 4, y0 + 4, 0xFFFFFF, false);
        }

        // 滑条（内容超过可视行数才画）
        if (maxScroll() > 0) {
            int bx = barX();
            g.fill(bx, barY(), bx + BAR_W, barY() + LIST_H, 0x40FFFFFF);
            int ty = thumbY();
            boolean hot = dragging || inBar(mx, my);
            g.fill(bx, ty, bx + BAR_W, ty + thumbH(), hot ? 0xFFF0F0F0 : 0xFFA8A8A8);
        }

        g.pose().popPose();
    }

    /**
     * 把"网络里的空白样板"画在空白样板槽上（2026-09-22 v282）。
     * <p>
     * 那个槽本身**不放东西**（放进去就被送进网络），所以这里画一个虚拟堆叠：
     * 图标 + 数量（数量就是网络存量，超过 64 用 k/M 缩写）。
     * 于是原来那行"网络空白样板：N"可以删掉 —— 存量直接长在槽上。
     */
    private void renderNetworkBlankSlot(GuiGraphics g) {
        var slot = menu.getSlot(QianJiTerminalMenu.SLOT_BLANK);
        if (slot == null) return;
        final int x = leftPos + slot.x;
        final int y = topPos + slot.y;
        if (blankCount <= 0) {
            // 网络里没有：只画一个淡淡的提示，别装成"有东西"
            g.drawString(font, Component.literal("§8空"), x + 4, y + 4, 0xFFFFFF, false);
            return;
        }
        final int shown = (int) Math.min(blankCount, 64);
        var icon = new net.minecraft.world.item.ItemStack(
                appeng.core.definitions.AEItems.BLANK_PATTERN.asItem(), Math.max(1, shown));
        g.renderItem(icon, x, y);
        // ⚠ 数字必须抬到物品之上：槽位物品是 AbstractContainerScreen#renderSlot 在 z=100 画的，
        // 自绘文字留在 z=0 就会被图标压住（sensei 实测"数字跑到图标下面"就是这个）。
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);
        if (blankCount > 64) {
            // 原版只会画到 64，超出的用缩写数字补在右下角
            String text = "§f" + compact(blankCount);
            g.drawString(font, Component.literal(text),
                    x + 17 - font.width(text), y + 9, 0xFFFFFF, true);
        } else {
            g.renderItemDecorations(font, icon, x, y);
        }
        g.pose().popPose();
    }

    /** 大数字缩写（只用于槽上的显示）：直接用 AE2 自己的格式化器，支持 K/M/G/T/P/E */
    private static String compact(long n) {
        try {
            // 第二个参数 = 允许的字符宽度；AE2 会在宽度内自动选单位（1.2M / 12G / 1.2T …）
            return appeng.util.ReadableNumberConverter.format(n, 4);
        } catch (Throwable t) {
            return String.valueOf(n);
        }
    }

    /** 鼠标是不是停在空白样板槽上（悬停要显示精确数量，v283） */
    private boolean hoveringBlankSlot() {
        var slot = menu.getSlot(QianJiTerminalMenu.SLOT_BLANK);
        return slot != null && this.hoveredSlot == slot;
    }

    /** 空白样板槽的 tooltip：精确数量 + 三个操作提示（v283） */
    private void renderBlankSlotTooltip(GuiGraphics g, int mx, int my) {
        var lines = new ArrayList<Component>();
        lines.add(Component.literal("§7网络里的空白样板：§e" + String.format(java.util.Locale.ROOT, "%,d", blankCount)));
        lines.add(Component.literal("§8放入 = 存进 ME 网络（槽里不留）"));
        lines.add(Component.literal("§8左键 = 从网络取 64 个到手上"));
        lines.add(Component.literal("§8Shift+左键 = 从网络取 64 个进背包"));
        g.renderComponentTooltip(font, lines, mx, my);
    }

    /** 悬停某行的 tooltip = 该样板的完整描述（与物品 tooltip 同源）+ 第一行定位 */
    private void renderRowTooltip(GuiGraphics g, int mx, int my, QianJiTerminalPagePacket.Entry entry) {
        var lines = new ArrayList<Component>();
        lines.add(Component.literal("§8[" + entry.pos().getX() + ", " + entry.pos().getY() + ", "
                + entry.pos().getZ() + "] 第 " + (entry.slot() / 54 + 1) + " 页 · 槽 " + entry.slot()));
        if (entry.tooltip() != null && !entry.tooltip().isEmpty()) {
            for (String line : entry.tooltip().split("\n")) {
                if (!line.isEmpty()) lines.add(Component.literal(line));
            }
        } else {
            lines.add(Component.literal(entry.label()));
        }
        g.renderComponentTooltip(font, lines, mx, my);
    }

    /**
     * 画一个灰色向下箭头（用 fill 拼，不引贴图/版本相关 API）。
     *
     * @param g     绘制上下文
     * @param cx    箭头水平中心
     * @param top   箭头顶端 y
     * @param color ARGB 颜色
     */
    private void drawDownArrow(GuiGraphics g, int cx, int top, int color) {
        g.fill(cx - 2, top, cx + 2, top + 7, color);
        for (int i = 0; i <= 5; i++) {
            int half = 5 - i;
            g.fill(cx - half, top + 7 + i, cx + half + 1, top + 8 + i, color);
        }
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {}
}
