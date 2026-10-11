package com.ae2addon.gui;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;

import com.ae2addon.network.CellNetwork;
import com.ae2addon.network.Mode2ConfigPacket;
import com.ae2addon.network.RequestCellScreenPacket;
import com.ae2addon.util.PinyinIndex;
import com.ae2addon.util.SearchMatch;
import com.ae2addon.cell.UnlimitedCellInventory;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.oredict.OreDictionary;
import org.lwjgl.input.Mouse;

/** Mode2 editor for the P2 settings exposed through UnlimitedCellInventory. */
@SideOnly(Side.CLIENT)
public final class Mode2ConfigScreen extends GuiContainer {
    private static final ResourceLocation PANEL = new ResourceLocation("ae2addon", "textures/gui/cell_panel.png");
    private static final int PANEL_X = 10, PANEL_Y = 72, PANEL_W = 236, ROW_H = 14;
    /**
     * The three tab labels in the header row (y+36). They share 144..246 with each other, so the third one
     * (2026-10-10) forced a re-layout: 存储 / 拒收 / 批量无限 in the Chinese font is 18 + 18 + 36 px.
     */
    private static final int TAB_ITEMS_X = 144, TAB_REJECT_X = 172, TAB_BATCH_X = 198;
    /** How long typing has to pause before the registry search is sent (it scans thousands of keys). */
    private static final long SEARCH_DEBOUNCE_MS = 350L;
    /** Category strip geometry: chips live between the two arrow gaps (see buildChips). */
    private static final int CATEGORY_Y = 75;
    private static final int CATEGORY_HIT_TOP = 73, CATEGORY_HIT_BOTTOM = 87;
    private static final int CATEGORY_LEFT = 18, CATEGORY_RIGHT = 238;
    private static final int CHIP_GAP = 8;
    /** A single chip's label cap, so one long rule name cannot push the other categories off the strip. */
    private static final int CHIP_MAX_LABEL_W = 92;
    /** How far one click on an arrow (or one wheel notch) moves the strip. */
    private static final int CATEGORY_STEP = 24;

    /** One clickable category entry of the strip, positioned in screen space. */
    private static final class Chip {
        final String id;     // "item" | "blacklist" | "ore:<name>" | "mod:<name>"
        final String label;  // possibly shortened for display
        final String full;   // the untruncated label, shown on hover
        final int color;
        final int x;
        final int width;

        Chip(String id, String label, String full, int color, int x, int width) {
            this.id = id;
            this.label = label;
            this.full = full;
            this.color = color;
            this.x = x;
            this.width = width;
        }

        boolean contains(int mouseX) {
            return mouseX >= x && mouseX < x + width;
        }
    }
    private final Mode2ConfigMenu menu;
    private final List<Mode2ConfigPacket.PanelEntry> all = new ArrayList<>();
    private final List<Mode2ConfigPacket.PanelEntry> filtered = new ArrayList<>();
    private final Map<Integer, List<Mode2ConfigPacket.PanelEntry>> pending = new TreeMap<>();
    private final ChunkRetry panelRetry = new ChunkRetry();
    private final RejectListState rejectList = new RejectListState();
    private final List<ItemStack> blacklist = new ArrayList<>();
    private final Map<String, String> committedAmounts = new LinkedHashMap<>();
    private final Map<Integer, List<ItemStack>> pendingBlacklist = new TreeMap<>();
    private final ChunkRetry blacklistRetry = new ChunkRetry();
    private final Map<String, Integer> oreRules = new LinkedHashMap<>(), modRules = new LinkedHashMap<>();
    private int expectedBlacklist;
    private boolean blacklistComplete;
    private final List<ItemStack> rejectItems = new ArrayList<>();
    private int expectedChunks;
    private boolean panelComplete;
    /** Which channel the chunks currently buffered in {@link #pending} belong to (see the type-4 handler). */
    private String pendingChannel = "";
    private boolean ruleInstant;
    private int uiState; // 0: choose work mode, 1: editor
    private int workMode = 1;
    private long currentThreshold = UnlimitedCellInventory.DEFAULT_THRESHOLD;
    private boolean rejectTab;
    /**
     * The third tab (2026-10-10, sensei's request): search the whole REGISTRY of the current channel and mark
     * what the search finds infinite in one go. Mutually exclusive with {@link #rejectTab}; both false = the
     * storage tab. Its list does NOT come from {@link #all} (that is the cell's own rows) but from
     * {@link #searchResults}, which the server fills in.
     */
    private boolean batchTab;
    /** Rows of the last completed registry search (packet type 26). */
    private final List<Mode2ConfigPacket.PanelEntry> searchResults = new ArrayList<>();
    private final Map<Integer, List<Mode2ConfigPacket.PanelEntry>> pendingSearch = new TreeMap<>();
    private final ChunkRetry searchRetry = new ChunkRetry();
    private int expectedSearchChunks;
    private boolean searchComplete;
    /** The query the last search was SENT for -- not the current text (see the debounce in updateScreen). */
    private String searchSent = "";
    private long searchDueAt;
    /** 0 = nothing armed, 1 = 「全部标无限」 awaiting confirmation, 2 = 「全部取消无限」 awaiting confirmation. */
    private int batchArmed;
    private boolean rejectDefault;
    private int rejectOnCount, rejectOffCount;
    private int scrollOffset;
    /** Which storage channel the panel is showing. Shared settings do not change with it -- only the rows do. */
    private String panelChannel = com.ae2addon.cell.CellChannelRegistry.ITEM;
    private GuiTextField thresholdInput, searchInput, itemThresholdInput, ruleInput, quantityInput;
    private ItemStack editing = ItemStack.EMPTY;
    private ItemStack quantityEditing = ItemStack.EMPTY;
    private boolean quantityLock;
    private int categoryScroll;
    private String activeRule;
    private GuiButton defaultButton;
    private String lastSearch = "";
    private boolean rebuildPending;

    public Mode2ConfigScreen(Mode2ConfigMenu menu) {
        super(menu);
        this.menu = menu;
        xSize = 256; ySize = 276;
    }

    @Override public void initGui() {
        super.initGui();
        if (menu.cell().hasTagCompound() && menu.cell().getTagCompound().hasKey("ae2addon:cell")) {
            net.minecraft.nbt.NBTTagCompound tag = menu.cell().getTagCompound().getCompoundTag("ae2addon:cell");
            workMode = tag.getInteger("wm");
            if (workMode < 1 || workMode > 3) workMode = 1;
            currentThreshold = tag.getLong("thr") > 0 ? tag.getLong("thr") : UnlimitedCellInventory.DEFAULT_THRESHOLD;
        }
        buildControls();
        CellNetwork.send(new Mode2ConfigPacket(3));
    }

