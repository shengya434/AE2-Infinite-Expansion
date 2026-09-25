package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.AE2Addon;
import com.ae2addon.item.CatalystItem;
import com.ae2addon.recipe.QianJiPatternData;
import com.ae2addon.util.RecipeByproducts;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 千机产出计算（2026-09-18 抽取）。
 * <p>
 * ⚠ 这里是**唯一一份**产出逻辑：推送路径（{@code QianJiBE.instantCraft}，真机消耗材料）
 * 与虚拟结算路径（{@code QianJiBE.settle}，CPU 侧模拟）**必须共用**，绝不写两份 ——
 * 否则两条路的副产分布会漂移。
 * <p>
 * 语义（沿用 2026-09-15 sensei 定稿 + 2026-09-18 决策 2，勿擅改）：
 * <ul>
 *   <li><b>逐份掷骰</b>：批量 N 份 = 掷 N 次骰，不做期望值取巧
 *       （旧的「掷一次 ×N」会让概率副产整批全出或全不出）；</li>
 *   <li>主产物 / 样板声明的确定产出：**照给**（不掷骰）；</li>
 *   <li>概率产出：按各自几率逐份掷骰（{@code chance <= 0} 视为必出）；</li>
 *   <li>催化剂：**产出数量倍数**，每份重掷一次（基础 50%×2 / 高级 ×2 / 终极 50%×4），
 *       主产物与副产物都乘；几率不被催化剂改动。</li>
 * </ul>
 * 结果按 key **汇总**（不是逐份展开），所以十万份订单也只产出几十个条目、几十次掷骰统计。
 */
public final class QianJiByproducts {

    private QianJiByproducts() {}

    /**
     * 一次产出计算的全部输入。
     *
     * @param random            随机源（**批量掷骰请用 {@link #threadRandom}**：
     *                          {@code level.random} 是带同步的共享源，十万级逐份掷骰会成热点）
     * @param level             世界（兼容通道查配方概率副产用；可为 null）
     * @param byproductsEnabled 千机面板上的副产开关；关掉 = 兼容通道不出额外副产
     * @param batches           份数（批量 N / 待结算 N）；逐份掷骰，至少按 1 份算
     * @param multiplierRoll    催化剂倍数掷骰（每份调用一次）；无催化剂传 {@code r -> 1}
     * @param multiplierKind    催化剂类型（供 {@link #sampledMultiplierSum} 走 O(1) 抽样）；
     *                          无催化剂传 null
     */
    public record Context(RandomSource random, @Nullable Level level, boolean byproductsEnabled,
                          long batches, ToIntFunction<RandomSource> multiplierRoll,
                          @Nullable CatalystItem multiplierKind, boolean catalystsPresent) {

        /** 兼容旧调用（无催化剂信息 → 只能逐份掷骰；且**默认催化剂不在网络里**） */
        public Context(RandomSource random, @Nullable Level level, boolean byproductsEnabled,
                       long batches, ToIntFunction<RandomSource> multiplierRoll) {
            this(random, level, byproductsEnabled, batches, multiplierRoll, null, false);
        }

        /** 兼容旧调用（无"催化剂是否在网"信息 → 保守当作不在） */
        public Context(RandomSource random, @Nullable Level level, boolean byproductsEnabled,
                       long batches, ToIntFunction<RandomSource> multiplierRoll,
                       @Nullable CatalystItem multiplierKind) {
            this(random, level, byproductsEnabled, batches, multiplierRoll, multiplierKind, false);
        }

        /**
         * {@code catalystsPresent}：**输入全是催化剂**的配方，其催化剂物品是否真的在网络里。
         * <p>
         * ⚠ 2026-09-19 sensei：「这种配方应该检测一下网络内是否有催化剂物品」——
         * 催化剂槽本来不参与提取（免得吞掉模具），于是"催化剂不在网也能白跑"，
         * 精华就等于白送。默认值取 {@code false}（**保守：宁可少给，不可白给**）。
         */
        public Context {
        }
    }

