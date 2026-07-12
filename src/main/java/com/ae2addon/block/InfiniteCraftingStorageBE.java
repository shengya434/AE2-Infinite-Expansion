package com.ae2addon.block;

import appeng.blockentity.crafting.CraftingBlockEntity;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class InfiniteCraftingStorageBE extends CraftingBlockEntity {

    public InfiniteCraftingStorageBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_CRAFTING_STORAGE.get(), pos, state);
    }

    @Override
    public long getStorageBytes() {
        // Long.MAX_VALUE = 9.22e18 字节 ≈ 8 EB
        // 致敬 MAX 合成存储器：单个方块就提供 64-bit 满额存储空间
        return Long.MAX_VALUE;
    }

    @Override
    public int getAcceleratorThreads() {
        return 0;
    }
}
