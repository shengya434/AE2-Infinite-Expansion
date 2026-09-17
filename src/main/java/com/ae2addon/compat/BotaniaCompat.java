package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Botania（植物魔法）配方兼容层 —— 反射实现，零硬依赖（跟 {@link CreateCompat} 一个风格）。
 * <p>
 * 2026-09-16/17 sensei 指派「魔源和魔力的输入检测 + 修一部分无法识别的配方」。Botania 这边标准 API
 * 只能看到物品，几处**配方数据在标准 API 之外**：
 * <ol>
 *   <li><b>mana 消耗</b>：{@code mana_infusion}/{@code runic_altar}/{@code terra_plate} 的配方类都有
 *       {@code mana} 字段（字节码实证：getManaToConsume / getManaUsage / getMana 都读它），
 *       但标准 {@code getIngredients()} 看不到 → 样板缺「魔力」输入槽</li>
 *   <li><b>花药台（实现类 {@code PetalsRecipe}）</b>：{@code getIngredients()} 只报花瓣，
 *       **试剂（任意种子）在 {@code getReagent()} 里**；另外还固定要**一桶水（1000 mB）**</li>
 *   <li><b>方块状态型配方</b>（输入/输出是 Botania 自有的 {@code StateIngredient}，不是 Ingredient）：
 *       <ul>
 *         <li>凝矿兰 {@code orechid} / {@code orechid_ignem}：按 {@code weight} 概率产矿</li>
 *         <li>变形菌 {@code marimorphosis}：同样按 {@code weight}（另有 biome 加成，取基础权重）</li>
 *         <li>凝露雏菊 {@code pure_daisy} / {@code state_copying_pure_daisy}：1:1 恒定转换
 *             （输入 {@code getInput()}，产物是 {@code getOutputState()} 那个方块）</li>
 *       </ul>
 *       标准 API 完全看不到这些 → 就是 sensei 报的「无法识别」</li>
 * </ol>
 */
public final class BotaniaCompat {

    private static final String PREFIX = "vazkii.botania.";
    private static final String PETALS_RECIPE = "vazkii.botania.common.crafting.PetalsRecipe";
    private static final String ORECHID_RECIPE = "vazkii.botania.common.crafting.OrechidRecipe";
    private static final String ORECHID_IGNEM_RECIPE = "vazkii.botania.common.crafting.OrechidIgnemRecipe";
    private static final String MARIMORPHOSIS_RECIPE = "vazkii.botania.common.crafting.MarimorphosisRecipe";
    /** 凝露雏菊有两个实现（{@code PureDaisyRecipe} 与 {@code StateCopyingPureDaisyRecipe}，后者继承前者） */
    private static final String PURE_DAISY_SUFFIX = "PureDaisyRecipe";

    /**
     * 权重型配方 → 它的 mana 常量所在方块实体类（Botania 把消耗写在机器里，配方 JSON 没有）。
     * 这些都是字节码实证的类：{@code OrechidBlockEntity.COST = 17500} 等。
     */
    private static final Map<String, String> WEIGHTED_FLOWER = Map.of(
            ORECHID_RECIPE, "vazkii.botania.common.block.flower.functional.OrechidBlockEntity",
            ORECHID_IGNEM_RECIPE, "vazkii.botania.common.block.flower.functional.OrechidIgnemBlockEntity",
            MARIMORPHOSIS_RECIPE, "vazkii.botania.common.block.flower.functional.MarimorphosisBlockEntity");

    /** 花药台每合成一次耗掉整整一桶水（2026-09-16 sensei 指出；Botania 内部就是「一桶」三态） */
    public static final long APOTHECARY_WATER = 1000L;

    /** 每个槽最多记多少候选（标签展开可能很长） */
    private static final int MAX_OPTIONS = 32;

    private static final Map<Class<?>, Boolean> IS_BOTANIA = new ConcurrentHashMap<>();
    /** 配方类 → mana 消耗（-1 = 这个类没有 mana 字段） */
    private static final Map<Class<?>, Integer> MANA = new ConcurrentHashMap<>();
    /** 类名#字段 → 常量值（-2 = 查过了没有） */
    private static final Map<String, Integer> CONSTANTS = new ConcurrentHashMap<>();

    /** 权重型配方的总权重：按配方类型（botania:orechid / _ignem / marimorphosis）分别求和，换管理器就清空 */
    private static RecipeManager cachedManager;
    private static final Map<String, Integer> TOTAL_BY_TYPE = new ConcurrentHashMap<>();

    private BotaniaCompat() {}

