package com.ae2addon.gui;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import com.ae2addon.AE2Addon;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.network.Mode2ConfigPacket;
import com.ae2addon.network.SetCellModePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.util.*;

/**
 * 模式2配置界面
 * <p>
 * 带搜索栏和分类过滤的存储面板
 * ┌────────────────────────────────────┐
 * │  自定义无限模式配置                   │
 * │  [阈值输入] [保存] [⟳][⇄模式]       │
 * │  ── 存储类型一览 ── (5种)           │
 * │  [搜索物品/流体...]                 │
 * │  [全部] [物品] [流体]               │
 * │  ┌──────────────────────────────┐  │
 * │  │ [icon] 钻石           300.0M│  │
 * │  │ [icon] 铁锭           300.0M│  │
 * │  │ [icon] 石              64B  │  │
 * │  └──────────────────────────────┘  │
 * │  Shift+点击 ↔ 切换无限              │
 * └────────────────────────────────────┘
 */
public class Mode2ConfigScreen extends AbstractContainerScreen<Mode2ConfigMenu> {

    private static final int W = 256, H = 250;
    private static final int PANEL_X = 10, PANEL_Y = 72;
    private static final int PANEL_W = 236, PANEL_H = 78;
    private static final int ROW_H = 14;

    private EditBox thresholdInput;
    private EditBox searchBox;
    private List<UnlimitedCellInventory.PanelItem> displayItems = new ArrayList<>();
    /** 搜索+分类过滤后的条目 */
    private List<UnlimitedCellInventory.PanelItem> filteredItems = new ArrayList<>();
    /** 当前数据中出现的所有 AEKeyType ID（有序） */
    private List<ResourceLocation> availableTypes = new ArrayList<>();
    /** 当前选中的 type ID，null=全部 */
    private ResourceLocation currentTypeFilter = null;
    private int scrollOffset = 0;
    private int maxVisibleRows;
    private String lastSearch = "";
    private int currentWorkMode = 1;
    /** 0=工作模式选择, 1=具体配置 */
    private int uiState = 0;
    private Button thresholdSaveBtn;

    /** 拼音缓存：原始名称 → [全拼, 首字母] */
    private final Map<String, String[]> pinyinCache = new HashMap<>();
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = new HanyuPinyinOutputFormat();
    static {
        PINYIN_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    // ── 分包累积（static，主线程安全） ──
    private static final Map<Integer, List<UnlimitedCellInventory.PanelItem>> PENDING_CHUNKS = new HashMap<>();
    private static int PENDING_TOTAL_CHUNKS = 0;

    public Mode2ConfigScreen(Mode2ConfigMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = W;
        imageHeight = H;
        titleLabelY = -1000;
    }

    @Override
    protected void init() {
        super.init();
        currentWorkMode = menu.getWorkMode();
        menu.slotsHidden = (uiState == 0);
        if (uiState == 0) {
            buildWorkModeSelectUI();
        } else {
            buildFullConfigUI();
        }
    }

    /** 刷新按钮：清空 pending 再请求，防止旧包污染 */
    private void requestRefresh() {
        synchronized (PENDING_CHUNKS) {
            PENDING_CHUNKS.clear();
            PENDING_TOTAL_CHUNKS = 0;
        }
        menu.requestPanelData();
    }

    private void saveThreshold() {
        try {
            long v = Long.parseLong(thresholdInput.getValue());
            v = Math.max(1, Math.min(v, Long.MAX_VALUE));
            AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(0, v, ""));
            thresholdInput.setValue(String.valueOf(v));
        } catch (NumberFormatException ignored) {
            thresholdInput.setValue(String.valueOf(menu.getThreshold()));
        }
    }

    /** 工作模式选择界面（3个按钮 + 返回） */
    private void buildWorkModeSelectUI() {
        int cx = leftPos + W / 2;
        String[] names = {"阈值模式", "存入无限", "臻藏模式"};
        for (int i = 0; i < 3; i++) {
            int wm = i + 1;
            boolean active = wm == currentWorkMode;
            int y = topPos + 24 + i * 28;
            String prefix = active ? "§a▶ " : "   ";
            String color = wm == 1 ? "§e" : (wm == 2 ? "§d" : "§5");
            addRenderableWidget(Button.builder(
                    Component.literal(prefix + color + names[i]),
                    b -> enterWorkMode(wm)
            ).bounds(cx - 80, y, 160, 22).build());
        }
        // 返回
        addRenderableWidget(Button.builder(
                Component.literal("§7← 返回"),
                b -> {
                    AE2Addon.NETWORK.sendToServer(new SetCellModePacket(0));
                    onClose();
                }
        ).bounds(cx - 30, topPos + 102, 60, 16).build());
    }

