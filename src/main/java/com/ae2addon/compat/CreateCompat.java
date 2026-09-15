package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create（机械动力）加工配方兼容层 —— **反射实现，零硬依赖**。
 * <p>
 * 2026-09-15 sensei「继续做配方兼容」：Create 的**物品**侧本来就走标准路径
 * （{@code getIngredients()} + {@code getResultItem()} + 概率产出探测），
 * 但**流体**另有出口，标准 API 完全看不到：
 * <pre>
 *   FluidIngredient[] getFluidIngredients()   ← 搅拌/混合/装桶要耗的流体（如 100mB 水）
 *   FluidStack[]     getFluidResults()        ← 产出的流体（如 500mB 巧克力）
 * </pre>
 * 只按标准路径提取 → 样板**缺流体输入槽**、**少流体产出**（GT 当年同一类坑）。
 * 这里把这两个方法补上；几率产出（{@code getRollableResults()}）已由
 * {@link com.ae2addon.util.RecipeByproducts} 负责，不重复。
 */
public final class CreateCompat {

    private static final String PROCESSING_RECIPE = "com.simibubi.create.content.processing.recipe.ProcessingRecipe";

    private static final Map<Class<?>, Boolean> IS_CREATE = new ConcurrentHashMap<>();

    private CreateCompat() {}

    /** 是不是 Create 的加工配方（ProcessingRecipe 及其子类：粉碎/洗涤/喷溅/搅拌/压实/压片/注液…） */
    public static boolean isCreateRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_CREATE.computeIfAbsent(recipe.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                if (PROCESSING_RECIPE.equals(k.getName())) return true;
            }
            return false;
        });
    }

    /** 流体输入槽（每个 FluidIngredient 一槽，候选 = 该原料匹配到的流体，数量 = getRequiredAmount） */
    public static List<List<GenericStack>> fluidInputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        for (Object ingredient : asList(invoke(recipe, "getFluidIngredients"))) {
            if (ingredient == null) continue;
            long amount = number(invoke(ingredient, "getRequiredAmount"), -1L);
            var options = new ArrayList<GenericStack>();
            for (Object fluid : asList(invoke(ingredient, "getMatchingFluidStacks"))) {
                GenericStack stack = toFluid(fluid, amount);
                if (stack == null) continue;
                boolean dup = false;
                for (var existing : options) {
                    if (existing.what().equals(stack.what())) { dup = true; break; }
                }
                if (!dup) options.add(stack);
                if (options.size() >= 32) break;
            }
            if (!options.isEmpty()) slots.add(List.copyOf(options));
        }
        return slots;
    }

    /** 流体产出（确定产出；Create 的流体产出没有几率字段） */
    public static List<GenericStack> fluidOutputs(Recipe<?> recipe) {
        var out = new ArrayList<GenericStack>();
        for (Object fluid : asList(invoke(recipe, "getFluidResults"))) {
            GenericStack stack = toFluid(fluid, -1L);
            if (stack != null) out.add(stack);
        }
        return out;
    }

    // ── 反射工具 ──

    @Nullable
    private static GenericStack toFluid(@Nullable Object value, long amount) {
        if (value instanceof FluidStack fluid && !fluid.isEmpty()) {
            long use = amount > 0 ? amount : Math.max(1, fluid.getAmount());
            return new GenericStack(AEFluidKey.of(fluid.getFluid()), use);
        }
        if (value instanceof ItemStack item && !item.isEmpty()) {
            return new GenericStack(appeng.api.stacks.AEItemKey.of(item),
                    amount > 0 ? amount : Math.max(1, item.getCount()));
        }
        return null;
    }

    private static List<?> asList(@Nullable Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list;
        if (value instanceof Iterable<?> it) {
            var list = new ArrayList<>();
            for (Object o : it) list.add(o);
            return list;
        }
        return List.of(value);
    }

    @Nullable
    private static Object invoke(@Nullable Object target, String methodName) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (!methodName.equals(m.getName()) || m.getParameterCount() != 0) continue;
                try {
                    return m.invoke(target);
                } catch (Throwable ignored) {
                    // 换同名重载再试
                }
            }
        }
        return null;
    }

    private static long number(@Nullable Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }
}
