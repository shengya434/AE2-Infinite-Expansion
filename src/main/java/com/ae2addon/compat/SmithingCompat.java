package com.ae2addon.compat;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原版**锻造台**（{@code net.minecraft.world.item.crafting.SmithingTransformRecipe}）兼容。
 * <p>
 * 2026-09-17 sensei 的清单里，smithing 有 41 条被判「无输入」（产物读得到，原料一个都没有）。
 * 读字节码实证：这个类**没有覆写 {@code getIngredients()}**（父接口给了个空实现），
 * 真正的原料在三个字段里：
 * <pre>
 *   template(Ingredient)  base(Ingredient)  addition(Ingredient)  result(ItemStack)
 * </pre>
 * 所以只能读字段补输入槽。三样在合成时都被消耗（1.20 的升级模板是真被吃掉的），一律按消耗品处理。
 */
public final class SmithingCompat {

    private static final String TRANSFORM =
            "net.minecraft.world.item.crafting.SmithingTransformRecipe";

    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, Boolean> IS_TRANSFORM = new ConcurrentHashMap<>();

    private SmithingCompat() {}

    /** 是不是锻造台「模板 + 底材 + 材料 → 结果」那一类配方（{@code minecraft:smithing_transform}） */
    public static boolean isSmithingTransform(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_TRANSFORM.computeIfAbsent(recipe.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                if (TRANSFORM.equals(k.getName())) return true;
            }
            return false;
        });
    }

    /** 三个字段 → 输入槽（模板 / 底材 / 材料），空字段自动跳过 */
    public static List<List<GenericStack>> inputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        addSlot(slots, readField(recipe, "template"));
        addSlot(slots, readField(recipe, "base"));
        addSlot(slots, readField(recipe, "addition"));
        return slots;
    }

    // ── 内部 ──

    private static void addSlot(List<List<GenericStack>> slots, @Nullable Object value) {
        if (!(value instanceof Ingredient ingredient)) return;
        var options = new ArrayList<GenericStack>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            options.add(new GenericStack(AEItemKey.of(stack), 1));
            if (options.size() >= MAX_OPTIONS) break;
        }
        if (!options.isEmpty()) slots.add(List.copyOf(options));
    }

    @Nullable
    private static Object readField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // 继续往上找
            }
        }
        return null;
    }
}
