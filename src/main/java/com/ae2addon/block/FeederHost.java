package com.ae2addon.block;

import java.math.BigInteger;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.orientation.RelativeSide;
import appeng.api.stacks.AEKey;
import appeng.api.upgrades.IUpgradeInventory;

/**
 * 供料站宿主抽象（2026-09-02 part 面板解耦）：GUI/菜单/开关共用的操作面。
 * 方块版 {@link InfiniteInterfaceBE} 与线缆部件版 part 都实现它——菜单/Screen 只依赖此接口。
 */
public interface FeederHost {

    SimpleContainer getPatternInventory();

    SimpleContainer getMarkerInventory();

    IUpgradeInventory getUpgrades();

    /** 已插容量卡数（决定样板/标记可用页数）。 */
    int capacityCards();

    /** 最大页数（0 起）。 */
    int maxPage();

    boolean isRemoved();

    /** 正面 = 机器所在方向（part 版 = 面板伸出方向）。 */
    @Nullable
    Direction getFront();

    @Nullable
    Level getLevel();

    BlockPos getBlockPos();

    /**
     * 标记槽点击：content=true（右键）= 容器内容物优先（流体/气体容器 → 内容物，普通物品 → 本体）；
     * content=false（左键）= 一律标记容器/物品本体。
     */
    boolean handleMarkerClick(int markerIndex, ItemStack carried, boolean content);

    void cycleMarkerTarget(int markerIndex);

    void markByKey(int markerIndex, AEKey key);

    /** 清除标记（同时退回蓄水池缓存）。 */
    void clearMarker(int markerIndex);

    /** 蓄水池概览 [种类数, 合计字符串]。 */
    String[] reservoirSummary();

    BigInteger totalFed();

    long feedRatePerSecond();

    long rejectRatePerSecond();

    long stockTargetValue();

    int restockIntervalValue();

    int feedBudgetValue();

    /** 某 key 的补货目标量（每标记独立目标，缺省全局）。 */
    long targetFor(AEKey key);

    boolean activeExtract();

    boolean activeFeed();

    boolean activeMarkerFeed();

    RelativeSide extractSide();

    /** GUI 开关切换（"extract"/"feed"/"markerFeed"/"dir"）。 */
    void toggleActive(String which);

    void cycleExtractSide();

    /** GUI 参数保存（key: stockTarget/restockInterval/feedBudget）。 */
    void setPerBlockParam(String key, long value);

    /** 每标记独立缓存目标（中键循环）。 */
    void setMarkerTarget(int markerIndex, long target);

    // ── 配置卡/内存卡复制粘贴所需（2026-09-02 part 兼容） ──

    long pStockTarget();

    void pStockTarget(long v);

    int pRestockInterval();

    void pRestockInterval(int v);

    int pFeedBudget();

    void pFeedBudget(int v);

    void setActiveExtract(boolean v);

    void setActiveFeed(boolean v);

    void setExtractSide(RelativeSide side);

    java.util.Map<AEKey, Long> markerTargetsSnapshot();

    void markerTargetsClear();

    void markerTargetsPut(AEKey key, long target);

    void setChanged();

    /** Jade 等外部显示：蓄水池全部条目文本行（物品/流体/化学物每类都列，不截断）。 */
    java.util.List<String> reservoirTooltipLines();

    /** 通用实现：遍历蓄水池生成 "显示名 §7x数量" 行（物品/流体/化学物统一处理）。 */
    static java.util.List<String> buildReservoirLines(
            java.util.Map<AEKey, java.math.BigInteger> reservoir) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (var e : reservoir.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            try {
                String name = e.getKey().getDisplayName().getString();
                lines.add(name + " §7x" + com.ae2addon.block.InfiniteInterfaceBE.fmt(e.getValue()));
            } catch (RuntimeException ignored) {
                // 个别 key 显示名异常不阻塞整体
            }
        }
        return lines;
    }
}
