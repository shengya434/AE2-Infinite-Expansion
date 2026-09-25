package com.ae2addon.compat;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Productive Bees 配方兼容层 —— <b>纯类名反射，零硬依赖</b>。
 * <p>
 * 2026-09-20 sensei：PB 的 13 个配方类型约 530 条一条都读不出来。真因和 Thermal 同款 ——
 * 配方数据放在 {@code cy.jdkdigital.productivebees.common.recipe.*} 自己的字段里，
 * 标准 {@link Recipe} API（{@code getIngredients()} / {@code getResultItem()}）是空的
 * → {@code QianJiRecipeModel.fromRecipe} 两头读不到 → 整类丢。
 * <p>
 * 以下字段全部由 javap 在 {@code productivebees-1.20.1-12.6.0.jar} 上实证（不凭记忆）：
 * <pre>
 * TagOutputRecipe（产出基类）
 *   public final Map&lt;Ingredient, IntArrayTag&gt; itemOutput
 *   getRecipeOutputs() → Map&lt;ItemStack, IntArrayTag&gt;      （更省事，优先用它）
 *   IntArrayTag = [min, max, chance]，chance 是**百分数**（实测取值 2/5/…/80/100）
 *                 → 一律 /100 转 0..1；0 = "几乎不产出" → 跳过该产出（见 percentToChance）
 *
 * CentrifugeRecipe          ingredient: Ingredient / fluidOutput: Pair&lt;String,Integer&gt;（流体 id + 量）
 *                           + getFluidOutputs() → Pair&lt;Fluid,Integer&gt;
 * AdvancedBeehiveRecipe     ingredient: Lazy&lt;BeeIngredient&gt;
 * BeeBreedingRecipe         ingredients: List&lt;Lazy&lt;BeeIngredient&gt;&gt; / offspring: Lazy&lt;BeeIngredient&gt;
 * BottlerRecipe             fluidInput: Pair&lt;String,Integer&gt; / itemInput: Ingredient / result: ItemStack
 * ItemConversionRecipe      bees: List&lt;Lazy&lt;BeeIngredient&gt;&gt; / ingredient: Ingredient / output: ItemStack / chance: int
 * BlockConversionRecipe     bees / input: Ingredient / stateFrom &amp; stateTo: BlockState / chance: int
 * BeeConversionRecipe       source &amp; result: Lazy&lt;BeeIngredient&gt; / item: Ingredient / chance: int
 * BeeSpawningRecipe         ingredient: Ingredient（巢） / spawnItem: Ingredient / output: List&lt;Lazy&lt;BeeIngredient&gt;&gt;
 *
 * BeeIngredient.getBeeType() → ResourceLocation（具体蜂种，如 productivebees:calorite）
 * </pre>
 * <p>
 * <b>蜜蜂 → 刷怪蛋的映射（官方依据，不是我猜的）</b>：PB 自己的
 * {@code cy.jdkdigital.productivebees.util.BeeCreator.getSpawnEgg(String beeId)} 字节码写得很清楚：
 * ① 有蜂种数据 → {@code new ItemStack(ModItems.CONFIGURABLE_SPAWN_EGG.get())} 然后
 * {@code stack.getOrCreateTagElement("EntityTag").putString("type", beeId)}；
 * ② 否则按注册名找独立刷怪蛋（{@code spawn_egg_&lt;蜂种path&gt;}，命名空间不同则换前缀）。
 * NBT 形态由 {@code SpawnEgg.m_43228_/getColor/getName} 三处独立证实都是
 * {@code EntityTag.type}（**与蜂巢同形**）→ 本类用
 * {@code {EntityTag:{type:"<蜂种id>"}}}，且**发一次日志**确认走了哪条路（不猜，留证据）。
 * 注册名注意：可配置刷怪蛋不是 lang 里那个名字 —— 真实注册名是
 * {@code productivebees:spawn_egg_configurable_bee}（由 ModEntities 常量池字符串
 * {@code "spawn_egg_"} + ConfigurableBee 实体注册名 {@code configurable_bee} 拼出；
 * PB 自己的配方 JSON 也写这个 id），而 lang 键反而是漏改的
 * {@code item.productivebees.spawn_egg_configurable}（会导致名字显示成 raw key，
 * 属于 PB 自己的小瑕疵，不影响我们）。
 * <p>
 * <b>按设计排除</b>（不是"读不出来"，见 {@link #isExcludedByDesign}）：
 * {@code BeeFishingRecipe}（输入是"钓鱼 / 生物群系"，没有物品输入，产物是蜜蜂实体）
 * 与 {@code BeeNBTChangerRecipe}（给手持物品改 NBT，产物与输入同物品、无新物品），
 * 这两类没有可编码成 AE 样板的东西。
 * <p>
 * <b>⚠ 反射的铁律（2026-09-20，踩过一次大坑）</b>：<b>原版（{@code net.minecraft.*}）与
 * Forge（{@code net.minecraftforge.*}）的类根本不需要反射</b> —— 我们自己的构建会把直接调用
 * 重映射成 SRG 名（javap 复核：{@code Ingredient.getItems()} 编译后直接就是
 * {@code Ingredient.m_43908_()}）。而**按 MCP 名反射**在生产环境必失败：
 * 运行时（SRG 名）里 {@code Ingredient.getItems} 根本不存在，反射抛
 * {@code NoSuchMethodException} → 被 try/catch 吞掉 → 候选恒空。
 * <p>
 * 所以：反射**只用来从 mod 自己的对象上取回原版/Forge 类型的对象**（如
 * {@code getRecipeOutputs} / {@code getBeeType} / {@code getFluidOutputs} —— 都是
 * {@code cy.jdkdigital.*} 的方法，不被 SRG 改名），拿到之后一律**强转 + 直接调用**。
 * 万不得已必须反射原版成员时，要**同时试 MCP 名与 SRG 名**（见 {@link #tagInt}）。
 * <p>
 * <b>⚠ 注册表默认值的坑（2026-09-20 实机铁证 —— 就是 171 条"无输入" + 76 条"只剩概率产出"的真因）</b>：
 * {@code ForgeRegistries.ITEMS.getValue(rl)} 在**找不到该注册名时返回的不是 null，而是注册表的默认值
 * （物品 = {@code minecraft:air}）**。依据（两层都是硬证据）：
 * <ol>
 *   <li>javap {@code net.minecraftforge.registries.ForgeRegistry.getValue(ResourceLocation)} 的字节码结尾
 *       就是 {@code value = names.get(key); … return value == null ? defaultValue : value;}
 *       （偏移 61–73），而 {@code ForgeRegistries.ITEMS} 正是
 *       {@code RegistryManager.getRegistry(Keys.ITEMS)} 造出来的 {@code ForgeRegistry}（static init 可查）。</li>
 *   <li>实机报告反证：按"蜜蜂能用独立刷怪蛋 {@code spawn_egg_<蜂种path>} 解析成功 ⟺ 该 id 是 PB 注册的
 *       24 个 {@code spawn_egg_*_bee} 之一"离线建模，算出的 OK 条数与实机报告**逐类完全一致**：
 *       bee_produce 2/2、bee_breeding 5/5、bee_conversion 3/3、bee_spawning 27/27、block_conversion 1/1。</li>
 * </ol>
 * 后果：**"这个蜜蜂没有独立刷怪蛋"被误判成"有"** → {@code new ItemStack(AIR)} 是空物品 →
 * 蜜蜂槽/产出**静默消失**（不抛异常、不打 WARN；老的 INFO 打印的还是"请求的 id"而不是"拿到的物品"，
 * 看起来像成功了，白绕了一轮排查）。
 * <p>
 * 所以：**按注册名取物品/流体一律走 {@link #itemById}/{@link #fluidById}**（{@code containsKey} + 非 AIR 双重确认），
 * 不许直接信 {@code getValue} 的非 null。
 * <p>
 * <b>chance 语义（javap + 数据实证，不是猜的 —— 2026-09-20）</b>：
 * <ul>
 *   <li>{@code BeeConversionRecipe.chance} 是**百分数**：PB 用
 *       {@code level.random.nextInt(100) < recipe.chance} 掷骰决定"结果蜂"是否出现
 *       （{@code BeeHelper} 字节码实锤：{@code bipush 100} + {@code m_188503_} + {@code chance} + {@code if_icmpge}）；
 *       缺省 100（114 条 JSON 里只有 3 条写了 30/30/10）→ **不写 chance 就是必成**。</li>
 *   <li>{@code BeeBreedingRecipe} 与 {@code BeeSpawningRecipe} **根本没有 chance 字段**（javap 字段表可见）
 *       → 后代蜂 / 孵出的蜂**一定产出**，我们按必出（1f）处理是对的（不是"只剩概率产出"）。</li>
 *   <li>{@code BlockConversionRecipe.chance} 同样是百分数（缺省 100）。</li>
 * </ul>
 * <b>顺带纠正一条被报告文案带偏的结论</b>：那 76 条"只剩概率产出"**并不是"没有确定主产物"**——
 * 它们的产出列表其实是**空的**（唯一的产出就是那只蜜蜂，而蜜蜂被映射成了空气），
 * 而"无主产物"这一档在诊断里同时包含了"只有概率产出"和"产出为空"两种情况。
 * 配平机制（{@code QianJiRecipeModel.balanceByMaxByproduct}）本来就跑在 {@code fromRecipe} 的公共收尾里、
 * 并不挑来源，所以它从来不是这批的原因。
 * <p>
 * <b>block_conversion 的输入是 {@code stateFrom}（方块状态），不是 {@code input}</b>：
 * 28 条 JSON 里 27 条只写 {@code from}/{@code to}，唯一写了 {@code "input"} 的
 * {@code block_conversion/botania/log_to_livingwood.json} 正好就是实机报告里该类**唯一**的 OK 条
 * → 反证 {@code input} 只是可选的显式覆盖。只读 {@code input} 的后果：20 条配方**一个输入槽都没有**。
 */
public final class ProductiveBeesCompat {

    /** 一个产出（含几率；chance ≥ 1 视为确定产出） */
    public record Stat(GenericStack stack, float chance) {}

    /** PB 配方类的包前缀 */
    private static final String PB_PKG = "cy.jdkdigital.productivebees.common.recipe.";

    /** 本类**已适配**的配方类（简单类名）；不在表里的 PB 配方一律不碰，交回标准路径 */
    private static final String[] SUPPORTED = {
            "CentrifugeRecipe",          // 离心机（含加热离心机）
            "AdvancedBeehiveRecipe",     // 高级蜂箱（对应类型 productivebees:bee_produce）
            "BeeBreedingRecipe",         // 蜜蜂繁殖
            "BottlerRecipe",             // 装瓶机
            "ItemConversionRecipe",      // 物品转换
            "BlockConversionRecipe",     // 方块转换
            "BeeConversionRecipe",       // 蜜蜂转换
            "BeeSpawningRecipe",         // 巢里刷蜂
    };

    /**
     * 认得出来但**明确不做**的 PB 配方类（{@link #isExcludedByDesign} 用，让诊断报告
     * 把它们标成「按设计排除」而不是「提取失败」）。
     * <p>
     * 另外还有一批 PB 配方类（{@code CombineGeneRecipe} / {@code HoneyTreatGeneRecipe} /
     * {@code ConfigurableHoneycombRecipe} / {@code ConfigurableCombBlockRecipe} /
     * {@code IncubationRecipe} / {@code BeeBombBeeCageRecipe}…）走的是**标准工作台/其它机器**
     * 的 Recipe 实现，标准 API 读得到 → 不在这里，也不需要在这里。
     */
    private static final String[] EXCLUDED = {
            "BeeFishingRecipe",     // 输入是"钓鱼"+生物群系，产物是蜜蜂实体 → 无物品输入可编码
            "BeeNBTChangerRecipe",  // 给物品改 NBT，产物与输入同物品 → 千机无「同名改 NBT」语义
    };

    /** 每个输入槽最多列多少候选（标签展开可能上千） */
    private static final int MAX_OPTIONS_PER_SLOT = 32;

    /** 可配置刷怪蛋（PB 用 NBT 区分蜂种）—— javap 实证的注册名 */
    private static final ResourceLocation CONFIGURABLE_SPAWN_EGG =
            new ResourceLocation("productivebees", "spawn_egg_configurable_bee");

    /** 蜂种 id 写进刷怪蛋 NBT 的路径：EntityTag.type（三处字节码交叉证实） */
    private static final String NBT_ENTITY_TAG = "EntityTag";
    private static final String NBT_TYPE_KEY = "type";

    private static final Map<Class<?>, Boolean> IS_PB = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> EXCLUDED_CACHE = new ConcurrentHashMap<>();

    /** 「蜂种 → 走的是独立刷怪蛋还是可配置刷怪蛋」只打一次日志，免得刷屏 */
    private static volatile boolean spawnEggShapeLogged;

    /**
     * 各类"一次性 WARN"的去重开关（按 key）。
     * <p>
     * 2026-09-20：那 171 条"无输入"最要命的地方是**全程零日志**（异常被吞 + 空物品悄悄返回），
     * 所以新加的每一条可能返回空的新路径都挂一个 key → 下一份实机日志自己就会说出断在哪里。
     * （原来那个单独的 {@code beeFailureLogged} 标志位已并入这里，行为不变：蜜蜂映射依旧只打一条）
     */
    private static final Set<String> WARNED_ONCE = ConcurrentHashMap.newKeySet();

    private ProductiveBeesCompat() {}

    // ════════════════════════════════════════════════════════
    //  判定
    // ════════════════════════════════════════════════════════

    /**
     * 是不是**本类已适配**的 PB 配方。
     * <p>
     * 只按类名字符串判（零硬依赖）：包名是 {@code cy.jdkdigital.productivebees.common.recipe.}
     * 且简单类名在 {@link #SUPPORTED} 白名单里。
     */
    public static boolean isProductiveBeesRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return IS_PB.computeIfAbsent(recipe.getClass(), c -> {
            String name = c.getName();
            if (!name.startsWith(PB_PKG)) return false;
            String simple = c.getSimpleName();
            for (String supported : SUPPORTED) {
                if (supported.equals(simple)) return true;
            }
            return false;
        });
    }

    /**
     * 按设计排除的 PB 配方（不是"读不出来"，是我们主动不做）。
     *
     * @return {@link #EXCLUDED} 里的类 → true
     */
    public static boolean isExcludedByDesign(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        return EXCLUDED_CACHE.computeIfAbsent(recipe.getClass(), c -> {
            String name = c.getName();
            if (!name.startsWith(PB_PKG)) return false;
            String simple = c.getSimpleName();
            for (String excluded : EXCLUDED) {
                if (excluded.equals(simple)) return true;
            }
            return false;
        });
    }

    /** 是不是「刷怪蛋」物品（= 本类眼里的一只蜜蜂）——催化剂候选，用于标「不消耗」 */
    public static boolean isSpawnEggItem(@Nullable Item item) {
        if (item == null) return false;
        try {
            if (item instanceof net.minecraftforge.common.ForgeSpawnEggItem) return true;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            return id != null && id.getPath().contains("spawn_egg");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 该槽在 PB 里是**不消耗**的（蜜蜂只是"在场/在机器里"，本体不变）。
     * <p>
     * <b>2026-09-20 修 bug（第 4 条）</b>：原来这个判定只看候选（"全是刷怪蛋就算不消耗"），
     * 由调用方对**每一个**输入槽都套一遍 → {@code BeeBreedingRecipe} 的亲代、
     * {@code BeeConversionRecipe} 的源蜂（本类 inputSlots 的注释明说它们**是真消耗**的）
     * 也被标成"不消耗" → **一只蜂可以无限繁殖 / 无限转换**（刷物品）。
     * 现在判定下沉到本类、按 recipe + 槽序号给答案：只有"蜜蜂本体不变"的那几类配方
     * （advanced_beehive / item_conversion / block_conversion / bee_spawning 的蜂）算催化剂，
     * breeding 亲代与 bee_conversion 源蜂**照实消耗**。
     *
     * @param recipe    PB 配方（判类型用）
     * @param slotIndex 该槽在 {@link #inputSlots(Recipe)} 返回列表里的下标（调用方按同一顺序遍历）
     * @param options   调用方手上那个槽的候选（省一次重复提取；与 slotIndex 对应）
     */
    public static boolean isCatalystSlot(@Nullable Recipe<?> recipe, int slotIndex,
                                         List<GenericStack> options) {
        if (recipe == null || slotIndex < 0 || options == null || options.isEmpty()) return false;
        // 这只蜜蜂是否"本体不变"取决于它属于哪一类配方 —— 先判配方，再判候选
        if (!beeStaysIntact(recipe, slotIndex)) return false;
        for (GenericStack option : options) {
            if (option == null || !(option.what() instanceof AEItemKey key)) return false;
            if (!isSpawnEggItem(key.getItem())) return false;
        }
        return true;
    }

    /**
     * 该槽里的蜜蜂是否"本体不变"（= 可当催化剂）。
     * <p>
     * 与 {@link #inputSlots(Recipe)} 的加槽顺序**严格对齐**：
     * <pre>
     * AdvancedBeehiveRecipe  蜂箱里养着的蜂            → 不变（不消耗）
     * ItemConversionRecipe   bees + ingredient         → 蜜蜂不变；物品（花粉之类）消耗
     * BlockConversionRecipe  bees + input              → 同上
     * BeeSpawningRecipe      ingredient(巢) + spawnItem → 巢消耗；具体刷怪蛋是"哪只蜂"→ 不变
     * BeeBreedingRecipe      亲代（两只）              → **真消耗**（否则一只蜂无限繁殖）
     * BeeConversionRecipe    source(源蜂) + item       → 源蜂**真消耗**（否则一只蜂无限转换）
     * </pre>
     */
    private static boolean beeStaysIntact(Recipe<?> recipe, int slotIndex) {
        String simple = recipe.getClass().getSimpleName();
        switch (simple) {
            case "AdvancedBeehiveRecipe":
                return slotIndex == 0;                        // 唯一的输入就是那只蜂
            case "ItemConversionRecipe", "BlockConversionRecipe":
                return slotIndex == 0;                        // 蜜蜂槽排在前面，物品/方块槽在后面
            case "BeeSpawningRecipe":
                // 巢是 slots[0]（消耗）；蜜蜂槽从 slots[1] 起
                return slotIndex >= 1;
            default:
                // BeeBreedingRecipe / BeeConversionRecipe / 其余 → 一律不是催化剂
                return false;
        }
    }

    // ════════════════════════════════════════════════════════
    //  输入
    // ════════════════════════════════════════════════════════

    /**
     * 输入槽：标准 Ingredient（物品）+ 流体输入 + 蜜蜂（映射成刷怪蛋）。
     */
    public static List<List<GenericStack>> inputSlots(@Nullable Recipe<?> recipe) {
        var slots = new ArrayList<List<GenericStack>>();
        if (recipe == null) return slots;
        try {
            String simple = recipe.getClass().getSimpleName();
            switch (simple) {
                case "CentrifugeRecipe" -> {
                    addItemOptions(slots, requiredField(recipe, "ingredient"));
                }
                case "AdvancedBeehiveRecipe" -> {
                    // 字段 `ingredient` 是**单个** Lazy<BeeIngredient>（javap 实证），不是集合 ——
                    // beeOptions() 本来就吃"单对象"这种形态，所以这里没有 asList 的坑
                    //（实机 171 条无输入的真因是刷怪蛋取到了 air，见类注释）
                    addBeeOptions(slots, requiredField(recipe, "ingredient"));
                }
                case "BeeBreedingRecipe" -> {
                    // 亲代被消耗 → 照实当消耗品（不标催化剂）
                    for (Object lazy : asList(requiredField(recipe, "ingredients"))) {
                        addBeeOptions(slots, lazy);
                    }
                }
                case "BottlerRecipe" -> {
                    // 流体输入：字段 fluidInput = Pair<流体id字符串, 量>
                    GenericStack fluid = fluidPairToGeneric(field(recipe, "fluidInput"));
                    if (fluid != null) slots.add(List.of(fluid));
                    addItemOptions(slots, field(recipe, "itemInput"));
                }
                case "ItemConversionRecipe" -> {
                    // 蜜蜂只催化，本体留下来 → 不消耗
                    addBeeOptions(slots, requiredField(recipe, "bees"));
                    addItemOptions(slots, field(recipe, "ingredient"));
                }
                case "BlockConversionRecipe" -> {
                    addBeeOptions(slots, requiredField(recipe, "bees"));
                    // ⚠ 2026-09-20 修（实机 20 条"无输入"的真因）：**真正的输入是 `stateFrom`（被转化的方块状态）**，
                    // 字段 `input` 只是 JSON 里可选的显式覆盖。
                    // 依据：28 条 JSON 里 27 条只有 from/to；唯一带 "input" 的
                    // botania/log_to_livingwood.json 正好就是实机报告里 block_conversion **唯一**的 OK 条。
                    // 不改会怎样：只读 input → 20 条配方一个输入槽都没有（整类报"无输入"）。
                    var blockInput = itemOptions(field(recipe, "input"));
                    if (blockInput.isEmpty()) {
                        blockInput = itemOptions(requiredField(recipe, "stateFrom"));
                        if (blockInput.isEmpty()) {
                            // from 是水/空气这类**没有物品形态**的方块 → 这个输入表达不出来。
                            // 照实打一条日志（旧行为是静默丢槽，实机上根本查不出为什么"无输入"）
                            warnOnce("pb-block-from-noitem",
                                    "block_conversion 的输入方块没有物品形态（from="
                                            + describeBlock(field(recipe, "stateFrom"))
                                            + "）→ 该输入槽缺失，样板只剩蜜蜂那一槽");
                        }
                    }
                    if (!blockInput.isEmpty()) slots.add(blockInput);
                }
                case "BeeConversionRecipe" -> {
                    // 蜜蜂 A 被转成蜜蜂 B → 源蜂真消耗
                    addBeeOptions(slots, requiredField(recipe, "source"));
                    // item 是这个转换需要的环境方块/物品（如 storage_blocks/calorite）
                    addItemOptions(slots, field(recipe, "item"));
                }
                case "BeeSpawningRecipe" -> {
                    // 巢方块 + 可选的具体刷怪蛋。巢**按消耗**处理（一次刷一只，语义更接近"巢 → 蜂"），
                    // 蜜蜂槽则由 isCatalystSlot 判为不消耗
                    addItemOptions(slots, requiredField(recipe, "ingredient"));
                    addItemOptions(slots, field(recipe, "spawnItem"));
                }
                default -> {
                    // 不在支持表里：什么都不给（调用方也不会走到这里）
                }
            }
        } catch (Throwable ignored) {
            // 反射层绝不把异常抛给调用方
        }
        return slots;
    }

    // ════════════════════════════════════════════════════════
    //  产出
    // ════════════════════════════════════════════════════════

    /**
     * 产出：物品（含 {@code IntArrayTag} 里的概率）+ 流体 + 蜜蜂（映射成刷怪蛋）。
     */
    public static List<Stat> outputs(@Nullable Recipe<?> recipe) {
        var out = new ArrayList<Stat>();
        if (recipe == null) return out;
        try {
            String simple = recipe.getClass().getSimpleName();
            switch (simple) {
                case "CentrifugeRecipe" -> {
                    addTagOutputs(out, recipe);
                    addCentrifugeFluid(out, recipe);
                }
                case "AdvancedBeehiveRecipe" -> addTagOutputs(out, recipe);
                case "BeeBreedingRecipe" -> {
                    // 后代蜜蜂：javap 实证只有单个 `Lazy<BeeIngredient> offspring`，而且**没有 chance 字段**
                    // → 后代一定产出（必出），实机那 23 条的"只剩概率产出"其实是产出为空，见类注释
                    var bees = new ArrayList<GenericStack>();
                    addBeeOptionsInto(bees, requiredField(recipe, "offspring"));
                    if (bees.isEmpty()) warnOnce("pb-bee-output-empty", beeOutputEmptyWhy(simple));
                    for (GenericStack stack : bees) out.add(new Stat(stack, 1f));
                }
                case "BottlerRecipe" -> {
                    GenericStack stack = toGeneric(field(recipe, "result"));
                    if (stack != null && stack.amount() > 0) out.add(new Stat(stack, 1f));
                }
                case "ItemConversionRecipe" -> {
                    GenericStack stack = toGeneric(field(recipe, "output"));
                    Float chance = chance100(field(recipe, "chance"));
                    // chance == null = PB 的 chance 0（几乎不产出）→ 跳过，不能当必出（见 percentToChance）
                    if (chance != null && stack != null && stack.amount() > 0) {
                        out.add(new Stat(stack, chance));
                    }
                }
                case "BlockConversionRecipe" -> {
                    // stateTo 是**方块状态**，千机只能按方块物品表示
                    Object stateTo = requiredField(recipe, "stateTo");
                    GenericStack stack = toGeneric(stateTo);
                    Float chance = chance100(field(recipe, "chance"));
                    if (chance != null && stack != null && stack.amount() > 0) {
                        out.add(new Stat(stack, chance));
                    } else if (chance != null && stack == null) {
                        // to 是水/岩浆/空气这类**没有物品形态**的方块 → 产出表达不出来。
                        // 实机里 block_conversion 的 3 条"提取失败"就是这三条（air_to_water / water_to_air /
                        // cobble_to_lava）。不硬编成流体（方块 ≠ 流体，会造假配方），照实打日志说明原因
                        warnOnce("pb-block-to-noitem",
                                "block_conversion 的产物方块没有物品形态（to=" + describeBlock(stateTo)
                                        + "）→ 无法编码成 AE 样板，这条配方只剩输入（会被报成「无主产物」）");
                    }
                }
                case "BeeConversionRecipe" -> {
                    // chance 是**百分数**（PB: nextInt(100) < chance 才把源蜂换成结果蜂），缺省 100 = 必成
                    Float chance = chance100(field(recipe, "chance"));
                    if (chance != null) {                       // 同上：chance 0 → 跳过
                        var bees = new ArrayList<GenericStack>();
                        addBeeOptionsInto(bees, requiredField(recipe, "result"));
                        if (bees.isEmpty()) warnOnce("pb-bee-output-empty", beeOutputEmptyWhy(simple));
                        for (GenericStack stack : bees) out.add(new Stat(stack, chance));
                    }
                }
                case "BeeSpawningRecipe" -> {
                    // javap 实证：该类**没有 chance 字段** → 孵出的蜂必出（1f）
                    var bees = new ArrayList<GenericStack>();
                    for (Object lazy : asList(requiredField(recipe, "output"))) {
                        addBeeOptionsInto(bees, lazy);
                    }
                    if (bees.isEmpty()) warnOnce("pb-bee-output-empty", beeOutputEmptyWhy(simple));
                    for (var stack : bees) out.add(new Stat(stack, 1f));
                }
                default -> {
                    // 不支持
                }
            }
        } catch (Throwable ignored) {
            // 同上：绝不上抛
        }
        return List.copyOf(dedupe(out));
    }

    /**
     * {@code TagOutputRecipe.getRecipeOutputs()}: Map&lt;ItemStack, IntArrayTag&gt;
     * → 物品 + [min, max, chance]。
     * <p>
     * <b>2026-09-20 修 bug（第 3 条）</b>：原来完全没用 {@code min}（只拿 {@code ItemStack} 的
     * count=1），而 PB 自己按 {@code tag.get(0)/(1)} 取数量（{@code lambda$completeRecipeProcessing$9}
     * 字节码），jar 里 35 条产出 {@code min > 1}（如 {@code honeycomb_brass} min=4 / max=6）
     * → 不改就少产（4 个变 1 个）。现在按 {@code min <= 0 ? 1 : min} 出数量。
     * <p>
     * {@code chance == 0} → **跳过**这条产出（见 {@link #percentToChance} 的说明）。
     */
    private static void addTagOutputs(List<Stat> out, Object recipe) {
        Object raw = invoke(recipe, "getRecipeOutputs");
        if (!(raw instanceof Map<?, ?> map)) return;
        for (var entry : map.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof ItemStack stack) || stack.isEmpty()) continue;
            Float chance = tagChance(entry.getValue());
            if (chance == null) continue;               // PB 的 chance=0 = 几乎不产出 → 这条不列
            GenericStack generic = withCount(stack, tagMin(entry.getValue()));
            if (generic == null) continue;
            out.add(new Stat(generic, chance));
        }
    }

    /** {@code IntArrayTag} 的第 0 位 = min（产出数量下限）；缺 / 取不到 → 沿用原数量 */
    private static int tagMin(@Nullable Object tag) {
        if (!(tag instanceof IntArrayTag array) || array.size() == 0) return 0;
        return tagInt(array.get(0));
    }

    /**
     * 按 {@code IntArrayTag} 的 min 出数量：{@code min <= 0 ? 1 : min}
     * （PB 自己也是这么取的 —— {@code tag.get(0)} 就是数量）。
     */
    private static GenericStack withCount(ItemStack stack, int min) {
        int count = min <= 0 ? 1 : min;
        try {
            // 产出物品可能带 NBT（configurable_honeycomb 那种）→ 走 copyWithCount 保住 NBT
            ItemStack sized = stack.copyWithCount(count);
            return toGeneric(sized);
        } catch (Throwable ignored) {
            return toGeneric(stack);
        }
    }

    /**
     * {@code IntArrayTag} = [min, max, chance] → chance。
     *
     * @return null = 这条产出应当**跳过**（PB 的 chance=0）
     */
    @Nullable
    private static Float tagChance(@Nullable Object tag) {
        if (!(tag instanceof IntArrayTag array) || array.size() == 0) return 1f;
        float raw = array.size() >= 3 ? tagInt(array.get(2)) : 100f;
        if (raw < 0f) return 1f;          // 读不到 chance → 按必出（保持既有宽容行为）
        return percentToChance(raw);
    }

    /**
     * {@code IntTag} → int。
     * <p>
     * ⚠ 这里**故意走反射**：{@code IntTag.getAsInt()} 在官方映射下叫 {@code getAsInt}，
     * 但 reobf 前的中间 jar 里是 SRG 名 {@code m_263179_}（javap 实证）——直接写死任何一个
     * 都可能在另一套映射下编译失败。两个名字都试，取不到返回 -1（= 未知概率，按必出处理）。
     */
    private static int tagInt(@Nullable Object intTag) {
        if (intTag == null) return -1;
        for (String name : new String[]{"getAsInt", "m_263179_"}) {
            Object value = invoke(intTag, name);
            if (value instanceof Number n) return n.intValue();
        }
        return -1;
    }

    /** 离心机流体产出：字段 {@code Pair<String,Integer> fluidOutput}（流体 id + 量） */
    private static void addCentrifugeFluid(List<Stat> out, Object recipe) {
        // 优先用 getFluidOutputs()（Pair<Fluid,Integer>，不用再查注册表）
        Object viaGetter = invoke(recipe, "getFluidOutputs");
        GenericStack fromGetter = fluidPairToGeneric(viaGetter);
        if (fromGetter != null) {
            out.add(new Stat(fromGetter, 1f));
            return;
        }
        GenericStack fromField = fluidPairToGeneric(field(recipe, "fluidOutput"));
        if (fromField != null) out.add(new Stat(fromField, 1f));
    }

    /**
     * {@code Pair<流体(字符串 id 或 Fluid)>, Integer 量> → GenericStack。
     * <p>
     * {@code Pair} 是 mojang 的库类、{@code getFirst/getSecond} 是**编译期绑定**的普通方法
     * （不是 net.minecraft.*，也没被 SRG 改）→ 直接强转调用，别再反射。
     * 反射只用来从 PB 对象上取回这个 Pair（PB 自己的字段/getter）。
     */
    @Nullable
    private static GenericStack fluidPairToGeneric(@Nullable Object pair) {
        if (pair == null) return null;
        try {
            if (!(pair instanceof com.mojang.datafixers.util.Pair<?, ?> p)) return null;
            Object first = p.getFirst();
            Object second = p.getSecond();
            long amount = second instanceof Number n ? n.longValue() : 0L;
            if (amount <= 0 || first == null) return null;
            if (first instanceof Fluid fluid) {
                return new GenericStack(AEFluidKey.of(fluid), amount);
            }
            if (first instanceof String id) {
                ResourceLocation key = ResourceLocation.tryParse(id);
                if (key == null) return null;
                // 同一个"注册表默认值"坑：FLUIDS.getValue 找不到时给的是 Fluid 注册表的默认值（空流体），
                // 不是 null → 会造出一个"空流体"键。走 fluidById 双重确认。
                var fluid = fluidById(key);
                if (fluid == null) return null;
                return new GenericStack(AEFluidKey.of(fluid), amount);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    // ════════════════════════════════════════════════════════
    //  单个原料 → 候选 GenericStack
    // ════════════════════════════════════════════════════════

    /** 标准 Ingredient → 候选（每个候选数量 = 该 ItemStack 自带 count） */
    private static void addItemOptions(List<List<GenericStack>> slots, @Nullable Object ingredient) {
        var options = itemOptions(ingredient);
        if (!options.isEmpty()) slots.add(options);
    }

    /** 蜜蜂（{@code Lazy<BeeIngredient>} / {@code List<Lazy<BeeIngredient>>} / BeeIngredient）→ 刷怪蛋候选 */
    private static void addBeeOptions(List<List<GenericStack>> slots, @Nullable Object value) {
        var options = beeOptions(value);
        if (!options.isEmpty()) slots.add(options);
    }

    /** 蜜蜂（单个或列表）→ 刷怪蛋候选（**累加到已有列表**，供产出复用；名字与建槽的重载区分开） */
    private static void addBeeOptionsInto(List<GenericStack> target, @Nullable Object value) {
        for (GenericStack stack : beeOptions(value)) {
            if (!contains(target, stack)) target.add(stack);
        }
    }

    /** 标准 {@link Ingredient} → 候选（每个候选数量 = 该 ItemStack 自带 count） */
    private static List<GenericStack> itemOptions(@Nullable Object ingredient) {
        var options = new ArrayList<GenericStack>();
        // 原版类直接强转 + 直调（不反射）：生产环境里 Ingredient.getItems() 叫 m_43908_，
        // 反射按 "getItems" 找必然 NoSuchMethodException → 整槽丢空（与 ThermalCompat 同一处坑）。
        if (!(ingredient instanceof Ingredient ing)) return options;
        try {
            for (ItemStack item : ing.getItems()) {
                GenericStack stack = toGeneric(item);
                if (stack == null || stack.amount() <= 0) continue;
                if (!contains(options, stack)) options.add(stack);
                if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
            }
        } catch (Throwable ignored) {
            // Ingredient 在某些状态下 getItems() 可能抛（标签未就绪）→ 当空处理
        }
        return options;
    }

    /** 蜜蜂对象（各种形态）→ 刷怪蛋候选 */
    private static List<GenericStack> beeOptions(@Nullable Object value) {
        var options = new ArrayList<GenericStack>();
        if (value == null) return options;
        try {
            if (value instanceof Iterable<?> it) {
                for (Object element : it) {
                    for (GenericStack stack : beeOptions(element)) {
                        if (!contains(options, stack)) options.add(stack);
                        if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
                    }
                    if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
                }
                return options;
            }
            if (value instanceof Object[] array) {
                for (Object element : array) {
                    for (GenericStack stack : beeOptions(element)) {
                        if (!contains(options, stack)) options.add(stack);
                        // 2026-09-20 顺手清理：这条展开路径原来漏了上限，
                        // 标签候选上千时会吐出一堆废槽（另两条路径都有这个 break）
                        if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
                    }
                    if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
                }
                return options;
            }
            ItemStack egg = spawnEggForBee(value);
            if (egg != null && !egg.isEmpty()) {
                GenericStack stack = toGeneric(egg);
                if (stack != null) options.add(stack);
            }
        } catch (Throwable ignored) {
            // 蜂种注册表没就绪时 Lazy.get() 可能抛 → 兜住，当这个槽没有候选
        }
        return options;
    }

    /**
     * 一只蜜蜂 → 刷怪蛋 ItemStack。
     * <p>
     * 取蜂种 id 的办法（依次试，全失败返回 null）：
     * ① {@code Lazy.get()}（字段是 Lazy 时）→ 再 {@code getBeeType()}；
     * ② 对象本身就是 BeeIngredient → {@code getBeeType()}；
     * ③ {@code getBeeEntity()} → 实体类型注册名（兜底；可配置蜂会给 configurable_bee，不可靠）。
     * <p>
     * 拿到蜂种 id 后：先试**独立刷怪蛋** {@code productivebees:spawn_egg_<蜂种path>}，
     * 没有再退到**可配置刷怪蛋** + NBT（{@code EntityTag.type} = 蜂种 id）——
     * 和 sensei 定的策略一致；哪条成功打一次日志（照 ChemicalCompat.keyOf 的纪律，留证据不猜）。
     * <p>
     * <b>2026-09-20 反射名 铁律</b>：{@code Lazy}（Forge）与注册表查询是**直接调用**，不走反射 ——
     * 反射只保留给 PB 自己的方法（{@code getBeeType} / {@code getBeeEntity} 在 {@code cy.jdkdigital.*}，
     * 不会被 SRG 改名）。原来这里用 {@code invokeStatic(...getKey, arg)} 传的是 {@code arg.getClass()}，
     * 而 {@code IForgeRegistry.getKey} 的形参是 {@code Object} → {@code getMethod} 永远找不到；
     * 现在直接 {@code ForgeRegistries.ENTITY_TYPES.getKey(...)}。
     * <p>
     * ⚠ 失败不再静默：任何一环断掉都会打一条 WARN（原来异常被吞、日志一行没有，
     * 实机上 171 条 advanced_beehive 变成"无输入"却查不出为什么）。
     */
    @Nullable
    private static ItemStack spawnEggForBee(@Nullable Object bee) {
        if (bee == null) return null;
        try {
            Object ingredient = bee;                 // 可能是 Lazy
            // Lazy 是 Forge 的类（extends java.util.function.Supplier）→ get() 直接调，不反射。
            // ⚠ 万一这里的 instanceof 因为类加载器不同而没命中，退回「按名字调」，
            //   避免整条蜜蜂路径静默返回空（这是实机上最难查的失败形态）
            if (bee instanceof Lazy<?> lazy) {
                Object value = lazy.get();
                if (value != null) ingredient = value;
            } else {
                Object value = invoke(bee, "get");   // 兜底：Lazy.get() 的 MCP 名
                if (value != null) ingredient = value;
            }
            // getBeeType() 是 PB 自己的 public 方法（不在 net.minecraft.* 下）→ 反射 ✓
            Object beeTypeRaw = invoke(ingredient, "getBeeType");
            // 拿回的是原版 ResourceLocation：用 toString() 取 id 再解析，避免依赖 instanceof
            //（万一类加载器不同，instanceof 会静默为 false —— 这正是"读不出又没日志"最难查的形态）
            String beeId = beeTypeRaw == null ? null : beeTypeRaw.toString();
            if (beeId == null || beeId.isEmpty()) {
                // 兜底：实体类型注册名（可配置蜂 → productivebees:configurable_bee）
                Object entityType = invoke(ingredient, "getBeeEntity");
                if (entityType != null && entityType instanceof net.minecraft.world.entity.EntityType<?> type) {
                    // Forge 注册表直接调（不反射）
                    ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
                    if (key != null) beeId = key.toString();
                }
            }
            if (beeId == null || beeId.isEmpty()) {
                logBeeFailureOnce("蜂种 id 读不出：bee=" + bee.getClass().getName()
                        + " 解析后=" + ingredient.getClass().getName()
                        + " getBeeType()=" + beeTypeRaw);
                return null;
            }

            ResourceLocation beeKey = ResourceLocation.tryParse(beeId);
            if (beeKey == null) {
                logBeeFailureOnce("蜂种 id 不是合法 ResourceLocation: " + beeId);
                return null;
            }

            // ① 独立刷怪蛋：spawn_egg_<蜂种 path>
            ResourceLocation dedicated = new ResourceLocation(beeKey.getNamespace(), "spawn_egg_" + beeKey.getPath());
            // ⚠ 2026-09-20 的**真凶**就在这里：原来写 `Item dedicatedItem = ForgeRegistries.ITEMS.getValue(dedicated);`
            // 然后判 `!= null` —— 而 getValue 找不到时返回的是注册表默认值 minecraft:air（**不是 null**，
            // 见类注释的 javap 证据）→ 这条分支永远"命中"，new ItemStack(AIR) 是空物品 → 槽位静默消失。
            // 不改会怎样：171 条 advanced_beehive + 45 条 bee_conversion + 8 条 bee_spawning + 24 条
            // bee_breeding 全部变成"无输入/无产出"，而且**一条日志都没有**。
            Item dedicatedItem = itemById(dedicated);
            if (dedicatedItem != null) {
                // 日志打印**真正拿到的物品 id**：老代码打印的是"请求的 id"，于是
                // `dedicated:productivebees:spawn_egg_imperium`（其实不存在）看起来像成功了 —— 误导过一轮排查
                logSpawnEggShapeOnce("dedicated:" + ForgeRegistries.ITEMS.getKey(dedicatedItem)
                        + "（蜂种 " + beeId + "）");
                return new ItemStack(dedicatedItem);
            }

            // ② 可配置刷怪蛋 + NBT（PB 官方形态，来自 BeeCreator.getSpawnEgg 的字节码）
            Item configurable = itemById(CONFIGURABLE_SPAWN_EGG);
            if (configurable == null) {
                logBeeFailureOnce("可配置刷怪蛋没找到：ITEM=" + CONFIGURABLE_SPAWN_EGG
                        + "（独立刷怪蛋 " + dedicated + " 也不存在）→ 刷怪蛋槽会为空");
                return null;
            }
            ItemStack stack = new ItemStack(configurable);
            if (stack.isEmpty()) {
                // 兜底：万一 itemById 的判据以后再失效，也绝不再静默返回一个空物品
                logBeeFailureOnce("刷怪蛋物品构造出来是空的：configurable=" + CONFIGURABLE_SPAWN_EGG);
                return null;
            }
            CompoundTag entityTag = stack.getOrCreateTagElement(NBT_ENTITY_TAG);
            entityTag.putString(NBT_TYPE_KEY, beeId);
            logSpawnEggShapeOnce("configurable:" + CONFIGURABLE_SPAWN_EGG + " NBT "
                    + NBT_ENTITY_TAG + "." + NBT_TYPE_KEY + "（蜂种 " + beeId + "）");
            return stack;
        } catch (Throwable t) {
            // 不再静默吞掉：把类名 + 消息记下来（实机取证用）
            logBeeFailureOnce("异常 " + t.getClass().getName() + ": " + t.getMessage());
            return null;
        }
    }

    /** 蜜蜂映射失败只打一次 WARN（原来异常被吞、日志空白，导致实机无法定位） */
    private static void logBeeFailureOnce(String why) {
        warnOnce("pb-bee-map", "蜜蜂→刷怪蛋映射失败（该槽会被当成无输入）：" + why);
    }

    /**
     * 一次性 WARN（照 {@link #logBeeFailureOnce} 的写法，但按 key 去重，能同时覆盖多类失败）。
     * <p>
     * 2026-09-20 的教训：那 171 条"无输入"查不出来，就是因为整条路径**一个字都不打**。
     * 现在每个"可能返回空"的新路径都有自己的 key —— 实机日志会直接说出断在哪一环。
     */
    private static void warnOnce(String key, String message) {
        if (!WARNED_ONCE.add(key)) return;
        try {
            com.ae2addon.AE2Addon.LOGGER.warn("[ProductiveBees] {}", message);
        } catch (Throwable ignored) {
            // 日志失败无所谓
        }
    }

    /** 「蜜蜂产出入不出得来」为空的通用说明（把两种可能都写清楚，方便下一份日志定位） */
    private static String beeOutputEmptyWhy(String simple) {
        return simple + " 的产出蜜蜂没能映射成刷怪蛋 → 产出为空"
                + "（该配方会被诊断报成「只剩概率产出」，其实是没有产出；"
                + "若是新版本 PB 改了字段，见上面的字段缺失 WARN）";
    }

    /**
     * 读一个**必须存在**的字段：取不到就说明 PB 改了字段名/结构 → 打一条一次性 WARN。
     * <p>
     * 为什么需要它：{@link #field} 取不到时返回 null，而所有调用点都把 null 当"这槽没有候选"，
     * 于是**字段改名 = 整类配方静默变空**（实机上没有任何线索）。这条日志把这个可能性堵上。
     */
    @Nullable
    private static Object requiredField(@Nullable Object recipe, String fieldName) {
        Object value = field(recipe, fieldName);
        if (value == null && recipe != null) {
            warnOnce("pb-field-" + recipe.getClass().getSimpleName() + "." + fieldName,
                    "读不到字段 " + recipe.getClass().getSimpleName() + "." + fieldName
                            + "（该配方的这一部分会被漏掉 —— PB 可能改了字段名，用 javap 复核）");
        }
        return value;
    }

    /** 方块状态 → 注册名（日志用；取不到就退回 toString） */
    private static String describeBlock(@Nullable Object value) {
        if (value instanceof BlockState state) {
            try {
                ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                if (key != null) return key.toString();
            } catch (Throwable ignored) {
                // 退回 toString
            }
        }
        return String.valueOf(value);
    }

    /**
     * 按注册名取物品，**认不出就返回 null**。
     * <p>
     * 存在的理由：{@code ForgeRegistries.ITEMS.getValue(rl)} 找不到时返回的是注册表默认值
     * （{@code minecraft:air}）而不是 null（javap 证据见类注释）→ 直接判 {@code != null} 会把
     * "不存在"当成"存在"，然后造出一个空物品、静默丢掉整个槽。这里 containsKey + 非 AIR 双重确认。
     */
    @Nullable
    private static Item itemById(@Nullable ResourceLocation id) {
        if (id == null) return null;
        try {
            if (!ForgeRegistries.ITEMS.containsKey(id)) return null;
            Item item = ForgeRegistries.ITEMS.getValue(id);
            return item == null || item == Items.AIR ? null : item;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按注册名取流体，**认不出就返回 null**（同一个默认值坑，见 {@link #itemById}） */
    @Nullable
    private static Fluid fluidById(@Nullable ResourceLocation id) {
        if (id == null) return null;
        try {
            if (!ForgeRegistries.FLUIDS.containsKey(id)) return null;
            return ForgeRegistries.FLUIDS.getValue(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 只打一次：确认蜜蜂映射实际走了哪条路（对照 sensei 的"两种形态都试 + 留日志"要求） */
    private static void logSpawnEggShapeOnce(String what) {
        if (spawnEggShapeLogged) return;
        spawnEggShapeLogged = true;
        try {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ProductiveBees] 蜜蜂→刷怪蛋映射生效：{}（蜂种 id 写在 {} 里，与 javap 结论一致）",
                    what, NBT_ENTITY_TAG + "." + NBT_TYPE_KEY);
        } catch (Throwable ignored) {
            // 日志失败无所谓
        }
    }

    // ════════════════════════════════════════════════════════
    //  概率换算
    // ════════════════════════════════════════════════════════

    /**
     * 整数 chance → 0..1。PB 的 chance **永远是百分数**（掷骰是 {@code nextInt(100)} 与
     * {@code tag.get(2)} 比较；414 条离心机 JSON 的取值只有 2/5/…/80/100）→ 一律 {@code /100}。
     *
     * @return null = 这条产出应当跳过（chance=0，见 {@link #percentToChance}）
     */
    @Nullable
    private static Float chance100(@Nullable Object raw) {
        if (!(raw instanceof Number n)) return 1f;
        return percentToChance(n.floatValue());
    }

    /**
     * PB 的百分数 → 0..1（chance ≥ 1 视为必出由调用方判）。
     * <p>
     * <b>2026-09-20 修 bug（第 5 条）</b>：原来写的是"只有 {@code > 1} 才除 100"
     * → {@code chance == 1} 被当成 100%，而 PB 的 1 就是 **1%**（掷骰 {@code nextInt(100)}），
     * jar 里 106 条产出正是 {@code chance: 1} → 稀有产出全变必出。现在一律 {@code /100}。
     * <p>
     * <b>反向坑（防）</b>：PB 的 {@code chance == 0} 意思是"几乎不产出"，而我们的约定是
     * {@code <= 0} = "未声明 → 按必出处理" —— 若照直映射，最稀有的产出反而变成 100% 必出。
     * 所以 0 一律**返回 null → 跳过这条产出**，绝不映射成 {@code <= 0}。
     *
     * @return null = 跳过这条产出
     */
    @Nullable
    private static Float percentToChance(float raw) {
        if (!(raw > 0f)) return null;      // 0 / 负数（含读不到时的 -1 哨兵）→ 跳过
        return Math.min(1f, raw / 100f);
    }

    // ════════════════════════════════════════════════════════
    //  任意 PB 侧对象 → GenericStack
    // ════════════════════════════════════════════════════════

    /** ItemStack / BlockState / FluidStack → GenericStack（数量 0 的返回 null） */
    @Nullable
    public static GenericStack toGeneric(@Nullable Object value) {
        if (value == null) return null;
        try {
            if (value instanceof ItemStack item) {
                if (item.isEmpty()) return null;
                return new GenericStack(AEItemKey.of(item), Math.max(1, item.getCount()));
            }
            if (value instanceof BlockState state) {
                Item item = state.getBlock().asItem();
                if (item == net.minecraft.world.item.Items.AIR) return null;
                return new GenericStack(AEItemKey.of(item), 1);
            }
            if (value instanceof net.minecraftforge.fluids.FluidStack fluid) {
                if (fluid.isEmpty()) return null;
                return new GenericStack(AEFluidKey.of(fluid), Math.max(1, fluid.getAmount()));
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

    private static boolean contains(List<GenericStack> options, GenericStack stack) {
        for (GenericStack existing : options) {
            if (existing.what().equals(stack.what()) && existing.amount() == stack.amount()) return true;
        }
        return false;
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

    /** 调 0 参 public 方法（找不到 / 抛异常一律 null） */
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

    /** 调任意 static 方法（取实体类型注册名用） */
    @Nullable
    private static Object invokeStatic(@Nullable Object target, String methodName, Object arg) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName, arg == null ? Object.class : arg.getClass());
            return m.invoke(null, arg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 读字段（含继承链的 private/protected/public）。
     * <p>
     * PB 这些配方字段几乎都是 {@code public final}，理论上能直接取；但字段名在别的版本
     * 可能变（例如 {@code BeeBreedingRecipe.offspring} 与多后代列表），所以统一走「先 getter
     * 后字段」的宽容路线 —— 取不到就当没有，不炸。
     */
    @Nullable
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
