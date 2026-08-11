package com.ae2addon.block;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.mixin.CraftingCPUClusterAccessor;
import com.ae2addon.mixin.IntegratedCraftingServiceBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 集成型 CPU 方块实体。
 * <p>
 * 多方块 3×5×3 成型后：
 * - 始终提供无限合成存储（Long.MAX_VALUE，单核心方块不溢出；
 *   若簇内混入其他贡献存储的方块会相加溢出，请勿混用）
 * - 如果结构内包含无限并行处理器，额外提供拉满并行（Integer.MAX_VALUE−1 账面值，
 *   真实执行由 CraftingCpuLogicMixin 的时间片限流）
 * - 未成型时返回 0，使其无法接入 AE 网络
 * <p>
 * 教训（2026-07-10 + 2026-08-06 + 2026-08-10）：
 * - Long.MAX_VALUE → CraftingCPUCluster.addBlockEntity 里 storage += bytes 会溢出为负 → 存不下材料（多个贡献方块相加时）
 * - Integer.MAX_VALUE → tickCraftingLogic 里 getCoProcessors()+1 溢出为负 → ops<=0 → CPU 永不执行（故用 MAX_VALUE−1）
 * - 高线程数 → 单 tick 循环爆炸 → 时间片限流（2026-08-10，参考 OmniSequence-Transfinite）
 */
public class IntegratedCPUBE extends CraftingBlockEntity {

    private boolean formed = false;
    private boolean hasCoProcessing = false;

    /**
     * 虚拟 CPU lane（量子分裂线程）：主簇忙时，新任务自动分配到空闲 lane，
     * 多个订单并行执行。思路来自 OmniSequence-Transfinite 的 Omni-Computation Core。
     */
    private final List<CraftingCPUCluster> virtualCpus = new ArrayList<>();

    /**
     * 常驻线程数（含主线程）：1 = 只保底主簇，虚拟 lane 用完即收。
     * 主簇忙时会自动补建 1 个空闲 lane 并行；任务完成后由
     * CraftingCPUClusterMixin.done() 注入触发隐藏（removeVirtualCpu）。
     */
    private static final int IDLE_LANE_TARGET = 1;

