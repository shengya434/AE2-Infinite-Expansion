package com.ae2addon.gui;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import com.ae2addon.AE2Addon;
import com.ae2addon.network.Mode2ConfigPacket;
import com.ae2addon.network.SetCellModePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;

public class ModeSelectScreen extends AbstractContainerScreen<ModeSelectMenu> {

    private static final int W = 180, H = 240;
    private final Set<AEKey> rejectOn = new LinkedHashSet<>(), rejectOff = new LinkedHashSet<>();
    private int rejectScroll;
    private boolean rejectDefault;
    private Button rejectDefaultButton;
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
        rejectDefaultButton = Button.builder(Component.empty(), b -> {
            rejectDefault = !rejectDefault;
            AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(8, rejectDefault));
            updateRejectDefaultButton();
        }).bounds(leftPos + 10, topPos + 215, 150, 16).build();
        addRenderableWidget(rejectDefaultButton);
        updateRejectDefaultButton();
        AE2Addon.NETWORK.sendToServer(new Mode2ConfigPacket(23));
    }

    public void setRejectDefault(boolean value) {
        rejectDefault = value;
        updateRejectDefaultButton();
    }

    public void setRejectKeys(Set<AEKey> on, Set<AEKey> off) {
        rejectOn.clear(); rejectOn.addAll(on);
        rejectOff.clear(); rejectOff.addAll(off);
        rejectScroll = Math.min(rejectScroll, Math.max(0, rejectEntries().size() - 3));
    }

    private void updateRejectDefaultButton() {
        if (rejectDefaultButton != null) rejectDefaultButton.setMessage(Component.literal(
                (rejectDefault ? "☑ " : "☐ ") + Component.translatable("gui.ae2addon.mode2.reject_default").getString()));
    }

    private List<AEKey> rejectEntries() {
        Set<AEKey> keys = new LinkedHashSet<>(rejectOn);
        keys.addAll(rejectOff);
        List<AEKey> entries = new ArrayList<>(keys);
        entries.sort(Comparator.comparing(k -> k.getDisplayName().getString()));
        return entries;
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
        List<AEKey> entries = rejectEntries();
        g.drawString(font, Component.translatable("gui.ae2addon.mode2.tab_reject"), leftPos + 10, topPos + 158, 0xFFFFAA, false);
        g.drawString(font, "(" + entries.size() + ")", leftPos + 72, topPos + 158, 0xAAAAAA, false);
        if (entries.isEmpty()) g.drawString(font, Component.translatable("gui.ae2addon.mode2.reject_empty"),
                leftPos + 12, topPos + 174, 0x888888, false);
        for (int i = rejectScroll; i < Math.min(entries.size(), rejectScroll + 3); i++) {
            AEKey key = entries.get(i);
            int rowY = topPos + 171 + (i - rejectScroll) * 14;
            ItemStack icon = key instanceof AEItemKey item ? item.toStack() : new ItemStack(Items.BUCKET);
            if (!icon.isEmpty()) g.renderItem(icon, leftPos + 10, rowY);
            String name = key.getDisplayName().getString();
            if (font.width(name) > 110) name = font.plainSubstrByWidth(name, 107) + "…";
            g.drawString(font, name, leftPos + 28, rowY + 3, 0xCCCCCC, false);
            g.drawString(font, Component.translatable(rejectOn.contains(key) ? "gui.ae2addon.mode2.reject_mark"
                    : "gui.ae2addon.mode2.reject_allow_mark"), leftPos + 150, rowY + 3,
                    rejectOn.contains(key) ? 0xFF5555 : 0x55FF55, false);
        }
        if (entries.size() > 3) {
            int barH = Math.max(7, 42 * 3 / entries.size());
            g.fill(leftPos + 174, topPos + 171 + rejectScroll * 42 / entries.size(),
                    leftPos + 176, topPos + 171 + rejectScroll * 42 / entries.size() + barH, 0xFF888888);
        }
        if (mx >= leftPos + 10 && mx < leftPos + 178 && my >= topPos + 171 && my < topPos + 213) {
            int row = (my - topPos - 171) / 14 + rejectScroll;
            if (row >= 0 && row < entries.size()) {
                AEKey key = entries.get(row);
                g.renderTooltip(font, Component.literal(key.getDisplayName().getString() + " | "
                        + I18n.get(rejectOn.contains(key) ? "gui.ae2addon.mode2.reject_mark"
                                : "gui.ae2addon.mode2.reject_allow_mark") + " | "
                        + I18n.get("gui.ae2addon.mode2.reject_tip")), mx, my);
            }
        }
    }

    /** 该键当前是否处于「拒收」态（用同步下来的状态判断；JEI 拖入时用来判重，避免误切成允许）。 */
    public boolean isRejectingKey(AEKey key) {
        if (key == null) return false;
        if (rejectOn.contains(key)) return true;
        if (rejectOff.contains(key)) return false;
        return rejectDefault;
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !hasControlDown()
                && mx >= leftPos + 10 && mx < leftPos + 178 && my >= topPos + 171 && my < topPos + 213) {
            int row = (int)(my - topPos - 171) / 14 + rejectScroll;
            List<AEKey> entries = rejectEntries();
            if (row >= 0 && row < entries.size()) {
                var tag = entries.get(row).toTagGeneric();
                // 2026-10-13 sensei：Shift+左键 = 从拒收列表删除（type 22 = clearReject）；
                // 左键 = 照旧切换 拒收/允许（type 7）。加入走 JEI 拖入（ModeSelectGhostHandler）。
                AE2Addon.NETWORK.sendToServer(hasShiftDown()
                        ? new Mode2ConfigPacket(22, tag) : new Mode2ConfigPacket(7, tag));
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= leftPos + 10 && mx < leftPos + 178 && my >= topPos + 171 && my < topPos + 213) {
            rejectScroll = Math.max(0, Math.min(Math.max(0, rejectEntries().size() - 3),
                    rejectScroll + (delta < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
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
