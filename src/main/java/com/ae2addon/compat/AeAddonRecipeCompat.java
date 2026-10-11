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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AE2 附属的机器配方兼容（2026-09-17 sensei 全 mod 扫描暴露的一批）。
 * <p>
 * 共同点：配方**都用 AE2 自己的 {@link GenericStack}（AEKey + 数量）存输入/产出**，
 * 但 {@code getIngredients()} 要么没实现、要么给的是空表 → 千机只看到产物、看不到原料。
 * <ul>
 *   <li><b>AdvancedAE 反应室</b>（{@code ReactionChamberRecipe}）：字段 {@code inputs}
 *       （{@code List<GenericStack>}）+ {@code output}（{@code GenericStack}）。物品产物 {@code getResultItem()}
 *       已经能拿到，但**流体产物拿不到**（它只处理 AEItemKey）</li>
 *   <li><b>ExtendedAE 电路切割机</b>（{@code CircuitCutterRecipe}）：输入是它自家的
 *       {@code IngredientStack$Item}（字段 {@code ingredient} 实际是 {@code Ingredient}，另有 {@code amount}）</li>
 *   <li><b>分子操纵器</b>（{@code MatterFabricationRecipe}，在 {@code omnisequence-transfinite} 里）：
 *       record，字段 {@code ingredients}（{@code List<CountedIngredient>}：ingredient + count）、
 *       {@code results}（{@code List<ItemStack>}）、{@code fluidInput}/{@code fluidResult}（FluidStack）、
 *       {@code aeInputs}（{@code List<GenericStack>}）。
 *       它的 {@code getResultItem()} 只给第一条 results → 这里只补**第 2 条往后**与流体产物，避免重复</li>
 * </ul>
 */
public final class AeAddonRecipeCompat {

    private static final String ADV_REACTION = "net.pedroksl.advanced_ae.recipes.ReactionChamberRecipe";
    private static final String EX_CUTTER = "com.glodblock.github.extendedae.recipe.CircuitCutterRecipe";
    private static final String MM_FABRICATION =
            "com.atir.molecularmanipulator.crafting.MatterFabricationRecipe";

    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, Boolean> IS_ADV = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_CUTTER = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_MM = new ConcurrentHashMap<>();

    private AeAddonRecipeCompat() {}

    // ── AdvancedAE 反应室 ──

    public static boolean isAdvancedReaction(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_ADV.computeIfAbsent(recipe.getClass(),
                c -> ADV_REACTION.equals(c.getName()));
    }