    /** 是不是 Botania 的配方（按包名判，不硬编码具体类） */
    public static boolean isBotaniaRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_BOTANIA.computeIfAbsent(recipe.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                if (k.getName().startsWith(PREFIX)) return true;
            }
            return false;
        });
    }

    /**
     * 配方自带的 mana 消耗（正数）；没有 mana 字段 → -1。
     * <p>
     * 三个配方类（mana_infusion / runic_altar / terra_plate）的字段名都是 {@code mana}（字节码实证），
     * 所以一个字段名通吃。
     */
    public static int manaCost(@Nullable Recipe<?> recipe) {
        if (recipe == null) return -1;
        Integer cached = MANA.get(recipe.getClass());
        if (cached != null) return cached;
        Object raw = readField(recipe, "mana");
        int value = raw instanceof Number n ? n.intValue() : -1;
        MANA.put(recipe.getClass(), value);
        return value;
    }

    /** 花药台配方（{@code botania:petal_apothecary} ↔ {@code PetalsRecipe}） */
    public static boolean isPetalApothecary(@Nullable Recipe<?> recipe) {
        return recipe != null && PETALS_RECIPE.equals(recipe.getClass().getName());
    }

    /**
     * 花药台的「试剂」= **任意种子**（{@code #botania:seed_apothecary_reagent}：
     * 小麦/甜菜/西瓜/南瓜种子，外加 {@code #forge:seeds}）。
     * <p>
     * 标准 {@code getIngredients()} 只报花瓣、不含试剂 → 只能自己调 {@code getReagent()} 拿。
     */
    public static List<GenericStack> reagentOptions(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object reagent = invokeNoArg(recipe, "getReagent");
        if (!(reagent instanceof Ingredient ingredient)) return List.of();
        return optionsOf(ingredient);
    }

    /** 花药台的水：1000 mB 流体，**直接从 ME 网络抽**（不是水桶物品，跟 Create 流体一个路子） */
    public static List<GenericStack> waterSlot() {
        return List.of(new GenericStack(AEFluidKey.of(Fluids.WATER), APOTHECARY_WATER));
    }

    // ── 权重型「方块状态」配方：凝矿兰（orechid / orechid_ignem）+ 变形菌（marimorphosis）──

    /** 是不是权重型方块状态配方（产物按 weight 概率出） */
    public static boolean isWeightedStateRecipe(@Nullable Recipe<?> recipe) {
        return recipe != null && WEIGHTED_FLOWER.containsKey(recipe.getClass().getName());
    }

    /** 输入（StateIngredient → 物品候选，通常是石头 / 深板岩 / 下界岩 / 可转化标签） */
    public static List<List<GenericStack>> stateInputs(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        var options = stateItems(invokeNoArg(recipe, "getInput"));
        return options.isEmpty() ? List.of() : List.of(options);
    }

    /** 产物（StateIngredient → 物品候选，如煤矿石 / 变形石） */
    public static List<GenericStack> stateOutputs(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        return stateItems(invokeNoArg(recipe, "getOutput"));
    }

    /** 配方权重（{@code getWeight()}；变形菌另有个 biome 加成版 {@code getWeight(Level, BlockPos)}，不取） */
    public static int stateWeight(@Nullable Recipe<?> recipe) {
        if (recipe == null) return -1;
        Object raw = invokeNoArg(recipe, "getWeight");
        return raw instanceof Number n ? n.intValue() : -1;
    }

    /**
     * 同类型（{@code botania:orechid} / {@code botania:orechid_ignem} / {@code botania:marimorphosis}）
     * **全部配方的权重和** —— 单条配方的概率 = 自身权重 / 这个总和。
     * <p>
     * 拿不到配方管理器（比如多人游戏客户端）→ 返回 -1（概率未知）。
     * 注意：**不能**用 Botania 自己的 {@code OrechidManager}，它是服务端 reload 监听器。
     */
    public static int stateTotalWeight(@Nullable Recipe<?> recipe) {
        if (recipe == null) return -1;
        RecipeManager manager = managerOrNull();
        if (manager == null) return -1;
        if (manager != cachedManager) {   // 换世界/重载数据包 → 缓存失效
            synchronized (TOTAL_BY_TYPE) {
                TOTAL_BY_TYPE.clear();
                cachedManager = manager;
            }
        }
        String type = String.valueOf(recipe.getType());
        Integer cached = TOTAL_BY_TYPE.get(type);
        if (cached != null) return cached;
        int total = 0;
        for (var other : manager.getRecipes()) {
            if (other == null || !isWeightedStateRecipe(other)) continue;
            if (!type.equals(String.valueOf(other.getType()))) continue;
            int weight = stateWeight(other);
            if (weight > 0) total += weight;
        }
        if (total > 0) TOTAL_BY_TYPE.put(type, total);
        return total > 0 ? total : -1;
    }

    /**
     * 每次转化的 mana 消耗：机器硬编码常量（{@code OrechidBlockEntity.COST} = 17500 等，字节码实证）。
     * 取不到 → 0（= 不加魔力槽）。
     */
    public static int stateManaCost(@Nullable Recipe<?> recipe) {
        if (recipe == null) return 0;
        String flower = WEIGHTED_FLOWER.get(recipe.getClass().getName());
        if (flower == null) return 0;
        int cost = staticInt(flower, "COST");
        if (cost <= 0) cost = staticInt(flower, "COST_GOG");   // 花园 of glass 模式用另一个常量
        return Math.max(0, cost);
    }

    // ── 凝露雏菊（pure_daisy / state_copying_pure_daisy）：1:1 恒定转换 ──

    /** 是不是凝露雏菊配方（两个实现类的名字都以 {@code PureDaisyRecipe} 结尾） */
    public static boolean isPureDaisy(@Nullable Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().endsWith(PURE_DAISY_SUFFIX);
    }

    /** 输入（StateIngredient → 物品候选，如 {@code #minecraft:logs}） */
    public static List<List<GenericStack>> pureDaisyInputs(@Nullable Recipe<?> recipe) {
        return stateInputs(recipe);
    }

    /**
     * 产物：凝露雏菊的产出是 **BlockState**（{@code getOutputState()}），不是 StateIngredient
     * → 取方块再转物品；退化时再试 {@code getOutput()}（兼容未来的实现）。
     */
    public static List<GenericStack> pureDaisyOutputs(@Nullable Recipe<?> recipe) {
        if (recipe == null) return List.of();
        Object state = invokeNoArg(recipe, "getOutputState");
        if (state instanceof BlockState blockState) {
            Block block = blockState.getBlock();
            if (block.asItem() != Items.AIR) {
                return List.of(new GenericStack(AEItemKey.of(block.asItem()), 1));
            }
        }
        return stateItems(invokeNoArg(recipe, "getOutput"));
    }

    // ── 反射工具 ──

    /**
     * {@code StateIngredient}（Botania 自己的「方块状态原料」）→ 物品候选。
     * <p>
     * 首选公开方法 {@code getDisplayedStacks()}（返回 {@code List<ItemStack>}，就是 JEI 里显示的那份）；
     * 没有就退回 {@code getBlocks()}（方块列表 → 各自 {@code asItem()}）。
     */
    public static List<GenericStack> stateItems(@Nullable Object stateIngredient) {
        if (stateIngredient == null) return List.of();
        var options = new ArrayList<GenericStack>();
        for (Object element : asList(invokeNoArg(stateIngredient, "getDisplayedStacks"))) {
            if (element instanceof ItemStack stack && !stack.isEmpty()) {
                options.add(new GenericStack(AEItemKey.of(stack), 1));
                if (options.size() >= MAX_OPTIONS) return List.copyOf(options);
            }
        }
        if (!options.isEmpty()) return List.copyOf(options);
        for (Object element : asList(invokeNoArg(stateIngredient, "getBlocks"))) {
            if (element instanceof Block block && block.asItem() != Items.AIR) {
                options.add(new GenericStack(AEItemKey.of(block.asItem()), 1));
                if (options.size() >= MAX_OPTIONS) break;
            }
        }
        return List.copyOf(options);
    }

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

    private static List<GenericStack> optionsOf(Ingredient ingredient) {
        var options = new ArrayList<GenericStack>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            options.add(new GenericStack(AEItemKey.of(stack), 1));
            if (options.size() >= MAX_OPTIONS) break;
        }
        return List.copyOf(options);
    }

    /** 读某个类的静态 int 常量（private 也读）；拿不到 → 0 */
    private static int staticInt(String className, String fieldName) {
        String key = className + "#" + fieldName;
        Integer cached = CONSTANTS.get(key);
        if (cached != null) return cached <= -2 ? 0 : cached;   // -2 = 查过没有
        int value = -2;
        try {
            Class<?> clazz = Class.forName(className, false, BotaniaCompat.class.getClassLoader());
            Field f = clazz.getDeclaredField(fieldName);
            if (Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                Object raw = f.get(null);
                if (raw instanceof Number n) value = n.intValue();
            }
        } catch (Throwable ignored) {
            // 类/字段没有 → 按「没有」处理
        }
        CONSTANTS.put(key, value);
        return value <= 0 ? 0 : value;
    }

    @Nullable
    private static RecipeManager managerOrNull() {
        try {
            var server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.getRecipeManager();
        } catch (Throwable ignored) {
            return null;
        }
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

    @Nullable
    private static Object invokeNoArg(Object target, String methodName) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
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
}
