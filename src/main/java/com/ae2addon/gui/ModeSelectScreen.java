package com.ae2addon.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import com.ae2addon.network.CellNetwork;
import com.ae2addon.network.Mode2ConfigPacket;
import com.ae2addon.network.SetCellModePacket;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

/** Storage mode selection with the cell-wide reject default switch. */
@SideOnly(Side.CLIENT)
public final class ModeSelectScreen extends GuiContainer {
    private static final ResourceLocation PANEL = new ResourceLocation("ae2addon", "textures/gui/cell_panel.png");
    private boolean rejectDefault;
    private int onCount;
    private int offCount;
    private final List<Mode2ConfigPacket.PanelEntry> visibleRejects = new ArrayList<>();
    private final Set<Integer> receivedChunks = new HashSet<>();
    private int expectedChunks;
    private int rejectScroll;
    private long pendingSince;
    private GuiButton defaultButton;
    private GuiTextField formatInput;

    public ModeSelectScreen(ModeSelectMenu menu) {
        super(menu); xSize = 180; ySize = 240;
    }

    @Override public void initGui() {
        super.initGui();
        int x = guiLeft, y = guiTop;
        buttonList.add(new GuiButton(1, x + 15, y + 10, 150, 22, I18n.format("gui.ae2addon.mode_select.btn1")));
        buttonList.add(new GuiButton(2, x + 15, y + 36, 150, 22, I18n.format("gui.ae2addon.mode_select.btn2")));
        buttonList.add(new GuiButton(3, x + 15, y + 62, 150, 22, I18n.format("gui.ae2addon.mode_select.btn3")));
        GuiButton format = new GuiButton(4, x + 15, y + 90, 150, 20,
                I18n.format("gui.ae2addon.mode_select.format_btn"));
        format.enabled = false;
        buttonList.add(format);
        formatInput = new GuiTextField(0, fontRenderer, x + 10, y + 116, 118, 16);
        formatInput.setMaxStringLength(32);
        formatInput.setEnabled(false);
        defaultButton = new GuiButton(8, x + 10, y + 215, 150, 16, "");
        buttonList.add(defaultButton);
        buttonList.add(new GuiButton(9, x + 150, y + 152, 20, 16, "R"));
        updateDefaultButton();
        CellNetwork.send(new Mode2ConfigPacket(23));
    }

    private void updateDefaultButton() {
        defaultButton.displayString = (rejectDefault ? "[x] " : "[ ] ")
                + I18n.format("gui.ae2addon.mode2.reject_default");
    }

    @Override protected void actionPerformed(GuiButton button) {
        if (button.id >= 1 && button.id <= 3) CellNetwork.send(new SetCellModePacket(button.id));
        else if (button.id == 8) {
            rejectDefault = !rejectDefault;
            updateDefaultButton();
            CellNetwork.send(new Mode2ConfigPacket(8, rejectDefault));
        } else if (button.id == 9) CellNetwork.send(new Mode2ConfigPacket(23));
    }

    public void accept(Mode2ConfigPacket packet) {
        if (packet.type == 18 && packet.state != null) {
            rejectDefault = packet.state.getBoolean("default");
            onCount = packet.state.getInteger("onCount");
            offCount = packet.state.getInteger("offCount");
            updateDefaultButton();
        } else if (packet.type == 4 && packet.chunkIndex == 0) {
            visibleRejects.clear();
            receivedChunks.clear();
            expectedChunks = packet.totalChunks;
            pendingSince = System.currentTimeMillis();
        }
        if (packet.type == 4 && expectedChunks == packet.totalChunks && receivedChunks.add(packet.chunkIndex)) {
            for (Mode2ConfigPacket.PanelEntry entry : packet.entries) {
                if (entry.rejecting) visibleRejects.add(entry);
            }
            if (receivedChunks.size() == expectedChunks) expectedChunks = 0;
            rejectScroll = Math.min(rejectScroll, Math.max(0, visibleRejects.size() - 3));
        }
    }

    @Override protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        mc.getTextureManager().bindTexture(PANEL);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
        drawRect(guiLeft + 7, guiTop + 152, guiLeft + 173, guiTop + 214, 0xFF111820);
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode_select.title"), guiLeft + 10, guiTop, 0xFFFFFF);
        fontRenderer.drawString(fontRenderer.trimStringToWidth(
                I18n.format("gui.ae2addon.mode_select.format_warn"), 160), guiLeft + 10, guiTop + 138, 0xBBBBBB);
        fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.tab_reject") + " (" + onCount + "/" + offCount + ")",
                guiLeft + 10, guiTop + 158, 0xFFFFFF);
        if (expectedChunks > 0) fontRenderer.drawString(
                I18n.format("gui.ae2addon.p3.partial", receivedChunks.size(), expectedChunks),
                guiLeft + 10, guiTop + 174, System.currentTimeMillis() - pendingSince > 5000 ? 0xFF5555 : 0xFFFF77);
        else if (visibleRejects.isEmpty()) fontRenderer.drawString(I18n.format("gui.ae2addon.mode2.reject_empty"),
                guiLeft + 10, guiTop + 174, 0xAAAAAA);
        for (int i = 0; expectedChunks == 0 && i < 3 && i + rejectScroll < visibleRejects.size(); i++) {
            ItemStack stack = visibleRejects.get(i + rejectScroll).item;
            fontRenderer.drawString(fontRenderer.trimStringToWidth(stack.getDisplayName(), 110),
                    guiLeft + 12, guiTop + 174 + i * 14, 0xDDDDDD);
        }
        formatInput.drawTextBox();
    }

    @Override protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int x = mouseX - guiLeft, y = mouseY - guiTop;
        if (mouseButton == 0 && !isShiftKeyDown() && !isCtrlKeyDown()
                && x >= 10 && x < 178 && y >= 171 && y < 213) {
            int row = (y - 171) / 14 + rejectScroll;
            if (row >= 0 && row < visibleRejects.size())
                CellNetwork.send(new Mode2ConfigPacket(7, visibleRejects.get(row).item.copy()));
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        formatInput.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getDWheel();
        if (wheel != 0) rejectScroll = Math.max(0, Math.min(Math.max(0, visibleRejects.size() - 3),
                rejectScroll + (wheel < 0 ? 1 : -1)));
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (!formatInput.textboxKeyTyped(typedChar, keyCode)) super.keyTyped(typedChar, keyCode);
    }

    @Override public boolean doesGuiPauseGame() { return false; }
}
