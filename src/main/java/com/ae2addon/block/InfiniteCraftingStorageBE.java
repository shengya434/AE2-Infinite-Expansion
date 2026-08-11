package com.ae2addon.block;

import appeng.blockentity.grid.AENetworkBlockEntity;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 无限合成存储器 BE — 已取消 CPU 功能（普通方块化）。
 * <p>
 * 继承 AENetworkBlockEntity：保留网格节点，可接入 AE 网络；
 * 不注册任何服务（不再是合成存储单元，不提供存储/并行）。
 */
public class InfiniteCraftingStorageBE extends AENetworkBlockEntity {

    public InfiniteCraftingStorageBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_CRAFTING_STORAGE.get(), pos, state);
    }
}
