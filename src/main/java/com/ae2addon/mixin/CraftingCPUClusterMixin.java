package com.ae2addon.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2addon.AE2Addon;
import com.ae2addon.block.IntegratedCPUBE;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CraftingCPUCluster 改造（仅对含 IntegratedCPUBE 的簇生效）：
 * <p>
 * 1. 移除 AE2 的单方块 16 线程上限（addBlockEntity 常量 16 → Integer.MAX_VALUE），
 *    否则 IntegratedCPUBE 返回的大线程数会抛 IllegalArgumentException。
 * 2. getAvailableStorage() → Long.MAX_VALUE：无限存储的精确哨兵值。
 *    CPU 列表 / 合成确认界面 / tooltip 的「∞」显示都依赖它（=Long.MAX_VALUE 判断）。
 */
@Mixin(value = CraftingCPUCluster.class, remap = false, priority = 1200)
public class CraftingCPUClusterMixin {

    @ModifyConstant(
            method = "addBlockEntity(Lappeng/blockentity/crafting/CraftingBlockEntity;)V",
            constant = @Constant(intValue = 16),
            remap = false
    )
    private int ae2addon$modifyMaxAcceleratorThreads(int original) {
        return Integer.MAX_VALUE;
    }

    @Inject(method = "getAvailableStorage", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ae2addon$infiniteStorage(CallbackInfoReturnable<Long> callback) {
        if (!ae2addon$diagLogged) {
            ae2addon$diagLogged = true;
            boolean integrated = ae2addon$isIntegratedCpu((CraftingCPUCluster) (Object) this);
            AE2Addon.LOGGER.info("[ae2addon] getAvailableStorage 注入生效！集成CPU={}", integrated);
        }
        if (ae2addon$isIntegratedCpu((CraftingCPUCluster) (Object) this)) {
            callback.setReturnValue(Long.MAX_VALUE);
        }
    }

    /**
     * ⚠️ 2026-08-22 移除（原 done() 自动隐藏虚拟 lane）：
     * 任务完成即 removeVirtualCpu 导致 lane 刚创建就被移除——单次订单瞬间完成，
     * 界面永远看不到「线程 1」，量子分裂形同虚设（sensei 实测：主线程被占时
     * 线程 1 不出现）。改为<b>常驻空闲 lane</b>：lane 完成后保留，由
     * IntegratedCPUBE.ensureOneIdleCpu 的回收逻辑在「主簇空闲」时才清理
     * （主簇忙 → 1 个空闲 lane 常驻可见，新订单直接量子分裂并行；
     * 主簇空闲 → 回收多余空闲 lane）。
     */

    @Unique
    private static boolean ae2addon$diagLogged;

    @Unique
    private static boolean ae2addon$isIntegratedCpu(CraftingCPUCluster cluster) {
        try {
            var iterator = cluster.getBlockEntities();
            while (iterator.hasNext()) {
                if (iterator.next() instanceof IntegratedCPUBE) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 簇未成型/结构异常时保守返回 false
        }
        return false;
    }
}
