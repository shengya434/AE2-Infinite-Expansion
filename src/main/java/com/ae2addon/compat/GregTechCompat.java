package com.ae2addon.compat;

import com.ae2addon.util.RecipeByproducts;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GregTech（gtceu）配方适配 —— 反射读取，零硬依赖。
 * <p>
 * 为什么需要：GT 的配方**不实现标准输出 API** —— {@code GTRecipe.getResultItem()} 返回空，
 * 物品产出全在 {@code GTRecipe.outputs}（{@code Map<RecipeCapability<?>, List<Content>>}）里，
 * 且每条产出带独立几率（{@code Content.chance / maxChance}，单位 1/10000）。
 * 千机原来的索引只认 {@code getResultItem()} → GT 产出的物品**一个都进不了索引**
 * （症状：样板报「洁净铂粉无匹配配方」，2026-09-15 sensei 实测）。
 * <p>
 * API 依据（gtceu 1.20.1-7.5.3 实机核验）：
 * <ul>
 *   <li>{@code GTRecipe.inputs / outputs}：public final Map，键为 RecipeCapability（物品能力类名含 Item）</li>
 *   <li>{@code Content.content}：物品能力下是 {@code Ingredient}（其 ItemStack 带数量）</li>
 *   <li>{@code Content.chance / maxChance}：int，几率 = chance / {@code ChanceLogic.getMaxChancedValue()}</li>
 * </ul>
 * 任何一步反射失败都返回空集合（= 不做额外处理），宁可漏也不误伤。
 */
public final class GregTechCompat {

    private static final String GT_RECIPE_CLASS = "com.gregtechceu.gtceu.api.recipe.GTRecipe";
    /** 几率上限（100%）：ChanceLogic.getMaxChancedValue()，取不到时用 10000 */
    private static final float MAX_CHANCE = gtMaxChance();

    private GregTechCompat() {}