    private void buildControls() {
        buttonList.clear();
        menu.slotsHidden = uiState == 0;
        int x = guiLeft, y = guiTop;

        // Create all text fields for BOTH uiState values.
        // They used to be created only inside the uiState == 1 branch below, but actionPerformed()
        // flips uiState to 1 while the rebuild (rebuildPending) is only consumed later in
        // updateScreen()/drawScreen(). The super.mouseClicked() call at the end of that same
        // mouseClicked() invocation therefore ran with uiState == 1 and these fields still null -> NPE.
        // Drawing and mouse/key handling below stay gated on uiState == 1, so the choose-mode page is
        // unaffected.
        thresholdInput = new GuiTextField(0, fontRenderer, x + 10, y + 20, 100, 16);
        thresholdInput.setMaxStringLength(19);
        thresholdInput.setText(Long.toString(currentThreshold));
        searchInput = new GuiTextField(1, fontRenderer, x + 10, y + 48, 136, 14);
        searchInput.setMaxStringLength(40);
        searchInput.setText(lastSearch);
        itemThresholdInput = new GuiTextField(2, fontRenderer, x + 10, y + 152, 166, 16);
        itemThresholdInput.setMaxStringLength(64);
        ruleInput = new GuiTextField(3, fontRenderer, x + 10, y + 152, 166, 16);
        ruleInput.setMaxStringLength(128);
        quantityInput = new GuiTextField(4, fontRenderer, x + 10, y + 152, 166, 16);
        quantityInput.setMaxStringLength(128);

        if (uiState == 0) {
            buttonList.add(new GuiButton(1, x + 48, y + 24, 160, 22,
                    (workMode == 1 ? "§a> " : "   ") + I18n.format("gui.ae2addon.mode2.wm.threshold")));
            buttonList.add(new GuiButton(2, x + 48, y + 52, 160, 22,
                    (workMode == 2 ? "§a> " : "   ") + I18n.format("gui.ae2addon.mode2.wm.deposit")));
            buttonList.add(new GuiButton(3, x + 48, y + 80, 160, 22,
                    (workMode == 3 ? "§a> " : "   ") + I18n.format("gui.ae2addon.mode2.wm.cherish")));
            buttonList.add(new GuiButton(4, x + 98, y + 108, 60, 16, I18n.format("gui.ae2addon.mode2.back")));
            return;
        }
        buttonList.add(new GuiButton(5, x + 158, y + 49, 60, 12, I18n.format("gui.ae2addon.mode2.back")));
        GuiButton save = new GuiButton(6, x + 114, y + 18, 50, 18, I18n.format("gui.ae2addon.mode2.save"));
        save.visible = workMode == 1;
        buttonList.add(save);
        buttonList.add(new GuiButton(7, x + 168, y + 18, 22, 18, "§bR"));
        buttonList.add(new GuiButton(8, x + 192, y + 18, 54, 18, "§e⇄ " + workMode));
        buttonList.add(new GuiButton(9, x + 180, y + 151, 28, 18, "§a✓"));
        buttonList.add(new GuiButton(10, x + 212, y + 151, 28, 18, "§c✕"));
        buttonList.add(new GuiButton(12, x + 10, y + 170, 48, 16, I18n.format("gui.ae2addon.p3.add_ore")));
        buttonList.add(new GuiButton(13, x + 62, y + 170, 48, 16, I18n.format("gui.ae2addon.p3.add_mod")));
        buttonList.add(new GuiButton(14, x + 112, y + 170, 43, 16, I18n.format("gui.ae2addon.p3.save_qty")));
        buttonList.add(new GuiButton(15, x + 160, y + 170, 42, 16,
                I18n.format(quantityLock ? "gui.ae2addon.p3.lock" : "gui.ae2addon.p3.unlock")));
        buttonList.add(new GuiButton(16, x + 205, y + 170, 41, 16,
                I18n.format(ruleInstant ? "gui.ae2addon.p3.all_instant" : "gui.ae2addon.p3.all_touch")));
        defaultButton = new GuiButton(11, x + 10, y + 172, 150, 16, "");
        defaultButton.y = y + 35;
        defaultButton.width = 126;
        buttonList.add(defaultButton);
        // Batch tab (2026-10-10): two action buttons on the (otherwise unused) lower row, and the confirm pair
        // on the rule-input row -- which this tab does not draw at all (see drawScreen).
        buttonList.add(new GuiButton(17, x + 10, y + 170, 113, 16,
                I18n.format("gui.ae2addon.mode2.batch_add")));
        buttonList.add(new GuiButton(18, x + 129, y + 170, 113, 16,
                I18n.format("gui.ae2addon.mode2.batch_remove")));
        buttonList.add(new GuiButton(19, x + 10, y + 151, 113, 16,
                I18n.format("gui.ae2addon.mode2.batch_confirm_btn")));
        buttonList.add(new GuiButton(20, x + 129, y + 151, 113, 16,
                I18n.format("gui.ae2addon.mode2.batch_cancel_btn")));
        updateDefaultButton();
        updateEditingButtons();
        applyTabVisibility();
        updateBatchButtons();
    }

    /**
     * Which controls each tab shows.
     *
     * <p>The batch tab has no rule / quantity editing (those are storage-tab concepts) and owns its own four
     * buttons instead. Visibility is set by id rather than by rebuilding the list, so the index-based lookups
     * in {@link #updateEditingButtons()} keep pointing at the same buttons.
     */
    private void applyTabVisibility() {
        for (GuiButton button : buttonList) {
            if (button.id == 14 || button.id == 15) {
                // 保存数量 / 锁定 属于**数量编辑框**，所以跟着 `quantityEditing` 走，而不是跟着页签走 ✗。
                // 以前它们和 12/13/16 一起被 `!batchTab` 打开 ⇒ **一进配置界面就显示** ✓
                // （sensei 2026-10-10 实测发现的初始 UI bug）；编辑期间由 updateEditingButtons() 同步 ✓。
                button.visible = !batchTab && !quantityEditing.isEmpty();
            } else if (button.id >= 12 && button.id <= 16) {
                button.visible = !batchTab;
            } else if (button.id == 17 || button.id == 18) {
                button.visible = batchTab;
            } else if (button.id == 19 || button.id == 20) {
                button.visible = batchTab && batchArmed != 0;
            }
        }
    }

    /** Relabels the two action buttons for the armed state, so "clicked once" is visible before it costs anything. */
    private void updateBatchButtons() {
        for (GuiButton button : buttonList) {
            if (button.id == 17) {
                button.displayString = I18n.format(batchArmed == 1 ? "gui.ae2addon.mode2.batch_add_armed"
                        : "gui.ae2addon.mode2.batch_add");
            } else if (button.id == 18) {
                button.displayString = I18n.format(batchArmed == 2 ? "gui.ae2addon.mode2.batch_remove_armed"
                        : "gui.ae2addon.mode2.batch_remove");
            }
        }
    }

    /**
     * Switches tabs: 0 = the cell's storage rows, 1 = the reject rows, 2 = the registry search.
     *
     * <p>Entering the batch tab drops the rule/quantity editor (it belongs to the storage tab and would draw
     * over the confirm buttons) and asks the server for the current query right away, without waiting for the
     * debounce.
     */
    private void setTab(int tab) {
        boolean wasBatch = batchTab;
        rejectTab = tab == 1;
        batchTab = tab == 2;
        scrollOffset = 0;
        batchArmed = 0;
        if (batchTab && !wasBatch) {
            hideItemThreshold();
            quantityEditing = ItemStack.EMPTY;
            quantityInput.setFocused(false);
        }
        applyTabVisibility();
        updateBatchButtons();
        applyFilters();
        if (batchTab) {
            requestSearch();
        }
    }

    private void updateDefaultButton() {
        if (defaultButton != null) defaultButton.displayString = (rejectDefault ? "[x] " : "[ ] ")
                + I18n.format("gui.ae2addon.mode2.reject_default");
    }

    /**
     * 左 Ctrl **或** 右 Ctrl。
     *
     * <p>Minecraft 自己的 isCtrlKeyDown() **只读左 Ctrl** ✗ ⇒ 按右 Ctrl 的玩家得到的是普通左键，
     * "元件内数量"编辑框永远不弹 ✓，而右键那条（单物品阈值）照样好用 ⇒ 现象就是"改的其实是阈值"
     * （sensei 2026-10-10 实测）。
     */
    private static boolean ctrlDownAny() {
        return org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
                || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
    }
    /**
     * 让"单物品阈值"与"元件内数量"两套编辑按钮只在各自编辑框活着时出现。
     *
     * <p><b>为什么改成按 id 找</b>（sensei 2026-10-10：「那两个按钮在我设置数量的时候会消失，之后只要我不退出配置页面就不会再出现」）：
     * 原来用**下标** uttonList.get(8)/get(9) ✗，而列表开头还有 1/2/3 号"选模式"按钮 ⇒ 下标 8/9 实际落在
     * **13 号与 14 号（保存数量）** ✗ ⇒ ① **锁定(15) 没人管** ✗；② 一进数量编辑，14 号随
     * quantityEditing 变空被藏掉，而且再也不会自己回来 ✓ —— 完全是他看到的现象 ✓。
     *
     * <p>教训：uttonList 的下标会随按钮数量漂移（批量页签刚加了 17–20 号 ✗），
     * **凡是有 id 语义的地方就按 id 找** ✓（updateBatchButtons() 一直是对的 ✓）。
     */
    private void updateEditingButtons() {
        if (uiState != 1) {
            return;
        }
        for (GuiButton button : buttonList) {
            if (button.id == 9 || button.id == 10) {
                button.visible = !editing.isEmpty();
            } else if (button.id == 14 || button.id == 15) {
                button.visible = !quantityEditing.isEmpty();
            }
        }
    }

    private void updateRuleInstantButton() {
        for (GuiButton button : buttonList) if (button.id == 16)
            button.displayString = I18n.format(ruleInstant ? "gui.ae2addon.p3.all_instant"
                    : "gui.ae2addon.p3.all_touch");
    }