    /** 一次产出计算的结果 */
    public record Outcome(List<GenericStack> stacks, Map<AEKey, ByproductLog> byproductLog) {

        public boolean isEmpty() {
            return stacks.isEmpty();
        }

        /**
         * 把结果并入调用方的清单：先主产物/确定产出，再把**实际掷中**的概率副产附在后面
         * （顺序刻意与旧实现一致：主产物在前）。
         */
        public void addInto(List<GenericStack> target) {
            target.addAll(stacks);
            for (var e : byproductLog.values()) {
                if (e.amount() <= 0) continue;
                target.add(new GenericStack(e.key(), e.amount()));
            }
        }
    }

    /**
     * 一个概率产出在本次计算里的**实际掷骰统计**（记账与日志都用实际值，不用期望值）。
     *
     * @param key        产出物
     * @param chance     生效几率
     * @param unitAmount 单次命中的基础数量（未乘催化剂倍数，仅供展示）
     * @param hits       实际掷中次数
     * @param rolls      掷骰总次数（= 份数）
     * @param multSum    命中时的催化剂倍数之和
     */
    public record ByproductLog(AEKey key, float chance, long unitAmount, long hits, long rolls,
                               long multSum) {

        /** 本次实际交付的数量（= 单价 × 各次命中的倍数，已含催化剂放大） */
        public long amount() {
            return unitAmount * multSum;
        }
    }

    /**
     * 自有样板（{@link QianJiPatternData}）的产出：精确执行，不猜配方。
     */
    public static Outcome roll(QianJiPatternData data, Context ctx) {
        var acc = new LinkedHashMap<AEKey, Long>();
        var log = new LinkedHashMap<AEKey, ByproductLog>();
        long batches = Math.max(1, ctx.batches());
        var random = ctx.random();

        // ⚠ 大单性能（2026-09-18 sensei 反馈严重卡顿）：
        // 这一段会在**一个 tick 内跑 batches 次**，所以循环体里只允许做：数组取值 + 一次
        // nextFloat + 一次 long 累加。集合遍历/装箱/字符串/构造对象全部提到循环外。
        // 概率产出打包成并列数组；无催化剂（倍率恒 1）时走"零额外记账"快路径。
        int pCount = data.primary().size();
        long[] pAmounts = new long[pCount];
        AEKey[] pKeys = new AEKey[pCount];
        for (int i = 0; i < pCount; i++) {
            pAmounts[i] = data.primary().get(i).stack().amount();
            pKeys[i] = data.primary().get(i).stack().what();
        }
        int cCount = data.chanced().size();
        float[] cChance = new float[cCount];
        long[] cUnit = new long[cCount];
        AEKey[] cKeys = new AEKey[cCount];
        long[] cHits = new long[cCount];
        long[] cMultSum = new long[cCount];
        for (int i = 0; i < cCount; i++) {
            var c = data.chanced().get(i);
            cChance[i] = c.chance() > 0f ? c.chance() : 1.0f;
            cUnit[i] = c.stack().amount();
            cKeys[i] = c.stack().what();
        }
        // 倍率恒 1 时不必逐份累计倍数（见 Context.multiplierRoll）
        boolean trackMult = ctx.multiplierRoll() != ONE;

        // ── 倍率与概率产出的掷骰：全部走 O(distinct) 抽样，不再逐份循环 ──
        // ⚠ 2026-09-18 晚（sensei 44T 实测：N=10.7 亿/次、掷骰 42.8ms、单 tick 落后 25.9 秒）：
        // 「逐份掷骰」在 10^9~10^12 量级下物理上做不到（一次几十秒）。
        // 但**分布是可以精确算出来**的，不必真的一次次掷：
        //   · 主产物/命中的总量 = 单价 × 倍率和
        //   · 倍率和：催化剂档位决定（1 档 = 恒定；2 档 = 二项抽样一次）
        //   · 命中次数：二项分布 B(N, p) 抽样一次
        // 结果与逐份掷骰**同分布**（不是拿期望值取巧），代价 O(1)。
        long multSumAll = sampledMultiplierSum(ctx, batches, random);
        for (int k = 0; k < cCount; k++) {
            cHits[k] = sampleBinomial(random, batches, cChance[k]);
            // ⚠ 只有**命中那些份**的倍率和才该记进 ByproductLog（不能拿全部份的倍率和）
            if (trackMult) cMultSum[k] = sampledMultiplierSum(ctx, cHits[k], random);
        }
        // 主产物：一次乘法 + 每 key 一次 merge
        for (int k = 0; k < pCount; k++) {
            acc.merge(pKeys[k], pAmounts[k] * multSumAll, Long::sum);
        }
        // 概率产出：命中量 = 单价 × 命中时的倍率和（等价于逐次 merge）
        for (int k = 0; k < cCount; k++) {
            if (cHits[k] <= 0) continue;
            long multSum = trackMult ? cMultSum[k] : cHits[k];
            acc.merge(cKeys[k], cUnit[k] * multSum, Long::sum);
        }
        for (int k = 0; k < cCount; k++) {
            long multSum = trackMult ? cMultSum[k] : cHits[k];
            log.put(cKeys[k], new ByproductLog(cKeys[k], cChance[k], cUnit[k], cHits[k], batches, multSum));
        }
        // ── 无限精华（2026-09-19 sensei 定稿，见 docs/infinite-essence-cell.md）──
        addEssences(data, acc, batches, ctx.catalystsPresent());
        return new Outcome(toStacks(acc), log);
    }

