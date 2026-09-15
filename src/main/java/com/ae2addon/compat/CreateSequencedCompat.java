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
     * @param allItems        基础原料 + 各步原料（物品集合，用于「原料齐不齐」）
     * @param stepItems       各步原料（不含基础原料，用于「装配次数」计数）
     * @param loops           装配次数
     * @param transitionalItem 过渡物品（序列中流转的「未完成品」；样板带它 = 这是一道「步骤」样板）
     * @param stepInputs      每一步的完整配方输入集合（含过渡物品），用于按步骤校验
     */
    public record Requirement(Set<Item> allItems, Set<Item> stepItems, int loops,
                              @org.jetbrains.annotations.Nullable Item transitionalItem,
                              List<Set<Item>> stepInputs,
                              List<List<Ingredient>> stepIngredients) {}

    private CreateSequencedCompat() {}

    /** 该配方是不是 Create 的序列装配（按运行时类名判定，无硬依赖） */
    public static boolean isSequencedAssembly(@Nullable Recipe<?> recipe) {
        return recipe != null && SEQUENCED_CLASS.equals(recipe.getClass().getName());
    }

    /**
     * 序列装配的「全链」归一化输入（2026-09-15 sensei：序列装配未适配）。
     *
     * @param baseIngredients 基础原料（{@code getIngredients()}）
     * @param stepIngredients 各步的完整原料（每步一组 Ingredient）
     * @param loops           装配圈数（每圈都会重跑一遍各步 → 各步原料要 ×loops）
     */
    public record Chain(List<Ingredient> baseIngredients, List<List<Ingredient>> stepIngredients, int loops) {}

    /**
     * 取「全链」输入：基础原料 + 各步原料（调用方按 loops 放大数量）。
     * <p>
     * 语义：千机是「无视流程的瞬间机」→ 把整条装配链的**全部材料**当输入、
     * 结果池（{@code resultPool}，带权重几率）当概率产出，一次搞定（不用逐步走）。
     */
    @Nullable
    public static Chain chain(@Nullable Recipe<?> recipe) {
        if (!isSequencedAssembly(recipe)) return null;
        try {
            var base = new ArrayList<Ingredient>(recipe.getIngredients());
            var stepIngredients = new ArrayList<List<Ingredient>>();
            Object sequence = readField(recipe, "sequence");
            if (sequence instanceof List<?> steps) {
                for (var step : steps) {
                    Object inner = invokeNoArg(step, "getRecipe");
                    if (!(inner instanceof Recipe<?> stepRecipe)) continue;
                    var raw = new ArrayList<Ingredient>(stepRecipe.getIngredients());
                    if (!raw.isEmpty()) stepIngredients.add(List.copyOf(raw));
                }
            }
            int loops = (int) recipe.getClass().getMethod("getLoops").invoke(recipe);
            return new Chain(List.copyOf(base), List.copyOf(stepIngredients), Math.max(1, loops));
        } catch (Throwable t) {
            return null; // 反射失败 → 调用方回退到标准路径
        }
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

            Item transitional = transitionalItem(recipe);
            var stepInputs = new ArrayList<Set<Item>>();
            var stepIngredients = new ArrayList<List<Ingredient>>();
            Object sequence = readField(recipe, "sequence");
            if (sequence instanceof List<?> steps) {
                for (var step : steps) {
                    Object inner = invokeNoArg(step, "getRecipe");
                    if (!(inner instanceof Recipe<?> stepRecipe)) continue;
                    var raw = new ArrayList<Ingredient>();
                    var inputs = new HashSet<Item>();
                    for (var ingredient : stepRecipe.getIngredients()) {
                        raw.add(ingredient);
                        collect(ingredient, inputs);
                    }
                    if (transitional != null) inputs.add(transitional);
                    if (!inputs.isEmpty()) stepInputs.add(Set.copyOf(inputs));
                    if (!raw.isEmpty()) stepIngredients.add(List.copyOf(raw));
                }
            }

            var all = new HashSet<>(base);
            all.addAll(stepItems);
            return new Requirement(Set.copyOf(all), Set.copyOf(stepItems), loops, transitional,
                    List.copyOf(stepInputs), List.copyOf(stepIngredients));
        } catch (Throwable t) {
            return null; // 反射失败 → 不做额外校验，避免误伤
        }
    }

    /** 过渡物品（未完成品）：字段 transitionalItem 是 protected ProcessingOutput，取 getStack() */
    @Nullable
    private static Item transitionalItem(Recipe<?> recipe) {
        try {
            Object output = readField(recipe, "transitionalItem");
            if (output == null) return null;
            Object stack = invokeNoArg(output, "getStack");
            if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) return itemStack.getItem();
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static Object readField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                var field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 沿父类继续找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static Object invokeNoArg(Object target, String method) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                var m = c.getDeclaredMethod(method);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // 沿父类继续找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void collect(@Nullable Ingredient ingredient, Set<Item> out) {
        if (ingredient == null) return;
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) out.add(stack.getItem());
        }
    }
}
