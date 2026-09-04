package com.ae2addon.block;

import appeng.me.cluster.implementations.CraftingCPUCluster;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装配处理器模块注册表（v0.3 M3，2026-09-04 sensei 决策：不做独立合成单元，
 * 作为集成 CPU 的拓展模块——贴入集成 CPU 簇即激活该簇的虚拟结算白名单）。
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
     * 查找服务于某集成 CPU 的装配处理器模块（该 CPU 的主簇或任一虚拟 lane 执行
     * 合成时都会命中——模块只入主簇，但能力覆盖 owner 的全部簇）。
     * 无模块/模块未入簇/无 owner 返回 null。
     */
    public static AssemblerCoreBE moduleFor(IntegratedCPUBE owner) {
        if (owner == null) {
            return null;
        }
        for (AssemblerCoreBE core : ACTIVE) {
            if (core.isRemoved() || !core.isFormed()) {
                continue;
            }
            if (core.getOwnerCPU() == owner) {
                return core;
            }
        }
        return null;
    }
}
