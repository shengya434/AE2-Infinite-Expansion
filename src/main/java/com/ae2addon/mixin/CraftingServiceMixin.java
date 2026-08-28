package com.ae2addon.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.ae2addon.api.IntegratedCraftingServiceBridge;
import com.ae2addon.block.IntegratedCPURegistry;
import com.ae2addon.crafting.BatchedCraftingOrder;
import com.ae2addon.crafting.BatchedCraftingQueue;
import com.ae2addon.crafting.CraftingCompat;
import com.ae2addon.crafting.RequirementCalculator;
import com.ae2addon.util.ChatLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.math.BigInteger;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * 量子分裂线程（虚拟 CPU lane）：把集成 CPU 的虚拟 lane 注册进 AE2 的
 * CraftingService CPU 集合，实现多订单并行；目标 CPU 忙时自动改用空闲 lane。
 * 思路来自 OmniSequence-Transfinite 的 OmniCraftingServiceMixin。
 */
@Mixin(value = CraftingService.class, remap = false, priority = 1200)
public abstract class CraftingServiceMixin implements IntegratedCraftingServiceBridge {

    /**
     * 2026-08-27 重要修正：priority 语义 = 高者后应用、后执行。
     * 此前设 2500（> AE2-VM 的 2000）想让模拟拦截抢在 VM 前，实际相反：
     * 我们后执行，VM 先 cancel → 我们让路 → 巨型订单绕过拆批（sensei 实测
     * 20:25：有 AE2-VM 时无「模拟拦截」日志、patternTimes=Long.MAX 的
     * oak_stairs/blackstone 订单未拆批）。改回 1200（< VM 2000）：
     * 我们先应用先执行 → 超限拦截 cancel → VM 后执行看到 isCancelled 尊重（
     * VM 字节码第 3 步 callback.isCancelled() → return，反编译确认）。
     */

    @Shadow
    @Final
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow
    @Final
    private IGrid grid;

    /**
     * 超限订单上下文缓存（2026-08-21）：模拟拦截返回真 CraftingPlan 后，
     * 用 IdentityHashMap 按实例缓存 grid/level/simNode/perBatch/truncated
     * （[5]=uuid 去重，[6]=截断标志），
     * submitJob 时取出识别为巨型订单（原 DeferredCraftingPlan 类型识别
     * 已被 GTL 界面 mixin 强转 CraftingPlan 崩溃，废弃）。
     * key 是模拟拦截创建的真 CraftingPlan 实例（提交时同实例传入）。
     */
    @Unique
    private static final java.util.IdentityHashMap<ICraftingPlan, Object[]>
            ae2addon$deferredContexts = new java.util.IdentityHashMap<>();

    /**
     * 上下文缓存容量上限（防泄漏：计划被取消/requester 消失时条目永不 remove，
     * 超限直接清空——条目生命周期短，偶尔重拦截一次无害）。
     */
    @Unique
    private static final int AE2ADDON_DEFERRED_CONTEXT_MAX = 4096;

    /** 虚拟 CPU 刷新节流：至少间隔 tick 数（submitJob/updateCPUClusters 高频触发，
     *  批量订单每秒提交数百次，每次全量刷新注册表太浪费）。 */
    @Unique
    private static final long AE2ADDON_CPU_REFRESH_INTERVAL_TICKS = 20;

    @Unique
    private long ae2addon$lastCpuRefreshTick = Long.MIN_VALUE;

    @Inject(method = "updateCPUClusters", at = @At("RETURN"), require = 0)
    private void ae2addon$registerVirtualCpus(CallbackInfo callback) {
        // 事件驱动（updateList 脏标记）触发，不节流：CPU 列表变化时全量刷新。
        ae2addon$refreshIntegratedCpus(false);
    }

