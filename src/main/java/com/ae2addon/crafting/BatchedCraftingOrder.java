package com.ae2addon.crafting;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingSubmitResult;
import com.ae2addon.util.ChatLog;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Future;

/**
 * 巨型订单（自动分批）：一个下单量超 long 记账上限的订单，
 * 被拆成多个安全批次，由 BatchedCraftingQueue 推进执行。
 * <p>
 * 执行模型（2026-08-20 并行化）：<b>有限并行</b>——同时最多
 * {@link #MAX_CONCURRENT} 个批次在跑（各自独立模拟+提交+link 跟踪），
 * 完成一批立即补一批，直到全部完成。全程异步，不阻塞服务端线程。
 * <p>
 * 状态机：QUEUED → RUNNING（批次窗口推进）→ DONE / FAILED / CANCELLED
 */
public final class BatchedCraftingOrder {

    public enum Status {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED
    }

    /** 单批材料需求安全上限：Long.MAX/4，给执行层（批量推送 N×）留足余量 */
    public static final long SAFE_LIMIT = Long.MAX_VALUE / 4;

    /** 单个巨型订单的最大批次数（防呆：超出则拒绝，避免天文数字批次） */
    private static final int MAX_BATCH_COUNT = 100_000;

    /** 并行度：同时执行的批次数（Integer.MAX_VALUE = 一次全发，由 CPU lane 自行调度） */
    public static final int MAX_CONCURRENT = Integer.MAX_VALUE;

    private final ServerLevel level;
    private final IGrid grid;
    private final IGridNode simNode;
    private final AEKey what;
    private final long totalAmount;
    private final ICraftingRequester requester;
    private final IActionSource source;
    private final List<Long> batchAmounts;
    private final List<BatchProgress> running = new ArrayList<>();

    private int nextBatchIndex = 0;
    private int completedCount = 0;
    private Status status = Status.QUEUED;

    /** 单个批次的运行状态 */
    private static final class BatchProgress {
        final long amount;
        Future<ICraftingPlan> pendingSimulation;
        ICraftingLink link;

        BatchProgress(long amount) {
            this.amount = amount;
        }
    }

    private BatchedCraftingOrder(ServerLevel level, IGrid grid, IGridNode simNode,
                                 AEKey what, long totalAmount,
                                 ICraftingRequester requester, IActionSource source,
                                 List<Long> batchAmounts) {
        this.level = level;
        this.grid = grid;
        this.simNode = simNode;
        this.what = what;
        this.totalAmount = totalAmount;
        this.requester = requester;
        this.source = source;
        this.batchAmounts = batchAmounts;
    }

    private static List<Long> splitBatches(long total, long perBatch) {
        List<Long> batches = new ArrayList<>();
        long remaining = total;
        while (remaining > 0) {
            long batch = Math.min(perBatch, remaining);
            batches.add(batch);
            remaining -= batch;
        }
        return batches;
    }

    /**
     * 从已损坏（超限）的 plan 创建分批订单；无需拆分时返回 null。
     */
    public static BatchedCraftingOrder create(ICraftingPlan badPlan,
                                              ICraftingRequester requester,
                                              IActionSource source) {
        if (badPlan == null || requester == null) {
            return null;
        }
        IGridNode node;
        try {
            node = requester.getActionableNode();
        } catch (RuntimeException e) {
            return null;
        }
        if (node == null || node.getGrid() == null) {
            return null;
        }
        ServerLevel level = node.getLevel();
        if (level == null) {
            return null;
        }
        var finalOutput = badPlan.finalOutput();
        if (finalOutput == null || finalOutput.what() == null || finalOutput.amount() <= 0) {
            return null;
        }

        IGrid grid = node.getGrid();
        long perBatch = RequirementCalculator.maxSafeBatch(
                grid, finalOutput.what(), finalOutput.amount(), SAFE_LIMIT);
        if (perBatch >= finalOutput.amount()) {
            return null;
        }

        List<Long> batches = splitBatches(finalOutput.amount(), perBatch);
        if (batches.size() <= 1 || batches.size() > MAX_BATCH_COUNT) {
            return null;
        }

        return new BatchedCraftingOrder(level, grid, null, finalOutput.what(),
                finalOutput.amount(), requester, source, batches);
    }