    /** 完整配置界面 */
    private void buildFullConfigUI() {
        // ── 阈值输入（仅模式1可见） ──
        thresholdInput = new EditBox(font, leftPos + 10, topPos + 20, 100, 16, Component.literal("Threshold"));
        thresholdInput.setValue(String.valueOf(menu.getThreshold()));
        thresholdInput.setMaxLength(19);
        thresholdInput.setVisible(currentWorkMode == 1);
        addRenderableWidget(thresholdInput);

        thresholdSaveBtn = Button.builder(
                Component.literal("§a保存"),
                btn -> saveThreshold()
        ).bounds(leftPos + 114, topPos + 18, 50, 18).build();
        thresholdSaveBtn.visible = (currentWorkMode == 1);
        addRenderableWidget(thresholdSaveBtn);

        // ── 刷新 ──
        addRenderableWidget(Button.builder(
                Component.literal("§b⟳"),
                btn -> requestRefresh()
        ).bounds(leftPos + 168, topPos + 18, 22, 18).build());

        // ── 切换工作模式（阈值→存入∞→臻藏→循环） ──
        String[] wmLabels = {"", "§e⇄ 存入∞", "§e⇄ 臻藏", "§e⇄ 阈值"};
        addRenderableWidget(Button.builder(
                Component.literal(wmLabels[currentWorkMode]),
                btn -> cycleWorkMode()
        ).bounds(leftPos + 194, topPos + 18, 52, 18).build());

        // ── 搜索框 ──
        searchBox = new EditBox(font, leftPos + PANEL_X, topPos + PANEL_Y - 24, PANEL_W - 50, 14,
                Component.literal("Search"));
        searchBox.setMaxLength(40);
        searchBox.setHint(Component.literal("§7搜索"));
        addRenderableWidget(searchBox);

        maxVisibleRows = PANEL_H / ROW_H;
        requestRefresh();
    }

