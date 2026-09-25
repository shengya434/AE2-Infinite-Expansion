package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thermal（热力系列：thermal_foundation / thermal_expansion + cofh_core）配方兼容层
 * —— <b>纯类名反射，零硬依赖</b>。
 * <p>
 * 2026-09-20 sensei：Thermal 的 30 个配方类型一条都读不出来。真因是它们的配方类
 * （{@code cofh.thermal.core.util.recipes.machine.*} / {@code ...recipes.device.*}，
 * 物理位置在 thermal_foundation 的嵌套 jar {@code META-INF/jarjar/thermal_core-*.jar} 里）
 * 把数据放在**自己的字段**里，标准 {@link Recipe} API 是空的
 * （{@code getIngredients()} 没实现、{@code getResultItem()} 返回 EMPTY）
 * → {@code QianJiRecipeModel.fromRecipe} 两头都读不到 → 整类被判「提取失败」。
 * <p>
 * 好在数据全部有 public getter（javap 实证，2026-09-20）：
 * <pre>
 * cofh.thermal.lib.util.recipes.ThermalRecipe（所有机器类继承它）
 *   getInputItems()          → List&lt;Ingredient&gt;          （MC 标准 Ingredient）
 *   getInputFluids()         → List&lt;FluidIngredient&gt;     （cofh 自有类型）
 *   getOutputItems()         → List&lt;ItemStack&gt;
 *   getOutputFluids()        → List&lt;FluidStack&gt;
 *   getOutputItemChances()   → List&lt;Float&gt;               （与 outputItems 平行；缺/null → 1.0）
 *
 * cofh.lib.common.fluid.FluidIngredient（在 cofh_core jar 里）
 *   getFluids() → FluidStack[]   /  isEmpty()
 *
 * 设备映射（方块 → 产出，环境方块不消耗）：
 *   RockGenMapping      getResult() / getBelow() / getAdjacent()      → ItemStack / Block
 *   TreeExtractorMapping getSapling() / getTrunk() / getLeaves() / getFluid()
 *                        （trunk/leaves 是 BlockIngredient#getBlockStates()）
 *   HiveExtractorMapping getHive() / getItem() / getFluid()
 * </pre>
 * <p>
 * <b>按设计排除</b>（不是配方，别当失败）：类名以 {@code Catalyst} / {@code Fuel} / {@code Boost} 结尾的
 * （SmelterCatalyst、PulverizerCatalyst、InsolatorCatalyst、NumismaticFuel、FisherBoost、
 * PotionDiffuserBoost、TreeExtractorBoost…）。前两类是机器增益 / 只产能量，后一类是设备概率修正。
 * <p>
 * ⚠ 已知取舍：{@code CrafterRecipe}（自动合成机）是**运行时**从别的配方现造的包装类，
 * 它自己的 {@code getOutputItemChances(IMachineInventory)} 带参、基类那套字段也可能为空
 * → 这里读到空就当没有（不会炸，最多少一类）。要支持得单独做，本次不做。
 * <p>
 * <b>⚠ 反射的铁律（2026-09-20，这个类就因为违反它整类丢输入）</b>：
 * {@code cofh.*} 的方法（{@code getInputItems} / {@code getFluids} / {@code getBlockStates} …）
 * **不在 MC 混淆范围内**，按名字反射没问题；但 {@code net.minecraft.*} 与 {@code net.minecraftforge.*}
 * 的成员在生产环境是 **SRG 名**（如 {@code Ingredient.getItems()} → {@code m_43908_}、
 * {@code BlockState.getBlock()} → {@code m_60734_}），<b>按 MCP 名反射必然 NoSuchMethodException</b>
 * （运行时探针实证：{@code getMethod("getItems")} → NoSuchMethodException，
 *  {@code getMethod("m_43908_")} → ItemStack[]）。
 * 原版/Forge 的类我们**自己的构建会重映射直接调用**，所以正确做法是：
 * 反射只用来从 Thermal 对象上取回原版类型的对象，拿到后强转 + 直接调。
 */
public final class ThermalCompat {