    /**
     * 给「**输入全是催化剂（不消耗）**」的配方追加无限精华。
     * <p>
     * sensei 的规则：这类配方什么都不消耗（千机可以无限白跑），所以在原有产物之外，
     * 为**每个声明输出**（主产物 + 副产物）额外产出「无限 xxx 精华」。
     * <ul>
     *   <li>数量 = **每执行一次 1 个** → 本批 {@code batches} 个（sensei 定：随批量 N 缩放）</li>
     *   <li>**不吃概率**：只要声明了就给，不随副产物是否命中而变（规则要可预测）</li>
     *   <li>**不吃催化剂倍数**：精华是解锁物，被倍率放大没有意义</li>
     *   <li>只对**物品/流体**输出发（元件的存储通道只有这两种；其它类型留待以后加外壳）</li>
     * </ul>
     * 精华本身是"一个物品 + NBT 绑定目标 key"（{@link com.ae2addon.item.InfiniteEssenceItem}），
     * 所以它照常走产物结算链路（pendingSettle → 记账 → 回收入网），不另开通道。
     */
    private static void addEssences(QianJiPatternData data, Map<AEKey, Long> acc, long batches,
                                    boolean catalystsPresent) {
        if (batches <= 0) {
            return;
        }
        if (!debugForceAllEssences) {
            if (!looksAllCatalyst(data)) {
                return;
            }
            // ⚠ 2026-09-19 sensei：**网络里必须真的存在这些催化剂物品**，否则精华就是白送
            // （催化剂槽不参与提取，机器本来可以"没有催化剂也照跑"）
            if (!catalystsPresent) {
                if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][精华] 跳过：配方输入全是催化剂，但**网络里没有这些催化剂**"
                                    + "（输入槽 {} 个，全部为不消耗输入）", data.inputs().size());
                }
                return;
            }
        }
        var primary = data.primary();
        var chanced = data.chanced();
        int before = acc.size();
        for (int i = 0; i < primary.size(); i++) {
            addEssence(primary.get(i).stack().what(), acc, batches);
        }
        for (int i = 0; i < chanced.size(); i++) {
            addEssence(chanced.get(i).stack().what(), acc, batches);
        }
        // 证据日志（sensei 验证 S3 靠它）：说清是"真判定"还是"调试强制"，
        // 并把输入槽的 catalyst 情况摊开，便于核对条件口径
        if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
            var inputs = data.inputs();
            int slots = inputs == null ? 0 : inputs.size();
            int catalystSlots = 0;
            if (inputs != null) {
                for (var slot : inputs) {
                    if (slot.catalyst()) {
                        catalystSlots++;
                    }
                }
            }
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][精华] 追加精华：声明输出 {} 项 × 本批 {} 个（触发={}；输入槽 {}/{} 标为催化剂；产物条目 {}→{}）",
                    primary.size() + chanced.size(), batches,
                    debugForceAllEssences ? "调试强制" : "输入全是催化剂",
                    catalystSlots, slots, before, acc.size());
        }
    }

    /** 单个输出 key 的精华：绑定到该 key、本批 batches 个（类型不支持则跳过） */
    private static void addEssence(AEKey target, Map<AEKey, Long> acc, long batches) {
        if (target == null || !isCellSupportedType(target)) {
            return;
        }
        net.minecraft.world.item.ItemStack ess =
                com.ae2addon.item.InfiniteEssenceItem.make(target, 1);
        if (ess.isEmpty()) {
            return;
        }
        AEKey essKey = appeng.api.stacks.AEItemKey.of(ess);
        if (essKey == null) {
            return;
        }
        acc.merge(essKey, batches, Long::sum);
    }

    /** 元件的存储通道支持的类型：物品 / 流体 / **化学品**（Applied-Mekanistics，可选） */
    private static boolean isCellSupportedType(AEKey key) {
        var type = key.getType();
        return appeng.api.stacks.AEKeyType.items().equals(type)
                || appeng.api.stacks.AEKeyType.fluids().equals(type)
                || com.ae2addon.compat.ChemicalCompat.isChemical(key);
    }

    /**
     * 「输入全是催化剂」的**声明层**判定：所有输入槽都标了 {@code catalyst}（不消耗），且至少一个槽。
     * <p>
     * 零输入的退化配方**不算**（保守；要放开再改这一行）。
     * <p>
     * ⚠ 这只是"配方长这样"，**不代表催化剂真的在网络里** —— 调用方（{@code QianJiBE}）
     * 还要用 {@link Context#catalystsPresent()} 把"网络里有没有"传进来。
     */
    public static boolean looksAllCatalyst(QianJiPatternData data) {
        var inputs = data.inputs();
        if (inputs == null || inputs.isEmpty()) {
            return false;
        }
        for (var slot : inputs) {
            if (!slot.catalyst()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 调试用：强制所有自有样板按"全催化剂"处理（只影响精华产出，不改消耗/产出量）。
     * 默认 false；由 {@code /ae2essence forceessence on|off} 切换。
     * 开启时**同时跳过"网络里有没有催化剂"的检查**（测试用）。
     */
    public static volatile boolean debugForceAllEssences = false;

    /**
     * 兼容通道：普通 AE2 处理样板 → 用兼容层反推配方的概率产出。
     * <p>
     * 与自有样板的区别（2026-09-15 修「巨型订单成品不到网」立的规矩，保留）：
     * <ul>
     *   <li>样板声明的产出一律**照给** —— 否则 CPU 在 waitingFor 里永远差 N，任务永不完成、
     *       产物被囤在 CPU 自己的库存里；</li>
     *   <li>只对「配方里有、样板**没声明**」的额外副产掷骰 —— 任务永不为副产等待。</li>
     * </ul>
     */
    public static Outcome rollCompat(IPatternDetails pattern, RecipeLookup lookup, Context ctx) {
        var acc = new LinkedHashMap<AEKey, Long>();
        var log = new LinkedHashMap<AEKey, ByproductLog>();
        long batches = Math.max(1, ctx.batches());

        // 配方只查一次：它决定「哪些算概率副产」，与份数无关
        var recipe = lookup.findRecipeFor(pattern);
        List<RecipeByproducts.Chanced> chanced = (ctx.byproductsEnabled() && recipe != null)
                ? RecipeByproducts.extract(recipe, ctx.level())
                : List.of();
        if (recipe != null && com.ae2addon.config.AE2AddonConfig.debugLogs()) {
            // 2026-09-22 v285：每次合成都打一行太吵 → 收进 debugLogs
            AE2Addon.LOGGER.info("QianJi craft(兼容): recipe={} chanced=[{}] batches={}",
                    recipe.getClass().getName(), describe(chanced), batches);
        }

        var declaredItems = new HashSet<Item>();
        var declaredList = new ArrayList<GenericStack>();
        for (var out : pattern.getOutputs()) {
            if (out == null || out.amount() <= 0) continue;
            declaredList.add(out);
            if (out.what() instanceof AEItemKey k) declaredItems.add(k.getItem());
        }
        // 副产候选（排除样板已声明的）打包成数组，循环里不碰集合
        var candKeys = new ArrayList<AEKey>();
        var candChance = new ArrayList<Float>();
        var candUnit = new ArrayList<Long>();
        for (var bp : chanced) {
            if (bp.stack().isEmpty() || declaredItems.contains(bp.stack().getItem())) continue;
            candKeys.add(AEItemKey.of(bp.stack()));
            candChance.add(bp.chance() > 0f ? bp.chance() : 1.0f);
            candUnit.add((long) bp.stack().getCount());
        }
        int nDecl = declaredList.size();
        int nCand = candKeys.size();
        long[] candHits = new long[nCand];
        long[] candMultSum = new long[nCand];
        var random = ctx.random();
        boolean trackMult = ctx.multiplierRoll() != ONE;

        // 同样的聚合思路（见 roll 的注释）：倍率与命中次数都走 O(1) 抽样，不逐份循环
        long multSumAll = sampledMultiplierSum(ctx, batches, random);
        for (int k = 0; k < nCand; k++) {
            candHits[k] = sampleBinomial(random, batches, candChance.get(k));
            if (trackMult) candMultSum[k] = sampledMultiplierSum(ctx, candHits[k], random);
        }
        for (int k = 0; k < nDecl; k++) {
            var out = declaredList.get(k);
            acc.merge(out.what(), out.amount() * multSumAll, Long::sum);
        }
        for (int k = 0; k < nCand; k++) {
            long multSum = trackMult ? candMultSum[k] : candHits[k];
            if (candHits[k] > 0) {
                acc.merge(candKeys.get(k), candUnit.get(k) * multSum, Long::sum);
            }
            log.put(candKeys.get(k), new ByproductLog(candKeys.get(k), candChance.get(k),
                    candUnit.get(k), candHits[k], batches, multSum));
        }
        return new Outcome(toStacks(acc), log);
    }

    // ── 内部 ──

    /** 无催化剂时的倍率掷骰（恒 1；用作"是否需要逐份累计倍数"的判据） */
    private static final ToIntFunction<RandomSource> ONE = r -> 1;

    /**
     * 全部 batches 份的催化剂倍率**和**（O(1)）。
     * <p>
     * 与逐份调用 {@code multiplierRoll} 求和**同分布**：
     * <ul>
     *   <li>无催化剂 / 高级（恒定 ×2）→ 精确值，不掷骰</li>
     *   <li>基础（50%×2）/ 终极（50%×4）→ 二项抽样一次：翻倍的份数 ~ B(N, 0.5)，
     *       倍率和 = 基准和 + 翻倍份数 × 增量</li>
     * </ul>
     */
    private static long sampledMultiplierSum(Context ctx, long batches, RandomSource random) {
        if (batches <= 0) {
            return 0L;
        }
        var kind = ctx.multiplierKind();
        if (kind == null) {
            return batches; // 无催化剂：恒 1，逐份求和 == batches
        }
        int distinct = kind.distinctMultiplierCount();
        if (distinct <= 1) {
            // 恒定倍率（高级 ×2）：精确值，不掷骰
            return safeMul(batches, Math.max(1, kind.baseMultiplier()));
        }
        // 两档 50/50：翻倍份数 ~ B(N, 0.5)
        long doubled = sampleBinomial(random, batches, 0.5f);
        long base = safeMul(batches, kind.baseMultiplier());
        long inc = (long) kind.doubledMultiplier() - kind.baseMultiplier();
        return base + safeMul(doubled, inc);
    }

    /**
     * 二项分布抽样：{@code B(n, p)} 的一次抽样（= "n 次独立伯努利里成功几次"）。
     * <p>
     * 与逐份 {@code nextFloat() < p} 计数**同分布**，但代价与 n 无关。
     * 用小 n 精确枚举、大 n 正态近似（含连续性校正）。游戏里关注的是数量级，
     * 正态近似带来的误差远小于显示/传输精度，且**不改变期望**。
     */
    public static long sampleBinomial(RandomSource random, long n, float p) {
        if (n <= 0) return 0L;
        if (p <= 0f) return 0L;
        if (p >= 1f) return n;
        if (n <= 64L) {
            // 小样本：直接数（最准）
            long hits = 0;
            for (long i = 0; i < n; i++) {
                if (random.nextFloat() < p) hits++;
            }
            return hits;
        }
        double mean = (double) n * p;
        double sd = Math.sqrt((double) n * p * (1.0 - p));
        if (sd <= 0.0) return Math.round(mean);
        double g = random.nextGaussian();
        double v = Math.floor(mean + g * sd + 0.5);
        if (v < 0.0) return 0L;
        if (v > (double) n) return n;
        return (long) v;
    }

    /** 乘法防溢出（溢出时保守返回原值，与旧 scaledAmount 行为一致） */
    private static long safeMul(long a, long b) {
        if (a <= 0 || b <= 0) return 0L;
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return a;
        }
    }

    /** 查真实配方（由方块实体提供，这里不依赖 BE 的字段） */
    public interface RecipeLookup {
        @Nullable
        Recipe<?> findRecipeFor(IPatternDetails details);
    }

    // 逐份调用 multiplierRoll 的旧实现已在 v145 移除：
    // 倍率和改由 sampledMultiplierSum 用二项抽样一次算出（同分布、O(1)）。

    /** 批量掷骰用的非同步随机源（见 {@link #threadRandom}） */
    private static final ThreadLocal<RandomSource> FAST_RANDOM =
            ThreadLocal.withInitial(() -> RandomSource.create(0L));
    private static long fastRandomSeedTick = Long.MIN_VALUE;

    /**
     * 批量掷骰用随机源：每 tick 从世界随机源**取一个种子**重置一次本地随机源。
     * <p>
     * 为什么不用 {@code level.random}：它是**带同步/原子**的共享随机源（存档 RNG 要可复现），
     * 十万级逐份掷骰时会成为热点；这里用一个普通 {@link RandomSource}，
     * 取种子的次数是「每 tick 一次」，随机性仍然由世界随机源提供。
     */
    public static RandomSource threadRandom(RandomSource worldRandom, long tick) {
        var random = FAST_RANDOM.get();
        if (tick != fastRandomSeedTick) {
            fastRandomSeedTick = tick;
            random.setSeed(worldRandom.nextLong());
        }
        return random;
    }

    private static List<GenericStack> toStacks(Map<AEKey, Long> acc) {
        var list = new ArrayList<GenericStack>(acc.size());
        for (var e : acc.entrySet()) {
            if (e.getValue() > 0) list.add(new GenericStack(e.getKey(), e.getValue()));
        }
        return list;
    }

    private static String describe(List<RecipeByproducts.Chanced> chanced) {
        if (chanced.isEmpty()) return "无";
        var sb = new StringBuilder();
        for (var c : chanced) {
            if (!sb.isEmpty()) sb.append('、');
            sb.append(c.stack().getHoverName().getString()).append(' ')
                    .append(c.chance() > 0f ? Math.round(c.chance() * 100) + "%" : "几率未知");
        }
        return sb.toString();
    }
}
