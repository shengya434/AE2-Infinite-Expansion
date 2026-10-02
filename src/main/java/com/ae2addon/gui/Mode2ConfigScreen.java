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
    private boolean ruleInstant;
    private int uiState; // 0: choose work mode, 1: editor
    private int workMode = 1;
    private long currentThreshold = UnlimitedCellInventory.DEFAULT_THRESHOLD;
    private boolean rejectTab;
    private boolean rejectDefault;
    private int rejectOnCount, rejectOffCount;
    private int scrollOffset;
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
        updateDefaultButton();
        updateEditingButtons();
    }

    private void updateDefaultButton() {
        if (defaultButton != null) defaultButton.displayString = (rejectDefault ? "[x] " : "[ ] ")
                + I18n.format("gui.ae2addon.mode2.reject_default");
    }

    private void updateEditingButtons() {
        if (buttonList.size() > 5 && uiState == 1) {
            buttonList.get(4).visible = !editing.isEmpty();
            buttonList.get(5).visible = !editing.isEmpty();
            buttonList.get(8).visible = !quantityEditing.isEmpty();
            buttonList.get(9).visible = !quantityEditing.isEmpty();
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
        }
    }

    private void refresh() {
        if (panelComplete) { pending.clear(); expectedChunks = 0; panelComplete = false; }
        if (blacklistComplete) { pendingBlacklist.clear(); expectedBlacklist = 0; blacklistComplete = false; }
        panelRetry.manualRefresh(); blacklistRetry.manualRefresh(); rejectList.manualRefresh();
        CellNetwork.send(new Mode2ConfigPacket(3));
    }

    private void saveThreshold() {
        try {
            long value = ThresholdExpression.parse(thresholdInput.getText());
            if (value < 1) throw new NumberFormatException();
            CellNetwork.send(new Mode2ConfigPacket(0, value));
            currentThreshold = value;
        } catch (NumberFormatException ex) { thresholdInput.setText(Long.toString(currentThreshold)); }
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
            CellNetwork.send(Mode2ConfigPacket.itemThreshold(editing.copy(), value));
            hideItemThreshold();
        } catch (NumberFormatException ex) { itemThresholdInput.setText(""); }
    }
    private void saveQuantity() {
        if (quantityEditing.isEmpty()) return;
        CellNetwork.send(Mode2ConfigPacket.quantity(quantityEditing.copy(), quantityInput.getText(), quantityLock));
        quantityEditing = ItemStack.EMPTY;
        quantityInput.setFocused(false);
        updateEditingButtons();
    }

    public void accept(Mode2ConfigPacket packet) {
        if (packet.type == 18 && packet.state != null) {
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
            if (expectedChunks == 0 || expectedChunks != packet.totalChunks) {
                pending.clear(); expectedChunks = packet.totalChunks;
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
        if (rejectTab) {
            source.clear();
            if (!rejectList.pending()) for (ItemStack item : rejectItems)
                source.add(new Mode2ConfigPacket.PanelEntry(item, BigInteger.ZERO, false, 0L, 0L, false, false));
        } else if ("blacklist".equals(activeRule)) {
            for (ItemStack item : blacklist) {
                boolean present = false;
                for (Mode2ConfigPacket.PanelEntry entry : source) if (sameKey(entry.item, item)) { present = true; break; }
                if (!present) source.add(new Mode2ConfigPacket.PanelEntry(item, BigInteger.ZERO, false, 0L, 0L, false, false));
            }
        }
        for (Mode2ConfigPacket.PanelEntry entry : source) {
            ItemStack stack = entry.item;
            if (!rejectTab && "blacklist".equals(activeRule) && !containsStack(blacklist, stack)) continue;
            if (!rejectTab && activeRule != null && !"blacklist".equals(activeRule)
                    && !"item".equals(activeRule) && !matchesRule(stack, activeRule)) continue;
            String id = stack.getItem().getRegistryName() == null ? ""
                    : stack.getItem().getRegistryName().toString();
            if (PinyinIndex.matches(query, stack.getDisplayName(), id)) filtered.add(entry);
        }
        scrollOffset = Math.min(scrollOffset, Math.max(0, filtered.size() - 4));
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
    private int maxCategoryScroll() {
        int width = fontRenderer.getStringWidth(I18n.format("gui.ae2addon.p3.cat_items")) + 8;
        for (String name : oreRules.keySet()) width += fontRenderer.getStringWidth("O:" + name + " [I]") + 8;
        for (String name : modRules.keySet()) width += fontRenderer.getStringWidth("M:" + name + " [I]") + 8;
        width += fontRenderer.getStringWidth(I18n.format("gui.ae2addon.p3.cat_blacklist")) + 8;
        return Math.max(0, width - 176);
    }

    @Override public void updateScreen() {
        super.updateScreen();
        if (rebuildPending) { rebuildPending = false; buildControls(); }
        if (uiState == 1 && !lastSearch.equals(searchInput.getText())) applyFilters();
        if (panelRetry.retryDue(expectedChunks > 0 && !panelComplete)) CellNetwork.send(new Mode2ConfigPacket(3));
        if (blacklistRetry.retryDue(expectedBlacklist > 0 && !blacklistComplete)) CellNetwork.send(new Mode2ConfigPacket(11));
        if (rejectList.retryDue()) CellNetwork.send(new Mode2ConfigPacket(23));
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
        if (uiState == 0) {
            fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.select_title"), guiLeft + 10, guiTop + 4, 0xFFFFFF);
            return;
        }
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.title"), guiLeft + 10, guiTop + 4, 0xFFFFFF);
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.tab_items"), guiLeft + 144, guiTop + 36,
                rejectTab ? 0x888888 : 0xFFFFFF);
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.tab_reject"), guiLeft + 193, guiTop + 36,
                rejectTab ? 0xFFFFFF : 0x888888);
        String hint = rejectTab ? I18n.format("gui.ae2addon.mode2.hint_reject")
                : I18n.format("gui.ae2addon.mode2.hint_bar");
        fontRenderer.drawString(fontRenderer.trimStringToWidth(hint, 235), guiLeft + 10, guiTop + 61, 0xBBBBBB);
        fontRenderer.drawString(fontRenderer.trimStringToWidth(activeRule == null
                ? I18n.format("gui.ae2addon.mode2.tab_all") : "[" + activeRule + "]", 54),
                guiLeft + 12, guiTop + 75, 0xFFFF99);
        int chipX = guiLeft + 70 - categoryScroll;
        String itemChip = I18n.format("gui.ae2addon.p3.cat_items");
        int itemChipWidth = fontRenderer.getStringWidth(itemChip) + 8;
        if (chipX >= guiLeft + 70 && chipX + itemChipWidth <= guiLeft + 246)
            fontRenderer.drawString(itemChip, chipX, guiTop + 75, 0xCCCCCC);
        chipX += itemChipWidth;
        for (Map.Entry<String, Integer> rule : oreRules.entrySet()) {
            String label = "O:" + rule.getKey() + (rule.getValue() == 1 ? " [I]" : " [T]");
            int width = fontRenderer.getStringWidth(label) + 8;
            if (chipX >= guiLeft + 70 && chipX + width <= guiLeft + 246)
                fontRenderer.drawString(label, chipX, guiTop + 75, 0x66CCFF);
            chipX += width;
        }
        for (Map.Entry<String, Integer> rule : modRules.entrySet()) {
            String label = "M:" + rule.getKey() + (rule.getValue() == 1 ? " [I]" : " [T]");
            int width = fontRenderer.getStringWidth(label) + 8;
            if (chipX >= guiLeft + 70 && chipX + width <= guiLeft + 246)
                fontRenderer.drawString(label, chipX, guiTop + 75, 0xDD99FF);
            chipX += width;
        }
        String blackChip = I18n.format("gui.ae2addon.p3.cat_blacklist");
        if (chipX >= guiLeft + 70 && chipX + fontRenderer.getStringWidth(blackChip) <= guiLeft + 246)
            fontRenderer.drawString(blackChip, chipX, guiTop + 75, 0xFF7777);
        if (panelRetry.exhausted(expectedChunks > 0 && !panelComplete)) fontRenderer.drawString(
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
            String name = entry.item.getDisplayName();
            if (fontRenderer.getStringWidth(name) > 102) name = fontRenderer.trimStringToWidth(name, 99) + "…";
            fontRenderer.drawString(name, guiLeft + 13, y, 0xDDDDDD);
            String status = rejectTab ? (containsStack(rejectList.on(), entry.item)
                    ? I18n.format("gui.ae2addon.mode2.reject_mark") : I18n.format("gui.ae2addon.mode2.reject_allow_mark"))
                    : (entry.infinite ? "§b∞" : shortAmount(entry.amount))
                    + (entry.quantityLocked ? " §eL" : "")
                    + (containsStack(blacklist, entry.item) ? " §cX" : "")
                    + (entry.whitelisted ? " §aW" : "");
            fontRenderer.drawString(status, guiLeft + 149, y, 0xFFFFFF);
        }
        if (workMode == 1) thresholdInput.drawTextBox();
        searchInput.drawTextBox();
        if (!editing.isEmpty()) itemThresholdInput.drawTextBox();
        else if (!quantityEditing.isEmpty()) quantityInput.drawTextBox();
        else ruleInput.drawTextBox();
        if (mouseX >= guiLeft + 10 && mouseX < guiLeft + 246 && mouseY >= guiTop + 88
                && mouseY < guiTop + 150) {
            int row = (mouseY - guiTop - 88) / ROW_H + scrollOffset;
            if (row >= 0 && row < filtered.size()) {
                Mode2ConfigPacket.PanelEntry entry = filtered.get(row);
                List<String> tooltip = new ArrayList<>(getItemToolTip(entry.item));
                tooltip.add(entry.infinite ? I18n.format("gui.ae2addon.mode2.infinite")
                        : I18n.format("gui.ae2addon.mode2.amount", entry.amount.toString()));
                if (entry.infinite) tooltip.add(I18n.format("gui.ae2addon.p3.bytes", entry.bytes));
                tooltip.add(I18n.format("gui.ae2addon.mode2.item_thr_title") + ": " + entry.threshold);
                if (entry.quantityLocked) tooltip.add(I18n.format("gui.ae2addon.mode2.qty_lock_on"));
                if (containsStack(blacklist, entry.item)) tooltip.add(I18n.format("gui.ae2addon.mode2.blacklisted"));
                if (!rejectTab && entry.whitelisted)
                    tooltip.add(I18n.format("gui.ae2addon.p3.remove_whitelist_tip"));
                if (entry.infinite && committedAmounts.containsKey(itemId(entry.item)))
                    tooltip.add(I18n.format("gui.ae2addon.p3.committed", committedAmounts.get(itemId(entry.item))));
                if (rejectTab) tooltip.add(I18n.format("gui.ae2addon.mode2.reject_tip"));
                drawHoveringText(tooltip, mouseX, mouseY);
            }
        }
        if (mouseX >= guiLeft + 205 && mouseX < guiLeft + 246
                && mouseY >= guiTop + 170 && mouseY < guiTop + 186)
            drawHoveringText(I18n.format("gui.ae2addon.p3.rule_all_tip"), mouseX, mouseY);
    }

    private static String shortAmount(BigInteger amount) {
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
                        if (!slot.getStack().isEmpty()) CellNetwork.send(new Mode2ConfigPacket(1, slot.getStack().copy()));
                        return;
                    }
                }
            }
            if (mouseButton == 0 && y >= 36 && y < 47 && x >= 144 && x < 246) {
                rejectTab = x >= 193; scrollOffset = 0; applyFilters(); return;
            }
            if (y >= 74 && y < 86 && x >= 70 && x < 246) {
                int chipX = 70 - categoryScroll;
                int itemChipWidth = fontRenderer.getStringWidth(I18n.format("gui.ae2addon.p3.cat_items")) + 8;
                if (x >= chipX && x < chipX + itemChipWidth) {
                    activeRule = "item"; applyFilters(); return;
                }
                chipX += itemChipWidth;
                for (Map.Entry<String, Integer> rule : oreRules.entrySet()) {
                    int width = fontRenderer.getStringWidth("O:" + rule.getKey() + " [I]") + 8;
                    if (x >= chipX && x < chipX + width) {
                        if (isShiftKeyDown() && mouseButton == 0)
                            CellNetwork.send(Mode2ConfigPacket.rule(20, true, rule.getKey(), 1));
                        else if (mouseButton == 1)
                            CellNetwork.send(Mode2ConfigPacket.rule(15, true, rule.getKey(), rule.getValue() == 1 ? 2 : 1));
                        else { activeRule = "ore:" + rule.getKey(); applyFilters(); }
                        return;
                    }
                    chipX += width;
                }
                for (Map.Entry<String, Integer> rule : modRules.entrySet()) {
                    int width = fontRenderer.getStringWidth("M:" + rule.getKey() + " [I]") + 8;
                    if (x >= chipX && x < chipX + width) {
                        if (isShiftKeyDown() && mouseButton == 0)
                            CellNetwork.send(Mode2ConfigPacket.rule(20, false, rule.getKey(), 1));
                        else if (mouseButton == 1)
                            CellNetwork.send(Mode2ConfigPacket.rule(15, false, rule.getKey(), rule.getValue() == 1 ? 2 : 1));
                        else { activeRule = "mod:" + rule.getKey(); applyFilters(); }
                        return;
                    }
                    chipX += width;
                }
                int blackWidth = fontRenderer.getStringWidth(I18n.format("gui.ae2addon.p3.cat_blacklist")) + 8;
                if (x >= chipX && x < chipX + blackWidth) {
                    activeRule = "blacklist"; applyFilters(); return;
                }
            }
            if (y >= 74 && y < 86 && x >= 10 && x < 70 && mouseButton == 0) {
                activeRule = null; applyFilters(); return;
            }
            if (x >= 10 && x < 246 && y >= 88 && y < 150 && !(rejectTab && rejectList.pending())) {
                int row = (y - 88) / ROW_H + scrollOffset;
                if (row >= 0 && row < filtered.size()) {
                    Mode2ConfigPacket.PanelEntry entry = filtered.get(row);
                    if (rejectTab && mouseButton == 0 && !isShiftKeyDown() && !isCtrlKeyDown())
                        CellNetwork.send(new Mode2ConfigPacket(7, entry.item.copy()));
                    else if (rejectTab && mouseButton == 1)
                        CellNetwork.send(new Mode2ConfigPacket(22, entry.item.copy()));
                    else if (!rejectTab && mouseButton == 1 && isShiftKeyDown())
                        CellNetwork.send(new Mode2ConfigPacket(11, entry.item.copy()));
                    else if (!rejectTab && mouseButton == 1 && isCtrlKeyDown() && entry.whitelisted
                            && entry.item.getItem().getRegistryName() != null) {
                        Mode2ConfigPacket remove = new Mode2ConfigPacket(2);
                        remove.text = entry.item.getItem().getRegistryName().toString();
                        CellNetwork.send(remove);
                    }
                    else if (!rejectTab && mouseButton == 0 && isCtrlKeyDown()) {
                        hideItemThreshold();
                        quantityEditing = entry.item.copy(); quantityLock = entry.quantityLocked;
                        quantityInput.setText(entry.amount.toString()); quantityInput.setFocused(true);
                        updateEditingButtons();
                        for (GuiButton button : buttonList) if (button.id == 15)
                            button.displayString = I18n.format(quantityLock ? "gui.ae2addon.p3.lock"
                                    : "gui.ae2addon.p3.unlock");
                    }
                    else if (!rejectTab && mouseButton == 0 && isShiftKeyDown())
                        CellNetwork.send(new Mode2ConfigPacket(5, entry.item.copy()));
                    else if (!rejectTab && mouseButton == 1 && !isShiftKeyDown())
                        showItemThreshold(entry.item.copy(), entry.threshold);
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
        int wheel = Mouse.getDWheel();
        if (wheel != 0) {
            if (isShiftKeyDown()) categoryScroll = Math.max(0,
                    Math.min(maxCategoryScroll(), categoryScroll + (wheel < 0 ? 24 : -24)));
            else scrollOffset = Math.max(0, Math.min(Math.max(0, filtered.size() - 4),
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
