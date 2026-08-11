package com.ae2addon.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * CraftingServiceMixin 暴露给外部的桥接接口：
 * 让 IntegratedCPUBE 能直接把虚拟 CPU lane 从 AE2 的
 * craftingCPUClusters 集合中移除（任务完成后隐藏线程）。
 * <p>
 * 思路来自 OmniSequence 的 OmniCraftingServiceBridge。
 */
public interface IntegratedCraftingServiceBridge {

    /**
     * 把 CPU 从 CraftingService.craftingCPUClusters 集合移除。
     */
    void ae2addon$unregisterCpu(CraftingCPUCluster cluster);

    /**
     * 把 CPU 注册进 CraftingService.craftingCPUClusters 集合。
     */
    void ae2addon$registerCpu(CraftingCPUCluster cluster);
}
