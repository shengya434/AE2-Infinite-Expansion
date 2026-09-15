package com.ae2addon.compat;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mekanism（通用机械）机器配方兼容层 —— **反射实现，零硬依赖**。
 * <p>
 * 2026-09-15 sensei：MEK 的机器配方此前整条没兼容。这里把 MEK 的配方 API 归一化：
 * <pre>
 * 输入：任何返回 {@code mekanism.api.recipes.ingredients.*Ingredient} 的 0 参方法
 *       （getInput / getItemInput / getChemicalInput / getMainInput / getExtraInput /
 *        getInputSolid / getInputFluid / getInputGas / getLeftInput / getRightInput …）
 *       → {@code getRepresentations()} 列候选 + {@code getNeededAmount(rep)} 取数量
 * 产出：{@code getOutputDefinition()} / {@code getMainOutputDefinition()}
 *       （+ 锯木厂的 {@code getSecondaryOutputDefinition()} × {@code getSecondaryChance()}）
 * </pre>
 * 一条配方只需要这两个方法族就能描述 —— 所以**所有 MEK 机器**（富集/粉碎/锯木/冶金灌注/
 * 净化/化学注入/化合/分离/电解/结晶/溶解/清洗/氧化/旋转/加压反应/核聚变…）共用同一段代码。
 * <p>
 * 化学物（气体/灌注/颜料/浆液）靠 **Applied-Mekanistics** 的 {@code MekanismKey.of(ChemicalStack)}
 * 变成 AE2 的 {@link AEKey}（该 mod 在本实例已装）；没装时化学槽**自动跳过**（不炸，只是缺槽）。
 * <p>
 * ⚠ 已知取舍：**旋转机（罗盘转换机）**一个配方对象里含两个方向，而我们的样板一层只有一份
 * 输入/产出 → 只取一个方向（两个方向都在时取气→液；只有液→气时才取液→气）。
 * 要双向得加「样板变体」机制。
 */
public final class MekanismCompat {

    /** 一个产出（含几率；chance ≥1 视为确定产出） */
    public record Stat(GenericStack stack, float chance) {}

    private static final String MEK_RECIPE_CLASS = "mekanism.api.recipes.MekanismRecipe";
    private static final String INGREDIENT_PKG = "mekanism.api.recipes.ingredients.";
    private static final String CHEMICAL_PKG = "mekanism.api.chemical.";

    /** 每个输入槽最多列多少候选（标签展开可能上千） */
    private static final int MAX_OPTIONS_PER_SLOT = 32;

    private static final Map<Class<?>, Boolean> IS_MEK = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Method>> INPUT_METHODS = new ConcurrentHashMap<>();

    // Applied-Mekanistics：ChemicalStack → AEKey
    private static final Class<?> CHEMICAL_STACK_CLS = classOrNull("mekanism.api.chemical.ChemicalStack");
    private static final Method MEK_KEY_OF = staticMethod("me.ramidzkh.mekae2.ae2.MekanismKey", "of", CHEMICAL_STACK_CLS);

    private MekanismCompat() {}

    // ════════════════════════════════════════════════════════
    //  判定
    // ════════════════════════════════════════════════════════