    /**
     * 每 tick lane 维护（2026-08-22）：必须在 craftingCPUClusters 迭代（
     * tickCraftingLogic）<b>之前</b>执行——迭代中 add/remove 会
     * ConcurrentModificationException（15:26 崩溃实锤：把 refreshLanes 挂进
     * tickCraftingLogic HEAD 导致 onServerEndTick 迭代期间改集合）。
     * updateCPUClusters 是事件驱动的（updateList），稳态不触发，所以这里
     * 是唯一的每 tick 钩子。
     */
    @Inject(method = "onServerEndTick", at = @At("HEAD"), require = 0)
    private void ae2addon$tickLaneMaintenance(CallbackInfo callback) {
        for (var blockEntity : IntegratedCPURegistry.all()) {
            if (blockEntity.isRemoved() || !blockEntity.isFormed()) {
                continue;
            }
            blockEntity.refreshLanes();
        }
    }

    @Inject(method = "submitJob", at = @At("RETURN"), require = 0)
    private void ae2addon$keepSpareCpuAfterSubmit(ICraftingPlan job,
            ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        // 批量提交高频路径：节流到 20 tick 一次（提交前 updateCPUClusters 已刷新过）
        ae2addon$refreshIntegratedCpus(true);
    }

    /**
     * 巨型订单自动分批：下单材料需求超 long 记账上限（模拟已溢出为负值）
     * 时，取消原提交，用 BigInteger 需求树重新估算并拆成多个安全批次，
     * 首批立即提交（返回真实 link），剩余批次入队串行执行。
     * 也处理模拟阶段拦截产生的 DeferredCraftingPlan（延迟计划）。
     */
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, require = 0)
    private void ae2addon$batchOversizedOrder(ICraftingPlan job,
            ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (job == null || BatchedCraftingQueue.dispatchInProgress) {
            if (CraftingCompat.debugLogs) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] submitJob早退 job={} sim={} dispatch={}",
                        job == null ? "null" : job.getClass().getSimpleName(),
                        job != null && job.simulation(), BatchedCraftingQueue.dispatchInProgress);
            }
            return;
        }
        // AE2-VM 等第三方 mod 接管模拟计算后，返回的模拟计划 simulation=true。
        // 普通模拟计划放行原版；超限的模拟计划也必须拆批（防溢出级订单绕过拆批）。
        if (job.simulation() && !ae2addon$isOversized(job)) {
            if (CraftingCompat.debugLogs) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] submitJob模拟计划放行(不超限) job={}",
                        job.getClass().getSimpleName());
            }
            return;
        }

        // 巨型订单识别：从上下文缓存取（模拟拦截创建的真 CraftingPlan 实例）
        Object[] ctx = ae2addon$deferredContexts.remove(job);
        if (ctx != null) {
            if (CraftingCompat.debugLogs) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] submitJob收到超限计划(上下文) what={} total={} perBatch={} truncated={}",
                        job.finalOutput() == null ? null : job.finalOutput().what(),
                        ctx[3], ctx[4], ctx[6]);
            }
            // 截断订单：配方树展开超预算（树过深/配方环），无法证明安全拆批 → 拒绝
            if (Boolean.TRUE.equals(ctx[6])) {
                ChatLog.err(ae2addon$levelOf(requestingMachine), null,
                        "配方树展开超预算（树过深或配方环），无法安全拆分，已拒绝");
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.CPU_TOO_SMALL));
                return;
            }
            // 去重：机器 requester 会反复提交同一计划，已受理的直接返回 CPU 忙
            if (BatchedCraftingQueue.isPlanAccepted((java.util.UUID) ctx[5])) {
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.CPU_BUSY));
                return;
            }
            var deferredOrder = BatchedCraftingOrder.createFromContext(
                    job, (appeng.api.networking.IGrid) ctx[0],
                    (net.minecraft.server.level.ServerLevel) ctx[1],
                    (appeng.api.networking.IGridNode) ctx[2],
                    (Long) ctx[3], (Long) ctx[4],
                    requestingMachine, source);
            if (deferredOrder == null) {
                ChatLog.err(ae2addon$levelOf(requestingMachine), null,
                        "订单需求超限且无法拆分，已拒绝");
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.CPU_TOO_SMALL));
                return;
            }
            try {
                ae2addon$acceptBatchedOrder(deferredOrder, requestingMachine, callback);
                BatchedCraftingQueue.markPlanAccepted((java.util.UUID) ctx[5]);
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.error(
                        "[ae2addon] 超限计划受理异常", e);
                ChatLog.err(ae2addon$levelOf(requestingMachine), null,
                        "巨型订单受理异常，请查看日志");
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.NO_CPU_FOUND));
            }
            return;
        }

        boolean oversized = ae2addon$isOversized(job);
        if (CraftingCompat.debugLogs) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] submitJob检查超限 plan={} 超限={}",
                    job.getClass().getSimpleName(), oversized);
        }
        if (!oversized) {
            return;
        }

        var order = BatchedCraftingOrder.create(job, requestingMachine, source);
        if (order == null && requestingMachine == null) {
            // 2026-08-27 修复：VM 提交路径 requester 可能为 null → create 直接失败。
            // 用本 CraftingService 的 grid 兜底构造（requester 只用于拿网格/level）。
            order = BatchedCraftingOrder.createFromGrid(job, this.grid, source);
        }
        if (order == null) {
            if (ae2addon$hasOverflow(job)) {
                ChatLog.err(ae2addon$levelOf(requestingMachine), null,
                        "订单材料需求超出 CPU 记账上限且无法拆分，已拒绝");
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.CPU_TOO_SMALL));
            }
            // 巨大但有效且无法拆分（如残留测试样板导致 perBatch=1）：放行原版提交，
            // 由 CPU 报缺料或正常执行，避免误导性报错（2026-08-22）。
            return;
        }
        ae2addon$acceptBatchedOrder(order, requestingMachine, callback);
    }

    /**
     * 模拟阶段拦截：需求超限时不让 AE2 原版模拟器去算（会卡死在反复重试），
     * 直接用 BigInteger 估算需求、计算安全批次，返回真 CraftingPlan
     * （饱和估算值填充；submitJob 分支靠 isOversized 识别后拆批）。
     * <p>
     * 2026-08-21 修复：原来返回自定义 DeferredCraftingPlan（ICraftingPlan 伪实现），
     * GTL 整合包的界面 mixin（gtlcore/EAE 等）会把 ICraftingPlan 强转成
     * appeng.crafting.CraftingPlan（final Record 不可继承）→ ClassCastException。
     * 改为直接构造真 CraftingPlan，任何 cast 都能通过。
     */
    /**
     * 小额订单免估算阈值（2026-08-22）：下单量 ≤ 10^6 的物品不可能达到溢出级
     * （除非每单位需求病态到 ≥2.3×10^12，那种放行原版也会快速报缺料）。
     * 跳过 analyze/首次配方树展开——首次展开在服务端线程上跑，复杂配方图
     * 会让普通订单下单瞬间卡死游戏（sensei 实测 17:31）。大单才估算（缓存）。
     */
    @Unique
    private long ae2addon$cheapOrderAmount() {
        return com.ae2addon.crafting.CraftingCompat.cheapOrderAmount;
    }

    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ae2addon$deferOversizedSimulation(Level level,
            ICraftingSimulationRequester simRequester, AEKey what, long amount,
            CalculationStrategy strategy,
            CallbackInfoReturnable<Future<ICraftingPlan>> callback) {
        // 2026-08-27：handler 是否被调用的诊断（非 debug——VM 环境下曾出现
        // handler 完全不执行/被抢先，debug 日志看不到，改 WARN 定位）。
        com.ae2addon.AE2Addon.LOGGER.warn(
                "[ae2addon] 模拟拦截被调用 what={} amount={} isCancelled={}",
                what, amount, callback.isCancelled());
        // 2026-08-27：AE2-VM 等更高/同优先级 mod 已接管（cancel+setReturnValue）→ 让路，
        // 避免覆盖对方的计算结果（本类 priority=1200 低于 VM 的 2000，正常我们先执行）。
        if (callback.isCancelled()) {
            if (CraftingCompat.debugLogs) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] 模拟拦截让路: 已被其他mod接管(cancelled) what={} amount={}",
                        what, amount);
            }
            return;
        }
        if (level == null || level.isClientSide || what == null || amount <= 0) {
            if (CraftingCompat.debugLogs) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] 模拟拦截早退 level={} what={} amount={}",
                        level, what, amount);
            }
            return;
        }
        // 小额订单免估算：直接走原版（不展开配方树，避免服务端线程卡顿）
        if (amount <= ae2addon$cheapOrderAmount()) {
            return;
        }
        long t0 = System.nanoTime();
        // 2026-08-27：拆批基准 = OVERFLOW_THRESHOLD (Long.MAX = 2^63-1，真溢出线)。
        // 需求 2^63 → 拆 (2^63-1)+1；2^64 → (2^63-1)×2+2。
        // 不用 SAFE_LIMIT (Long.MAX/4)——那是执行层保守余量，作拆批基准会
        // 把 10^16~10^18 级合法订单误判超限、拆出天文数字批次（sensei 拍板）。
        var analysis = RequirementCalculator.analyze(
                grid, what, amount, BatchedCraftingOrder.OVERFLOW_THRESHOLD);
        // 2026-08-27：analyze 结果非 debug（VM 环境下曾判定未超限/异常，需无条件可见）
        com.ae2addon.AE2Addon.LOGGER.warn(
                "[ae2addon] 模拟拦截结果: what={} amount={} 超限={} 截断={} perBatch={} perUnit数={}",
                what, amount, analysis.oversized, analysis.truncated,
                analysis.maxSafeBatch,
                analysis.perUnit == null ? -1 : analysis.perUnit.size());
        if (CraftingCompat.debugLogs) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] 模拟拦截: what={} amount={} 超限={} 截断={} perBatch={} 耗时={}ms",
                    what, amount, analysis.oversized, analysis.truncated,
                    analysis.maxSafeBatch,
                    (System.nanoTime() - t0) / 1_000_000L);
        }
        if (!analysis.oversized) {
            return; // 需求安全，走原版模拟
        }
        if (!analysis.truncated && analysis.maxSafeBatch <= 1) {
            // 2026-08-22：perBatch=1（单单位需求 ≥ PERF_LIMIT，通常被残留测试样板/
            // 异常配方撑爆）且非截断 → 无法安全拆批，放行原版模拟。其计划若 long 溢出
            // 会被 submitJob 拒绝（见 ae2addon$hasOverflow）；若有效则报缺料或正常执行
            // ——都比误导性的「CPU 存储不足」好（sensei 实测 15:47：dark_oak_log 1kw
            // 单被测试样板撑爆，误报 CPU_TOO_SMALL）。
            if (CraftingCompat.debugLogs) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] 模拟拦截放行: perBatch<=1 无法拆批 what={} amount={}",
                        what, amount);
            }
            return;
        }
        long perBatch = analysis.maxSafeBatch;

        // 超限：估算需求（饱和到 long 供确认界面显示）并返回真 CraftingPlan
        var used = new KeyCounter();
        for (var entry : analysis.needs.entrySet()) {
            used.add(entry.getKey(), entry.getValue()
                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue());
        }
        var realPlan = new appeng.crafting.CraftingPlan(
                new appeng.api.stacks.GenericStack(what, amount),
                Long.MAX_VALUE,   // bytes
                false,            // simulation
                false,            // multiplePaths
                used,             // usedItems（饱和估算）
                new KeyCounter(), // emittedItems
                new KeyCounter(), // missingItems
                java.util.Map.of()); // patternTimes
        // 缓存上下文供 submitJob 识别（按实例 IdentityHashMap）
        ServerLevel serverLevel = level instanceof ServerLevel sl ? sl : null;
        appeng.api.networking.IGridNode simNode = null;
        try {
            simNode = simRequester == null ? null : simRequester.getGridNode();
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 模拟拦截: simRequester节点获取失败", e);
        }
        ae2addon$deferredContexts.put(realPlan, new Object[]{
                grid, serverLevel, simNode, amount, perBatch,
                java.util.UUID.randomUUID(), analysis.truncated});
        if (ae2addon$deferredContexts.size() > AE2ADDON_DEFERRED_CONTEXT_MAX) {
            // 防泄漏：超限清空（条目生命周期短，偶尔重拦截无害）
            ae2addon$deferredContexts.clear();
        }
        callback.setReturnValue(CompletableFuture.completedFuture(realPlan));
        if (CraftingCompat.debugLogs) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 模拟拦截：需求超限，改为真 CraftingPlan what={} amount={} perBatch={} truncated={}",
                    what, amount, perBatch, analysis.truncated);
        }
    }

    /** 受理拆批订单：入队异步执行，返回成功（无 link，调用方按受理处理）。 */
    @Unique
    private static void ae2addon$acceptBatchedOrder(BatchedCraftingOrder order,
            ICraftingRequester requestingMachine,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        BatchedCraftingQueue.add(order);
        ChatLog.info(ae2addon$levelOf(requestingMachine), null,
                "订单需求超限，已受理并拆分为 " + order.getBatchCount()
                        + " 批逐批执行");
        // 成功受理：errorCode=null → successful()=true；link 为 null（真实批次由队列提交）
        callback.setReturnValue(new CraftingSubmitResult(null, null, null));
    }

    /**
     * 目标集成 CPU 忙时，自动把任务重定向到空闲虚拟 lane。
     */
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true, require = 0)
    private void ae2addon$redirectBusyIntegratedCpu(ICraftingPlan job,
            ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (job == null || job.simulation()
                || !(target instanceof CraftingCPUCluster selected)
                || !selected.isBusy()) {
            return;
        }
        var owner = IntegratedCPURegistry.ownerOf(selected);
        if (owner == null || !owner.isFormed()) {
            return;
        }
        var replacement = owner.getOrCreateIdleCpu();
        if (replacement == null || replacement == selected) {
            return;
        }
        if (!ae2addon$diagLogged) {
            ae2addon$diagLogged = true;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 量子分裂生效！busy CPU={} → 重定向到空闲 lane={}",
                    selected.getCoProcessors(), replacement.getCoProcessors());
        }
        callback.setReturnValue(replacement.submitJob(grid, job, source, requestingMachine));
        owner.ensureOneIdleCpu();
    }

    @Unique
    private static boolean ae2addon$diagLogged;

    /**
     * 计划是否已 long 溢出（出现负值）：溢出计划已损坏，绝不能提交给 CPU。
     * 与 ae2addon$isOversized 区分：负值=损坏必须拒；正值巨大=有效（可能缺料/可执行）。
     */
    @Unique
    private static boolean ae2addon$hasOverflow(ICraftingPlan plan) {
        try {
            for (var entry : plan.usedItems()) {
                if (entry.getLongValue() < 0) {
                    return true;
                }
            }
            for (var times : plan.patternTimes().values()) {
                if (times < 0) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        return false;
    }

    /**
     * 超限检测：usedItems/patternTimes 出现负值（long 溢出损坏）
     * 或超过 OVERFLOW_THRESHOLD（Long.MAX/2 ≈ 4.6×10^18，接近真溢出 2^63）
     * → 判定为巨型订单（拦截拆批）。
     * <p>
     * 2026-08-27 修正：原用 SAFE_LIMIT (Long.MAX/4) 太保守，AE2-VM 精确计算
     * 的 10^16~10^18 级订单（远未到 2^63 溢出）会被误判超限强行拆批，
     * 批数超上限反而拆批失败（sensei 实测「溢出拆批没成功」）。
     */
    @Unique
    private static boolean ae2addon$isOversized(ICraftingPlan plan) {
        try {
            for (var entry : plan.usedItems()) {
                long value = entry.getLongValue();
                // >= 而非 >：AE2-VM 把超限需求 cap 成 Long.MAX_VALUE 哨兵（正好等于阈值），
                // 用 > 会漏判（Long.MAX > Long.MAX = false）→ 订单按 cap 值提交只发 Long.MAX 材料
                // （sensei 实测 16:00：真实需求 2^64 只发出 9223372036854775807）。
                if (value < 0 || value >= BatchedCraftingOrder.OVERFLOW_THRESHOLD) {
                    return true;
                }
            }
            for (var times : plan.patternTimes().values()) {
                if (times < 0 || times >= BatchedCraftingOrder.OVERFLOW_THRESHOLD) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        return false;
    }

    @Unique
    private static ServerLevel ae2addon$levelOf(ICraftingRequester requester) {
        try {
            if (requester != null && requester.getActionableNode() != null) {
                return requester.getActionableNode().getLevel();
            }
        } catch (RuntimeException ignored) {
            // 节点未就绪
        }
        return null;
    }

    private void ae2addon$refreshIntegratedCpus(boolean throttled) {
        // 节流（仅 submitJob 高频路径）：至少间隔 20 tick 才全量扫描一次注册表
        if (throttled) {
            long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
            if (tick - ae2addon$lastCpuRefreshTick < AE2ADDON_CPU_REFRESH_INTERVAL_TICKS) {
                return;
            }
            ae2addon$lastCpuRefreshTick = tick;
        }
        int registered = 0;
        for (var blockEntity : IntegratedCPURegistry.all()) {
            if (blockEntity.isRemoved() || !blockEntity.isFormed()) {
                continue;
            }
            // 2026-08-27 修复：只注册本网格的 lane。此前遍历全局注册表，
            // 把其他网络的 lane 也注册进当前 CraftingService → AE2 终端可看到
            // 其他网络的 CPU 并跨网络下单（sensei 实测 20:15：未连接其他网络
            // 的 CPU，也能用本网络终端给别的网络 CPU 下单）。
            appeng.api.networking.IGrid beGrid = null;
            try {
                var node = blockEntity.getMainNode() == null
                        ? null : blockEntity.getMainNode().getNode();
                if (node != null) {
                    beGrid = node.getGrid();
                }
            } catch (RuntimeException ignored) {
                // 网格未就绪：跳过（下轮刷新再试）
            }
            if (beGrid != this.grid) {
                continue;
            }
            // 提交任务后主簇可能已忙：确保有空闲虚拟 lane 再注册，
            // 否则自动模式选 CPU 时看不到可用的 lane（量子分裂失效的根因）
            blockEntity.ensureOneIdleCpu();
            for (var cpu : blockEntity.allCpus()) {
                if (!cpu.isDestroyed() && cpu.isActive()) {
                    ae2addon$registerCpu(cpu);
                    registered++;
                }
            }
        }
        if (registered != ae2addon$lastRegisteredCount) {
            ae2addon$lastRegisteredCount = registered;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 虚拟CPU注册: 当前注册{}个lane（注册表内方块{}个）",
                    registered, IntegratedCPURegistry.all().size());
        }
    }

    @Unique
    private static int ae2addon$lastRegisteredCount = -1;

    // ── IntegratedCraftingServiceBridge 实现 ──

    @Override
    public void ae2addon$unregisterCpu(CraftingCPUCluster cluster) {
        if (cluster != null) {
            craftingCPUClusters.remove(cluster);
        }
    }

    @Override
    public void ae2addon$registerCpu(CraftingCPUCluster cluster) {
        if (cluster != null && !cluster.isDestroyed()) {
            craftingCPUClusters.add(cluster);
        }
    }
}
