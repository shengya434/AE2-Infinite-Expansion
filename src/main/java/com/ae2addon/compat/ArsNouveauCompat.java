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
 * Ars Nouveau（新生魔艺）配方兼容层 —— 反射实现，零硬依赖。
 * <p>
 * 2026-09-16 sensei 报「灌注室（imbuement）与附魔装置（enchanting_apparatus）配方无法识别」。
 * 根因是**标准 API 全空**（读 jar 字节码实证）：
 * <pre>
 *   ImbuementRecipe.getResultItem(RegistryAccess)           → ItemStack.EMPTY（常量池里就是 f_41583_ = EMPTY）
 *   EnchantingApparatusRecipe.getResultItem(RegistryAccess) → ItemStack.EMPTY
 *   两个类的 getIngredients()（m_7527_）都没有实现
 * </pre>
 * 于是 {@code candidates()} 认为它们「没有产出」直接跳过 —— 这就是「无法识别」。
 * 真正的数据全在字段里（字节码实证）：
 * <pre>
 *   灌注室 ImbuementRecipe:          input(Ingredient)  output(ItemStack)  source(int)  pedestalItems(List&lt;Ingredient&gt;)
 *   附魔装置 EnchantingApparatusRecipe: reagent(Ingredient) result(ItemStack) sourceCost(int) pedestalItems(List&lt;Ingredient&gt;)
 * </pre>
 * 所以这里一律**读字段**，不碰被重载过的 getter（{@code getResult(Tile)} 还要 Tile 参数，更麻烦）。
 */
public final class ArsNouveauCompat {

    private static final String PREFIX = "com.hollingsworth.arsnouveau.";
    private static final String IMBUEMENT =
            "com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe";
    private static final String APPARATUS =
            "com.hollingsworth.arsnouveau.api.enchanting_apparatus.EnchantingApparatusRecipe";

    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, Boolean> IS_FIELD_ONLY = new ConcurrentHashMap<>();

    private ArsNouveauCompat() {}

    /** 是不是「标准 API 拿不到东西、必须读字段」的那两类配方（灌注室 / 附魔装置） */
    public static boolean isFieldOnlyRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_FIELD_ONLY.computeIfAbsent(recipe.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                String name = k.getName();
                if (IMBUEMENT.equals(name) || APPARATUS.equals(name)) return true;
            }
            return false;
        });
    }

    /** 是不是 Ars 的配方（诊断/日志用） */
    public static boolean isArsRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().startsWith(PREFIX);
    }

    /** 真产物：附魔装置读 {@code result}、灌注室读 {@code output}（getResultItem 恒为 EMPTY，别用） */
    public static ItemStack output(@Nullable Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        Object raw = readField(recipe, "result");
        if (raw instanceof ItemStack stack && !stack.isEmpty()) return stack;
        raw = readField(recipe, "output");
        return raw instanceof ItemStack stack && !stack.isEmpty() ? stack : ItemStack.EMPTY;
    }

    /** 魔源消耗：附魔装置字段 {@code sourceCost}、灌注室字段 {@code source}（字段名不一致，都要试）；没有 → 0 */
    public static int sourceCost(@Nullable Recipe<?> recipe) {
        if (recipe == null) return 0;
        Object raw = readField(recipe, "sourceCost");
        if (raw instanceof Number n && n.intValue() > 0) return n.intValue();
        raw = readField(recipe, "source");
        return raw instanceof Number n && n.intValue() > 0 ? n.intValue() : 0;
    }

    /**
     * 输入槽：中心物品（附魔装置的 {@code reagent} / 灌注室的 {@code input}）
     * + 周围基座物品（{@code pedestalItems}，两者都是 {@code List<Ingredient>}）。
     * <p>
     * 这些输入在合成时都被消耗（附魔装置的 {@code keepNbtOfReagent} 只保留 NBT，不是「不消耗」），
     * 所以一律按消耗品处理。
     */
    public static List<List<GenericStack>> inputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        addIngredientSlot(slots, readField(recipe, "reagent"));
        addIngredientSlot(slots, readField(recipe, "input"));
        Object pedestals = readField(recipe, "pedestalItems");
        if (pedestals instanceof Iterable<?> it) {
            for (Object element : it) addIngredientSlot(slots, element);
        }
        return slots;
    }

    // ── 反射工具 ──

    private static void addIngredientSlot(List<List<GenericStack>> slots, @Nullable Object value) {
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