    private void cycleWorkMode() {
        currentWorkMode = (currentWorkMode % 3) + 1;
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(6, currentWorkMode));
        rebuildWidgets();
    }

    private void enterWorkMode(int wm) {
        currentWorkMode = wm;
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(6, wm));
        uiState = 1;
        rebuildWidgets();
    }

    // ── 过滤逻辑 ──

    /** 从当前数据扫描所有出现的 AEKeyType */
    private void scanAvailableTypes() {
        Set<ResourceLocation> types = new LinkedHashSet<>();
        for (var item : displayItems) {
            types.add(item.key.getType().getId());
        }
        availableTypes = new ArrayList<>(types);
        // 如果当前筛选类型在数据中不存在了，重置
        if (currentTypeFilter != null && !types.contains(currentTypeFilter)) {
            currentTypeFilter = null;
        }
    }

    private void applyFilters() {
        String search = searchBox.getValue().toLowerCase(Locale.ROOT).trim();
        filteredItems.clear();

        for (var item : displayItems) {
            // 分类过滤
            if (currentTypeFilter != null) {
                ResourceLocation typeId = item.key.getType().getId();
                if (!typeId.equals(currentTypeFilter)) continue;
            }

            // 搜索过滤（含拼音支持）
            if (!search.isEmpty()) {
                String name = item.key.getDisplayName().getString().toLowerCase(Locale.ROOT);
                if (!matchesSearch(name, search)) continue;
            }

            filteredItems.add(item);
        }

        int maxOffset = Math.max(0, filteredItems.size() - maxVisibleRows);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
    }

    /**
     * 中文拼音匹配：支持原名、全拼、首字母三种方式
     */
    private boolean matchesSearch(String name, String search) {
        // 直接包含（原名匹配）
        if (name.contains(search)) return true;

        // 获取拼音
        String[] pinyin = pinyinCache.computeIfAbsent(name, this::toPinyin);
        if (pinyin == null) return false;

        // 全拼匹配
        if (pinyin[0] != null && pinyin[0].contains(search)) return true;

        // 首字母匹配
        return pinyin[1] != null && pinyin[1].contains(search);
    }

    /**
     * 将中文字符串转为拼音，返回 [全拼, 首字母]
     * 非中文部分保留原字符，全部转小写
     */
    private String[] toPinyin(String chinese) {
        StringBuilder full = new StringBuilder();
        StringBuilder initials = new StringBuilder();

        for (char c : chinese.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                try {
                    String[] pinyins = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT);
                    if (pinyins != null && pinyins.length > 0) {
                        full.append(pinyins[0]);
                        initials.append(pinyins[0].charAt(0));
                        continue;
                    }
                } catch (BadHanyuPinyinOutputFormatCombination ignored) {}
            }
            // 非中文字符：直接附加（小写化）
            char lower = Character.toLowerCase(c);
            full.append(lower);
            initials.append(lower);
        }

        return new String[]{full.toString(), initials.toString()};
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // 不绘制默认的物品栏标签
    }

    // ── 分包接收 ──

    /**
     * 由数据包处理线程（enqueueWork → 主线程）调用。
     * 累积所有 chunk，收齐后合并并更新面板。
     */
    public static void handlePanelDataChunk(
            List<UnlimitedCellInventory.PanelItem> items,
            int chunkIndex, int totalChunks) {

        // chunkIndex == 0 表示新一批传输开始
        if (chunkIndex == 0) {
            PENDING_CHUNKS.clear();
            PENDING_TOTAL_CHUNKS = totalChunks;
        }

        // 跳过过期 chunk（来自上一批传输，但 batch total 已变化）
        if (totalChunks != PENDING_TOTAL_CHUNKS) {
            return;
        }

        PENDING_CHUNKS.put(chunkIndex, items);

        // 收齐所有 chunk → 合并
        if (PENDING_CHUNKS.size() >= PENDING_TOTAL_CHUNKS) {
            List<UnlimitedCellInventory.PanelItem> merged = new ArrayList<>();
            for (int i = 0; i < PENDING_TOTAL_CHUNKS; i++) {
                List<UnlimitedCellInventory.PanelItem> chunk = PENDING_CHUNKS.get(i);
                if (chunk == null) return; // 还没到齐
                merged.addAll(chunk);
            }

            // 更新屏幕
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof Mode2ConfigScreen screen) {
                screen.updatePanelData(merged);
            }

            PENDING_CHUNKS.clear();
            PENDING_TOTAL_CHUNKS = 0;
        }
    }

    public void updatePanelData(List<UnlimitedCellInventory.PanelItem> items) {
        this.displayItems = items;
        this.menu.setPanelItems(items);
        scanAvailableTypes();
        applyFilters();
        // 刷新后同步 workMode
        int newWm = menu.getWorkMode();
        if (newWm != currentWorkMode) {
            currentWorkMode = newWm;
        }
    }

    // ── 渲染 ──

    @Override
    protected void renderBg(GuiGraphics g, float d, int mx, int my) {
        renderBackground(g);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float d) {
        renderBg(g, d, mx, my);
        super.render(g, mx, my, d); // slots isActive=false 时不渲染

        if (uiState == 0) {
            g.drawString(font, "§l✦ 选择工作模式", leftPos + W / 2 - 36, topPos + 4, 0xFFFFAA, false);
            String[] curLabels = {"", "§e▶ 当前：阈值模式", "§d▶ 当前：存入无限", "§5▶ 当前：臻藏模式"};
            String[] curDescs = {"", "§7说明：物品达阈值后自动解锁无限", "§7说明：存入的任意物品会直接变为无限", "§7说明：只有白名单内的物品才能无限"};
            if (currentWorkMode >= 1 && currentWorkMode <= 3) {
                g.drawString(font, Component.literal(curLabels[currentWorkMode]), leftPos + 10, topPos + 125, 0xFFFFAA, false);
                g.drawString(font, Component.literal(curDescs[currentWorkMode]), leftPos + 10, topPos + 138, 0x888888, false);
            }
            renderTooltip(g, mx, my);
            return;
        }

        // 完整配置界面
        g.drawString(font, "§e✦ 自定义无限配置", leftPos + 10, topPos + 4, 0xFFFFAA, false);
        renderPanel(g, mx, my);
        renderFilterTabs(g, mx, my);
        g.drawString(font, "§7Shift+点击切换  |  背包物品添加白名单  |  滚轮滚动", leftPos + 10, topPos + PANEL_Y + PANEL_H + 8, 0x888888, false);
        renderTooltip(g, mx, my);
        renderPanelTooltip(g, mx, my);
    }

    /** 面板条目悬浮提示：物品显示完整 tooltip，流体/其他显示名称 */
    private void renderPanelTooltip(GuiGraphics g, int mx, int my) {
        if (!(isInPanelArea(mx, my) || isInFilterTabRows(mx, my))) return;

        // 先检查分类标签悬浮
        if (my >= topPos + PANEL_Y + 2 && my < topPos + PANEL_Y + 16) {
            int tabX = leftPos + PANEL_X + 2;
            int tabY = topPos + PANEL_Y + 2;

            // 全部标签
            String allLabel = "[全部]";
            int tabW = font.width(allLabel) + 4;
            if (mx >= tabX && mx < tabX + tabW && my >= tabY && my < tabY + 14) {
                g.renderComponentTooltip(font, List.of(Component.literal("§7显示所有类型")), mx, my);
                return;
            }
            tabX += tabW;

            // 动态类型标签
            for (ResourceLocation typeId : availableTypes) {
                String label = "[" + getTypeDisplayName(typeId) + "]";
                tabW = font.width(label) + 4;
                if (mx >= tabX && mx < tabX + tabW && my >= tabY && my < tabY + 14) {
                    Component desc = getTypeFilterTooltip(typeId);
                    g.renderComponentTooltip(font, List.of(Component.literal("§7筛选: ").append(desc)), mx, my);
                    return;
                }
                tabX += tabW;
            }
            return;
        }

        // 面板物品悬浮
        if (!isInPanelArea(mx, my)) return;
        int row = getClickedRow(mx, my);
        if (row < 0 || row >= filteredItems.size()) return;

        var entry = filteredItems.get(row);
        AEKey key = entry.key;

        if (key instanceof appeng.api.stacks.AEItemKey itemKey) {
            // 物品：获取完整 tooltip（含模组名/附魔等），再追加无限标记
            ItemStack stack = itemKey.toStack();
            var mc = Minecraft.getInstance();
            var flag = mc.options.advancedItemTooltips
                    ? TooltipFlag.Default.ADVANCED
                    : TooltipFlag.Default.NORMAL;
            List<Component> lines = new ArrayList<>(stack.getTooltipLines(mc.player, flag));
            if (entry.isInfinite) {
                // 在 tooltip 最下方追加 ∞ 标记
                lines.add(Component.literal(""));
                lines.add(Component.literal("§e∞ 无限"));
            }
            g.renderComponentTooltip(font, lines, mx, my);
        } else {
            // 流体/其他：显示名称+类型
            List<Component> lines = new ArrayList<>();
            lines.add(key.getDisplayName());
            if (entry.isInfinite) {
                lines.add(Component.literal("§e∞ 无限"));
            } else {
                lines.add(Component.literal("§7数量: §b" + formatAmount(entry.amount)));
            }
            g.renderComponentTooltip(font, lines, mx, my);
        }
    }

    /** 检查鼠标是否在分类标签行区域内 */
    private boolean isInFilterTabRows(double mx, double my) {
        return my >= topPos + PANEL_Y + 2 && my < topPos + PANEL_Y + 16
                && mx >= leftPos + PANEL_X + 2 && mx < leftPos + PANEL_X + PANEL_W - 2;
    }

    /** 获取分类过滤器的工具提示描述 */
    private static Component getTypeFilterTooltip(ResourceLocation typeId) {
        if (typeId.equals(AEKeyType.items().getId())) return Component.literal("§e物品");
        if (typeId.equals(AEKeyType.fluids().getId())) return Component.literal("§b流体");
        return Component.literal("§d" + typeId.getPath());
    }

    private String getTypeDisplayName(ResourceLocation typeId) {
        if (typeId.equals(AEKeyType.items().getId())) return "物品";
        if (typeId.equals(AEKeyType.fluids().getId())) return "流体";
        String path = typeId.getPath();
        if (path.equals("emc")) return "emc";
        if (path.equals("fe") || path.equals("rf")) return path;
        if (path.startsWith("gas")) return "气体";
        if (path.length() > 4) return path.substring(0, 4);
        return path;
    }

    private void renderFilterTabs(GuiGraphics g, int mx, int my) {
        int x = leftPos + PANEL_X + 2;
        int y = topPos + PANEL_Y + 2;
        int tabX = x;

        // 全部标签
        boolean allActive = currentTypeFilter == null;
        boolean allHover = mx >= tabX && mx < tabX + 44 && my >= y && my < y + 14;
        String allColor = allActive ? "§b" : (allHover ? "§7" : "§8");
        g.drawString(font, Component.literal(allColor + "[全部]"), tabX, y, 0xFFFFFF, false);
        tabX += font.width("[全部]") + 4;

        // 动态类型标签
        for (ResourceLocation typeId : availableTypes) {
            boolean active = typeId.equals(currentTypeFilter);
            boolean hover = mx >= tabX && mx < tabX + 44 && my >= y && my < y + 14;
            String color = active ? "§b" : (hover ? "§7" : "§8");
            String label = "[" + getTypeDisplayName(typeId) + "]";
            g.drawString(font, Component.literal(color + label), tabX, y, 0xFFFFFF, false);
            tabX += font.width(label) + 4;
        }
    } 

    private void renderPanel(GuiGraphics g, int mx, int my) {
        int x = leftPos + PANEL_X;
        int y = topPos + PANEL_Y + 16; // 搜索框+标签下方

        // 面板背景
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0xCC111111);

        // 面板标题
        String header = "§6─ 存储类型一览";
        int headerW = font.width(header);
        g.drawString(font, Component.literal(header), x + 2, topPos + PANEL_Y - 36, 0xFFFFAA, false);
        String countStr = "§7" + displayItems.size() + "种";
        if (currentTypeFilter != null || !searchBox.getValue().isEmpty()) {
            countStr += "/" + filteredItems.size();
        }
        g.drawString(font, Component.literal(countStr), x + 6 + headerW, topPos + PANEL_Y - 36, 0x888888, false);

        if (filteredItems.isEmpty()) {
            String msg = displayItems.isEmpty()
                    ? "§7存入后会出现在这里"
                    : "§7无匹配";
            g.drawString(font, Component.literal(msg), x + 8, y + 6, 0x888888, false);
            return;
        }

        int totalRows = filteredItems.size();
        int startIdx = Math.min(scrollOffset, Math.max(0, totalRows - maxVisibleRows));
        int endIdx = Math.min(startIdx + maxVisibleRows, totalRows);

        for (int i = startIdx; i < endIdx; i++) {
            int rowY = y + (i - startIdx) * ROW_H;
            var entry = filteredItems.get(i);
            boolean isInfinite = entry.isInfinite;
            AEKey key = entry.key;
            long amount = entry.amount;
            long bytes = entry.bytes;

            // 行悬停高亮
            boolean hovered = mx >= x + 1 && mx < x + PANEL_W - 1
                    && my >= rowY && my < rowY + ROW_H;
            if (hovered) {
                g.fill(x + 1, rowY, x + PANEL_W - 1, rowY + ROW_H, 0x66FFFF00);
            }

            // 物品图标
            ItemStack iconStack = ItemStack.EMPTY;
            if (key instanceof appeng.api.stacks.AEItemKey itemKey) {
                iconStack = itemKey.toStack();
            } else if (key.getType().equals(AEKeyType.fluids())) {
                iconStack = new ItemStack(Items.WATER_BUCKET);
            } else {
                iconStack = new ItemStack(Items.BUCKET);
            }
            if (!iconStack.isEmpty()) {
                g.renderItem(iconStack, x + 2, rowY);
                g.renderItemDecorations(font, iconStack, x + 2, rowY);
            }

            // 物品名
            String name = key.getDisplayName().getString();
            if (font.width(name) > 80) name = font.plainSubstrByWidth(name, 77) + "…";
            g.drawString(font, name, x + 20, rowY + 3, isInfinite ? 0xFFFFAA : 0xCCCCCC, false);

            // 数量/无限
            if (isInfinite) {
                g.drawString(font, Component.literal("§b∞"), x + PANEL_W - 20, rowY + 3, 0x55FFFF, false);
            } else {
                String ct = formatAmount(amount);
                int cw = font.width(ct);
                g.drawString(font, ct, x + PANEL_W - cw - 10, rowY + 3, 0xAAAAAA, false);
            }
        }

        // 滚动条
        if (totalRows > maxVisibleRows) {
            int barH = Math.max(maxVisibleRows * PANEL_H / totalRows, 8);
            int barY = y + (startIdx * PANEL_H / totalRows);
            g.fill(x + PANEL_W - 3, barY, x + PANEL_W - 1, barY + barH, 0xFF888888);
        }
    }

    private String formatAmount(long amount) {
        if (amount >= 1_000_000_000_000_000_000L) return (amount / 1_000_000_000_000_000_000L) + "E";
        if (amount >= 1_000_000_000_000_000L) return (amount / 1_000_000_000_000_000L) + "P";
        if (amount >= 1_000_000_000_000L) return (amount / 1_000_000_000_000L) + "T";
        if (amount >= 1_000_000_000) return (amount / 1_000_000_000) + "G";
        if (amount >= 1_000_000) return (amount / 1_000_000) + "M";
        if (amount >= 1_000) return (amount / 1_000) + "K";
        return String.valueOf(amount);
    }

    // ── 点击处理 ──

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (uiState == 0) return super.mouseClicked(mx, my, button);
        // 检查分类标签点击（标签行区域）
        if (my >= topPos + PANEL_Y + 2 && my < topPos + PANEL_Y + 16) {
            int tabX = leftPos + PANEL_X + 2;
            int tabW = font.width("[全部]") + 4;
            if (mx >= tabX && mx < tabX + tabW) {
                currentTypeFilter = null;
                applyFilters();
                return true;
            }
            tabX += tabW;
            for (ResourceLocation typeId : availableTypes) {
                String label = "[" + getTypeDisplayName(typeId) + "]";
                tabW = font.width(label) + 4;
                if (mx >= tabX && mx < tabX + tabW) {
                    currentTypeFilter = typeId;
                    applyFilters();
                    return true;
                }
                tabX += tabW;
            }
        } 

        // 检查面板区域条目点击
        if (isInPanelArea(mx, my)) {
            int row = getClickedRow(mx, my);
            if (row >= 0 && row < filteredItems.size()) {
                if (hasShiftDown()) {
                    menu.sendToggleInfinite(filteredItems.get(row).key.toTagGeneric());
                    return true;
                }
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (uiState == 0) return super.mouseScrolled(mx, my, delta);
        if (isInPanelArea(mx, my)) {
            if (delta < 0) {
                scrollOffset = Math.min(scrollOffset + 1, Math.max(0, filteredItems.size() - maxVisibleRows));
            } else if (delta > 0) {
                scrollOffset = Math.max(scrollOffset - 1, 0);
            }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (uiState == 0) return super.keyPressed(keyCode, scanCode, modifiers);
        // 搜索框获得焦点时，E键不应关闭界面（让'e'正常输入到搜索框）
        if (searchBox.isFocused() && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            for (var child : children()) {
                if (child.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean isInPanelArea(double mx, double my) {
        int x = leftPos + PANEL_X;
        int y = topPos + PANEL_Y + 16;
        return mx >= x && mx < x + PANEL_W && my >= y && my < y + PANEL_H;
    }

    private int getClickedRow(double mx, double my) {
        int y = topPos + PANEL_Y + 16;
        int relY = (int) (my - y);
        return relY / ROW_H + scrollOffset;
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (uiState == 0) return; // 工作模式选择界面不需要 tick
        // 检查数据更新
        List<UnlimitedCellInventory.PanelItem> menuItems = menu.getPanelItems();
        if (!menuItems.equals(displayItems)) {
            this.displayItems = menuItems;
            applyFilters();
        }
        // 搜索框内容变化时实时重新过滤
        if (!searchBox.getValue().equals(lastSearch)) {
            lastSearch = searchBox.getValue();
            applyFilters();
        }
    }
}
