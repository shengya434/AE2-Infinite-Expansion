package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 「无用之物」（useless_mod）高级合金炉配方兼容（2026-09-17 sensei 清单）。
 * <p>
 * sensei 的原话：「材料输入数量错误，全为 1，流体输入不检测」—— 读字节码后都对上了：
 * <pre>
 *   inputItems      List&lt;Ingredient&gt;   ← 原料
 *   inputItemCounts List&lt;Long&gt;         ← **跟原料平行的数量表**（不读它当然全按 1 算）
 *   inputFluids     List&lt;FluidStack&gt;   ← 流体输入（原来一个都没抽）
 *   outputItems     List&lt;ItemStack&gt;    ← 物品产出
 *   outputFluids    List&lt;FluidStack&gt;   ← 流体产出
 *   catalyst        Ingredient + catalystCount  ← 催化剂（按数量消耗）
 *   mold            Ingredient                  ← 模具（**不消耗**，反复用）
 * </pre>
 */
public final class UselessModCompat {

    private static final String ALLOY_FURNACE =
            "com.sorrowmist.useless.recipes.advancedalloyfurnace.AdvancedAlloyFurnaceRecipe";

    private static final int MAX_OPTIONS = 32;

    /** 输入槽 + 是否「不消耗」（模具就是放着反复用的） */
    public record SlotSpec(List<GenericStack> options, boolean catalyst) {}

    private UselessModCompat() {}

    public static boolean isAdvancedAlloyFurnace(@Nullable Recipe<?> recipe) {
        return recipe != null && ALLOY_FURNACE.equals(recipe.getClass().getName());
    }

    /**
     * 原料：{@code inputItems} 与 {@code inputItemCounts} **平行对应**
     * （第 i 个原料的数量是 inputItemCounts 的第 i 项）—— 2026-09-17 修正「数量全为 1」的根因。
     */
    public static List<List<GenericStack>> itemInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        Object items = readField(recipe, "inputItems");
        Object counts = readField(recipe, "inputItemCounts");
        if (!(items instanceof Iterable<?> itemList)) return slots;
        var countList = new ArrayList<Number>();
        if (counts instanceof Iterable<?> countIterable) {
            for (Object element : countIterable) {
                if (element instanceof Number n) countList.add(n);
            }
        }
        int index = 0;
        for (Object element : itemList) {
            long count = index < countList.size() ? Math.max(1, countList.get(index).longValue()) : 1;
            index++;
            if (!(element instanceof Ingredient ingredient)) continue;
            var options = new ArrayList<GenericStack>();
            for (ItemStack stack : ingredient.getItems()) {
                if (stack.isEmpty()) continue;
                options.add(new GenericStack(AEItemKey.of(stack), count));
                if (options.size() >= MAX_OPTIONS) break;
            }
            if (!options.isEmpty()) slots.add(List.copyOf(options));
        }
        return slots;
    }

    /** 流体输入：{@code inputFluids}（{@code List<FluidStack>}）—— 2026-09-17 新增（原来完全不检测） */
    public static List<GenericStack> fluidInputs(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object fluids = readField(recipe, "inputFluids");
        if (!(fluids instanceof Iterable<?> it)) return List.of();
        var out = new ArrayList<GenericStack>();
        for (Object element : it) {
            if (element instanceof FluidStack fluid && !fluid.isEmpty()) {
                out.add(new GenericStack(AEFluidKey.of(fluid.getFluid()), Math.max(1, fluid.getAmount())));
            }
        }
        return List.copyOf(out);
    }

    /** 催化剂（按 catalystCount 消耗）+ 模具（不消耗） */
    public static List<SlotSpec> specialInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<SlotSpec>();
        if (recipe == null) return slots;
        addIngredient(slots, readField(recipe, "catalyst"), Math.max(1, readInt(recipe, "catalystCount")), false);
        addIngredient(slots, readField(recipe, "mold"), 1, true);
        return slots;
    }

    /** 产出：{@code outputItems} + {@code outputFluids} */
    public static List<GenericStack> outputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<GenericStack>();
        if (recipe == null) return out;
        Object items = readField(recipe, "outputItems");
        if (items instanceof Iterable<?> it) {
            for (Object element : it) {
                if (element instanceof ItemStack stack && !stack.isEmpty()) {
                    out.add(GenericStack.fromItemStack(stack));
                }
            }
        }
        Object fluids = readField(recipe, "outputFluids");
        if (fluids instanceof Iterable<?> it) {
            for (Object element : it) {
                if (element instanceof FluidStack fluid && !fluid.isEmpty()) {
                    out.add(new GenericStack(AEFluidKey.of(fluid.getFluid()), Math.max(1, fluid.getAmount())));
                }
            }
        }
        return out;
    }

    // ── 反射工具 ──

    private static void addIngredient(List<SlotSpec> slots, @Nullable Object value, long amount, boolean catalyst) {
        if (!(value instanceof Ingredient ingredient)) return;
        var options = new ArrayList<GenericStack>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            options.add(new GenericStack(AEItemKey.of(stack), amount));
            if (options.size() >= MAX_OPTIONS) break;
        }
        if (!options.isEmpty()) slots.add(new SlotSpec(List.copyOf(options), catalyst));
    }

    private static int readInt(Object target, String name) {
        Object raw = readField(target, name);
        return raw instanceof Number n ? n.intValue() : 1;
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
