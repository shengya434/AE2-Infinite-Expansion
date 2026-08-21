package com.ae2addon.block;

import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.CraftingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug 销毁方块注册表：收集所有活跃的 DebugTrashBE，
 * 供 CraftingCpuLogicMixin 在 executeCrafting 时追加为任意 pattern 的 provider。
 */
public final class DebugTrashRegistry {

    private static final Set<DebugTrashBE> ACTIVE = ConcurrentHashMap.newKeySet();

    private DebugTrashRegistry() {
    }

    public static void register(DebugTrashBE blockEntity) {
        ACTIVE.add(blockEntity);
    }

    public static void unregister(DebugTrashBE blockEntity) {
        ACTIVE.remove(blockEntity);
    }

    /**
     * 收集与给定 craftingService 同一网络的、活跃的 Debug 方块。
     */
    public static List<ICraftingProvider> collectFor(CraftingService craftingService) {
        var result = new ArrayList<ICraftingProvider>();
        for (var blockEntity : ACTIVE) {
            if (blockEntity.isRemoved()
                    || blockEntity.getLevel() == null
                    || blockEntity.getLevel().isClientSide()) {
                continue;
            }
            if (!blockEntity.getMainNode().isActive()) {
                continue;
            }
            var grid = blockEntity.getMainNode().getGrid();
            if (grid == null || grid.getCraftingService() != craftingService) {
                continue;
            }
            result.add(blockEntity);
        }
        return result;
    }

    /**
     * 指定世界里是否存在活跃的 Debug 方块（批量推送 N× 提取的启用条件：
     * 只有无限消费型接收方存在时才做 N×，否则普通 PatternProvider/机器
     * 会拒绝 ScaledPattern 的 N× 输入 → 材料滞留，发送量 < 计算量）。
     */
    public static boolean hasActiveIn(net.minecraft.world.level.Level level) {
        if (level == null) {
            return false;
        }
        for (var blockEntity : ACTIVE) {
            if (blockEntity.isRemoved()) {
                continue;
            }
            if (blockEntity.getLevel() != level) {
                continue;
            }
            try {
                if (blockEntity.getMainNode().isActive()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 网格未就绪，继续
            }
        }
        return false;
    }
}
