package com.ae2addon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * Debug 销毁方块（测试用）：拥有无限输入能力，接受后瞬间销毁其中材料。
 * <p>
 * 通过 ICraftingProvider.pushPattern 接收 CPU 推送的任意配方输入，
 * 接受即销毁。比创造流体储罐更强：输入量不受 int 限制（KeyCounter 是 long）。
 */
public class DebugTrashBlock extends BaseEntityBlock {

    public DebugTrashBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
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
        return new DebugTrashBE(pos, state);
    }
}
