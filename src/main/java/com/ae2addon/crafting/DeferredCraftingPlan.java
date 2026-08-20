package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;

/**
 * 延迟计划（伪 ICraftingPlan）：当订单需求超 long 记账上限时，
 * 模拟阶段（beginCraftingCalculation）直接返回本对象，避免 AE2 原版
 * 模拟器在超限需求上陷入反复重试/卡死。
 * <p>
 * 调用方（终端确认界面 / 机器 requester）把它当作普通计划使用：
 * 确认界面正常显示产物与材料清单（材料量为饱和估算值）；
 * 真正提交时（submitJob），CraftingServiceMixin 识别出本类型，
 * 用存储的 totalAmount/perBatch 拆批执行。
 * <p>
 * 同时缓存 grid/level（模拟拦截时就有）：提交时 requester 节点可能
 * 不可用（getActionableNode 返回 null），用缓存的网格/世界兜底。
 */
public final class DeferredCraftingPlan implements ICraftingPlan {

    private final IGrid grid;
    private final ServerLevel level;
    private final appeng.api.networking.IGridNode simNode;
    private final GenericStack finalOutput;
    private final KeyCounter usedItems;
    private final long totalAmount;
    private final long perBatch;
    private final java.util.UUID planId = java.util.UUID.randomUUID();

    public appeng.api.networking.IGridNode getSimNode() {
        return simNode;
    }

    public java.util.UUID getPlanId() {
        return planId;
    }

    public DeferredCraftingPlan(IGrid grid, ServerLevel level,
                                appeng.api.networking.IGridNode simNode,
                                AEKey what, long totalAmount, long perBatch,
                                KeyCounter usedItems) {
        this.grid = grid;
        this.level = level;
        this.simNode = simNode;
        this.finalOutput = new GenericStack(what, totalAmount);
        this.totalAmount = totalAmount;
        this.perBatch = perBatch;
        this.usedItems = usedItems;
    }

    public IGrid getGrid() {
        return grid;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public long totalAmount() {
        return totalAmount;
    }

    public long perBatch() {
        return perBatch;
    }

    @Override
    public GenericStack finalOutput() {
        return finalOutput;
    }

    @Override
    public long bytes() {
        return Long.MAX_VALUE;
    }

    @Override
    public boolean simulation() {
        return false;
    }

    @Override
    public boolean multiplePaths() {
        return false;
    }

    @Override
    public KeyCounter usedItems() {
        return usedItems;
    }

    @Override
    public KeyCounter emittedItems() {
        return new KeyCounter();
    }

    @Override
    public KeyCounter missingItems() {
        return new KeyCounter();
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return Map.of();
    }
}