    public static boolean isMekanismRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_MEK.computeIfAbsent(recipe.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                if (MEK_RECIPE_CLASS.equals(k.getName())) return true;
            }
            return c.getName().startsWith("mekanism.") && !c.getName().startsWith("mekanism.client.");
        });
    }

    // ════════════════════════════════════════════════════════
    //  输入
    // ════════════════════════════════════════════════════════

    /** 输入槽（物品/流体/化学物都是 GenericStack；标签类原料一槽多候选） */
    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        var seen = new java.util.HashSet<String>();
        int rotary = rotaryDirection(recipe);
        for (Method m : ingredientMethods(recipe.getClass())) {
            // 旋转机（罗盘转换机）一个配方对象里含**两个方向**：只取一个方向（见 rotaryDirection）
            if (rotary == 1 && "getFluidInput".equals(m.getName())) continue;
            if (rotary == 2 && "getGasInput".equals(m.getName())) continue;
            Object ingredient = invoke(recipe, m);
            if (ingredient == null) continue;
            var options = representations(ingredient);
            if (options.isEmpty()) continue;
            var signature = new StringBuilder();
            for (var option : options) signature.append(option.what()).append('x').append(option.amount()).append(';');
            if (!seen.add(signature.toString())) continue;   // 同一槽被两个方法名暴露 → 去重
            slots.add(options);
        }
        return slots;
    }

    /**
     * 旋转机（RotaryRecipe）方向判定。
     *
     * @return 0 = 不是旋转机；1 = 气→液；2 = 液→气
     *         <p>旋转机一个配方对象同时含两个方向，而我们的样板一层只有一份输入/产出 →
     *         单向取值（两个方向都在时先取气→液；要双向的话后续加“样板变体”）
     */
    private static int rotaryDirection(Recipe<?> recipe) {
        if (!hasMethod(recipe.getClass(), "hasGasToFluid") || !hasMethod(recipe.getClass(), "hasFluidToGas")) {
            return 0;
        }
        boolean gasToFluid = truthy(invoke(recipe, "hasGasToFluid"));
        boolean fluidToGas = truthy(invoke(recipe, "hasFluidToGas"));
        if (fluidToGas && !gasToFluid) return 2;
        return 1;
    }

    private static boolean hasMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (name.equals(m.getName()) && m.getParameterCount() == 0) return true;
        }
        return false;
    }

    private static boolean truthy(@Nullable Object value) {
        return value instanceof Boolean b && b;
    }

    /** 该类所有「返回 Ingredient 的 0 参方法」（按方法名排序 → 槽位顺序稳定） */
    private static List<Method> ingredientMethods(Class<?> recipeClass) {
        return INPUT_METHODS.computeIfAbsent(recipeClass, c -> {
            var list = new ArrayList<Method>();
            for (Method m : c.getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getName().contains("Input")) continue;
                if (!m.getReturnType().getName().startsWith(INGREDIENT_PKG)) continue;
                list.add(m);
            }
            list.sort(Comparator.comparing(Method::getName));
            return List.copyOf(list);
        });
    }

    /** 一个 Ingredient → 候选 GenericStack 列表 */
    private static List<GenericStack> representations(Object ingredient) {
        var options = new ArrayList<GenericStack>();
        Object repsRaw = invoke(ingredient, "getRepresentations");
        if (!(repsRaw instanceof Iterable<?> reps)) return options;
        for (Object rep : reps) {
            if (rep == null) continue;
            long needed = neededAmount(ingredient, rep);
            GenericStack stack = toGeneric(rep, needed);
            if (stack == null || stack.amount() <= 0) continue;
            boolean dup = false;
            for (var existing : options) {
                if (existing.what().equals(stack.what()) && existing.amount() == stack.amount()) { dup = true; break; }
            }
            if (!dup) options.add(stack);
            if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
        }
        return options;
    }

    private static long neededAmount(Object ingredient, Object rep) {
        for (Method m : ingredient.getClass().getMethods()) {
            if (!"getNeededAmount".equals(m.getName()) || m.getParameterCount() != 1) continue;
            try {
                Object value = m.invoke(ingredient, rep);
                if (value instanceof Number n && n.longValue() > 0) return n.longValue();
            } catch (Throwable ignored) {
                // 换个重载/桥接方法再试
            }
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════
    //  产出
    // ════════════════════════════════════════════════════════

    /** 产出（物品/流体/化学物，含几率） */
    public static List<Stat> outputs(Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        // 主产出：不同配方族的命名不同
        for (String name : new String[]{"getOutputDefinition", "getMainOutputDefinition", "getMainOutput"}) {
            for (Object element : asList(invoke(recipe, name))) {
                collect(out, element, 1f);
            }
        }
        // 旋转机：取与输入方向对应的那侧产出
        int rotary = rotaryDirection(recipe);
        if (rotary == 1) {
            for (Object element : asList(invoke(recipe, "getFluidOutputDefinition"))) collect(out, element, 1f);
        } else if (rotary == 2) {
            for (Object element : asList(invoke(recipe, "getGasOutputDefinition"))) collect(out, element, 1f);
        }
        // 概率产出：锯木厂（次要产出 + 独立几率字段）
        float secondaryChance = number(invoke(recipe, "getSecondaryChance"), -1f);
        for (Object element : asList(invoke(recipe, "getSecondaryOutputDefinition"))) {
            collect(out, element, secondaryChance > 0f ? secondaryChance : 1f);
        }
        return List.copyOf(dedupe(out));
    }

    /** 把一个「产出元素」拆成 GenericStack（支持直接 stack、List、以及 record 型输出容器） */
    private static void collect(List<Stat> out, @Nullable Object element, float chance) {
        if (element == null) return;
        if (element instanceof Iterable<?> it) {
            for (Object inner : it) collect(out, inner, chance);
            return;
        }
        GenericStack direct = toGeneric(element, -1);
        if (direct != null) {
            if (direct.amount() > 0) out.add(new Stat(direct, chance));
            return;
        }
        // record 型输出容器（PressurizedReactionRecipeOutput / ElectrolysisRecipeOutput…）：
        // 扫它的 0 参方法，取返回 stack 的那些
        for (Method m : element.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            Object value = invoke(element, m);
            if (value == null || value == element) continue;
            GenericStack inner = toGeneric(value, -1);
            if (inner != null && inner.amount() > 0) out.add(new Stat(inner, chance));
        }
    }

    private static List<Stat> dedupe(List<Stat> stats) {
        var result = new ArrayList<Stat>();
        for (var stat : stats) {
            boolean dup = false;
            for (var existing : result) {
                if (existing.chance() == stat.chance() && existing.stack().what().equals(stat.stack().what())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) result.add(stat);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════
    //  单个 stack 的转换（物品 / 流体 / 化学物）
    // ════════════════════════════════════════════════════════

    /**
     * 任意 MEK 侧 stack 对象 → {@link GenericStack}。
     *
     * @param needed &gt;0 时用这个数量（配方要求的量），否则用对象自带数量
     */
    @Nullable
    public static GenericStack toGeneric(@Nullable Object value, long needed) {
        if (value == null) return null;
        if (value instanceof ItemStack item) {
            if (item.isEmpty()) return null;
            long amount = needed > 0 ? needed : Math.max(1, item.getCount());
            return new GenericStack(AEItemKey.of(item), amount);
        }
        if (value instanceof FluidStack fluid) {
            if (fluid.isEmpty()) return null;
            long amount = needed > 0 ? needed : Math.max(1, fluid.getAmount());
            return new GenericStack(AEFluidKey.of(fluid.getFluid()), amount);
        }
        if (value.getClass().getName().startsWith(CHEMICAL_PKG)) {
            return chemicalToGeneric(value, needed);
        }
        return null;
    }

    /** MEK 化学物（气体/灌注/颜料/浆液）→ AEKey（走 Applied-Mekanistics；没装 = null） */
    @Nullable
    private static GenericStack chemicalToGeneric(Object chemicalStack, long needed) {
        if (MEK_KEY_OF == null) return null;
        long own = number(invoke(chemicalStack, "getAmount"), 0L);
        if (own <= 0 && needed <= 0) return null;
        try {
            Object key = MEK_KEY_OF.invoke(null, chemicalStack);
            if (!(key instanceof AEKey aeKey)) return null;
            return new GenericStack(aeKey, needed > 0 ? needed : own);
        } catch (Throwable t) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════
    //  反射工具
    // ════════════════════════════════════════════════════════

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
    private static Object invoke(@Nullable Object target, Method method) {
        if (target == null || method == null) return null;
        try {
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
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
                    // 继续找同名重载
                }
            }
        }
        return null;
    }

    private static float number(@Nullable Object value, float fallback) {
        return value instanceof Number n ? n.floatValue() : fallback;
    }

    private static long number(@Nullable Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    @Nullable
    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Method staticMethod(String className, String methodName, @Nullable Class<?> param) {
        if (param == null) return null;
        Class<?> clazz = classOrNull(className);
        if (clazz == null) return null;
        try {
            return clazz.getMethod(methodName, param);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供诊断：本层是否具备化学物支持（Applied-Mekanistics 在位） */
    public static boolean chemicalsSupported() {
        return MEK_KEY_OF != null;
    }
}
