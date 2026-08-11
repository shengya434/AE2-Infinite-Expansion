package com.ae2addon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * 无限并行处理单元 — 已取消 CPU 功能（普通方块化）。
 * <p>
 * 不再继承 CraftingUnitBlock：不会参与 AE2 合成 CPU 结构、没有 formed 材质。
 * 仍保留网格节点（通过 BE），可以像普通方块一样接入 AE 网络（接智能线缆等）。
 * 现在的用途：作为 集成型CPU（IntegratedCPU）多方块的内部元件材料。
 */
public class InfiniteCoProcessingBlock extends BaseEntityBlock {

    public InfiniteCoProcessingBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(3.0f)
                .requiresCorrectToolForDrops());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InfiniteCoProcessingBE(pos, state);
    }
}