    /**
     * 输入槽（**每个槽一组候选项**，保住标签语义）。
     * <p>
     * ⚠ <b>2026-10-11 实机教训：AdvancedAE 1.3.x 换了数据形状，我们这版 compat 悄悄失效了。</b>
     * 现在配方类暴露公开访问器 {@code getInputs(): List<IngredientStack$Item>}
     * （{@code IngredientStack} 在 jar-in-jar 的 {@code ae2addonlib-1.0.4} 里，基类有
     * {@code getIngredient()} + {@code getAmount()}），而 2026-09-17 适配时读的字段
     * {@code inputs: List<GenericStack>} **已经不存在** ⇒ 反射恒返回 null ⇒
     * 实机 scan 里 {@code advanced_ae:reaction} **20 条全部「无输入」**（ae2cs 里另有 1 条）。
     * <p>
     * 新老两条路都留着：先走 {@code getInputs()}，拿不到再退回旧字段形状（早期版本）。
     */
    public static List<List<GenericStack>> advancedInputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        // ① 新 API：List<IngredientStack$Item>（Ingredient + amount）
        Object inputs = callGetter(recipe, "getInputs");
        if (inputs instanceof Iterable<?> it) {
            for (Object element : it) {
                var options = optionsOfIngredientStack(element);
                if (!options.isEmpty()) slots.add(options);
            }
        }
        if (!slots.isEmpty()) return List.copyOf(slots);
        // ② 旧形状兜底：字段 inputs = List<GenericStack>（AdvancedAE 早期版本）
        Object legacy = readField(recipe, "inputs");
        if (legacy instanceof Iterable<?> it) {
            for (Object element : it) {
                Object value = element instanceof GenericStack ? element : readField(element, "stack");
                if (value instanceof GenericStack stack && stack.what() != null && stack.amount() > 0) {
                    slots.add(List.of(stack));
                }
            }
        }
        return List.copyOf(slots);
    }

    /**
     * 一个 {@code IngredientStack$Item} → 候选 {@link GenericStack} 列表
     * （{@code getIngredient()} 是 {@code Ingredient}，数量在 {@code getAmount()}）。
     */
    private static List<GenericStack> optionsOfIngredientStack(@Nullable Object element) {
        if (element == null) return List.of();
        Object ingredient = callGetter(element, "getIngredient");
        long amount = Math.max(1, callInt(element, "getAmount", 1));
        if (ingredient instanceof Ingredient ing) return optionsOf(ing, amount);
        // 兜底：真的是 GenericStack（旧形状混进来）
        if (ingredient == null && element instanceof GenericStack stack && stack.what() != null) {
            return List.of(stack);
        }
        return List.of();
    }

    @Nullable
    private static Object callGetter(@Nullable Object target, String name) {
        if (target == null) return null;
        try {
            var method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int callInt(@Nullable Object target, String name, int fallback) {
        Object value = callGetter(target, name);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    /**
     * 流体输入：字段 {@code fluid}，类型是 {@code ae2addonlib} 的 {@code IngredientStack$Fluid}
     * （jar-in-jar 里的库；父类字段 {@code ingredient} 是 {@code Object}，实际装的是 {@code FluidStack}，
     * 另有 {@code amount}）。2026-09-17 自查补：AdvancedAE 的配方**基本都带流体**（如 500 mB 水）。
     */
    public static List<GenericStack> advancedFluidInput(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        // ① 新 API（1.3.x）：getFluid() → IngredientStack$Fluid（getStack() 直接给 FluidStack，数量在 getAmount()）
        Object fluid = callGetter(recipe, "getFluid");
        if (fluid != null) {
            FluidStack stack = null;
            Object raw = callGetter(fluid, "getStack");
            if (raw instanceof FluidStack fs) stack = fs;
            if (stack == null) {
                Object inner = callGetter(fluid, "getIngredient");   // 新 API 这里给的是 Fluid
                if (inner instanceof FluidStack fs2) stack = fs2;
                else if (inner instanceof net.minecraft.world.level.material.Fluid f) stack = new FluidStack(f, 1);
            }
            if (stack != null && !stack.isEmpty()) {
                long amount = Math.max(1, callInt(fluid, "getAmount", 1));
                if (amount <= 1 && stack.getAmount() > 1) amount = stack.getAmount();
                return List.of(new GenericStack(AEFluidKey.of(stack.getFluid()), amount));
            }
        }
        // ② 旧形状兜底：字段 fluid（IngredientStack$Fluid，父类字段 ingredient 装的是 FluidStack）
        Object legacy = readField(recipe, "fluid");
        if (legacy == null) return List.of();
        Object inner = readField(legacy, "ingredient");
        if (!(inner instanceof FluidStack stack) || stack.isEmpty()) return List.of();
        long amount = Math.max(1, readInt(legacy, "amount"));
        if (amount <= 1 && stack.getAmount() > 1) amount = stack.getAmount();
        return List.of(new GenericStack(AEFluidKey.of(stack.getFluid()), amount));
    }

    /** 产出：{@code output} 字段（{@code GenericStack}）；物品侧标准 API 也能拿到，流体侧只有这里能拿 */
    @Nullable
    public static GenericStack advancedOutput(@Nullable Recipe<?> recipe) {
        if (recipe == null) return null;
        Object raw = readField(recipe, "output");
        if (raw instanceof GenericStack stack && stack.what() != null && stack.amount() > 0) return stack;
        return null;
    }

    // ── ExtendedAE 电路切割机 ──

    public static boolean isCircuitCutter(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_CUTTER.computeIfAbsent(recipe.getClass(),
                c -> EX_CUTTER.equals(c.getName()));
    }

    /**
     * 输入：{@code input} 是 {@code IngredientStack$Item}（字段 {@code ingredient} 是 {@code Ingredient}，
     * 字段 {@code amount} 是需求量；注意 {@code ingredient} 的声明类型是 {@code Predicate}，取值后要判类型）。
     */
    public static List<GenericStack> circuitCutterInput(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object input = readField(recipe, "input");
        if (input == null) return List.of();
        Object ingredient = readField(input, "ingredient");
        if (!(ingredient instanceof Ingredient ing)) return List.of();
        return optionsOf(ing, Math.max(1, readInt(input, "amount")));
    }

    // ── 分子操纵器：物质制造（matter_fabrication） ──

    public static boolean isMatterFabrication(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_MM.computeIfAbsent(recipe.getClass(),
                c -> MM_FABRICATION.equals(c.getName()));
    }

    /** 输入：{@code ingredients}（CountedIngredient：ingredient + count）+ {@code aeInputs} + {@code fluidInput} */
    public static List<List<GenericStack>> matterFabricationInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;

        Object ingredients = readField(recipe, "ingredients");
        if (ingredients instanceof Iterable<?> it) {
            for (Object element : it) {
                Object ing = readField(element, "ingredient");
                if (!(ing instanceof Ingredient ingredient)) continue;
                var options = optionsOf(ingredient, Math.max(1, readInt(element, "count")));
                if (!options.isEmpty()) slots.add(options);
            }
        }
        Object aeInputs = readField(recipe, "aeInputs");
        if (aeInputs instanceof Iterable<?> it) {
            for (Object element : it) {
                if (element instanceof GenericStack stack && stack.what() != null && stack.amount() > 0) {
                    slots.add(List.of(stack));
                }
            }
        }
        Object fluid = readField(recipe, "fluidInput");
        if (fluid instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            slots.add(List.of(new GenericStack(AEFluidKey.of(fluidStack.getFluid()),
                    Math.max(1, fluidStack.getAmount()))));
        }
        return slots;
    }

    /**
     * **额外**产物：{@code results} 的第 2 条往后（第 1 条已经被标准 {@code getResultItem} 拿走）+ 流体产物。
     * 这样不会跟标准路径重复。
     */
    public static List<GenericStack> matterFabricationExtraOutputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<GenericStack>();
        if (recipe == null) return out;
        Object results = readField(recipe, "results");
        if (results instanceof Iterable<?> it) {
            int index = 0;
            for (Object element : it) {
                index++;
                if (index == 1) continue;
                if (element instanceof ItemStack stack && !stack.isEmpty()) {
                    out.add(GenericStack.fromItemStack(stack));
                }
            }
        }
        Object fluid = readField(recipe, "fluidResult");
        if (fluid instanceof FluidStack fluidStack && !fluidStack.isEmpty()) {
            out.add(new GenericStack(AEFluidKey.of(fluidStack.getFluid()),
                    Math.max(1, fluidStack.getAmount())));
        }
        return out;
    }

    // ── 反射工具 ──

    private static List<GenericStack> optionsOf(Ingredient ingredient, long amount) {
        var options = new ArrayList<GenericStack>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            options.add(new GenericStack(AEItemKey.of(stack), amount));
            if (options.size() >= MAX_OPTIONS) break;
        }
        return List.copyOf(options);
    }

    private static int readInt(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object raw = f.get(target);
                if (raw instanceof Number n) return n.intValue();
            } catch (Throwable ignored) {
                // 继续往上找
            }
        }
        return 1;
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
