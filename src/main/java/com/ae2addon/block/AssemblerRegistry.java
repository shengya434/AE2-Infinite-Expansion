package com.ae2addon.block;

import appeng.me.cluster.implementations.CraftingCPUCluster;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装配处理器核心注册表：收集所有存活的 AssemblerCoreBE，
 * 供 CraftingCpuLogicMixin 查询「某 CPU 簇是否含装配处理器核心 + 白名单判定」。
 */
public final class AssemblerRegistry {

    private static final Set<AssemblerCoreBE> ACTIVE = ConcurrentHashMap.newKeySet();

    private AssemblerRegistry() {
    }

    public static void register(AssemblerCoreBE blockEntity) {
        ACTIVE.add(blockEntity);
    }

    public static void unregister(AssemblerCoreBE blockEntity) {
        ACTIVE.remove(blockEntity);
    }

    /**
     * CPU 簇内是否含装配处理器核心；返回第一个命中的核心（同簇只会有 0/1 个）。
     */
    public static AssemblerCoreBE coreIn(CraftingCPUCluster cluster) {
        if (cluster == null || cluster.isDestroyed()) {
            return null;
        }
        for (AssemblerCoreBE core : ACTIVE) {
            if (core.isRemoved() || !core.isFormed()) {
                continue;
            }
            if (core.getCluster() == cluster) {
                return core;
            }
        }
        return null;
    }

    /** 遍历辅助：簇内是否有任何装配处理器核心。 */
    public static boolean hasCoreIn(CraftingCPUCluster cluster) {
        return coreIn(cluster) != null;
    }
}
