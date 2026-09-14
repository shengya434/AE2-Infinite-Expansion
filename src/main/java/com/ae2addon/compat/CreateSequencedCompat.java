package com.ae2addon.compat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Create「序列装配」（Sequenced Assembly）适配 —— 反射读取，零硬依赖。
 * <p>
 * 为什么需要：{@code SequencedAssemblyRecipe#getIngredients()} 只报**基础原料**，
 * 装配链上每一步的原料（以及装配次数 {@code loops}）都不在里面。千机只按
 * getIngredients() 校验的话，「1 份原料 → 1 份成品」的样板会被放行——等于跳过
 * 整个装配流程白拿成品（2026-09-14 sensei 实测）。
 * <p>
 * 取法（Create 6.0.8 实机核验）：
 * <ul>
 *   <li>各步原料：{@code addAdditionalIngredientsAndMachines(List<Ingredient>)}（公开方法，往传入列表里补）</li>
 *   <li>装配次数：{@code getLoops()}</li>
 * </ul>
 * 任何一步反射失败都返回 null（= 不做额外校验），宁可漏拦也不误伤。
 */
public final class CreateSequencedCompat {

    private static final String SEQUENCED_CLASS =
            "com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe";

    /**
     * 序列装配的完整需求。
     *
     * @param allItems  基础原料 + 各步原料（物品集合，用于「原料齐不齐」）
     * @param stepItems 各步原料（不含基础原料，用于「装配次数」计数）
     * @param loops     装配次数
     */
    public record Requirement(Set<Item> allItems, Set<Item> stepItems, int loops) {}

    private CreateSequencedCompat() {}

    /** 该配方是不是 Create 的序列装配（按运行时类名判定，无硬依赖） */
    public static boolean isSequencedAssembly(@Nullable Recipe<?> recipe) {
        return recipe != null && SEQUENCED_CLASS.equals(recipe.getClass().getName());
    }

    @Nullable
    public static Requirement requirement(@Nullable Recipe<?> recipe) {
        if (!isSequencedAssembly(recipe)) return null;
        try {
            var base = new HashSet<Item>();
            for (var ingredient : recipe.getIngredients()) {
                collect(ingredient, base);
            }

            var stepItems = new HashSet<Item>();
            var additional = new ArrayList<Ingredient>();
            recipe.getClass().getMethod("addAdditionalIngredientsAndMachines", List.class)
                    .invoke(recipe, additional);
            for (var ingredient : additional) {
                collect(ingredient, stepItems);
            }

            int loops = (int) recipe.getClass().getMethod("getLoops").invoke(recipe);

            var all = new HashSet<>(base);
            all.addAll(stepItems);
            return new Requirement(Set.copyOf(all), Set.copyOf(stepItems), loops);
        } catch (Throwable t) {
            return null; // 反射失败 → 不做额外校验，避免误伤
        }
    }

    private static void collect(@Nullable Ingredient ingredient, Set<Item> out) {
        if (ingredient == null) return;
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) out.add(stack.getItem());
        }
    }
}
