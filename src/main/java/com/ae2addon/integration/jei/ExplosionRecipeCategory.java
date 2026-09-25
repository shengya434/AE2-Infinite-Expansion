package com.ae2addon.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.AE2Addon;
import com.ae2addon.recipe.ExplosionRecipe;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 页：**爆炸合成**（2026-09-19 sensei：爆炸配方单开一个标签页）。
 * <p>
 * 版面：左边是要求丢在地上的材料（每格 tooltip 写需要多少个）→ 中间 TNT → 右边产物。
 * 点/右键 TNT 就能筛出这一页（TNT 注册成催化剂）。
 * <p>
 * 配方本身由 {@code data/ae2addon/recipes/explosion_*.json} 定义，
 * 执行者是 {@link com.ae2addon.crafting.ExplosionRecipeHandler}（按物品个数跨堆叠统计）。
 */
public class ExplosionRecipeCategory implements IRecipeCategory<ExplosionRecipe> {

    public static final RecipeType<ExplosionRecipe> TYPE =
            RecipeType.create(AE2Addon.MODID, "explosion_recipe", ExplosionRecipe.class);

    private static final int EDGE = 4;
    private static final int STEP = 20;
    private static final int PER_ROW = 3;
    private static final int Y_IN = 4;
    private static final int X_TNT = 76;
    private static final int Y_MID = 22;
    private static final int X_OUT = 104;

    private final IDrawable background;
    private final IDrawable icon;

    public ExplosionRecipeCategory(IGuiHelper helper) {
        this.background = helper.createBlankDrawable(136, 46);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, new ItemStack(Items.TNT));
    }

    @Override
    public RecipeType<ExplosionRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.ae2addon.jei.explosion.title");
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
    public void setRecipe(IRecipeLayoutBuilder builder, ExplosionRecipe recipe, IFocusGroup focuses) {
        List<GenericStack> inputs = recipe.inputs();
        for (int i = 0; i < inputs.size(); i++) {
            int x = EDGE + (i % PER_ROW) * STEP;
            int y = Y_IN + (i / PER_ROW) * STEP;
            var slotBuilder = builder.addInputSlot(x, y);
            addStack(slotBuilder, inputs.get(i));
        }

        // 中间：爆炸本身（催化剂 → 右键 TNT 能筛出这一页）
        builder.addSlot(RecipeIngredientRole.CATALYST, X_TNT, Y_MID)
                .addItemStack(new ItemStack(Items.TNT))
                .addRichTooltipCallback((view, tooltip) -> {
                    tooltip.add(Component.translatable("gui.ae2addon.jei.explosion.tnt"));
                    tooltip.add(Component.translatable("gui.ae2addon.jei.explosion.hint"));
                });

        if (recipe.result() != null) {
            var out = builder.addOutputSlot(X_OUT, Y_MID);
            addStack(out, recipe.result());
        }
    }

    private static void addStack(IRecipeSlotBuilder slotBuilder, GenericStack stack) {
        if (stack == null || stack.what() == null) {
            return;
        }
        if (stack.what() instanceof AEItemKey itemKey) {
            // 数量可能远超 64（JEI 图标只画 64 个），真实数量走 tooltip
            slotBuilder.addItemStack(itemKey.toStack((int) Math.max(1L, Math.min(64L, stack.amount()))));
        } else if (stack.what() instanceof AEFluidKey fluidKey) {
            slotBuilder.addFluidStack(fluidKey.getFluid(), stack.amount(), fluidKey.copyTag());
        } else if (!MekanismJeiCompat.addChemical(slotBuilder, stack.what(), stack.amount())) {
            // 非物品非流体、MEK 也渲染不了 → 图标由 JEI 的空白格代替，数量仍在 tooltip 里
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 爆炸配方页：无法渲染的键 {}",
                    stack.what().getDisplayName().getString());
        }
        long amount = stack.amount();
        slotBuilder.addRichTooltipCallback((view, tooltip) ->
                tooltip.add(Component.literal("§7数量 §f" + amount)));
    }

    /** 给插件用的图标栈（催化剂注册用） */
    public static List<ItemStack> catalysts() {
        var list = new ArrayList<ItemStack>(1);
        list.add(new ItemStack(Items.TNT));
        return list;
    }
}
