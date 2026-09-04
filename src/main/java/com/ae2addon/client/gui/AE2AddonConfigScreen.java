package com.ae2addon.client.gui;

import com.ae2addon.config.AE2AddonConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 游戏内配置界面（mod 列表 → 配置按钮，2026-08-27 21:46 sensei 要求）。
 * <p>
 * Forge 1.20.1 不自带通用配置 GUI，这里手写轻量版：\n
 * - 滚动列表显示全部配置项（名称 + 当前值）\n
 * - 点击数字项 → 输入框编辑，回车应用；点击布尔项 → 直接切换\n
 * - 保存按钮 / 关闭界面 → 写盘（spec.save）+ 热加载（AE2AddonConfig.apply）\n
 * 无需第三方配置库。
 */
public class AE2AddonConfigScreen extends Screen {

    private static final int ROW_H = 20;
    private static final int TOP = 34;

    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int scrollOffset;
    private int editingIndex = -1;
    private EditBox editBox;
    private Component toast = null;
    private long toastUntil = 0;

    /** 配置项：显示名 + SpecValue + 允许范围（数字项 clamp 用；布尔忽略）。 */
    private record Entry(String label, ForgeConfigSpec.ConfigValue value, long min, long max) {
    }

    public AE2AddonConfigScreen(Screen parent) {
        super(Component.literal("AE2Addon 配置"));
        this.parent = parent;
        // 全部可配项（与 AE2AddonConfig 静态字段一一对应；范围与 defineInRange 一致）
        entries.add(new Entry("maxConcurrent — 最大并行批次（0=无限制）", AE2AddonConfig.MAX_CONCURRENT, 0, Integer.MAX_VALUE));
        entries.add(new Entry("idleLaneTarget — 空闲虚拟 lane 池", AE2AddonConfig.IDLE_LANE_TARGET, 1, 4096));
        entries.add(new Entry("maxBatchCount — 单订单最大批数", AE2AddonConfig.MAX_BATCH_COUNT, 2, 10_000_000));
        entries.add(new Entry("batchMaxMultiplier — 批量翻倍上限", AE2AddonConfig.BATCH_MAX_MULTIPLIER, 1, Long.MAX_VALUE));
        entries.add(new Entry("sharedExpCap — 经验共享继承上限（0=关）", AE2AddonConfig.SHARED_EXP_CAP, 0, Long.MAX_VALUE));
        entries.add(new Entry("cheapOrderAmount — 小额免估算阈值", AE2AddonConfig.CHEAP_ORDER_AMOUNT, 1, Long.MAX_VALUE));
        entries.add(new Entry("cellDisplayBytes — 无限元件显示字节", AE2AddonConfig.CELL_DISPLAY_BYTES, 1, Long.MAX_VALUE));
        entries.add(new Entry("infiniteItemAmount — 无限物品真实数量", AE2AddonConfig.INFINITE_ITEM_AMOUNT, 1, Long.MAX_VALUE));
        entries.add(new Entry("cpuDisplayBytes — CPU 显示字节", AE2AddonConfig.CPU_DISPLAY_BYTES, 1, Long.MAX_VALUE));
        entries.add(new Entry("cpuDisplayThreads — CPU 显示线程（0=拉满）", AE2AddonConfig.CPU_DISPLAY_THREADS, 0, 100_000_000));
        entries.add(new Entry("cpuStorageText — 存储显示文本覆盖（留空=数值/∞）", AE2AddonConfig.CPU_STORAGE_TEXT, 0, 0));
        entries.add(new Entry("cpuThreadsText — 并行显示文本覆盖（留空=数值/∞）", AE2AddonConfig.CPU_THREADS_TEXT, 0, 0));
        entries.add(new Entry("feederFeedBudget — 接口喂出尝试/tick（发送速度主旋钮）", AE2AddonConfig.FEEDER_FEED_BUDGET, 1, 1_000_000));
        entries.add(new Entry("feederFeedStack — 接口单次喂出堆叠（默认64，大堆叠机器可调大）", AE2AddonConfig.FEEDER_FEED_STACK, 1, Integer.MAX_VALUE));
        entries.add(new Entry("feederRestockInterval — 接口补货间隔 tick（1=最快）", AE2AddonConfig.FEEDER_RESTOCK_INTERVAL, 1, 200));
        entries.add(new Entry("feederExtractInterval — 主动抽取间隔 tick（1=每tick最快）", AE2AddonConfig.FEEDER_EXTRACT_INTERVAL, 1, 10000));
        entries.add(new Entry("feederExtractStack — 主动抽取每次物品数（默认64，调大提速）", AE2AddonConfig.FEEDER_EXTRACT_STACK, 1, Integer.MAX_VALUE));
        entries.add(new Entry("feederExtractFluid — 主动抽取每次流体 mB（默认1000）", AE2AddonConfig.FEEDER_EXTRACT_FLUID, 1, Integer.MAX_VALUE));
        entries.add(new Entry("feederExtractGas — 主动抽取每次气体量（默认1000）", AE2AddonConfig.FEEDER_EXTRACT_GAS, 1, Integer.MAX_VALUE));
        entries.add(new Entry("feederExtractLoopLimit — 主动抽取循环累计上限（0=关）", AE2AddonConfig.FEEDER_EXTRACT_LOOP_CAP, 0, 2_000_000_000));
        entries.add(new Entry("feederStockTarget — 接口补货目标/种（0=关）", AE2AddonConfig.FEEDER_STOCK_TARGET, 0, Long.MAX_VALUE));
        entries.add(new Entry("debugLogs — 调试日志", AE2AddonConfig.DEBUG_LOGS, 0, 0));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("保存并应用"), b -> saveAndClose())
                .bounds(width / 2 - 120, height - 30, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> {
            minecraft.setScreen(parent);
        }).bounds(width / 2 + 10, height - 30, 110, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        g.drawCenteredString(font, Component.literal("AE2Addon 配置（改完保存，热加载生效）"),
                width / 2, 8, 0xFFFFFF);
        g.drawString(font, Component.literal("点击数字项编辑，点击布尔项切换；滚轮滚动"),
                10, 20, 0x888888);

        int visible = (height - TOP - 40) / ROW_H;
        for (int i = 0; i < visible; i++) {
            int idx = scrollOffset + i;
            if (idx >= entries.size()) {
                break;
            }
            Entry entry = entries.get(idx);
            int y = TOP + i * ROW_H;
            boolean isEditing = idx == editingIndex;
            // 行背景（编辑中高亮）
            g.fill(6, y, width - 6, y + ROW_H - 2, isEditing ? 0x553366FF : 0x22000000);
            if (isEditing) {
                // 编辑中：行内只显示输入框（2026-08-27 21:53 修复：原实现文字与输入框重叠）
                continue;
            }
            g.drawString(font, Component.literal(entry.label()), 12, y + 5, 0xFFFFFF);
            String val = String.valueOf(entry.value().get());
            // 布尔项显示开/关
            if (entry.value().get() instanceof Boolean b) {
                val = b ? "ON" : "OFF";
            }
            g.drawString(font, Component.literal(val), width - 130, y + 5,
                    isEditing ? 0x66FF66 : 0xAAAAAA);
        }

        if (editBox != null) {
            editBox.render(g, mx, my, partial);
        }
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            g.drawCenteredString(font, toast, width / 2, height - 52, 0x66FF66);
        }
        super.render(g, mx, my, partial);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (editBox != null && editingIndex >= 0) {
            // 点击输入框外 → 应用编辑
            if (!editBox.isMouseOver(mx, my)) {
                applyEdit();
            }
            return super.mouseClicked(mx, my, btn);
        }
        int visible = (height - TOP - 40) / ROW_H;
        if (mx > 6 && mx < width - 6 && my > TOP && my < TOP + visible * ROW_H) {
            int idx = scrollOffset + (int) ((my - TOP) / ROW_H);
            if (idx >= 0 && idx < entries.size()) {
                startEdit(idx);
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int max = Math.max(0, entries.size() - (height - TOP - 40) / ROW_H);
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── 编辑逻辑 ──

    private void startEdit(int idx) {
        Entry entry = entries.get(idx);
        Object current = entry.value().get();
        if (current instanceof Boolean b) {
            // 布尔：直接切换
            entry.value().set(!b);
            showToast("已切换 " + entry.label().split(" — ")[0] + " = " + (!b ? "ON" : "OFF"));
            return;
        }
        // 数字：创建输入框
        editingIndex = idx;
        int y = TOP + (idx - scrollOffset) * ROW_H;
        if (y < TOP || y > height - 60) {
            return; // 行不可见
        }
        editBox = new EditBox(font, 12, y + 2, width - 150, 16, Component.literal("值"));
        editBox.setValue(String.valueOf(current));
        editBox.setMaxLength(64);
        editBox.setFocused(true);
        addWidget(editBox);
    }

    private void applyEdit() {
        if (editBox == null || editingIndex < 0) {
            return;
        }
        Entry entry = entries.get(editingIndex);
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
                String name = entry.label().split(" — ")[0];
                showToast("已修改 " + name + " = " + (text.isEmpty() ? "（空）" : text));
                cancelEdit();
                return;
            }
            long raw = parseLong(text);
            // 超限自动回退（2026-08-27 22:00 sensei 要求）
            long clamped = Math.max(entry.min(), Math.min(entry.max(), raw));
            if (current instanceof Integer) {
                parsed = (int) clamped;
            } else {
                parsed = clamped;
            }
            entry.value().set(parsed);
            String name = entry.label().split(" — ")[0];
            if (clamped != raw) {
                showToast(name + " 超限，已回退到 " + (entry.max() < raw ? "上限 " + entry.max() : "下限 " + entry.min()));
            } else {
                showToast("已修改 " + name + " = " + clamped);
            }
        } catch (NumberFormatException e) {
            showToast("无效输入：" + e.getMessage());
        }
        cancelEdit();
    }

