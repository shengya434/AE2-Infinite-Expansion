package com.ae2addon.crafting;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import com.ae2addon.util.ChatLog;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 巨型订单队列：串行逐批执行所有被拆分的订单。
 * <p>
 * 每个订单同一时间只有一个批次在跑（提交给 AE2 后由 link 跟踪），
 * 批次完成 → 推下一批；批次失败/被取消 → 终止订单剩余批次。
 * <p>
 * {@link #dispatchInProgress} 是给 CraftingServiceMixin 的防递归标志：
 * 队列内部提交时置位，mixin 的超限拦截检测到后直接放行，避免无限递归。
 */
public final class BatchedCraftingQueue {

    /** 队列内部提交标志（mixin 检查；服务端单线程，volatile 保险） */
    public static volatile boolean dispatchInProgress = false;

    private static final List<BatchedCraftingOrder> orders = new ArrayList<>();

    /** 已受理的延迟计划 ID（去重：机器 requester 会反复提交同一计划） */
    private static final java.util.Set<java.util.UUID> acceptedPlanIds = new java.util.HashSet<>();

    private BatchedCraftingQueue() {}

    public static boolean isPlanAccepted(java.util.UUID planId) {
        return planId != null && acceptedPlanIds.contains(planId);
    }

    public static void markPlanAccepted(java.util.UUID planId) {
        if (planId != null) {
            acceptedPlanIds.add(planId);
            if (acceptedPlanIds.size() > 200) {
                acceptedPlanIds.clear(); // 简单防膨胀（订单生命周期短）
            }
        }
    }

    public static void add(BatchedCraftingOrder order) {
        orders.add(order);
    }

    /**
     * 绕过 mixin 拦截的原版提交（队列内部专用）。
     * 显式选择「存储最大且空闲」的 CPU，避免原版自动选择
     * 选中存储不足的普通 CPU（第一批提交报 CPU_TOO_SMALL）。
     */
    public static ICraftingSubmitResult submitBypass(IGrid grid, ICraftingPlan plan,
                                                     ICraftingRequester requester,
                                                     IActionSource source) {
        dispatchInProgress = true;
        try {
            appeng.api.networking.crafting.ICraftingCPU target = findBestCpu(grid, plan);
            return grid.getCraftingService().submitJob(plan, requester, target, false, source);
        } finally {
            dispatchInProgress = false;
        }
    }

    /**
     * 找存储最大且空闲的 CPU（能装下 plan 的优先）。找不到返回 null，
     * 由原版自动选择兜底。
     */
    private static appeng.api.networking.crafting.ICraftingCPU findBestCpu(
            IGrid grid, ICraftingPlan plan) {
        appeng.api.networking.crafting.ICraftingCPU best = null;
        long bestStorage = -1;
        for (var cpu : grid.getCraftingService().getCpus()) {
            if (cpu.isBusy()) {
                continue;
            }
            long storage = cpu.getAvailableStorage();
            if (storage >= plan.bytes() && storage > bestStorage) {
                bestStorage = storage;
                best = cpu;
            }
        }
        return best;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || orders.isEmpty()) {
            return;
        }
        Iterator<BatchedCraftingOrder> iterator = orders.iterator();
        while (iterator.hasNext()) {
            BatchedCraftingOrder order = iterator.next();
            if (order.getLevel() == null || order.getLevel().isClientSide) {
                continue;
            }
            boolean alive = order.tick();
            if (!alive) {
                switch (order.getStatus()) {
                    case DONE -> ChatLog.ok(order.getLevel(), null,
                            "巨型订单全部 " + order.getBatchCount()
                                    + " 批已完成（" + order.getTotalAmount() + " 个）");
                    case FAILED -> ChatLog.err(order.getLevel(), null,
                            "巨型订单第 " + order.getCurrentBatchIndex()
                                    + " 批失败，订单已取消");
                    case CANCELLED -> ChatLog.warn(order.getLevel(), null,
                            "巨型订单批次被取消，剩余批次已终止");
                    default -> {}
                }
                iterator.remove();
            }
        }
    }

    /** 调试/管理用：当前排队订单数。 */
    public static int size() {
        return orders.size();
    }
}
