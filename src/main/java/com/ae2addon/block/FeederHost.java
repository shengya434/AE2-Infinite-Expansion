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

    boolean handleMarkerRightClick(int markerIndex, ItemStack carried);

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
}
