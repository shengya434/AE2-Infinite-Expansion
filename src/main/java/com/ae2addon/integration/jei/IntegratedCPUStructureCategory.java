package com.ae2addon.integration.jei;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.multiblock.MultiblockPreviewDef;
import com.ae2addon.block.multiblock.MultiblockPreviewDefs;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;

/** One JEI structure category per definition, sharing a single preview widget. */
public final class IntegratedCPUStructureCategory implements IRecipeCategory<IntegratedCPUStructureCategory.StructureRecipe> {
    public record StructureRecipe() {}

    public static final RecipeType<StructureRecipe> TYPE =
            RecipeType.create(AE2Addon.MODID, "integrated_cpu_structure", StructureRecipe.class);
    public static final RecipeType<StructureRecipe> NOEXPAND_TYPE =
            RecipeType.create(AE2Addon.MODID, "integrated_cpu_structure_noexpand", StructureRecipe.class);
    public static final RecipeType<StructureRecipe> QIANJI_TYPE =
            RecipeType.create(AE2Addon.MODID, "qianji_structure", StructureRecipe.class);
    public static final RecipeType<StructureRecipe> DRIVE_TYPE =
            RecipeType.create(AE2Addon.MODID, "infinite_drive_structure", StructureRecipe.class);

    private final MultiblockPreviewDef definition;
    private final RecipeType<StructureRecipe> type;
    private final IDrawable background;
    private final IDrawable icon;

    public IntegratedCPUStructureCategory(IGuiHelper helper, MultiblockPreviewDef definition,
                                          RecipeType<StructureRecipe> type) {
        this.definition = definition;
        this.type = type;
        background = helper.createBlankDrawable(240, 320);
        icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK,
                definition.icon());
    }

    public static List<IntegratedCPUStructureCategory> all(IGuiHelper helper) {
        var expanded = MultiblockPreviewDefs.INTEGRATED_CPU;
        var noExpand = MultiblockPreviewDefs.INTEGRATED_CPU_NOEXPAND;
        AE2Addon.LOGGER.info("[ae2addon] 集成CPU结构预览自检: 含拓展={}x{}x{} / {}种材料 / {}块; "
                        + "无拓展={}x{}x{} / {}种材料 / {}块",
                expanded.width(), expanded.height(), expanded.depth(),
                expanded.materialCounts().size(), expanded.placementCount(),
                noExpand.width(), noExpand.height(), noExpand.depth(),
                noExpand.materialCounts().size(), noExpand.placementCount());
        return List.of(
                new IntegratedCPUStructureCategory(helper, expanded, TYPE),
                new IntegratedCPUStructureCategory(helper, noExpand, NOEXPAND_TYPE),
                new IntegratedCPUStructureCategory(helper, MultiblockPreviewDefs.QIANJI, QIANJI_TYPE),
                new IntegratedCPUStructureCategory(helper, MultiblockPreviewDefs.INFINITE_DRIVE, DRIVE_TYPE));
    }

    @Override
    public RecipeType<StructureRecipe> getRecipeType() {
        return type;
    }

    @Override
    public net.minecraft.network.chat.Component getTitle() {
        return definition.title();
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
    public void setRecipe(IRecipeLayoutBuilder builder, StructureRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.OUTPUT, 216, 4)
                .addItemStack(definition.icon());

        var materials = definition.materialCounts().entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<net.minecraft.world.level.block.Block, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .reversed()
                        .thenComparing(entry -> BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString()))
                .toList();
        for (int i = 0; i < materials.size(); i++) {
            var entry = materials.get(i);
            var item = entry.getKey().asItem();
            int count = entry.getValue();
            // ⚠ 2026-09-25 修正：这里原来写的是 new ItemStack(item, Math.min(count, item.getMaxStackSize()))，
            // 于是每种材料最多只显示 64（如石英块 586 → 显示 64）。
            // 但那个 64 的上限是**我们自己夹的**，不是 JEI/原版的限制：
            // 1.20.1 的 ItemStack 直接把 count 存进 int 字段，不按 maxStackSize 夹；
            // JEI 侧 DisplayIngredientAcceptor 不碰 count，角标走原版 renderItemDecorations
            // → String.valueOf(stack.getCount())，数量多少就画多少（右对齐、往左溢出）。
            // 所以直接传真实数量，角标就会显示 586，不再需要额外自绘。
            var stack = new ItemStack(item, count);
            builder.addSlot(RecipeIngredientRole.INPUT, 8 + i % 8 * 18, 218 + i / 8 * 32)
                    .addItemStack(stack)
                    .addRichTooltipCallback((slot, tooltip) -> tooltip.add(
                            net.minecraft.network.chat.Component.translatable("gui.ae2addon.jei.cpu.need", count)));
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, StructureRecipe recipe, IFocusGroup focuses) {
        var preview = new IntegratedCPUStructurePreview(new ScreenPosition(0, 0), definition);
        builder.addWidget(preview);
        builder.addInputHandler(preview);

        var counts = definition.materialCounts();
        var top = counts.entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<net.minecraft.world.level.block.Block, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .reversed())
                .limit(5)
                .map(entry -> BuiltInRegistries.BLOCK.getKey(entry.getKey()) + "=" + entry.getValue())
                .toList();
        AE2Addon.LOGGER.info("[ae2addon] JEI {} 结构: {} 种材料, {} 块, 前五: {}",
                definition.id(), counts.size(), definition.placementCount(), top);
    }
}