    public static boolean isGtRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && GT_RECIPE_CLASS.equals(recipe.getClass().getName());
    }

    // ══════════════════════════════════════════════════════════
    //  通用提取（物品 + 流体，2026-09-15 补：原来只读物品能力 → 漏流体）
    // ══════════════════════════════════════════════════════════

    /** 一条输入/产出：GenericStack（物品或流体）+ 几率（0..1；<0 = 未声明） */
    public record Stat(appeng.api.stacks.GenericStack stack, float chance) {}

    /** GT 的输入槽（每个 Content 一个槽；物品保留 Ingredient 语义，流体保留 FluidIngredient 选项） */
    public static List<List<appeng.api.stacks.GenericStack>> inputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<appeng.api.stacks.GenericStack>>();
        for (var content : allContents(recipe, "inputs", false)) {
            var options = new ArrayList<appeng.api.stacks.GenericStack>();
            Object inner = contentInner(content);
            if (inner instanceof Ingredient ingredient) {
                for (ItemStack stack : ingredient.getItems()) {
                    if (!stack.isEmpty()) options.add(appeng.api.stacks.GenericStack.fromItemStack(stack));
                }
            } else if (inner != null && inner.getClass().getName().contains("FluidIngredient")) {
                for (var fs : fluidStacksOf(inner)) {
                    if (fs != null && !fs.isEmpty()) options.add(appeng.api.stacks.GenericStack.fromFluidStack(fs));
                }
            }
            if (!options.isEmpty()) slots.add(List.copyOf(options));
        }
        return slots;
    }

    /** GT 的全部产出（物品 + 流体，带几率） */
    public static List<Stat> outputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        for (var content : allContents(recipe, "outputs", true)) {
            Object inner = contentInner(content);
            float chance = chanceOf(content);
            if (inner instanceof Ingredient ingredient) {
                for (ItemStack stack : ingredient.getItems()) {
                    if (!stack.isEmpty()) out.add(new Stat(appeng.api.stacks.GenericStack.fromItemStack(stack), chance));
                }
            } else if (inner instanceof ItemStack stack) {
                if (!stack.isEmpty()) out.add(new Stat(appeng.api.stacks.GenericStack.fromItemStack(stack), chance));
            } else if (inner != null && inner.getClass().getName().contains("FluidIngredient")) {
                for (var fs : fluidStacksOf(inner)) {
                    if (fs != null && !fs.isEmpty()) {
                        out.add(new Stat(appeng.api.stacks.GenericStack.fromFluidStack(fs), chance));
                    }
                }
            }
        }
        return out;
    }

    /** 取 Content 里的对象（物品是 Ingredient，流体是 FluidIngredient） */
    @Nullable
    private static Object contentInner(Object content) {
        try {
            return content.getClass().getField("content").get(content);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** FluidIngredient 的候选流体押（public FluidStack[] stacks 字段） */
    private static net.minecraftforge.fluids.FluidStack[] fluidStacksOf(Object fluidIngredient) {
        try {
            Object value = fluidIngredient.getClass().getField("stacks").get(fluidIngredient);
            if (value instanceof net.minecraftforge.fluids.FluidStack[] stacks) return stacks;
        } catch (Throwable ignored) {
        }
        return new net.minecraftforge.fluids.FluidStack[0];
    }

    /** 把 GTRecipe 的 inputs/outputs 映射展平为 Content 列表（物品与流体能力都要） */
    private static List<Object> allContents(@Nullable Recipe<?> recipe, String fieldName, boolean withChance) {
        var out = new ArrayList<Object>();
        if (!isGtRecipe(recipe)) return out;
        try {
            Object mapValue = recipe.getClass().getField(fieldName).get(recipe);
            if (!(mapValue instanceof Map<?, ?> map)) return out;
            for (var entry : map.entrySet()) {
                Object capability = entry.getKey();
                if (capability == null) continue;
                String capName = capability.getClass().getSimpleName();
                if (!capName.contains("Item") && !capName.contains("Fluid")) continue;
                if (!(entry.getValue() instanceof List<?> contents)) continue;
                out.addAll(contents);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** GT 物品输出（chance: 0..1；<0 表示没读到几率） */
    public static List<RecipeByproducts.Chanced> itemOutputs(@Nullable Recipe<?> recipe) {
        return itemContents(recipe, "outputs", true);
    }

    /** GT 物品输入物品集合（用 Ingredient 展开） */
    public static Set<Item> itemInputs(@Nullable Recipe<?> recipe) {
        var items = new HashSet<Item>();
        for (var chanced : itemContents(recipe, "inputs", false)) {
            if (!chanced.stack().isEmpty()) items.add(chanced.stack().getItem());
        }
        return items;
    }

    /**
     * GT 物品输入**原始 Ingredient 列表**（保留标签语义，供逐 Ingredient 命中判定）。
     * 为什么要它：若把标签展开成“全部成员”再要求样板包含每一项，
     * 带标签（如任意铁板）的合法样板会被误拒。
     */
    public static List<Ingredient> itemInputIngredients(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<Ingredient>();
        if (!isGtRecipe(recipe)) return out;
        try {
            Object mapValue = recipe.getClass().getField("inputs").get(recipe);
            if (!(mapValue instanceof Map<?, ?> map)) return out;
            for (var entry : map.entrySet()) {
                Object capability = entry.getKey();
                if (capability == null || !capability.getClass().getSimpleName().contains("Item")) continue;
                if (!(entry.getValue() instanceof List<?> contents)) continue;
                for (var content : contents) {
                    Object inner = content.getClass().getField("content").get(content);
                    if (inner instanceof Ingredient ingredient) out.add(ingredient);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 读 GTRecipe 的 inputs/outputs 里的**物品**能力内容。
     *
     * @param withChance true=按输出语义给几率；false=输入（几率无意义，回 -1）
     */
    private static List<RecipeByproducts.Chanced> itemContents(@Nullable Recipe<?> recipe, String fieldName,
                                                                boolean withChance) {
        var out = new ArrayList<RecipeByproducts.Chanced>();
        if (!isGtRecipe(recipe)) return out;
        try {
            Object mapValue = recipe.getClass().getField(fieldName).get(recipe);
            if (!(mapValue instanceof Map<?, ?> map)) return out;
            for (var entry : map.entrySet()) {
                Object capability = entry.getKey();
                if (capability == null) continue;
                if (!capability.getClass().getSimpleName().contains("Item")) continue; // 只要物品能力
                if (!(entry.getValue() instanceof List<?> contents)) continue;
                for (var content : contents) {
                    ItemStack stack = stackOf(content);
                    if (stack == null || stack.isEmpty()) continue;
                    out.add(new RecipeByproducts.Chanced(stack, withChance ? chanceOf(content) : -1f));
                }
            }
        } catch (Throwable ignored) {
            // 反射失败 → 空集合（不做额外处理）
        }
        return out;
    }

    @Nullable
    private static ItemStack stackOf(Object content) {
        try {
            Object inner = content.getClass().getField("content").get(content);
            if (inner instanceof ItemStack stack) return stack;
            if (inner instanceof Ingredient ingredient && ingredient.getItems().length > 0) {
                return ingredient.getItems()[0];
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static float chanceOf(Object content) {
        try {
            int chance = content.getClass().getField("chance").getInt(content);
            return Math.min(1f, chance / MAX_CHANCE);
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    private static float gtMaxChance() {
        try {
            Class<?> logic = Class.forName("com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic");
            Object value = logic.getMethod("getMaxChancedValue").invoke(null);
            if (value instanceof Number n && n.floatValue() > 0) return n.floatValue();
        } catch (Throwable ignored) {
        }
        return 10000f;
    }
}
