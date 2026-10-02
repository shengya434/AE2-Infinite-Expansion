package com.ae2addon.gui;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private int expectedChunks;
    private long pendingSince;
    private int uiState; // 0: choose work mode, 1: editor
    private int workMode = 1;
    private long currentThreshold = UnlimitedCellInventory.DEFAULT_THRESHOLD;
    private boolean rejectTab;
    private boolean rejectDefault;
    private int rejectOnCount, rejectOffCount;
    private int scrollOffset;
    private GuiTextField thresholdInput, searchInput, itemThresholdInput;
    private ItemStack editing = ItemStack.EMPTY;
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

        // NOTE (2026-10-02, spec-side fix): create these three text fields for BOTH uiState values.
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
        defaultButton = new GuiButton(11, x + 10, y + 172, 150, 16, "");
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
        }
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
        }
    }

    private void refresh() { CellNetwork.send(new Mode2ConfigPacket(3)); }

    private void saveThreshold() {
        try {
            long value = ThresholdExpression.parse(thresholdInput.getText());
            if (value < 1) throw new NumberFormatException();
            CellNetwork.send(new Mode2ConfigPacket(0, value));
            currentThreshold = value;
        } catch (NumberFormatException ex) { thresholdInput.setText(Long.toString(currentThreshold)); }
    }

    private void showItemThreshold(ItemStack item, long threshold) {
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

    public void accept(Mode2ConfigPacket packet) {
        if (packet.type == 18 && packet.state != null) {
            rejectDefault = packet.state.getBoolean("default");
            rejectOnCount = packet.state.getInteger("onCount");
            rejectOffCount = packet.state.getInteger("offCount");
            updateDefaultButton();
        } else if (packet.type == 4) {
            if (packet.chunkIndex == 0) {
                pending.clear(); expectedChunks = packet.totalChunks;
                pendingSince = System.currentTimeMillis();
            }
            if (expectedChunks != packet.totalChunks || packet.chunkIndex < 0
                    || packet.chunkIndex >= expectedChunks) return;
            pending.put(packet.chunkIndex, packet.entries);
            if (pending.size() == expectedChunks) {
                all.clear();
                for (int i = 0; i < expectedChunks; i++) all.addAll(pending.get(i));
                pending.clear(); expectedChunks = 0;
                applyFilters();
            }
        }
    }

    private void applyFilters() {
        filtered.clear();
        String query = searchInput == null ? lastSearch : searchInput.getText();
        lastSearch = query;
        for (Mode2ConfigPacket.PanelEntry entry : all) {
            ItemStack stack = entry.item;
            String id = stack.getItem().getRegistryName() == null ? ""
                    : stack.getItem().getRegistryName().toString();
            if (PinyinIndex.matches(query, stack.getDisplayName(), id)) filtered.add(entry);
        }
        scrollOffset = Math.min(scrollOffset, Math.max(0, filtered.size() - 4));
    }

    @Override public void updateScreen() {
        super.updateScreen();
        if (rebuildPending) { rebuildPending = false; buildControls(); }
        if (uiState == 1 && searchInput != null && !lastSearch.equals(searchInput.getText())) applyFilters();
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
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.tab_all") + "  (" + filtered.size() + ")",
                guiLeft + 12, guiTop + 75, 0xFFFF99);
        if (expectedChunks > 0) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.partial", pending.size(), expectedChunks),
                guiLeft + 12, guiTop + 98, System.currentTimeMillis() - pendingSince > 5000 ? 0xFF5555 : 0xFFFF77);
        else if (filtered.isEmpty()) fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.no_match"),
                guiLeft + 12, guiTop + 100, 0xAAAAAA);
        for (int i = 0; i < 4 && i + scrollOffset < filtered.size(); i++) {
            Mode2ConfigPacket.PanelEntry entry = filtered.get(i + scrollOffset);
            int y = guiTop + 89 + i * ROW_H;
            String name = entry.item.getDisplayName();
            if (fontRenderer.getStringWidth(name) > 102) name = fontRenderer.trimStringToWidth(name, 99) + "…";
            fontRenderer.drawString(name, guiLeft + 13, y, 0xDDDDDD);
            String status = rejectTab ? (entry.rejecting ? I18n.format("gui.ae2addon.mode2.reject_mark") :
                    I18n.format("gui.ae2addon.mode2.reject_allow_mark"))
                    : (entry.infinite ? "§b∞" : shortAmount(entry.amount))
                    + (entry.whitelisted ? " §aW" : "") + (entry.rejecting ? " §c!" : "");
            fontRenderer.drawString(status, guiLeft + 149, y, 0xFFFFFF);
        }
        if (workMode == 1) thresholdInput.drawTextBox();
        searchInput.drawTextBox();
        if (!editing.isEmpty()) itemThresholdInput.drawTextBox();
        if (mouseX >= guiLeft + 10 && mouseX < guiLeft + 246 && mouseY >= guiTop + 88
                && mouseY < guiTop + 150) {
            int row = (mouseY - guiTop - 88) / ROW_H + scrollOffset;
            if (row >= 0 && row < filtered.size()) {
                Mode2ConfigPacket.PanelEntry entry = filtered.get(row);
                List<String> tooltip = new ArrayList<>();
                tooltip.add(entry.item.getDisplayName());
                tooltip.add(entry.infinite ? I18n.format("gui.ae2addon.mode2.infinite")
                        : I18n.format("gui.ae2addon.mode2.amount", entry.amount.toString()));
                if (rejectTab) tooltip.add(I18n.format("gui.ae2addon.mode2.reject_tip"));
                drawHoveringText(tooltip, mouseX, mouseY);
            }
        }
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
            if (x >= 10 && x < 246 && y >= 88 && y < 150) {
                int row = (y - 88) / ROW_H + scrollOffset;
                if (row >= 0 && row < filtered.size()) {
                    Mode2ConfigPacket.PanelEntry entry = filtered.get(row);
                    if (rejectTab && mouseButton == 0 && !isShiftKeyDown() && !isCtrlKeyDown())
                        CellNetwork.send(new Mode2ConfigPacket(7, entry.item.copy()));
                    else if (rejectTab && mouseButton == 1)
                        CellNetwork.send(new Mode2ConfigPacket(22, entry.item.copy()));
                    else if (!rejectTab && mouseButton == 0 && isShiftKeyDown() && entry.infinite)
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
        }
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (uiState == 1) {
            if (!editing.isEmpty()) {
                if (keyCode == 28 || keyCode == 156) { saveItemThreshold(); return; }
                if (keyCode == 1) { hideItemThreshold(); return; }
                if (itemThresholdInput.textboxKeyTyped(typedChar, keyCode)) return;
            }
            if (workMode == 1 && thresholdInput.textboxKeyTyped(typedChar, keyCode)) return;
            if (searchInput.textboxKeyTyped(typedChar, keyCode)) return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        if (uiState != 1) return;
        int wheel = Mouse.getDWheel();
        if (wheel != 0) scrollOffset = Math.max(0, Math.min(Math.max(0, filtered.size() - 4),
                scrollOffset + (wheel < 0 ? 1 : -1)));
    }

    @Override public boolean doesGuiPauseGame() { return false; }
}
