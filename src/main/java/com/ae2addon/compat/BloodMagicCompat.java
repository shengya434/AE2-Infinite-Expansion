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
 * Blood Magic（血魔法）配方兼容（2026-09-17 sensei 全 mod 扫描暴露，约 440 条问题配方里最大的一块）。
 * <p>
 * 三类能治（产物/输入都在字段里，标准 API 只给一半）：
 * <pre>
 *   alchemytable（RecipeAlchemyTable，133 条）：input 是 List&lt;Ingredient&gt; → 输入槽
 *   soulforge   （RecipeTartaricForge，90 条） ：JSON 写 input0..input3，序列化器收进 input 这个 List
 *   arc         （RecipeARC，101 条）           ：输入/工具走标准 API 就有了，缺的是主产物 {@code output}、
 *                                                流体输入 {@code inputFluid}、流体产出 {@code outputFluid}、
 *                                                以及几率附加产物 {@code addedItems}
 * </pre>
 * ❗2026-09-17 自查修正（用新的字段表解析器逐字段核对）：
 * <ul>
 *   <li>附加产物的字段真名是 <b>{@code addedItems}</b>（我原来猜的 addedOutput/addedOutputs 全不存在 → 白写）</li>
 *   <li>{@code inputFluid} 的类型是 <b>{@code FluidStackIngredient}</b>（不是 FluidStack）
 *       → 走它的 {@code getRepresentations()} 取 FluidStack 列表</li>
 *   <li>漏了 <b>{@code outputFluid}</b>（FluidStack）这个流体产物</li>
 * </ul>
 * 明确**不做**（产物是动态算出来的，硬造没意义）：{@code flask} 系列 111 条（药水 NBT 动态）、
 * {@code meteor} 12 条（召唤陨石，没有物品产物）、{@code downgrade} 10 条、
 * {@code anointment_apply}（把涂膏打到已有物品上）。
 */
public final class BloodMagicCompat {

    private static final String ALCHEMY_TABLE = "wayoftime.bloodmagic.recipe.RecipeAlchemyTable";
    private static final String SOUL_FORGE = "wayoftime.bloodmagic.recipe.RecipeTartaricForge";
    private static final String ARC = "wayoftime.bloodmagic.recipe.RecipeARC";

    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, Boolean> IS_TABLE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_SOULFORGE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> IS_ARC = new ConcurrentHashMap<>();

    /** 附加产物（ARC 的 addedItems）：物品 + 几率 */
    public record Chanced(GenericStack stack, float chance) {}

    private BloodMagicCompat() {}

    public static boolean isAlchemyTable(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_TABLE.computeIfAbsent(recipe.getClass(),
                c -> ALCHEMY_TABLE.equals(c.getName()));
    }

    public static boolean isSoulForge(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_SOULFORGE.computeIfAbsent(recipe.getClass(),
                c -> SOUL_FORGE.equals(c.getName()));
    }

    public static boolean isArc(@Nullable Recipe<?> recipe) {
        return recipe != null && IS_ARC.computeIfAbsent(recipe.getClass(),
                c -> ARC.equals(c.getName()));
    }

    /** 输入槽：{@code input} 字段是 {@code List<Ingredient>}（soulforge 的 input0..input3 也收进这里） */
    public static List<List<GenericStack>> listInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        Object input = readField(recipe, "input");
        if (!(input instanceof Iterable<?> it)) return slots;
        for (Object element : it) {
            var options = optionsOf(element);
            if (!options.isEmpty()) slots.add(options);
        }
        return slots;
    }

    /** ARC 主产物：{@code output} 字段（{@code getResultItem} 给不出东西 → 只能自己读） */
    public static ItemStack arcOutput(@Nullable Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        Object raw = readField(recipe, "output");
        return raw instanceof ItemStack stack && !stack.isEmpty() ? stack : ItemStack.EMPTY;
    }

    /** ARC 的流体产出：{@code outputFluid}（FluidStack，可为空） */
    @Nullable
    public static GenericStack arcOutputFluid(@Nullable Recipe<?> recipe) {
        if (recipe == null) return null;
        Object raw = readField(recipe, "outputFluid");
        if (raw instanceof FluidStack fluid && !fluid.isEmpty()) {
            return new GenericStack(AEFluidKey.of(fluid.getFluid()), Math.max(1, fluid.getAmount()));
        }
        return null;
    }

    /**
     * ARC 的流体输入（JSON 的 {@code inputFluid}，如 200 mB 水）。
     * 字段类型是 {@code FluidStackIngredient}（Single/Tagged/Multi 三种实现）
     * → 优先走 {@code getRepresentations()}（返回 {@code List<FluidStack>}），拿不到再退回字段 {@code fluidStack}。
     */
    public static List<GenericStack> arcFluidInput(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object raw = readField(recipe, "inputFluid");
        if (raw == null) return List.of();

        var stacks = new ArrayList<FluidStack>();
        if (raw instanceof FluidStack direct && !direct.isEmpty()) {
            stacks.add(direct);
        } else {
            Object reps = invokeNoArg(raw, "getRepresentations");
            if (reps instanceof Iterable<?> it) {
                for (Object element : it) {
                    if (element instanceof FluidStack fluid && !fluid.isEmpty()) stacks.add(fluid);
                }
            }
            if (stacks.isEmpty()) {
                Object single = readField(raw, "fluidStack");
                if (single instanceof FluidStack fluid && !fluid.isEmpty()) stacks.add(fluid);
            }
        }
        if (stacks.isEmpty()) return List.of();

        var options = new ArrayList<GenericStack>();
        for (FluidStack fluid : stacks) {
            options.add(new GenericStack(AEFluidKey.of(fluid.getFluid()), Math.max(1, fluid.getAmount())));
            if (options.size() >= MAX_OPTIONS) break;
        }
        return List.copyOf(options);
    }

    /** ARC 的几率附加产物：字段 {@code addedItems}（元素有 type（原料）+ chance） */
    public static List<Chanced> arcAddedOutputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<Chanced>();
        if (recipe == null) return out;
        Object list = readField(recipe, "addedItems");
        if (!(list instanceof Iterable<?> it)) return out;
        for (Object element : it) {
            var options = optionsOf(readField(element, "type"));
            if (options.isEmpty()) continue;
            Object chance = readField(element, "chance");
            float p = chance instanceof Number n ? n.floatValue() : -1f;
            out.add(new Chanced(options.get(0), p));
        }
        return out;
    }

    // ── 反射工具 ──

    private static List<GenericStack> optionsOf(@Nullable Object value) {
        if (!(value instanceof Ingredient ingredient)) return List.of();
        var options = new ArrayList<GenericStack>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            options.add(new GenericStack(AEItemKey.of(stack), 1));
            if (options.size() >= MAX_OPTIONS) break;
        }
        return List.copyOf(options);
    }

    @Nullable
    private static Object invokeNoArg(Object target, String methodName) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (var m : c.getMethods()) {
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
