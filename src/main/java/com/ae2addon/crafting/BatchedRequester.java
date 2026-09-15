package com.ae2addon.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚拟请求方：当原 requester 为 null（玩家终端提交时 requestingMachine 为 null）
 * 时，用它充当提交方，让原版 trySubmitJob 创建真实的 requester link——
 * 否则原版返回 successful(null)，队列无法跟踪批次完成/取消。
 * <p>
 * ❗2026-09-15 修正（sensei：“巨型订单没收到产物”）：“CPU 会自动把成品入网”是**错的** ——
 * AE2 的成品最终通过 `CraftingLink.insert()` → `requester.insertCraftedItems()` **交给请求方**，
 * 请求方负责把东西放下。原来这里直接 `return amount`（假装收下），等于把**整单最终产物扔掉了**
 * （巨型订单必定走本类，普通小订单走 AE2 自己的请求方 → 所以只有巨型订单丢产物，
 * 与“做装配处理器时遇到的”是同一个坑）。现在真的往 ME 网络里插。
 */
public final class BatchedRequester implements ICraftingRequester {

    private final IGridNode node;
    private final IActionSource source;
    private final List<ICraftingLink> links = new ArrayList<>();

    public BatchedRequester(IGridNode node, IActionSource source) {
        this.node = node;
        this.source = source;
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        synchronized (links) {
            return ImmutableSet.copyOf(links);
        }
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        // ❗真把成品入网：AE2 的最终产物是交给 requester 的（不是自动入网），
        // 以前直接 return amount = 把整单成品丢掉（巨型订单没产物的真根因）
        if (what == null || amount <= 0) {
            return 0;
        }
        var grid = node == null ? null : node.getGrid();
        var storage = grid == null ? null : grid.getStorageService().getInventory();
        if (storage == null) {
            // 断网：模拟阶段说“能收”免得 CPU 直接报错；真入网时只能拒收（产物留在 CPU 手里）
            return mode == Actionable.SIMULATE ? amount : 0;
        }
        long inserted = storage.insert(what, amount, mode, source);
        if (mode == Actionable.MODULATE && inserted < amount) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 巨型订单成品入网不完整 what={} 期望{} 实插{}（网络满？）",
                    what, amount, inserted);
        }
        return inserted;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        // 状态变化：无需额外处理，队列通过 link.isDone()/isCanceled() 查询
    }

    public void trackLink(ICraftingLink link) {
        if (link != null) {
            synchronized (links) {
                links.add(link);
            }
        }
    }

    @Override
    public IGridNode getActionableNode() {
        return node;
    }
}
