package com.ae2addon.block;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2addon.api.IntegratedCraftingServiceBridge;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.mixin.CraftingCPUClusterAccessor;
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
     * 常驻空闲 lane 数（2026-08-22：1 → 16；2026-08-27：config idleLaneTarget 可配）。
     * 主簇忙时一个 tick 内 burst 建满，让巨型订单批次立即并行——原来 1 lane/tick
     * 串行建立（每批占掉 lane 后下 tick 才补 1 个），sensei 实测「线程创建速度
     * 1线程/t」批次推进极慢。主簇空闲时回收全部空闲 lane，不空转。
     */
    private static volatile int IDLE_LANE_TARGET = com.ae2addon.config.AE2AddonConfig.idleLaneTarget();

    /** 配置热加载时由 AE2AddonConfig 调用（更新空闲 lane 池大小）。 */
    public static void applyConfig() {
        IDLE_LANE_TARGET = com.ae2addon.config.AE2AddonConfig.idleLaneTarget();
    }

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
        // config cpuDisplayBytes（默认 Long.MAX_VALUE）：无限存储显示。
        // 单个核心方块相加不会溢出；若同簇混入其他贡献存储的
        // CraftingBlockEntity 会溢出为负（历史教训）。真实存储无限。
        return formed ? com.ae2addon.config.AE2AddonConfig.cpuDisplayBytes() : 0;
    }

    /**
     * 只有成型且包含并行处理器时才有线程。
     * Integer.MAX_VALUE−1：拉满账面并行度（AE2 内部 +1 后恰好 Integer.MAX_VALUE，不溢出）。
     * CraftingCpuLogicMixin 的时间片限流接管真实执行，线程数只是好看的数字，
     * 单 tick 实际只跑预算内的工作量，不会爆炸。
     * <p>
     * 兼容性保险（2026-08-21）：限流 mixin 注入被其他 mod（gtlcore/gtocore 等）
     * 干扰或 AE2 版本不兼容时，{@link com.ae2addon.mixin.CraftingCpuLogicMixin#ae2addon$isTimeSliceActive()}
     * 为 false → 回退保守线程数（16），避免无时间片保护的高线程单 tick 循环爆炸。
     */
    @Override
    public int getAcceleratorThreads() {
        if (!formed || !hasCoProcessing) {
            return 0;
        }
        if (!com.ae2addon.crafting.CraftingCompat.timeSliceActive) {
            return 16;
        }
        // config cpuDisplayThreads（0 = Integer.MAX_VALUE-1 拉满）：
        // 账面并行度显示值；CraftingCpuLogicMixin 的时间片限流接管真实执行，
        // 单 tick 实际只跑预算内的工作量，不会爆炸。
        return com.ae2addon.config.AE2AddonConfig.cpuDisplayThreads();
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
     * 每 tick lane 维护（2026-08-22）：确保空闲 lane 存在并注册进 CraftingService。
     * 由 CraftingCpuLogicMixin.beginDispatchBudget（tickCraftingLogic HEAD，每 tick
     * 对每个已注册簇触发）调用——updateCPUClusters 是事件驱动（updateList 脏标记），
     * 稳态运行时不触发，靠它建 lane 会饿死（sensei 实测：主簇忙时线程1不出现）。
     */
    public void refreshLanes() {
        if (!formed || getCluster() == null) {
            return;
        }
        ensureOneIdleCpu();
        var bridge = ae2addon$craftingBridge();
        if (bridge == null) {
            return;
        }
        for (var cpu : allCpus()) {
            if (!cpu.isDestroyed() && cpu.isActive()) {
                bridge.ae2addon$registerCpu(cpu);
            }
        }
    }

    /**
     * 保证常驻线程数（IDLE_LANE_TARGET 个 lane，含主簇），并回收多余空闲。
     */
    public void ensureOneIdleCpu() {
        if (!formed || getCluster() == null) {
            return;
        }
        if (!getCluster().isBusy()) {
            // 主簇空闲：清空空闲虚拟 lane（只留主簇），避免 16 个空转
            reapIdleLanes(0);
            return;
        }
        // 主簇忙：确保 IDLE_LANE_TARGET 个空闲 lane（一个 tick 内 burst 建满）
        int idleCount = 0;
        for (var cpu : virtualCpus) {
            if (!cpu.isBusy() && !cpu.isDestroyed()) {
                idleCount++;
            }
        }
        while (idleCount < IDLE_LANE_TARGET) {
            createVirtualCpu();
            idleCount++;
        }
        // 回收超标空闲（任务完成留下的多余 lane）
        reapIdleLanes(IDLE_LANE_TARGET);
    }

    /** 回收空闲虚拟 lane，保留前 keepIdle 个空闲（按列表顺序）。
     *  2026-08-27：增加对「isBusy 假阳性」lane 的强制回收——批次 link 取消后
     *  AE2 job 清理可能延迟/丢失，cluster.isBusy() 永久 true → lane 泄漏
     *  （sensei 实测：恢复订单取消后 lane 累积到 64+ 个）。
     *  通过公开 API getLastLink().isCanceled() 判断任务已取消 → 强制 cancelJob 后回收。 */
    private void reapIdleLanes(int keepIdle) {
        var bridge = ae2addon$craftingBridge();
        int idleKept = 0;
        Iterator<CraftingCPUCluster> iterator = virtualCpus.iterator();
        while (iterator.hasNext()) {
            var cpu = iterator.next();
            if (cpu.isDestroyed()) {
                iterator.remove();
                if (bridge != null) {
                    bridge.ae2addon$unregisterCpu(cpu);
                }
                continue;
            }
            if (!cpu.isBusy()) {
                if (idleKept < keepIdle) {
                    idleKept++;
                    continue;
                }
                iterator.remove();
                if (bridge != null) {
                    bridge.ae2addon$unregisterCpu(cpu);
                }
                continue;
            }
            // isBusy 但底层任务已取消（link canceled）：强制清理后回收，防泄漏
            try {
                var link = cpu.craftingLogic.getLastLink();
                if (link != null && link.isCanceled()) {
                    cpu.cancelJob();
                    iterator.remove();
                    if (bridge != null) {
                        bridge.ae2addon$unregisterCpu(cpu);
                    }
                }
            } catch (RuntimeException ignored) {
                // 反射/状态读取失败：保留 lane，下轮再试
            }
        }
    }

    /**
     * 按需创建 lane 并立即注册（2026-08-22：批次无空闲 CPU 时调用，
     * 每批一个 lane 全并行——「int 级别」并行度，但按需创建不会像
     * IDLE_LANE_TARGET=Integer.MAX_VALUE 那样无界建到 OOM）。
     * 已有空闲 lane 时直接返回（不新建）。
     */
    public CraftingCPUCluster createAndRegisterLane() {
        var cpu = getOrCreateIdleCpu();
        if (cpu != null && !cpu.isDestroyed() && cpu.isActive()) {
            var bridge = ae2addon$craftingBridge();
            if (bridge != null) {
                bridge.ae2addon$registerCpu(cpu);
            }
            return cpu;
        }
        return null;
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
     * 虚拟 CPU lane 手动移除（2026-08-22 起不再由 done() 自动调用——
     * 改为常驻空闲 lane，由 {@link #ensureOneIdleCpu} 的回收逻辑在
     * 主簇空闲时清理）。保留供手动管理/未来使用。
     * <p>
     * 从 lane 列表移除，并从 CraftingService 的 craftingCPUClusters 集合剔除，
     * 界面线程列表立即消失。主簇（cpu == getCluster()）不隐藏。
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
