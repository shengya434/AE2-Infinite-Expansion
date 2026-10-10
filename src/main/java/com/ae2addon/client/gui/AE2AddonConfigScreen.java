package com.ae2addon.client.gui;

import com.ae2addon.config.AE2AddonConfig;
import com.ae2addon.util.NumberExpr;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内配置界面（mod 列表 → 配置按钮，2026-08-27 21:46 sensei 要求）。
 * <p>
 * Forge 1.20.1 不自带通用配置 GUI，这里手写轻量版：
 * <ul>
 *   <li>顶部搜索栏：按名称/说明/分区名实时过滤（边打边筛）</li>
 *   <li>列表按**作用分区**（合成/结算/接口/显示/终端），带分区标题条</li>
 *   <li>点击数字项 → 输入框编辑，回车应用；点击布尔项 → 直接切换</li>
 *   <li>保存按钮 / 关闭界面 → 写盘（spec.save）+ 热加载（AE2AddonConfig.apply）</li>
 * </ul>
 * <p>
 * ⚠⚠ 2026-09-27 sensei 明确交代（**务必记住**）：
 * <b>本界面的配置项是「自记添加」，不会根据 config 文件（ForgeConfigSpec）自动生成。</b>
 * 也就是说 —— 在 {@link AE2AddonConfig} 里新增一个配置项之后，
 * **必须手写到下面 {@code add(...)} 那一串里，它才会出现在游戏内界面**。
 * 本次就实测到 4 项漏加（{@code QIANJI_SETTLE_CAP} / {@code QIANJI_SETTLE_CALLS_PER_TICK} /
 * {@code HOT_LOG_BUDGET_PER_TICK} / {@code FEEDER_NETWORK_PUSH_KEYS}），已补齐。
 * 反过来排查「某配置项界面里没有」时，**先怀疑漏加，而不是怀疑 config 定义**。
 */
public class AE2AddonConfigScreen extends Screen {

    private static final int ROW_H = 20;
    /** 分区标题条高度（比普通行略矮，省地方） */
    private static final int HEADER_H = 14;
    /** 列表起始 y（搜索栏在它上面） */
    private static final int TOP = 56;

    // ── 分区名（显示顺序 = 声明顺序；文案在语言文件里）──
    private static final String SEC_CPU = "gui.ae2addon.config.sec.cpu";
    private static final String SEC_QIANJI = "gui.ae2addon.config.sec.qianji";
    private static final String SEC_FEEDER = "gui.ae2addon.config.sec.feeder";
    private static final String SEC_EXTRACT = "gui.ae2addon.config.sec.extract";
    private static final String SEC_DISPLAY = "gui.ae2addon.config.sec.display";
    private static final String SEC_MISC = "gui.ae2addon.config.sec.misc";

    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    /** 过滤后的「行」列表（分区标题 + 配置项），滚动与点击都按它算 */
    private final List<Row> rows = new ArrayList<>();
    private String lastQuery = "\u0000"; // 与首次 rebuild 一定不等，保证首帧就会构建

    /** 配置项的真实 config 键（行右侧名字用它，**不再**去切显示名的破折号）。 */
    private record Entry(String sectionKey, String section, String key, String label,
                         ForgeConfigSpec.ConfigValue value, long min, long max) {
    }

    /** 渲染行：分区标题（entry == null）或配置项。 */
    private record Row(String section, Entry entry) {
    }

    private int scrollOffset;
    /** 正在编辑的行下标（rows 的下标；-1 = 未编辑） */
    private int editingRow = -1;
    private EditBox editBox;
    private EditBox searchBox;
    private Component toast = null;
    private long toastUntil = 0;

