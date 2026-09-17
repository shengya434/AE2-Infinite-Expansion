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
 * NaturesAura（自然灵气）配方兼容（2026-09-17 sensei 全 mod 扫描暴露）。
 * <p>
 * 三类配方的产物 {@code getResultItem()} 都读得到，问题全在**输入不走标准 API**：
 * <pre>
 *   altar      （AltarRecipe）     : input(Ingredient) + catalyst(Ingredient，**不消耗**) + output + aura
 *   tree_ritual（TreeRitualRecipe）: sapling(Ingredient) + ingredients(Ingredient[]) + result
 *   offering   （OfferingRecipe）  : input(Ingredient) + start_item(Ingredient) + output
 *   animal_spawner（AnimalSpawnerRecipe）: 产出是**实体**（entity 字段）→ 没有物品产物，做不了样板，跳过
 * </pre>
 * 注：{@code aura}（灵气）没有 AE 网络里的对应 key（没有桥 mod），所以不计入输入。
 */
public final class NaturesAuraCompat {

    private static final String ALTAR = "de.ellpeck.naturesaura.recipes.AltarRecipe";
    private static final String TREE_RITUAL = "de.ellpeck.naturesaura.recipes.TreeRitualRecipe";
    private static final String OFFERING = "de.ellpeck.naturesaura.recipes.OfferingRecipe";

    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, Boolean> IS_ALTAR = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_TREE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_OFFERING = new ConcurrentHashMap<>();

    /** 输入槽 + 是否「不消耗」（祭坛的 catalyst 是放着反复用的） */
    public record SlotSpec(List<GenericStack> options, boolean catalyst) {}

    private NaturesAuraCompat() {}

    public static boolean isAltar(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_ALTAR.computeIfAbsent(recipe.getClass(), c -> ALTAR.equals(c.getName()));
    }

    public static boolean isTreeRitual(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_TREE.computeIfAbsent(recipe.getClass(), c -> TREE_RITUAL.equals(c.getName()));
    }

    public static boolean isOffering(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_OFFERING.computeIfAbsent(recipe.getClass(), c -> OFFERING.equals(c.getName()));
    }

    /** 祭坛：原料（消耗）+ 催化剂（**不消耗**，占着反复用） */
    public static List<SlotSpec> altarInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<SlotSpec>();
        if (recipe == null) return slots;
        addIngredient(slots, readField(recipe, "input"), false);
        addIngredient(slots, readField(recipe, "catalyst"), true);
        return slots;
    }

    /** 树仪式：树苗（消耗）+ 一圈材料 */
    public static List<SlotSpec> treeRitualInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<SlotSpec>();
        if (recipe == null) return slots;
        // ⚠2026-09-17 自查修正：字段真名是 saplingType（我原来写 sapling → 取到 null，树苗输入会静默丢失）
        addIngredient(slots, readField(recipe, "saplingType"), false);
        Object ingredients = readField(recipe, "ingredients");
        if (ingredients instanceof Object[] array) {
            for (Object element : array) addIngredient(slots, element, false);
        } else if (ingredients instanceof Iterable<?> it) {
            for (Object element : it) addIngredient(slots, element, false);
        }
        return slots;
    }

    /** 献祭：献祭物 + 起始物品（都很可能在仪式里被消耗） */
    public static List<SlotSpec> offeringInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<SlotSpec>();
        if (recipe == null) return slots;
        addIngredient(slots, readField(recipe, "input"), false);
        // ⚠2026-09-17 自查修正：字段真名是 startItem（JSON 里写的是 start_item，Java 字段没有下划线）
        addIngredient(slots, readField(recipe, "startItem"), false);
        return slots;
    }

    // ── 反射工具 ──

    private static void addIngredient(List<SlotSpec> slots, @Nullable Object value, boolean catalyst) {
        if (!(value instanceof Ingredient ingredient)) return;
        var options = new ArrayList<GenericStack>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            options.add(new GenericStack(AEItemKey.of(stack), 1));
            if (options.size() >= MAX_OPTIONS) break;
        }
        if (!options.isEmpty()) slots.add(new SlotSpec(List.copyOf(options), catalyst));
    }

    @Nullable
    private static Object readField(Object target, String name) {
        if (target == null) return null;
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
