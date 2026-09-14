package com.ae2addon.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 驱动器（无限级）面板 —— 512 格元件，54 格/页翻页（2026-09-14 重写）。
 * <p>
 * 翻页经 {@code handleInventoryButtonClick} 发给服务端（服务端权威页码），
 * 页码从菜单的 DataSlot 读（不在客户端本地维护）。
 */
public class InfiniteDriveScreen extends AbstractContainerScreen<InfiniteDriveMenu> {

    private static final int W = 176;
    private static final int H = 234;
    private static final int BTN_PREV = 0;
    private static final int BTN_NEXT = 1;

    public InfiniteDriveScreen(InfiniteDriveMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = W;
        imageHeight = H;
        inventoryLabelY = 138;
    }

    @Override
    protected void init() {
        super.init();
        int cx = leftPos + W / 2;

        addRenderableWidget(Button.builder(Component.literal("◀"), b -> sendButtonClick(BTN_PREV))
                .bounds(cx - 58, topPos + 4, 20, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> sendButtonClick(BTN_NEXT))
                .bounds(cx + 38, topPos + 4, 20, 14).build());
    }

    private void sendButtonClick(int buttonId) {
        Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        renderBackground(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int page = menu.getCurrentPage();
        int maxPage = menu.getMaxPage();
        String pageText = "§7" + (page + 1) + " / " + (maxPage + 1);
        g.drawString(font, Component.literal(pageText),
                leftPos + W / 2 - font.width(pageText) / 2, topPos + 7, 0xFFFFFF, false);

        // 页码对应的槽位区间提示（512 格 → 0-53 / 54-107 / …）
        int from = page * 54 + 1;
        int to = Math.min(page * 54 + 54, 512);
        String rangeText = "§8#" + from + " – " + to;
        g.drawString(font, Component.literal(rangeText), leftPos + 6, topPos + 7, 0x808080, false);

        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        g.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
    }
}