    public IntegratedCPUBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INTEGRATED_CPU.get(), pos, state);
    }

    @Override
    public void onReady() {
        super.onReady();
        IntegratedCPURegistry.register(this);
    }

    @Override
    public void onChunkUnloaded() {
        IntegratedCPURegistry.unregister(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        IntegratedCPURegistry.unregister(this);
        super.setRemoved();
    }

    public boolean isFormed() {
        return formed;
    }

    public void setFormed(boolean formed) {
        this.formed = formed;
        if (formed) {
            ensureOneIdleCpu();
        }
        setChanged();
    }

    /**
     * 在结构检测时设置是否包含无限并行处理器。
     */
    public void setHasCoProcessing(boolean has) {
        this.hasCoProcessing = has;
        setChanged();
    }

    public boolean hasCoProcessing() {
        return hasCoProcessing;
    }

    @Override
    public long getStorageBytes() {
        // 未成型 → 不贡献存储，无法接入网络
        // Long.MAX_VALUE：无限存储显示。单个核心方块相加不会溢出；
        // 若同簇混入其他贡献存储的 CraftingBlockEntity 会溢出为负（历史教训）
        return formed ? Long.MAX_VALUE : 0;
    }

    /**
     * 只有成型且包含并行处理器时才有线程。
     * Integer.MAX_VALUE−1：拉满账面并行度（AE2 内部 +1 后恰好 Integer.MAX_VALUE，不溢出）。
     * CraftingCpuLogicMixin 的时间片限流接管真实执行，线程数只是好看的数字，
     * 单 tick 实际只跑预算内的工作量，不会爆炸。
     */
    @Override
    public int getAcceleratorThreads() {
        return (formed && hasCoProcessing) ? Integer.MAX_VALUE - 1 : 0;
    }

    /**
     * 所有 CPU lane（主簇 + 虚拟 lane）。
     */
    public List<CraftingCPUCluster> allCpus() {
        var result = new ArrayList<CraftingCPUCluster>(virtualCpus.size() + 1);
        var primary = getCluster();
        if (primary != null && !primary.isDestroyed()) {
            result.add(primary);
        }
        for (var cpu : virtualCpus) {
            if (!cpu.isDestroyed()) {
                result.add(cpu);
            }
        }
        return result;
    }

    /**
     * 创建虚拟 CPU lane（挂在本方块的网格节点上）。
     */
    private CraftingCPUCluster createVirtualCpu() {
        var cpu = new CraftingCPUCluster(worldPosition, worldPosition);
        var accessor = (CraftingCPUClusterAccessor) (Object) cpu;
        accessor.ae2addon$addBlockEntity(this);
        accessor.ae2addon$finishCluster();
        virtualCpus.add(cpu);
        return cpu;
    }

    /**
     * 保证常驻线程数（IDLE_LANE_TARGET 个 lane，含主簇），并回收多余空闲。
     */
    public void ensureOneIdleCpu() {
        if (!formed || getCluster() == null) {
            return;
        }
        int idleCount = getCluster().isBusy() ? 0 : 1;
        for (var cpu : virtualCpus) {
            if (!cpu.isBusy() && !cpu.isDestroyed()) {
                idleCount++;
            }
        }
        // 常驻：空闲 lane 不足时补建（预分裂，界面列表一直可见）
        while (idleCount < IDLE_LANE_TARGET) {
            createVirtualCpu();
            idleCount++;
        }
        // 回收多余空闲（保留 IDLE_LANE_TARGET 个）
        if (idleCount > IDLE_LANE_TARGET) {
            var bridge = ae2addon$craftingBridge();
            Iterator<CraftingCPUCluster> iterator = virtualCpus.iterator();
            while (iterator.hasNext() && idleCount > IDLE_LANE_TARGET) {
                var cpu = iterator.next();
                if (!cpu.isBusy()) {
                    iterator.remove();
                    if (bridge != null) {
                        bridge.ae2addon$unregisterCpu(cpu);
                    }
                    idleCount--;
                }
            }
        }
    }

    /**
     * 获取空闲 lane；没有则创建。
     */
    public CraftingCPUCluster getOrCreateIdleCpu() {
        if (!formed || getCluster() == null) {
            return null;
        }
        for (var cpu : allCpus()) {
            if (!cpu.isBusy() && !cpu.isDestroyed()) {
                return cpu;
            }
        }
        return createVirtualCpu();
    }

    /**
     * 虚拟 CPU lane 任务完成后的隐藏回调（由 CraftingCPUClusterMixin 的
     * done() 注入调用）：从 lane 列表移除，并从 CraftingService 的
     * craftingCPUClusters 集合剔除，界面线程列表立即消失。
     * <p>
     * 主簇完成任务也会走到这里，但主簇不隐藏（cpu == getCluster() 直接返回）。
     */
    public void removeVirtualCpu(CraftingCPUCluster cpu) {
        if (cpu == null || cpu == getCluster()) {
            return;
        }
        virtualCpus.remove(cpu);
        var bridge = ae2addon$craftingBridge();
        if (bridge != null) {
            bridge.ae2addon$unregisterCpu(cpu);
        }
    }

    /**
     * 从 AE 网格拿到 CraftingService 桥接（用于注册/注销虚拟 lane）。
     * 网格不可用时返回 null（下次 refresh 会重建集合）。
     */
    private IntegratedCraftingServiceBridge ae2addon$craftingBridge() {
        try {
            var node = getMainNode().getNode();
            if (node != null && node.getGrid() != null
                    && node.getGrid().getCraftingService()
                            instanceof IntegratedCraftingServiceBridge bridge) {
                return bridge;
            }
        } catch (RuntimeException ignored) {
            // 网格未就绪
        }
        return null;
    }

    public int getCpuLaneCount() {
        return allCpus().size();
    }

    public int getActiveJobCount() {
        int active = 0;
        for (var cpu : allCpus()) {
            if (cpu.isBusy()) {
                active++;
            }
        }
        return active;
    }

    // ── AE 网格控制：未成型时不暴露给 AE 网络 ──

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        if (!formed) return null;
        return super.getGridNode(dir);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return formed ? super.getCableConnectionType(dir) : AECableType.NONE;
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("formed", formed);
        tag.putBoolean("hasCo", hasCoProcessing);
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        hasCoProcessing = tag.getBoolean("hasCo");
    }
}