    @Override protected void actionPerformed(GuiButton button) {
        if (button.id >= 1 && button.id <= 3) {
            workMode = button.id;
            CellNetwork.send(Mode2ConfigPacket.workMode(workMode));
            uiState = 1; rebuildPending = true;
        } else if (button.id == 4) CellNetwork.send(new RequestCellScreenPacket(CellScreens.SELECT));
        else if (button.id == 5) { uiState = 0; rebuildPending = true; }
        else if (button.id == 6) saveThreshold();
        else if (button.id == 7) refresh();
        else if (button.id == 8) {
            workMode = workMode % 3 + 1;
            CellNetwork.send(Mode2ConfigPacket.workMode(workMode));
            rebuildPending = true;
        } else if (button.id == 9) saveItemThreshold();
        else if (button.id == 10) hideItemThreshold();
        else if (button.id == 11) {
            rejectDefault = !rejectDefault;
            updateDefaultButton();
            CellNetwork.send(new Mode2ConfigPacket(8, rejectDefault));
        } else if (button.id == 12 || button.id == 13) {
            String name = ruleInput.getText().trim();
            if (!name.isEmpty()) { CellNetwork.send(Mode2ConfigPacket.rule(19, button.id == 12, name, 1)); ruleInput.setText(""); }
        } else if (button.id == 14) saveQuantity();
        else if (button.id == 15) {
            quantityLock = !quantityLock;
            button.displayString = I18n.format(quantityLock ? "gui.ae2addon.p3.lock" : "gui.ae2addon.p3.unlock");
        } else if (button.id == 16) {
            ruleInstant = !ruleInstant;
            updateRuleInstantButton();
            CellNetwork.send(new Mode2ConfigPacket(10, ruleInstant));
        } else if (button.id == 17 || button.id == 18) {
            // ARM, never fire. Marking hundreds of keys infinite is not something a stray click may do: the same
            // "you have to mean it" spirit as the format button's DELETE box in ModeSelectScreen.
            int target = button.id - 16;
            batchArmed = batchArmed == target ? 0 : target;
            applyTabVisibility();
            updateBatchButtons();
        } else if (button.id == 19) {
            // The confirmation button: the ONLY click that sends the bulk action (type 27).
            if (batchArmed == 0) return;
            boolean add = batchArmed == 1;
            batchArmed = 0;
            applyTabVisibility();
            updateBatchButtons();
            Mode2ConfigPacket packet = new Mode2ConfigPacket(27);
            packet.text = searchInput.getText();
            packet.flag = add;
            // The channel the search ran on. Empty or "all" mean "whatever the panel shows"; on "all" the server
            // searches EVERY channel the cell speaks and each row names its own channel (sensei, 2026-10-10: it
            // used to scan the item channel only, which is why a fluid could not be marked infinite as a fluid).
            packet.channelId = panelChannel;
            CellNetwork.send(packet);
        } else if (button.id == 20) {
            batchArmed = 0;
            applyTabVisibility();
            updateBatchButtons();
        }
    }

    /** Asks the server for the current query's registry rows right now, skipping the typing debounce. */
    private void requestSearch() {
        searchDueAt = 0L;
        sendSearch(searchInput.getText());
    }

    private void sendSearch(String query) {
        searchSent = query;
        searchComplete = false;
        pendingSearch.clear();
        expectedSearchChunks = 0;
        searchRetry.start();
        Mode2ConfigPacket packet = new Mode2ConfigPacket(25);
        packet.text = query;
        packet.channelId = panelChannel;
        CellNetwork.send(packet);
    }

