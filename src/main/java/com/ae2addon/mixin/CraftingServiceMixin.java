package com.ae2addon.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.ae2addon.api.IntegratedCraftingServiceBridge;
import com.ae2addon.block.IntegratedCPURegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * 量子分裂线程（虚拟 CPU lane）：把集成 CPU 的虚拟 lane 注册进 AE2 的
 * CraftingService CPU 集合，实现多订单并行；目标 CPU 忙时自动改用空闲 lane。
 * 思路来自 OmniSequence-Transfinite 的 OmniCraftingServiceMixin。
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceMixin implements IntegratedCraftingServiceBridge {

    @Shadow
    @Final
    private Set<CraftingCPUCluster> craftingCPUClusters;

    @Shadow
    @Final
    private IGrid grid;

    @Inject(method = "updateCPUClusters", at = @At("RETURN"))
    private void ae2addon$registerVirtualCpus(CallbackInfo callback) {
        ae2addon$refreshIntegratedCpus();
    }

    @Inject(method = "submitJob", at = @At("RETURN"))
    private void ae2addon$keepSpareCpuAfterSubmit(ICraftingPlan job,
            ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        ae2addon$refreshIntegratedCpus();
    }

    /**
     * 目标集成 CPU 忙时，自动把任务重定向到空闲虚拟 lane。
     */
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void ae2addon$redirectBusyIntegratedCpu(ICraftingPlan job,
            ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource source,
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (job == null || job.simulation()
                || !(target instanceof CraftingCPUCluster selected)
                || !selected.isBusy()) {
            return;
        }
        var owner = IntegratedCPURegistry.ownerOf(selected);
        if (owner == null || !owner.isFormed()) {
            return;
        }
        var replacement = owner.getOrCreateIdleCpu();
        if (replacement == null || replacement == selected) {
            return;
        }
        if (!ae2addon$diagLogged) {
            ae2addon$diagLogged = true;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 量子分裂生效！busy CPU={} → 重定向到空闲 lane={}",
                    selected.getCoProcessors(), replacement.getCoProcessors());
        }
        callback.setReturnValue(replacement.submitJob(grid, job, source, requestingMachine));
        owner.ensureOneIdleCpu();
    }

    @Unique
    private static boolean ae2addon$diagLogged;

    private void ae2addon$refreshIntegratedCpus() {
        int registered = 0;
        for (var blockEntity : IntegratedCPURegistry.all()) {
            if (blockEntity.isRemoved() || !blockEntity.isFormed()) {
                continue;
            }
            // 提交任务后主簇可能已忙：确保有空闲虚拟 lane 再注册，
            // 否则自动模式选 CPU 时看不到可用的 lane（量子分裂失效的根因）
            blockEntity.ensureOneIdleCpu();
            for (var cpu : blockEntity.allCpus()) {
                if (!cpu.isDestroyed() && cpu.isActive()) {
                    ae2addon$registerCpu(cpu);
                    registered++;
                }
            }
        }
        if (registered != ae2addon$lastRegisteredCount) {
            ae2addon$lastRegisteredCount = registered;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 虚拟CPU注册: 当前注册{}个lane（注册表内方块{}个）",
                    registered, IntegratedCPURegistry.all().size());
        }
    }

    @Unique
    private static int ae2addon$lastRegisteredCount = -1;

    // ── IntegratedCraftingServiceBridge 实现 ──

    @Override
    public void ae2addon$unregisterCpu(CraftingCPUCluster cluster) {
        if (cluster != null) {
            craftingCPUClusters.remove(cluster);
        }
    }

    @Override
    public void ae2addon$registerCpu(CraftingCPUCluster cluster) {
        if (cluster != null && !cluster.isDestroyed()) {
            craftingCPUClusters.add(cluster);
        }
    }
}
