package com.ae2addon.integration.jei;

import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
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
        this.background = helper.createBlankDrawable(176, 128);
        this.icon = helper.createDrawableIngredient(
                VanillaTypes.ITEM_STACK, new ItemStack(ModItems.INTEGRATED_CPU_ITEM.get()));
    }

    @Override
    public RecipeType<IntegratedCPURecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.literal("集成 CPU 结构");
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
        // 纯结构展示，无合成槽位
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
