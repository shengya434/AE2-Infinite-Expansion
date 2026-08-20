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
 * 产物路径不受影响：AE2 CPU 合成完成自动输出到 ME 网络，requester 的
 * insertCraftedItems 只是「请求方收货」的备选路径，这里返回 amount 表示接受。
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
        // 接受产物（CPU 已同步输出到 ME 网络，这里只是路径占位）
        return amount;
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
