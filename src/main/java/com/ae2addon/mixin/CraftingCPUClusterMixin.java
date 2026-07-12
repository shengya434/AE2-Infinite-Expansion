package com.ae2addon.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 移除 AE2 CraftingCPUCluster 的 16 线程上限。
 * 将 addBlockEntity 中的常量 16 替换为 Integer.MAX_VALUE。
 */
@Mixin(CraftingCPUCluster.class)
public class CraftingCPUClusterMixin {

    @ModifyConstant(
            method = "addBlockEntity(Lappeng/blockentity/crafting/CraftingBlockEntity;)V",
            constant = @Constant(intValue = 16),
            remap = false
    )
    private int ae2addon$modifyMaxAcceleratorThreads(int original) {
        return Integer.MAX_VALUE;
    }
}
