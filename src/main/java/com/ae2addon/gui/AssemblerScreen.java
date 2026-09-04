package com.ae2addon.gui;

import com.ae2addon.block.AssemblerCoreBE;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 装配处理器样板槽界面：9×5 页 + 翻页按钮 + 页码，纯 vanilla 渲染。
 */
public class AssemblerScreen extends AbstractContainerScreen<AssemblerMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation("textures/gui/container/dispenser.png");
    private static final int PAGE_BTN_W = 16;

    public AssemblerScreen(AssemblerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 194;
    }

    @Override
    protected void init() {
        super.init();
        // 标题移到槽区上方居中（默认位置会被槽区压住）
        this.titleLabelY = 6;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 简洁暗色背景（2026-09-04 sensei：背景图删掉）
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF2A2A2A);

        // 样板槽格底色（9×5，起于 (8,30)）
        graphics.fill(leftPos + 7, topPos + 29,
                leftPos + 7 + 9 * 18 + 2, topPos + 29 + 5 * 18 + 2, 0xFF8B8B8B);
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 9; col++) {
                int x = leftPos + 8 + col * 18;
                int y = topPos + 30 + row * 18;
                graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF373737);
            }
        }

        // 翻页按钮 + 页码条（槽区上方 y=17..27）
        int cx = leftPos + imageWidth / 2;
        // 上一页（◀ 在左）
        drawPageButton(graphics, cx - 60, topPos + 16, -1);
        // 页码
        Component pageText = Component.literal(
                (menu.currentPage() + 1) + " / " + AssemblerCoreBE.PAGES);
        graphics.drawString(font, pageText, cx - font.width(pageText) / 2,
                topPos + 19, 0xFFE0E0E0, false);
        // 下一页（▶ 在右）
        drawPageButton(graphics, cx + 60 - PAGE_BTN_W, topPos + 16, 1);
    }

    private void drawPageButton(GuiGraphics graphics, int x, int y, int delta) {
        graphics.fill(x, y, x + PAGE_BTN_W, y + 12, 0xFF555555);
        String arrow = delta < 0 ? "<" : ">";
        int tx = x + (PAGE_BTN_W - font.width(arrow)) / 2;
        graphics.drawString(font, arrow, tx, y + 2, 0xFFFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int cx = leftPos + imageWidth / 2;
            // 上一页按钮区域
            if (mouseX >= cx - 60 && mouseX <= cx - 60 + PAGE_BTN_W
                    && mouseY >= topPos + 16 && mouseY <= topPos + 28) {
                menu.changePage(-1);
                return true;
            }
            // 下一页按钮区域
            if (mouseX >= cx + 60 - PAGE_BTN_W && mouseX <= cx + 60
                    && mouseY >= topPos + 16 && mouseY <= topPos + 28) {
                menu.changePage(1);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // 2026-09-04（抄 InfiniteInterfaceScreen 经验）：1.20.1 render() 链不调用
        // renderTooltip（反编译 0 调用点），显式补调才有悬浮提示（重复画无害）
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, 6, 0xFFFFFF, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, this.imageHeight - 92, 0xA0A0A0, false);
    }
}
