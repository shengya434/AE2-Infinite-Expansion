package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.network.Mode2ConfigPacket;
import com.ae2addon.network.SetCellModePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class ModeSelectScreen extends AbstractContainerScreen<ModeSelectMenu> {

    private static final int W = 180, H = 158;
    private EditBox formatInput;
    private Button formatConfirmButton;
    private Button formatCancelButton;
    private boolean formatOpen;
    private boolean formatConfirmed;

    public ModeSelectScreen(ModeSelectMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = W; imageHeight = H;
    }

    @Override
    protected void init() {
        super.init();
        formatOpen = false;
        formatConfirmed = false;
        int cx = leftPos + W / 2;
        addRenderableWidget(btn(Component.translatable("gui.ae2addon.mode_select.btn1"), 1).bounds(cx - 75, topPos + 10, 150, 22).build());
        addRenderableWidget(btn(Component.translatable("gui.ae2addon.mode_select.btn2"), 2).bounds(cx - 75, topPos + 36, 150, 22).build());
        addRenderableWidget(btn(Component.translatable("gui.ae2addon.mode_select.btn3"), 3).bounds(cx - 75, topPos + 62, 150, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ae2addon.mode_select.format_btn"), b -> showFormat())
                .bounds(cx - 75, topPos + 90, 150, 20).build());

        formatInput = new EditBox(font, leftPos + 10, topPos + 116, 118, 16, Component.literal("Format"));
        formatInput.setMaxLength(32);
        formatInput.setResponder(value -> {
            formatConfirmed = false;
            if (formatConfirmButton != null) formatConfirmButton.setMessage(Component.literal("§c√"));
        });
        addRenderableWidget(formatInput);
        formatConfirmButton = Button.builder(Component.literal("§c√"), b -> confirmFormat())
                .bounds(leftPos + 130, topPos + 115, 20, 18).build();
        addRenderableWidget(formatConfirmButton);
        formatCancelButton = Button.builder(Component.literal("§7✕"), b -> hideFormat())
                .bounds(leftPos + 152, topPos + 115, 20, 18).build();
        addRenderableWidget(formatCancelButton);
        updateFormatVisibility();
    }

    private void updateFormatVisibility() {
        formatInput.setVisible(formatOpen);
        formatConfirmButton.visible = formatOpen;
        formatCancelButton.visible = formatOpen;
    }

    private void showFormat() {
        formatInput.setValue("");
        formatConfirmed = false;
        formatConfirmButton.setMessage(Component.literal("§c√"));
        formatOpen = true;
        updateFormatVisibility();
        setFocused(formatInput);
        formatInput.setFocused(true);
    }

    private void hideFormat() {
        formatOpen = false;
        formatConfirmed = false;
        formatInput.setFocused(false);
        setFocused(null);
        updateFormatVisibility();
    }

    private void checkFormatText() {
        String value = formatInput.getValue().trim();
        formatConfirmed = value.equalsIgnoreCase("DELETE") || value.equals("确认") || value.equals("删除");
        formatConfirmButton.setMessage(Component.literal(formatConfirmed ? "§a√" : "§c√"));
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                formatConfirmed ? "gui.ae2addon.mode_select.format_ok" : "gui.ae2addon.mode_select.format_bad"), false);
    }

    private void confirmFormat() {
        if (!formatConfirmed) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("gui.ae2addon.mode_select.format_first"), false);
            return;
        }
        hideFormat();
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(14, 0L, ""));
    }

    private Button.Builder btn(Component text, int mode) {
        return Button.builder(text, b -> {
            AE2Addon.NETWORK.sendToServer(new SetCellModePacket(mode));
            this.onClose();
        });
    }

    @Override protected void renderBg(GuiGraphics g, float d, int mx, int my) { renderBackground(g); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float d) {
        super.render(g, mx, my, d);
        g.drawString(font, Component.translatable("gui.ae2addon.mode_select.title"), leftPos + W / 2 - 36, topPos + 0, 0xFFFFFF, false);
        g.drawString(font, Component.translatable(formatOpen
                ? "gui.ae2addon.mode_select.format_hint" : "gui.ae2addon.mode_select.format_warn"),
                leftPos + 10, topPos + 138, 0xFFFFFF, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (formatOpen) {
            if (keyCode == 257 || keyCode == 335) { checkFormatText(); return true; }
            if (keyCode == 256) { hideFormat(); return true; }
            if (formatInput.isFocused()) { formatInput.keyPressed(keyCode, scanCode, modifiers); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
