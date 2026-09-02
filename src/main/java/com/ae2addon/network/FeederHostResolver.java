package com.ae2addon.network;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.parts.IPartHost;

import com.ae2addon.block.FeederHost;
import com.ae2addon.block.InfiniteInterfaceBE;

/**
 * 供料站宿主定位（2026-09-02 part 版）：GUI 网络包只有 pos——
 * 方块版直接拿 BE；part 版挂在 CableBus 上，需从 IPartHost 里找。
 */
public final class FeederHostResolver {

    private FeederHostResolver() {
    }

    /** 优先方块 BE，其次 pos 处线缆上的第一个供料站 part。 */
    @Nullable
    public static FeederHost resolve(Level level, BlockPos pos) {
        if (level == null) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FeederHost fh) {
            return fh;
        }
        if (be instanceof IPartHost host) {
            for (Direction d : Direction.values()) {
                var part = host.getPart(d);
                if (part instanceof FeederHost fh) {
                    return fh;
                }
            }
        }
        return null;
    }

    /** 兼容旧引用：明确要方块版 BE。 */
    @Nullable
    public static InfiniteInterfaceBE resolveBE(Level level, BlockPos pos) {
        if (level == null) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof InfiniteInterfaceBE b ? b : null;
    }
}
