package com.ae2addon.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import com.ae2addon.item.BoundInfiniteCellItem;
import com.ae2addon.item.InfiniteEssenceItem;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * JEI 页：**无限精华 + ME 元件外壳 → 无限 xxx 元件**。
 * <p>
 * 2026-09-19（sensei 定稿的功能，见 {@code docs/infinite-essence-cell.md}）。
 * <p>
 * ⚠ 只做**两张示意**（物品一张、流体一张），**不按 key 展开**：
 * 精华/元件是"一个物品 + NBT 绑定"，可能的 key 有上万种，
 * 每个都生成一条 JEI 会把界面撑爆（千机页已经有 5 万多条了）。
 * 所以条目里的精华用**示例物品**（石头/岩浆…），并在 tooltip 里写明"任意精华均可"。
 * 物品本身的说明走 {@code addIngredientInfo}（见 AE2AddonJEIPlugin）。
 */
public class EssenceToCellRecipeCategory implements IRecipeCategory<EssenceToCellRecipeCategory.Entry> {

    /**
     * 一条示意配方。
     *
     * @param essences 精华示例（JEI 里可用滚轮换看，只是"长这样"的示意）
     * @param housing  对应的 ME 元件外壳
     * @param cell     产物元件（NBT 同精华绑定）
     * @param kind     变体（决定 tooltip 文案：物品 / 流体 / 化学品）
     */
    public record Entry(List<ItemStack> essences, ItemStack housing, ItemStack cell,
                        BoundInfiniteCellItem.Kind kind) {
    }

    public static final RecipeType<Entry> TYPE =
            RecipeType.create(AE2Addon.MODID, "essence_to_cell", Entry.class);

    private static final int X_ESSENCE = 1;
    private static final int X_HOUSING = 25;
    private static final int X_RESULT = 76;
    private static final int Y = 2;

    private final IDrawable background;
    private final IDrawable icon;

    public EssenceToCellRecipeCategory(IGuiHelper helper) {
        this.background = helper.createBlankDrawable(120, 40);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK,
                new ItemStack(ModItems.INFINITE_ITEM_CELL.get()));
    }

    /** 两张（装了 Applied-Mekanistics 时三张）示意图：物品 / 流体 / 化学品 */
    public static List<Entry> examples() {
        var list = new java.util.ArrayList<Entry>(3);
        ItemStack itemEssence = InfiniteEssenceItem.make(AEItemKey.of(Items.STONE), 1);
        ItemStack itemEssence2 = InfiniteEssenceItem.make(AEItemKey.of(Items.IRON_INGOT), 1);
        ItemStack itemEssence3 = InfiniteEssenceItem.make(AEItemKey.of(Items.DIAMOND), 1);
        ItemStack itemCell = BoundInfiniteCellItem.make(
                ModItems.INFINITE_ITEM_CELL.get(), AEItemKey.of(Items.STONE));
        list.add(new Entry(List.of(itemEssence, itemEssence2, itemEssence3),
                new ItemStack(appeng.core.definitions.AEItems.ITEM_CELL_HOUSING.asItem()),
                itemCell, BoundInfiniteCellItem.Kind.ITEM));

        ItemStack fluidEssence = InfiniteEssenceItem.make(AEFluidKey.of(Fluids.LAVA), 1);
        ItemStack fluidEssence2 = InfiniteEssenceItem.make(AEFluidKey.of(Fluids.WATER), 1);
        ItemStack fluidCell = BoundInfiniteCellItem.make(
                ModItems.INFINITE_FLUID_CELL.get(), AEFluidKey.of(Fluids.LAVA));
        list.add(new Entry(List.of(fluidEssence, fluidEssence2),
                new ItemStack(appeng.core.definitions.AEItems.FLUID_CELL_HOUSING.asItem()),
                fluidCell, BoundInfiniteCellItem.Kind.FLUID));

        // 化学品（Applied-Mekanistics 可选）：装了才加这一张
        Item chemHousing = com.ae2addon.compat.ChemicalCompat.chemicalHousing();
        var chemKey = com.ae2addon.compat.ChemicalCompat.exampleChemicalKey();
        if (chemHousing != null && chemKey != null) {
            ItemStack chemEssence = InfiniteEssenceItem.make(chemKey, 1);
            ItemStack chemCell = BoundInfiniteCellItem.make(
                    ModItems.INFINITE_CHEMICAL_CELL.get(), chemKey);
            list.add(new Entry(List.of(chemEssence), new ItemStack(chemHousing),
                    chemCell, BoundInfiniteCellItem.Kind.CHEMICAL));
        }
        return list;
    }

    @Override
    public RecipeType<Entry> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.ae2addon.jei.essence_cell.title");
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
    public void setRecipe(IRecipeLayoutBuilder builder, Entry recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, X_ESSENCE, Y)
                .addItemStacks(recipe.essences())
                .addRichTooltipCallback((view, tooltip) -> tooltip.add(
                        Component.translatable("gui.ae2addon.jei.essence_cell.any")));
        builder.addSlot(RecipeIngredientRole.INPUT, X_HOUSING, Y)
                .addItemStack(recipe.housing());
        builder.addSlot(RecipeIngredientRole.OUTPUT, X_RESULT, Y)
                .addItemStack(recipe.cell())
                .addRichTooltipCallback((view, tooltip) -> tooltip.add(
                        Component.translatable(switch (recipe.kind()) {
                            case ITEM -> "gui.ae2addon.jei.essence_cell.item";
                            case FLUID -> "gui.ae2addon.jei.essence_cell.fluid";
                            case CHEMICAL -> "gui.ae2addon.jei.essence_cell.chemical";
                        })));
    }
}
