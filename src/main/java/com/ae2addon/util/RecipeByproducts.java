package com.ae2addon.util;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从「真实配方」里抽取次级产出（副产物）。
 * <p>
 * 1.20.1 的 Forge 配方 API 只暴露主产物（{@link Recipe#getResultItem}），次级产出是各 mod
 * 自己配方对象上的字段/方法 → 这里**用反射探测**取，不引入任何硬依赖（无对应 mod 时只是探不到）。
 * <p>
 * 目前验证过的形状：
 * <ul>
 *   <li><b>Create</b>（6.0.8 实机核验）：{@code ProcessingRecipe#getRollableResults()} →
 *       元素 {@code getStack()} + {@code getChance()}（粉碎/洗涤/喷溅的百分比额外产出）</li>
 *   <li><b>Mekanism</b>（10.4.16 实机核验）：{@code SawmillRecipe#getSecondaryOutputDefinition()}
 *       （Ingredient）+ {@code getSecondaryChance()}（double）</li>
 *   <li>通用容器名探测：{@code getSecondaryOutput(s)/getByproducts/getExtraOutputs/getOutputs/…}，
 *       元素支持 {@code getStack/getMainOutput/getSecondaryOutput/getMaxSecondaryOutput}</li>
 * </ul>
 * 主产物（与 {@code getResultItem} 同物品且几率≥1）会被排除，避免把主产物当副产再发一份。
 */
public final class RecipeByproducts {

    /** 一条带几率的次级产出；chance < 0 表示「配方没写几率」 */
    public record Chanced(ItemStack stack, float chance) {}

    /** 容器型方法名（按优先级；无参调用） */
    private static final String[] CONTAINER_METHODS = {
            "getRollableResults",
            "getSecondaryOutputs", "getSecondaryOutput",
            "getByproducts", "getExtraOutputs", "getChanceOutputs",
            "getOutputs", "getOutput"
    };

    private static final String[] STACK_GETTERS = {
            "getStack", "getMainOutput", "getSecondaryOutput", "getMaxSecondaryOutput",
            "getResult", "getOutput"
    };

    private static final String[] CHANCE_GETTERS = {
            "getChance", "getSecondaryChance"
    };

    /** 配方类 → 探测结论缓存（避免每次都反射） */
    private static final Map<Class<?>, List<Chanced>> PROBE_CACHE = new ConcurrentHashMap<>();

    private RecipeByproducts() {}

    /** 探测该配方是否有次级产出（结果按配方类缓存，只探一次） */
    public static boolean hasByproducts(Class<?> recipeClass) {
        List<Chanced> cached = PROBE_CACHE.get(recipeClass);
        return cached != null && !cached.isEmpty();
    }

    public static List<Chanced> extract(Recipe<?> recipe, net.minecraft.world.level.Level level) {
        return extract(recipe, level == null ? null : level.registryAccess());
    }

    /** 同上（不依赖 Level：编码/索引场景用 RecipeManager.registries()） */
    public static List<Chanced> extract(Recipe<?> recipe, @Nullable net.minecraft.core.RegistryAccess access) {
        List<Chanced> cached = PROBE_CACHE.get(recipe.getClass());
        if (cached != null) return cached;

        List<Chanced> out = new ArrayList<>();
        ItemStack primary = ItemStack.EMPTY;
        if (access != null) {
            try {
                primary = recipe.getResultItem(access);
            } catch (Throwable ignored) {
                // 个别 mod 配方在此抛异常 → 退化为「无主产物」
            }
        }

        // ① 容器型方法
        for (String name : CONTAINER_METHODS) {
            Object value = invoke(recipe, name);
            if (value == null) continue;
            if (value instanceof Iterable<?> it) {
                for (Object element : it) add(out, stackOf(element), chanceOf(element), primary);
            } else {
                add(out, stackOf(value), chanceOf(value), primary);
            }
            if (!out.isEmpty()) break;
        }

        // ② Create 序列装配：结果池（概率产出；主产物概率产、副产不明的那类配方就在这里）
        if (out.isEmpty()) {
            collectResultPool(recipe, primary, out);
        }

        // ③ GT：带几率的物品产出（几率 <100% 的即为副产；主产物绝不当副产重复发）
        if (out.isEmpty()) {
            for (var chanced : com.ae2addon.compat.GregTechCompat.itemOutputs(recipe)) {
                ItemStack stack = chanced.stack();
                if (stack.isEmpty()) continue;
                if (!primary.isEmpty() && stack.getItem() == primary.getItem()) continue;
                add(out, stack, chanced.chance(), primary);
            }
        }

        // ④ Mekanism 锯木式：Ingredient 定义 + 独立几率字段
        if (out.isEmpty()) {
            Object def = invoke(recipe, "getSecondaryOutputDefinition");
            if (def instanceof Ingredient ing) {
                ItemStack[] items = ing.getItems();
                if (items.length > 0) {
                    Object chance = invoke(recipe, "getSecondaryChance");
                    add(out, items[0], chance instanceof Number n ? n.floatValue() : -1f, primary);
                }
            }
        }

        List<Chanced> result = List.copyOf(out);
        PROBE_CACHE.put(recipe.getClass(), result);
        return result;
    }

    // ── 内部 ──

    /**
     * Create 序列装配的结果池（resultPool）：字段里的值实际是**权重**，必须归一化。
     * <p>
     * 2026-09-15 sensei 实测：「权重 8 被当成 800%」。判法：池里有任一项 >1 → 按总和归一；
     * 全 ≤1 → 已经是概率，原样用。（与 {@code getRollableResults()} 的 chance 语义不同，别合并）
     */
    private static void collectResultPool(Recipe<?> recipe, ItemStack primary, List<Chanced> out) {
        if (!com.ae2addon.compat.CreateSequencedCompat.isSequencedAssembly(recipe)) return;
        try {
            Object pool = recipe.getClass().getField("resultPool").get(recipe);
            if (!(pool instanceof Iterable<?> entries)) return;
            var rawStacks = new ArrayList<ItemStack>();
            var rawWeights = new ArrayList<Float>();
            float total = 0f;
            float max = 0f;
            for (Object entry : entries) {
                ItemStack stack = stackOf(entry);
                if (stack == null || stack.isEmpty()) continue;
                if (!primary.isEmpty() && stack.getItem() == primary.getItem()) continue; // 主产物不重复发
                float weight = chanceOf(entry);
                if (weight <= 0f) weight = 1f;
                rawStacks.add(stack);
                rawWeights.add(weight);
                total += weight;
                if (weight > max) max = weight;
            }
            if (rawStacks.isEmpty()) return;
            boolean weighted = max > 1.0f;
            for (int i = 0; i < rawStacks.size(); i++) {
                float weight = rawWeights.get(i);
                float chance = weighted ? (total > 0f ? weight / total : 1f) : weight;
                add(out, rawStacks.get(i), chance, primary);
            }
        } catch (Throwable ignored) {
            // 反射失败 → 当作没有结果池
        }
    }

    private static void add(List<Chanced> out, @Nullable ItemStack stack, float chance, ItemStack primary) {
        if (stack == null || stack.isEmpty()) return;
        // 主产物排除：同物品且几率≥1（Create 的主产物就是 chance=1 那条）
        if (!primary.isEmpty() && chance >= 1.0f && ItemStack.isSameItem(stack, primary)) return;
        for (Chanced existing : out) {
            if (existing.chance() == chance && ItemStack.isSameItemSameTags(existing.stack(), stack)) return;
        }
        out.add(new Chanced(stack.copy(), chance));
    }

    @Nullable
    private static ItemStack stackOf(@Nullable Object element) {
        if (element instanceof ItemStack stack) return stack;
        if (element == null) return null;
        for (String name : STACK_GETTERS) {
            Object value = invoke(element, name);
            if (value instanceof ItemStack stack && !stack.isEmpty()) return stack;
        }
        return null;
    }

    private static float chanceOf(@Nullable Object element) {
        if (element == null) return -1f;
        for (String name : CHANCE_GETTERS) {
            Object value = invoke(element, name);
            if (value instanceof Number n) return n.floatValue();
        }
        return -1f;
    }

    @Nullable
    private static Object invoke(Object target, String methodName) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(methodName);
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
}
