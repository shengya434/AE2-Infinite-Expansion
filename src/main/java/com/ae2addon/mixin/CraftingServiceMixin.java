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
import com.ae2addon.crafting.DeferredCraftingPlan;
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
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceMixin implements IntegratedCraftingServiceBridge {

    @Shadow
    @Final
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow
    @Final
    private IGrid grid;

    @Inject(method = "updateCPUClusters", at = @At("RETURN"))
    private void ae2addon$registerVirtualCpus(CallbackInfo callback) {
        ae2addon$refreshIntegratedCpus();
    }

    @Inject(method = "submitJob", at = @At("RETURN"))
    private void ae2addon$keepSpareCpuAfterSubmit(ICraftingPlan job,
            ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        ae2addon$refreshIntegratedCpus();
    }

    /**
     * 巨型订单自动分批：下单材料需求超 long 记账上限（模拟已溢出为负值）
     * 时，取消原提交，用 BigInteger 需求树重新估算并拆成多个安全批次，
     * 首批立即提交（返回真实 link），剩余批次入队串行执行。
     * 也处理模拟阶段拦截产生的 DeferredCraftingPlan（延迟计划）。
     */
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void ae2addon$batchOversizedOrder(ICraftingPlan job,
            ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (job == null || job.simulation() || BatchedCraftingQueue.dispatchInProgress) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] submitJob早退 job={} sim={} dispatch={}",
                    job == null ? "null" : job.getClass().getSimpleName(),
                    job != null && job.simulation(), BatchedCraftingQueue.dispatchInProgress);
            return;
        }

        if (job instanceof DeferredCraftingPlan deferred) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] submitJob收到延迟计划 total={} perBatch={} planId={}",
                    deferred.totalAmount(), deferred.perBatch(), deferred.getPlanId());
            // 去重：机器 requester 会反复提交同一计划，已受理的直接返回 CPU 忙
            if (BatchedCraftingQueue.isPlanAccepted(deferred.getPlanId())) {
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.CPU_BUSY));
                return;
            }
            var deferredOrder = BatchedCraftingOrder.createFromDeferred(
                    deferred, requestingMachine, source);
            if (deferredOrder == null) {
                ChatLog.err(ae2addon$levelOf(requestingMachine), null,
                        "订单需求超限且无法拆分，已拒绝");
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.CPU_TOO_SMALL));
                return;
            }
            try {
                ae2addon$acceptBatchedOrder(deferredOrder, requestingMachine, callback);
                BatchedCraftingQueue.markPlanAccepted(deferred.getPlanId());
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.error(
                        "[ae2addon] 延迟计划受理异常 total={}", deferred.totalAmount(), e);
                ChatLog.err(ae2addon$levelOf(requestingMachine), null,
                        "巨型订单受理异常，请查看日志");
                callback.setReturnValue(CraftingSubmitResult.simpleError(
                        CraftingSubmitErrorCode.NO_CPU_FOUND));
            }
            return;
        }

        boolean oversized = ae2addon$isOversized(job);
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][debug] submitJob检查超限 plan={} 超限={}",
                job.getClass().getSimpleName(), oversized);
        if (!oversized) {
            return;
        }

        var order = BatchedCraftingOrder.create(job, requestingMachine, source);
        if (order == null) {
            ChatLog.err(ae2addon$levelOf(requestingMachine), null,
                    "订单材料需求超出 CPU 记账上限且无法拆分，已拒绝");
            callback.setReturnValue(CraftingSubmitResult.simpleError(
                    CraftingSubmitErrorCode.CPU_TOO_SMALL));
            return;
        }
        ae2addon$acceptBatchedOrder(order, requestingMachine, callback);
    }

    /**
     * 模拟阶段拦截：需求超限时不让 AE2 原版模拟器去算（会卡死在反复重试），
     * 直接用 BigInteger 估算需求、计算安全批次，返回 DeferredCraftingPlan。
     * 真正提交时由 submitJob 分支拆批执行。
     */
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, remap = false)
    private void ae2addon$deferOversizedSimulation(Level level,
            ICraftingSimulationRequester simRequester, AEKey what, long amount,
            CalculationStrategy strategy,
            CallbackInfoReturnable<Future<ICraftingPlan>> callback) {
        if (level == null || level.isClientSide || what == null || amount <= 0) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] 模拟拦截早退 level={} what={} amount={}",
                    level, what, amount);
            return;
        }
        boolean oversized = RequirementCalculator.isOversized(
                grid, what, amount, BatchedCraftingOrder.SAFE_LIMIT);
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][debug] 模拟拦截: what={} amount={} 超限={}",
                what, amount, oversized);
        if (!oversized) {
            return; // 需求安全，走原版模拟
        }
        long perBatch = RequirementCalculator.maxSafeBatch(
                grid, what, amount, BatchedCraftingOrder.SAFE_LIMIT);
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][debug] 模拟拦截超限: perBatch={} safeLimit={}",
                perBatch, BatchedCraftingOrder.SAFE_LIMIT);

        // 超限：估算需求（饱和到 long 供确认界面显示）并返回延迟计划
        var used = new KeyCounter();
        var needs = RequirementCalculator.estimate(grid, what, amount);
        for (var entry : needs.entrySet()) {
            used.add(entry.getKey(), entry.getValue()
                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue());
        }
        ServerLevel serverLevel = level instanceof ServerLevel sl ? sl : null;
        appeng.api.networking.IGridNode simNode = null;
        try {
            simNode = simRequester == null ? null : simRequester.getGridNode();
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 模拟拦截: simRequester节点获取失败", e);
        }
        callback.setReturnValue(CompletableFuture.completedFuture(
                new DeferredCraftingPlan(grid, serverLevel, simNode, what, amount, perBatch, used)));
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon] 模拟拦截：需求超限，改为延迟计划 what={} amount={} perBatch={}",
                what, amount, perBatch);
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
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
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
     * 超限检测：usedItems（材料总需求）或 patternTimes（配方执行次数）
     * 出现负值（long 溢出）或超过安全上限 → 判定为巨型订单。
     */
    @Unique
    private static boolean ae2addon$isOversized(ICraftingPlan plan) {
        try {
            for (var entry : plan.usedItems()) {
                long value = entry.getLongValue();
                if (value < 0 || value > BatchedCraftingOrder.SAFE_LIMIT) {
                    return true;
                }
            }
            for (var times : plan.patternTimes().values()) {
                if (times < 0 || times > BatchedCraftingOrder.SAFE_LIMIT) {
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

    private void ae2addon$refreshIntegratedCpus() {
        int registered = 0;
        for (var blockEntity : IntegratedCPURegistry.all()) {
            if (blockEntity.isRemoved() || !blockEntity.isFormed()) {
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
