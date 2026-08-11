package com.ae2addon.mixin;

import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * CraftingCPUCluster 内部方法访问器（虚拟 CPU lane 需要）。
 * 思路来自 OmniSequence-Transfinite 的 CraftingCPUClusterAccessor。
 */
@Mixin(value = CraftingCPUCluster.class, remap = false)
public interface CraftingCPUClusterAccessor {

    @Invoker("addBlockEntity")
    void ae2addon$addBlockEntity(CraftingBlockEntity blockEntity);

    @Invoker("done")
    void ae2addon$finishCluster();
}