    /** 一个产出（含几率；chance ≥ 1 视为确定产出） */
    public record Stat(GenericStack stack, float chance) {}

    /** 判定：类名前缀 + 配方包路径（machine = 机器配方，device = 设备映射） */
    private static final String THERMAL_PKG = "cofh.thermal.";
    private static final String MACHINE_PKG = ".util.recipes.machine.";
    private static final String DEVICE_PKG = ".util.recipes.device.";

    /** 设备映射配方的判定（只用来决定「方块物品算不算不消耗」这一条，见 #isCatalystSlot） */
    private static final Map<Class<?>, Boolean> IS_DEVICE = new ConcurrentHashMap<>();

    /** 按设计排除的类名后缀 */
    private static final String[] EXCLUDED_SUFFIXES = {"Catalyst", "Fuel", "Boost"};

    /** 每个输入槽最多列多少候选（标签展开可能上千） */
    private static final int MAX_OPTIONS_PER_SLOT = 32;

    /** 设备映射里的「结果」方法族（不同类叫法不同） */
    private static final String[] DEVICE_RESULT_METHODS = {"getResult", "getItem"};

    /** 环境方块槽（不消耗）的方法族：发光的方块喂给设备用，配方本身不消耗它 */
    private static final String[] DEVICE_ENV_BLOCK_METHODS = {
            "getBelow", "getAdjacent", "getSapling", "getTrunk", "getLeaves", "getHive"
    };

    private static final Map<Class<?>, Boolean> IS_THERMAL = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> EXCLUDED = new ConcurrentHashMap<>();

    private ThermalCompat() {}

    // ════════════════════════════════════════════════════════
    //  判定
    // ════════════════════════════════════════════════════════

