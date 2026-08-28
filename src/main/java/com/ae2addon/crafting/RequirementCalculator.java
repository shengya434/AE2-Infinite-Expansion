package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * BigInteger 需求树估算器（带每单位需求缓存）。
 * <p>
 * 用途：下单前用 BigInteger 完整展开配方树，算出「最坏情况下」每个材料的
 * 总需求量（纯合成路径，不抵扣库存）。当任一材料需求超过 long 记账上限时，
 * AE2 原版模拟（long 记账）会溢出/卡死——本计算器在拦截点重新估算需求，
 * 从而把订单拆成多个安全批次。
 * <p>
 * 重要设计决策（2026-08-20 教训）：
 * <b>不抵扣库存</b>——无限存储（Mode 3）会把库存报告成 Long.MAX_VALUE 哨兵值，
 * 若按库存抵扣，需求树会因「库存全覆盖」而提前终止，链式配方（9a→1b→…）的
 * 指数级需求永远算不出来，导致拦截失效。纯配方树展开给出最坏上界，
 * 对「库存充足」的订单最多造成多余的拆批（每批快速从库存提取完成，无害）。
 * <p>
 * 性能优化（2026-08-22）：
 * <ul>
 *   <li><b>每单位需求缓存</b>：同一物品的「每单位最坏需求」只展开一次
 *       （按网格缓存，LRU 4096），下单时 O(1) 查表 + BigInteger 乘法缩放，
 *       不再每次请求都全量展开配方树（原实现：每次合成请求都展开一次）。</li>
 *   <li><b>单趟分析</b>：一次 {@link #analyze} 同时产出「是否超限 + 每单位需求 +
 *       最大安全批 + 需求明细」，替代原来的 isOversized + maxSafeBatch 双重展开。</li>
 *   <li><b>懒失效</b>：缓存条目保存展开时使用的配方列表快照；查询时逐个比对
 *       getCraftingFor 返回的列表（先比实例、再比内容），配方变了才重算。
 *       连续失效抖动（AE2 每次返回新列表实例）时短暂钉住缓存防抖。</li>
 *   <li><b>截断即超限</b>：展开预算耗尽时标记 truncated——需求少算不能当作
 *       「安全」，调用方必须拒绝订单而不是放行原版模拟（原实现静默截断 =
 *       低估 → 放行 → long 溢出卡死，正确性 bug，2026-08-22 修复）。</li>
 *   <li><b>EMC 忽略（2026-08-22 兼容计划撤回）</b>：AppliedE 的 TransmutationPattern
 *       （EMC 转换）输入是 EMCKey 假键——按 key 类型识别后跳过，不参与展开
 *       （是「忽略 EMC」不是「兼容 EMC」）。EMC 链的性能级拦截（系统 B）已撤回，
 *       拦截只按 SAFE_LIMIT 溢出级（系统 A）；EMC 链的非溢出订单走原版。
 *       教训：EMC 的存在让优化系统复杂度爆炸（双系统/优先级/假计划），
 *       sensei 拍板：不为 EMC 做兼容优化。</li>
 * </ul>
 * 保守策略（宁高勿低，保证拆批后每批都安全）：
 * - 多配方时选「单次材料总消耗最大」的配方
 * - 多选输入槽（possibleInputs）取数量最大的选项
 * - 忽略副产物抵扣
 * <p>
 * 防爆：递归路径集合防环（DFS 栈）；展开总次数上限 MAX_EXPANSIONS 兜底；
 * 每层只沿「最坏配方」单分支展开，不会因多候选配方分支爆炸。
 */
public final class RequirementCalculator {

    /** 单次估算最大展开节点数（防配方环/超深树导致卡顿）；缓存命中后每物品只付一次 */
    private static final int MAX_EXPANSIONS = 200_000;

    /** 每网格缓存条目上限（LRU，防止配方集很大的服务器把缓存撑爆） */
    private static final int CACHE_MAX_ENTRIES = 4096;

    /** 连续失效多少次后短暂钉住缓存（防 AE2 每次返回新列表实例导致的抖动重算） */
    private static final int PIN_AFTER_INVALIDATIONS = 3;

    /** 钉住期间跳过校验的查询次数（钉住结束后重新开放校验） */
    private static final int PIN_QUERY_BUDGET = 64;

    /** 每网格缓存（grid 弱键：网格卸载/世界关闭后自动回收，不跨世界泄漏） */
    private static final WeakHashMap<IGrid, GridCache> CACHES = new WeakHashMap<>();

    private RequirementCalculator() {}

    /** 单个网格的缓存。 */
    private static final class GridCache {
        final LinkedHashMap<AEKey, CachedNeeds> entries =
                new LinkedHashMap<>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<AEKey, CachedNeeds> eldest) {
                        return size() > CACHE_MAX_ENTRIES;
                    }
                };
    }

    /** 某物品的每单位最坏需求 + 配方快照（懒失效校验用）。 */
    private static final class CachedNeeds {
        final Map<AEKey, BigInteger> perUnit;
        /** 每单位产物的样板执行次数（BigInteger，防 long 溢出；buildBatchPlan 用） */
        final Map<IPatternDetails, BigInteger> perUnitPatternTimes;
        /**
         * 每单位<b>叶子材料</b>需求（无配方/外部提供的终端材料，如永恒之星、无尽桶）。
         * 原版 tryExtractInitialItems 只从网络提取叶子材料——中间产物（奇异点等）
         * 由 CPU 按 patternTimes 执行样板合成，不能出现在 usedItems 里
         * （否则网络无该中间物库存 → MISSING_INGREDIENT，sensei 实测 18:31）。
         */
        final Map<AEKey, BigInteger> perUnitLeafNeeds;
        /** 展开时用到的每个物品的配方列表快照（校验：getCraftingFor 是否还是这些） */
        final Map<AEKey, Collection<IPatternDetails>> snapshots;
        final boolean truncated;
        int invalidations;
        int skipValidation;

        CachedNeeds(Map<AEKey, BigInteger> perUnit,
                    Map<IPatternDetails, BigInteger> perUnitPatternTimes,
                    Map<AEKey, BigInteger> perUnitLeafNeeds,
                    Map<AEKey, Collection<IPatternDetails>> snapshots,
                    boolean truncated) {
            this.perUnit = perUnit;
            this.perUnitPatternTimes = perUnitPatternTimes;
            this.perUnitLeafNeeds = perUnitLeafNeeds;
            this.snapshots = snapshots;
            this.truncated = truncated;
        }
    }

    /** 一次分析的结果（单趟产出全部答案）。 */
    public static final class Analysis {
        /** 是否超限（截断时强制视为超限） */
        public final boolean oversized;
        /** 配方树展开被预算截断：需求少算，无法证明安全，调用方必须拒绝订单 */
        public final boolean truncated;
        /** 安全单批产物量（未超限时 = amount） */
        public final long maxSafeBatch;
        /** 按 amount 缩放后的材料需求（BigInteger，供确认界面饱和显示） */
        public final Map<AEKey, BigInteger> needs;
        /** 每单位产物需求（缓存主数据） */
        public final Map<AEKey, BigInteger> perUnit;

        Analysis(boolean oversized, boolean truncated, long maxSafeBatch,
                 Map<AEKey, BigInteger> needs, Map<AEKey, BigInteger> perUnit) {
            this.oversized = oversized;
            this.truncated = truncated;
            this.maxSafeBatch = maxSafeBatch;
            this.needs = needs;
            this.perUnit = perUnit;
        }
    }

    /**
     * 单趟分析（缓存加速）：估算下单 (what, amount) 的最坏材料需求，
     * 并给出是否超限、最大安全单批产物量。
     * <p>
     * 2026-08-22 双系统拆分：
     * <ul>
     *   <li><b>系统 A（巨型订单，溢出级）</b>：任一材料需求 > overflowLimit（SAFE_LIMIT）
     *       → 拦截，按 overflowLimit 拆批（CPU long 记账安全；高单单位需求物品也能拆）。</li>
     *   <li><b>系统 B（性能保护，EMC 级）</b>：需求 > perfLimit（PERF_LIMIT）且链含 EMC
     *       配方 → 拦截，按 perfLimit 拆批（批次小、EMC 模拟快，原版计算卡死的真凶）。</li>
     *   <li>其余（含测试样板撑爆但无 EMC 的）→ 不拦截，放行原版（报缺料或执行）。</li>
     * </ul>
     */
    public static Analysis analyze(IGrid grid, AEKey what, long amount, long safeLimit) {
        CachedNeeds cached = cachedPerUnit(grid, what);
        Map<AEKey, BigInteger> perUnit = cached.perUnit;

        // 缩放：needs = perUnit × amount
        BigInteger amountBI = BigInteger.valueOf(amount);
        Map<AEKey, BigInteger> needs = new HashMap<>();
        boolean oversized = cached.truncated;
        BigInteger limitBI = BigInteger.valueOf(safeLimit);
        for (var e : perUnit.entrySet()) {
            BigInteger scaled = e.getValue().multiply(amountBI);
            needs.put(e.getKey(), scaled);
            if (!oversized && scaled.compareTo(limitBI) > 0) {
                oversized = true;
            }
        }

        long perBatch;
        if (!oversized) {
            perBatch = amount;
        } else if (cached.truncated) {
            perBatch = 1; // 调用方会拒绝；这里给最保守值
        } else {
            perBatch = maxSafeBatchOf(perUnit, amount, safeLimit);
        }
        return new Analysis(oversized, cached.truncated, perBatch, needs, perUnit);
    }

    /** 兼容入口（BatchedCraftingOrder 用）：只算最大安全单批产物量。 */
    public static long maxSafeBatch(IGrid grid, AEKey what, long amount, long safeLimit) {
        return analyze(grid, what, amount, safeLimit).maxSafeBatch;
    }

    /**
     * 2026-08-27 方案2：用 BigInteger 估算直接构造批次真计划（simulation=false），
     * 完全绕过原版 AE2 模拟（long 累加在 999999×999999×batch 中间溢出 → 损坏计划）。
     * <p>
     * 由每单位需求 + 每单位样板执行次数缩放得到批次需求，饱和到 long 填充计划。
     * 注意：估算不抵扣库存（纯配方树最坏上界）——批次提交后由 CPU 按计划提取，
     * 库存足够时正常执行；缺料时 CPU 报缺料（与模拟拦截的饱和计划行为一致）。
     *
     * @return 真 CraftingPlan（simulation=false）；网格/节点不可用时返回 null
     */
    public static appeng.api.networking.crafting.ICraftingPlan buildBatchPlan(
            IGrid grid, AEKey what, long amount) {
        if (grid == null || what == null || amount <= 0) {
            return null;
        }
        CachedNeeds cached = cachedPerUnit(grid, what);
        if (cached.truncated) {
            // 估算被截断：无法证明安全，不能构造计划（调用方应拒绝）
            return null;
        }
        BigInteger amountBI = BigInteger.valueOf(amount);

        var used = new appeng.api.stacks.KeyCounter();
        // 只放叶子材料（无配方/外部提供）的<b>总需求</b>：中间产物（奇异点等）由 CPU
        // 按 patternTimes 执行样板合成，不能出现在 usedItems——否则原版
        // tryExtractInitialItems 尝试从网络提取中间物 → 无库存 → MISSING_INGREDIENT。
        // 2026-08-27 19:30 修复：此前用「缺口=总需求−库存」，用户无限模式库存巨大 →
        // gap=0 → usedItems 空 → tryExtractInitialItems 无事可做 → CPU inventory 空
        // → executeCrafting 所有 task 提取失败（1x提取 null，日志实锤）→ 订单卡死。
        // 原版语义：usedItems = 需从网络提取注入 CPU inventory 的初始材料（叶子总需求），
        // 库存不够时 tryExtractInitialItems 自然报 MISSING_INGREDIENT（正确缺料行为）。
        for (var e : cached.perUnitLeafNeeds.entrySet()) {
            BigInteger need = e.getValue().multiply(amountBI);
            long needLong = need.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            if (needLong > 0) {
                used.add(e.getKey(), needLong);
            }
        }
        Map<appeng.api.crafting.IPatternDetails, Long> patternTimes = new HashMap<>();
        for (var e : cached.perUnitPatternTimes.entrySet()) {
            BigInteger scaled = e.getValue().multiply(amountBI);
            patternTimes.put(e.getKey(), scaled
                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue());
        }

        return new appeng.crafting.CraftingPlan(
                new appeng.api.stacks.GenericStack(what, amount),
                Long.MAX_VALUE,   // bytes（集成 CPU 无限存储；原版 CPU 也接受哨兵）
                false,            // simulation
                false,            // multiplePaths
                used,             // usedItems（缺口 = 总需求 - 库存）
                new appeng.api.stacks.KeyCounter(), // emittedItems
                new appeng.api.stacks.KeyCounter(), // missingItems
                patternTimes);
    }

    /** 由每单位需求反推单批安全产物量（瓶颈材料 = 需求最大的那个）。 */
    private static long maxSafeBatchOf(Map<AEKey, BigInteger> perUnit, long amount, long safeLimit) {
        BigInteger perBatch = BigInteger.valueOf(amount);
        BigInteger limitBI = BigInteger.valueOf(safeLimit);
        for (var e : perUnit.entrySet()) {
            BigInteger perUnitNeed = e.getValue();
            if (perUnitNeed.signum() <= 0) {
                continue;
            }
            BigInteger maxBatch = limitBI.divide(perUnitNeed);
            if (maxBatch.compareTo(perBatch) < 0) {
                perBatch = maxBatch;
            }
        }
        if (perBatch.signum() <= 0) {
            return 1;
        }
        long batch = perBatch.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        return Math.max(1, Math.min(batch, amount));
    }

    /** 取（或算）某物品的每单位需求缓存条目。 */
    private static CachedNeeds cachedPerUnit(IGrid grid, AEKey what) {
        GridCache cache = CACHES.get(grid);
        if (cache == null) {
            cache = new GridCache();
            CACHES.put(grid, cache);
        }
        CachedNeeds entry = cache.entries.get(what);
        if (entry == null) {
            entry = computePerUnit(grid, what);
            cache.entries.put(what, entry);
            return entry;
        }
        if (entry.skipValidation > 0) {
            entry.skipValidation--;
            return entry; // 钉住期：跳过校验，用缓存值
        }
        if (isValid(grid, entry)) {
            entry.invalidations = 0;
            return entry;
        }
        if (++entry.invalidations >= PIN_AFTER_INVALIDATIONS) {
            // 连续失效：疑似 AE2 每次调用都返回新列表实例（内容没变），
            // 短暂钉住避免每次查询都重算整个配方树。
            entry.skipValidation = PIN_QUERY_BUDGET;
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 配方缓存连续失效 {} 次，短暂钉住 {}（疑似配方列表抖动）",
                    entry.invalidations, what);
            return entry;
        }
        CachedNeeds fresh = computePerUnit(grid, what);
        cache.entries.put(what, fresh);
        return fresh;
    }

    /** 懒失效校验：快照里每个物品的配方列表是否还是原来的（先比实例，再比内容）。 */
    private static boolean isValid(IGrid grid, CachedNeeds entry) {
        var service = grid.getCraftingService();
        for (var e : entry.snapshots.entrySet()) {
            Collection<IPatternDetails> current = service.getCraftingFor(e.getKey());
            Collection<IPatternDetails> cached = e.getValue();
            if (current == cached) {
                continue;
            }
            // 空列表可能每次都是新实例：两边都空视为未变化
            if (cached.isEmpty() && current.isEmpty()) {
                continue;
            }
            // 内容一致（同一批 pattern 实例）视为未变化
            if (current.equals(cached)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** 计算某物品的每单位最坏需求（展开 1 单位产物的整棵配方树）。 */
    private static CachedNeeds computePerUnit(IGrid grid, AEKey what) {
        Map<AEKey, BigInteger> needs = new HashMap<>();
        Map<IPatternDetails, BigInteger> patternTimes = new HashMap<>();
        Map<AEKey, BigInteger> leafNeeds = new HashMap<>();
        Map<AEKey, Collection<IPatternDetails>> snapshots = new HashMap<>();
        int[] budget = {0};
        boolean[] truncated = {false};
        if (CraftingCompat.debugLogs) {
            var rootPatterns = grid.getCraftingService().getCraftingFor(what);
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] 估算展开根 {}: 配方 {} 个", what, rootPatterns.size());
            for (var p : rootPatterns) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug]   配方 {} 成本={} 可展开={} 是EMC={}",
                        p.getClass().getName(), patternCost(p),
                        isExpandable(grid, p), isEmcPattern(p));
            }
        }
        expand(grid, what, BigInteger.ONE, new HashSet<>(), needs, patternTimes,
                leafNeeds, snapshots, budget, truncated);
        if (CraftingCompat.debugLogs) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] 每单位需求 {}: {} 种材料, 截断={}",
                    what, needs.size(), truncated[0]);
            int printed = 0;
            for (var e : needs.entrySet()) {
                if (printed++ >= 20) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][debug]   ...共 {} 种", needs.size());
                    break;
                }
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug]   {} = {}", e.getKey(), e.getValue());
            }
        }
        return new CachedNeeds(needs, patternTimes, leafNeeds, snapshots, truncated[0]);
    }

    private static void expand(IGrid grid, AEKey key, BigInteger need,
                               Set<AEKey> path,
                               Map<AEKey, BigInteger> needs,
                               Map<IPatternDetails, BigInteger> patternTimes,
                               Map<AEKey, BigInteger> leafNeeds,
                               Map<AEKey, Collection<IPatternDetails>> snapshots,
                               int[] budget, boolean[] truncated) {
        if (++budget[0] > MAX_EXPANSIONS) {
            truncated[0] = true; // 预算耗尽：需求少算，标记截断（调用方必须拒绝，不能当安全）
            return;
        }
        needs.merge(key, need, BigInteger::add);

        // 防环：当前路径已展开过该 key（如 EMC 互转 A→B→A）
        if (!path.add(key)) {
            return;
        }

        // EMC 假键是终端叶子（2026-08-22）：虚拟货币不能「造」出更多材料，
        // 记录需求即止。绝不查它的「配方」——AppliedE 给 EMCKey 也注册了
        // EMC→物品转换配方，展开会陷入 EMC 互转图爆炸/误导（sensei 建议）。
        if (isEmcKey(key)) {
            leafNeeds.merge(key, need, BigInteger::add);
            path.remove(key);
            return;
        }

        var service = grid.getCraftingService();
        Collection<IPatternDetails> patterns = service.getCraftingFor(key);
        snapshots.put(key, patterns);
        if (patterns.isEmpty()) {
            // 无配方：外部提供（如无尽桶、无限盘叶子）或缺失，无法展开——
            // 这是叶子材料，需求记入 leafNeeds（原版只从网络提取叶子；
            // 中间产物由 CPU 按 patternTimes 合成，不进 usedItems）。
            leafNeeds.merge(key, need, BigInteger::add);
            path.remove(key);
            return;
        }

        // 选「能继续展开」且「非 EMC」的配方里单次材料总消耗<b>最小</b>的（2026-08-22 改）：
        // AE2 原版计算选最小代价路径，估算必须镜像它——否则残留测试样板
        // （如 999999× quantum_entangled_singularity → dark_oak_log）会被当作最坏分支，
        // 每单位需求撑到 10^12+，连 1 单位的订单都被判超限/无法拆批
        // （sensei 实测 15:37：dark_oak_log 1M 单 eternal_heart=999998000001 → perBatch=1 拒绝）。
        // 安全性不变：若连最省路径都超限 → 拦截拆批，仍然正确。
        IPatternDetails best = null;
        BigInteger bestCost = null;
        for (var pattern : patterns) {
            if (isEmcPattern(pattern)) {
                continue;
            }
            if (!isExpandable(grid, pattern)) {
                continue;
            }
            BigInteger cost = patternCost(pattern);
            if (best == null || cost.compareTo(bestCost) < 0) {
                bestCost = cost;
                best = pattern;
            }
        }
        if (best == null) {
            // 全部被跳过（整条链都是 EMC/外部提供）：取消耗最小的兜底，需求照记
            for (var pattern : patterns) {
                BigInteger cost = patternCost(pattern);
                if (best == null || cost.compareTo(bestCost) < 0) {
                    bestCost = cost;
                    best = pattern;
                }
            }
        }
        if (best == null) {
            path.remove(key);
            return;
        }
        if (CraftingCompat.debugLogs) {
            StringBuilder sb = new StringBuilder();
            for (var input : best.getInputs()) {
                for (var c : input.getPossibleInputs()) {
                    if (c != null && c.what() != null) {
                        sb.append(c.what()).append("x").append(c.amount()).append(" ");
                    }
                }
            }
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] 展开 {}: 最省配方={} 成本={} 输入=[{}]",
                    key, best.getClass().getName(), bestCost, sb);
        }

        long outAmount = outputAmount(best, key);
        if (outAmount <= 0) {
            path.remove(key);
            return;
        }

        // 该配方需执行次数 = ceil(need / 单次产出)。
        // ⚠️ 2026-08-22 修复：need 是累计需求（每单位 × 上游倍数），不能按 1 单位算——
        // 第一刀误写成 ceil(1/outAmount)（恒=1），链式配方指数需求（9^9=387M）变成
        // 线性（每层都是 9），估算低估 8 个数量级 → 拦截失效 → 卡「正在计算」
        // （sensei 日志实锤：blackstone 每单位需求=9 而非 387420489）。
        BigInteger times = need.add(BigInteger.valueOf(outAmount - 1))
                .divide(BigInteger.valueOf(outAmount));

        // 2026-08-27：记录样板执行次数（BigInteger，buildBatchPlan 构造真计划用，
        // 避免批次模拟走原版 long 累加溢出）。
        patternTimes.merge(best, times, BigInteger::add);

        for (var input : best.getInputs()) {
            var possible = input.getPossibleInputs();
            GenericStack chosen = null;
            for (var candidate : possible) {
                if (candidate == null || candidate.what() == null) {
                    continue;
                }
                if (chosen == null || candidate.amount() > chosen.amount()) {
                    chosen = candidate;
                }
            }
            if (chosen == null) {
                continue;
            }
            BigInteger perUnit = BigInteger.valueOf(chosen.amount())
                    .multiply(BigInteger.valueOf(Math.max(1, input.getMultiplier())));
            expand(grid, chosen.what(), times.multiply(perUnit),
                    path, needs, patternTimes, leafNeeds, snapshots, budget, truncated);
        }

        path.remove(key);
    }

    /**
     * 配方是否「可展开」：至少一个输入候选有配方可继续展开。
     * 用于最坏分支选择时跳过死胡同（如 EMC 假键输入——EMC 键无配方）。
     * 外部提供材料（无尽桶等）没有配方，其输入同样视为死胡同。
     */
    private static boolean isExpandable(IGrid grid, IPatternDetails pattern) {
        var service = grid.getCraftingService();
        for (var input : pattern.getInputs()) {
            for (var candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null
                        && !service.getCraftingFor(candidate.what()).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否为 EMC 假键配方（AppliedE 的 TransmutationPattern）：输入是 EMCKey
     * （虚拟货币，不是真实材料）。EMC 假键自身也有配方（EMC→物品转换），
     * 所以不能只靠 isExpandable 排除——必须按 key 类型识别。
     * 按类名判断（不引用 AppliedE 类，避免硬依赖）：AEKeyType 实现类名含 "EMC"。
     */
    private static boolean isEmcPattern(IPatternDetails pattern) {
        for (var input : pattern.getInputs()) {
            for (var candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null
                        && isEmcKey(candidate.what())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isEmcKey(appeng.api.stacks.AEKey key) {
        try {
            String typeName = key.getType().getClass().getName();
            return typeName.contains("EMC") || typeName.contains("Emc");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 单次配方的材料总消耗（多选槽取最大选项，保守上界）。 */
    private static BigInteger patternCost(IPatternDetails pattern) {
        BigInteger cost = BigInteger.ZERO;
        for (var input : pattern.getInputs()) {
            long best = 0;
            for (var candidate : input.getPossibleInputs()) {
                if (candidate != null && candidate.what() != null) {
                    best = Math.max(best, candidate.amount());
                }
            }
            cost = cost.add(BigInteger.valueOf(best)
                    .multiply(BigInteger.valueOf(Math.max(1, input.getMultiplier()))));
        }
        return cost;
    }

    /** 配方输出中目标 key 的产出量（忽略副产物抵扣）。 */
    private static long outputAmount(IPatternDetails pattern, AEKey key) {
        for (var out : pattern.getOutputs()) {
            if (out != null && out.what() != null && out.what().equals(key)) {
                return out.amount();
            }
        }
        return 0;
    }
}
