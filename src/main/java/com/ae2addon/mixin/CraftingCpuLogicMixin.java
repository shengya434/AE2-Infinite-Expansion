package com.ae2addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.inv.ICraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.ae2addon.AE2Addon;
import com.ae2addon.block.IntegratedCPUBE;
import com.ae2addon.crafting.CraftingCompat;
import com.ae2addon.crafting.ScaledPattern;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 集成 CPU 的两大改造（参考 OmniSequence-Transfinite）：
 * <p>
 * 1. 时间片限流：AE2 的 tickCraftingLogic 中 {@code remainingOperations = getCoProcessors() + 1}
 *    是每 tick 的真实 push 次数上限。本 Mixin 让集成 CPU 的 executeCrafting 循环
 *    每次迭代检查 nanoTime 预算，超时立即终止 —— 线程数只是账面值，游戏不卡。
 *    预算自适应：headroom = 目标MSPT(45ms) − 服务器平均MSPT，夹在 [1ms, 32ms]。
 * <p>
 * 2. 批量推送（scaled pattern）：一次 pushPattern 携带 N× 配方输入，接收方接受后
 *    一次完成 N 份合成。N 自适应：成功翻倍，失败减半，1× 失败锁定为逐条推送。
 *    对无限消费型接收方（创造垃圾桶等）可以瞬间把订单材料全部送出。
 * <p>
 * 仅对包含 {@link IntegratedCPUBE} 的 CPU 簇生效，不影响原版 AE2 CPU。
 */
@Mixin(value = CraftingCpuLogic.class, remap = false, priority = 1200)
public abstract class CraftingCpuLogicMixin {

    // ── 时间片预算 ──

    /**
     * 预算 = clamp(45ms − 服务器MSPT, 1ms, 48ms)。
     * 2026-08-21 优化：上限从 32ms 提到 48ms——MSPT 低时空闲预算更多，
     * 1× 逐条推送（GTL 等批量失效场景）每 tick 能多跑 ~50% 迭代。
     * 自适应保护仍在：服务器卡顿时 headroom 归零，预算自动回落到 1ms。
     */
    @Unique
    private static final long AE2ADDON_DISPATCH_MAX_BUDGET_NANOS = 48_000_000L;
    @Unique
    private static final long AE2ADDON_DISPATCH_TARGET_TICK_NANOS = 45_000_000L;
    @Unique
    private static final long AE2ADDON_DISPATCH_MIN_BUDGET_NANOS = 1_000_000L;
    @Unique
    private static final long AE2ADDON_DISPATCH_FALLBACK_NANOS = 4_000_000L;

    // ── 批量推送 ──

    /**
     * 单次批量推送的绝对上限（防止反射失败等异常情况下失控）。
     * Long.MAX_VALUE：批量 N 可以一路涨到 long 级（2^63−1），
     * 单次 push 即可发放 long 级材料（受任务剩余量与库存钳制，
     * 输入×N 溢出由 ScaledPattern 的 multiplyExact 抛异常自动回退）。
     * config batchMaxMultiplier 可配（热加载）。
     */
    @Unique
    private long ae2addon$batchMaxMultiplier() {
        return com.ae2addon.crafting.CraftingCompat.batchMaxMultiplier;
    }

