package com.ae2addon.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * JEI 页「千机·自用配方」（2026-09-15 sensei 定稿）：
 * 展示千机可由自有样板执行的配方（输入 / 主产物 / **概率产出带几率**），
 * 并在页面上提供一个「编码」按钮 —— 点一下就把这张配方编成**千机配方样板**（数据自洽，千机可精确执行）。
 */
public class QianJiRecipeCategory implements IRecipeCategory<QianJiRecipeCategory.Entry> {

    public static final RecipeType<Entry> TYPE =
            new RecipeType<>(new ResourceLocation(AE2Addon.MODID, "qianji_recipes"), Entry.class);

    private static final int WIDTH = 176;
    private static final int HEIGHT = 92;
    /** 「编码」按钮区域（页面内坐标） */
    private static final int BTN_X = 150;
    private static final int BTN_Y = 68;
    private static final int BTN_W = 20;
    private static final int BTN_H = 16;

    private final IDrawable icon;
    private final IDrawable arrow;

    /** 一条配方：来源 id + 我们提取出的数据 */
    public record Entry(String recipeId, QianJiPatternData data) {}

    public QianJiRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(new ItemStack(ModItems.QIAN_JI_PATTERN.get()));
        this.arrow = guiHelper.getRecipeArrow();
    }

    public static Entry of(Recipe<?> recipe, QianJiPatternData data) {
        ResourceLocation id = recipe.getId();
        return new Entry(id == null ? "" : id.toString(), data);
    }

    @Override
    public RecipeType<Entry> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.ae2addon.jei.qianji.title");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Entry entry, IFocusGroup focuses) {
        QianJiPatternData data = entry.data();

        // ── 输入：左上，每行 5 格 ──
        int x = 6;
        int y = 16;
        for (QianJiPatternData.Slot slot : data.inputs()) {
            var slotBuilder = builder.addInputSlot(x, y);
            addOptions(slotBuilder, slot.options());
            x += 18;
            if (x > 84) {
                x = 6;
                y += 18;
            }
        }

        // ── 主产物：右侧第一行。
        //    2026-09-15 修：原来用 addItemStack(itemOf(...))，流体输出会被渲染成**空气**；
        //    统一走 addStack（内部区分物品/流体）。──
        int ox = 106;
        int oy = 16;
        for (QianJiPatternData.Out out : data.primary()) {
            addStack(builder.addOutputSlot(ox, oy), out.stack());
            ox += 18;
            if (ox > 142) {
                ox = 106;
                oy += 18;
            }
        }

        // ── 概率产出：右侧第二行起 ──
        ox = 106;
        oy = 52;
        for (QianJiPatternData.Chanced chanced : data.chanced()) {
            addStack(builder.addOutputSlot(ox, oy), chanced.stack());
            ox += 18;
            if (ox > 142) {
                ox = 106;
                oy += 18;
            }
        }
    }

    private static void addOptions(mezz.jei.api.gui.builder.IRecipeSlotBuilder slotBuilder,
                                   List<GenericStack> options) {
        for (GenericStack option : options) {
            addStack(slotBuilder, option);
        }
    }

    private static void addStack(mezz.jei.api.gui.builder.IRecipeSlotBuilder slotBuilder, GenericStack stack) {
        if (stack == null || stack.what() == null) return;
        if (stack.what() instanceof AEItemKey itemKey) {
            slotBuilder.addItemStack(itemKey.toStack((int) Math.max(1, stack.amount())));
        } else if (stack.what() instanceof AEFluidKey fluidKey) {
            slotBuilder.addFluidStack(fluidKey.getFluid(), stack.amount());
        }
    }

    private static ItemStack itemOf(GenericStack stack) {
        return stack.what() instanceof AEItemKey itemKey
                ? itemKey.toStack((int) Math.max(1, stack.amount()))
                : ItemStack.EMPTY;
    }

    @Override
    public void draw(Entry entry, IRecipeSlotsView slotsView, GuiGraphics graphics, double mouseX, double mouseY) {
        arrow.draw(graphics, 88, 17);

        var font = Minecraft.getInstance().font;
        QianJiPatternData data = entry.data();

        // 概率产出：槽位下方标几率（位置与 setRecipe 保持一致）
        int ox = 106;
        int oy = 52;
        for (QianJiPatternData.Chanced chanced : data.chanced()) {
            String pct = chanced.chance() > 0f ? Math.round(chanced.chance() * 100) + "%" : "?";
            graphics.drawString(font, "§d" + pct, ox, oy + 17, 0xFFFFFF, false);
            ox += 18;
            if (ox > 142) {
                ox = 106;
                oy += 18;
            }
        }

        // 编码按钮
        boolean hover = mouseX >= BTN_X && mouseX <= BTN_X + BTN_W
                && mouseY >= BTN_Y && mouseY <= BTN_Y + BTN_H;
        graphics.fill(BTN_X, BTN_Y, BTN_X + BTN_W, BTN_Y + BTN_H, hover ? 0x8040FF40 : 0x60207020);
        graphics.drawString(font, "§a编码", BTN_X - 2, BTN_Y + 4, 0xFFFFFF, false);

        graphics.drawString(font, "§7主产 §a●§7 / 概率 §d●", 6, 78, 0xFFFFFF, false);
        if (entry.recipeId() != null && !entry.recipeId().isEmpty()) {
            graphics.drawString(font, "§8" + trim(entry.recipeId(), 30), 6, 62, 0xFFFFFF, false);
        }
    }

    @Override
    public boolean handleInput(Entry entry, double mouseX, double mouseY,
                               com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (mouseX < BTN_X || mouseX > BTN_X + BTN_W || mouseY < BTN_Y || mouseY > BTN_Y + BTN_H) {
            return false;
        }
        if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return false;
        }
        // 点击「编码」→ 请求服务端生成千机配方样板（成本与 AE2 编码一致：空白样板）
        AE2Addon.NETWORK.sendToServer(new com.ae2addon.network.QianJiPatternPacket(entry.recipeId()));
        return true;
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