    /**
     * 从 DeferredCraftingPlan（模拟阶段拦截产生的伪计划）创建分批订单。
     */
    public static BatchedCraftingOrder createFromDeferred(DeferredCraftingPlan plan,
                                                          ICraftingRequester requester,
                                                          IActionSource source) {
        if (plan == null) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] createFromDeferred: plan=null");
            return null;
        }
        IGridNode node = null;
        try {
            node = requester == null ? null : requester.getActionableNode();
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] createFromDeferred: requester节点异常", e);
        }
        IGrid grid = plan.getGrid();
        ServerLevel level = plan.getLevel();
        if (grid == null && node != null) {
            grid = node.getGrid();
        }
        if (level == null && node != null) {
            level = node.getLevel();
        }
        if (grid == null || level == null) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromDeferred: grid={} level={} node={} 无法获取网格/世界",
                    grid, level, node);
            return null;
        }

        long total = plan.totalAmount();
        long perBatch = plan.perBatch();
        if (total <= 0 || perBatch <= 0 || perBatch >= total) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromDeferred: 无法拆批 total={} perBatch={}",
                    total, perBatch);
            return null;
        }

        List<Long> batches = splitBatches(total, perBatch);
        if (batches.size() <= 1 || batches.size() > MAX_BATCH_COUNT) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromDeferred: 批次数量异常 total={} perBatch={} 批数={}",
                    total, perBatch, batches.size());
            return null;
        }

        return new BatchedCraftingOrder(level, grid, plan.getSimNode(),
                plan.finalOutput().what(), total, requester, source, batches);
    }

    /**
     * 队列 tick 推进（服务端线程，不阻塞）：维护批次窗口——
     * 模拟完成→提交→等完成→补新批，保持 MAX_CONCURRENT 个并行。
     *
     * @return true = 订单仍在进行中；false = 订单已结束
     */
    public boolean tick() {
        switch (status) {
            case QUEUED -> {
                status = Status.RUNNING;
                fillWindow();
                return status == Status.RUNNING;
            }
            case RUNNING -> {
                // 1. 模拟完成的批次 → 提交
                for (BatchProgress batch : running) {
                    if (batch.pendingSimulation != null && batch.pendingSimulation.isDone()) {
                        Future<ICraftingPlan> done = batch.pendingSimulation;
                        batch.pendingSimulation = null;
                        ICraftingPlan plan;
                        try {
                            plan = done.get();
                        } catch (Exception e) {
                            com.ae2addon.AE2Addon.LOGGER.error(
                                    "[ae2addon] 分批模拟异常 what={}", what, e);
                            failAll();
                            return false;
                        }
                        if (plan == null || plan.simulation()) {
                            com.ae2addon.AE2Addon.LOGGER.warn(
                                    "[ae2addon] 分批模拟缺料/无效 what={} plan={}",
                                    what, plan);
                            ChatLog.err(level, null,
                                    "批次缺少材料或模拟无效，订单已取消（见日志）");
                            failAll();
                            return false;
                        }
                        // requester 为 null（玩家终端提交）时用虚拟请求方换取真实 link
                        ICraftingRequester effectiveRequester = requester != null
                                ? requester : new BatchedRequester(simNode, source);
                        ICraftingSubmitResult result = BatchedCraftingQueue.submitBypass(
                                grid, plan, effectiveRequester, source);
                        if (result == null || !result.successful()) {
                            com.ae2addon.AE2Addon.LOGGER.warn(
                                    "[ae2addon] 分批提交失败: what={} 错误码={} 详情={}",
                                    what,
                                    result == null ? "null" : result.errorCode(),
                                    result == null ? "null" : result.errorDetail());
                            ChatLog.err(level, null,
                                    "批次提交失败，订单已取消");
                            failAll();
                            return false;
                        }
                        batch.link = result.link();
                        com.ae2addon.AE2Addon.LOGGER.info(
                                "[ae2addon] 批次已提交 what={} 进度={}/{}+{}",
                                what, completedCount, batchAmounts.size(),
                                running.size());
                    }
                }

                // 2. 清理完成的批次；检查取消
                Iterator<BatchProgress> iterator = running.iterator();
                while (iterator.hasNext()) {
                    BatchProgress batch = iterator.next();
                    if (batch.link == null) {
                        continue;
                    }
                    if (batch.link.isCanceled()) {
                        cancelAll();
                        return false;
                    }
                    if (batch.link.isDone()) {
                        completedCount++;
                        iterator.remove();
                        com.ae2addon.AE2Addon.LOGGER.info(
                                "[ae2addon] 批次完成 what={} 进度={}/{}",
                                what, completedCount, batchAmounts.size());
                    }
                }

                // 3. 补新批，保持并行度
                fillWindow();

                // 4. 全部完成？
                if (completedCount == batchAmounts.size() && running.isEmpty()) {
                    markDone();
                    return false;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 补充批次窗口：保持 MAX_CONCURRENT 个进行中（含模拟中）。 */
    private void fillWindow() {
        while (status == Status.RUNNING
                && running.size() < MAX_CONCURRENT
                && nextBatchIndex < batchAmounts.size()) {
            BatchProgress batch = new BatchProgress(batchAmounts.get(nextBatchIndex));
            nextBatchIndex++;
            if (!startSimulation(batch)) {
                failAll();
                return;
            }
            running.add(batch);
        }
    }

    /** 发起批次模拟（异步）。返回 false 表示发起失败。 */
    private boolean startSimulation(BatchProgress batch) {
        IGridNode node = simNode;
        if (node == null) {
            try {
                node = requester == null ? null : requester.getActionableNode();
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.warn(
                        "[ae2addon] 发起批次模拟: requester节点不可用", e);
            }
        }
        try {
            batch.pendingSimulation = grid.getCraftingService().beginCraftingCalculation(
                    level, new SimulationRequester(source, node), what, batch.amount,
                    CalculationStrategy.REPORT_MISSING_ITEMS);
            return true;
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.error(
                    "[ae2addon] 发起批次模拟异常 what={}", what, e);
            return false;
        }
    }

    private void failAll() {
        status = Status.FAILED;
        cancelRunning();
        com.ae2addon.AE2Addon.LOGGER.warn(
                "[ae2addon] 巨型订单失败 what={} 进度={}/{}",
                what, completedCount, batchAmounts.size());
    }

    private void cancelAll() {
        status = Status.CANCELLED;
        cancelRunning();
        com.ae2addon.AE2Addon.LOGGER.warn(
                "[ae2addon] 巨型订单被取消 what={} 进度={}/{}",
                what, completedCount, batchAmounts.size());
    }

    private void cancelRunning() {
        for (BatchProgress batch : running) {
            if (batch.link != null && !batch.link.isDone() && !batch.link.isCanceled()) {
                try {
                    batch.link.cancel();
                } catch (RuntimeException ignored) {
                    // 链接已失效
                }
            }
        }
        running.clear();
    }

    public void markDone() {
        status = Status.DONE;
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon] 巨型订单完成 what={} total={} 批次={}",
                what, totalAmount, batchAmounts.size());
    }

    public Status getStatus() {
        return status;
    }

    public int getBatchCount() {
        return batchAmounts.size();
    }

    public int getCurrentBatchIndex() {
        return Math.min(completedCount + 1, batchAmounts.size());
    }

    public AEKey getWhat() {
        return what;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public ServerLevel getLevel() {
        return level;
    }
}
