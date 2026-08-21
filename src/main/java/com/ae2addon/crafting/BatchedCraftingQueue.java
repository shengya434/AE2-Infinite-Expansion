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

    /** 已从存档恢复（按 server 实例：退回主菜单再进世界 = 新 server → 重新恢复） */
    private static net.minecraft.server.MinecraftServer lastRestoredServer = null;

    /** 保存节流计数器（每 20 tick ≈ 1 秒保存一次） */
    private static int saveCounter = 0;

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
        saveAll();
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
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 懒加载恢复：世界加载后第一次 tick 从存档恢复巨型订单（断点续跑）
        ensureRestored(event.getServer());
        if (orders.isEmpty()) {
            return;
        }
        // 定期保存（20 tick 节流；SavedData 实际写盘在保存世界时）
        if (++saveCounter % 20 == 0) {
            saveAll();
        }
        Iterator<BatchedCraftingOrder> iterator = orders.iterator();
        while (iterator.hasNext()) {
            BatchedCraftingOrder order = iterator.next();
            if (order.getLevel() == null || order.getLevel().isClientSide) {
                continue;
            }
            boolean alive;
            try {
                alive = order.tick();
            } catch (RuntimeException e) {
                // 隔离异常：单个订单 tick 崩溃不能拖垮整个队列（恢复订单网格失效等）
                com.ae2addon.AE2Addon.LOGGER.error(
                        "[ae2addon] 巨型订单 tick 异常，已强制终止 what={}",
                        order.getWhat(), e);
                order.forceTerminate();
                alive = false;
            }
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

    /** 从存档恢复巨型订单（每个 server 实例只执行一次）。 */
    private static void ensureRestored(net.minecraft.server.MinecraftServer server) {
        if (server == null || server == lastRestoredServer) {
            return;
        }
        lastRestoredServer = server;
        // 清理上一 server 实例残留的订单（orders 是静态列表，退出世界不清空，
        // 旧订单持有失效的 level/grid，会 tick 异常或与恢复订单重复）
        orders.removeIf(o -> o.getLevel() == null
                || o.getLevel().getServer() != server);
        try {
            var overworld = server.overworld();
            var data = com.ae2addon.data.MegaOrderSavedData.get(overworld);
            var snapshots = data.getOrders();
            for (var snap : snapshots) {
                var level = server.getLevel(snap.dimension);
                if (level == null) {
                    continue;
                }
                var order = BatchedCraftingOrder.restore(snap, level);
                if (order != null) {
                    orders.add(order);
                }
            }
            if (snapshots.isEmpty()) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][debug] 巨型订单恢复检查：无待恢复订单");
            } else {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon] 已恢复 {} 个巨型订单（断点续跑）", snapshots.size());
            }
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 巨型订单恢复失败", e);
        }
    }

    /** 保存当前所有订单到存档（按维度去重）。 */
    private static void saveAll() {
        try {
            var byLevel = new java.util.HashMap<net.minecraft.server.level.ServerLevel,
                    java.util.List<BatchedCraftingOrder>>();
            for (var order : orders) {
                var level = order.getLevel();
                if (level == null || level.isClientSide) {
                    continue;
                }
                byLevel.computeIfAbsent(level, k -> new java.util.ArrayList<>()).add(order);
            }
            for (var entry : byLevel.entrySet()) {
                var data = com.ae2addon.data.MegaOrderSavedData.get(entry.getKey());
                data.setOrders(entry.getValue().stream()
                        .map(BatchedCraftingOrder::snapshot)
                        .toList());
            }
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 巨型订单保存失败", e);
        }
    }

    /** 订单面板用：当前所有巨型订单的快照。 */
    public static java.util.List<BatchedCraftingOrder> getOrders() {
        synchronized (orders) {
            return new java.util.ArrayList<>(orders);
        }
    }

    /** 订单面板用：按索引取消订单（越界安全）。 */
    public static void cancelOrder(int index) {
        synchronized (orders) {
            if (index >= 0 && index < orders.size()) {
                orders.get(index).cancelOrder();
            }
        }
    }
}
