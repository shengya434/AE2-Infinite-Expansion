package com.ae2addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.inv.ICraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.ae2addon.AE2Addon;
import com.ae2addon.block.DebugTrashRegistry;
import com.ae2addon.block.IntegratedCPUBE;
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
import java.util.IdentityHashMap;
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
     * 预算 = clamp(45ms − 服务器MSPT, 1ms, 32ms)。
     */
    @Unique
    private static final long AE2ADDON_DISPATCH_MAX_BUDGET_NANOS = 32_000_000L;
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
     */
    @Unique
    private static final long AE2ADDON_BATCH_MAX_MULTIPLIER = Long.MAX_VALUE;

    /**
     * 诊断日志间隔（tick）。
     */
    @Unique
    private static final long AE2ADDON_DIAG_LOG_INTERVAL_TICKS = 100;

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
    @Unique
    private long ae2addon$diagProbeGrowth;
    @Unique
    private String ae2addon$diagBatchFailProvider = "-";

    // 批量状态（每个 pattern 的自适应 N）
    @Unique
    private final Map<IPatternDetails, Long> ae2addon$batchNext =
            new IdentityHashMap<>();
    @Unique
    private final Map<IPatternDetails, Boolean> ae2addon$batchLocked =
            new IdentityHashMap<>();

    // 当前批量上下文（一次提取 → 一次 push 之间传递）
    @Unique
    private boolean ae2addon$batchActive;
    @Unique
    private IPatternDetails ae2addon$batchBasePattern;
    @Unique
    private ScaledPattern ae2addon$batchScaledPattern;
    @Unique
    private long ae2addon$batchMultiplier;

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
        ae2addon$budgetActive = integrated;
        if (ae2addon$budgetActive) {
            Object currentJob = ae2addon$getJob();
            if (ae2addon$diagLastJob != currentJob) {
                // 新任务：重置批量自适应状态，避免旧任务的 N/锁定泄漏
                ae2addon$diagLastJob = currentJob;
                ae2addon$batchNext.clear();
                ae2addon$batchLocked.clear();
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

    /**
     * 任务循环（job.tasks.entrySet() 的迭代）：超时即终止整个任务的遍历。
     */
    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z", ordinal = 0),
            require = 0)
    private boolean ae2addon$limitTaskIteration(Iterator<?> iterator) {
        com.ae2addon.crafting.CraftingCompat.timeSliceActive = true;
        if (ae2addon$budgetActive) {
            ae2addon$diagIterations++;
            if (System.nanoTime() >= ae2addon$deadlineNanos) {
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
        if (ae2addon$budgetActive) {
            ae2addon$diagIterations++;
            if (System.nanoTime() >= ae2addon$deadlineNanos) {
                return false;
            }
        }
        return iterator.hasNext();
    }

    /**
     * getProviders 结果追加 Debug 销毁方块：DebugTrashBE 不注册任何 pattern，
     * 通过这里成为任意 pattern 的接收者（同一网络内），用于测试无限吞吐。
     */
    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;getProviders("
                            + "Lappeng/api/crafting/IPatternDetails;)"
                            + "Ljava/lang/Iterable;"),
            require = 0)
    private Iterable<ICraftingProvider> ae2addon$appendDebugTrashProviders(
            CraftingService craftingService, IPatternDetails patternDetails) {
        var providers = craftingService.getProviders(patternDetails);
        var debugProviders = DebugTrashRegistry.collectFor(craftingService);
        if (debugProviders.isEmpty()) {
            return providers;
        }
        var combined = new ArrayList<ICraftingProvider>();
        providers.forEach(combined::add);
        combined.addAll(debugProviders);
        return combined;
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
        boolean sameTickSamePattern = ae2addon$lastExtractTick == currentTick
                && ae2addon$lastExtractPattern == patternDetails;
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
        if (n <= 1) {
            // 我们 CPU：直接调原始静态方法，绕开 Omni 的 extractBatch handler
            // （它会对 scaled 输出算 waitingFor 余量，N 大时 reinject+null → 批量被误判失败锁 1）
            return CraftingCpuHelper.extractPatternInputs(patternDetails, inventory,
                    level, expectedOutputs, expectedContainerItems);
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
        if (!ae2addon$batchActive
                || ae2addon$batchBasePattern != patternDetails
                || ae2addon$batchScaledPattern == null
                || ae2addon$batchMultiplier <= 1) {
            boolean accepted = provider.pushPattern(patternDetails, inputs);
            // 1× 成功也是批量探测的成功：翻倍 N，让同一 tick 内后续提取
            // 直接尝试 2×/4×/8×... 指数暴涨，对无限消费型接收方瞬间全发
            if (ae2addon$budgetActive && accepted && patternDetails != null) {
                ae2addon$onBatchAccepted(patternDetails, 1L);
            }
            return accepted;
        }

        IPatternDetails dispatchPattern = ae2addon$batchScaledPattern;
        boolean temporarilyAdded = ae2addon$temporarilyRegisterScaledPattern(
                provider, patternDetails, dispatchPattern);
        boolean accepted;
        try {
            accepted = provider.pushPattern(dispatchPattern, inputs);
        } finally {
            if (temporarilyAdded) {
                ae2addon$removeTemporarilyAddedPattern(provider, dispatchPattern);
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
        }
        return accepted;
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

    // ── 批量自适应控制器 ──

    @Unique
    private long ae2addon$getBatchMultiplier(IPatternDetails pattern) {
        var value = ae2addon$batchNext.get(pattern);
        return value == null ? 1L : value;
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
        long doubled = multiplier > AE2ADDON_BATCH_MAX_MULTIPLIER / 2
                ? AE2ADDON_BATCH_MAX_MULTIPLIER
                : multiplier * 2;
        ae2addon$batchNext.put(pattern, Math.max(1L, doubled));
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
