package com.ae2addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ICraftingInventory;
import appeng.hooks.ticking.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.ae2addon.AE2Addon;
import com.ae2addon.block.DebugTrashRegistry;
import com.ae2addon.block.IntegratedCPUBE;
import com.ae2addon.crafting.ScaledPattern;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
@Mixin(value = CraftingCpuLogic.class, priority = 1100, remap = false)
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

    @Shadow
    private ExecutingCraftingJob job;

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

    @Inject(method = "tickCraftingLogic", at = @At("HEAD"))
    private void ae2addon$beginDispatchBudget(IEnergyService energyService,
            CraftingService craftingService, CallbackInfo callback) {
        boolean integrated = ae2addon$isIntegratedCpu(cluster);
        if (!ae2addon$diagLoaded) {
            ae2addon$diagLoaded = true;
            AE2Addon.LOGGER.info("[ae2addon] CraftingCpuLogicMixin 已生效，集成CPU检测={}", integrated);
        }
        ae2addon$budgetActive = integrated;
        if (ae2addon$budgetActive) {
            if (ae2addon$diagLastJob != job) {
                // 新任务：重置批量自适应状态，避免旧任务的 N/锁定泄漏
                ae2addon$diagLastJob = job;
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
     * <p>
     * 用 @ModifyExpressionValue（而非 @Redirect）以便与 OmniSequence 的
     * 同类注入链式共存：priority 1100 &gt; Omni 的默认 1000，我们的 handler
     * 在链的最外层，最后决定返回值。
     */
    @ModifyExpressionValue(method = "executeCrafting",
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z", ordinal = 0))
    private boolean ae2addon$limitTaskIteration(boolean original) {
        if (ae2addon$budgetActive) {
            ae2addon$diagIterations++;
            if (System.nanoTime() >= ae2addon$deadlineNanos) {
                return false;
            }
        }
        return original;
    }

    /**
     * provider 循环（craftingService.getProviders(details) 的迭代）：
     * 超时即停止向机器推送，本 tick 收工。
     */
    @ModifyExpressionValue(method = "executeCrafting",
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;hasNext()Z", ordinal = 1))
    private boolean ae2addon$limitProviderIteration(boolean original) {
        if (ae2addon$budgetActive) {
            ae2addon$diagIterations++;
            if (System.nanoTime() >= ae2addon$deadlineNanos) {
                return false;
            }
        }
        return original;
    }

    /**
     * getProviders 结果追加 Debug 销毁方块：DebugTrashBE 不注册任何 pattern，
     * 通过这里成为任意 pattern 的接收者（同一网络内），用于测试无限吞吐。
     * <p>
     * 用 @WrapOperation（链式）：priority 1100 在最外层，先调用原始链
     * （Omni 的 provider 选择在其中执行），再把 DebugTrash 追加到结果尾部。
     */
    @WrapOperation(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/me/service/CraftingService;getProviders("
                            + "Lappeng/api/crafting/IPatternDetails;)"
                            + "Ljava/lang/Iterable;"))
    private Iterable<ICraftingProvider> ae2addon$appendDebugTrashProviders(
            CraftingService craftingService, IPatternDetails patternDetails,
            Operation<Iterable<ICraftingProvider>> original) {
        // 我们 CPU：直接调原始方法绕开 Omni 链；非我们 CPU 走链
        var providers = ae2addon$budgetActive
                ? craftingService.getProviders(patternDetails)
                : original.call(craftingService, patternDetails);
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
     * <p>
     * @WrapOperation 链式：priority 1100 外层，先走原始链（Omni 的批量提取
     * 在其中执行，对我们 CPU 簇它不会拦），再执行我们自己的 N× 提取。
     */
    @WrapOperation(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs("
                            + "Lappeng/api/crafting/IPatternDetails;"
                            + "Lappeng/crafting/inv/ICraftingInventory;"
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lappeng/api/stacks/KeyCounter;"
                            + "Lappeng/api/stacks/KeyCounter;"
                            + ")[Lappeng/api/stacks/KeyCounter;"))
    private KeyCounter[] ae2addon$extractBatch(IPatternDetails patternDetails,
            ICraftingInventory inventory, Level level, KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems,
            Operation<KeyCounter[]> original) {
        ae2addon$clearBatchContext();
        // 上次批量尝试没有任何 provider 接受 → 收敛批量 N
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
        if (!ae2addon$diagExtractLogged) {
            ae2addon$diagExtractLogged = true;
            AE2Addon.LOGGER.info("[ae2addon] extractBatch 被调用！pattern={}, budgetActive={}",
                    patternDetails == null ? "null" : patternDetails.getClass().getSimpleName(),
                    ae2addon$budgetActive);
        }
        if (!ae2addon$budgetActive || patternDetails == null || inventory == null) {
            // 非我们 CPU（原版/Omni）：交给链上的其他 handler（Omni 的批量逻辑）
            return original.call(patternDetails, inventory, level,
                    expectedOutputs, expectedContainerItems);
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
     * <p>
     * @WrapOperation 链式：外层先调原始链（Omni 的 pushBatch 在其中执行，
     * 对我们 CPU 簇它不会拦），再执行我们自己的批量 push。
     */
    @WrapOperation(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/networking/crafting/ICraftingProvider;"
                            + "pushPattern(Lappeng/api/crafting/IPatternDetails;"
                            + "[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean ae2addon$pushBatch(ICraftingProvider provider,
            IPatternDetails patternDetails, KeyCounter[] inputs,
            Operation<Boolean> original) {
        ae2addon$diagPushCalls++;
        if (!ae2addon$batchActive
                || ae2addon$batchBasePattern != patternDetails
                || ae2addon$batchScaledPattern == null
                || ae2addon$batchMultiplier <= 1) {
            // 非批量路径：非我们 CPU 走链（Omni 处理），我们 CPU 直接调接口方法
            boolean accepted = ae2addon$budgetActive
                    ? provider.pushPattern(patternDetails, inputs)
                    : original.call(provider, patternDetails, inputs);
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
            // 批量 push：直接调接口方法，绕开 Omni 的 pushBatch handler
            // （它不认识我们的 ScaledPattern，可能走它的批量上下文导致误判）
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
     * OmniSequence 的 {@code limitUnboundedCraftingBurst} 对非自家 CPU 的簇
     * 会把 getCoProcessors() 砍到 255（coProcessors &gt; 256 时），我们的集成 CPU
     * 的 65536 线程就是这么被吃的。
     * <p>
     * 这里用 priority 1100（&gt; Omni 默认 1000）的 @ModifyExpressionValue 包在链的
     * 最外层：先让 Omni 的 handler 执行，再对我们的 CPU 簇强制恢复 MAX_VALUE−1
     * （与 Omni 自家 CPU 相同的哨兵值，显示 ∞ 时也兼容）。
     */
    @ModifyExpressionValue(method = "tickCraftingLogic",
            at = @At(value = "INVOKE",
                    target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;"
                            + "getCoProcessors()I"))
    private int ae2addon$protectCoProcessors(int coProcessors) {
        if (ae2addon$budgetActive && coProcessors != Integer.MAX_VALUE - 1) {
            return Integer.MAX_VALUE - 1;
        }
        return coProcessors;
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
        var currentJob = job;
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
        var currentJob = job;
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
    private static Map<IPatternDetails, Object> ae2addon$getTasks(ExecutingCraftingJob currentJob) {
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
