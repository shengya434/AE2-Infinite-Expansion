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

    /** GT 的输入槽（每个 Content 一个槽；物品保留 Ingredient 语义，流体取 FluidIngredient.getStacks()） */
    public static List<List<appeng.api.stacks.GenericStack>> inputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<appeng.api.stacks.GenericStack>>();
        for (var info : inputSlotInfos(recipe)) {
            slots.add(info.options());
        }
        return slots;
    }

    /**
     * 一条输入槽：候选 + 是否被消耗。
     *
     * @param consumed {@code false} = GT 的 {@code notConsumable()} —— **催化剂/模具/工具**，
     *                 机器不会消耗它（GT 内部的实现就是“把这条输入的 chance 置 0”）
     */
    public record InputSlot(List<appeng.api.stacks.GenericStack> options, boolean consumed) {}

    /** 带「是否消耗」信息的输入槽（2026-09-15：识别 notConsumable 输入，见 {@link InputSlot}） */
    public static List<InputSlot> inputSlotInfos(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<InputSlot>();
        for (var entry : entries(recipe, "inputs")) {
            var options = toStacks(entry.typed(), false);
            if (options.isEmpty()) continue;
            // 输入的 chance：正常输入 = maxChance（全部），notConsumable 输入 = 0
            boolean consumed = chanceOf(entry.content()) != 0f;
            slots.add(new InputSlot(List.copyOf(options), consumed));
        }
        return slots;
    }

    /** GT 的全部产出（物品 + 流体，带几率） */
    public static List<Stat> outputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        for (var entry : entries(recipe, "outputs")) {
            float chance = chanceOf(entry.content());
            for (var stack : toStacks(entry.typed(), true)) {
                out.add(new Stat(stack, chance));
            }
        }
        return out;
    }

    /** 能力条目：capability 实例 + Content + 经 {@code capability.of(...)} 转换后的对象 */
    private record Entry(Object capability, Object content, Object typed) {}

    /**
     * 把 GTRecipe 的 inputs/outputs 映射展平为条目（物品与流体能力都要）。
     * <p>
     * ⚠ 2026-09-15 修复：Content 内部对象的**真实类型不能靠猜** ——
     * GT 自己走 {@code RecipeCapability.of(Object)} 转换（流体能力下会转成 FluidIngredient）。
     * 之前只识别名字里带 FluidIngredient 的对象，结果流体一个都没抽到。
     */
    private static List<Entry> entries(@Nullable Recipe<?> recipe, String fieldName) {
        var out = new ArrayList<Entry>();
        if (!isGtRecipe(recipe)) return out;
        try {
            Object mapValue = recipe.getClass().getField(fieldName).get(recipe);
            if (!(mapValue instanceof Map<?, ?> map)) return out;
            for (var rawEntry : map.entrySet()) {
                Object capability = rawEntry.getKey();
                if (capability == null) continue;
                String capName = capability.getClass().getSimpleName();
                if (!capName.contains("Item") && !capName.contains("Fluid")) continue;
                if (!(rawEntry.getValue() instanceof List<?> contents)) continue;
                for (var content : contents) {
                    Object inner = contentInner(content);
                    out.add(new Entry(capability, content, convert(capability, inner)));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 调 {@code capability.of(inner)}（GT 自己的转换入口） */
    private static Object convert(Object capability, @Nullable Object inner) {
        if (inner == null) return null;
        try {
            return capability.getClass().getMethod("of", Object.class).invoke(capability, inner);
        } catch (Throwable ignored) {
            return inner;
        }
    }

    /** 转换后的对象 → GenericStack 列表（物品 Ingredient / 流体 FluidIngredient / 直接栈） */
    private static List<appeng.api.stacks.GenericStack> toStacks(@Nullable Object typed, boolean outputs) {
        var out = new ArrayList<appeng.api.stacks.GenericStack>();
        if (typed == null) return out;

        if (typed instanceof Ingredient ingredient) {
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty()) out.add(appeng.api.stacks.GenericStack.fromItemStack(stack));
            }
            return out;
        }
        if (typed instanceof ItemStack stack) {
            if (!stack.isEmpty()) out.add(appeng.api.stacks.GenericStack.fromItemStack(stack));
            return out;
        }
        if (typed instanceof net.minecraftforge.fluids.FluidStack stack) {
            if (!stack.isEmpty()) out.add(appeng.api.stacks.GenericStack.fromFluidStack(stack));
            return out;
        }
        if (typed instanceof net.minecraftforge.fluids.FluidStack[] stacks) {
            for (var stack : stacks) {
                if (stack != null && !stack.isEmpty()) out.add(appeng.api.stacks.GenericStack.fromFluidStack(stack));
            }
            return out;
        }
        // FluidIngredient（GT 流体能力）：getStacks() 是公开入口（stacks 字段也可，但用它更稳）
        if (typed.getClass().getName().contains("FluidIngredient")) {
            Object stacks = invokeNoArgPublic(typed, "getStacks");
            if (stacks instanceof net.minecraftforge.fluids.FluidStack[] arr) {
                for (var stack : arr) {
                    if (stack != null && !stack.isEmpty())
                        out.add(appeng.api.stacks.GenericStack.fromFluidStack(stack));
                }
            }
        }
        return out;
    }

    @Nullable
    private static Object invokeNoArgPublic(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 诊断用：把一条配方每个能力条目的类型摊开（probe 命令用） */
    public static List<String> describeEntries(@Nullable Recipe<?> recipe, String fieldName) {
        var lines = new ArrayList<String>();
        for (var entry : entries(recipe, fieldName)) {
            String innerName = entry.content() == null ? "?" : String.valueOf(contentInner(entry.content()));
            int innerIdx = innerName.lastIndexOf('@');
            if (innerIdx > 0) innerName = innerName.substring(0, innerIdx);
            lines.add(entry.capability().getClass().getSimpleName()
                    + " | 内容=" + shortName(entry.content())
                    + " | 转后=" + shortName(entry.typed())
                    + " | 候选=" + toStacks(entry.typed(), true).size());
        }
        return lines;
    }

    private static String shortName(@Nullable Object o) {
        if (o == null) return "null";
        String cls = o.getClass().getName();
        return cls.substring(cls.lastIndexOf('.') + 1);
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
