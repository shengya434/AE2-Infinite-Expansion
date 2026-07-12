package com.ae2addon.block;

import appeng.blockentity.crafting.CraftingBlockEntity;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class InfiniteCoProcessingBE extends CraftingBlockEntity {

    public InfiniteCoProcessingBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_CO_PROCESSING.get(), pos, state);
    }

    @Override
    public long getStorageBytes() {
        return 0;
    }

    @Override
    public int getAcceleratorThreads() {
        return Integer.MAX_VALUE;
    }
}
