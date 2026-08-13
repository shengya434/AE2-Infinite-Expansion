package com.ae2addon.integration.jei;

import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 集成 CPU 结构 JEI 分类页：展示 3×5×3 多方块结构（自动旋转预览 + 分层浏览）。
 */
public class IntegratedCPURecipeCategory implements IRecipeCategory<IntegratedCPURecipeCategory.IntegratedCPURecipe> {

    /** 结构预览占位 recipe */
    public record IntegratedCPURecipe() {
    }

    public static final RecipeType<IntegratedCPURecipe> TYPE =
            RecipeType.create(AE2Addon.MODID, "integrated_cpu_structure", IntegratedCPURecipe.class);

    private final IDrawable background;
    private final IDrawable icon;

    public IntegratedCPURecipeCategory(IGuiHelper helper) {
        // 背景高度 156：上方是 3D 结构预览（y≈34~100），下方是材料清单（y≈104~152）
        this.background = helper.createBlankDrawable(176, 156);
        this.icon = helper.createDrawableIngredient(
                VanillaTypes.ITEM_STACK, new ItemStack(ModItems.INTEGRATED_CPU_ITEM.get()));
    }

    @Override
    public RecipeType<IntegratedCPURecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.ae2addon.jei.cpu_structure");
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, IntegratedCPURecipe recipe, IFocusGroup focuses) {
        // 展示集成 CPU 物品作为"输出"，让 JEI 把分类与物品关联起来
        // （按 U 查看集成 CPU 用途时能看到结构预览）
        builder.addSlot(RecipeIngredientRole.OUTPUT, 140, 110)
                .addItemStack(new ItemStack(ModItems.INTEGRATED_CPU_ITEM.get()));
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, IntegratedCPURecipe recipe, IFocusGroup focuses) {
        // 预览组件：位置相对 category 背景（176x128）
        IntegratedCPUPreviewWidget widget =
                new IntegratedCPUPreviewWidget(new ScreenPosition(18, 0));
        builder.addWidget(widget);
        builder.addInputHandler(widget);
        // 材料统计文字
        int purple = IntegratedCPUStructure.materialCounts()
                .getOrDefault(net.minecraft.world.level.block.Blocks.PURPLE_CONCRETE, 0);
        int magenta = IntegratedCPUStructure.materialCounts()
                .getOrDefault(net.minecraft.world.level.block.Blocks.MAGENTA_CONCRETE, 0);
        int bookshelf = IntegratedCPUStructure.materialCounts()
                .getOrDefault(net.minecraft.world.level.block.Blocks.BOOKSHELF, 0);
        AE2Addon.LOGGER.info("[ae2addon] JEI 集成CPU结构: 紫{} 品红{} 书架{}",
                purple, magenta, bookshelf);
    }
}