    private void cancelEdit() {
        editingIndex = -1;
        if (editBox != null) {
            removeWidget(editBox);
            editBox = null;
        }
    }

    /**
     * 数字解析（2026-08-27 21:56 sensei 要求支持表达式 + 22:00 单位后缀）：
     * 先试精确整数（Long.parseLong，Long.MAX 不失真）；失败则走表达式求值。
     * 支持 + - * / ^（幂）括号、科学计数 1e12、常量 MAX/INF（= Long.MAX_VALUE）、
     * 单位后缀 K/M/G/T/P/E（1K=1e3 … 1E=1e18，大小写通吃）。
     */
    private long parseLong(String text) throws NumberFormatException {
        String t = text.trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException ignored) {
            // 非纯数字 → 表达式求值（含单位后缀）
        }
        return evalExpr(t);
    }

    /** 表达式求值（递归下降，double 运算后截断 long）。 */
    private long evalExpr(String text) throws NumberFormatException {
        String s = text.trim().toUpperCase()
                .replace("LONG.MAX", "MAX")
                .replace("LONGMAX", "MAX")
                .replace("INFINITE", "MAX")
                .replace("INF", "MAX")
                .replace("MAX", "\u0000") // 保护常量（防下方 X→* 误伤 MAX 的 X）
                .replace("×", "*")
                .replace("X", "*")
                .replace("÷", "/")
                .replace("\u0000", "MAX");
        // 单位后缀：末尾 K/M/G/T/P/E（前面是数字/点/括号闭合）→ 整体乘单位
        // 注意与科学计数区分：1e12 末尾是数字不触发；1E 末尾是单位 E 触发
        // （1e18 科学计数与 1E 单位值相同，无冲突）
        double unit = 1.0;
        if (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            switch (last) {
                case 'K' -> { unit = 1e3; s = s.substring(0, s.length() - 1); }
                case 'M' -> { unit = 1e6; s = s.substring(0, s.length() - 1); }
                case 'G' -> { unit = 1e9; s = s.substring(0, s.length() - 1); }
                case 'T' -> { unit = 1e12; s = s.substring(0, s.length() - 1); }
                case 'P' -> { unit = 1e15; s = s.substring(0, s.length() - 1); }
                case 'E' -> { unit = 1e18; s = s.substring(0, s.length() - 1); }
                default -> { }
            }
        }
        ExprParser p = new ExprParser(s);
        double v = p.parseExpression() * unit;
        if (!p.isEnd()) {
            throw new NumberFormatException("多余字符: " + s.substring(p.pos));
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new NumberFormatException("结果无效");
        }
        return (long) v;
    }

    /** 迷你表达式解析器（+ - * / ^ 括号，右结合幂）。 */
    private static final class ExprParser {
        private final String s;
        private int pos;

        ExprParser(String s) {
            this.s = s;
        }

        boolean isEnd() {
            return pos >= s.length();
        }

        private char peek() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipWs() {
            peek();
        }

        double parseExpression() {
            double v = parseTerm();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    char op = s.charAt(pos++);
                    double rhs = parseTerm();
                    v = op == '+' ? v + rhs : v - rhs;
                } else {
                    return v;
                }
            }
        }

        private double parseTerm() {
            double v = parsePower();
            while (true) {
                skipWs();
                if (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                    char op = s.charAt(pos++);
                    double rhs = parsePower();
                    v = op == '*' ? v * rhs : v / rhs;
                } else {
                    return v;
                }
            }
        }

        /** 幂：右结合（2^3^2 = 2^(3^2)）。 */
        private double parsePower() {
            double base = parseAtom();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                double exp = parsePower(); // 右结合
                return Math.pow(base, exp);
            }
            return base;
        }

        private double parseAtom() {
            skipWs();
            if (pos >= s.length()) {
                throw new NumberFormatException("表达式不完整");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = parseExpression();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new NumberFormatException("缺少右括号");
                }
                pos++;
                return v;
            }
            if (c == '-') { // 一元负号
                pos++;
                return -parseAtom();
            }
            if (c == '+') {
                pos++;
                return parseAtom();
            }
            if (s.startsWith("MAX", pos)) {
                pos += 3;
                return Long.MAX_VALUE;
            }
            // 数字（含科学计数 1e12 / 1.5e9；+/- 仅在 e/E 后合法）
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos))
                    || s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E'
                    || ((s.charAt(pos) == '+' || s.charAt(pos) == '-') && pos > start
                        && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')))) {
                pos++;
            }
            if (start == pos) {
                throw new NumberFormatException("无法识别的字符: " + c);
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new NumberFormatException("无效数字: " + s.substring(start, pos));
            }
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
            showToast("已保存并热加载");
        } catch (RuntimeException e) {
            showToast("保存失败: " + e.getMessage());
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
