package com.ae2addon.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 千机·阿比舒 样板管理界面
 * <p>
 * 翻页通过 handleInventoryButtonClick 发送服务端包，
 * 服务端响应后同步槽位数据回客户端。
 */
public class QianJiScreen extends AbstractContainerScreen<QianJiMenu> {

    private static final int W = 176;
    private static final int H = 222;
    private static final int BTN_PREV = 0;
    private static final int BTN_NEXT = 1;

    public QianJiScreen(QianJiMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = W;
        imageHeight = H;
        inventoryLabelY = 10000;
        titleLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        int cx = leftPos + W / 2;

        addRenderableWidget(Button.builder(
                Component.literal("◀"),
                btn -> sendButtonClick(BTN_PREV)
        ).bounds(cx - 52, topPos + 5, 20, 16).build());

        addRenderableWidget(Button.builder(
                Component.literal("▶"),
                btn -> sendButtonClick(BTN_NEXT)
        ).bounds(cx + 32, topPos + 5, 20, 16).build());
    }

    /** 发送翻页按钮点击到服务端 */
    private void sendButtonClick(int buttonId) {
        Minecraft.getInstance().gameMode
                .handleInventoryButtonClick(menu.containerId, buttonId);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        renderBackground(g);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);

        // 页号
        int page = menu.getCurrentPage();
        int maxPage = (1280 + 54 - 1) / 54 - 1;
        String pageText = "§7" + (page + 1) + " / " + (maxPage + 1);
        g.drawString(font, Component.literal(pageText),
                leftPos + W / 2 - font.width(pageText) / 2, topPos + 7, 0xFFFFFF, false);

        // 催化剂标签
        g.drawString(font, Component.literal("§7催化剂"),
                leftPos + 28, topPos + 22, 0x888888, false);

        // 样板标签
        g.drawString(font, Component.literal("§7样板"),
                leftPos + 8, topPos + 42, 0x555555, false);

        renderTooltip(g, mx, my);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {}
}