    public AE2AddonConfigScreen(Screen parent) {
        super(Component.translatable("gui.ae2addon.config.title"));
        this.parent = parent;
        // ⚠ 全部可配项在这里**手工**登记（范围与 defineInRange 一致）。
        //   新增配置项必须补到这里，否则游戏内界面看不到 —— 见类注释。
        add(SEC_CPU, "maxConcurrent",
                "gui.ae2addon.config.item.maxConcurrent",
                AE2AddonConfig.MAX_CONCURRENT, 0, Integer.MAX_VALUE);
        add(SEC_CPU, "idleLaneTarget",
                "gui.ae2addon.config.item.idleLaneTarget",
                AE2AddonConfig.IDLE_LANE_TARGET, 1, 4096);
        add(SEC_CPU, "maxBatchCount",
                "gui.ae2addon.config.item.maxBatchCount",
                AE2AddonConfig.MAX_BATCH_COUNT, 2, 10_000_000);
        add(SEC_CPU, "sharedExpCap",
                "gui.ae2addon.config.item.sharedExpCap",
                AE2AddonConfig.SHARED_EXP_CAP, 0, Long.MAX_VALUE);
        add(SEC_CPU, "dispatchBudgetPerTick",
                "gui.ae2addon.config.item.dispatchBudgetPerTick",
                AE2AddonConfig.DISPATCH_BUDGET_PER_TICK, 0, 10_000_000);
        add(SEC_CPU, "cpuTimeSliceTargetMs",
                "gui.ae2addon.config.item.cpuTimeSliceTargetMs",
                AE2AddonConfig.CPU_TIME_SLICE_TARGET_MS, 1, 500);
        add(SEC_CPU, "cheapOrderAmount",
                "gui.ae2addon.config.item.cheapOrderAmount",
                AE2AddonConfig.CHEAP_ORDER_AMOUNT, 1, Long.MAX_VALUE);

        add(SEC_QIANJI, "qianjiSettleCap",
                "gui.ae2addon.config.item.qianjiSettleCap",
                AE2AddonConfig.QIANJI_SETTLE_CAP, 1, 10_000_000);
        add(SEC_QIANJI, "qianjiSettleCallsPerTick",
                "gui.ae2addon.config.item.qianjiSettleCallsPerTick",
                AE2AddonConfig.QIANJI_SETTLE_CALLS_PER_TICK, 0, 100_000);
        add(SEC_QIANJI, "hotLogBudgetPerTick",
                "gui.ae2addon.config.item.hotLogBudgetPerTick",
                AE2AddonConfig.HOT_LOG_BUDGET_PER_TICK, 0, 1_000_000);

        add(SEC_FEEDER, "feederFeedBudget",
                "gui.ae2addon.config.item.feederFeedBudget",
                AE2AddonConfig.FEEDER_FEED_BUDGET, 1, 1_000_000);
        add(SEC_FEEDER, "feederFeedStack",
                "gui.ae2addon.config.item.feederFeedStack",
                AE2AddonConfig.FEEDER_FEED_STACK, 1, Integer.MAX_VALUE);
        add(SEC_FEEDER, "feederRestockInterval",
                "gui.ae2addon.config.item.feederRestockInterval",
                AE2AddonConfig.FEEDER_RESTOCK_INTERVAL, 1, 200);
        add(SEC_FEEDER, "feederStockTarget",
                "gui.ae2addon.config.item.feederStockTarget",
                AE2AddonConfig.FEEDER_STOCK_TARGET, 0, Long.MAX_VALUE);
        add(SEC_FEEDER, "feederPowerFeCap",
                "gui.ae2addon.config.item.feederPowerFeCap",
                AE2AddonConfig.FEEDER_POWER_FE_CAP, 1, Integer.MAX_VALUE);
        add(SEC_FEEDER, "feederPowerPassesPerTick",
                "gui.ae2addon.config.item.feederPowerPassesPerTick",
                AE2AddonConfig.FEEDER_POWER_PASSES, 1, 1024);

        add(SEC_EXTRACT, "feederExtractInterval",
                "gui.ae2addon.config.item.feederExtractInterval",
                AE2AddonConfig.FEEDER_EXTRACT_INTERVAL, 1, 10000);
        add(SEC_EXTRACT, "feederExtractStack",
                "gui.ae2addon.config.item.feederExtractStack",
                AE2AddonConfig.FEEDER_EXTRACT_STACK, 1, Integer.MAX_VALUE);
        add(SEC_EXTRACT, "feederExtractFluid",
                "gui.ae2addon.config.item.feederExtractFluid",
                AE2AddonConfig.FEEDER_EXTRACT_FLUID, 1, Integer.MAX_VALUE);
        add(SEC_EXTRACT, "feederExtractGas",
                "gui.ae2addon.config.item.feederExtractGas",
                AE2AddonConfig.FEEDER_EXTRACT_GAS, 1, Integer.MAX_VALUE);
        add(SEC_EXTRACT, "feederExtractLoopLimit",
                "gui.ae2addon.config.item.feederExtractLoopLimit",
                AE2AddonConfig.FEEDER_EXTRACT_LOOP_CAP, 0, 2_000_000_000);
        add(SEC_EXTRACT, "feederNetworkPushKeys",
                "gui.ae2addon.config.item.feederNetworkPushKeys",
                AE2AddonConfig.FEEDER_NETWORK_PUSH_KEYS, 1, 4096);

        add(SEC_DISPLAY, "cellDisplayBytes",
                "gui.ae2addon.config.item.cellDisplayBytes",
                AE2AddonConfig.CELL_DISPLAY_BYTES, 1, Long.MAX_VALUE);
        add(SEC_DISPLAY, "infiniteItemAmount",
                "gui.ae2addon.config.item.infiniteItemAmount",
                AE2AddonConfig.INFINITE_ITEM_AMOUNT, 1, Long.MAX_VALUE);
        add(SEC_DISPLAY, "cpuDisplayBytes",
                "gui.ae2addon.config.item.cpuDisplayBytes",
                AE2AddonConfig.CPU_DISPLAY_BYTES, 1, Long.MAX_VALUE);
        add(SEC_DISPLAY, "cpuDisplayThreads",
                "gui.ae2addon.config.item.cpuDisplayThreads",
                AE2AddonConfig.CPU_DISPLAY_THREADS, 0, 100_000_000);
        add(SEC_DISPLAY, "cpuStorageText",
                "gui.ae2addon.config.item.cpuStorageText",
                AE2AddonConfig.CPU_STORAGE_TEXT, 0, 0);
        add(SEC_DISPLAY, "cpuThreadsText",
                "gui.ae2addon.config.item.cpuThreadsText",
                AE2AddonConfig.CPU_THREADS_TEXT, 0, 0);

        add(SEC_MISC, "wirelessTerminalCapacity",
                "gui.ae2addon.config.item.wirelessTerminalCapacity",
                AE2AddonConfig.WIRELESS_TERMINAL_CAPACITY, 0, Long.MAX_VALUE);
        add(SEC_MISC, "debugLogs",
                "gui.ae2addon.config.item.debugLogs",
                AE2AddonConfig.DEBUG_LOGS, 0, 0);
    }

