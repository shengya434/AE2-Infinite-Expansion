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
    public record Chain(List<Ingredient> baseIngredients, List<List<Ingredient>> stepIngredients, int loops,
                        java.util.Set<Item> redundantItems) {
        /** 兼容旧调用：不带「中间产物物品集合」 */
        public Chain(List<Ingredient> baseIngredients, List<List<Ingredient>> stepIngredients, int loops) {
            this(baseIngredients, stepIngredients, loops, java.util.Set.of());
        }
    }

    /**
     * 取「全链」输入：基础原料 + 各步**机器施加的原料**（调用方按 loops 放大数量）。
     * <p>
     * 语义：千机是「无视流程的瞬间机」→ 把整条装配链的**全部材料**当输入、
     * 结果池（{@code resultPool}，带权重几率）当概率产出，一次搞定（不用逐步走）。
     * <p>
     * ⚠ 2026-09-15 sensei：「不管中间步骤，只在意**装配次数**与**装配原料（机械手上的）**」——
     * 所以各步原料里要**剔除过渡物品**（{@code incomplete_*} 那类半成品）：它是流程中间产物、
     * 且实例带 NBT，当样板输入既无意义又匹配不上。
     */
    @Nullable
    public static Chain chain(@Nullable Recipe<?> recipe) {
        return chain(recipe, null);
    }

    /**
     * 同上，但能拿到 {@link net.minecraft.core.RegistryAccess}：用它取**每一步自己的产出物品**
     * （过渡物品/半成品）—— 这是剔除中间产物最可靠的一条依据。
     */
    @Nullable
    public static Chain chain(@Nullable Recipe<?> recipe, @Nullable net.minecraft.core.RegistryAccess access) {
        if (!isSequencedAssembly(recipe)) return null;
        try {
            // ⚠ 基础原料取**字段 ingredient**，不能用 getIngredients() ——
            // Create 的 getIngredients() = 基础 + addAdditionalIngredientsAndMachines(各步原料)，
            // 用它会把各步原料算两遍（2026-09-15 sensei 实测：金板/齿轮数量对不上）
            var base = new ArrayList<Ingredient>();
            Object baseIngredient = readField(recipe, "ingredient");
            if (baseIngredient instanceof Ingredient ing) {
                base.add(ing);
            } else {
                base.addAll(recipe.getIngredients());
            }
            var stepIngredients = new ArrayList<List<Ingredient>>();
            var rawSteps = new ArrayList<List<Ingredient>>();
            Object sequence = readField(recipe, "sequence");
            if (sequence instanceof List<?> steps) {
                for (var step : steps) {
                    Object inner = invokeNoArg(step, "getRecipe");
                    if (!(inner instanceof Recipe<?> stepRecipe)) continue;
                    rawSteps.add(new ArrayList<Ingredient>(stepRecipe.getIngredients()));
                }
            }
            // 过渡物品（半成品）集合：① 字段 ② **每步自己的产出物品**（最可靠） ③ 每步都出现的原料交集
            var redundant = new java.util.HashSet<Item>();
            Item fieldTransitional = transitionalItem(recipe);
            if (fieldTransitional != null) redundant.add(fieldTransitional);
            if (access != null && sequence instanceof List<?> steps2) {
                for (var step : steps2) {
                    Object inner = invokeNoArg(step, "getRecipe");
                    if (!(inner instanceof Recipe<?> stepRecipe)) continue;
                    try {
                        ItemStack stepOut = stepRecipe.getResultItem(access);
                        if (stepOut != null && !stepOut.isEmpty()) redundant.add(stepOut.getItem());
                    } catch (Throwable ignored) {
                        // 取不到就算，靠另两条依据
                    }
                }
            }
            if (rawSteps.size() >= 2) {
                java.util.Set<Item> common = null;
                for (var stepIngredientsRaw : rawSteps) {
                    var items = new java.util.HashSet<Item>();
                    for (var ingredient : stepIngredientsRaw) {
                        for (ItemStack stack : ingredient.getItems()) {
                            if (!stack.isEmpty()) items.add(stack.getItem());
                        }
                    }
                    if (common == null) {
                        common = items;
                    } else {
                        common.retainAll(items);
                    }
                }
                if (common != null) redundant.addAll(common);
            }
            for (var stepIngredientsRaw : rawSteps) {
                var raw = new ArrayList<Ingredient>();
                for (var ingredient : stepIngredientsRaw) {
                    if (isRedundant(ingredient, redundant)) continue;
                    raw.add(ingredient);
                }
                if (!raw.isEmpty()) stepIngredients.add(List.copyOf(raw));
            }
            int loops = (int) recipe.getClass().getMethod("getLoops").invoke(recipe);
            return new Chain(List.copyOf(base), List.copyOf(stepIngredients), Math.max(1, loops),
                    java.util.Set.copyOf(redundant));
        } catch (Throwable t) {
            return null; // 反射失败 → 调用方回退到标准路径
        }
    }

    /**
     * 步骤样板素材（2026-09-15 sensei 选：序列装配要出手「步骤样板」）。
     *
     * @param transitionalItem 过渡物品（未完成品）
     * @param stepIngredients  各步原料
     * @param loops            装配圈数
     */
    public record StepPlan(@Nullable Item transitionalItem, List<List<Ingredient>> stepIngredients, int loops) {}

    /**
     * 取步骤样板素材（无过渡物品时返回 null —— 那就只能出「全链」一种样板）。
     */
    @Nullable
    public static StepPlan stepPlan(@Nullable Recipe<?> recipe) {
        var chain = chain(recipe);
        if (chain == null) return null;
        Item transitional = transitionalItem(recipe);
        if (transitional == null) return null;
        return new StepPlan(transitional, chain.stepIngredients(), chain.loops());
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

    /**
     * 这个 Ingredient 是否整个落在「过渡物品集合」里——是则它不是玩家要供的原料。
     * 过渡物品由序列自己产生，实例带 NBT，当样板输入既无意义也匹配不上。
     */
    private static boolean isRedundant(Ingredient ingredient, java.util.Set<Item> redundant) {
        if (ingredient == null || redundant.isEmpty()) return false;
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) return false;
        for (ItemStack stack : items) {
            if (stack.isEmpty() || !redundant.contains(stack.getItem())) return false;
        }
        return true;
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
