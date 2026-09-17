package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.mojang.datafixers.util.Either;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EnderIO 配方兼容（2026-09-17 sensei 清单第二批里最大的一块）。
 * <p>
 * 这里五类机器的数据**全在字段里**，标准 API 只给一半（所以走独立分支，避免跟标准路径重复加输入）：
 * <pre>
 *   alloy_smelting（AlloySmeltingRecipe）：inputs(List&lt;CountedIngredient&gt;：ingredient+count) + output(ItemStack)
 *                                          → getResultItem 是空的；2227 条里绝大多数是它照 GT/原版熔炼**运行时生成**的
 *   sag_milling   （SagMillingRecipe）    ：input(Ingredient) + outputs(List&lt;OutputItem&gt;：output(Either&lt;ItemStack,
 *                                          SizedTagOutput&gt;) + chance + isOptional)
 *   slicing       （SlicingRecipe）       ：inputs(List&lt;Ingredient&gt;) + output
 *   soul_binding  （SoulBindingRecipe）   ：input(Ingredient) + output
 *   tank          （TankRecipe）          ：input(Ingredient) + fluid(FluidStack) + output + isEmptying
 * </pre>
 * 明确**不做**：
 * <ul>
 *   <li>{@code grinding_ball}（GrindingBallRecipe，10 条）：它是「磨球**定义**」（item + doublingChance +
 *       bonusMultiplier + durability），没有任何原料，不是可合成的配方 → 做不了样板</li>
 *   <li>{@code enchanting}（EnchanterRecipe，41 条）：配方只有 enchantment + costMultiplier + input(CountedIngredient)，
 *       **没有产物字段**（getResultItem 是常量 EMPTY），产物是把输入的那件物品附魔后返回 → 依赖输入物，做不了</li>
 * </ul>
 */
public final class EnderioCompat {

    private static final String ALLOY = "com.enderio.machines.common.recipe.AlloySmeltingRecipe";
    private static final String SAG = "com.enderio.machines.common.recipe.SagMillingRecipe";
    private static final String SLICING = "com.enderio.machines.common.recipe.SlicingRecipe";
    private static final String SOUL = "com.enderio.machines.common.recipe.SoulBindingRecipe";
    private static final String TANK = "com.enderio.machines.common.recipe.TankRecipe";

    private static final int MAX_OPTIONS = 32;

    /** 配方类 → 归哪一类（"?" = 是 EnderIO 的类但不在我们处理范围内） */
    private static final Map<Class<?>, String> KIND = new ConcurrentHashMap<>();

    /** 几率产出（sag_milling 的 outputs） */
    public record Chanced(GenericStack stack, float chance) {}

    private EnderioCompat() {}

    @Nullable
    private static String kindOf(@Nullable Recipe<?> recipe) {
        if (recipe == null) return null;
        if (!recipe.getClass().getName().startsWith("com.enderio.")) return null;
        String kind = KIND.computeIfAbsent(recipe.getClass(), c -> {
            String n = c.getName();
            if (ALLOY.equals(n)) return ALLOY;
            if (SAG.equals(n)) return SAG;
            if (SLICING.equals(n)) return SLICING;
            if (SOUL.equals(n)) return SOUL;
            if (TANK.equals(n)) return TANK;
            return "?";
        });
        return "?".equals(kind) ? null : kind;
    }

    /** 本类亲自处理这五类（走独立分支，不再走标准 getIngredients/getResultItem） */
    public static boolean isHandled(@Nullable Recipe<?> recipe) {
        return kindOf(recipe) != null;
    }

    public static boolean isAlloySmelting(@Nullable Recipe<?> recipe) {
        return ALLOY.equals(kindOf(recipe));
    }

    public static boolean isSagMilling(@Nullable Recipe<?> recipe) {
        return SAG.equals(kindOf(recipe));
    }

    public static boolean isSlicing(@Nullable Recipe<?> recipe) {
        return SLICING.equals(kindOf(recipe));
    }

    public static boolean isSoulBinding(@Nullable Recipe<?> recipe) {
        return SOUL.equals(kindOf(recipe));
    }

    public static boolean isTank(@Nullable Recipe<?> recipe) {
        return TANK.equals(kindOf(recipe));
    }

    // ── 输入 ──