    /**
     * 是不是 Thermal 的自用配方类。
     * <p>
     * 只按**类名字符串**判（零硬依赖）：以 {@code cofh.thermal.} 开头，且类名里含
     * {@code .util.recipes.machine.} 或 {@code .util.recipes.device.}。
     */
    public static boolean isThermalRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_THERMAL.computeIfAbsent(recipe.getClass(), c -> {
            String name = c.getName();
            if (!name.startsWith(THERMAL_PKG)) return false;
            return name.contains(MACHINE_PKG) || name.contains(DEVICE_PKG);
        });
    }

    /**
     * 是不是 {@code .util.recipes.device.} 的**设备映射**配方（tree_extractor / rock_gen / hive_extractor）。
     * <p>
     * 这个判据只服务一件事：设备映射的输入是"设备旁边要有这个方块"（不消耗），
     * 而机器配方（{@code .util.recipes.machine.}）里的方块物品是**真被吃掉**的
     * （press 的 {@code *_unpacking} 首料就是 {@code *_block}）。
     */
    private static boolean isDeviceRecipe(Recipe<?> recipe) {
        return IS_DEVICE.computeIfAbsent(recipe.getClass(), c -> {
            String name = c.getName();
            return name.startsWith(THERMAL_PKG) && name.contains(DEVICE_PKG);
        });
    }

    /**
     * 按设计排除的配方（不是"读不出来"，是我们主动不做）。
     *
     * @return 类名以 Catalyst / Fuel / Boost 结尾 → true
     */
    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return EXCLUDED.computeIfAbsent(recipe.getClass(), c -> {
            String simple = c.getSimpleName();
            for (String suffix : EXCLUDED_SUFFIXES) {
                if (simple.endsWith(suffix)) return true;
            }
            return false;
        });
    }

    // ════════════════════════════════════════════════════════
    //  输入
    // ════════════════════════════════════════════════════════

    /**
     * 输入槽：物品 Ingredient（一槽多候选）+ 流体（每个 FluidStack 一个槽）+ 设备映射的环境方块槽。
     * <p>
     * 环境方块（rock_gen 的 below/adjacent、tree_extractor 的 sapling/trunk/leaves、hive_extractor 的 hive）
     * 也当成输入槽给出 —— 它们是"设备旁边要有这个方块"，**不消耗**，由
     * {@link #isCatalystSlot(Recipe, int, List)} 按「recipe + 槽序号」识别
     * （方块 id 通常不含 {@code _die}，所以那条路只对**设备映射配方**额外放行方块物品：
     *  见 {@link #isDeviceEnvSlot(Recipe, int)} —— 机器配方里的方块是真被吃掉的）。
     */
    public static List<List<GenericStack>> inputSlots(Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        try {
            // 物品输入：Ingredient → 每个候选 ItemStack 一个候选
            for (Object ingredient : asList(invoke(recipe, "getInputItems"))) {
                var options = itemIngredientOptions(ingredient);
                if (!options.isEmpty()) slots.add(options);
            }
            // 流体输入：FluidIngredient.getFluids() → 每个 FluidStack 一个槽
            for (Object fluidIngredient : asList(invoke(recipe, "getInputFluids"))) {
                var options = fluidIngredientOptions(fluidIngredient);
                if (!options.isEmpty()) slots.add(options);
            }
            // 设备映射：环境方块（不消耗）
            for (String methodName : DEVICE_ENV_BLOCK_METHODS) {
                var options = blockOptions(invoke(recipe, methodName));
                if (!options.isEmpty()) slots.add(options);
            }
        } catch (Throwable ignored) {
            // 反射层绝不把异常抛给调用方
        }
        return slots;
    }

    /**
     * 该槽是不是「设备映射的环境方块」（不消耗）。{@code slotIndex} 是
     * {@link #inputSlots(Recipe)} 返回列表里的下标 —— 按"这个方法能不能从那个槽读回东西"
     * 判断，所以调用方必须用同一个 recipe 的 inputSlots 顺序。
     */
    public static boolean isDeviceEnvSlot(Recipe<?> recipe, int slotIndex) {
        if (recipe == null || slotIndex < 0) return false;
        var slots = inputSlots(recipe);
        if (slotIndex >= slots.size()) return false;
        return isDeviceEnvSlot(slots.get(slotIndex));
    }

    /** 候选列表版本：全部候选都是方块物品才算环境方块 */
    public static boolean isDeviceEnvSlot(List<GenericStack> options) {
        if (options == null || options.isEmpty()) return false;
        for (GenericStack option : options) {
            if (option == null || !(option.what() instanceof AEItemKey key) || !isBlockItem(key)) return false;
        }
        return true;
    }

    /**
     * 该槽是不是**不消耗**的槽。
     * <p>
     * 两种情形：① {@code *_die} 压印模具（注册名 path 含 {@code _die}）；
     * ② **设备映射配方**（{@code .util.recipes.device.}）的环境方块 —— 设备只是"旁边要有这个方块"，
     * 配方本身不消耗它。
     * <p>
     * ⚠ 2026-09-20 修 bug：原来"候选是方块物品就算不消耗"对**所有** Thermal 配方生效，
     * 而 press 的 {@code *_unpacking} 恰恰拿方块当被消耗原料（45/63 条首料是
     * {@code *_block} / blue_ice / bricks / clay，例：{@code press_amethyst_unpacking}
     * = 紫水晶块 → 4 紫水晶碎片）→ 样板会变成"**一块方块无限换碎片**"（刷物品）。
     * 所以这条只保留给设备映射配方，机器配方里**只有** {@code _die} 后缀算不消耗。
     *
     * @param options 调用方手上那个槽的候选（= {@link #inputSlots(Recipe)} 的同一序号元素，
     *                传进来省一次重复提取）；{@code slotIndex} 必须与它对应
     */
    public static boolean isCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        if (options == null || options.isEmpty()) return false;
        boolean sawItem = false;
        for (GenericStack option : options) {
            if (option == null || !(option.what() instanceof AEItemKey key)) continue;
            sawItem = true;
            String path = itemPath(key);
            if (path != null && path.contains("_die")) return true;
        }
        // 方块物品 = 环境方块这一条**只**对设备映射生效；机器配方里方块是原料，必须照实消耗
        return sawItem && recipe != null && isDeviceRecipe(recipe) && isDeviceEnvSlot(recipe, slotIndex);
    }

    // ════════════════════════════════════════════════════════
    //  产出
    // ════════════════════════════════════════════════════════

    /**
     * 产出：物品（带 getOutputItemChances 的概率）+ 流体（chance 1.0）+ 设备映射的产出。
     */
    public static List<Stat> outputs(Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        if (recipe == null) return out;
        try {
            // 物品产出：概率与 outputItems 平行；缺失 / null / 长度不够 → 1.0
            List<?> chances = asList(invoke(recipe, "getOutputItemChances"));
            List<?> items = asList(invoke(recipe, "getOutputItems"));
            for (int i = 0; i < items.size(); i++) {
                GenericStack stack = toGeneric(items.get(i));
                if (stack == null || stack.amount() <= 0) continue;
                float chance = 1f;
                if (i < chances.size()) {
                    Object raw = chances.get(i);
                    if (raw instanceof Number n) chance = n.floatValue();
                }
                if (chance > 1f) {
                    // 2026-09-20 修 bug：Thermal 的 chance > 1 是**整数倍率**不是概率
                    // （MachineBlockEntity.resolveOutputs 字节码：chance > 1 → amount = (int) chance * count；
                    //  配方数据里 chance: 2.0 有 46 条，还有 6.0 / 12.5）
                    // → 数量乘以 (int) chance，并且**当必出**（返回的 chance 值 ≥ 1f，由调用方放 primary）。
                    // 不改 → 本来产 2 份的只产 1 份，最多差 12 倍。
                    stack = scaled(stack, (int) chance);
                    out.add(new Stat(stack, chance));
                } else {
                    // (0,1] 照旧按概率（进 chanced）；≤0 = 未声明 → -1f（调用方按必出处理），语义不变
                    out.add(new Stat(stack, chance > 0f ? chance : -1f));
                }
            }
            // 流体产出：Thermal 没有概率字段 → 恒 1.0
            for (Object element : asList(invoke(recipe, "getOutputFluids"))) {
                GenericStack stack = toGeneric(element);
                if (stack != null && stack.amount() > 0) out.add(new Stat(stack, 1f));
            }
            // 设备映射产出：rock_gen → 物品；tree_extractor / hive_extractor → 流体
            for (String methodName : DEVICE_RESULT_METHODS) {
                GenericStack stack = toGeneric(invoke(recipe, methodName));
                if (stack != null && stack.amount() > 0) out.add(new Stat(stack, 1f));
            }
            GenericStack deviceFluid = toGeneric(invoke(recipe, "getFluid"));
            if (deviceFluid != null && deviceFluid.amount() > 0) out.add(new Stat(deviceFluid, 1f));
        } catch (Throwable ignored) {
            // 同上：绝不上抛
        }
        return List.copyOf(dedupe(out));
    }

    /** 整数倍率产出：数量 = 原数量 × multiplier（溢出 / 超 int 时保守退回原数量，绝不吐出天文数字） */
    private static GenericStack scaled(GenericStack stack, int multiplier) {
        if (stack == null || multiplier <= 1) return stack;
        long scaled = (long) stack.amount() * (long) multiplier;
        if (scaled <= 0L || scaled > Integer.MAX_VALUE) {
            // 反射读到脏值（NaN/∞ 会被 (int) 转成 0 或 MAX_VALUE）→ 宁可少给也不要给错
            return stack;
        }
        try {
            return new GenericStack(stack.what(), scaled);
        } catch (Throwable ignored) {
            return stack;
        }
    }

    // ════════════════════════════════════════════════════════
    //  单个原料 → 候选 GenericStack
    // ════════════════════════════════════════════════════════

    /**
     * MC 标准的 {@link Ingredient} → 候选（每个候选数量 = 该 ItemStack 自带 count，如压印要 4 个）。
     * <p>
     * <b>2026-09-20 修 bug（反射名 铁律）</b>：原来这里写的是 {@code invoke(ingredient, "getItems")} ——
     * 用**反射按 MCP 名**去调原版方法。生产环境（SRG）里这个方法叫 {@code m_43908_}，
     * 于是反射每次都抛 {@code NoSuchMethodException} 被吞掉 → 候选恒空 →
     * <b>Thermal 所有物品输入全丢</b>（实机报告：press 227 / smelter 133 / pulverizer 81 / centrifuge 59 /
     * furnace 28 / crucible 14 / sawmill 12 / pyrolyzer 3 / *_recycle 6，共 564 条"无输入"；
     * 而有流体输入的那几类因为走 cofh 自己的 {@code getFluids}（不被 SRG 改名）所以正常）。
     * <p>
     * 修法：<b>原版 / Forge 的类根本不需要反射</b> —— 我们的构建会把直接调用重映射成 SRG 名
     * （javap 复核：直接调用编译出来就是 {@code m_43908_}）。反射只用来**从 Thermal 对象上取回
     * 原版类型的对象**（{@code getInputItems} 是 CoFH 方法 ✓），拿到之后强转 + 直接调。
     */
    private static List<GenericStack> itemIngredientOptions(@Nullable Object ingredient) {
        var options = new ArrayList<GenericStack>();
        // 强转成原版 Ingredient：这里的 instanceof 是安全的（Ingredient 是原版类，双方同一个类加载器），
        // 拿到手之后直接调 getItems()，由 reobf 负责把名字映射成 m_43908_
        if (!(ingredient instanceof Ingredient ing)) return options;
        try {
            for (ItemStack item : ing.getItems()) {
                GenericStack stack = toGeneric(item);
                if (stack == null || stack.amount() <= 0) continue;
                if (!contains(options, stack)) options.add(stack);
                if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
            }
        } catch (Throwable ignored) {
            // Ingredient 在某些状态下（标签未就绪）getItems 可能抛 → 当空处理，不上抛
        }
        return options;
    }

    /** cofh FluidIngredient → 每个 FluidStack 一个候选（数量自带 amount，如 15 mB 树脂） */
    private static List<GenericStack> fluidIngredientOptions(@Nullable Object fluidIngredient) {
        var options = new ArrayList<GenericStack>();
        if (fluidIngredient == null) return options;
        // isEmpty/getFluids 是 cofh 自己的方法（不被 SRG 改名）→ 继续反射，零硬依赖
        if (truthy(invoke(fluidIngredient, "isEmpty"))) return options;
        Object array = invoke(fluidIngredient, "getFluids");
        if (!(array instanceof FluidStack[] fluids)) {
            // 老版本可能给的是 Collection
            for (Object element : asList(array)) {
                GenericStack stack = toGeneric(element);
                if (stack != null && stack.amount() > 0 && !contains(options, stack)) options.add(stack);
            }
            return options;
        }
        for (FluidStack fluid : fluids) {
            // FluidStack 是 Forge 类：getFluid()/getAmount()/isEmpty() 全部直接调（不反射）
            GenericStack stack = toGeneric(fluid);
            if (stack == null || stack.amount() <= 0) continue;
            if (!contains(options, stack)) options.add(stack);
            if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
        }
        return options;
    }

    /**
     * 一个「方块 / 方块原料」对象 → 候选方块物品。
     * <p>
     * 支持三种形态：{@link Block}（RockGenMapping 的 below/adjacent、TreeExtractor 的 sapling）、
     * {@code BlockIngredient}（getBlockStates() → Collection&lt;BlockState&gt;）、
     * 以及 BlockState / ItemStack 本身。
     */
    private static List<GenericStack> blockOptions(@Nullable Object value) {
        var options = new ArrayList<GenericStack>();
        if (value == null) return options;
        try {
            if (value instanceof Block block) {
                addBlock(options, block);
                return options;
            }
            // getBlockStates() 是 cofh BlockIngredient 自己的方法（不被 SRG 改名）→ 反射 ✓
            Object states = invoke(value, "getBlockStates");
            if (states instanceof Collection<?> it) {
                for (Object state : it) {
                    if (state == null) continue;
                    Block block = blockOf(state);
                    if (block != null) addBlock(options, block);
                    if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
                }
                return options;
            }
            // BlockState 本身 / 别的形态
            Block direct = blockOf(value);
            if (direct != null) addBlock(options, direct);
        } catch (Throwable ignored) {
            // 忽略
        }
        return options;
    }

    /**
     * {@link BlockState}（原版）→ Block。
     * <p>
     * <b>2026-09-20 修 bug（同一类坑）</b>：原来用 {@code invoke(value, "getBlock")}，
     * 而生产环境里 {@code BlockState.getBlock()} 叫 {@code m_60734_}（javap + 运行时探针实证：
     * {@code getMethod("getBlock")} → NoSuchMethodException）→ 设备映射的环境方块全部读不到。
     * 现在强转成原版 BlockState 直接调（编译期绑定，reobf 会映射成 m_60734_）。
     */
    @Nullable
    private static Block blockOf(Object value) {
        if (value instanceof Block block) return block;
        if (value instanceof BlockState state) return state.getBlock();   // 原版类 → 直接调，不反射
        return null;
    }

    private static void addBlock(List<GenericStack> options, Block block) {
        try {
            GenericStack stack = toGeneric(block);
            if (stack != null && stack.amount() > 0 && !contains(options, stack)) options.add(stack);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static boolean contains(List<GenericStack> options, GenericStack stack) {
        for (GenericStack existing : options) {
            if (existing.what().equals(stack.what()) && existing.amount() == stack.amount()) return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════
    //  任意 Thermal 侧对象 → GenericStack
    // ════════════════════════════════════════════════════════

    /** ItemStack / FluidStack / Block（方块物品）/ ItemLike → GenericStack（数量为 0 的返回 null） */
    @Nullable
    public static GenericStack toGeneric(@Nullable Object value) {
        if (value == null) return null;
        try {
            if (value instanceof ItemStack item) {
                if (item.isEmpty()) return null;
                return new GenericStack(AEItemKey.of(item), Math.max(1, item.getCount()));
            }
            if (value instanceof FluidStack fluid) {
                if (fluid.isEmpty()) return null;
                return new GenericStack(AEFluidKey.of(fluid), Math.max(1, fluid.getAmount()));
            }
            if (value instanceof Block block) {
                ItemStack item = new ItemStack(block);
                if (item.isEmpty()) return null;
                return new GenericStack(AEItemKey.of(item), 1);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    // ════════════════════════════════════════════════════════
    //  反射工具
    // ════════════════════════════════════════════════════════

    private static List<Stat> dedupe(List<Stat> stats) {
        var result = new ArrayList<Stat>();
        for (Stat stat : stats) {
            boolean dup = false;
            for (Stat existing : result) {
                if (existing.chance() == stat.chance() && existing.stack().what().equals(stat.stack().what())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) result.add(stat);
        }
        return result;
    }

    private static boolean truthy(@Nullable Object value) {
        return value instanceof Boolean b && b;
    }

    /** 注册名 path（如 {@code press_plate_die}） */
    @Nullable
    private static String itemPath(AEItemKey key) {
        try {
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(key.getItem());
            return id == null ? null : id.getPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该 AEItemKey 是不是方块物品（设备映射的环境方块就是靠这个认出来的） */
    private static boolean isBlockItem(AEItemKey key) {
        try {
            return key.getItem() instanceof net.minecraft.world.item.BlockItem;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<?> asList(@Nullable Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list;
        if (value instanceof Iterable<?> it) {
            var list = new ArrayList<>();
            for (Object o : it) list.add(o);
            return list;
        }
        if (value instanceof Object[] array) return List.of(array);
        return List.of(value);
    }

    /** 调 0 参方法（找不到 / 抛异常一律 null） */
    @Nullable
    private static Object invoke(@Nullable Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable ignored) {
            // 继承链上的 public 方法 getMethod 能取到；取不到就是真没有
        }
        return null;
    }

    /**
     * 读字段（含继承链的 protected/private）。
     * <p>
     * 当前 Thermal 版本的 TreeExtractorMapping 有 public {@code getFluid()}，用不上这条路；
     * 留着兜别的版本（javap 实证过它曾是 {@code protected final FluidStack fluid} 无 getter）。
     */
    @Nullable
    @SuppressWarnings("unused")
    private static Object field(@Nullable Object target, String fieldName) {
        if (target == null) return null;
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {
                    // 往父类找
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }
}
