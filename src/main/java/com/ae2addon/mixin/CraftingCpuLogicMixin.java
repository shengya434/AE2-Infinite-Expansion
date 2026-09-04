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
    private final Map<IPatternDetails, Long> ae2addon$batchNext =
            new HashMap<>();
    @Unique
    private final Map<IPatternDetails, Boolean> ae2addon$batchLocked =
            new HashMap<>();

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
        if (ae2addon$budgetActive) {
            Object currentJob = ae2addon$getJob();
            if (ae2addon$diagLastJob != currentJob) {
                // 新任务：重置批量自适应状态 + 清除无限接口的推送归属记录
                // （材料保留在接口=正常交付；归属只服务于当前任务的取消回退）
                ae2addon$diagLastJob = currentJob;
                ae2addon$batchNext.clear();
                ae2addon$batchLocked.clear();
                if (currentJob != null) {
                    com.ae2addon.block.InfiniteInterfaceBE.resetPushedFor(cluster);
                }
            }
            ae2addon$budgetNanos = ae2addon$getAdaptiveBudgetNanos();
            ae2addon$deadlineNanos = System.nanoTime() + ae2addon$budgetNanos;
            long tick = TickHandler.instance().getCurrentTick();
            if (ae2addon$diagLastTick == Long.MIN_VALUE) {
                ae2addon$diagLastTick = tick;
            } else if (tick - ae2addon$diagLastTick >= AE2ADDON_DIAG_LOG_INTERVAL_TICKS) {
                long span = tick - ae2addon$diagLastTick;
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
                    long headroom = Math.max(
                            0L, AE2ADDON_DISPATCH_TARGET_TICK_NANOS - averageTickNanos);
                    return Math.min(AE2ADDON_DISPATCH_MAX_BUDGET_NANOS,
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
        if (ae2addon$pendingSettle != null && !ae2addon$pendingSettle.isEmpty()) {
            ae2addon$flushPendingSettle();
        }
        // 虚拟结算根产物 finishJob 后 job 已置 null：终止任务迭代防 NPE
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
        if (!sameTickSamePattern) {
            ae2addon$lastExtractTick = currentTick;
            ae2addon$lastExtractPattern = patternDetails;
            ae2addon$clearBatchContext();
            // 上次批量尝试没有任何 provider 接受 → 收敛批量 N（仅新 tick 首次提取时）
            if (ae2addon$batchPendingMultiplier > 1) {
                long previous = ae2addon$batchPendingMultiplier;
                ae2addon$batchPendingMultiplier = -1;
                if (previous <= 2) {
                    ae2addon$batchLocked.put(patternDetails, Boolean.TRUE);
                    ae2addon$batchNext.put(patternDetails, 1L);
                } else {
                    ae2addon$batchNext.put(patternDetails, Math.max(1L, previous / 2));
                }
            }
        }
        if (!ae2addon$diagExtractLogged) {
            ae2addon$diagExtractLogged = true;
            AE2Addon.LOGGER.info("[ae2addon] extractBatch 被调用！pattern={}, budgetActive={}",
                    patternDetails == null ? "null" : patternDetails.getClass().getSimpleName(),
                    ae2addon$budgetActive);
        }
        if (!ae2addon$budgetActive || patternDetails == null || inventory == null) {
            // 非我们 CPU（原版）：直接调用原始静态方法（原版行为）
            return CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                    level, expectedOutputs, expectedContainerItems);
        }

        long taskRemaining = ae2addon$getTaskValue(patternDetails);
        if (taskRemaining <= 1) {
            ae2addon$diagTaskValueFallback++;
        }
        long n = Math.min(ae2addon$getBatchMultiplier(patternDetails), taskRemaining);
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
                // M1c（2026-09-04）：虚拟结算无真实装配瓶颈 → 一次提取全部任务材料，
                // 整层瞬时结算（ScaledPattern multiplyExact 防溢出，溢出自动回退 1×）
                n = Math.min(taskRemaining, ae2addon$batchMaxMultiplier());
                if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][debug] 合成族样板虚拟结算全量: pattern={} n={} taskRemaining={}",
                            patternDetails, n, taskRemaining);
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
        if (n <= 1) {
            // 我们 CPU：直接调原始静态方法，绕开 Omni 的 extractBatch handler
            // （它会对 scaled 输出算 waitingFor 余量，N 大时 reinject+null → 批量被误判失败锁 1）
            var result1x = CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                    level, expectedOutputs, expectedContainerItems);
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
            // 2× 都提取不出：基本是库存/接收能力不足，锁定逐条避免震荡
            ae2addon$batchLocked.put(patternDetails, Boolean.TRUE);
            ae2addon$setBatchMultiplier(patternDetails, 1);
        } else {
            ae2addon$setBatchMultiplier(patternDetails, Math.max(1, n / 2));
        }
        expectedOutputs.reset();
        expectedContainerItems.reset();
        return CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                level, expectedOutputs, expectedContainerItems);
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
        com.ae2addon.block.InfiniteInterfaceBE.returnPushedFor(cluster);
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
        // ── 虚拟结算（v0.3 M1）：合成类样板节点不真实装配 ──
        // 材料已在 extract 阶段从 crafting storage 提取（销毁 ✓）；产物走原版 insert
        // 回收通道瞬时注入：根产物→CraftingLink+finishJob；中间产物→crafting storage 供上层。
        if (ae2addon$virtualSettleActive(patternDetails)) {
            return ae2addon$virtualSettle(patternDetails);
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
            if (ae2addon$budgetActive && accepted && patternDetails != null) {
                ae2addon$onBatchAccepted(patternDetails, 1L);
            } else if (!accepted && patternDetails != null) {
                ae2addon$recordStuck(patternDetails);
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
     * 判定（v0.3 M3）：仅限合成族样板（合成/切石/锻造，非处理类）；且当前 CPU 簇的
     * 集成 CPU（主簇或虚拟 lane）挂了装配处理器模块并声明了该样板（样板槽白名单）
     * 才虚拟结算。无模块/未声明 → 一律真实合成。
     */
    @Unique
    private boolean ae2addon$virtualSettleActive(IPatternDetails patternDetails) {
        if (patternDetails == null || !ae2addon$isCraftingPattern(patternDetails)) {
            return false;
        }
        var owner = com.ae2addon.block.IntegratedCPURegistry.ownerOf(cluster);
        var module = com.ae2addon.block.AssemblerRegistry.moduleFor(owner);
        boolean declared = module != null && module.declares(patternDetails);
        if (CraftingCompat.debugLogs) {
            // 诊断（2026-09-04 锻造/切石不虚拟排查）：环节日志节流
            long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
            if (ae2addon$diagSettleTick != tick) {
                ae2addon$diagSettleTick = tick;
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

    @Unique
    private long ae2addon$diagSettleTick = Long.MIN_VALUE;

    /**
     * 虚拟结算：不真实装配（跳过 provider.pushPattern），材料已由 extract 阶段扣出
     * （= 销毁）。产物登记到 pendingSettle，返回 true 让外层记账（waitingFor += 产物），
     * 待任务循环 hasNext 处（记账后）调原版 insert 回收：
     * - 根产物（finalOutput 匹配）→ CraftingLink 送达请求者 + remainingAmount 归零 + finishJob
     * - 中间产物 → crafting storage（inventory），上游节点 extract 直接命中
     * N× 结算（M1c）：批量上下文激活时按 batchMultiplier 一次结算 N 份（产物×N 入
     * pendingSettle，任务值补减 N−1）；1× 为回退/非批量路径。
     */
    @Unique
    private boolean ae2addon$virtualSettle(IPatternDetails patternDetails) {
        try {
            long n = 1;
            if (ae2addon$batchActive && ae2addon$batchBasePattern != null
                    && ae2addon$batchBasePattern.equals(patternDetails)
                    && ae2addon$batchMultiplier > 1) {
                n = ae2addon$batchMultiplier;
            }
            var outputs = patternDetails.getOutputs();
            if (outputs == null || outputs.length == 0) {
                return false;
            }
            KeyCounter pending = ae2addon$pendingSettle;
            if (pending == null) {
                pending = new KeyCounter();
                ae2addon$pendingSettle = pending;
            }
            boolean settledAny = false;
            for (var out : outputs) {
                if (out == null || out.what() == null || out.amount() <= 0) {
                    continue;
                }
                settledAny = true;
                // N× 产物：ScaledPattern 构造时对 inputs/outputs 均 multiplyExact 验溢，
                // 能走到批量提取说明 n×amount 未溢出（1× 路径无溢出问题）
                pending.add(out.what(), out.amount() * n);
            }
            if (!settledAny) {
                return false;
            }
            if (n > 1) {
                // 任务值补减 n−1（AE2 外层还会 −1，共 −n → 归零移除）；decrementTaskValue
                // 自带 current<=amount 保护（归零交给 AE2），不重复清批量上下文——
                // 同 tick 缓存提取二次 push 时仍需按同 N 结算（材料已按 N× 扣出）
                ae2addon$decrementTaskValue(patternDetails, n - 1);
            }
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.info(
                        "[ae2addon][settle] 虚拟结算: pattern={} N={} 产物{}种 → 待回收（外层记账后 insert）",
                        patternDetails.getClass().getSimpleName(), n, outputs.length);
            }
            return true;
        } catch (Throwable t) {
            if (CraftingCompat.debugLogs) {
                AE2Addon.LOGGER.warn("[ae2addon][settle] 虚拟结算异常: {}", t.toString());
            }
            return false;
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
        try {
            AEKey rootKey = ae2addon$getFinalOutputKey();
            var logic = cluster.craftingLogic;
            var grid = cluster.getGrid();
            var networkStorage = grid == null ? null : grid.getStorageService().getInventory();
            for (var entry : pending) {
                if (entry == null || entry.getKey() == null || entry.getLongValue() <= 0) {
                    continue;
                }
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                boolean isRoot = rootKey != null && rootKey.equals(key);
                if (isRoot && networkStorage != null) {
                    // 物理入网：requester（接口/终端）交割时从网络提取产物
                    // ⚠️ AE2 insert 返回「已插入量」（非剩余量）——2026-09-04 20:40 修正误报
                    long insertedAmt = networkStorage.insert(key, amount,
                            appeng.api.config.Actionable.MODULATE, cluster.getSrc());
                    if (CraftingCompat.debugLogs) {
                        AE2Addon.LOGGER.info(
                                "[ae2addon][settle] 物理入网: key={} 期望{} 实插{} root=true",
                                key, amount, insertedAmt);
                    }
                    if (insertedAmt < amount && CraftingCompat.debugLogs) {
                        AE2Addon.LOGGER.warn(
                                "[ae2addon][settle] 根产物入网部分失败: key={} 已插{} 期望{}（网络满？）",
                                key, insertedAmt, amount);
                    }
                } else if (CraftingCompat.debugLogs && networkStorage != null) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][settle] 中间产物(不入网): key={} 量={} rootKey={}",
                            key, amount, rootKey);
                }
                // 账务：waitingFor 冲抵 + 根产物→link 交割/finishJob；中间产物→crafting storage
                logic.insert(key, amount, appeng.api.config.Actionable.MODULATE);
            }
            if (CraftingCompat.debugLogs) {
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
        }
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

    @Unique
    private long ae2addon$getBatchMultiplier(IPatternDetails pattern) {
        var value = ae2addon$batchNext.get(pattern);
        if (value != null) {
            return value;
        }
        // 无本地经验：继承共享经验（其他 lane 同产物已成功翻倍到的 N）
        if (ae2addon$sharedExpCap() <= 0) {
            return 1L; // config 关闭共享（sharedExpCap=0）
        }
        var key = ae2addon$patternKey(pattern);
        if (key != null) {
            var exp = ae2addon$sharedBatchExp.get(key);
            if (exp != null && exp > 1) {
                return Math.min(exp, ae2addon$sharedExpCap());
            }
        }
        return 1L;
    }

    /** 样板经验 key：产物 AEKey（同产物样板共享批量经验）；获取失败返回 null */
    @Unique
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
        ae2addon$batchNext.put(pattern, Math.max(1L, multiplier));
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
        if (Boolean.TRUE.equals(ae2addon$batchLocked.get(pattern))) {
            return;
        }
        long maxMult = ae2addon$batchMaxMultiplier();
        long doubled = multiplier > maxMult / 2
                ? maxMult
                : multiplier * 2;
        ae2addon$batchNext.put(pattern, Math.max(1L, doubled));
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
            ae2addon$batchLocked.put(pattern, Boolean.TRUE);
            ae2addon$batchNext.put(pattern, 1L);
        } else {
            ae2addon$batchNext.put(pattern, Math.max(1L, multiplier / 2));
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
        if (amount <= 0 || !ae2addon$reflectionAvailable) {
            return;
        }
        var currentJob = ae2addon$getJob();
        if (currentJob == null) {
            return;
        }
        try {
            var task = ae2addon$getTasks(currentJob).get(pattern);
            if (task == null) {
                return;
            }
            var field = ae2addon$getTaskValueField(task);
            long current = field.getLong(task);
            if (current > amount) {
                field.setLong(task, current - amount);
            } else {
                // 任务将归零，交给 AE2 自身的 value-- 处理（避免并发双扣）
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ae2addon$disableReflection(exception);
        }
    }

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
