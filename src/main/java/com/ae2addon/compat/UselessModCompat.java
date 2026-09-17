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

    /**
     * 催化剂 + 模具（2026-09-17 sensei 定调：按锭的种类分三种处理）。
     * <p>
     * 背景：万象合金炉里催化剂的作用是**给机器加并行度**；而千机天生无限并行，
     * 玩家为它花掉的锭换不来任何提升 —— 体验上纯亏。所以：
     * <ul>
     *   <li><b>有用锭</b>（{@code useful_ingot}）→ 当**催化剂**（不消耗），保留为门槛</li>
     *   <li><b>无用锭</b>（{@code useless_ingot_tier_N}）：
     *     <ul>
     *       <li>这条配方**本身就在合成锭**（升阶链：tier_N 的催化剂正是 tier_{N-1}）→ 属正常合成行为
     *           → **按消耗记**</li>
     *       <li>产物不是锭（晶体/齿轮/板…，催化剂写的是共享标签 {@code #useless_mod:useless_ingots}）
     *           → 那只是买并行度的 → **不记这个输入**</li>
     *     </ul></li>
     *   <li>非锭类催化剂（万一有）→ 维持原样，当不消耗催化剂</li>
     *   <li>模具（{@code metal_mold_*}）同样是放着反复用的 → 不消耗</li>
     * </ul>
     */
    public static List<SlotSpec> specialInputs(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<SlotSpec>();
        if (recipe == null) return slots;
        Object catalyst = readField(recipe, "catalyst");
        if (catalyst instanceof Ingredient ingredient && !isAlsoMaterial(recipe, ingredient)) {
            long count = Math.max(1, readInt(recipe, "catalystCount"));
            int role = catalystRole(recipe, ingredient);
            if (role == ROLE_CATALYST) {
                addIngredient(slots, catalyst, count, true);    // 不消耗的门槛
            } else if (role == ROLE_CONSUME) {
                addIngredient(slots, catalyst, count, false);   // 正常消耗（升阶链）
            }
            // ROLE_DROP：只为并行度服务 → 干脆不记（千机不需要那份并行）
        }
        addIngredient(slots, readField(recipe, "mold"), 1, true);
        return slots;
    }

    private static final int ROLE_CATALYST = 0;   // 记为「不消耗」的催化剂
    private static final int ROLE_CONSUME = 1;    // 记为正常消耗品
    private static final int ROLE_DROP = 2;       // 直接不记（只为并行度服务）

    /**
     * 催化剂按哪种角色记录：见 {@link #specialInputs} 的说明。
     * 判据来自配方数据本身：催化剂候选里是哪种锭 + 这条配方产不产锭。
     */
    private static int catalystRole(Recipe<?> recipe, Ingredient catalyst) {
        boolean anyUseful = false;
        boolean anyUseless = false;
        for (ItemStack stack : catalyst.getItems()) {
            if (stack.isEmpty()) continue;
            String path = itemPath(stack);
            if ("useful_ingot".equals(path)) {
                anyUseful = true;
            } else if (path.startsWith("useless_ingot_tier_")) {
                anyUseless = true;
            } else {
                return ROLE_CATALYST;   // 不是锭类催化剂 → 维持「不消耗」
            }
        }
        if (!anyUseless) return ROLE_CATALYST;                          // 只有有用锭 → 催化剂
        if (!anyUseful && producesIngot(recipe)) return ROLE_CONSUME;   // 升阶链：正常消耗
        return ROLE_DROP;                                               // 其余：不记
    }

    /** 这条配方的产物里有没有「锭」（无用锭各阶 / 有用锭 / 可能有用锭） */
    private static boolean producesIngot(Recipe<?> recipe) {
        Object outputs = readField(recipe, "outputItems");
        if (!(outputs instanceof Iterable<?> it)) return false;
        for (Object element : it) {
            if (!(element instanceof ItemStack stack) || stack.isEmpty()) continue;
            String path = itemPath(stack);
            if (path.startsWith("useless_ingot_tier_") || "useful_ingot".equals(path)
                    || "possible_useful_ingot".equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static String itemPath(ItemStack stack) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? "" : id.getPath();
    }

    /**
     * 催化剂里的物品是否**也**是该配方的输入/输出物：
     * 是的话它已经按物料记过，不再当催化剂重复记（对应 sensei 那句「除非其为输入或输出物」）。
     */
    private static boolean isAlsoMaterial(Recipe<?> recipe, Ingredient catalyst) {
        var wanted = new java.util.HashSet<net.minecraft.world.item.Item>();
        for (ItemStack stack : catalyst.getItems()) {
            if (!stack.isEmpty()) wanted.add(stack.getItem());
        }
        if (wanted.isEmpty()) return false;
        Object inputs = readField(recipe, "inputItems");
        if (inputs instanceof Iterable<?> it) {
            for (Object element : it) {
                if (!(element instanceof Ingredient ing)) continue;
                for (ItemStack stack : ing.getItems()) {
                    if (!stack.isEmpty() && wanted.contains(stack.getItem())) return true;
                }
            }
        }
        Object outputs = readField(recipe, "outputItems");
        if (outputs instanceof Iterable<?> it) {
            for (Object element : it) {
                if (element instanceof ItemStack stack && !stack.isEmpty()
                        && wanted.contains(stack.getItem())) return true;
            }
        }
        return false;
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