    /** alloy_smelting：{@code inputs} 是 {@code List<CountedIngredient>}（ingredient + count，数量别丢） */
    public static List<List<GenericStack>> countedInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        Object inputs = readField(recipe, "inputs");
        if (!(inputs instanceof Iterable<?> it)) return slots;
        for (Object element : it) {
            Object ing = readField(element, "ingredient");
            if (!(ing instanceof Ingredient ingredient)) continue;
            long count = Math.max(1, readInt(element, "count"));
            var options = optionsOf(ingredient, count);
            if (!options.isEmpty()) slots.add(options);
        }
        return slots;
    }

    /** slicing：{@code inputs} 是 {@code List<Ingredient>}（没有数量，一律 1） */
    public static List<List<GenericStack>> ingredientListInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        Object inputs = readField(recipe, "inputs");
        if (!(inputs instanceof Iterable<?> it)) return slots;
        for (Object element : it) {
            if (!(element instanceof Ingredient ingredient)) continue;
            var options = optionsOf(ingredient, 1);
            if (!options.isEmpty()) slots.add(options);
        }
        return slots;
    }

    /** sag_milling / soul_binding / tank：{@code input} 是单个 Ingredient */
    public static List<GenericStack> singleInput(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object input = readField(recipe, "input");
        if (!(input instanceof Ingredient ingredient)) return List.of();
        return optionsOf(ingredient, 1);
    }

    /** tank：{@code fluid} 字段（FluidStack，如 1000 mB 水） */
    public static List<GenericStack> fluidInput(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object raw = readField(recipe, "fluid");
        if (raw instanceof FluidStack fluid && !fluid.isEmpty()) {
            return List.of(new GenericStack(AEFluidKey.of(fluid.getFluid()), Math.max(1, fluid.getAmount())));
        }
        return List.of();
    }

    // ── 产出 ──

    /** alloy / slicing / soul_binding / tank：{@code output} 字段（ItemStack） */
    public static ItemStack output(@Nullable Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        Object raw = readField(recipe, "output");
        return raw instanceof ItemStack stack && !stack.isEmpty() ? stack : ItemStack.EMPTY;
    }

    /**
     * sag_milling：{@code outputs} 是 {@code List<OutputItem>}，元素字段
     * {@code output}（{@code Either<ItemStack, SizedTagOutput>}）+ {@code chance} + {@code isOptional}。
     * 标签型产出（SizedTagOutput：itemTag + count）取标签里**第一个**物品当代表（多候选会撑爆槽位）。
     */
    public static List<Chanced> sagOutputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<Chanced>();
        if (recipe == null) return out;
        Object outputs = readField(recipe, "outputs");
        if (!(outputs instanceof Iterable<?> it)) return out;
        for (Object element : it) {
            float chance = readFloat(element, "chance");
            Object raw = readField(element, "output");
            if (!(raw instanceof Either<?, ?> either)) continue;
            Object left = either.left().orElse(null);
            if (left instanceof ItemStack stack && !stack.isEmpty()) {
                out.add(new Chanced(GenericStack.fromItemStack(stack), chance));
                continue;
            }
            Object right = either.right().orElse(null);
            if (right == null) continue;
            Object tag = readField(right, "itemTag");
            if (!(tag instanceof TagKey<?> tagKey)) continue;
            long count = Math.max(1, readInt(right, "count"));
            GenericStack first = firstOfTag(tagKey, count);
            if (first != null) out.add(new Chanced(first, chance));
        }
        return out;
    }

    // ── 反射工具 ──

    @SuppressWarnings("unchecked")
    @Nullable
    private static GenericStack firstOfTag(TagKey<?> tagKey, long count) {
        try {
            var itemTag = (TagKey<Item>) tagKey;
            var tagged = ForgeRegistries.ITEMS.tags().getTag(itemTag);
            for (Item item : tagged) {
                return new GenericStack(AEItemKey.of(item), count);
            }
        } catch (Throwable ignored) {
            // 标签不存在/解析失败 → 没有候选
        }
        return null;
    }

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
        Object raw = readField(target, name);
        return raw instanceof Number n ? n.intValue() : 1;
    }

    private static float readFloat(Object target, String name) {
        Object raw = readField(target, name);
        return raw instanceof Number n ? n.floatValue() : -1f;
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
