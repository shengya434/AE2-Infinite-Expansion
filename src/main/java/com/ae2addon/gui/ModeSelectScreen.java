package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.network.SetCellModePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class ModeSelectScreen extends AbstractContainerScreen<ModeSelectMenu> {

    private static final int W = 160, H = 115;

    public ModeSelectScreen(ModeSelectMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = W; imageHeight = H;
    }

    @Override
    protected void init() {
        super.init();
        int cx = leftPos + W / 2;
        addRenderableWidget(btn("§a【无限制存储】", 1).bounds(cx - 75, topPos + 10, 150, 22).build());
        addRenderableWidget(btn("§e【自定义无限】", 2).bounds(cx - 75, topPos + 36, 150, 22).build());
        addRenderableWidget(btn("§d【全类型无限】", 3).bounds(cx - 75, topPos + 62, 150, 22).build());
    }

    private Button.Builder btn(String text, int mode) {
        return Button.builder(Component.literal(text), b -> {
            AE2Addon.NETWORK.sendToServer(new SetCellModePacket(mode));
            this.onClose();
        });
    }

    @Override protected void renderBg(GuiGraphics g, float d, int mx, int my) { renderBackground(g); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float d) {
        super.render(g, mx, my, d);
        g.drawString(font, Component.literal("§l选择存储模式"), leftPos + W / 2 - 36, topPos + 0, 0xFFFFFF, false);
        g.drawString(font, Component.literal("§7右键可配置自定义模式"), leftPos + 10, topPos + 90, 0x888888, false);
    }
}