    /**
     * 本网格的**并行上限**（2026-09-17 sensei）：接入集成型CPU → 0（不限）；
     * 否则 = 网络内并行数总和（{@code QianJiBE.networkParallelSum}）。0 表示不限。
     */
    @Unique
    private long ae2addon$parallelCap() {
        try {
            var grid = cluster == null ? null : cluster.getGrid();
            if (grid == null) return 0;
            for (var cpu : grid.getMachines(com.ae2addon.block.IntegratedCPUBE.class)) {
                if (cpu != null && !cpu.isRemoved() && cpu.isFormed()) return 0;   // 在线 → 不限
            }
            return com.ae2addon.block.QianJiBE.networkParallelSum(grid);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 批量 N 的有效上限：config 上限 与 并行上限 取小（并行 0 = 不限 → 用 config） */
    @Unique
    private long ae2addon$effectiveBatchMax() {
        long config = ae2addon$batchMaxMultiplier();
        long cap = ae2addon$parallelCap();
        return cap <= 0 ? config : Math.min(config, cap);
    }

    /**
     * 批量 N 的**起步值**（2026-09-17 sensei：「智能翻倍到该并行能处理的最大量」）。
     * <p>
     * 原来任何新样板都从 1× 起步，逐轮翻倍（1→2→4…）——八千多的并行也要爬十几轮，看着就是「慢」。
     * 现在离线时**直接以网络并行数总和起步**（能一次吃下多少就先试多少；吃不下会被拒收、自动减半收敛）；
     * 接入集成型CPU（并行不限）时仍从 1× 起步，靠翻倍快速上涨。
     */
    @Unique
    private long ae2addon$seedBatchMultiplier() {
        long cap = ae2addon$parallelCap();
        return cap > 0 ? cap : 1L;
    }

    /**
     * 批量经验共享（2026-08-27）：按产物物品共享「同 pattern 已成功翻倍到的 N」。
     * 各 lane 独立 CPU、各自维护 batchNext，新 lane 从 1× 探测起步，翻倍到高 N
     * 前速度远落后老 lane（sensei 实测 20:05「每个线程发送速度不一致」）。
     * 新 lane 无本地经验时继承共享经验（clamp 到 65536 起步），随后本地翻倍
     * 继续增长——所有 lane 快速收敛到一致吞吐。key = 产物 AEKey。
     */
    @Unique
    private static final java.util.Map<appeng.api.stacks.AEKey, Long>
            ae2addon$sharedBatchExp = new java.util.concurrent.ConcurrentHashMap<>();

    /** 共享经验继承上限（config sharedExpCap 热加载，0=关闭共享）：防新 lane 直接继承天文 N 单次巨量 push */
    @Unique
    private long ae2addon$sharedExpCap() {
        return com.ae2addon.crafting.CraftingCompat.sharedExpCap;
    }

    /**
     * 诊断日志间隔（tick）。2026-08-21 从 100 拉大到 600（30 秒）——
     * CPU 功能基本完好，减少日志占用；每 5 分钟还有一次汇总看趋势。
     */
    @Unique
    private static final long AE2ADDON_DIAG_LOG_INTERVAL_TICKS = 600;

    @Shadow
    @Final
    private CraftingCPUCluster cluster;

    /**
     * 时间片限流是否已注入生效：由限流重定向方法置位
     * {@link com.ae2addon.crafting.CraftingCompat#timeSliceActive}（独立工具类，
     * mixin 不能暴露 public static 方法供外部调用——Mixin 规范，2026-08-21 教训：
     * 在 mixin 里写 public static 方法导致两个整合包全部 MixinApplyError）。
     */
    // job 字段反射（兼容不同 AE2 版本的字段名；@Shadow 字段名在部分版本不存在会直接崩）
    @Unique
    private static volatile java.lang.reflect.Field ae2addon$jobField;
    @Unique
    private static volatile boolean ae2addon$jobFieldFailed;

    /** 获取当前执行中的任务（反射按类型匹配，不依赖字段名；返回 Object 避免
     *  引用 ExecutingCraftingJob 类——GT 版 AE2 无此类，mixin 转换会 ClassMetadataNotFound）。 */
    @Unique
    private Object ae2addon$getJob() {
        if (ae2addon$jobFieldFailed) {
            return null;
        }
        try {
            java.lang.reflect.Field field = ae2addon$jobField;
            if (field == null) {
                field = ae2addon$findJobField(getClass());
                if (field == null) {
                    ae2addon$jobFieldFailed = true;
                    return null;
                }
                field.setAccessible(true);
                ae2addon$jobField = field;
            }
            return field.get(this);
        } catch (RuntimeException | ReflectiveOperationException e) {
            ae2addon$jobFieldFailed = true;
            return null;
        }
    }

    /** 在目标类中查找任务字段（名字候选 + 类型名匹配，不引用具体类）。 */
    @Unique
    private static java.lang.reflect.Field ae2addon$findJobField(Class<?> targetClass) {
        for (String name : new String[]{"job", "craftingJob", "currentJob", "m_job"}) {
            try {
                java.lang.reflect.Field field = targetClass.getDeclaredField(name);
                if (ae2addon$isJobType(field.getType())) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // 候选名不存在，继续
            }
        }
        // 类型匹配兜底：不依赖字段名
        for (java.lang.reflect.Field field : targetClass.getDeclaredFields()) {
            if (ae2addon$isJobType(field.getType())) {
                return field;
            }
        }
        return null;
    }

    /** 任务类型判断：按类名匹配（不加载类，兼容 GT 版 AE2 无此类/改名）。 */
    @Unique
    private static boolean ae2addon$isJobType(Class<?> type) {
        if (type == null) {
            return false;
        }
        String name = type.getName();
        return name.equals("appeng.crafting.execution.ExecutingCraftingJob")
                || name.endsWith("ExecutingCraftingJob");
    }

    // 时间片状态
    @Unique
    private long ae2addon$deadlineNanos;
    @Unique
    private long ae2addon$budgetNanos;
    @Unique
    private boolean ae2addon$budgetActive;

    // 诊断状态
    @Unique
    private boolean ae2addon$diagLoaded;
    @Unique
    private Object ae2addon$diagLastJob;
    @Unique
    private long ae2addon$diagLastTick = Long.MIN_VALUE;
    @Unique
    private long ae2addon$diagIterations;
    @Unique
    private long ae2addon$diagBatchAccepted;
    @Unique
    private long ae2addon$diagBatchRejected;
    @Unique
    private long ae2addon$diagBatchMultiplierSum;
    @Unique
    private long ae2addon$diagBatchCount;
    @Unique
    private long ae2addon$diagBatchExtractAttempts;
    @Unique
    private long ae2addon$diagBatchExtractFailures;
    @Unique
    private long ae2addon$diagTaskValueFallback;
    @Unique
    private boolean ae2addon$diagExtractLogged;
    @Unique
    private long ae2addon$diagPushCalls;

    /** 诊断日志节流计数器（extractBatch 详情/1x提取/pushBatch 共用，防刷屏掉刻） */
    @Unique
    private long ae2addon$diagExtractLogCount;
    @Unique
    private long ae2addon$diagProbeGrowth;
    @Unique
    private String ae2addon$diagBatchFailProvider = "-";

    // 批量状态（每个 pattern 的自适应 N）
    // ⚠ 2026-08-21 兼容性修复：原用 IdentityHashMap（对象身份比较），
    // gtlcore 等 mod 环境下 AE2 每次调用可能传入不同实例（equals 相等）→
    // 查表永远 miss → 批量 N 永远 1（sensei 日志：批量成功0次/平均批量N=1）。
    // 改 HashMap 按 equals 匹配（pattern 是值对象，equals 可靠）。
    @Unique
    private final Map<appeng.api.stacks.AEKey, Long> ae2addon$batchNext =
            new HashMap<>();
    /**
     * 「该样板暂时别翻倍」的**冷却锁**：值 = 锁定到期 tick（0/缺失 = 未锁）。
     * <p>
     * ⚠ 2026-09-18 sensei 反馈「>4 万下单量卡在正在合成、还伴随轻微卡顿」的根因之一：
     * 原来是**永久布尔锁**（只在新任务开始时清），一次偶发的批量提取失败
     * （库存暂时不足 / crafting storage 一时凑不齐）就把该样板锁死成 1 份/tick，
     * 之后**再也不恢复** —— 4 万的单 = 4 万 tick ≈ 半小时，看起来就是卡住。
     * 现在改成冷却：锁 T 秒后自动解锁重试，偶发失败不会毁掉整单。
     */
    @Unique
    private final Map<appeng.api.stacks.AEKey, Long> ae2addon$batchLocked =
            new HashMap<>();

    /** 冷却锁时长（tick）：100 tick = 5 秒 */
    @Unique
    private static final long AE2ADDON_BATCH_LOCK_TICKS = 100L;

    /** 该样板当前是否处于冷却锁（到期自动失效） */
    @Unique
    private boolean ae2addon$batchLockedFor(IPatternDetails pattern) {
        Long until = ae2addon$batchLocked.get(ae2addon$batchKey(pattern));
        if (until == null) {
            return false;
        }
        if (TickHandler.instance().getCurrentTick() >= until) {
            ae2addon$batchLocked.remove(ae2addon$batchKey(pattern)); // 冷却结束：允许重新爬坡
            return false;
        }
        return true;
    }

    /** 给样板加冷却锁 */
    @Unique
    private void ae2addon$lockBatchFor(IPatternDetails pattern) {
        ae2addon$batchLocked.put(ae2addon$batchKey(pattern),
                TickHandler.instance().getCurrentTick() + AE2ADDON_BATCH_LOCK_TICKS);
    }

    /**
     * 2026-09-17 sensei「A 方案」：本次提取/推送是不是**千机样板**。
     * <p>
     * 批量通道原来只对「我们的集成型CPU」开放（{@code budgetActive}），于是用别的 CPU
     * （如 omni cells 的量子CPU）时，千机样板也只能 1× 走，吃不到并行。
     * 现在放宽成：**我们的集成CPU 或 千机样板**都能进批量通道；
     * 时间片限流（limitTaskIteration / limitProviderIteration / 派发预算）仍然只对我们的集成CPU 开。
     */
    @Unique
    private boolean ae2addon$qianjiBatch;

    /** 本 tick 千机结算次数已用尽（提取阶段置位；见 ae2addon$virtualSettle） */
    @Unique
    private boolean ae2addon$settleLimitReached;

    /** 每个样板"上次看到的任务值"，用于每单只打一次 task开始 取证 */
    @Unique
    private final Map<IPatternDetails, Long> ae2addon$taskValueSeen = new HashMap<>();

    /** 上限诊断节流（每秒最多一条） */
    @Unique
    private long ae2addon$diagCapLogged = Long.MIN_VALUE;

    /**
     * 自己记账的"本单还应交付多少批"（**不依赖 AE2 反射**）。
     * <p>
     * ⚠ 2026-09-19：**账本 key 必须用"样板定义"（AEItemKey）而不是 pattern 对象** ——
     * 用对象做 key 时，AE2 每次计划/派发可能给出不同的 pattern 实例，
     * 于是 `delivered.get()` 拿不到旧账 → **每次都被当成首见、基准重置、自记剩余永远等于基准**，
     * 表现就是 sensei 看到的「自记剩余=0 却还在每批 128 份」。
     */
    @Unique
    private final Map<appeng.api.stacks.AEKey, Long> ae2addon$delivered = new HashMap<>();

    @Unique
    private final Map<appeng.api.stacks.AEKey, Long> ae2addon$deliveredBaseline = new HashMap<>();

    /** 取账本 key：优先用样板定义（稳定），取不到才退回 null（不记账） */
    @Unique
    private static appeng.api.stacks.AEKey ae2addon$accountKey(IPatternDetails pattern) {
        try {
            return pattern == null ? null : pattern.getDefinition();
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private long ae2addon$selfRemaining(IPatternDetails pattern, long taskValue) {
        var key = ae2addon$accountKey(pattern);
        if (key == null) {
            return taskValue;   // 无法记账：退回 AE2 的值（保持旧行为）
        }
        var base = ae2addon$deliveredBaseline.get(key);
        if (base == null) {
            // 首见：以 AE2 的任务值作为本单总量基准
            ae2addon$deliveredBaseline.put(key, taskValue);
            ae2addon$delivered.put(key, 0L);
            AE2Addon.LOGGER.info("[ae2addon][账本] 首见：基准={} key={}", taskValue, key);
            return taskValue;
        }
        long delivered = ae2addon$delivered.getOrDefault(key, 0L);
        long remaining = base - delivered;
        return remaining > 0 ? remaining : 0L;
    }

    /**
     * 账本剩余（**供结算侧使用**，与提取侧共用同一套换单自愈逻辑）。
     * <p>
     * 关键：账本按样板定义做 key，"上一单交付到 0"会被下一单沿用 ⇒ 新单被误判为"已交付足够"而卡住。
     * 判据：自记剩余 ≤ 0 **但 AE2 的任务值仍有货** ⇒ 旧账，重置后重新记账。
     */
    @Unique
    private long ae2addon$ledgerRemaining(IPatternDetails pattern) {
        long taskValue = ae2addon$getTaskValue(pattern);
        long left = ae2addon$selfRemaining(pattern, taskValue);
        if (left <= 0 && taskValue > 1) {
            AE2Addon.LOGGER.info(
                    "[ae2addon][账本] 换单自愈（旧账剩余=0，AE2 任务值={}）→ 重置账本", taskValue);
            ae2addon$resetDelivered();
            left = ae2addon$selfRemaining(pattern, taskValue);
        }
        return left;
    }

    /** 交付了一批（由结算侧调用）：累计已交付量 */    @Unique
    private void ae2addon$noteDelivered(IPatternDetails pattern, long batches) {
        var key = ae2addon$accountKey(pattern);
        if (key == null || batches <= 0) return;
        long now = ae2addon$delivered.merge(key, batches, Long::sum);
        if (CraftingCompat.debugLogs && ae2addon$logHot()) {
            long base = ae2addon$deliveredBaseline.getOrDefault(key, 0L);
            AE2Addon.LOGGER.info("[ae2addon][账本] 已交付 {}（累计 {}/基准 {}）剩余 {}", batches, now, base,
                    Math.max(0L, base - now));
        }
    }

    /** 任务结束/换单：清空自己记的账 */
    @Unique
    private void ae2addon$resetDelivered() {
        if (!ae2addon$delivered.isEmpty()) {
            AE2Addon.LOGGER.info("[ae2addon][账本] 清账（共 {} 个样板）", ae2addon$delivered.size());
        }
        ae2addon$delivered.clear();
        ae2addon$deliveredBaseline.clear();
    }

    /**
     * 任务身份变化检测（**取消订单 / 换单都会触发**）。
     * <p>
     * ⚠ 2026-09-19（sensei：订单没合完就取消，账本不会重置）：
     * 原来只在"job 为 null"时清账，而**取消订单时 job 会换成一个新对象**（不是 null），
     * 于是旧账本被新单沿用。这里在每次提取时对比任务对象的 **identity**，
     * 一旦变了就立刻清账 —— 无论上一单是完成、取消还是被替换。
     */
    @Unique
    private Object ae2addon$lastJobIdentity;

    @Unique
    private void ae2addon$checkJobSwitch() {
        var job = ae2addon$getJob();
        if (job != ae2addon$lastJobIdentity) {
            AE2Addon.LOGGER.info(
                    "[ae2addon][账本] 任务身份变化（{} → {}）→ 重置账本",
                    ae2addon$lastJobIdentity == null ? "无" : "旧任务",
                    job == null ? "无" : "新任务");
            // ⚠ 2026-09-19：若此刻还有待回收产物，说明它没能赶在任务消失前结清
            // （每 tick TAIL 的 flushAtTickEnd 就是为杜绝这种情况加的）。
            // 这里**只报警不清理**：清理等于静默丢产物，而留着还能靠日志追。
            var leftover = ae2addon$pendingSettle;
            if (leftover != null && !leftover.isEmpty()) {
                AE2Addon.LOGGER.warn(
                        "[ae2addon][settle] 任务切换时仍有待回收产物 {} 项（{}）"
                                + "—— 这批会记到新任务的账上、非根产物可能被丢弃，请查 flushAtTickEnd",
                        leftover.size(), ae2addon$describeCounter(leftover));
            }
            ae2addon$lastJobIdentity = job;
            ae2addon$resetDelivered();
        }
    }

    /**
     * 本次是「次数用尽后强制只提 1 份」的那一批。
     * <p>
     * 这一批的材料**已经被提取并消耗**，所以结算侧必须放行这 1 份（且不占次数预算），
     * 否则材料凭空消失、任务永远等待 → 卡死。
     */
    @Unique
    private boolean ae2addon$settleLimitForced;

    /**
     * 本批**实际提取的份数**（1× 提取路径与批量路径都会写）。
     * <p>
     * ⚠ 2026-09-18：结算侧必须用这个值，不能用 batchMultiplier ——
     * 后者是"想提取多少"，被限流夹过之后两者会不一致，导致
     * 「提取了却等不到产物」或「结算超出真实材料」两种卡死。
     */
    @Unique
    private long ae2addon$settleBatchN;

    /** 热路径日志闸门（每 tick 行数预算；见 CraftingCompat.allowHotLog） */
    @Unique
    private static boolean ae2addon$logHot() {
        return com.ae2addon.crafting.CraftingCompat.allowHotLog();
    }

    /**
     * tick 节流闸门（**哨兵安全**）。
     * <p>
     * ⚠ 2026-09-19（踩坑）：节流字段的哨兵值如果是 {@code Long.MIN_VALUE}，
     * 就**不能**写成 `tick - last < N`：

     * {@code tick - Long.MIN_VALUE} 会整数溢出成一个大负数，恒 `< N` →
     * 判断永远为真 → 每次都 return，而 last 只在放行后才更新 ⇒ **日志永远不会出现**。
     * 结果：`[stuck]` 卡住诊断、`[cost]` 回收开销汇总从上线起一次都没打印过，
     * 排查时看起来就像"钩子没注入"（实际是闸门把自己焊死了）。
     *
     * @param last     上次记录时的 tick（未记录过传哨兵值）
     * @param tick     当前 tick
     * @param interval 最小间隔（tick）
     */
    @Unique
    private static boolean ae2addon$tickGate(long last, long tick, long interval) {
        return last == Long.MIN_VALUE || tick - last >= interval;
    }

    /** 同 tick 缓存对应的批量倍数（倍数涨了就作废缓存，否则翻倍会被缓存吃掉） */
    @Unique
    private long ae2addon$batchCachedMultiplier;

    /**
     * 虚拟结算"已学到的批量规模"（样板定义 key → n）。
     * <p>
     * ⚠ 2026-09-19：这是**虚拟结算自己的**批量爬坡状态，与批量通道的 `batchNext` **完全解耦**。
     * 每成功结算一批就 ×2（受每 tick 份数上限约束），于是巨型订单会迅速逼近上限，
     * 而不是被推送通道的收敛逻辑来回拉扯。
     */
    @Unique
    private final Map<appeng.api.stacks.AEKey, Long> ae2addon$settleScale = new HashMap<>();

    /** 本批虚拟结算刚提取时保存的 expected 快照（供 provider 环节补 waitingFor 记账） */
    @Unique
    private KeyCounter ae2addon$lastSettleExpected;

    /** 批量账本的内容键（= 样板定义）——**绝不能用 pattern 对象做 key**（AE2 会换实例） */    @Unique
    private static appeng.api.stacks.AEKey ae2addon$batchKey(IPatternDetails pattern) {
        if (pattern == null) return null;
        try {
            return pattern.getDefinition();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 判定一条样板是不是千机的（自有样板详情，或样板物品上带我们的 NBT） */
    @Unique
    private static boolean ae2addon$isQianJiPattern(IPatternDetails pattern) {
        if (pattern == null) return false;
        if (pattern instanceof com.ae2addon.crafting.QianJiPatternDetails) return true;
        try {
            var def = pattern.getDefinition();
            if (def == null) return false;
            return com.ae2addon.recipe.QianJiPatternData.of(def.toStack(1)) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 千机虚拟结算的每 tick 份数上限。
     * <p>
     * - 无集成型CPU → {@code round(网络并行数 / 20)}（8192 → 410）；至少 1 份
     * - 有集成型CPU → config {@code qianjiSettleCap}（默认 4096）
     * <p>
     * ⚠ 2026-09-18 sensei 反馈「下大单严重卡顿、集成型CPU 在线时更明显」：
     * 原口径是「有集成型CPU 就不限」，于是一个 tick 要逐份掷骰结算整个任务量
     * （往往几十万~上千万份）→ 单 tick 卡死。现在**任何情况下都有上限**，
     * 靠分片把工作量摊到多个 tick。逐份掷骰是概率正确性要求，不能改成期望值取巧。
     */
    @Unique
    private long ae2addon$qianjiSettleCapPerTick() {
        if (ae2addon$budgetActive && ae2addon$isIntegratedCpu(cluster)) {
            return Math.max(1L, com.ae2addon.crafting.CraftingCompat.qianjiSettleCap);
        }
        var grid = cluster == null ? null : cluster.getGrid();
        long parallel = grid == null ? 0L : com.ae2addon.block.QianJiBE.networkParallelSum(grid);
        if (parallel <= 0) {
            // ⚠ 2026-09-18 晚（sensei：无集成CPU 时合成太快）：原来这里返回 config 上限
            // （默认 4096）—— 等于"算不出并行数就不限速"，节流会**静默失效**。
            // 改成保守小值（并行未知 = 按最小并行 1 处理），并打一次诊断便于定位。
            if (CraftingCompat.debugLogs && ae2addon$diagCapFallbackTick != ae2addon$flushAllowedTick) {
                ae2addon$diagCapFallbackTick = ae2addon$flushAllowedTick;
                AE2Addon.LOGGER.info(
                        "[ae2addon][settle] 节流取不到并行数（grid={}）→ 用保守值 {} 份/tick",
                        grid == null ? "null" : "ok", AE2ADDON_CAP_UNKNOWN_PARALLEL);
            }
            return AE2ADDON_CAP_UNKNOWN_PARALLEL;
        }
        // 并行数/20，除不尽四舍五入
        return Math.max(1L, (parallel + 10L) / 20L);
    }

    /** 取不到网络并行数时的保守每 tick 份数（并行按最小 1 折算：round(1/20) → 下限 1） */
    @Unique
    private static final long AE2ADDON_CAP_UNKNOWN_PARALLEL = 1L;

    /**
     * **虚拟结算单批**的份数上限（2026-09-19 sensei：「账本每批只能交付 4096」）。
     * <p>
     * 集成型 CPU 下**不再按份数夹批**，返回 {@link Long#MAX_VALUE}。
     * 理由：{@link #ae2addon$qianjiSettleCapPerTick()} 那个上限的注释写的是
     * "防止一 tick 逐份掷骰结算上千万份把单 tick 卡死"——但我们同日就换成了
     * **O(1) 整体抽样**（保留分布、不做逐份掷骰），这条理由已经不成立。
     * 它现在只剩副作用：
     * <ul>
     *   <li>把「翻倍」学到的大 N 一刀砍回 4096（日志实证：`multiplier=4096 → batchNext=8192`
     *       但 `本批 4096 份`）→ 1562.5 万份的单被切成 3800+ 批、6.7 万次结算、20 万行日志；</li>
     *   <li>与 sensei 早先定的口径冲突：**N 要涨到上限，不要夹**。</li>
     * </ul>
     * 真正防卡顿的是「每 tick 结算**调用次数**预算」({@code qianjiSettleCallsPerTick},
     * 默认 512) 与时间片预算：单次结算的成本只跟**物品种类数**有关，跟份数无关。
     * <p>
     * ⚠ 非集成型 CPU 仍沿用 {@code round(并行/20)}（sensei 明确要求过的节流口径）。
     * ⚠ 提取侧（{@code clampQianjiBatch}）**不受本方法影响**，仍走原上限 ——
     * 那是为"真实 provider 推送"设计的，不能顺手放开。
     */
    @Unique
    private long ae2addon$qianjiSettleBatchCeiling() {
        if (ae2addon$budgetActive && ae2addon$isIntegratedCpu(cluster)) {
            return Long.MAX_VALUE;
        }
        return ae2addon$qianjiSettleCapPerTick();
    }

    @Unique
    private long ae2addon$diagCapFallbackTick = Long.MIN_VALUE;

    /** 千机样板在提取阶段先把 N 夹到每 tick 上限：保证「提取多少 = 结算多少」（同 tick 原子） */
    @Unique
    private long ae2addon$clampQianjiBatch(long n, IPatternDetails pattern) {
        if (n <= 1 || !ae2addon$isQianJiPattern(pattern)) return n;
        long cap = ae2addon$qianjiSettleCapPerTick();
        return n > cap ? cap : n;
    }

    // 当前批量上下文（一次提取 → 一次 push 之间传递）
    @Unique
    private boolean ae2addon$batchActive;
    @Unique
    private IPatternDetails ae2addon$batchBasePattern;
    @Unique
    private ScaledPattern ae2addon$batchScaledPattern;
    @Unique
    private long ae2addon$batchMultiplier;

    // 卡死退避（2026-08-22）：记录「本 tick 内被拒收的 pattern」，
    // 同一 tick 不再重试（机器拒收时每 tick 最多试 1 次，避免烧光时间片预算
    // 空转——sensei 实测：合成机器缓冲满后 CPU 每 tick 空转 49 次）。
    @Unique
    private IPatternDetails ae2addon$lastStuckPattern;
    @Unique
    private long ae2addon$lastStuckTick = Long.MIN_VALUE;

    // 批量提取结果缓存：AE2 同一 tick 会对同一任务调用两次 extractPatternInputs，
    // 第二次直接返回缓存，避免重复提取/重复扣库存，也避免第二次调用开头
    // clearBatchContext 清掉批量上下文导致 pending 收敛锁死。
    @Unique
    private KeyCounter[] ae2addon$batchCachedInputs;

    /**
     * 缓存命中时必须补回的 expectedOutputs / expectedContainerItems 快照。
     * 原版 pushPattern 成功后会用这两个 KeyCounter 向 job.waitingFor 记账，
     * 若缓存路径直接返回输入而不补回它们，waitingFor 会漏记 N× 输出量，
     * 导致 CPU 界面显示的实际发送量与真实发送量不符（修复 2026-08-17）。
     */
    @Unique
    private KeyCounter ae2addon$batchCachedOutputs;
    @Unique
    private KeyCounter ae2addon$batchCachedContainerItems;

    /** 上次批量提取的 tick + pattern（用于识别同 tick 重复提取） */
    @Unique
    private long ae2addon$lastExtractTick = Long.MIN_VALUE;
    @Unique
    private IPatternDetails ae2addon$lastExtractPattern;

    /**
     * 待确认的批量倍数：>0 表示上一次批量提取成功但没有任何 provider 接受
     * （下次提取时据此减半/锁定，防止反复空转）。
     */
    @Unique
    private long ae2addon$batchPendingMultiplier = -1;

    // 任务值反射
    @Unique
    private static volatile Field ae2addon$tasksField;
    @Unique
    private static volatile Field ae2addon$taskValueField;
    @Unique
    private static volatile boolean ae2addon$reflectionAvailable = true;
    @Unique
    private static volatile boolean ae2addon$reflectionFailureLogged;

    // ── 时间片：每 tick 预算 ──

    /**
     * 每 tick 收工前把待回收产物结清（2026-09-19 sensei 实测 bug 的修复）。
     * <p>
     * ⚠ 为什么不能只靠任务循环的 {@code hasNext} 钩子回收（原来就是这么干的）：
     * 千机虚拟结算会把任务**做到 0 并移除**，任务表一空，AE2 就不再调用
     * {@code executeCrafting} ⇒ 我们的钩子**再也不会被调用** ⇒ 这一批的待回收产物
     * 永远等不到回收：
     * <ul>
     *   <li>产物卡在 {@code pendingSettle} 里不交付，waitingFor 的账也还不掉 → 任务挂住；</li>
     *   <li>等玩家下**下一单**时，新任务的循环才把它回收 —— 而那时 {@code job.waitingFor}
     *       已经是新任务的空表 ⇒ {@code insert} 认不到账（实测日志"期望1 实收0"），
     *       非根产物（副产物/精华）被丢弃；</li>
     *   <li>更糟：root 走的是**物理入网**，不看 waitingFor ⇒ 照样进网
     *       ⇒ **新订单凭空多出上一单的产物**。</li>
     * </ul>
     * 放在 {@code tickCraftingLogic} 的 TAIL（每 tick 每实例恰好一次，任务空也照样调用），
     * 且在 {@code executeCrafting} 之外 ⇒ 这里 finishJob 把 job 置 null 也不会让
     * AE2 循环里的后续解引用炸掉（那是"结算内立刻回收"不能做的原因）。
     */
    @Inject(method = "tickCraftingLogic", at = @At("TAIL"), require = 0)
    private void ae2addon$flushAtTickEnd(IEnergyService energyService,
            CraftingService craftingService, CallbackInfo callback) {
        if (ae2addon$pendingSettle == null || ae2addon$pendingSettle.isEmpty()) {
            return;
        }
        ae2addon$flushPendingSettle();
    }

    @Inject(method = "tickCraftingLogic", at = @At("HEAD"), require = 0)
    private void ae2addon$beginDispatchBudget(IEnergyService energyService,
            CraftingService craftingService, CallbackInfo callback) {
        boolean integrated = ae2addon$isIntegratedCpu(cluster);
        if (!ae2addon$diagLoaded) {
            ae2addon$diagLoaded = true;
            AE2Addon.LOGGER.info("[ae2addon] CraftingCpuLogicMixin 已生效，集成CPU检测={}", integrated);
        }
        // ⚠️ lane 维护不在本钩子做（2026-08-22 15:26 崩溃教训）：tickCraftingLogic
        // 在 onServerEndTick 的 craftingCPUClusters 迭代中调用，此时 add/remove
        // 集合会 ConcurrentModificationException。已移到 CraftingServiceMixin 的
        // onServerEndTick HEAD（迭代前，安全）。
        ae2addon$budgetActive = integrated;
        // ⚠ 2026-09-19（sensei：急停按钮）：打开后**立即停工** ——
        // 把 budgetActive 压成 false，批量通道 / 虚拟结算 / 派发预算全部不再工作，
        // 但不动玩家已下的订单与已提取的材料（只是不再往网络里推/结算）。
        if (com.ae2addon.crafting.CraftingCompat.cpuHalted) {
            ae2addon$budgetActive = false;
        }
        if (ae2addon$budgetActive) {
            Object currentJob = ae2addon$getJob();
            if (ae2addon$diagLastJob != currentJob) {
                // 新任务：重置批量自适应状态 + 清除无限接口的推送归属记录
                // （材料保留在接口=正常交付；归属只服务于当前任务的取消回退）
                ae2addon$diagLastJob = currentJob;
                // ⚠ 2026-09-19（sensei：希望 9T 的单**直接**用 3.4e10 的 N，而不是从 2 爬）：
                // 原来无条件 `batchNext.clear()` → **每单都从 1 重新爬坡**，
                // 累积经验（sharedBatchExp）虽然记着，却因为 batchNext 被清空而不会被继承。
                // 现在：清空**之前先把每个样板已爬到的 N 留作新任务的起步值**——
                // 首个被读到时直接用这个种子（见 ae2addon$getBatchMultiplier 的种子日志）。
                ae2addon$carryOverBatchNext();
                ae2addon$batchLocked.clear();
                if (currentJob != null) {
                    com.ae2addon.block.InfiniteInterfaceBE.resetPushedFor(cluster);
                    // 千机同样有「已推送未合成」的账：新任务开始前退回，不丢料（2026-09-15）
                    com.ae2addon.block.QianJiBE.resetPushedFor(cluster);
                }
            }
            ae2addon$budgetNanos = ae2addon$getAdaptiveBudgetNanos();
            ae2addon$deadlineNanos = System.nanoTime() + ae2addon$budgetNanos;
            long tick = TickHandler.instance().getCurrentTick();
            if (ae2addon$diagLastTick == Long.MIN_VALUE) {
                ae2addon$diagLastTick = tick;
            } else if (tick - ae2addon$diagLastTick >= AE2ADDON_DIAG_LOG_INTERVAL_TICKS) {
                long span = tick - ae2addon$diagLastTick;
                // 2026-09-22 v285：这类周期性诊断收进 debugLogs 开关（默认关，排查时打开即可）
                if (com.ae2addon.config.AE2AddonConfig.debugLogs()) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon] CPU调度诊断: 最近{}tick平均迭代{}次/tick，预算{}ms，批量成功{}次/失败{}次，平均批量N={}，提取尝试{}次/失败{}次，任务值回退{}次，push总调用{}次，1×翻倍{}次，批量失败provider={}",
                            span,
                            ae2addon$diagIterations / Math.max(1, span),
                            ae2addon$budgetNanos / 1_000_000L,
                            ae2addon$diagBatchAccepted,
                            ae2addon$diagBatchRejected,
                            ae2addon$diagBatchCount == 0 ? 1
                                    : ae2addon$diagBatchMultiplierSum / ae2addon$diagBatchCount,
                            ae2addon$diagBatchExtractAttempts,
                            ae2addon$diagBatchExtractFailures,
                            ae2addon$diagTaskValueFallback,
                            ae2addon$diagPushCalls,
                            ae2addon$diagProbeGrowth,
                            ae2addon$diagBatchFailProvider);
                }
                ae2addon$diagLastTick = tick;
                ae2addon$diagIterations = 0;
                ae2addon$diagBatchAccepted = 0;
                ae2addon$diagBatchRejected = 0;
                ae2addon$diagBatchMultiplierSum = 0;
                ae2addon$diagBatchCount = 0;
                ae2addon$diagBatchExtractAttempts = 0;
                ae2addon$diagBatchExtractFailures = 0;
                ae2addon$diagTaskValueFallback = 0;
                ae2addon$diagPushCalls = 0;
                ae2addon$diagProbeGrowth = 0;
            }
        }
    }

    @Unique
    private long ae2addon$getAdaptiveBudgetNanos() {
        try {
            if (cluster.getLevel() instanceof ServerLevel serverLevel) {
                float averageTickMillis = serverLevel.getServer().getAverageTickTime();
                if (Float.isFinite(averageTickMillis) && averageTickMillis > 0.0F) {
                    long averageTickNanos = (long) (averageTickMillis * 1_000_000.0F);
                    // 时间片目标可配（config cpuTimeSliceTargetMs，热加载）：巨型订单提速旋钮
                    long targetNanos = com.ae2addon.crafting.CraftingCompat.cpuTimeSliceTargetMs > 0
                            ? com.ae2addon.crafting.CraftingCompat.cpuTimeSliceTargetMs * 1_000_000L
                            : AE2ADDON_DISPATCH_TARGET_TICK_NANOS;
                    long headroom = Math.max(
                            0L, targetNanos - averageTickNanos);
                    // 上限跟随可配目标（原来硬夹 48ms —— 巨型订单提速时目标调大也被削回来）
                    long maxBudget = Math.max(AE2ADDON_DISPATCH_MAX_BUDGET_NANOS, targetNanos);
                    return Math.min(maxBudget,
                            Math.max(AE2ADDON_DISPATCH_MIN_BUDGET_NANOS, headroom));
                }
            }
        } catch (RuntimeException ignored) {
            // 拿不到服务器信息时走保守默认
        }
        return AE2ADDON_DISPATCH_FALLBACK_NANOS;
    }

    /** 时间片预算检查节流：每 N 次迭代才查一次时钟（nanoTime 调用有 ~25-40ns 开销） */
    @Unique
    private static final int AE2ADDON_BUDGET_CHECK_INTERVAL = 32;

    /** 当前节流计数（每次迭代 ++，到 32 才查 deadline） */
    @Unique
    private int ae2addon$budgetCheckCounter;

    /** 超时检查：节流版（每 32 次迭代查一次时钟，省掉绝大多数 nanoTime 开销） */
    @Unique
    private boolean ae2addon$budgetExceeded() {
        if (++ae2addon$budgetCheckCounter < AE2ADDON_BUDGET_CHECK_INTERVAL) {
            return false;
        }
        ae2addon$budgetCheckCounter = 0;
        return System.nanoTime() >= ae2addon$deadlineNanos;
    }

    /**
     * 任务循环（job.tasks.entrySet() 的迭代）：超时即终止整个任务的遍历。
     */
    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z", ordinal = 0),
            require = 0)
    private boolean ae2addon$limitTaskIteration(Iterator<?> iterator) {
        com.ae2addon.crafting.CraftingCompat.timeSliceActive = true;
        // 虚拟结算产物回收：settle 返回 true 后外层已完成 waitingFor 记账，此刻回收
        // （insert 需 waitingFor 有记账才冲抵；根产物 finishJob 置 job=null → 下面终止迭代）
        //
        // ⚠ 2026-09-18 晚（卡顿）：**每个 tick 最多回收一次**。
        // 原来每次迭代都回收，而任务循环一 tick 能跑几十次 → 一 tick 几十次
        // 「账务 insert + 网络物理入网」，每次还带巨量物品（实测单次 131072）→ 卡顿。
        // pendingSettle 不会丢（下次迭代/下个 tick 照样回收），只是把频率压到"每 tick 一次"。
        if (ae2addon$pendingSettle != null && !ae2addon$pendingSettle.isEmpty()
                && ae2addon$flushPendingSettleOncePerTick()) {
            ae2addon$flushPendingSettle();
        }
        // 虚拟结算根产物 finishJob 后 job 已置 null：终止任务迭代防 NPE
        if (ae2addon$settleStopIteration) {
            ae2addon$settleStopIteration = false;
            return false;
        }
        // ── 卡住诊断（2026-09-18，sensei 反馈「>512 的单只合成一部分就卡住」）──
        // 每秒最多一条：把卡住那一刻的内部数字摊开，别再靠猜。
        ae2addon$diagnoseStuckTask();
        ae2addon$reportFlushCost();
        if (ae2addon$budgetActive) {
            ae2addon$diagIterations++;
            if (ae2addon$budgetExceeded()) {
                return false;
            }
        }
        // 本 tick 千机结算次数用尽 → 直接收工，别在同 tick 里再跑上百轮「结算 + 网络 insert」。
        // 剩下的任务下个 tick 继续（材料没被提前提取，不会悬空）。
        if (ae2addon$settleLimitReached) {
            return false;
        }
        return iterator.hasNext();
    }

    /**
     * provider 循环（craftingService.getProviders(details) 的迭代）：
     * 超时即停止向机器推送，本 tick 收工。
     */
    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z", ordinal = 1),
            require = 0)
    private boolean ae2addon$limitProviderIteration(Iterator<?> iterator) {
        com.ae2addon.crafting.CraftingCompat.timeSliceActive = true;
        // 虚拟结算根产物 finishJob 后 job 已置 null：终止 provider 迭代防 NPE
        if (ae2addon$settleStopIteration) {
            ae2addon$settleStopIteration = false;
            return false;
        }
        if (ae2addon$budgetActive) {
            ae2addon$diagIterations++;
            if (ae2addon$budgetExceeded()) {
                return false;
            }
        }
        return iterator.hasNext();
    }


    // ── 批量推送：提取阶段 ──

    /**
     * 提取阶段：若当前 pattern 有批量 N（&gt;1），用 ScaledPattern 提取 N× 输入。
     * 提取失败（库存不足等）→ 批量 N 减半回退，并降级为 1× 提取。
     */
    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs("
                            + "Lappeng/api/crafting/IPatternDetails;"
                            + "Lappeng/crafting/inv/ICraftingInventory;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lappeng/api/stacks/KeyCounter;"
                            + "Lappeng/api/stacks/KeyCounter;"
                            + ")[Lappeng/api/stacks/KeyCounter;"),
            require = 0)
    private KeyCounter[] ae2addon$extractBatch(IPatternDetails patternDetails,
            ICraftingInventory inventory, Level level, KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems) {
        // 登记本 CPU 逻辑对象，供 TaskValueProbe 的 server tick 钩子每秒取证
        // （放在这里是因为本方法**确实会被调用**——日志里能看到它，而 hasNext 重定向没有生效）
        try {
            com.ae2addon.debug.TaskValueProbe.track(this);
        } catch (Throwable ignored) {
        }
        // ⚠ 2026-09-19（sensei：急停按下后仍会推出物品）—— **急停必须是硬门禁**。
        // 原来急停只把 `budgetActive` 压成 false，但千机样板会落进
        // `qianjiBatch = !budgetActive && isQianJiPattern` 这条分支
        // （第 980 行，本来是为"用别的 CPU 跑千机样板"开的 A 方案）→ **等于把急停绕过去了**，
        // 于是急停后照样提取、结算、把产物注入网络。
        // 这里直接返回 null：AE2 会跳过 provider 循环（见本方法上方字节码注释），
        // 本 tick 不提取、不结算、不推物品（材料与订单都保持原样，恢复后继续）。
        if (com.ae2addon.crafting.CraftingCompat.cpuHalted) {
            return null;
        }
        // ── 同 tick 同 pattern 重复提取：直接返回缓存，幂等 ──
        // AE2 的 executeCrafting 同一 tick 会对同一任务调用两次 extractPatternInputs。
        // 第二次若走完整逻辑，开头 clearBatchContext 会清掉第一次的批量上下文，
        // 且 pending 收敛会把 batchNext 锁死为 1（历史教训：批量翻倍被吃）。
        long currentTick = TickHandler.instance().getCurrentTick();
        // 卡死退避：本 tick 已尝试推送该 pattern 且被拒（任务值 ≤1 时最典型）→
        // 直接返回 null 跳过（迭代变廉价，不烧预算）；下个 tick 再试一次。
        if (ae2addon$lastStuckTick == currentTick
                && ae2addon$lastStuckPattern != null
                && ae2addon$lastStuckPattern.equals(patternDetails)
                && ae2addon$getTaskValue(patternDetails) <= 1) {
            return null;
        }
        boolean sameTickSamePattern = ae2addon$lastExtractTick == currentTick
                && ae2addon$lastExtractPattern != null
                && ae2addon$lastExtractPattern.equals(patternDetails);
        // ⚠ 2026-09-19（sensei：N 卡在同一值不翻倍——实测同一 tick 内推送多次）：
        // 同 tick 缓存命中的是"用旧 batchNext 提取的那批"。若此刻 batchNext 已经被
        // `onBatchAccepted` 翻倍，**继续用缓存就等于把翻倍吃掉**（N 永远不动）。
        // 判据：缓存的倍数 < 当前 batchNext ⇒ 作废缓存，按新倍数重新提取。
        if (sameTickSamePattern && ae2addon$batchCachedInputs != null
                && ae2addon$getBatchMultiplier(patternDetails) > ae2addon$batchCachedMultiplier) {
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.info(
                        "[ae2addon][翻倍] 倍数已涨（缓存 {} → 当前 {}）→ 作废同 tick 缓存，重新提取",
                        ae2addon$batchCachedMultiplier,
                        ae2addon$getBatchMultiplier(patternDetails));
            }
            sameTickSamePattern = false;   // 走完整路径：重算 n、重新提取
        }
        if (sameTickSamePattern && ae2addon$batchCachedInputs != null) {
            // 修复：补回 expected 快照，否则原版 push 成功后的 waitingFor 记账为空
            if (ae2addon$batchCachedOutputs != null) {
                for (var entry : ae2addon$batchCachedOutputs) {
                    expectedOutputs.add(entry.getKey(), entry.getLongValue());
                }
            }
            if (ae2addon$batchCachedContainerItems != null) {
                for (var entry : ae2addon$batchCachedContainerItems) {
                    expectedContainerItems.add(entry.getKey(), entry.getLongValue());
                }
            }
            return ae2addon$batchCachedInputs;
        }
        // ⚠ 2026-09-19（sensei：巨型订单还跑不了 / 需解耦虚拟结算的批量）：
        // **千机样板走虚拟结算时，不再经过批量通道的提取**。
        // 原因：虚拟结算的份数一直被批量通道的 `batchMultiplier` 绑着，
        // 于是"退役批量通道"和"保住性能"互相打架；而且提取与结算是两步、可能错配。
        // 现在把「定批 + 提取」整体交给结算路径（见 ae2addon$virtualSettle）。
        //
        // ⚠ 2026-09-19（sensei：「卡计划合成」）—— 这里**绝不能返回 null**！
        //
        // 字节码取证（AE2 15.4.10 `CraftingCpuLogic.executeCrafting`，javap 偏移）：
        //   125: extractPatternInputs(...) → astore 12
        //   130: getProviders(details).iterator()        ← provider 循环紧跟在提取之后
        //   165: aload 12 / 167: ifnonnull 173 / 170: goto 493
        //   493: aload 12 / 495: ifnull 507 / 504: reinjectPatternInputs(inventory, inputs) / 507: goto 33
        // → 返回 null 时 AE2 **跳过整个 provider 循环**（直接去 493 收尾再下一个任务），
        //   于是 pushBatch / virtualSettle **永远不会被调用** ⇒ 千机订单每 tick 空转、
        //   进度永远 0、看着就是"卡住"（v171 把这里改成 return null 时，
        //   注释写的"让 provider 环节去调虚拟结算"这个前提是错的，且当时没实机验证）。
        //
        // 现在返回**非 null 的空数组**：它只是"继续往下走"的通行证。        // 空数组在 AE2 侧的用途全部无害（已逐条核对）：
        //   ① `calculatePatternPower(空)` = 0 → 走 0 功耗分支；
        //   ② 只作为参数传给 `pushPattern` —— 而那次调用被我们的 pushBatch 接管，
        //      真正的「定批 + 提取 + 结算」由虚拟结算自己做（见 ae2addon$virtualSettle）；
        //   ③ 收尾处 `reinjectPatternInputs(inventory, 空)` 是空操作（不会重复退料）。
        if (ae2addon$isQianJiPattern(patternDetails) && ae2addon$virtualSettleActive(patternDetails)) {
            ae2addon$clearBatchContext();
            // 每次一个空数组（每 tick 至多几次，开销可忽略；不进 static 字段是为了少一处 mixin 静态初始化）
            return new KeyCounter[0];
        }
        if (!sameTickSamePattern) {
            ae2addon$lastExtractTick = currentTick;
            ae2addon$lastExtractPattern = patternDetails;
            ae2addon$clearBatchContext();
            // 上次批量尝试没有任何 provider 接受 → 收敛批量 N（仅新 tick 首次提取时）
            if (ae2addon$batchPendingMultiplier > 1) {
                long previous = ae2addon$batchPendingMultiplier;
                ae2addon$batchPendingMultiplier = -1;
                if (previous <= 2) {
                    ae2addon$lockBatchFor(patternDetails);
                    ae2addon$batchNext.put(ae2addon$batchKey(patternDetails), 1L);
                } else {
                    ae2addon$batchNext.put(ae2addon$batchKey(patternDetails), Math.max(1L, previous / 2));
                }
            }
        }
        if (!ae2addon$diagExtractLogged) {
            ae2addon$diagExtractLogged = true;
            AE2Addon.LOGGER.info("[ae2addon] extractBatch 被调用！pattern={}, budgetActive={}",
                    patternDetails == null ? "null" : patternDetails.getClass().getSimpleName(),
                    ae2addon$budgetActive);
        }
        // ⚠ 2026-09-18 晚：**本单第一次提取时的任务值**（这条挂在已证明会执行的路径上）。
        // sensei 下单 40000（1粉→1锭）却产出 4195328，需要判定任务值本身是多少。
        // 每单只打一次：靠"上次记录的任务值"与"当前值"判断是否换了新单。
        if (patternDetails != null) {
            long seen = ae2addon$getTaskValue(patternDetails);
            var last = ae2addon$taskValueSeen.get(patternDetails);
            if (last == null || last != seen) {
                ae2addon$taskValueSeen.put(patternDetails, seen);
                var outs = patternDetails.getOutputs();
                var sb = new StringBuilder("[ae2addon][task开始] 任务值=").append(seen)
                        .append(" 样板=").append(patternDetails.getClass().getSimpleName())
                        .append(" 声明产出=[");
                if (outs != null) {
                    for (int i = 0; i < outs.length && i < 3; i++) {
                        if (outs[i] == null || outs[i].what() == null) continue;
                        if (i > 0) sb.append(' ');
                        sb.append(outs[i].what().getDisplayName().getString())
                                .append('×').append(outs[i].amount());
                    }
                }
                sb.append("] batchNext=").append(ae2addon$getBatchMultiplier(patternDetails));
                AE2Addon.LOGGER.info("{}", sb);
            }
        }
        // ⚠ 2026-09-18 晚（sensei：插入量 = 期望 + 8194，正好多一批）：**残值必须夹住**。
        // taskRemaining 是"本批还能做多少"，而 n = min(batchNext, taskRemaining) 本应天然夹住。
        // 为防任何路径（缓存命中/共享经验/限流）绕过这一步，这里统一再夹一次，并打权威日志。
        if (patternDetails != null) {
            long residual = ae2addon$getTaskValue(patternDetails);
            long beforeClamp = Math.min(ae2addon$getBatchMultiplier(patternDetails), residual);
            if (residual > 0 && residual < 100000L) {
                AE2Addon.LOGGER.info(
                        "[ae2addon][夹取] 本批 batchNext={} 残值={} → 可做 {} 份（末批应按残值，不应整批 8194）",
                        ae2addon$getBatchMultiplier(patternDetails), residual, beforeClamp);
            }
        }
        // 2026-09-17 sensei「A 方案」：批量通道对「我们的集成CPU」或「千机样板」开放 ——
        // 用其它 CPU（如 omni cells 的量子CPU）跑千机样板时，也要能吃到网络并行数的批量。
        ae2addon$qianjiBatch = !ae2addon$budgetActive && ae2addon$isQianJiPattern(patternDetails);
        if ((!ae2addon$budgetActive && !ae2addon$qianjiBatch)
                || patternDetails == null || inventory == null) {
            // 别的样板 / 原版 CPU：直接调用原始静态方法（原版行为）
            return CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                    level, expectedOutputs, expectedContainerItems);
        }

        long taskRemaining = ae2addon$getTaskValue(patternDetails);
        if (taskRemaining <= 1) {
            ae2addon$diagTaskValueFallback++;
        }
        // ⚠ 2026-09-18/19（sensei：40000 的单做出 40970，末批该 7224 却做 8194）：
        // **自记剩余是唯一权威**：
        //   · ≤ 0 → 本单已交付够，停止提取（否则会被 AE2 那个读不准的值带偏，无限多给）
        //   · > 0 → 直接夹住本批份数（末批自然只做残值）
        long selfRemaining = ae2addon$ledgerRemaining(patternDetails);
        // 换单/取消检测（任务对象 identity 变化即清账）
        ae2addon$checkJobSwitch();
        selfRemaining = ae2addon$ledgerRemaining(patternDetails);
        if (selfRemaining <= 0) {
            AE2Addon.LOGGER.info(
                    "[ae2addon][提取] 本单已交付足够（自记剩余={}，AE2 读到的={}）→ 停止提取，不再多给",
                    selfRemaining, taskRemaining);
            ae2addon$settleBatchN = 0L;
            return null;
        }
        long n = Math.min(ae2addon$getBatchMultiplier(patternDetails), selfRemaining);
        if (com.ae2addon.crafting.CraftingCompat.debugLogs && (++ae2addon$diagExtractLogCount & 0x3F) == 0) {
            String io = "?";
            try {
                var outs = patternDetails.getOutputs();
                if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                    io = outs[0].what().getDisplayName().getString();
                }
            } catch (RuntimeException ignored) {
            }
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] extractBatch详情(节流): 产出={} taskRemaining={} batchMultiplier={} n={}",
                    io, taskRemaining, ae2addon$getBatchMultiplier(patternDetails), n);
        }
        // 2026-08-28：同网格存在无限接口声明该样板 → 跳过自适应爬坡直接全量推。
        // 无限接口无条件收 N×，一次 push 交付整个任务（多 lane 并行 = 并行推送）。
        if (ae2addon$hasFeederFor(patternDetails)) {
            n = Math.min(taskRemaining, ae2addon$batchMaxMultiplier());
            if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] 无限接口全量推送: pattern={} n={} taskRemaining={}",
                        patternDetails.getClass().getSimpleName(), n, taskRemaining);
            }
        } else if (ae2addon$isCraftingPattern(patternDetails)) {
            // 2026-08-22：合成族样板（合成/切石/锻造，MA 执行族）强制 1× 推送。
            // 真实合成机器按单次配方执行，N× ScaledPattern 输入会导致拒收/错乱
            // （sensei 实测：AECraftingPattern 单 tick 206 次拒收、任务卡 1、CPU
            // 永久 busy）。处理样板（AEProcessingPattern）保留批量推送。
            if (ae2addon$virtualSettleActive(patternDetails)) {
                // ⚠ 2026-09-18：千机样板**先夹再分派**（不要等到后面统一夹取）——
                // 否则夹取后 n 可能落到 1× 提取分支，而批量上下文仍是"想提取的"大 N，
                // 结算侧就会按大 N 结算 → 提取份数 ≠ 结算份数 → 任务卡死。
                if (ae2addon$isQianJiPattern(patternDetails)) {
                    long before = n;
                    n = ae2addon$clampQianjiBatch(n, patternDetails);
                    ae2addon$settleLimitReached =
                            !com.ae2addon.crafting.CraftingCompat.pendingSettleCallBudget();
                    if (ae2addon$settleLimitReached) {
                        // 本 tick 次数已用尽：只提 1 份，并标记"这一份必须被结算"
                        n = 1;
                        ae2addon$settleLimitForced = true;
                    }
                    if (n != before) {
                        // ⚠ 关键：本批实际提取的份数变了，批量上下文必须跟着变。
                        // 否则结算侧读到的是"想提取的"大 N（没被夹过的 batchNext），
                        // 就会结算出多于真实材料的产物 → waitingFor 永远差 N → 只合成一部分就卡死
                        // （sensei 2026-09-18 实测：下单量越大越容易复现）。
                        ae2addon$batchMultiplier = n;
                        if (com.ae2addon.crafting.CraftingCompat.debugLogs && !ae2addon$settleLimitReached) {
                            com.ae2addon.AE2Addon.LOGGER.info(
                                    "[ae2addon][settle] 千机结算节流: {} → {} 份/tick（cap={} taskRemaining={}）",
                                    before, n, ae2addon$qianjiSettleCapPerTick(), taskRemaining);
                        }
                    }
                }
                if (ae2addon$isSelfReferentialPattern(patternDetails)) {
                    // 增殖配方（模板复制 a+b=2a，产物与输入同种）——2026-09-09 提速：
                    // 不再强制逐次。批量 N = 当前 crafting storage 可用种子量（库存感知）：
                    // 每轮提取全部可用种子 → 结算产 2N 回流 → 库存翻倍 → 指数滚雪球
                    // （1→2→4→8…），500 次任务只需 ~log2(500)≈9 轮而非 500 轮。
                    // 种子库存探测用 SIMULATE（不真扣）；提取失败自动减半回退。
                    long seedCap = ae2addon$probeSelfSeedCap(patternDetails, inventory);
                    n = Math.min(taskRemaining, Math.max(1, seedCap));
                    n = Math.min(n, ae2addon$batchMaxMultiplier());
                    if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
                        com.ae2addon.AE2Addon.LOGGER.info(
                                "[ae2addon][debug] 增殖库存感知批量: pattern={} taskRemaining={} seedCap={} n={}",
                                patternDetails, taskRemaining, seedCap, n);
                    }
                } else {
                    // M1c（2026-09-04）：虚拟结算无真实装配瓶颈 → 一次提取尽可能多，
                    // 整层瞬时结算（ScaledPattern multiplyExact 防溢出，溢出自动回退 1×）。
                    // ⚠ 2026-09-18 起千机样板另有每 tick 上限（见 ae2addon$qianjiSettleCapPerTick），
                    // 在下面统一夹取，避免单 tick 掷骰几百万份把服务端卡死。
                    n = Math.min(taskRemaining, ae2addon$batchMaxMultiplier());
                    if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
                        com.ae2addon.AE2Addon.LOGGER.info(
                                "[ae2addon][debug] 合成族样板虚拟结算全量: pattern={} n={} taskRemaining={}",
                                patternDetails, n, taskRemaining);
                    }
                }
            } else {
                n = 1;
                if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][debug] 合成族样板强制1×: pattern={} batchNext={} taskRemaining={}",
                            patternDetails,
                            ae2addon$getBatchMultiplier(patternDetails), taskRemaining);
                }
            }
        }
        // ⚠ 2026-09-18 晚（sensei：产物比下单量多，且随下单量增大而增大）：
        // **把「本批实际提取份数」收敛成唯一权威来源**。结算侧只认 settleBatchN，
        // 不再读"想提取的" batchMultiplier —— 后者在限流/回退/缓存命中时会与真实提取量脱节。
        //
        // ⚠ 末批（sensei：40970 = 5×8194）额外用**最新残值**夹死：
        // 末批特征是"残值 < batchNext"，任何"整批"口径都会多出 (batchNext − 残值)。
        long residualNow = ae2addon$getTaskValue(patternDetails);
        if (residualNow > 0 && n > residualNow) {
            AE2Addon.LOGGER.info("[ae2addon][夹取] 末批：本批想提 {} 份，残值 {} → 夹到 {} 份",
                    n, residualNow, residualNow);
            n = residualNow;
        }
        ae2addon$settleBatchN = n;
        // ⚠ 2026-09-19（sensei：N 应该逐轮翻倍，实测卡在同一值不动）：
        // 这里原来无条件 `batchMultiplier = n`，而 n 是**本批开始前读到的旧 batchNext**，
        // 于是把 `onBatchAccepted` 刚写好的翻倍值**当场踩掉** →
        // 下一批又读到旧的 n ⇒ 表现为"N 一直不动 / 只在换单时才涨"。
        // 改成只在"变大"时写（不回退），让翻倍真正生效。
        if (n > ae2addon$batchMultiplier) {
            ae2addon$batchMultiplier = n; // 结算侧两者必须一致（见 qianji 分支的注释）
        }
        // 1× 路径也要更新"缓存对应的倍数"，否则缓存标记会停留在旧值上
        ae2addon$batchCachedMultiplier = n;
        // ⚠ 结算侧对照：本批"实际提取份数"与"批量倍率"是否一致（不一致就是要抓的错配）
        if (CraftingCompat.debugLogs) {
            AE2Addon.LOGGER.info(
                    "[ae2addon][提取] 本批 n={} batchMultiplier={} 自记剩余={} taskRemaining={}",
                    n, ae2addon$batchMultiplier, selfRemaining, taskRemaining);
        }
        // ⚠ 2026-09-18 晚：**提取时刻的实测数字**（判定"读晚了还是减晚了"）。
        // 只在本批"可能吃满"时打（残值不大），避免刷屏。
        if (residualNow > 0 && residualNow <= 200000L) {
            AE2Addon.LOGGER.info(
                    "[ae2addon][提取] 本批 n={} taskRemaining(读到的)={} batchNext={} 残值(夹取时)={}"
                            + " 自记剩余={}",
                    n, taskRemaining, ae2addon$getBatchMultiplier(patternDetails), residualNow,
                    ae2addon$selfRemaining(patternDetails, ae2addon$getTaskValue(patternDetails)));
        }
        // ⚠ 2026-09-19（sensei：N 最大只到 128）：把"决定 N 上限的三个值"直接打出来，
        // 不再靠推算 —— 并行上限 / config 上限 / 有效上限。
        if (ae2addon$diagCapLogged != ae2addon$flushAllowedTick) {
            ae2addon$diagCapLogged = ae2addon$flushAllowedTick;
            AE2Addon.LOGGER.info(
                    "[ae2addon][上限] 并行上限={} config上限={} 有效批量上限={} 当前 batchNext={} 本tick每tick份数cap={}",
                    ae2addon$parallelCap(), ae2addon$batchMaxMultiplier(), ae2addon$effectiveBatchMax(),
                    ae2addon$getBatchMultiplier(patternDetails), ae2addon$qianjiSettleCapPerTick());
        }
        if (n <= 1) {
            // 我们 CPU：直接调原始静态方法，绕开 Omni 的 extractBatch handler
            // （它会对 scaled 输出算 waitingFor 余量，N 大时 reinject+null → 批量被误判失败锁 1）
            var result1x = CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                    level, expectedOutputs, expectedContainerItems);
            ae2addon$settleBatchN = result1x == null ? 0L : 1L;
            if (com.ae2addon.crafting.CraftingCompat.debugLogs && (++ae2addon$diagExtractLogCount & 0x3F) == 0) {
                String io = "?";
                try {
                    var outs = patternDetails.getOutputs();
                    if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                        io = outs[0].what().getDisplayName().getString();
                    }
                } catch (RuntimeException ignored) {
                }
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] 1x提取(节流): 产出={} 结果={} inv={}",
                        io, result1x == null ? "null(失败)" : "成功",
                        inventory == null ? "null" : inventory.getClass().getSimpleName());
                // 2026-09-09 增殖诊断：提取失败节流打印 crafting storage 全量 + 输入组明细
                // （全量兜底每 512 次提取失败打一次，防刷屏）
                if (result1x == null && (ae2addon$diagExtractLogCount & 0x1FF) == 0) {
                    ae2addon$dumpInventoryDiag(patternDetails, inventory);
                }
            }
            return result1x;
        }

        ae2addon$diagBatchExtractAttempts++;
        ScaledPattern scaled;
        try {
            scaled = new ScaledPattern(patternDetails, n);
        } catch (RuntimeException exception) {
            ae2addon$setBatchMultiplier(patternDetails, 1);
            return CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                    level, expectedOutputs, expectedContainerItems);
        }

        var batchInputs = CraftingCpuHelper.extractPatternInputs(scaled, inventory,
                level, expectedOutputs, expectedContainerItems);
        if (batchInputs != null) {
            ae2addon$batchActive = true;
            ae2addon$batchBasePattern = patternDetails;
            ae2addon$batchScaledPattern = scaled;
            ae2addon$batchMultiplier = n;
            ae2addon$batchPendingMultiplier = n;
            ae2addon$settleBatchN = n; // 结算侧按"实际提取的份数"结算（见 ae2addon$virtualSettle）
            ae2addon$batchCachedMultiplier = n; // 缓存对应的倍数（倍数涨了就作废缓存）
            ae2addon$batchCachedInputs = batchInputs;
            // 保存 expected 快照，供同 tick 缓存命中时补回记账
            ae2addon$batchCachedOutputs = new KeyCounter();
            for (var entry : expectedOutputs) {
                ae2addon$batchCachedOutputs.add(entry.getKey(), entry.getLongValue());
            }
            ae2addon$batchCachedContainerItems = new KeyCounter();
            for (var entry : expectedContainerItems) {
                ae2addon$batchCachedContainerItems.add(entry.getKey(), entry.getLongValue());
            }
            return batchInputs;
        }

        // N× 提取失败：回退批量并降级 1×（expected 计数器重置，避免残留污染）
        ae2addon$diagBatchExtractFailures++;
        if (n <= 2) {
            // 2× 都提取不出：基本是库存/接收能力不足，冷却锁住避免连续震荡
            // （⚠ 是冷却锁不是永久锁：5 秒后自动重试，否则偶发一次失败会把整单拖死）
            ae2addon$lockBatchFor(patternDetails);
            ae2addon$setBatchMultiplier(patternDetails, 1);
        } else {
            ae2addon$setBatchMultiplier(patternDetails, Math.max(1, n / 2));
        }
        expectedOutputs.reset();
        expectedContainerItems.reset();
        var fallback1x = CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                level, expectedOutputs, expectedContainerItems);
        // ⚠ 关键：这次是按 1× 实际提取的，必须把「本批实际提取份数」改成 1，
        // 否则结算侧读到的是没提取成功的大 N → 产物凭空多出 → 等待表永远差 N → 卡死
        ae2addon$settleBatchN = fallback1x == null ? 0L : 1L;
        return fallback1x;
    }

    /**
     * 是否为合成样板（crafting pattern）：此类配方强制 1× 推送（见 extractBatch）。
     * 按类名判断（不引用具体类，兼容 AE2 民间重置版/gtlcore 改名）。
     */
    @Unique
    private static boolean ae2addon$isCraftingPattern(IPatternDetails pattern) {
        // 合成族（MA 可执行、可虚拟结算）：合成/切石机/锻造台样板（2026-09-04 sensei：
        // 切石/锻造的虚拟兼容也要做）；处理样板 AEProcessingPattern 不在族内。
        // 按类名判断（不引用具体类，兼容 AE2 民间重置版/gtlcore 改名）。
        if (pattern == null) {
            return false;
        }
        String name = pattern.getClass().getName();
        return !name.endsWith("AEProcessingPattern")
                && (name.endsWith("AECraftingPattern")
                        || name.endsWith("AEStonecuttingPattern")
                        || name.endsWith("AESmithingTablePattern")
                        || name.contains("CraftingPattern"));
    }

    /**
     * 自指/增殖配方判定（2026-09-06）：产物与输入包含同种 key——
     * 如模板复制 1模板+7钻+1下界岩→2模板。此类配方全量批量需要 N 个自身
     * 种子备料（备不齐）→ 只能逐次结算靠产物倍增滚雪球。
     */
    @Unique
    private static boolean ae2addon$isSelfReferentialPattern(IPatternDetails pattern) {
        if (pattern == null) {
            return false;
        }
        var outs = pattern.getOutputs();
        if (outs == null || outs.length == 0) {
            return false;
        }
        for (var inGroup : pattern.getInputs()) {
            if (inGroup == null || inGroup.getPossibleInputs() == null) {
                continue;
            }
            for (var gs : inGroup.getPossibleInputs()) {
                if (gs == null || gs.what() == null) {
                    continue;
                }
                for (var out : outs) {
                    if (out != null && out.what() != null
                            && out.what().equals(gs.what())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 增殖批量种子上限探测（2026-09-09 提速）：返回「当前 crafting storage 中
     * 可提取的自指种子量 ÷ 每份执行消耗量」。增殖每轮提取全部可用种子 → 结算
     * 产 2N 回流 → 库存翻倍，批量 N 跟随即可指数滚雪球。探测 SIMULATE 不真扣。
     */
    @Unique
    private long ae2addon$probeSelfSeedCap(IPatternDetails patternDetails,
            appeng.crafting.inv.ICraftingInventory inventory) {
        try {
            if (patternDetails == null || inventory == null) {
                return 1;
            }
            var outs = patternDetails.getOutputs();
            if (outs == null || outs.length == 0) {
                return 1;
            }
            for (var out : outs) {
                if (out == null || out.what() == null) {
                    continue;
                }
                AEKey key = out.what();
                // 每份执行消耗该种子的量（amount × mult 累加，防御多组重复）
                long perUse = 0;
                boolean inInput = false;
                var inputs = patternDetails.getInputs();
                if (inputs != null) {
                    for (var inGroup : inputs) {
                        if (inGroup == null || inGroup.getPossibleInputs() == null) {
                            continue;
                        }
                        long groupUse = 0;
                        boolean groupHas = false;
                        for (var gs : inGroup.getPossibleInputs()) {
                            if (gs != null && gs.what() != null
                                    && gs.what().equals(key)) {
                                groupHas = true;
                                groupUse = Math.max(groupUse, gs.amount());
                            }
                        }
                        if (groupHas) {
                            inInput = true;
                            perUse += groupUse
                                    * Math.max(1, inGroup.getMultiplier());
                        }
                    }
                }
                if (!inInput || perUse <= 0) {
                    continue;
                }
                // SIMULATE 探测可提取量（不真扣）
                long avail = inventory.extract(key, Long.MAX_VALUE,
                        appeng.api.config.Actionable.SIMULATE);
                if (avail <= 0) {
                    return 0;
                }
                return Math.max(0, avail / perUse);
            }
            return 1;
        } catch (Throwable t) {
            return 1;
        }
    }

    /**
     * 同网格是否存在无限接口声明了该样板（全量推送判定）。
     * 只查同网格：跨网络误判会导致全量推给不相干的 provider 全部拒收卡任务。
     */
    @Unique
    private boolean ae2addon$hasFeederFor(IPatternDetails pattern) {
        appeng.api.networking.IGrid grid = null;
        try {
            grid = cluster.getGrid();
        } catch (RuntimeException ignored) {
        }
        if (grid == null || pattern == null) {
            return false;
        }
        return com.ae2addon.block.InfiniteInterfaceBE.hasFeederFor(grid, pattern);
    }

    /**
     * 任务取消钩子（CraftingCPUCluster.cancelJob → CraftingCpuLogic.cancel）：
     * 通知无限接口把该簇推送的未喂出材料回退网络（2026-08-28 sensei 需求）。
     */
    @Inject(method = "cancel", at = @At("HEAD"), require = 0)
    private void ae2addon$onCraftingCancelled(CallbackInfo callback) {
        if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
            // 诊断（2026-09-06 蓄水池被清排查）：谁在取消任务
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < Math.min(st.length, 8); i++) {
                sb.append(st[i].toString()).append(" <- ");
            }
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][feeder][diag] CPU任务cancel触发: {}", sb);
        }
        com.ae2addon.block.InfiniteInterfaceBE.returnPushedFor(cluster);
        // 千机：把该簇「已推送未合成」的材料也退回网络，并把簇标为已取消
        // （挡掉延迟到下一 tick 的 callable，否则材料退回 + 产物照样产出 = 复制）
        com.ae2addon.block.QianJiBE.returnPushedFor(cluster);
    }

    // ── 批量推送：push 阶段 ──

    /**
     * push 阶段：批量上下文时推 ScaledPattern（N× 输入）。
     * 成功 → 任务值额外减 N−1（AE2 自己会再减 1，共 −N），批量翻倍；
     * 失败 → 批量减半（1× 失败则锁定逐条）。
     */
    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingProvider;"
                            + "pushPattern(Lappeng/api/crafting/IPatternDetails;"
                            + "[Lappeng/api/stacks/KeyCounter;)Z"),
            require = 0)
    private boolean ae2addon$pushBatch(ICraftingProvider provider,
            IPatternDetails patternDetails, KeyCounter[] inputs) {
        ae2addon$diagPushCalls++;
        // ⚠ 2026-09-19（sensei：急停后仍会推出物品）：急停是**硬门禁** ——
        // 既不许虚拟结算，也不许真实派发（返回 false = provider 拒收，AE2 正常退避，
        // 材料不会丢，恢复后照常）。
        if (com.ae2addon.crafting.CraftingCompat.cpuHalted) {
            return false;
        }
        // ── 虚拟结算（v0.3 M1）：合成类样板节点不真实装配 ──
        // 材料已在 extract 阶段从 crafting storage 提取（销毁 ✓）；产物走原版 insert
        // 回收通道瞬时注入：根产物→CraftingLink+finishJob；中间产物→crafting storage 供上层。
        if (ae2addon$virtualSettleActive(patternDetails)) {
            // ⚠ 2026-09-18 晚 修「倍数永远不涨」：
            // 原来这里直接 `return ae2addon$virtualSettle(...)`，**绕过了下面的
            // onBatchAccepted 翻倍逻辑** → 千机的批量倍数永远停在 1，大订单被钉死在
            // 1 份/tick（实测 4.1 万份任务里每次只 -1 ≈ 半小时，看着就是卡在正在合成）。
            // 现在：千机样板按"本批实际结算份数"走同样的翻倍 + 任务值补减；
            // 装配处理器（原有虚拟结算）保持原样不动，避免改动它已有的表现。
            long settledBatches = ae2addon$virtualSettle(patternDetails);
            if (settledBatches > 0 && patternDetails != null
                    && ae2addon$isQianJiPattern(patternDetails)) {
                ae2addon$noteDelivered(patternDetails, settledBatches); // 自己记账已交付
                // 虚拟结算自己爬坡：每成功一批 ×2（受每 tick 份数上限约束）
                var scaleKey = ae2addon$batchKey(patternDetails);
                if (scaleKey != null) {
                    // ⚠ 2026-09-19：爬坡上限改用"结算单批上限"（集成CPU = 不夹），
                    // 否则 scale 会被 4096 钉死，永远学不到更大的批（sensei 报的问题）
                    long cap = ae2addon$qianjiSettleBatchCeiling();
                    long cur = ae2addon$settleScale.getOrDefault(scaleKey, 1L);
                    long next = (cur > cap / 2) ? cap : cur * 2;
                    ae2addon$settleScale.put(scaleKey, Math.max(1L, next));
                }
                ae2addon$decrementTaskValue(patternDetails, settledBatches - 1);
                ae2addon$onBatchAccepted(patternDetails, settledBatches);
                ae2addon$batchPendingMultiplier = -1;
            }
            return settledBatches > 0;
        }
        // ── 共享成功派发预算（2026-09-08 学 ae2lt 双预算思想）：预算耗尽时拒绝本次
        // push（返回 false 模拟 provider 拒绝，AE2 正常退避），防止多个巨型订单同
        // tick 抢占把服务端拖垮。仅限集成 CPU（budgetActive）真实 push 计费；
        // 原版 CPU 与虚拟结算不占预算。
        boolean budgetReserved = false;
        if (ae2addon$budgetActive
                && com.ae2addon.crafting.CraftingCompat.dispatchBudgetPerTick > 0) {
            if (!com.ae2addon.crafting.CraftingCompat.tryConsumeDispatch()) {
                if (CraftingCompat.debugLogs) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][debug] 共享派发预算耗尽，拒绝 push (provider={}, 已用={})",
                            provider == null ? "null" : provider.getClass().getSimpleName(),
                            com.ae2addon.crafting.CraftingCompat.dispatchUsedThisTick());
                }
                return false;
            }
            budgetReserved = true; // push 失败会退回配额
        }
        if (!ae2addon$batchActive
                || ae2addon$batchBasePattern == null
                || !ae2addon$batchBasePattern.equals(patternDetails)
                || ae2addon$batchScaledPattern == null
                || ae2addon$batchMultiplier <= 1) {
            com.ae2addon.crafting.CraftingCompat.currentPushingCluster = cluster;
            boolean accepted;
            try {
                accepted = provider.pushPattern(patternDetails, inputs);
            } finally {
                com.ae2addon.crafting.CraftingCompat.currentPushingCluster = null;
            }
            if (CraftingCompat.debugLogs) {
                // 节流：push 高频，全量打印掉刻（sensei 实测 20:00）。每 200 次打一条。
                if ((++ae2addon$diagPushCalls & 0xFF) == 0) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][debug] pushBatch(1x): provider={} pattern={} 接受={} batchActive={} (节流)",
                            provider == null ? "null" : provider.getClass().getSimpleName(),
                            patternDetails == null ? "null" : patternDetails.getClass().getSimpleName(),
                            accepted, ae2addon$batchActive);
                }
            }
            // 1× 成功也是批量探测的成功：翻倍 N，让同一 tick 内后续提取
            // 直接尝试 2×/4×/8×... 指数暴涨，对无限消费型接收方瞬间全发
            // A 方案：非我们 CPU 跑千机样板时也要能翻倍（否则 N 永远是 1）
            if ((ae2addon$budgetActive || ae2addon$qianjiBatch) && accepted && patternDetails != null) {
                ae2addon$onBatchAccepted(patternDetails, 1L);
            } else if (!accepted && patternDetails != null) {
                ae2addon$recordStuck(patternDetails);
            }
            if (!accepted && budgetReserved) {
                com.ae2addon.crafting.CraftingCompat.refundDispatch(); // push失败退配额
            }
            return accepted;
        }

        IPatternDetails dispatchPattern = ae2addon$batchScaledPattern;
        boolean temporarilyAdded = ae2addon$temporarilyRegisterScaledPattern(
                provider, patternDetails, dispatchPattern);
        boolean accepted;
        com.ae2addon.crafting.CraftingCompat.currentPushingCluster = cluster;
        try {
            accepted = provider.pushPattern(dispatchPattern, inputs);
        } finally {
            com.ae2addon.crafting.CraftingCompat.currentPushingCluster = null;
            if (temporarilyAdded) {
                ae2addon$removeTemporarilyAddedPattern(provider, dispatchPattern);
            }
        }

        if (CraftingCompat.debugLogs) {
            // 节流：push 高频（32 lane × 每 tick 多次），全量打印掉刻
            //（sensei 实测 20:00：开 debug 日志游戏掉刻）。每 200 次打一条。
            if ((++ae2addon$diagPushCalls & 0xFF) == 0) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] pushBatch: provider={} pattern={} N={} 接受={} (节流)",
                        provider == null ? "null" : provider.getClass().getSimpleName(),
                        dispatchPattern == null ? "null" : dispatchPattern.getClass().getSimpleName(),
                        ae2addon$batchMultiplier, accepted);
            }
        }

        if (accepted) {
            ae2addon$decrementTaskValue(patternDetails, ae2addon$batchMultiplier - 1);
            ae2addon$onBatchAccepted(patternDetails, ae2addon$batchMultiplier);
            ae2addon$batchPendingMultiplier = -1;
            ae2addon$clearBatchContext();
        } else {
            // 单个 provider 拒绝批量 ≠ 批量不可行（后续 provider 可能接受）。
            // 不清空上下文：同一次 provider 循环里后续 provider 继续走批量路径，
            // 避免「原始 pattern + N× 输入」错配（历史教训）。
            // 也不直接反馈控制器；由下次提取的 pending 收敛逻辑处理。
            ae2addon$diagBatchFailProvider = provider.getClass().getName();
            // 2026-08-22 修复：拒收计数此前从不自增（onBatchRejected 只在锁定路径
            // 调用），诊断里的「失败0次」是假象。这里只计数不改 N 收敛（后续
            // provider 接受时不该减半，收敛仍交给 pending 逻辑）。
            ae2addon$diagBatchRejected++;
            ae2addon$recordStuck(patternDetails);
            if (budgetReserved) {
                com.ae2addon.crafting.CraftingCompat.refundDispatch(); // push失败退配额
            }
        }
        return accepted;
    }

    // ── 虚拟结算（v0.3 M1，开发中）──

    /** 虚拟结算后根产物 finishJob 置 job=null，需终止本 tick 任务迭代防 NPE。 */
    @Unique
    private boolean ae2addon$settleStopIteration;

    /**
     * 待回收产物（key→量）：settle 返回 true 后外层才把产物记入 waitingFor；
     * insert 只在 waitingFor 有记账时冲抵（字节码 2026-09-04 确认），所以产物回收
     * 必须推迟到记账完成后——在任务循环 hasNext 处执行（此时记账已发生）。
     */
    @Unique
    private KeyCounter ae2addon$pendingSettle;

    /**
     * 当前待回收产物是否来自自指配方（增殖，模板复制类）：是则 root 产物
     * 回流 crafting storage 供下一轮 extract 滚雪球，而非物理入网——
     * 自指任务的 root 同时也是后续轮次的输入种子（2026-09-06）。
     */
    @Unique
    private boolean ae2addon$pendingSettleSelfRef;

    /**
     * 判定（v0.3 M3）：仅限合成族样板（合成/切石/锻造，非处理类）；且当前 CPU 簇的
     * 集成 CPU（主簇或虚拟 lane）挂了装配处理器模块并声明了该样板（样板槽白名单）
     * 才虚拟结算。无模块/未声明 → 一律真实合成。
     */
    @Unique
    private boolean ae2addon$virtualSettleActive(IPatternDetails patternDetails) {
        if (patternDetails == null) {
            return false;
        }
        // 2026-09-18（sensei）：**千机样板由插了该样板的那台千机结算**。
        // 注意这条要在「合成族」判定之前——千机样板不一定登记成 MA 可执行的合成族。
        if (ae2addon$isQianJiPattern(patternDetails)) {
            boolean hasOwner = com.ae2addon.block.QianJiBE.settleOwner(patternDetails) != null;
            if (CraftingCompat.debugLogs) {
                // ⚠ 2026-09-19（sensei 两次反馈刷屏，分两步才治好）：
                //   ① 节流原来是**每实例一份** —— 集成 CPU 64 条量子分裂线程 = 64 个
                //      CraftingCpuLogic 实例 → 64 × 20tps = **1280 行/秒**（实测 4 分钟 4 万行）。
                //   ② 改成跨实例全局节流后降到 20 行/秒（每 tick 一行），照样是"一直打 log"。
                //   现在只在**判定结果变化**时打（并限速 1 秒最多一行）：稳态零输出，
                //   真出问题时（归属机器丢了）立刻能看见。
                long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
                if (ae2addon$lastQianjiVerdict != (hasOwner ? 1 : 0)
                        && ae2addon$tickGate(ae2addon$diagSettleTickGlobal, tick, 20L)
                        && ae2addon$logHot()) {
                    ae2addon$lastQianjiVerdict = hasOwner ? 1 : 0;
                    ae2addon$diagSettleTickGlobal = tick;
                    AE2Addon.LOGGER.info("[ae2addon][settle] 千机样板判定变化: pattern={} 有归属机器={}",
                            patternDetails.getClass().getSimpleName(), hasOwner);
                }
            }
            return hasOwner;
        }
        if (!ae2addon$isCraftingPattern(patternDetails)) {
            return false;
        }
        var owner = com.ae2addon.block.IntegratedCPURegistry.ownerOf(cluster);
        var module = com.ae2addon.block.AssemblerRegistry.moduleFor(owner);
        boolean declared = module != null && module.declares(patternDetails);
        if (CraftingCompat.debugLogs) {
            // 诊断（2026-09-04 锻造/切石不虚拟排查）：环节日志节流
            // 2026-09-19：跨实例 + 5 秒一条（64 条 lane 会放大 64 倍）
            long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
            if (ae2addon$tickGate(ae2addon$diagSettleTickGlobal, tick, 100L) && ae2addon$logHot()) {
                ae2addon$diagSettleTickGlobal = tick;
                String out = "?";
                try {
                    var outs = patternDetails.getOutputs();
                    if (outs != null && outs.length > 0 && outs[0] != null
                            && outs[0].what() != null) {
                        out = outs[0].what().toString();
                    }
                } catch (RuntimeException ignored) {
                }
                AE2Addon.LOGGER.info(
                        "[ae2addon][settle] 判定: pattern={} 产物={} owner={} module={} declared={} 簇@{} 模块槽0={}",
                        patternDetails.getClass().getSimpleName(), out,
                        owner == null ? "null" : "cpu",
                        module == null ? "null" : module.getBlockPos().toShortString(),
                        declared, System.identityHashCode(cluster),
                        module == null ? "-" : module.getSlot(0).getHoverName().getString());
            }
        }
        return declared;
    }

    /**
     * [settle] 诊断日志的**跨实例**全局节流（2026-09-19）。
     * <p>
     * 原来是每实例一份的 tick 节流字段，但集成 CPU 会把工作拆到 N 条
     * 量子分裂线程上，每条 lane 一个 CraftingCpuLogic 实例 → 日志量 ×N。
     * 实测 64 条 lane 时 `千机样板判定` 一行刷到 1280 行/秒（40,346 行/4 分钟），
     * 既淹掉别的日志也拖慢服务端（同步写盘）。改成 static 后全局每 tick 最多 1 行。
     */
    @Unique
    private static long ae2addon$diagSettleTickGlobal = Long.MIN_VALUE;

    /**
     * 上次的「千机样板 → 有归属机器」判定结果（-1 未知 / 0 无 / 1 有）。
     * 只在变化时打日志，稳态零输出（见 {@link #ae2addon$virtualSettleActive}）。
     */
    @Unique
    private static int ae2addon$lastQianjiVerdict = -1;

    /**
     * 虚拟结算：不真实装配（跳过 provider.pushPattern），材料已由 extract 阶段扣出
     * （= 销毁）。产物登记到 pendingSettle，外层据此记账（waitingFor += 产物），
     * 待任务循环 hasNext 处（记账后）调原版 insert 回收：
     * - 根产物（finalOutput 匹配）→ CraftingLink 送达请求者 + remainingAmount 归零 + finishJob
     * - 中间产物 → crafting storage（inventory），上游节点 extract 直接命中
     * <p>
     * ⚠ 2026-09-18 晚：返回值从 boolean 改成 **long = 本批实际结算的份数**（0 = 没结算）。
     * 调用方（{@code ae2addon$pushBatch}）据此走**和真实 push 完全相同**的
     * 「任务值补减 + onBatchAccepted 翻倍」路径 —— 这是修「倍数永远停在 1」的关键。
     */
    @Unique
    private long ae2addon$virtualSettle(IPatternDetails patternDetails) {
        // ⚠ 2026-09-19（sensei：急停后仍会推出物品）：急停是**硬门禁**。
        // 双保险：除了 extractBatch 顶部的拦截，这里再挡一次（结算才是真正产出产物的地方）。
        if (com.ae2addon.crafting.CraftingCompat.cpuHalted) {
            return 0L;
        }
        // 本批的记账台账清零：结算后要用它算 waitingFor 差额（副产物/精华/倍数放大的部分）
        ae2addon$creditedThisSettle = new KeyCounter();
        // ⚠ 2026-09-18 修数字错配（sensei 反馈「CPU 内订单莫名变大 + 卡住不动」）：
        // **结算的份数必须等于提取的份数**。提取侧被限流夹过 N，如果这里还按
        // batchMultiplier（未夹的、会无限翻倍的值）结算，就会出现两种事故：
        //   ① 结算多了 → waitingFor 记账超过真实产物 → 任务永远差 N → 卡死
        //   ② 结算少了 → 提取的材料凭空消失 → 产物永不出现 → 同样卡死
        // 提取侧记的 ae2addon$settleBatchN 就是这一批实际提取的份数（含 1× 提取）。
        // ⚠ 优先读「本批**实际提取**的份数」（settleBatchN，提取侧在每条路径上都写），
        // 而不是「想提取的」batchMultiplier —— 两者在限流/提取失败回退/锁定降级时会不一致，
        // 按后者结算就会产出多于真实材料的产物 → 等待表永远差 N → 卡在正在合成。
        long settledN = ae2addon$settleBatchN > 0
                ? ae2addon$settleBatchN
                : (ae2addon$batchActive
                        && ae2addon$batchBasePattern != null
                        && ae2addon$batchBasePattern.equals(patternDetails)
                        && ae2addon$batchMultiplier > 0
                        ? ae2addon$batchMultiplier
                        : 1L);
        // ⚠ 2026-09-19 关键修复（sensei：同一 tick 交付 5 批、提取日志只 2 条）：
        // AE2 的循环会**在同一 tick 内重复推送**同一样板，而提取走"同 tick 缓存"提前返回，
        // 于是结算在后续几轮里读到的仍是**缓存批次**的份数（8194），自记剩余（7224）根本没参与
        // → 末批照样按整批给，多出 970。
        // 修法：**在结算这一刻用"自记剩余"夹一次**，末批自然只交付残值。
        if (ae2addon$isQianJiPattern(patternDetails)) {
            // 换单/取消检测：任务身份变了就清账（取消订单时 job 会换成新对象，不是 null）
            ae2addon$checkJobSwitch();
            long left = ae2addon$ledgerRemaining(patternDetails);
            if (left <= 0) {
                AE2Addon.LOGGER.info(
                        "[ae2addon][结算] 本单已交付足够（自记剩余={}）→ 跳过本批结算，不再多给", left);
                return 0L;
            }
            // ⚠ 2026-09-19（解耦虚拟结算的批量）：**本批份数由这里自己定**，
            // 不再借用批量通道的 batchMultiplier —— 那套是为"真实 provider 推送"设计的，
            // 绑着它会让"退役批量通道"和"保住性能"互相打架。
            // 定批 = min(自记剩余, 已学到的批量规模, 本 tick 份数上限)，至少 1。
            long scale = ae2addon$settleScale.computeIfAbsent(
                    ae2addon$batchKey(patternDetails), k -> 1L);
            // ⚠ 2026-09-19：上限改用"结算单批上限"（集成CPU = 不夹，见该方法注释）
            long cap = ae2addon$qianjiSettleBatchCeiling();
            long right = Math.max(1L, Math.min(left, Math.min(scale, cap)));
            // 本 tick 结算次数闸门（每 tick 次数上限）
            if (!com.ae2addon.crafting.CraftingCompat.tryConsumeSettleCall()) {
                return 0L;
            }
            // 自己提取：与结算同一步完成（设计文档 §6 的原子性要求）
            var inventoryHere = cluster == null || cluster.craftingLogic == null
                    ? null : cluster.craftingLogic.getInventory();
            if (inventoryHere == null) {
                return 0L;
            }
            ScaledPattern scaled;
            try {
                scaled = new ScaledPattern(patternDetails, right);
            } catch (RuntimeException overflow) {
                // ⚠ 2026-09-19（sensei：Long.MAX 下单量 × 64 产出的样板会不会溢出）：
                // `ScaledPattern` 用三处 `Math.multiplyExact`（输出量 / 输入乘数 / 输入量）
                // 主动抛 ArithmeticException，**不会静默回绕**——这点它是可靠的。
                // 但光"不结算"会变成每 tick 都失败、订单永久卡住 ⇒ **必须降级重试**：
                // 把已学到的规模减半（至少 1），下一轮用更小的批。
                long fallback = Math.max(1L, right / 2);
                ae2addon$settleScale.put(ae2addon$batchKey(patternDetails), fallback);
                if (CraftingCompat.debugLogs) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][结算] 溢出（本批 {} 份，输入/输出超过 long）→ 降级为 {} 份重试",
                            right, fallback);
                }
                return 0L;
            }
            var expectedOut = new KeyCounter();
            var expectedCont = new KeyCounter();
            var extracted = CraftingCpuHelper.extractPatternInputs(scaled, inventoryHere,
                    cluster.getLevel(), expectedOut, expectedCont);
            if (extracted == null) {
                // 材料不足 or 内部溢出：同样降级，避免死循环卡在同一档
                long fallback = Math.max(1L, right / 2);
                ae2addon$settleScale.put(ae2addon$batchKey(patternDetails), fallback);
                // ⚠ 2026-09-19（sensei：化学品千机配方卡住）：这条以前是**静默**的，
                // 而"提取失败"正是卡住的高频原因（材料/类型不支持/库存空）
                if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][结算] 提取失败（材料不足/类型不支持）本批 {} 份 → 降级为 {} 份重试；pattern={}",
                            right, fallback, patternDetails.getClass().getSimpleName());
                }
                return 0L;
            }
            settledN = right;
            ae2addon$settleBatchN = right;
            ae2addon$lastSettleExpected = expectedOut;   // 供 provider 记账（waitingFor）
            // ⚠ 2026-09-19（解耦后必须自己记账）：原来 expected 由"外层 provider 环节"写入
            // `job.waitingFor`，但我们绕过了提取环节、外层拿不到 expected → 记账会漏。
            // 这里直接补进 waitingFor，保证 `pendingSettle` 回收时能正常冲抵。
            ae2addon$addWaitingFor(expectedOut);
            if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                AE2Addon.LOGGER.info(
                        "[ae2addon][结算] 自定批：剩余 {} 已学到 {} cap {} → 本批 {} 份（已提取，与结算同步）",
                        left, scale, cap, right);
            }
        } else if (ae2addon$settleLimitForced) {
            // 本 tick 结算次数用尽 → 上游只提取了 1 份，并且**已经消耗了这 1 份材料**。
            // 所以这 1 份必须照常结算（不清预算、也不再受理后续），否则材料凭空消失 → 任务卡死。
            ae2addon$settleLimitForced = false;
            settledN = 1L;
        } else if (!com.ae2addon.crafting.CraftingCompat.tryConsumeSettleCall()) {
            // 次数用尽且本批不是"限流后仅 1 份"那批 → 不再结算
            return 0L;
        }
        try {
            // n = 本批**实际提取的份数**（见方法开头 settledN 的推导）
            long n = settledN > 0 ? settledN : 1;
            var outputs = patternDetails.getOutputs();
            if (outputs == null || outputs.length == 0) {
                return 0L;
            }
            KeyCounter pending = ae2addon$pendingSettle;
            if (pending == null) {
                pending = new KeyCounter();
                ae2addon$pendingSettle = pending;
            }
            boolean settledAny = false;
            // 千机样板：产出必须走千机自己的掷骰（主产物 + 概率副产 + 催化剂倍数），
            // 不能拿样板声明的 outputs × N —— 那会让概率副产变成必出、催化剂倍率丢失。
            var qianjiOwner = ae2addon$isQianJiPattern(patternDetails)
                    ? com.ae2addon.block.QianJiBE.settleOwner(patternDetails)
                    : null;
            if (qianjiOwner != null) {
                long t0 = System.nanoTime();
                var outcome = qianjiOwner.settle(patternDetails, n);
                long costUs = (System.nanoTime() - t0) / 1000L;
                if (outcome == null) {
                    // 机器拒绝（样板校验不过 / 未成型）→ 让调用方回退真实推送路径
                    return 0L;
                }
                for (var stack : outcome.stacks()) {
                    if (stack == null || stack.what() == null || stack.amount() <= 0) continue;
                    pending.add(stack.what(), stack.amount());
                    settledAny = true;
                }
                // ⚠ 2026-09-19 修两处记账 bug（sensei：无限精华那条线暴露出来的）：
                //
                // ① **删掉原先"再遍历 byproductLog 加一遍"的循环**：
                //    `outcome.stacks()` 里已经含概率产出（roll 把命中量 merge 进了 acc），
                //    再按 log.amount() 加一遍会让副产物**双倍**进待回收表。
                //    以前没暴雷是因为下面 ② 让两笔都被丢弃了，记账修好后就会变成"凭空多一份"。
                //
                // ② **补齐 waitingFor**：`QianJiPatternDetails.getOutputs()` **只含主产物**
                //    （副产物与无限精华都不在样板声明里），而回收走 AE2 的
                //    `CraftingCpuLogic.insert` —— 它只在 `job.waitingFor` 已有该 key 的期待量时
                //    才认账（`waiting <= 0 → return 0`），**没记账的产物会被静默丢弃**。
                //    所以这里把"本批实际要交付的量"按 key 补齐（含催化剂倍数高出声明量的部分）。
                ae2addon$topUpWaitingFor(outcome.stacks());
                if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][settle] 千机虚拟结算: pattern={} N={} 主产物{}种 副产{}种"
                                    + " 掷骰耗时={}µs → 待回收（任务值={} 递减：调用{} 成功{} 跳过{}）",
                            patternDetails.getClass().getSimpleName(), n, outcome.stacks().size(),
                            outcome.byproductLog().size(), costUs,
                            ae2addon$getTaskValue(patternDetails),
                            ae2addon$diagDecrementCalls, ae2addon$diagDecrementApplied,
                            ae2addon$diagDecrementSkipped);
                }
            } else {
                for (var out : outputs) {
                    if (out == null || out.what() == null || out.amount() <= 0) {
                        continue;
                    }
                    settledAny = true;
                    // N× 产物：ScaledPattern 构造时对 inputs/outputs 均 multiplyExact 验溢，
                    // 能走到批量提取说明 n×amount 未溢出（1× 路径无溢出问题）
                    pending.add(out.what(), out.amount() * n);
                }
            }
            if (!settledAny) {
                // ⚠ 2026-09-19（sensei：化学品千机配方卡住）：这条以前也是静默的
                if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][结算] 结算无产物（qianjiOwner={} outputs={}）→ 本批不计账；pattern={}",
                            qianjiOwner != null, outputs == null ? -1 : outputs.length,
                            patternDetails.getClass().getSimpleName());
                }
                return 0L;
            }
            // 自指/增殖配方（产物=输入同种）：root 产物需回流 crafting storage
            // 供下一轮 extract（滚雪球），flush 据此分流（2026-09-06）
            ae2addon$pendingSettleSelfRef = ae2addon$isSelfReferentialPattern(patternDetails);
            // 任务值补减 n−1 已上移到调用方（ae2addon$pushBatch），与真实 push 路径同一处收口，
            // 避免"两条路各减一次"或"某条路忘了减"（2026-09-18 晚统一）
            if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                AE2Addon.LOGGER.info(
                        "[ae2addon][settle] 虚拟结算: pattern={} N={} 产物{}种 → 待回收（外层记账后 insert）",
                        patternDetails.getClass().getSimpleName(), n, outputs.length);
            }
            return n;
        } catch (Throwable t) {
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.warn("[ae2addon][settle] 虚拟结算异常: {}", t.toString());
            }
            return 0L;
        }
    }

    /** 记账后回收 pendingSettle 产物（须在任务循环 hasNext 处调用：此时外层已记账）。
     *  根产物：先物理注入网络存储（真实 MA 路径产物进网后 requester 才提得到货），再走账务。
     *  中间产物：仅账务 insert（进 crafting storage 供上层 extract）。 */
    @Unique
    private void ae2addon$flushPendingSettle() {
        KeyCounter pending = ae2addon$pendingSettle;
        if (pending == null || pending.isEmpty()) {
            return;
        }
        ae2addon$pendingSettle = null;
        boolean selfRef = ae2addon$pendingSettleSelfRef;
        ae2addon$pendingSettleSelfRef = false;
        // 回收耗时诊断（2026-09-18 晚）：卡顿的嫌疑在"账务 insert + 物理入网"这一对，
        // 这里是唯一能量到它们的地方。累计到每 tick 汇总（见 ae2addon$reportFlushCost）。
        long tFlush0 = System.nanoTime();
        long totalAmount = 0;
        for (var e : pending) {
            if (e != null && e.getLongValue() > 0) totalAmount += e.getLongValue();
        }
        // ⚠ 2026-09-19 诊断（sensei 实测：「精华追加了、waitingFor 也补了，但回收实收 0」）：
        // 把"回收前这一刻的真相"摊开 —— 任务身份（对象 hashCode，看是不是换了 job）、
        // waitingFor 的真实内容、待回收内容。三者一对就能判断是"记到别的 job 上了"
        // 还是"key 不相等"还是"根本没记进去"。
        if (CraftingCompat.debugLogs && ae2addon$logHot()) {
            Object jobHere = ae2addon$getJob();
            AE2Addon.LOGGER.info(
                    "[ae2addon][flush] 回收前快照: job={} waitingFor={} 待回收={}",
                    jobHere == null ? "null" : ("@" + System.identityHashCode(jobHere)),
                    ae2addon$describeWaitingFor(jobHere), ae2addon$describeCounter(pending));
        }
        try {
            AEKey rootKey = ae2addon$getFinalOutputKey();
            var logic = cluster.craftingLogic;
            var grid = cluster.getGrid();
            var networkStorage = grid == null ? null : grid.getStorageService().getInventory();
            // ⚠ 2026-09-19（sensei 实测「精华 期望1 实收0」的第二层原因）：**分发顺序**。
            // 根产物的 insert 会走 CraftingLink 交割 → remainingAmount 归零 → **finishJob → job 置 null**；
            // 同一批里排在它后面的非根产物再 insert 时，AE2 第一行就是
            // `if (job == null) return 0`（javap 偏移 4-12）⇒ **直接丢弃**。
            // 实测日志正是这个形状：`物理入网 alloy 实插1 root=true` 紧跟
            // `产物未被全部认账 essence 实收0`（pending 的插入顺序恰好是主产物在前、精华在后）。
            //
            // 修法：**两趟处理 —— 先全部非根，最后才根**。
            // 非根先 insert → 进 CPU 合成存储；根再交割并 finishJob 时，
            // AE2 的 `finishJob → storeItems()` 会把合成存储里的东西**一并倒进网络**（javap 已核），
            // 所以一件都不会丢。多批订单（根不结束任务）时行为不变。
            for (int pass = 0; pass < 2; pass++) {
            for (var entry : pending) {
                if (entry == null || entry.getKey() == null || entry.getLongValue() <= 0) {
                    continue;
                }
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                boolean isRoot = rootKey != null && rootKey.equals(key);
                if ((pass == 0) == isRoot) {
                    continue;   // 第 0 趟跳过根、第 1 趟只做根
                }
                // ① 账务先行：waitingFor 冲抵 + finalOutput 匹配 → link.insert 交割尝试
                // （此时网络还没产物 → requester 提取 0 → 拿不到）+ remainingAmount 归零 +
                // finishJob。顺序关键（2026-09-04 修复：先入网再账务会让 requester 把
                // 刚入网的 root 提走——终端请求塞玩家背包、巨型量无处放 → 产物消失网络归零；
                // sensei 语义：产物注入网络，requester 只是发起方）
                long creditedToCpu = logic.insert(key, amount, appeng.api.config.Actionable.MODULATE);
                // ⚠ 2026-09-19 新增核对（今天栽在"静默丢弃"上太多次）：
                // AE2 的 `insert` 语义是 `amount = min(amount, waitingFor 里的期待量)`，
                // 期待量不足时**只收一部分**并把差额丢掉，而返回值就是"实际收了多少"。
                // 这里对**非根产物**核对：少了就报警 —— 否则产物无声无息消失（副产物/精华
                // 就是这样从虚拟结算上线起一直被丢弃的）。
                // 根产物不查：它的返回值走 CraftingLink，本来就可能小于 amount（交割量由请求方决定）。
                if (!isRoot && creditedToCpu < amount && CraftingCompat.debugLogs && ae2addon$logHot()) {
                    AE2Addon.LOGGER.warn(
                            "[ae2addon][settle] 产物未被全部认账: key={} 期望{} 实收{} 差额{}"
                                    + "（waitingFor 期待量不足 → 差额会被丢弃；见 topUpWaitingFor）",
                            key, amount, creditedToCpu, amount - creditedToCpu);
                }
                // ② 产物实体去向：
                //    - 自指任务（模板复制类）：root 同时也是后续轮次的输入种子 →
                //      回流 crafting storage（cluster inventory）滚雪球，不入网；
                //      任务完成时原版把 inventory 富余自动退网络（2026-09-06）
                //    - 普通任务：root 物理入网（账务后：link 已完成，产物留在网络）
                if (isRoot && networkStorage != null) {
                    if (selfRef) {
                        var inv = logic.getInventory();
                        if (inv != null) {
                            inv.insert(key, amount,
                                    appeng.api.config.Actionable.MODULATE);
                            if (CraftingCompat.debugLogs) {
                                AE2Addon.LOGGER.info(
                                        "[ae2addon][settle] 自指产物回流crafting storage: key={} 量={}（不入网，滚雪球）",
                                        key, amount);
                            }
                        }
                    } else {
                        // ⚠️ AE2 insert 返回「已插入量」（非剩余量）——2026-09-04 20:40 修正误报
                        long insertedAmt = networkStorage.insert(key, amount,
                                appeng.api.config.Actionable.MODULATE, cluster.getSrc());
                        if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                            AE2Addon.LOGGER.info(
                                    "[ae2addon][settle] 物理入网(账务后): key={} 期望{} 实插{} root=true",
                                    key, amount, insertedAmt);
                        }
                        if (insertedAmt < amount && CraftingCompat.debugLogs) {
                            AE2Addon.LOGGER.warn(
                                    "[ae2addon][settle] 根产物入网部分失败: key={} 已插{} 期望{}（网络满？）",
                                    key, insertedAmt, amount);
                        }
                        // ⚠ 2026-09-19 修正误报：根产物的 `insert` 返回的是 **CraftingLink 收了多少**，
                        // 而这个设计里"产物物理入网、requester 再去网络里取"本来就是正常路径
                        // （sensei 语义：产物注入网络）⇒ link 收 0 **不代表**出错。
                        // 只在**任务已经没了**（取消/结束）时才值得报警。
                        if (creditedToCpu <= 0 && ae2addon$getJob() == null && CraftingCompat.debugLogs) {
                            AE2Addon.LOGGER.warn(
                                    "[ae2addon][settle] 根产物未走交割且任务已不在（取消/结束）"
                                            + "→ 仅物理入网: key={} 量={}",
                                    key, amount);
                        }
                    }
                } else if (CraftingCompat.debugLogs && networkStorage != null) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][settle] 中间产物(不入网): key={} 量={} rootKey={}",
                            key, amount, rootKey);
                }
            }
            }   // pass 循环结束（0 = 非根，1 = 根）
            if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                AE2Addon.LOGGER.info("[ae2addon][settle] 回收完成: {} 种产物已注入（root 已入网）",
                        pending.size());
            }
            // 根产物 insert 触发 finishJob → job 置 null：终止本 tick 任务迭代，防外层 NPE
            if (ae2addon$getJob() == null) {
                ae2addon$settleStopIteration = true;
            }
        } catch (Throwable t) {
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.warn("[ae2addon][settle] 产物回收异常: {}", t.toString());
            }
        } finally {
            // 累计本 tick 的回收开销（供 ae2addon$reportFlushCost 汇总，避免刷屏）
            ae2addon$flushCostNanos += System.nanoTime() - tFlush0;
            ae2addon$flushCostCalls++;
            ae2addon$flushCostAmount += totalAmount;
        }
    }

    /** 回收节流：本 tick 是否还可以回收（每 tick 只放行一次） */
    @Unique
    private long ae2addon$flushAllowedTick = Long.MIN_VALUE;

    @Unique
    private boolean ae2addon$flushPendingSettleOncePerTick() {
        // ⚠ 2026-09-19（sensei：急停后仍会推出物品）：**待回收产物也要按住**。
        // 急停前已经结算出来、排在 pendingSettle 里的产物，原来下一 tick 就会被
        // `insert` 注入网络 —— 那正是"按了急停还在出物品"的另一条路。
        // 现在急停期间不回收（产物留在待回收表里，点「恢复」后立刻正常注入，不会丢）。
        if (com.ae2addon.crafting.CraftingCompat.cpuHalted) {
            return false;
        }
        long tick = TickHandler.instance().getCurrentTick();
        if (ae2addon$flushAllowedTick == tick) {
            return false;
        }
        ae2addon$flushAllowedTick = tick;
        return true;
    }

    /** 回收路径开销累计（每 tick 归零前汇总一次） */    @Unique
    private long ae2addon$flushCostNanos;
    @Unique
    private long ae2addon$flushCostCalls;
    @Unique
    private long ae2addon$flushCostAmount;
    @Unique
    private long ae2addon$flushCostTick = Long.MIN_VALUE;

    /** 每秒汇总一次回收开销：次数 / 总耗时 / 单次平均 / 物品总量 —— 卡顿定位的关键数字 */
    @Unique
    private void ae2addon$reportFlushCost() {
        if (!CraftingCompat.debugLogs || ae2addon$flushCostCalls <= 0) {
            return;
        }
        long tick = TickHandler.instance().getCurrentTick();
        // ⚠ 同上：哨兵安全闸门（原来溢出恒真 → `[cost]` 从来没打印过）
        if (!ae2addon$tickGate(ae2addon$flushCostTick, tick, 20L)) {
            return;
        }
        long calls = ae2addon$flushCostCalls;
        long nanos = ae2addon$flushCostNanos;
        long amount = ae2addon$flushCostAmount;
        ae2addon$flushCostCalls = 0;
        ae2addon$flushCostNanos = 0;
        ae2addon$flushCostAmount = 0;
        ae2addon$flushCostTick = tick;
        AE2Addon.LOGGER.info(
                "[ae2addon][cost] 回收路径: {} 次 / 共 {}µs / 单次平均 {}µs / 物品共 {}"
                        + "（若单次平均很大 → 网络 insert 是瓶颈；若次数很大 → 结算太碎）",
                calls, nanos / 1000L, (nanos / 1000L) / Math.max(1L, calls), amount);
    }

    // finalOutput 反射字段缓存
    @Unique
    private static volatile java.lang.reflect.Field ae2addon$finalOutputField;
    @Unique
    private static volatile boolean ae2addon$finalOutputFieldFailed;

    /** 任务树根产物的 key（无任务/失败返回 null）。 */
    @Unique
    private AEKey ae2addon$getFinalOutputKey() {
        Object job = ae2addon$getJob();
        if (job == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = ae2addon$finalOutputField;
            if (field == null) {
                if (ae2addon$finalOutputFieldFailed) {
                    return null;
                }
                try {
                    field = job.getClass().getDeclaredField("finalOutput");
                } catch (NoSuchFieldException e) {
                    field = null;
                }
                if (field == null) {
                    // 类型兜底：唯一 GenericStack 类型字段
                    for (java.lang.reflect.Field f : job.getClass().getDeclaredFields()) {
                        if (GenericStack.class.isAssignableFrom(f.getType())) {
                            field = f;
                            break;
                        }
                    }
                }
                if (field == null) {
                    ae2addon$finalOutputFieldFailed = true;
                    return null;
                }
                field.setAccessible(true);
                ae2addon$finalOutputField = field;
            }
            Object out = field.get(job);
            if (!(out instanceof GenericStack gs) || gs.what() == null) {
                return null;
            }
            return gs.what();
        } catch (RuntimeException | ReflectiveOperationException e) {
            ae2addon$finalOutputFieldFailed = true;
            return null;
        }
    }

    /**
     * 2026-09-09 增殖诊断：打印 crafting storage 全量 + pattern 输入组明细。
     * 定位「71 次结算成功后 1x 提取永久失败」的缺料/失配根因。
     */
    @Unique
    private void ae2addon$dumpInventoryDiag(IPatternDetails patternDetails,
            appeng.crafting.inv.ICraftingInventory inventory) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[增殖诊断] extract失败 pattern=").append(
                    patternDetails == null ? "null" : patternDetails.getClass().getSimpleName());
            sb.append(" 输入组=");
            if (patternDetails != null) {
                var inputs = patternDetails.getInputs();
                if (inputs != null) {
                    sb.append(inputs.length).append("组: ");
                    for (int i = 0; i < inputs.length; i++) {
                        var grp = inputs[i];
                        if (grp == null) {
                            continue;
                        }
                        sb.append("[").append(i).append("]mult=").append(grp.getMultiplier()).append(" {");
                        var poss = grp.getPossibleInputs();
                        if (poss != null) {
                            for (var gs : poss) {
                                if (gs != null && gs.what() != null) {
                                    sb.append(gs.what()).append("x").append(gs.amount()).append(" ");
                                }
                            }
                        }
                        sb.append("} ");
                    }
                } else {
                    sb.append("null");
                }
            }
            sb.append(" storage=");
            if (inventory instanceof appeng.crafting.inv.ListCraftingInventory lci) {
                sb.append(lci.list.size()).append("种: ");
                for (var e : lci.list) {
                    sb.append(e.getKey()).append("x").append(e.getLongValue()).append(" ");
                }
            } else {
                sb.append(inventory == null ? "null" : inventory.getClass().getSimpleName());
            }
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] {}", sb);
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 增殖诊断失败: {}", t.toString());
        }
    }

    // ── getCoProcessors 保护（与 OmniSequence 共存）──

    /**
     * getCoProcessors 保护：对集成 CPU 簇强制返回 MAX_VALUE−1
     * （AE2 内部 +1 不溢出；CPU 列表/∞ 显示的哨兵值）。
     * 原版/其他簇返回原值，不影响原版行为。
     */
    @Redirect(method = "tickCraftingLogic",
            at = @At(value = "INVOKE",
                    target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;"
                            + "getCoProcessors()I"),
            require = 0)
    private int ae2addon$protectCoProcessors(CraftingCPUCluster targetCluster) {
        if (ae2addon$budgetActive && ae2addon$isIntegratedCpu(targetCluster)) {
            return Integer.MAX_VALUE - 1;
        }
        return targetCluster.getCoProcessors();
    }

    @Unique
    private void ae2addon$recordStuck(IPatternDetails pattern) {
        ae2addon$lastStuckPattern = pattern;
        ae2addon$lastStuckTick = TickHandler.instance().getCurrentTick();
    }

    // ── 批量自适应控制器 ──

    /** 卡住诊断节流：每秒最多一条 */
    @Unique
    private long ae2addon$diagStuckTick = Long.MIN_VALUE;

    /** 任务值开始快照是否已打（每 tick 检查、只打一次） */
    @Unique
    private boolean ae2addon$taskSnapshotDone;

    /**
     * 任务状态诊断（2026-09-18）：sensei 反馈「下单量 >512 时只合成一部分就卡住」。
     * <p>
     * 把卡住那一刻的内部数字全部摊开：任务值、批量倍数、本批实际提取份数、
     * 待回收产物、以及 job 里登记的各产物期待量（waitingFor）。
     * 有了这些数字就能判定到底是「任务值没减」「产物没回收」还是「期待量对不上」。
     */
    @Unique
    private void ae2addon$diagnoseStuckTask() {
        if (!CraftingCompat.debugLogs) {
            return;
        }
        long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
        // ⚠ 用哨兵安全的闸门（原来 `tick - Long.MIN_VALUE < 100` 溢出恒真 → 永远不打印）
        if (!ae2addon$tickGate(ae2addon$diagStuckTick, tick, 100L)) { // 5 秒一条
            return;
        }
        ae2addon$diagStuckTick = tick;
        try {
            var job = ae2addon$getJob();
            // ⚠ 2026-09-18 晚：**任务值开始快照**。
            // sensei 实测「下单 40000（1粉转1锭）→ 实际产出 4195328 = 512 × 8194」，
            // 说明 CPU 里登记的任务值本身就不是 40000。AE2 的任务循环是"该做多少就做多少"，
            // 所以产出偏多的**源头在任务值**，不在结算。这条日志用来抓"第一次看到任务值时它是多少"。
            if (job != null && !ae2addon$taskSnapshotDone) {
                var tasks0 = ae2addon$getTasks(job);
                var sb0 = new StringBuilder("[ae2addon][task开始] ");
                sb0.append("任务数=").append(tasks0 == null ? -1 : tasks0.size());
                int shown0 = 0;
                if (tasks0 != null) {
                    for (var e0 : tasks0.entrySet()) {
                        if (shown0++ >= 3) break;
                        long v0 = -1;
                        try {
                            var f0 = ae2addon$getTaskValueField(e0.getValue());
                            if (f0 != null) v0 = f0.getLong(e0.getValue());
                        } catch (RuntimeException ignored) {
                        }
                        sb0.append(" | ").append(e0.getKey() == null ? "null"
                                : e0.getKey().getClass().getSimpleName()).append(" 任务值=").append(v0);
                    }
                }
                AE2Addon.LOGGER.info("{}（若这里的量级远大于下单量 → 源头是计划/任务值，不是结算）", sb0);
                ae2addon$taskSnapshotDone = true;
            }
            var sb = new StringBuilder("[ae2addon][stuck] ");
            // ⚠ 连"钩子有没有被调用"也记下来：如果连这条都不出现，说明卡住时
            // 任务迭代根本没在跑（即 CPU 侧已经不认为有活干），那是另一类问题。
            sb.append("迭代钩子在跑 job=").append(job == null ? "null" : "有");
            if (job == null) {
                // 任务结束：复位快照标志与自己记的账，下一单能重新开始
                ae2addon$taskSnapshotDone = false;
                ae2addon$resetDelivered();
                sb.append(" 待回收=").append(ae2addon$pendingSettle == null ? 0 : ae2addon$pendingSettle.size())
                        .append(" 迭代=").append(ae2addon$diagIterations);
                AE2Addon.LOGGER.info("{}", sb);
                return;
            }
            var tasks = ae2addon$getTasks(job);
            sb.append(" jobTasks=").append(tasks == null ? -1 : tasks.size());
            int shown = 0;
            if (tasks != null) {
                for (var entry : tasks.entrySet()) {
                    if (shown++ >= 3) break;
                    var pattern = entry.getKey();
                    long value = -1;
                    try {
                        var field = ae2addon$getTaskValueField(entry.getValue());
                        if (field != null) value = field.getLong(entry.getValue());
                    } catch (RuntimeException ignored) {
                    }
                    sb.append(" | ").append(pattern == null ? "null"
                                    : pattern.getClass().getSimpleName())
                            .append(" 任务值=").append(value)
                            .append(" 批量倍数=").append(ae2addon$getBatchMultiplier(pattern))
                            .append(" 本批提取=").append(ae2addon$settleBatchN);
                }
            }
            sb.append(" | 待回收=").append(ae2addon$pendingSettle == null ? 0 : ae2addon$pendingSettle.size());
            sb.append(" 本tick结算次数=").append(CraftingCompat.settleCallsUsedThisTick());
            sb.append(" 迭代=").append(ae2addon$diagIterations);
            sb.append(" waitingFor=").append(ae2addon$describeWaitingFor(job));
            AE2Addon.LOGGER.info("{}", sb);
        } catch (Throwable t) {
            AE2Addon.LOGGER.info("[ae2addon][stuck] 诊断失败: {}", t.toString());
        }
    }

    /**
     * 本批已补进 {@code job.waitingFor} 的期待量（按 key 累计）。每批结算开始时清空。
     * <p>
     * 用途：结算产出可能**超出样板声明的量**（副产物、无限精华、催化剂倍数放大的部分），
     * 而 AE2 的 `CraftingCpuLogic.insert` 只认 waitingFor 里的量 —— 差额必须补上，
     * 见 {@link #ae2addon$topUpWaitingFor}。
     */
    @Unique
    private KeyCounter ae2addon$creditedThisSettle;

    /**
     * 把「本批实际要交付的量」补齐进 {@code job.waitingFor}。
     * <p>
     * ⚠ 2026-09-19（sensei：无限精华 / 副产物）：AE2 的 `insert` 是
     * {@code waiting = waitingFor.extract(key, amount, SIMULATE); if (waiting <= 0) return 0;}
     * —— 期待量不足时**多出来的部分被静默丢弃**（且我们的 flush 不看返回值）。
     * 而 `QianJiPatternDetails.getOutputs()` 只有主产物，副产物与精华都不在其中，
     * 所以必须在这里按 outcome 逐 key 补差额（只补正差，避免重复记账）。
     */
    @Unique
    private void ae2addon$topUpWaitingFor(java.util.List<appeng.api.stacks.GenericStack> stacks) {
        var ledger = ae2addon$creditedThisSettle;
        if (ledger == null || stacks == null || stacks.isEmpty()) {
            return;
        }
        var topUp = new KeyCounter();
        for (var stack : stacks) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) {
                continue;
            }
            long have = ledger.get(stack.what());
            if (stack.amount() > have) {
                topUp.add(stack.what(), stack.amount() - have);
            }
        }
        if (topUp.isEmpty()) {
            return;
        }
        ae2addon$addWaitingFor(topUp);
        if (CraftingCompat.debugLogs && ae2addon$logHot()) {
            AE2Addon.LOGGER.info("[ae2addon][settle] 补齐 waitingFor: {} 项（副产物/精华/倍数放大的部分）",
                    topUp.size());
        }
    }

    /**
     * 把 expected 记进 {@code job.waitingFor}（反射；与 {@code describeWaitingFor} 同一套做法）。
     * <p>
     * ⚠ 2026-09-19：解耦虚拟结算后**必须自己记这一笔** —— 原来 expected 是外层 provider
     * 环节写进 waitingFor 的，而我们绕过了提取环节，外层拿不到 expected。
     * 少了它，`pendingSettle` 回收时无法冲抵 → 产物进不了账 → 任务永远差 N。
     */
    @Unique
    private void ae2addon$addWaitingFor(KeyCounter expected) {
        if (expected == null || expected.isEmpty()) {
            return;
        }
        try {
            var job = ae2addon$getJob();
            if (job == null) {
                return;
            }
            var wf = ae2addon$waitingForOf(job);
            if (wf == null) {
                return;   // waitingForOf 内部已经打过失败原因
            }
            for (var e : expected) {
                if (e.getKey() != null && e.getLongValue() > 0) {
                    wf.insert(e.getKey(), e.getLongValue(), appeng.api.config.Actionable.MODULATE);
                    // 同步进"本批已记账"台账（供 topUpWaitingFor 算差额，避免重复记账）
                    if (ae2addon$creditedThisSettle != null) {
                        ae2addon$creditedThisSettle.add(e.getKey(), e.getLongValue());
                    }
                }
            }
            if (CraftingCompat.debugLogs && ae2addon$logHot()) {
                // ⚠ 带上 job 身份与记账后的 waitingFor 内容 —— 与 [flush] 那条快照对起来，
                // 就能判断"补的账和回收用的是不是同一个 job / 同一个 key"
                AE2Addon.LOGGER.info(
                        "[ae2addon][settle] 记账: waitingFor += {} 项（本批 {} 份）job={} 账后={}",
                        expected.size(), ae2addon$settleBatchN, "@" + System.identityHashCode(job),
                        ae2addon$describeCounter(wf.list));
            }
        } catch (Throwable t) {
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.warn("[ae2addon][settle] waitingFor 记账异常: {}", t.toString());
            }
        }
    }

    /**
     * 取 {@code job.waitingFor}（{@code ExecutingCraftingJob} 的**包级私有**字段，
     * 真实类型是 {@code appeng.crafting.inv.ListCraftingInventory}）。
     * <p>
     * ⚠ 2026-09-19 踩坑（sensei：「任务还挂着，产物就返回了」）：原来这里判的是
     * {@code instanceof KeyCounter} —— 但 {@code ListCraftingInventory} **不继承**
     * KeyCounter（它只是持有 `public final KeyCounter list`）→ instanceof 恒 false
     * → 记账静默失败 → 根产物入不了账 → AE2 的 {@code CraftingCpuLogic.insert}
     * 读到 `waiting <= 0` 直接返回 0（不认账、不走 CraftingLink、不 finishJob）
     * → **产物被我们物理塞进网络，任务却永远挂着**（`[stuck]` 里 jobTasks=0 但 job=有）。
     * <p>
     * 取不到时**必须留下日志**，不能再静默失效（今天已经栽在"静默"上三次）。
     */
    @Unique
    private static appeng.crafting.inv.ListCraftingInventory ae2addon$waitingForOf(Object job) {
        if (job == null) {
            return null;
        }
        try {
            for (var f : job.getClass().getDeclaredFields()) {
                if (!f.getName().equals("waitingFor")) {
                    continue;
                }
                f.setAccessible(true);
                var v = f.get(job);
                if (v instanceof appeng.crafting.inv.ListCraftingInventory wf) {
                    return wf;
                }
                if (CraftingCompat.debugLogs) {
                    AE2Addon.LOGGER.warn(
                            "[ae2addon][settle] waitingFor 类型不是 ListCraftingInventory 而是 {} → 记账失效",
                            v == null ? "null" : v.getClass().getName());
                }
                return null;
            }
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.warn("[ae2addon][settle] {} 里找不到 waitingFor 字段 → 记账失效",
                        job.getClass().getName());
            }
        } catch (Throwable t) {
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.warn("[ae2addon][settle] 读 waitingFor 失败: {}", t.toString());
            }
        }
        return null;
    }

    /** KeyCounter 摘要（key=量，最多 4 项）——诊断用，与 waitingFor 摘要同一风格 */
    @Unique
    private static String ae2addon$describeCounter(KeyCounter counter) {
        if (counter == null) {
            return "null";
        }
        var sb = new StringBuilder("{");
        int n = 0;
        for (var e : counter) {
            if (n++ >= 4) {
                sb.append("…");
                break;
            }
            sb.append(e.getKey()).append('=').append(e.getLongValue()).append(' ');
        }
        return sb.append('}').toString();
    }

    /** job.waitingFor 摘要（key=量，最多 4 项；读不到就返回 ?） */
    @Unique
    private String ae2addon$describeWaitingFor(Object job) {        try {
            var wf = ae2addon$waitingForOf(job);
            if (wf == null) {
                return "?";
            }
            var sb = new StringBuilder("{");
            int n = 0;
            for (var e : wf.list) {
                if (n++ >= 4) {
                    sb.append("…");
                    break;
                }
                sb.append(e.getKey()).append('=').append(e.getLongValue()).append(' ');
            }
            return sb.append('}').toString();
        } catch (Throwable ignored) {
        }
        return "?";
    }

    @Unique
    private long ae2addon$getBatchMultiplier(IPatternDetails pattern) {
        var value = ae2addon$batchNext.get(ae2addon$batchKey(pattern));
        if (value != null) {
            return value;
        }
        // ⚠ 2026-09-19（sensei：大订单应**直接**用已累积的大 N，而不是从 2 爬）：
        // 新任务开始时把上一单爬到的 N 记进了 pendingSeed，这里**优先消费它**，
        // 让"学到的批量规模"跨订单生效。
        var seedKey = ae2addon$batchKey(pattern);
        if (seedKey != null) {
            var seeded = ae2addon$pendingSeed.remove(seedKey);
            if (seeded != null && seeded > 1) {
                AE2Addon.LOGGER.info("[ae2addon][种子] 本单起步 N={}（沿用上一单学到的规模）", seeded);
                ae2addon$batchNext.put(seedKey, seeded);
                return seeded;
            }
        }
        // 增殖配方（产物=输入同种）不继承共享经验：增殖批量 N 由本任务库存种子
        // 驱动（从 1 开始滚，逐轮翻倍），其他任务的大 N 起步会导致首轮提取失败
        // 震荡（2026-09-09）。
        if (ae2addon$isSelfReferentialPattern(pattern)) {
            return 1L;
        }
        // 无本地经验：继承共享经验（其他 lane 同产物已成功翻倍到的 N）
        if (ae2addon$sharedExpCap() <= 0) {
            return ae2addon$seedBatchMultiplier(); // config 关闭共享 → 用并行数起步
        }
        var key = ae2addon$patternKey(pattern);
        if (key != null) {
            var exp = ae2addon$sharedBatchExp.get(key);
            if (exp != null && exp > 1) {
                return Math.min(exp, ae2addon$sharedExpCap());
            }
        }
        // 没有任何经验 → 以并行数起步（2026-09-17 sensei：别再慢慢爬了）
        return ae2addon$seedBatchMultiplier();
    }

    /**
     * 新任务开始：把"每个样板已爬到的 N"留作起步种子，再清空。
     * <p>
     * ⚠ 2026-09-19（sensei：希望 9T 的单直接就用 3.4e10 的 N）：原来直接 `clear()`，
     * 于是每单都从 1 重爬。这里把旧值记进 {@code pendingSeed}，首个读取时作为起步值，
     * 让"已经学到的批量规模"跨订单保留。
     */
    @Unique
    private void ae2addon$carryOverBatchNext() {
        int kept = 0;
        for (var e : ae2addon$batchNext.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 1) {
                ae2addon$pendingSeed.put(e.getKey(), e.getValue());
                kept++;
            }
        }
        if (kept > 0) {
            AE2Addon.LOGGER.info("[ae2addon][种子] 新任务：保留 {} 个样板的已爬到 N 作为起步值", kept);
        }
        ae2addon$batchNext.clear();
    }

    /** 新任务起步种子（样板定义 → N）；读取一次后即消费 */
    @Unique
    private final Map<appeng.api.stacks.AEKey, Long> ae2addon$pendingSeed = new HashMap<>();

    /** 样板经验 key：产物 AEKey（同产物样板共享批量经验）；获取失败返回 null */    @Unique
    private static appeng.api.stacks.AEKey ae2addon$patternKey(IPatternDetails pattern) {
        if (pattern == null) {
            return null;
        }
        try {
            var outs = pattern.getOutputs();
            if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                return outs[0].what();
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    @Unique
    private void ae2addon$setBatchMultiplier(IPatternDetails pattern, long multiplier) {
        ae2addon$batchNext.put(ae2addon$batchKey(pattern), Math.max(1L, multiplier));
    }

    @Unique
    private void ae2addon$onBatchAccepted(IPatternDetails pattern, long multiplier) {
        if (multiplier <= 1) {
            ae2addon$diagProbeGrowth++;
        } else {
            ae2addon$diagBatchAccepted++;
            ae2addon$diagBatchMultiplierSum += multiplier;
            ae2addon$diagBatchCount++;
        }
        if (ae2addon$batchLockedFor(pattern)) {
            return;
        }
        // 2026-09-09 提速：增殖配方（产物=输入同种）不再强制逐次——批量 N 成功后
        // 正常翻倍（1→2→4…），配合种子回流翻倍实现指数滚雪球。共享经验对增殖
        // 也适用：新 lane 继承 N 后种子不足会自然回退收敛（提取失败减半）。
        long maxMult = ae2addon$effectiveBatchMax();
        // ⚠ 2026-09-19 sensei 澄清：**N 涨到上限是预期行为，不要限住它**。
        // 集成CPU 在线时"并行不限"⇒ effectiveBatchMax = config(Long.MAX_VALUE)，
        // 于是 N 一路翻倍到极大值——这正是"无限接收方一次吃完"想要的效果；
        // 每 tick 的实际工作量由 `qianjiSettleCap`（份数）与
        // `qianjiSettleCallsPerTick`（次数）两个闸门控制，与 N 的上限无关。
        long doubled = multiplier > maxMult / 2
                ? maxMult
                : multiplier * 2;
        ae2addon$batchNext.put(ae2addon$batchKey(pattern), Math.max(1L, doubled));
        // ⚠ 2026-09-19（sensei：集成CPU 下同一单内 N 不翻倍，只有"下一单"才变）：
        // 把翻倍前后的值与上限一起打出来，直接判定是"没翻倍"还是"被上限按住"。
        if (CraftingCompat.debugLogs && ae2addon$logHot()) {
            AE2Addon.LOGGER.info(
                    "[ae2addon][翻倍] multiplier={} → batchNext={}（有效上限={} 并行上限={} config上限={}）",
                    multiplier, doubled, maxMult, ae2addon$parallelCap(), ae2addon$batchMaxMultiplier());
        }
        // 共享经验：同产物其他 lane 的新任务继承此 N（clamp 上限防单次巨量起步）
        if (multiplier > 1 && ae2addon$sharedExpCap() > 0) {
            var key = ae2addon$patternKey(pattern);
            if (key != null) {
                ae2addon$sharedBatchExp.merge(key, multiplier, Math::max);
            }
        }
    }

    @Unique
    private void ae2addon$onBatchRejected(IPatternDetails pattern, long multiplier) {
        ae2addon$diagBatchRejected++;
        if (multiplier <= 2) {
            ae2addon$lockBatchFor(pattern);
            ae2addon$batchNext.put(ae2addon$batchKey(pattern), 1L);
        } else {
            ae2addon$batchNext.put(ae2addon$batchKey(pattern), Math.max(1L, multiplier / 2));
        }
    }

    @Unique
    private void ae2addon$clearBatchContext() {
        ae2addon$batchActive = false;
        ae2addon$batchBasePattern = null;
        ae2addon$batchScaledPattern = null;
        ae2addon$batchMultiplier = 1;
        ae2addon$batchCachedInputs = null;
        ae2addon$batchCachedOutputs = null;
        ae2addon$batchCachedContainerItems = null;
        // 每批重新记「实际提取份数」；-1 表示本批还没提取成功，
        // 结算侧据此回退到 batchMultiplier 而不是拿上一批的残留值（防数字错配）
        ae2addon$settleBatchN = -1L;
        ae2addon$settleLimitForced = false;
    }

    // ── 临时注册 scaled pattern（某些 provider 会校验 pattern 必须在可用列表里）──

    @Unique
    private boolean ae2addon$temporarilyRegisterScaledPattern(
            ICraftingProvider provider, IPatternDetails basePattern,
            IPatternDetails dispatchPattern) {
        try {
            List<IPatternDetails> availablePatterns = provider.getAvailablePatterns();
            if (availablePatterns == null
                    || availablePatterns.contains(dispatchPattern)) {
                return false;
            }
            for (var available : availablePatterns) {
                if (available == basePattern
                        || (available != null && available.getDefinition()
                                .equals(basePattern.getDefinition()))) {
                    availablePatterns.add(dispatchPattern);
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 不可变视图/快照：能推就推，推不了自然回退
        }
        return false;
    }

    @Unique
    private void ae2addon$removeTemporarilyAddedPattern(
            ICraftingProvider provider, IPatternDetails dispatchPattern) {
        try {
            List<IPatternDetails> availablePatterns = provider.getAvailablePatterns();
            if (availablePatterns == null) {
                return;
            }
            for (int index = availablePatterns.size() - 1; index >= 0; index--) {
                if (availablePatterns.get(index) == dispatchPattern) {
                    availablePatterns.remove(index);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // 清理失败不影响主流程
        }
    }

    // ── 任务值反射（AE2 无公开 API）──

    @Unique
    private long ae2addon$getTaskValue(IPatternDetails pattern) {
        var currentJob = ae2addon$getJob();
        if (currentJob == null || !ae2addon$reflectionAvailable) {
            return 1;
        }
        try {
            var task = ae2addon$getTasks(currentJob).get(pattern);
            if (task == null) {
                return 1;
            }
            var field = ae2addon$getTaskValueField(task);
            return field.getLong(task);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ae2addon$disableReflection(exception);
            return 1;
        }
    }

    @Unique
    private void ae2addon$decrementTaskValue(IPatternDetails pattern, long amount) {
        ae2addon$diagDecrementCalls++;
        if (amount <= 0 || !ae2addon$reflectionAvailable) {
            ae2addon$diagDecrementSkipped++;
            return;
        }
        var currentJob = ae2addon$getJob();
        if (currentJob == null) {
            ae2addon$diagDecrementSkipped++;
            return;
        }
        try {
            var task = ae2addon$getTasks(currentJob).get(pattern);
            if (task == null) {
                ae2addon$diagDecrementSkipped++;
                return;
            }
            var field = ae2addon$getTaskValueField(task);
            long current = field.getLong(task);
            if (current > amount) {
                field.setLong(task, current - amount);
                ae2addon$diagDecrementApplied++;
            } else {
                // ⚠ 2026-09-18 晚：**只有本批确实没提取到份数**（settleBatchN<=0）才回退到 batchMultiplier；
                // 只要能拿到"本批实际提取份数"，就一律以它为准（末批 7224 vs 整批 8194 就是这里分出来的）。
                field.setLong(task, 0L);
                ae2addon$diagDecrementApplied++;
                if (CraftingCompat.debugLogs) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][task减] 残值 {} ≤ 本批交付 {} → 置 0 收尾（本批已超额覆盖）",
                            current, amount);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ae2addon$diagDecrementSkipped++;
            ae2addon$disableReflection(exception);
        }
    }

    /** 任务值递减的计数（v152 诊断：判定"任务值到底有没有被减"） */
    @Unique
    private long ae2addon$diagDecrementCalls;
    @Unique
    private long ae2addon$diagDecrementApplied;
    @Unique
    private long ae2addon$diagDecrementSkipped;
    @Unique
    private long ae2addon$diagDecrementLogged;

    @Unique
    @SuppressWarnings("unchecked")
    private static Map<IPatternDetails, Object> ae2addon$getTasks(Object currentJob) {
        try {
            var field = ae2addon$tasksField;
            if (field == null) {
                field = currentJob.getClass().getDeclaredField("tasks");
                field.setAccessible(true);
                ae2addon$tasksField = field;
            }
            return (Map<IPatternDetails, Object>) field.get(currentJob);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ae2addon$disableReflection(exception);
            return Map.of();
        }
    }

    @Unique
    private static Field ae2addon$getTaskValueField(Object task)
            throws NoSuchFieldException {
        var field = ae2addon$taskValueField;
        if (field == null) {
            field = task.getClass().getDeclaredField("value");
            field.setAccessible(true);
            ae2addon$taskValueField = field;
        }
        return field;
    }

    @Unique
    private static void ae2addon$disableReflection(Exception exception) {
        ae2addon$reflectionAvailable = false;
        if (!ae2addon$reflectionFailureLogged) {
            ae2addon$reflectionFailureLogged = true;
            AE2Addon.LOGGER.error(
                    "[ae2addon] AE2 任务反射不可用，批量推送已禁用，回退逐条推送", exception);
        }
    }

    // ── 集成 CPU 识别 ──

    @Unique
    private static boolean ae2addon$isIntegratedCpu(CraftingCPUCluster cluster) {
        try {
            var iterator = cluster.getBlockEntities();
            while (iterator.hasNext()) {
                if (iterator.next() instanceof IntegratedCPUBE) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 簇尚未成型/结构异常时保守返回 false，不影响原版行为
        }
        return false;
    }
}
