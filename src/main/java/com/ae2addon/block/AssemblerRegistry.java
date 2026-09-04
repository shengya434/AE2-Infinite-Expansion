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
     * 查找服务某集成 CPU 的装配处理器模块。按【同网格】匹配（2026-09-04 修复：
     * 此前按 ownerCPU 精确匹配——多集成 CPU 同网络时模块只服务第一个，其余 CPU
     * 的订单判定 module=null → 走 1× 强制 → 巨型订单卡死）。模块接入哪个网格，
     * 就服务该网格的全部集成 CPU（白名单共享；一般每网一个模块）。
     */
    public static AssemblerCoreBE moduleFor(IntegratedCPUBE owner) {
        if (owner == null) {
            return null;
        }
        var ownerGrid = gridOf(owner);
        if (ownerGrid == null) {
            return null;
        }
        for (AssemblerCoreBE core : ACTIVE) {
            if (core.isRemoved() || !core.isFormed()) {
                continue;
            }
            if (core.getOwnerCPU() == null) {
                // 惰性重试关联（网格就绪延迟兜底）
                core.refreshOwnerNow();
            }
            if (gridOf(core) == ownerGrid) {
                return core;
            }
        }
        return null;
    }

    private static appeng.api.networking.IGrid gridOf(appeng.blockentity.grid.AENetworkBlockEntity be) {
        try {
            var node = be.getMainNode();
            return node == null ? null : node.getGrid();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
