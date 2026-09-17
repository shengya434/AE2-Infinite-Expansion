package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.mojang.datafixers.util.Either;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EvilCraft 配方兼容（2026-09-17 sensei 清单第二批）。
 * <p>
 * 两类机器的数据都在字段里（标准 {@code getIngredients()} 没实现 → 「无输入」）：
 * <pre>
 *   blood_infuser（RecipeBloodInfuser）  ：inputIngredient(Ingredient) + inputFluid(FluidStack，如 10000 mB 血)
 *                                          + outputItem(Either&lt;ItemStack, ?&gt;) + inputTier
 *   environmental_accumulator（RecipeEnvironmentalAccumulator）：inputIngredient + outputItem(Either)
 *                                          （weather 是天气条件，不是物品 → 不计入输入）
 * </pre>
 */
public final class EvilcraftCompat {

    private static final String BLOOD_INFUSER =
            "org.cyclops.evilcraft.core.recipe.type.RecipeBloodInfuser";
    private static final String ENV_ACC =
            "org.cyclops.evilcraft.core.recipe.type.RecipeEnvironmentalAccumulator";

    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, String> KIND = new ConcurrentHashMap<>();

    private EvilcraftCompat() {}

    @Nullable
    private static String kindOf(@Nullable Recipe<?> recipe) {
        if (recipe == null) return null;
        if (!recipe.getClass().getName().startsWith("org.cyclops.evilcraft.")) return null;
        String kind = KIND.computeIfAbsent(recipe.getClass(), c -> {
            String n = c.getName();
            if (BLOOD_INFUSER.equals(n)) return BLOOD_INFUSER;
            if (ENV_ACC.equals(n)) return ENV_ACC;
            return "?";
        });
        return "?".equals(kind) ? null : kind;
    }

    public static boolean isHandled(@Nullable Recipe<?> recipe) {
        return kindOf(recipe) != null;
    }

    public static boolean isBloodInfuser(@Nullable Recipe<?> recipe) {
        return BLOOD_INFUSER.equals(kindOf(recipe));
    }

    public static boolean isEnvironmentalAccumulator(@Nullable Recipe<?> recipe) {
        return ENV_ACC.equals(kindOf(recipe));
    }

    /** 输入：{@code inputIngredient}（单个 Ingredient）+ {@code inputFluid}（FluidStack，可空） */
    public static List<List<GenericStack>> inputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        Object ingredient = readField(recipe, "inputIngredient");
        if (ingredient instanceof Ingredient ing) {
            var options = new ArrayList<GenericStack>();
            for (ItemStack stack : ing.getItems()) {
                if (stack.isEmpty()) continue;
                options.add(new GenericStack(AEItemKey.of(stack), 1));
                if (options.size() >= MAX_OPTIONS) break;
            }
            if (!options.isEmpty()) slots.add(List.copyOf(options));
        }
        Object fluid = readField(recipe, "inputFluid");
        if (fluid instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            slots.add(List.of(new GenericStack(AEFluidKey.of(fluidStack.getFluid()),
                    Math.max(1, fluidStack.getAmount()))));
        }
        return slots;
    }

    /** 产出：{@code outputItem} 是 {@code Either}（左边是 ItemStack；右边尽力解析） */
    public static ItemStack output(@Nullable Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        Object raw = readField(recipe, "outputItem");
        if (!(raw instanceof Either<?, ?> either)) {
            return raw instanceof ItemStack stack && !stack.isEmpty() ? stack : ItemStack.EMPTY;
        }
        Object left = either.left().orElse(null);
        if (left instanceof ItemStack stack && !stack.isEmpty()) return stack;
        Object right = either.right().orElse(null);
        if (right instanceof ItemStack stack && !stack.isEmpty()) return stack;
        Object nested = readField(right, "item");
        return nested instanceof ItemStack stack && !stack.isEmpty() ? stack : ItemStack.EMPTY;
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
