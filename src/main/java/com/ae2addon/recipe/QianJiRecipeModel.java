package com.ae2addon.recipe;

import com.ae2addon.compat.GregTechCompat;
import com.ae2addon.util.RecipeByproducts;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 归一化配方提取层（2026-09-15 sensei 定稿的"独立样板体系"第一层）。
 * <p>
 * 职责：把世界里的真实配方（GT 机器配方 / Create 加工 / 原版熔炉…）统一转成
 * {@link QianJiPatternData} —— 千机与 JEI 页共用同一份数据，不再各自反推。
 * <p>
 * 提取原则：
 * <ul>
 *   <li>输入：优先各 mod 专用提取器（GT 的 inputs 映射，保留标签语义）→ 退回标准 {@code getIngredients()}</li>
 *   <li>主产物：标准 {@code getResultItem()} + GT 的确定性产出（几率≥100%）</li>
 *   <li>概率产出：{@link RecipeByproducts} 抽取（GT chanced / Create rollable / 序列装配结果池 / Mekanism 副产）</li>
 * </ul>
 */
public final class QianJiRecipeModel {

    /** 每个输入槽最多记多少可选物品（标签展开可能上千，截断） */
    private static final int MAX_OPTIONS_PER_SLOT = 32;

    private QianJiRecipeModel() {}

    /** 世界里的「可处理配方」（有产出即可；供 JEI 页与指令列举） */
    public static List<Recipe<?>> candidates(Level level) {
        var out = new ArrayList<Recipe<?>>();
        for (var recipe : level.getRecipeManager().getRecipes()) {
            if (recipe == null) continue;
            if (outputsOf(recipe, level).isEmpty() && RecipeByproducts.extract(recipe, level).isEmpty()) continue;
            out.add(recipe);
        }
        return out;
    }

    /** 真实配方 → 我们的样板数据 */
    @Nullable
    public static QianJiPatternData fromRecipe(Recipe<?> recipe, Level level) {
        if (recipe == null) return null;

        // ── 输入槽 ──
        var inputs = new ArrayList<QianJiPatternData.Slot>();
        for (var ingredient : rawInputIngredients(recipe)) {
            var options = new ArrayList<Item>();
            int count = 1;
            for (ItemStack stack : ingredient.getItems()) {
                if (stack.isEmpty()) continue;
                if (options.isEmpty()) count = Math.max(1, stack.getCount());
                if (!options.contains(stack.getItem())) options.add(stack.getItem());
                if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
            }
            if (!options.isEmpty()) inputs.add(new QianJiPatternData.Slot(options, count));
        }

        // ── 主产物：标准结果 + GT 的确定性产出 ──
        var primary = new ArrayList<QianJiPatternData.Out>();
        ItemStack standard = recipe.getResultItem(level.registryAccess());
        if (!standard.isEmpty()) {
            primary.add(new QianJiPatternData.Out(standard.getItem(), standard.getCount()));
        }
        for (var chanced : GregTechCompat.itemOutputs(recipe)) {
            if (chanced.stack().isEmpty()) continue;
            if (chanced.chance() >= 1f) {
                primary.add(new QianJiPatternData.Out(chanced.stack().getItem(), chanced.stack().getCount()));
            }
        }

        // ── 概率产出 ──
        var chanced = new ArrayList<QianJiPatternData.Chanced>();
        for (var bp : RecipeByproducts.extract(recipe, level)) {
            if (bp.stack().isEmpty()) continue;
            chanced.add(new QianJiPatternData.Chanced(bp.stack().getItem(), bp.stack().getCount(),
                    bp.chance() > 0f ? bp.chance() : -1f));
        }

        if (inputs.isEmpty() && primary.isEmpty() && chanced.isEmpty()) return null;

        ResourceLocation id = recipe.getId();
        return new QianJiPatternData(String.valueOf(recipe.getType()),
                id == null ? "" : id.toString(), inputs, primary, chanced);
    }

    /** 配方的输入 Ingredient 列表（GT 走 inputs 映射并保留标签语义） */
    public static List<Ingredient> rawInputIngredients(Recipe<?> recipe) {
        if (GregTechCompat.isGtRecipe(recipe)) {
            var gt = GregTechCompat.itemInputIngredients(recipe);
            if (!gt.isEmpty()) return gt;
        }
        return recipe.getIngredients();
    }

    /** 产出物品集合（标准 + GT） */
    private static Set<Item> outputsOf(Recipe<?> recipe, Level level) {
        var items = new HashSet<Item>();
        ItemStack standard = recipe.getResultItem(level.registryAccess());
        if (!standard.isEmpty()) items.add(standard.getItem());
        for (var c : GregTechCompat.itemOutputs(recipe)) {
            if (!c.stack().isEmpty()) items.add(c.stack().getItem());
        }
        return items;
    }
}
