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
 * <p>
 * ⚠ <b>2026-10-11 实机教训（反射名铁律又一次）</b>：原计划"反射 {@code template}/{@code base}/{@code addition}
 * 三个字段"在生产环境**行不通** —— Minecraft 的成员在运行时是 **SRG 名**
 * （javap 实测：{@code f_265949_} / {@code f_265888_} / {@code f_265907_}），MCP 名反射必然失败，
 * 于是 5 条锻造配方一直挂在「无输入」（ae2cs scan 实测）。而 {@code SmithingRecipe} 接口没有任何访问器，
 * 没法改成直接调用 ⇒ 现在按**声明顺序取所有 {@code Ingredient} 类型字段**，完全不依赖名字。
 * 记牢这条：{@code net.minecraft.*} 的成员要么直接调用（构建会重映射成 SRG 名），要么就别按 MCP 名反射。
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
        // ① 开发环境（MCP 名）路径：名字明确、语义清楚
        addSlot(slots, readField(recipe, "template"));
        addSlot(slots, readField(recipe, "base"));
        addSlot(slots, readField(recipe, "addition"));
        if (!slots.isEmpty()) return slots;
        // ② 生产环境：Minecraft 的成员是 **SRG 名**（javap 实测本类字段是
        //    f_265949_ / f_265888_ / f_265907_），按 MCP 名反射**必然失败** ——
        //    这就是 2026-10-11 实机 ae2cs scan 里那 5 条「无输入」的真因；
        //    而 SmithingRecipe 接口**没有任何访问器**（只有 isTemplateIngredient 之类的判断），
        //    没法像别的 compat 那样改成直接调用。
        //    ⇒ 用「不依赖名字」的取法：按**声明顺序**取该类（含父类）声明的所有 Ingredient 字段。
        //    javap 显示声明顺序正是 template → base → addition，与 ① 的槽序一致。
        for (Object value : declaredIngredients(recipe)) addSlot(slots, value);
        return slots;
    }

    /** 类（含父类）里声明的所有 {@link Ingredient} 字段的值，按声明顺序 */
    private static List<Object> declaredIngredients(Recipe<?> recipe) {
        var out = new ArrayList<Object>();
        for (Class<?> c = recipe.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Ingredient.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(recipe);
                    if (value != null) out.add(value);
                } catch (Throwable ignored) {
                    // 读不到就跳过这一个字段
                }
            }
        }
        return out;
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
