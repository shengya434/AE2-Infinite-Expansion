package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.config.AE2AddonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
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

    private static final int W = 211; // 2026-08-30：右扩 35px 容纳网络工具 3×3 卡槽栏
    private static final int H = 237;
    private static final int MAX_STATUS_LINES = 3;
    private static final int STATUS_Y = 137;
    /** 状态行最大宽度（避开右侧网络工具卡槽栏）。 */
    private static final int STATUS_MAX_W = 145;

    private static final String[] KEYS = {"stockTarget", "restockInterval", "feedBudget"};
    private static final String[] LABELS = {
            "gui.ae2addon.feeder.label.stock",
            "gui.ae2addon.feeder.label.interval",
            "gui.ae2addon.feeder.label.budget"};
    /** 参数行 Y（顶部，位于样板槽上方）。 */
    private static final int[] ROW_YS = {17, 31, 45};

    private final EditBox[] boxes = new EditBox[3];

    /** 中键弹框：标记缓存目标输入框（隐藏时不可见）。 */
    private EditBox targetBox;

    /** 中键当前编辑的标记槽容器 index（-1 = 未编辑）。 */
    private int targetBoxMarker = -1;


    /** 蓄水池状态行（服务端 FeederStatusPacket → 主线程）。 */
    private boolean paramBoxesFilled = false;

    public static void handleStatus(List<String> lines) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof InfiniteInterfaceScreen screen) {
            screen.menu.statusLines = lines;
            // 首次收到时用每接口参数填充输入框（§7参数: 目标=.. 间隔=.. 预算=..）
            if (!screen.paramBoxesFilled) {
                for (String line : lines) {
                    String text = deserialize(line).getString();
                    if (text.contains("目标=") || text.contains("Target=")) {
                        screen.fillParamBoxes(text);
                        screen.paramBoxesFilled = true;
                        break;
                    }
                }
            }
        }
    }

    /** 按像素宽度拆行（逐码点，中文/emoji 安全）。 */
    private static List<String> wrapByWidth(Font font, String text, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            if (cur.length() > 0 && font.width(cur.toString() + ch) > maxW) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(ch);
            i += Character.charCount(cp);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 状态行 JSON → Component（失败回退纯文本）。 */
    private static Component deserialize(String json) {
        try {
            return Component.Serializer.fromJson(json);
        } catch (Exception e) {
            return Component.literal(json);
        }
    }

    /** 解析「参数: 目标=1000000 间隔=4 预算=4096」填充三个输入框。 */
    private void fillParamBoxes(String line) {
        String plain = line.replaceAll("§.", "");
        String[] parts = plain.split(" ");
        for (String part : parts) {
            if (part.startsWith("目标=")) {
                if (boxes[0] != null) boxes[0].setValue(part.substring(3));
            } else if (part.startsWith("Target=")) {
                if (boxes[0] != null) boxes[0].setValue(part.substring(6));
            } else if (part.startsWith("间隔=")) {
                if (boxes[1] != null) boxes[1].setValue(part.substring(3));
            } else if (part.startsWith("Interval=")) {
                if (boxes[1] != null) boxes[1].setValue(part.substring(9));
            } else if (part.startsWith("预算=")) {
                if (boxes[2] != null) boxes[2].setValue(part.substring(3));
            } else if (part.startsWith("Budget=")) {
                if (boxes[2] != null) boxes[2].setValue(part.substring(7));
            }
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
        // 翻页按钮（右上角；容量卡≥1 时可用）
        addRenderableWidget(Button.builder(Component.literal("◀"),
                b -> getMenu().flipPage(-1)
        ).bounds(leftPos + 122, topPos + 4, 16, 12).build());
        addRenderableWidget(Button.builder(Component.literal("▶"),
                b -> getMenu().flipPage(1)
        ).bounds(leftPos + 158, topPos + 4, 16, 12).build());
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

            addRenderableWidget(Button.builder(Component.translatable("gui.ae2addon.feeder.save"),
                    b -> saveSetting(idx)
            ).bounds(leftPos + 122, topPos + ROW_YS[i] - 1, 32, 16).build());
        }
        // 中键弹框：标记缓存目标输入（默认隐藏；中键点击标记槽时显示）
        targetBox = new EditBox(font, leftPos + 26, topPos + 128, 120, 14,
                Component.literal("target"));
        targetBox.setMaxLength(40);
        targetBox.setVisible(false);
        targetBox.setCanLoseFocus(false);
        addRenderableWidget(targetBox);
    }

    /** 中键点击标记槽：显示目标输入框并预填当前值。 */
    private void openTargetBox(int markerIndex) {
        if (targetBox == null || markerIndex < 0) {
            return;
        }
        targetBoxMarker = markerIndex;
        var stack = getMenu().getFeeder().getMarkerInventory().getItem(markerIndex);
        long cur = getMenu().getFeeder().targetFor(
                com.ae2addon.block.InfiniteInterfaceBE.keyOfStack(stack));
        targetBox.setValue(cur == Long.MAX_VALUE ? "MAX" : String.valueOf(cur));
        targetBox.setVisible(true);
        targetBox.setFocused(true);
    }

    /** 确认输入 → 发送到服务端 → 隐藏输入框。 */
    private void saveTargetBox() {
        if (targetBox == null || targetBoxMarker < 0) {
            return;
        }
        try {
            long value = parseSetting(targetBox.getValue().trim());
            var be = getMenu().getFeeder();
            AE2Addon.NETWORK.sendToServer(
                    new com.ae2addon.network.FeederTargetPacket(
                            be.getBlockPos(), targetBoxMarker, value));
            AE2Addon.LOGGER.info("[ae2addon][gui] 标记 {} 缓存目标 = {}（文本 {}）",
                    targetBoxMarker, value, targetBox.getValue().trim());
        } catch (NumberFormatException e) {
            AE2Addon.LOGGER.info("[ae2addon][gui] 标记目标解析失败: {}", targetBox.getValue());
        }
        targetBoxMarker = -1;
        targetBox.setVisible(false);
        targetBox.setFocused(false);
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
                    new com.ae2addon.network.FeederSettingPacket(
                            getMenu().getFeeder().getBlockPos(), KEYS[idx], value));
        } catch (NumberFormatException e) {
            AE2Addon.LOGGER.info("[ae2addon][gui] 解析失败: {} 文本={}", KEYS[idx], text);
        }
    }

    /** 客户端解析：MAX/INF → Long.MAX；支持表达式（+ - * / ^ 括号）、K/M/G/T/P 后缀、
     *  万/亿、科学计数、全角归一（2026-08-30 sensei：10^10 / 10*2 要能用）。 */
    private long parseSetting(String text) throws NumberFormatException {
        String t = normalizeExpr(text);
        if (t.equals("MAX") || t.equals("INF") || t.equals("INFINITE")) {
            return Long.MAX_VALUE;
        }
        ExprParser p = new ExprParser(t);
        double v = p.parseExpr();
        if (!p.atEnd() || Double.isNaN(v) || !Double.isFinite(v) || v < 0) {
            throw new NumberFormatException("无效: " + text);
        }
        return (long) Math.min(Long.MAX_VALUE, v);
    }

    /** 全角→半角、去空白、万/亿→×1e4/×1e8。 */
    private static String normalizeExpr(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            char n = c;
            if (c >= '０' && c <= '９') {
                n = (char) ('0' + (c - '０'));
            } else if (c >= 'Ａ' && c <= 'Ｚ') {
                n = (char) ('A' + (c - 'Ａ'));
            } else if (c >= 'ａ' && c <= 'ｚ') {
                n = (char) ('a' + (c - 'ａ'));
            } else {
                switch (c) {
                    case '．' -> n = '.';
                    case '＾' -> n = '^';
                    case '×', '＊' -> n = '*';
                    case '－' -> n = '-';
                    case '＋' -> n = '+';
                    case '／' -> n = '/';
                    case '（' -> n = '(';
                    case '）' -> n = ')';
                    case '万' -> {
                        sb.append("*1e4");
                        continue;
                    }
                    case '亿' -> {
                        sb.append("*1e8");
                        continue;
                    }
                    default -> { }
                }
            }
            sb.append(Character.toUpperCase(n));
        }
        return sb.toString();
    }

    /** 极简表达式解析器：+ - * / ^（^ 右结合；优先级 ^ > 一元负号 > * / > + -）。 */
    private static class ExprParser {
        private final String s;
        private int i;

        ExprParser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        private char peek() {
            return atEnd() ? '\0' : s.charAt(i);
        }

        private boolean eat(char c) {
            if (peek() == c) {
                i++;
                return true;
            }
            return false;
        }

        double parseExpr() {
            double v = parseTerm();
            while (true) {
                if (eat('+')) {
                    v += parseTerm();
                } else if (eat('-')) {
                    v -= parseTerm();
                } else {
                    return v;
                }
            }
        }

        private double parseTerm() {
            double v = parsePower();
            while (true) {
                if (eat('*')) {
                    v *= parsePower();
                } else if (eat('/')) {
                    v /= parsePower();
                } else {
                    return v;
                }
            }
        }

        private double parsePower() {
            double v = parseUnary();
            if (eat('^')) {
                v = Math.pow(v, parsePower()); // 右结合：2^3^2 = 2^(3^2)
            }
            return v;
        }

        private double parseUnary() {
            if (eat('-')) {
                return -parseUnary();
            }
            if (eat('+')) {
                return parseUnary();
            }
            return parseAtom();
        }

        private double parseAtom() {
            if (eat('(')) {
                double v = parseExpr();
                if (!eat(')')) {
                    throw new NumberFormatException("缺右括号");
                }
                return v;
            }
            int start = i;
            boolean dot = false;
            while (!atEnd()) {
                char c = peek();
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    i++;
                } else {
                    break;
                }
            }
            if (start == i) {
                throw new NumberFormatException("缺数字");
            }
            // 科学计数指数（1e6 / 2E10）；非指数（如 1E 结尾）回退
            if (peek() == 'e' || peek() == 'E') {
                int save = i;
                i++;
                if (peek() == '+' || peek() == '-') {
                    i++;
                }
                if (peek() >= '0' && peek() <= '9') {
                    while (peek() >= '0' && peek() <= '9') {
                        i++;
                    }
                } else {
                    i = save;
                }
            }
            int numEnd = i; // 数字部分到此为止（后缀不并入数字串）
            double unit = 1.0;
            switch (peek()) {
                case 'K' -> {
                    unit = 1e3;
                    i++;
                }
                case 'M' -> {
                    unit = 1e6;
                    i++;
                }
                case 'G' -> {
                    unit = 1e9;
                    i++;
                }
                case 'T' -> {
                    unit = 1e12;
                    i++;
                }
                case 'P' -> {
                    unit = 1e15;
                    i++;
                }
                default -> { }
            }
            return Double.parseDouble(s.substring(start, numEnd)) * unit;
        }
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

        // 网络工具 3×3 卡槽栏底框（右下角；有工具时显示）
        if (menu.hasToolbox()) {
            int px = x + InfiniteInterfaceMenu.TOOLBOX_X - 4;
            int py = y + InfiniteInterfaceMenu.TOOLBOX_Y - 4;
            g.fill(px, py, px + 58, py + 58, 0xF0101010);
            g.fill(px, py, px + 58, py + 1, 0xFF555555);
            g.fill(px, py + 57, px + 58, py + 58, 0xFF555555);
            g.fill(px, py, px + 1, py + 58, 0xFF555555);
            g.fill(px + 57, py, px + 58, py + 58, 0xFF555555);
        }

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

    /**
     * 标记槽（index 9-17）图标叠加绘制：右键标记的 WrappedGenericStack
     * （流体/气体）在槽位上方重绘——流体画真·流体贴图（着色），物品直接渲染。
     * 注：AbstractContainerScreen.renderSlot 是 private 不能覆写，故在
     * renderLabels（槽渲染之后、translate 坐标系）里叠加绘制。
     */
    private void renderMarkerIcons(GuiGraphics g) {
        int markerStart = menu.markerSlotStart();
        int markerEnd = menu.markerSlotEnd();
        for (Slot slot : menu.getSlotList()) {
            if (slot.index < markerStart || slot.index >= markerEnd || !slot.isActive()) {
                continue; // 只画当前页（分页同步 bug 修复：非当前页图标不得叠加）
            }
            var stack = slot.getItem();
            if (stack.getItem() instanceof appeng.items.misc.WrappedGenericStack wgs) {
                appeng.api.stacks.AEKey key = wgs.unwrapWhat(stack);
                if (key instanceof appeng.api.stacks.AEFluidKey fluidKey) {
                    renderFluidIcon(g, fluidKey, slot.x, slot.y);
                } else if (key instanceof appeng.api.stacks.AEItemKey itemKey) {
                    g.renderItem(itemKey.toStack(), slot.x, slot.y);
                }
                // 气体等其他 key：暂用原版（WrappedGenericStack 默认）渲染
            }
        }
    }

    /** 槽内绘制流体贴图（真·流体图标 + 颜色着色）。 */
    private void renderFluidIcon(GuiGraphics g, appeng.api.stacks.AEFluidKey key, int x, int y) {
        try {
            var fluid = key.getFluid();
            var ext = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
            var stack = key.toStack(1000);
            var still = ext.getStillTexture(stack);
            var sprite = net.minecraft.client.Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                    .apply(still);
            g.fill(x, y, x + 16, y + 16, 0xFF000000); // 槽底
            g.blit(x, y, 0, 16, 16, sprite);          // 流体贴图
            int color = ext.getTintColor(stack);       // 着色覆盖（半透明）
            g.fill(x, y, x + 16, y + 16, (color & 0xFFFFFF) | 0x90000000);
        } catch (RuntimeException e) {
            g.fill(x, y, x + 16, y + 16, 0xFF555555); // 兜底灰块
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFAA, false);

        // 参数行标签（顶部，输入框左侧）
        for (int i = 0; i < 3; i++) {
            g.drawString(font, Component.translatable(LABELS[i]), 8, ROW_YS[i] + 3, 0xFFFFFF, false);
        }

        // 升级槽整行（9格，x=8..152；无标签，卡片自明）
        // （原“升级”文字标签已移除，避免与第一格重叠）

        // 页标签（右上角 ◀ N/M ▶ 之间）
        String pageLabel = "§7" + (menu.currentPage + 1) + "/" + (menu.getFeeder().maxPage() + 1);
        g.drawString(font, Component.literal(pageLabel), 142, 6, 0xFFFFFF, false);

        // 样板槽/标记槽标签（格子正上方）
        g.drawString(font,
                Component.translatable("gui.ae2addon.infinite_interface.patterns"),
                26, 79, 0xAAAAAA, false);
        g.drawString(font,
                Component.translatable("gui.ae2addon.infinite_interface.markers"),
                96, 79, 0xAAAAAA, false);

        // 状态行（样板槽下方；最多 2 行，超长截断）
        List<String> lines = menu.statusLines;
        int lineY = STATUS_Y;
        int total = Math.min(lines.size(), MAX_STATUS_LINES);
        for (int i = 0; i < total; i++) {
            Component comp = deserialize(lines.get(i));
            String text = comp.getString();
            String stripped = text.replaceAll("§.", "");
            boolean isLast = (i == total - 1);
            if (isLast && font.width(stripped) > STATUS_MAX_W) {
                // 最后一行（开关行，最重要）：超宽拆行，保证完整可见
                List<String> wraps = wrapByWidth(font, stripped, STATUS_MAX_W);
                for (int j = 0; j < wraps.size(); j++) {
                    g.drawString(font, Component.literal(wraps.get(j)), 8, lineY, 0xFFFFFF, false);
                    lineY += (j < wraps.size() - 1) ? 5 : 7;
                }
            } else {
                // 前几行：超宽截断省略号，不换行（换行配额留给最后一行）
                if (font.width(stripped) > STATUS_MAX_W) {
                    comp = Component.literal(font.plainSubstrByWidth(stripped, STATUS_MAX_W - 4) + "…");
                }
                g.drawString(font, comp, 8, lineY, 0xFFFFFF, false);
                lineY += 7;
            }
        }
        // 标记槽图标叠加（WrappedGenericStack 流体/物品）
        renderMarkerIcons(g);
    }

    /** 悬浮槽物品名诊断行渲染计数（tooltip 排查用）。 */
    private static int tooltipProbeCount = 0;

    /** 【临时探针】确认 renderTooltip 是否被调用（排查 tooltip 不显示）。 */
    @Override
    protected void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (hoveredSlot != null && hoveredSlot.hasItem() && (++tooltipProbeCount & 63) == 1) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][tooltip] renderTooltip 被调用: {} (hoveredSlot 有物品)",
                    hoveredSlot.getItem().getHoverName().getString());
        }
        super.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 容量卡增减后翻页范围变化：钳制当前页回合法范围
        int max = menu.getFeeder().maxPage();
        if (menu.currentPage > max) {
            menu.currentPage = max;
        }
        // ⚠️ 不能自己先调 renderBg（super 内部会调，画两遍）；renderTooltip 同理
        super.render(g, mouseX, mouseY, partialTick);
        // 2026-08-28 tooltip 修复：反编译实锤 1.20.1 render() 链不调用
        // renderTooltip（0 调用点），显式补调才能显示悬浮提示（重复画无害）
        renderTooltip(g, mouseX, mouseY);
    }

    /** 中键命中检测：鼠标下当前页的槽位（AbstractContainerScreen 的 isHovering 是 private）。 */
    private Slot findMarkerSlot(double mouseX, double mouseY) {
        for (Slot slot : menu.getSlotList()) {
            if (slot.isActive()
                    && mouseX >= leftPos + slot.x - 1 && mouseX < leftPos + slot.x + 17
                    && mouseY >= topPos + slot.y - 1 && mouseY < topPos + slot.y + 17) {
                return slot;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 中键点击标记槽 = 弹输入框（拦截创造模式默认的拾取功能）
        if (button == 2 && targetBox != null) {
            if (targetBox.isVisible()) {
                // 输入框开着时中键 = 确认
                saveTargetBox();
                return true;
            }
            Slot hovered = findMarkerSlot(mouseX, mouseY);
            if (hovered != null && hovered.index >= menu.markerSlotStart()
                    && hovered.index < menu.markerSlotEnd() && hovered.isActive()) {
                openTargetBox(hovered.index - menu.markerSlotStart());
                return true;
            }
        }
        // 状态行第 3 行（开关行）点击切换：分区按实际渲染文本自适应
        // （2026-08-30：硬编码分区在英文方向名/字体宽度下会歪，改为按 [值] 组右边界测量）
        int relX = (int) mouseX - leftPos;
        int relY = (int) mouseY - topPos;
        int total = Math.min(menu.statusLines.size(), MAX_STATUS_LINES);
        int switchY = STATUS_Y + 7 * Math.max(0, total - 1);
        if (button == 0 && relY >= switchY - 2 && relY <= switchY + 10 && relX >= 8 && relX < 170) {
            String which = switchZoneAt(relX);
            AE2Addon.NETWORK.sendToServer(
                    new com.ae2addon.network.FeederTogglePacket(
                            getMenu().getFeeder().getBlockPos(), which));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 开关行点击区：按 4 个 [值] 组的实际渲染右边界划分（入/向/出/标），
     *  自适应语言与方向名宽度；解析失败回退硬编码分区。 */
    private String switchZoneAt(int relX) {
        List<String> lines = menu.statusLines;
        if (lines.size() < 3) {
            return relX < 24 ? "extract" : (relX < 44 ? "dir" : (relX < 70 ? "feed" : "markerFeed"));
        }
        String text = deserialize(lines.get(2)).getString().replaceAll("§.", "");
        int[] ends = {24, 44, 70, 170}; // 解析失败时兜底
        int search = 0;
        for (int g = 0; g < 4; g++) {
            int open = text.indexOf('[', search);
            int close = open < 0 ? -1 : text.indexOf(']', open + 1);
            if (open < 0 || close < 0) {
                break;
            }
            ends[g] = 8 + font.width(text.substring(0, close + 1));
            search = close + 1;
        }
        if (relX < ends[0]) {
            return "extract";
        }
        if (relX < ends[1]) {
            return "dir";
        }
        if (relX < ends[2]) {
            return "feed";
        }
        return "markerFeed";
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
        // 标记目标输入框回车 = 确认
        if (targetBox != null && targetBox.isVisible() && targetBox.isFocused()
                && (keyCode == 257 || keyCode == 335)) {
            saveTargetBox();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