    /**
     * The typing debounce for the registry search.
     *
     * <p>A registry search is a SERVER-side scan of every key in the channel (thousands of display names through
     * {@code PinyinIndex}), so one request per keystroke is not acceptable: the query goes out once typing has
     * paused. Anything already sent is left alone; only a changed query arms the timer.
     */
    private void tickBatchSearch() {
        String text = searchInput.getText();
        if (text.equals(searchSent)) {
            searchDueAt = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (searchDueAt == 0L) {
            searchDueAt = now + SEARCH_DEBOUNCE_MS;
        } else if (now >= searchDueAt) {
            sendSearch(text);
        }
    }

    private void refresh() {
        if (panelComplete) { pending.clear(); expectedChunks = 0; panelComplete = false; }
        if (blacklistComplete) { pendingBlacklist.clear(); expectedBlacklist = 0; blacklistComplete = false; }
        panelRetry.manualRefresh(); blacklistRetry.manualRefresh(); rejectList.manualRefresh();
        CellNetwork.send(new Mode2ConfigPacket(3));
        if (batchTab) {
            requestSearch();
        }
    }

    private void saveThreshold() {
        try {
            long value = ThresholdExpression.parse(thresholdInput.getText());
            if (value < 1) throw new NumberFormatException();
            CellNetwork.send(new Mode2ConfigPacket(0, value));
            currentThreshold = value;
        } catch (NumberFormatException ex) { thresholdInput.setText(Long.toString(currentThreshold)); }
    }

    /** The key NBT of the row whose threshold is being edited (null = an item row from an older path). */
    private net.minecraft.nbt.NBTTagCompound editingKeyNbt;

    /**
     * The channel of the ROW whose threshold box is open -- not the panel's selection.
     *
     * <p>Since the panel gained a 全部 view, {@code panelChannel} can be the pseudo id {@code "all"}, which
     * {@code CellChannelRegistry.byId()} cannot resolve, so the server's {@code resolveKey} returned null and
     * dropped the action: the threshold box looked dead on that view and worked on any single channel
     * (sensei's A/B log, 2026-10-10: {@code channel=all key=null（会被丢弃）}). Every row knows its own channel.
     */
    private String editingChannelId;
    /**
     * The key of the row whose "元件内数量" is being edited, in its own channel's NBT.
     *
     * <p>Why the quantity path needs its own copy (sensei, 2026-10-10: 「还是不行，改完后变的单物品阈值」):
     * it sent the packet with the channel but WITHOUT the key, so on any non-item channel the server could not
     * resolve the key and dropped the action -- while the single-item-threshold path, which does carry its key,
     * kept working. That is exactly why the number looked like it had gone into the threshold instead.
     */
    private net.minecraft.nbt.NBTTagCompound quantityKeyNbt;

    /** Same reasoning as {@link #editingChannelId}: the quantity box acts on the ROW's channel. */
    private String quantityChannelId;

    /** The row's own channel when it has one, else the panel's selection (see {@link #editingChannelId}). */
    private String rowChannelOrPanel(String rowChannelId) {
        return rowChannelId == null || rowChannelId.isEmpty() ? panelChannel : rowChannelId;
    }

    /**
     * Draws {@code text} at a fraction of the normal font, trimmed so it never passes {@code maxWidth} SCREEN
     * pixels. The trim happens in unscaled units ({@code maxWidth / scale}), which is the whole point: a smaller
     * font fits more characters into the same space instead of just looking smaller.
     */
    private void drawScaledToWidth(String text, int x, int y, float scale, int maxWidth, int color) {
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(x, y, 0F);
        net.minecraft.client.renderer.GlStateManager.scale(scale, scale, 1F);
        fontRenderer.drawString(fontRenderer.trimStringToWidth(text, (int) (maxWidth / scale)), 0, 0, color);
        net.minecraft.client.renderer.GlStateManager.popMatrix();
    }

    /**
     * What an infinite row shows for its amount: plain {@code ∞}, or {@code ∞(n)} carrying the committed amount
     * when the cell reports one (sensei, 2026-10-10: "无限物品的承诺额度改以 ∞（n）形式呈现").
     *
     * <p>The {@code "(threshold)"} marker the server adds means the number is just the row's threshold and
     * nothing was ever committed, so it is NOT shown as an amount -- {@code ∞(1000)} there would claim a
     * commitment that does not exist.
     */
    private String infiniteLabel(Mode2ConfigPacket.PanelEntry entry) {
        String committed = committedAmounts.get(itemId(entry.item));
        if (committed == null || committed.contains("(threshold)")) {
            return "§b∞";
        }
        return "§b∞(§f" + committed + "§b)";
    }

    private void showItemThreshold(ItemStack item, long threshold) {
        quantityEditing = ItemStack.EMPTY;
        editing = item;
        itemThresholdInput.setText(Long.toString(threshold));
        itemThresholdInput.setFocused(true);
        updateEditingButtons();
    }

    private void hideItemThreshold() {
        editing = ItemStack.EMPTY;
        itemThresholdInput.setFocused(false);
        updateEditingButtons();
    }

    private void saveItemThreshold() {
        if (editing.isEmpty()) return;
        try {
            String raw = itemThresholdInput.getText().trim();
            long value = raw.isEmpty() ? 0 : ThresholdExpression.parse(raw);
            if (value < 0) throw new NumberFormatException();
            Mode2ConfigPacket packet = Mode2ConfigPacket.itemThreshold(editing.copy(), value);
        packet.channelId = rowChannelOrPanel(editingChannelId);
        packet.keyNbt = editingKeyNbt;
        CellNetwork.send(packet);
            hideItemThreshold();
        } catch (NumberFormatException ex) { itemThresholdInput.setText(""); }
    }
    private void saveQuantity() {
        if (quantityEditing.isEmpty()) return;
        Mode2ConfigPacket packet = Mode2ConfigPacket.quantity(quantityEditing.copy(),
                quantityInput.getText(), quantityLock);
        // Carry the row's OWN key, exactly like saveItemThreshold does. Without these two fields the server can
        // only rebuild the key from the item stack, which is no key at all on a fluid / gas / mana / flux row --
        // the action was silently dropped there (see quantityKeyNbt).
        packet.channelId = rowChannelOrPanel(quantityChannelId);
        packet.keyNbt = quantityKeyNbt;
        CellNetwork.send(packet);
        quantityEditing = ItemStack.EMPTY;
        quantityKeyNbt = null;
        quantityInput.setFocused(false);
        updateEditingButtons();
    }

    public void accept(Mode2ConfigPacket packet) {
        if (packet.type == 18 && packet.state != null) {
            if (packet.state.hasKey("ch", 8) && !packet.state.getString("ch").isEmpty()) {
                panelChannel = packet.state.getString("ch");
            }
            rejectDefault = packet.state.getBoolean("default");
            ruleInstant = packet.state.getBoolean("ruleInstant");
            updateRuleInstantButton();
            rejectOnCount = packet.state.getTagList("on", 10).tagCount();
            rejectOffCount = packet.state.getTagList("off", 10).tagCount();
            committedAmounts.clear();
            net.minecraft.nbt.NBTTagList committed = packet.state.getTagList("committed", 10);
            for (int i = 0; i < committed.tagCount(); i++) {
                net.minecraft.nbt.NBTTagCompound row = committed.getCompoundTagAt(i);
                ItemStack stack = new ItemStack(row.getCompoundTag("key"));
                if (!stack.isEmpty()) committedAmounts.put(itemId(stack), row.getString("amount")
                        + (row.getBoolean("has") ? "" : " (threshold)"));
            }
            updateDefaultButton();
        } else if (packet.type == 4) {
            // A chunk set belongs to ONE channel. After a channel switch the incoming rows are a fresh list,
            // not a continuation -- and the old and new lists often have the same chunk count, so without this
            // reset the previous channel's chunks stayed in `pending` and were spliced back in, which is why the
            // panel kept showing item rows whatever the player selected (sensei, 2026-10-04).
            if (!packet.channelId.equals(pendingChannel) || expectedChunks == 0
                    || expectedChunks != packet.totalChunks) {
                pending.clear();
                pendingChannel = packet.channelId;
                expectedChunks = packet.totalChunks;
                panelComplete = false;
                panelRetry.start();
            }
            if (expectedChunks != packet.totalChunks || packet.chunkIndex < 0
                    || packet.chunkIndex >= expectedChunks) return;
            if (panelComplete) pending.put(packet.chunkIndex, packet.entries);
            else if (pending.putIfAbsent(packet.chunkIndex, packet.entries) == null) panelRetry.progress();
            if (pending.size() == expectedChunks) {
                all.clear();
                for (int i = 0; i < expectedChunks; i++) all.addAll(pending.get(i));
                // Keep complete chunks: trailing packets from a retry must not start a false partial list.
                panelComplete = true;
                applyFilters();
            }
        } else if (packet.type == 26) {
            // Registry search chunks (batch tab). Same accumulation rules as the panel's chunks: a chunk set
            // belongs to ONE search, and a fresh set is not a continuation of the previous one.
            if (expectedSearchChunks == 0 || expectedSearchChunks != packet.totalChunks) {
                pendingSearch.clear();
                expectedSearchChunks = packet.totalChunks;
                searchComplete = false;
                searchRetry.start();
            }
            if (expectedSearchChunks != packet.totalChunks || packet.chunkIndex < 0
                    || packet.chunkIndex >= expectedSearchChunks) return;
            if (searchComplete) pendingSearch.put(packet.chunkIndex, packet.entries);
            else if (pendingSearch.putIfAbsent(packet.chunkIndex, packet.entries) == null) searchRetry.progress();
            if (pendingSearch.size() == expectedSearchChunks) {
                searchResults.clear();
                for (int i = 0; i < expectedSearchChunks; i++) searchResults.addAll(pendingSearch.get(i));
                // Keep complete chunks: a trailing packet from a retry must not start a false partial list.
                searchComplete = true;
                scrollOffset = 0;
                // The result set just changed, so a pending confirmation no longer names anything.
                batchArmed = 0;
                applyTabVisibility();
                updateBatchButtons();
                applyFilters();
            }
        } else if (packet.type == 21) {
            rejectList.handleChunk(packet.on, packet.off, packet.chunkIndex, packet.totalChunks);
            rejectItems.clear(); rejectItems.addAll(rejectList.on()); rejectItems.addAll(rejectList.off());
            applyFilters();
        } else if (packet.type == 12) {
            if (expectedBlacklist == 0 || expectedBlacklist != packet.totalChunks) {
                pendingBlacklist.clear(); expectedBlacklist = packet.totalChunks;
                blacklistComplete = false; blacklistRetry.start();
            }
            if (expectedBlacklist == packet.totalChunks && packet.chunkIndex >= 0
                    && packet.chunkIndex < expectedBlacklist) {
                if (blacklistComplete) pendingBlacklist.put(packet.chunkIndex, packet.keys);
                else if (pendingBlacklist.putIfAbsent(packet.chunkIndex, packet.keys) == null) blacklistRetry.progress();
                if (pendingBlacklist.size() == expectedBlacklist) {
                    blacklist.clear();
                    for (int i = 0; i < expectedBlacklist; i++) blacklist.addAll(pendingBlacklist.get(i));
                    blacklistComplete = true;
                    applyFilters();
                }
            }
        } else if (packet.type == 9) {
            Map<String, Integer> dest = packet.isOre ? oreRules : modRules;
            dest.clear();
            for (int i = 0; i < packet.rules.size(); i++) dest.put(packet.rules.get(i), packet.modes.get(i));
            if (activeRule != null && activeRule.startsWith(packet.isOre ? "ore:" : "mod:")
                    && !dest.containsKey(activeRule.substring(4))) activeRule = null;
            applyFilters();
        }
    }
    private static String itemId(ItemStack stack) {
        return stack.getItem().getRegistryName() + ":" + stack.getMetadata() + ":"
                + (stack.hasTagCompound() ? stack.getTagCompound().toString() : "");
    }
    private static boolean sameKey(ItemStack a, ItemStack b) {
        return ItemStack.areItemsEqual(a, b) && ItemStack.areItemStackTagsEqual(a, b);
    }
    private static boolean containsStack(List<ItemStack> list, ItemStack needle) {
        for (ItemStack item : list) if (sameKey(item, needle)) return true;
        return false;
    }

    private void applyFilters() {
        filtered.clear();
        String query = searchInput.getText();
        lastSearch = query;
        List<Mode2ConfigPacket.PanelEntry> source = new ArrayList<>(all);
        if (batchTab) {
            // This tab's list IS the server's registry search: it already matched the query against the display
            // name and the registry id of every key in the channel. Re-filtering here would only DROP rows --
            // a non-item row's client-side icon/label does not carry the id the server matched on.
            source.clear();
            source.addAll(searchResults);
        } else if (rejectTab) {
            source.clear();
            // The reject page lists what you can MARK. It used to list only the two explicit reject sets, so an
            // item that was merely STORED had nothing to click -- "in the reject page there is no match for a
            // stored item" (sensei, 2026-10-04). Now it shows the cell's own rows plus any reject entry the cell
            // does not list (rejected but nothing stored). Reject is item-scoped on the wire (packet type 21
            // sends item stacks), so non-item rows are left out.
            if (!rejectList.pending()) {
                for (Mode2ConfigPacket.PanelEntry entry : all) {
                    if (!entry.item.isEmpty()) {
                        source.add(entry);
                    }
                }
                for (ItemStack item : rejectItems) {
                    boolean present = false;
                    for (Mode2ConfigPacket.PanelEntry entry : source) {
                        if (sameKey(entry.item, item)) { present = true; break; }
                    }
                    if (!present) {
                        source.add(new Mode2ConfigPacket.PanelEntry(com.ae2addon.cell.CellChannelRegistry.ITEM,
                                null, item.getDisplayName(), item, BigInteger.ZERO, false, 0L, 0L, false, false));
                    }
                }
            }
        } else if ("blacklist".equals(activeRule)) {
            for (ItemStack item : blacklist) {
                boolean present = false;
                for (Mode2ConfigPacket.PanelEntry entry : source) if (sameKey(entry.item, item)) { present = true; break; }
                if (!present) source.add(new Mode2ConfigPacket.PanelEntry(com.ae2addon.cell.CellChannelRegistry.ITEM, null, item.getDisplayName(), item, BigInteger.ZERO, false, 0L, 0L, false, false));
            }
        }
        for (Mode2ConfigPacket.PanelEntry entry : source) {
            if (batchTab) {
                // The category strip / rule filters belong to the storage tab; the server already did the only
                // filtering this tab has.
                filtered.add(entry);
                continue;
            }
            ItemStack stack = entry.item;
            if (!rejectTab && "blacklist".equals(activeRule) && !containsStack(blacklist, stack)) continue;
            if (!rejectTab && activeRule != null && !"blacklist".equals(activeRule)
                    && !"item".equals(activeRule) && !matchesRule(stack, activeRule)) continue;
            String id = stack.getItem().getRegistryName() == null ? ""
                    : stack.getItem().getRegistryName().toString();
            // A non-item row has no item to search on: use the label the server sent instead of asking an
            // empty stack for its name (which would answer "Air" and match silly queries).
            String searchName = stack.isEmpty() ? (entry.label == null ? "" : entry.label) : stack.getDisplayName();
            // ⚠ 只有物品通道的行才把物品形态交给匹配器：非物品行的 entry.item 是"桶"这种表示栈，
            //    拿它匹配矿词/modid 会让所有流体行都命中 minecraft:*（它的 modid/矿词改由 keyNbt 兜底）。
            //    匹配口径与服务端 searchRegistry 共用 SearchMatch，见那个类的注释。
            boolean itemRow = com.ae2addon.cell.CellChannelRegistry.ITEM.equals(entry.channelId);
            if (SearchMatch.matches(query, searchName, id, itemRow ? stack : null, entry.keyNbt)) filtered.add(entry);
        }
        scrollOffset = Math.min(scrollOffset, Math.max(0, filtered.size() - 4));
    }
    /**
     * What the batch tab says where the category strip normally sits: the size of the last result, how far an
     * unfinished transfer has got, or -- while a confirmation is armed -- exactly what it is about to touch.
     */
    private String batchStatusLine() {
        if (batchArmed != 0) {
            return I18n.format(batchArmed == 1 ? "gui.ae2addon.mode2.batch_confirm_add"
                    : "gui.ae2addon.mode2.batch_confirm_remove", filtered.size());
        }
        if (expectedSearchChunks > 0 && !searchComplete) {
            return I18n.format("gui.ae2addon.p3.partial", pendingSearch.size(), expectedSearchChunks);
        }
        if (!searchComplete) {
            return I18n.format("gui.ae2addon.mode2.batch_searching");
        }
        String result = I18n.format("gui.ae2addon.mode2.batch_result", filtered.size(),
                Mode2ConfigPacket.MAX_SEARCH_ROWS);
        // Row clicks work on every channel now: the key travels as its own channel's NBT (2026-10-10), which is
        // exactly what used to be missing -- a non-item row click made the server rebuild the key from an item
        // stack and threw ClassCastException inside the channel's normalise().
        return result;
    }

    private static boolean matchesRule(ItemStack stack, String rule) {
        if (rule.startsWith("mod:")) return stack.getItem().getRegistryName() != null
                && rule.substring(4).equals(stack.getItem().getRegistryName().getNamespace());
        if (rule.startsWith("ore:")) {
            for (int id : OreDictionary.getOreIDs(stack))
                if (rule.substring(4).equals(OreDictionary.getOreName(id))) return true;
        }
        return false;
    }
    /**
     * The category strip: 物品 / one chip per ore rule / one chip per mod rule / 黑名单.
     *
     * <p>sensei's 2026-10-03 report ("only about 3 categories fit, fewer when the names are long, and that is
     * not all of them") had three causes, all fixed here: the strip only started at x=70 because a duplicate
     * filter label sat to its left, one long rule name could eat the whole row (labels are capped now), and
     * chips that did not fit were simply not drawn with no hint that more existed (arrow zones + wheel now).
     *
     * <p>Built as ONE list that drawing, hit-testing and the scroll range all walk, because the day's other
     * bugs all came from two sides of a judgement drifting apart.
     */
    private List<Chip> buildChips() {
        return buildChips(categoryScroll);
    }

    private List<Chip> buildChips(int scroll) {
        List<Chip> chips = new ArrayList<>();
        int x = guiLeft + CATEGORY_LEFT - scroll;
        x = addChip(chips, "item", I18n.format("gui.ae2addon.p3.cat_items"), 0xCCCCCC, x);
        for (Map.Entry<String, Integer> rule : oreRules.entrySet()) {
            x = addChip(chips, "ore:" + rule.getKey(), ruleLabel("O:", rule.getKey(), rule.getValue()),
                    0x66CCFF, x);
        }
        for (Map.Entry<String, Integer> rule : modRules.entrySet()) {
            x = addChip(chips, "mod:" + rule.getKey(), ruleLabel("M:", rule.getKey(), rule.getValue()),
                    0xDD99FF, x);
        }
        addChip(chips, "blacklist", I18n.format("gui.ae2addon.p3.cat_blacklist"), 0xFF7777, x);
        return chips;
    }

    /** The untruncated label of one rule chip: prefix, rule name, and the Instant/Touch marker. */
    private static String ruleLabel(String prefix, String name, int mode) {
        return prefix + name + (mode == 1 ? " [I]" : " [T]");
    }

    private int addChip(List<Chip> chips, String id, String full, int color, int x) {
        String label = capLabel(full);
        int width = fontRenderer.getStringWidth(label) + CHIP_GAP;
        chips.add(new Chip(id, label, full, color, x, width));
        return x + width;
    }

    /** Caps one chip's label so a single long rule name cannot push every other category off the strip. */
    private String capLabel(String full) {
        if (fontRenderer.getStringWidth(full) <= CHIP_MAX_LABEL_W) {
            return full;
        }
        String suffix = full.endsWith(" [I]") || full.endsWith(" [T]") ? full.substring(full.length() - 4) : "";
        String head = suffix.isEmpty() ? full : full.substring(0, full.length() - 4);
        int room = Math.max(12, CHIP_MAX_LABEL_W - fontRenderer.getStringWidth("…" + suffix));
        return fontRenderer.trimStringToWidth(head, room) + "…" + suffix;
    }

    private int maxCategoryScroll() {
        List<Chip> chips = buildChips(0);
        int total = 0;
        for (Chip chip : chips) {
            total += chip.width;
        }
        return Math.max(0, total - (CATEGORY_RIGHT - CATEGORY_LEFT));
    }

    private boolean canScrollCategoryLeft() {
        return categoryScroll > 0;
    }

    private boolean canScrollCategoryRight() {
        return categoryScroll < maxCategoryScroll();
    }

    /** Chips that are fully inside the strip; the rest are reached with the arrow zones or the wheel. */
    private boolean chipVisible(Chip chip) {
        return chip.x >= guiLeft + CATEGORY_LEFT && chip.x + chip.width <= guiLeft + CATEGORY_RIGHT;
    }

    @Override public void updateScreen() {
        super.updateScreen();
        if (rebuildPending) { rebuildPending = false; buildControls(); }
        if (uiState == 1 && !lastSearch.equals(searchInput.getText())) applyFilters();
        if (panelRetry.retryDue(expectedChunks > 0 && !panelComplete)) CellNetwork.send(new Mode2ConfigPacket(3));
        if (blacklistRetry.retryDue(expectedBlacklist > 0 && !blacklistComplete)) CellNetwork.send(new Mode2ConfigPacket(11));
        if (rejectList.retryDue()) CellNetwork.send(new Mode2ConfigPacket(23));
        if (uiState == 1 && batchTab) tickBatchSearch();
        // Retry re-sends the SAME query without resetting the budget (never call start() here: that would make
        // the retry counter immortal, exactly like the panel/blacklist retries above).
        if (searchRetry.retryDue(expectedSearchChunks > 0 && !searchComplete)) {
            Mode2ConfigPacket retry = new Mode2ConfigPacket(25);
            retry.text = searchSent;
            retry.channelId = panelChannel;
            CellNetwork.send(retry);
        }
    }

    @Override protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        mc.getTextureManager().bindTexture(PANEL);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, 256);
        drawRect(guiLeft, guiTop + 256, guiLeft + xSize, guiTop + ySize, 0xFF202531);
        if (uiState == 1) {
            drawRect(guiLeft + PANEL_X, guiTop + PANEL_Y, guiLeft + PANEL_X + PANEL_W,
                    guiTop + PANEL_Y + 78, 0xFF111820);
            drawRect(guiLeft + 10, guiTop + 61, guiLeft + 246, guiTop + 71, 0xFF303644);
        }
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (rebuildPending) { rebuildPending = false; buildControls(); }
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        // 1.12.2 does NOT draw slot tooltips for a container screen: GuiContainer.drawScreen only fills
        // hoveredSlot (javap), and every vanilla container calls this itself at the end of its own drawScreen.
        // GuiContainer contains exactly ONE reference to renderToolTip, inside renderHoveredToolTip, so
        // overriding drawScreen without calling it means no slot has a tooltip at all -- which is what sensei
        // saw over the player inventory (BUG2). (My earlier probe only checked that the slot geometry matched,
        // not that this call site existed; that is why it wrongly looked fine.)
        renderHoveredToolTip(mouseX, mouseY);
        if (uiState == 0) {
            fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.select_title"), guiLeft + 10, guiTop + 4, 0xFFFFFF);
            return;
        }
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.title"), guiLeft + 10, guiTop + 4, 0xFFFFFF);
        drawChannelSelector();
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.tab_items"), guiLeft + TAB_ITEMS_X, guiTop + 36,
                !rejectTab && !batchTab ? 0xFFFFFF : 0x888888);
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.tab_reject"), guiLeft + TAB_REJECT_X, guiTop + 36,
                rejectTab ? 0xFFFFFF : 0x888888);
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.tab_batch"), guiLeft + TAB_BATCH_X, guiTop + 36,
                batchTab ? 0xFFFFFF : 0x888888);
        String hint = batchTab ? I18n.format("gui.ae2addon.mode2.hint_batch")
                : rejectTab ? I18n.format("gui.ae2addon.mode2.hint_reject")
                : I18n.format("gui.ae2addon.mode2.hint_bar");
        // A rule filter names itself here instead of stealing width from the category strip: its chip can be
        // scrolled out of view, and the strip needs every pixel to show as many categories as possible.
        if (!batchTab && !rejectTab && activeRule != null && !"item".equals(activeRule)) {
            hint = I18n.format("gui.ae2addon.mode2.filter_active", activeRule);
        }
        // 75% scale (sensei, 2026-10-10: "快捷键提示字体缩小点，都显示不完啦"): the bar carries four gestures
        // and at full size the last one was trimmed off. The trim width is divided by the scale, so the smaller
        // font really does fit more text into the same 235 screen pixels.
        drawScaledToWidth(hint, guiLeft + 10, guiTop + 61, 0.75F, 235, 0xBBBBBB);
        if (batchTab) {
            // The category strip is the storage tab's (ore/mod rules, blacklist). This tab borrows its space to
            // say what the last search found -- and, while a confirmation is armed, what it is about to touch.
            fontRenderer.drawString(fontRenderer.trimStringToWidth(batchStatusLine(), 235), guiLeft + 10,
                    guiTop + CATEGORY_Y, batchArmed != 0 ? 0xFF5555 : 0xBBBBBB);
        } else {
            for (Chip chip : buildChips()) {
                if (!chipVisible(chip)) {
                    continue; // clipped chips are reached with the arrows or the wheel
                }
                boolean active = chip.id.equals(activeRule == null ? "item" : activeRule);
                fontRenderer.drawString(chip.label, chip.x, guiTop + CATEGORY_Y, active ? 0xFFFFFF : chip.color);
            }
            // Arrow zones at both ends of the strip: the only sign that there are more categories than fit.
            if (canScrollCategoryLeft()) {
                fontRenderer.drawString("←", guiLeft + 10, guiTop + CATEGORY_Y, 0xFFFF77);
            }
            if (canScrollCategoryRight()) {
                fontRenderer.drawString("→", guiLeft + CATEGORY_RIGHT + 1, guiTop + CATEGORY_Y, 0xFFFF77);
            }
        }
        if (batchTab) {
            if (searchRetry.exhausted(expectedSearchChunks > 0 && !searchComplete)) fontRenderer.drawString(
                    I18n.format("gui.ae2addon.p3.retry_stopped", I18n.format("gui.ae2addon.mode2.tab_batch")),
                    guiLeft + 12, guiTop + 98, 0xFF5555);
            else if (searchComplete && filtered.isEmpty()) fontRenderer.drawString(
                    I18n.format("gui.ae2addon.mode2.no_match"), guiLeft + 12, guiTop + 100, 0xAAAAAA);
        }
        else if (panelRetry.exhausted(expectedChunks > 0 && !panelComplete)) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.retry_stopped", I18n.format("gui.ae2addon.p3.list_panel")),
                guiLeft + 12, guiTop + 98, 0xFF5555);
        else if (blacklistRetry.exhausted(expectedBlacklist > 0 && !blacklistComplete)) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.retry_stopped", I18n.format("gui.ae2addon.p3.cat_blacklist")),
                guiLeft + 12, guiTop + 98, 0xFF5555);
        else if (rejectList.exhausted()) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.retry_stopped", I18n.format("gui.ae2addon.mode2.tab_reject")),
                guiLeft + 12, guiTop + 98, 0xFF5555);
        else if (expectedChunks > 0 && !panelComplete) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.partial", pending.size(), expectedChunks),
                guiLeft + 12, guiTop + 98, 0xFFFF77);
        else if (rejectTab && rejectList.pending()) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.partial", rejectList.received(), rejectList.expected()),
                guiLeft + 12, guiTop + 98, 0xFFFF77);
        else if (expectedBlacklist > 0 && !blacklistComplete) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.partial", pendingBlacklist.size(), expectedBlacklist),
                guiLeft + 12, guiTop + 98, 0xFFFF77);
        else if (filtered.isEmpty()) fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.no_match"),
                guiLeft + 12, guiTop + 100, 0xAAAAAA);
        for (int i = 0; i < 4 && i + scrollOffset < filtered.size(); i++) {
            Mode2ConfigPacket.PanelEntry entry = filtered.get(i + scrollOffset);
            int y = guiTop + 89 + i * ROW_H;
            // The item channel has a real icon and a display name; every other channel only has the label the
            // server sent (a fluid / gas / essentia / mana / FE key is not an item).
            String name = entry.item.isEmpty() || !com.ae2addon.cell.CellChannelRegistry.ITEM.equals(entry.channelId)
                    ? (entry.label == null || entry.label.isEmpty() ? entry.channelId : entry.label)
                    : entry.item.getDisplayName();
            // In the 全部 view a row names its own channel too (sensei, 2026-10-10: "石头（物品）"): that list
            // mixes every channel, and without the suffix a fluid's name and an item's name are told apart only
            // by what the amount column happens to look like. The base name is trimmed FIRST so the suffix
            // survives; the channel word keeps its own colour inside the parentheses.
            String suffix = Mode2ConfigPacket.ALL_CHANNELS.equals(panelChannel)
                    ? "§7（" + channelDisplayName(entry.channelId) + "§7）" : "";
            if (fontRenderer.getStringWidth(name) + fontRenderer.getStringWidth(suffix) > 102) {
                name = fontRenderer.trimStringToWidth(name,
                        Math.max(20, 102 - fontRenderer.getStringWidth(suffix) - 6)) + "…";
            }
            fontRenderer.drawString(name + suffix, guiLeft + 13, y, 0xDDDDDD);
            String status = rejectTab ? (containsStack(rejectList.on(), entry.item)
                    ? I18n.format("gui.ae2addon.mode2.reject_mark") : I18n.format("gui.ae2addon.mode2.reject_allow_mark"))
                    : (entry.infinite ? infiniteLabel(entry) : shortAmount(entry.amount))
                    + (entry.quantityLocked ? " §eL" : "")
                    + (containsStack(blacklist, entry.item) ? " §cX" : "")
                    + (entry.whitelisted ? " §aW" : "");
            int statusX = guiLeft + 149;
            fontRenderer.drawString(status, statusX, y, 0xFFFFFF);
            // The per-item threshold lives in the ROW now, right after the amount (sensei, 2026-10-10:
            // "单物品阈值数量移动到表格中物品存储数量的右侧，不以 tooltip 形式呈现"). Only rows that carry an
            // override show it -- the global threshold applies everywhere else and would just be noise. Greyer
            // than the amount, brighter while that row's editor is open, and it may not run past the row's edge.
            if (entry.threshold > 0) {
                String thr = "§8T" + entry.threshold;
                int thrX = statusX + fontRenderer.getStringWidth(status) + 4;
                int thrWidth = fontRenderer.getStringWidth(thr);
                boolean open = !editing.isEmpty() && itemId(editing).equals(itemId(entry.item));
                fontRenderer.drawString(thr, Math.min(thrX, guiLeft + 246 - thrWidth), y, open ? 0xFFFF77 : 0x999999);
            }
        }
        if (workMode == 1) thresholdInput.drawTextBox();
        searchInput.drawTextBox();
        if (!editing.isEmpty()) itemThresholdInput.drawTextBox();
        else if (!quantityEditing.isEmpty()) quantityInput.drawTextBox();
        else if (!batchTab) ruleInput.drawTextBox();
        if (mouseX >= guiLeft + 10 && mouseX < guiLeft + 246 && mouseY >= guiTop + 88
                && mouseY < guiTop + 150) {
            int row = (mouseY - guiTop - 88) / ROW_H + scrollOffset;
            if (row >= 0 && row < filtered.size()) {
                Mode2ConfigPacket.PanelEntry entry = filtered.get(row);
                List<String> tooltip = new ArrayList<>(getItemToolTip(entry.item));
                // In the "全部" view rows of several channels sit side by side and only the item channel has an
                // icon, so the row has to say which channel it belongs to.
                if (Mode2ConfigPacket.ALL_CHANNELS.equals(panelChannel)) {
                    tooltip.add(I18n.format("gui.ae2addon.mode2.channel_of",
                            channelDisplayName(entry.channelId)));
                }
                tooltip.add(entry.infinite ? I18n.format("gui.ae2addon.mode2.infinite")
                        : I18n.format("gui.ae2addon.mode2.amount", entry.amount.toString()));
                if (entry.infinite) tooltip.add(I18n.format("gui.ae2addon.p3.bytes", entry.bytes));
                // The per-item threshold and the committed amount are drawn IN the row now (sensei, 2026-10-10),
                // so they are no longer repeated here. The exact (unrounded) amount stays, because the row
                // shows it in short form.
                if (entry.quantityLocked) tooltip.add(I18n.format("gui.ae2addon.mode2.qty_lock_on"));
                if (containsStack(blacklist, entry.item)) tooltip.add(I18n.format("gui.ae2addon.mode2.blacklisted"));
                if (!rejectTab && entry.whitelisted)
                    tooltip.add(I18n.format("gui.ae2addon.p3.remove_whitelist_tip"));
                if (rejectTab) tooltip.add(I18n.format("gui.ae2addon.mode2.reject_tip"));
                drawHoveringText(tooltip, mouseX, mouseY);
            }
        }
        if (!batchTab && mouseX >= guiLeft + 205 && mouseX < guiLeft + 246
                && mouseY >= guiTop + 170 && mouseY < guiTop + 186)
            drawHoveringText(I18n.format("gui.ae2addon.p3.rule_all_tip"), mouseX, mouseY);
        // The arrow zones need to explain themselves: there is no other sign that more categories exist.
        int categoryRelY = mouseY - guiTop;
        if (!batchTab && categoryRelY >= CATEGORY_HIT_TOP && categoryRelY < CATEGORY_HIT_BOTTOM) {
            int categoryRelX = mouseX - guiLeft;
            boolean onLeftArrow = categoryRelX >= 10 && categoryRelX < CATEGORY_LEFT && canScrollCategoryLeft();
            boolean onRightArrow = categoryRelX >= CATEGORY_RIGHT && categoryRelX < 246
                    && canScrollCategoryRight();
            if (onLeftArrow || onRightArrow) {
                drawHoveringText(java.util.Collections.singletonList(
                        I18n.format("gui.ae2addon.mode2.cat_scroll_tip")), mouseX, mouseY);
            } else {
                // A truncated chip loses its tail, so hovering shows the full rule name it stands for.
                for (Chip chip : buildChips()) {
                    if (chipVisible(chip) && chip.contains(mouseX)) {
                        drawHoveringText(java.util.Collections.singletonList(chip.full), mouseX, mouseY);
                        break;
                    }
                }
            }
        }
    }
    /**
     * The channel selector in the title row: {@code ‹ 流体 ›}.
     *
     * <p>The panel serves every channel the cell speaks, while the settings (mode / threshold / rules) stay
     * shared, so this only changes which storage rows are listed.
     */
    private void drawChannelSelector() {
        if (selectableChannels().size() < 2) {
            return;
        }
        String name = channelDisplayName(panelChannel);
        int width = fontRenderer.getStringWidth(name);
        int nameX = guiLeft + 246 - 12 - width;
        fontRenderer.drawString("§7‹", nameX - 8, guiTop + 4, 0xFFFFFF);
        fontRenderer.drawString(name, nameX, guiTop + 4, 0xFFFFFF);
        fontRenderer.drawString("§7›", guiLeft + 246 - 6, guiTop + 4, 0xFFFFFF);
    }

    /**
     * What the selector cycles through: "全部" first (every channel in one list), then each channel the cell
     * speaks (sensei asked for the All entry on 2026-10-04).
     */
    private static java.util.List<String> selectableChannels() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        ids.add(Mode2ConfigPacket.ALL_CHANNELS);
        for (com.ae2addon.cell.CellChannel<?> channel : com.ae2addon.cell.CellChannelRegistry.all()) {
            ids.add(channel.id());
        }
        return ids;
    }

    /** Cycles the panel's channel by {@code step} through "全部" and the registered channels. */
    private void cyclePanelChannel(int step) {
        java.util.List<String> ids = selectableChannels();
        if (ids.size() < 2) {
            return;
        }
        int index = ids.indexOf(panelChannel);
        if (index < 0) {
            index = 0;
        }
        index = (index + step % ids.size() + ids.size()) % ids.size();
        panelChannel = ids.get(index);
        scrollOffset = 0;
        // Drop the previous channel's rows immediately: the new set arrives as fresh chunks, and leaving the
        // old rows on screen under the new label looks exactly like "the switch did nothing".
        all.clear();
        pending.clear();
        pendingChannel = "";
        expectedChunks = 0;
        panelComplete = false;
        applyFilters();
        CellNetwork.send(Mode2ConfigPacket.panelChannel(panelChannel));
        if (batchTab) {
            // A registry search is per channel, so the new channel's rows have to be fetched again.
            searchResults.clear();
            applyFilters();
            requestSearch();
        }
    }

    /** Display name for a channel id (the panel is Chinese-facing; the id is the fallback). */
    private static String channelDisplayName(String channelId) {
        if (Mode2ConfigPacket.ALL_CHANNELS.equals(channelId)) {
            return "§d全部";
        }
        switch (channelId) {
            case "item": return "§b物品";
            case "fluid": return "§b流体";
            case "gas": return "§b气体";
            case "essentia": return "§b源质";
            case "mana": return "§b法力";
            case "flux": return "§b能量";
            default: return "§b" + channelId;
        }
    }

    /** Tags a panel action with the key it is about, so it works for every channel, not just items. */
    private Mode2ConfigPacket withChannel(Mode2ConfigPacket packet, Mode2ConfigPacket.PanelEntry entry) {
        packet.channelId = entry == null ? panelChannel : entry.channelId;
        packet.keyNbt = entry == null ? null : entry.keyNbt;
        return packet;
    }

    private Mode2ConfigPacket withChannel(Mode2ConfigPacket packet) {
        packet.channelId = panelChannel;
        return packet;
    }

    private static String shortAmount(BigInteger amount) {
        // Past Long.MAX a digit run stops being readable at a glance (2^128 is 39 digits), so only that range
        // switches to scientific notation. The exact value still lives in the tooltip and in the quantity box.
        if (amount.bitLength() > 63) {
            String digits = amount.toString();
            return digits.charAt(0) + "." + digits.substring(1, 3) + "e" + (digits.length() - 1);
        }
        if (amount.compareTo(BigInteger.valueOf(1000000)) >= 0) return amount.divide(BigInteger.valueOf(1000000)) + "M";
        if (amount.compareTo(BigInteger.valueOf(1000)) >= 0) return amount.divide(BigInteger.valueOf(1000)) + "K";
        return amount.toString();
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (uiState == 1) {
            int x = mouseX - guiLeft, y = mouseY - guiTop;
            if (mouseButton == 0 && isShiftKeyDown()) {
                for (Slot slot : menu.inventorySlots) {
                    if (x >= slot.xPos && x < slot.xPos + 16 && y >= slot.yPos && y < slot.yPos + 16) {
                        if (!slot.getStack().isEmpty()) CellNetwork.send(withChannel(new Mode2ConfigPacket(1, slot.getStack().copy())));
                        return;
                    }
                }
            }
            // Channel selector: the two arrows sit at the right end of the title row.
            if (uiState == 1 && mouseButton == 0 && y >= 0 && y < 14 && x >= 190 && x < 246) {
                cyclePanelChannel(x >= 236 ? 1 : -1);
                return;
            }
            if (mouseButton == 0 && y >= 36 && y < 47 && x >= TAB_ITEMS_X && x < 246) {
                setTab(x < TAB_REJECT_X ? 0 : (x < TAB_BATCH_X ? 1 : 2));
                return;
            }
            if (!batchTab && y >= CATEGORY_HIT_TOP && y < CATEGORY_HIT_BOTTOM) {
                // Arrow gaps first: they are drawn exactly when something is scrolled out of the strip.
                if (x >= 10 && x < CATEGORY_LEFT && canScrollCategoryLeft()) {
                    categoryScroll = Math.max(0, categoryScroll - CATEGORY_STEP * 3);
                    return;
                }
                if (x >= CATEGORY_RIGHT && x < 246 && canScrollCategoryRight()) {
                    categoryScroll = Math.min(maxCategoryScroll(), categoryScroll + CATEGORY_STEP * 3);
                    return;
                }
                for (Chip chip : buildChips()) {
                    if (!chipVisible(chip) || !chip.contains(mouseX)) {
                        continue;
                    }
                    if (chip.id.startsWith("ore:") || chip.id.startsWith("mod:")) {
                        boolean ore = chip.id.startsWith("ore:");
                        String name = chip.id.substring(4);
                        int mode = (ore ? oreRules : modRules).getOrDefault(name, 1);
                        if (mouseButton == 0 && isShiftKeyDown()) {
                            CellNetwork.send(Mode2ConfigPacket.rule(20, ore, name, 1));       // shift+左键删规则
                        } else if (mouseButton == 1) {
                            CellNetwork.send(Mode2ConfigPacket.rule(15, ore, name, mode == 1 ? 2 : 1));
                        } else {
                            activeRule = chip.id;
                            applyFilters();
                        }
                    } else {
                        activeRule = chip.id;   // 物品 / 黑名单
                        applyFilters();
                    }
                    return;
                }
                // Empty strip space clears the filter -- the affordance the old left-hand label used to give.
                activeRule = null;
                applyFilters();
                return;
            }
            if (x >= 10 && x < 246 && y >= 88 && y < 150 && !(rejectTab && rejectList.pending())) {
                int row = (y - 88) / ROW_H + scrollOffset;
                if (row >= 0 && row < filtered.size()) {
                    Mode2ConfigPacket.PanelEntry entry = filtered.get(row);
                    if (batchTab) {
                        // One key at a time: the SAME action the storage tab's Shift+left-click sends
                        // (type 5 = toggle this key's infinity). Plain left click on purpose -- a search result
                        // row has no icon-click conventions to honour, and this tab's bulk action is the
                        // button's job, not a row's.
                        //
                        // NOTE for the record: the task said "reuse the type 7 path". In THIS codebase type 7 is
                        // the reject toggle (see the server handler and ModeSelectScreen), while toggling a
                        // key's infinity is type 5 -- so type 5 is what "same as the item page" actually means.
                        //
                        // Item rows only, and that is not a preference: types 1/5/7/11/13/17/22 carry an
                        // ItemStack and NOTHING else (see toBytes -- withChannel()'s channelId/keyNbt are simply
                        // not written), so a fluid/gas/essentia key cannot be named on that wire. The server
                        // resolves what arrives as the row's channel's key type, and a foreign key throws
                        // (FluidCellChannel's normalise bridge does `checkcast IAEFluidStack`). The bulk button
                        // works on every channel because type 27 carries the channel id and the server names
                        // the keys itself.
                        // Any channel: the packet now carries the key's own channel NBT (2026-10-10), so the
                        // server never has to guess the key from the row's icon. This used to be gated on the
                        // item channel because type 5 dropped that information on the wire.
                        if (mouseButton == 0 && !isShiftKeyDown() && !ctrlDownAny()) {
                            batchArmed = 0;
                            applyTabVisibility();
                            updateBatchButtons();
                            CellNetwork.send(withChannel(new Mode2ConfigPacket(5, entry.item.copy()), entry));
                        }
                        return;
                    }
                    // ===== 2026-10-10 根因修复：这里原来是**没有花括号**的 else-if 链 =====
                    // 链尾那一枝写成
                    //     else if (右键阈值条件)
                    //         editingKeyNbt = entry.keyNbt;
                    //     showItemThreshold(entry.item.copy(), entry.threshold);   // <-- 缩进骗人
                    //     return;
                    // Java 里没有花括号时只有第一句属于 if，所以 showItemThreshold 与 return 其实是
                    // **无条件执行**的后续语句：任何一次行点击（普通左键、Ctrl+左键、Shift+左键、拒收页左键…）
                    // 走完自己的分支后都会掉进 showItemThreshold，而它第一句就是 quantityEditing = EMPTY，
                    // 把刚打开的数量编辑框当场抹掉 —— 这就是「普通左键=单物品阈值」「Ctrl+左键还是阈值」
                    // 「保存数量/锁定按钮一闪就没」四个现象的同一个根因。
                    // 修法：每一枝都补上花括号并各自 return，链尾留一个吞掉剩余点击的 return。
                    if (rejectTab && mouseButton == 0 && !isShiftKeyDown() && !ctrlDownAny()) {
                        CellNetwork.send(withChannel(new Mode2ConfigPacket(7, entry.item.copy()), entry));
                        return;
                    }
                    if (rejectTab && mouseButton == 1) {
                        CellNetwork.send(withChannel(new Mode2ConfigPacket(22, entry.item.copy()), entry));
                        return;
                    }
                    if (!rejectTab && mouseButton == 1 && isShiftKeyDown()) {
                        CellNetwork.send(withChannel(new Mode2ConfigPacket(11, entry.item.copy()), entry));
                        return;
                    }
                    if (!rejectTab && mouseButton == 1 && ctrlDownAny() && entry.whitelisted
                            && entry.item.getItem().getRegistryName() != null) {
                        Mode2ConfigPacket remove = new Mode2ConfigPacket(2);
                        remove.text = entry.item.getItem().getRegistryName().toString();
                        CellNetwork.send(remove);
                        return;
                    }
                    if (!rejectTab && mouseButton == 0 && ctrlDownAny()) {
                        hideItemThreshold();
                        quantityEditing = entry.item.copy(); quantityLock = entry.quantityLocked;
                        quantityKeyNbt = entry.keyNbt;
                        quantityChannelId = entry.channelId;
                        quantityInput.setText(entry.amount.toString()); quantityInput.setFocused(true);
                        updateEditingButtons();
                        for (GuiButton button : buttonList) if (button.id == 15)
                            button.displayString = I18n.format(quantityLock ? "gui.ae2addon.p3.lock"
                                    : "gui.ae2addon.p3.unlock");
                        return;
                    }
                    if (!rejectTab && mouseButton == 0 && isShiftKeyDown()) {
                        CellNetwork.send(withChannel(new Mode2ConfigPacket(5, entry.item.copy()), entry));
                        return;
                    }
                    if (!rejectTab && mouseButton == 1 && !isShiftKeyDown()) {
                        // 右键单物品阈值（对照实验已结束，恢复启用）
                        editingKeyNbt = entry.keyNbt;
                        editingChannelId = entry.channelId;
                        showItemThreshold(entry.item.copy(), entry.threshold);
                        return;
                    }
                    // 其余点击（例如普通左键）故意什么都不做：防误触，原设计如此。
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (uiState == 1) {
            if (workMode == 1) thresholdInput.mouseClicked(mouseX, mouseY, mouseButton);
            searchInput.mouseClicked(mouseX, mouseY, mouseButton);
            if (!editing.isEmpty()) itemThresholdInput.mouseClicked(mouseX, mouseY, mouseButton);
            else if (!quantityEditing.isEmpty()) quantityInput.mouseClicked(mouseX, mouseY, mouseButton);
            else ruleInput.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (uiState == 1) {
            if (!editing.isEmpty()) {
                if (keyCode == 28 || keyCode == 156) { saveItemThreshold(); return; }
                if (keyCode == 1) { hideItemThreshold(); return; }
                if (itemThresholdInput.textboxKeyTyped(typedChar, keyCode)) return;
            }
            if (!quantityEditing.isEmpty()) {
                if (keyCode == 28 || keyCode == 156) { saveQuantity(); return; }
                if (keyCode == 1) { quantityEditing = ItemStack.EMPTY; updateEditingButtons(); return; }
                if (quantityInput.textboxKeyTyped(typedChar, keyCode)) return;
            } else if (ruleInput.textboxKeyTyped(typedChar, keyCode)) return;
            if (workMode == 1 && thresholdInput.textboxKeyTyped(typedChar, keyCode)) return;
            if (searchInput.textboxKeyTyped(typedChar, keyCode)) return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (uiState != 1) return;
        // ⚠ 必须用 getEventDWheel()（单个鼠标事件的值），**不能用 getDWheel()**（2026-10-11 修）：
        //   LWJGL2 里 `getDWheel()` 读的是**按帧累加**的 dwheel 字段，而且**读一次就清零**
        //   （字节码：`int r = dwheel; dwheel = 0; return r;`），而本方法在 MC 的事件循环里
        //   **每个鼠标事件都会被调用一次** ⇒ 一帧内第一个事件把整帧滚动量一次读走（只滚 1 行），
        //   其余事件读到 0。表现就是"滚轮滚好几下列表才动一下"，且**整合包越大 FPS 越低越明显**
        //   （游戏本身不卡，纯粹是滚动量被吞）。AE2 自己用的也是 getEventDWheel()。
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        // Same cursor maths vanilla uses, so "over the category strip" means what the player sees. Chips now
        // scroll with a plain wheel (Shift+wheel still works too) and the strip draws arrows whenever there is
        // more to the left or right -- sensei could not tell that more categories existed.
        int mouseX = Mouse.getX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getY() * this.height / this.mc.displayHeight - 1;
        boolean overStrip = mouseY >= guiTop + CATEGORY_HIT_TOP && mouseY < guiTop + CATEGORY_HIT_BOTTOM;
        if (overStrip || isShiftKeyDown()) {
            categoryScroll = Math.max(0, Math.min(maxCategoryScroll(),
                    categoryScroll + (wheel < 0 ? CATEGORY_STEP : -CATEGORY_STEP)));
        } else {
            scrollOffset = Math.max(0, Math.min(Math.max(0, filtered.size() - 4),
                    scrollOffset + (wheel < 0 ? 1 : -1)));
        }
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    // ------------------------------------------------------------------
    // JEI ghost-ingredient support (see com.ae2addon.integration.jei)
    // ------------------------------------------------------------------

    /**
     * Screen-space rectangle that accepts a dragged ingredient, or {@code null} when this page takes no
     * drops. Kept in sync with the row hit area in mouseClicked (x 10..246, y 88..150).
     */
    public java.awt.Rectangle ghostArea() {
        if (uiState != 1) {
            return null; // the work-mode chooser takes no drops
        }
        if (rejectTab && rejectList.pending()) {
            return null; // the explicit reject list is only half received; a drop now would mean nothing
        }
        return new java.awt.Rectangle(guiLeft + 10, guiTop + 88, 236, 62);
    }

    /**
     * Applies an ingredient dragged in from JEI, with the same semantics as clicking a row in the matching
     * tab: the reject page toggles rejection (type 7), the item page adds to the whitelist (type 1).
     *
     * @return whether anything was sent.
     */
    public boolean applyGhostIngredient(net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty() || uiState != 1) {
            return false;
        }
        if (rejectTab) {
            if (rejectList.pending()) {
                return false;
            }
            CellNetwork.send(new Mode2ConfigPacket(7, stack.copy()));
        } else {
            CellNetwork.send(new Mode2ConfigPacket(1, stack.copy()));
        }
        return true;
    }
}