    /**
     * 登记一个配置项。
     * <p>
     * 2026-10-10（i18n）：显示名不再写死在代码里 —— 传进来的 {@code label} 是**翻译键**
     * （{@code gui.ae2addon.config.item.<configKey>}），{@code key} 是配置真名
     * （与 {@code defineInRange} 的第一参数一致，行右侧显示与搜索都用它）。
     * 分区同理：{@code sectionKey} 是 {@code gui.ae2addon.config.sec.*}。
     */
    private void add(String sectionKey, String key, String labelKey, ForgeConfigSpec.ConfigValue value,
                     long min, long max) {
        entries.add(new Entry(sectionKey, I18n.get(sectionKey), key, I18n.get(labelKey),
                value, min, max));
    }

    @Override
    protected void init() {
        // 顶部搜索栏（2026-09-27 sensei：配置项太多，要能搜）
        searchBox = new EditBox(font, 10, 32, width - 20, 16,
                Component.translatable("gui.ae2addon.config.search_placeholder"));
        searchBox.setHint(Component.translatable("gui.ae2addon.config.search_hint"));
        searchBox.setMaxLength(64);
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox); // 一进来就能直接打字搜

        addRenderableWidget(Button.builder(Component.translatable("gui.ae2addon.config.save"),
                b -> saveAndClose())
                .bounds(width / 2 - 120, height - 30, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ae2addon.config.cancel"), b -> {
            minecraft.setScreen(parent);
        }).bounds(width / 2 + 10, height - 30, 110, 20).build());
    }

    /** 按当前搜索词重建 rows（分区标题只在有命中项时出现）。 */
    private void rebuildRows() {
        String q = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase();
        rows.clear();
        String current = null;
        for (Entry e : entries) {
            boolean hit = q.isEmpty()
                    || e.label().toLowerCase().contains(q)
                    || e.section().toLowerCase().contains(q)
                    || e.key().toLowerCase().contains(q);
            if (!hit) {
                continue;
            }
            if (!e.section().equals(current)) {
                current = e.section();
                rows.add(new Row(current, null)); // 分区标题
            }
            rows.add(new Row(current, e));
        }
        scrollOffset = 0;
    }

    /** 当前可见高度能放下多少像素（分区标题比普通行矮，所以按像素裁）。 */
    private int listBottom() {
        return height - 38;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        // 搜索词变了就重建（放在 render 里是为了兼容粘贴/退格/输入法等所有输入路径）
        String now = searchBox == null ? "" : searchBox.getValue();
        if (!now.equals(lastQuery)) {
            lastQuery = now;
            rebuildRows();
        }

        renderBackground(g);
        g.drawCenteredString(font, Component.translatable("gui.ae2addon.config.header"),
                width / 2, 8, 0xFFFFFF);
        g.drawString(font, Component.translatable("gui.ae2addon.config.help", rows.size()),
                10, 20, 0x888888);

        int bottom = listBottom();
        int y = TOP;
        for (int i = scrollOffset; i < rows.size(); i++) {
            int h = rows.get(i).entry() == null ? HEADER_H : ROW_H;
            if (y + h > bottom) {
                break;
            }
            Row row = rows.get(i);
            if (row.entry() == null) {
                // 分区标题条
                g.fill(4, y, width - 4, y + h - 1, 0x66404A2E);
                g.drawString(font, Component.literal("§e§l" + row.section()),
                        10, y + 3, 0xFFD76A);
            } else {
                Entry entry = row.entry();
                boolean isEditing = i == editingRow;
                g.fill(6, y, width - 6, y + h - 2, isEditing ? 0x553366FF : 0x22000000);
                if (!isEditing) {
                    g.drawString(font, Component.literal(entry.label()), 16, y + 5, 0xFFFFFF);
                    String val = String.valueOf(entry.value().get());
                    if (entry.value().get() instanceof Boolean b) {
                        val = b ? "ON" : "OFF";
                    }
                    g.drawString(font, Component.literal(val), width - 130, y + 5, 0xAAAAAA);
                }
            }
            y += h;
        }

        if (toast != null && System.currentTimeMillis() < toastUntil) {
            g.drawCenteredString(font, toast, width / 2, height - 52, 0x66FF66);
        }
        // 搜索栏/按钮/值输入框都由 super 统一渲染（避免双重渲染）
        super.render(g, mx, my, partial);
    }

    /** 命中测试：返回 rows 下标，未命中返回 -1（分区标题返回 -2，表示"占位但不响应"）。 */
    private int rowAt(double my) {
        int y = TOP;
        int bottom = listBottom();
        for (int i = scrollOffset; i < rows.size(); i++) {
            int h = rows.get(i).entry() == null ? HEADER_H : ROW_H;
            if (y + h > bottom) {
                break;
            }
            if (my >= y && my < y + h) {
                return rows.get(i).entry() == null ? -2 : i;
            }
            y += h;
        }
        return -1;
    }

    private int rowY(int rowIndex) {
        int y = TOP;
        int bottom = listBottom();
        for (int i = scrollOffset; i < rows.size(); i++) {
            int h = rows.get(i).entry() == null ? HEADER_H : ROW_H;
            if (y + h > bottom) {
                break;
            }
            if (i == rowIndex) {
                return y;
            }
            y += h;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (editBox != null && editingRow >= 0) {
            // 点击输入框外 → 应用编辑
            if (!editBox.isMouseOver(mx, my)) {
                applyEdit();
            }
            return super.mouseClicked(mx, my, btn);
        }
        if (mx > 4 && mx < width - 4 && my > TOP && my < listBottom()) {
            int idx = rowAt(my);
            if (idx >= 0) {
                startEdit(idx);
                return true;
            }
            if (idx == -2) {
                return true; // 分区标题：吃掉点击，别落到后面
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        // 可见行数下界（分区标题比普通行矮，这里用 ROW_H 估算是保守的）
        int fit = Math.max(1, (listBottom() - TOP) / ROW_H);
        int max = Math.max(0, rows.size() - fit);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset - (int) delta * 3));
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (editBox != null) {
            return editBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editBox != null) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                applyEdit();
                return true;
            }
            if (keyCode == 256) { // Esc
                cancelEdit();
                return true;
            }
            return editBox.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == 256 && searchBox != null && !searchBox.getValue().isEmpty()) {
            // Esc：先清空搜索词（不清就交给父类关界面）
            searchBox.setValue("");
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 编辑逻辑 ──

    private void startEdit(int rowIndex) {
        Entry entry = rows.get(rowIndex).entry();
        if (entry == null) {
            return;
        }
        Object current = entry.value().get();
        if (current instanceof Boolean b) {
            // 布尔：直接切换
            entry.value().set(!b);
            showToast(I18n.get("gui.ae2addon.config.toast.toggled",
                    nameOf(entry), !b ? "ON" : "OFF"));
            return;
        }
        editingRow = rowIndex;
        int y = rowY(rowIndex);
        if (y < 0) {
            editingRow = -1;
            return; // 行不可见
        }
        if (searchBox != null) {
            searchBox.setFocused(false); // 让键位进值输入框
        }
        editBox = new EditBox(font, 16, y + 2, width - 160, 16,
                Component.translatable("gui.ae2addon.config.value_placeholder"));
        editBox.setValue(String.valueOf(current));
        editBox.setMaxLength(64);
        // ⚠ 必须用 addRenderableWidget：addWidget 只进 children/narratables，
        //   **不进 renderables** ⇒ 用 addWidget 的话这个输入框根本不会显示
        //   （原实现是靠 render() 里手动 editBox.render(...) 兜的）。
        addRenderableWidget(editBox);
        this.setFocused(editBox);
    }

    private void applyEdit() {
        if (editBox == null || editingRow < 0) {
            return;
        }
        Entry entry = rows.get(editingRow).entry();
        String text = editBox.getValue().trim();
        try {
            Object current = entry.value().get();
            Object parsed;
            if (current instanceof Boolean) {
                parsed = current; // 布尔不走输入框
                return;
            }
            if (current instanceof String) {
                entry.value().set(text); // 文本项：直接存字符串（2026-09-03）
                showToast(I18n.get("gui.ae2addon.config.toast.modified", nameOf(entry),
                        text.isEmpty() ? I18n.get("gui.ae2addon.config.toast.empty") : text));
                cancelEdit();
                return;
            }
            long raw = NumberExpr.parse(text);
            // 超限自动回退（2026-08-27 22:00 sensei 要求）
            long clamped = Math.max(entry.min(), Math.min(entry.max(), raw));
            if (current instanceof Integer) {
                parsed = (int) clamped;
            } else {
                parsed = clamped;
            }
            entry.value().set(parsed);
            if (clamped != raw) {
                showToast(I18n.get(entry.max() < raw
                                ? "gui.ae2addon.config.toast.clamped_max"
                                : "gui.ae2addon.config.toast.clamped_min",
                        nameOf(entry), entry.max() < raw ? entry.max() : entry.min()));
            } else {
                showToast(I18n.get("gui.ae2addon.config.toast.modified", nameOf(entry), clamped));
            }
        } catch (NumberFormatException e) {
            showToast(I18n.get("gui.ae2addon.config.toast.invalid", e.getMessage()));
        }
        cancelEdit();
    }

    /** 行右侧显示的配置名 = 配置真名（原先靠切显示名的破折号，换语言就会错）。 */
    private static String nameOf(Entry entry) {
        return entry.key();
    }

    private void cancelEdit() {
        editingRow = -1;
        if (editBox != null) {
            removeWidget(editBox);
            editBox = null;
        }
        if (searchBox != null) {
            // 焦点还给搜索栏；必须用 Screen#setFocused（只调 widget.setFocused 不会更新
            // Screen 自己的 focused 字段，键位会继续发给已移除的旧控件）
            this.setFocused(searchBox);
        }
    }

    private void showToast(String msg) {
        toast = Component.literal(msg);
        toastUntil = System.currentTimeMillis() + 3000;
    }

    private void saveAndClose() {
        cancelEdit();
        try {
            // 写盘 + 热加载
            AE2AddonConfig.SPEC.save();
            AE2AddonConfig.apply();
            showToast(I18n.get("gui.ae2addon.config.toast.saved"));
        } catch (RuntimeException e) {
            showToast(I18n.get("gui.ae2addon.config.toast.save_failed", e.getMessage()));
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        cancelEdit();
        try {
            AE2AddonConfig.SPEC.save();
            AE2AddonConfig.apply();
        } catch (RuntimeException ignored) {
        }
        super.onClose();
    }
}
