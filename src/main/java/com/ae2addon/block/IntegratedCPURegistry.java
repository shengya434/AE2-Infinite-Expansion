package com.ae2addon.block;

import appeng.me.cluster.implementations.CraftingCPUCluster;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集成 CPU 注册表：收集所有存活的 IntegratedCPUBE，
 * 供 CraftingServiceMixin 把虚拟 CPU lane 注册进 AE2 的 CPU 集合。
 */
public final class IntegratedCPURegistry {

    private static final Set<IntegratedCPUBE> ACTIVE = ConcurrentHashMap.newKeySet();

    private IntegratedCPURegistry() {
    }

    public static void register(IntegratedCPUBE blockEntity) {
        ACTIVE.add(blockEntity);
    }

    public static void unregister(IntegratedCPUBE blockEntity) {
        ACTIVE.remove(blockEntity);
    }

    public static Set<IntegratedCPUBE> all() {
        return ACTIVE;
    }

    /**
     * 查找某个 CPU 簇（主簇或虚拟 lane）所属的集成 CPU 方块。
     */
    public static IntegratedCPUBE ownerOf(CraftingCPUCluster cluster) {
        if (cluster == null || cluster.isDestroyed()) {
            return null;
        }
        for (var blockEntity : ACTIVE) {
            if (blockEntity.isRemoved()) {
                continue;
            }
            for (var cpu : blockEntity.allCpus()) {
                if (cpu == cluster) {
                    return blockEntity;
                }
            }
        }
        return null;
    }
}
