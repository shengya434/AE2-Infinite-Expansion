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

    /** Jade 等外部显示：蓄水池条目按类型分组，每类最多 {@code perTypeLimit} 行（其余隐去）。 */
    static java.util.List<String> buildReservoirLines(
            java.util.Map<AEKey, java.math.BigInteger> reservoir) {
        return buildReservoirLines(reservoir, 3);
    }

    /** 通用实现：物品/流体/化学物分组，每类限行数，超出显示「…等 N 种」。 */
    static java.util.List<String> buildReservoirLines(
            java.util.Map<AEKey, java.math.BigInteger> reservoir, int perTypeLimit) {
        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<String> fluids = new java.util.ArrayList<>();
        java.util.List<String> chems = new java.util.ArrayList<>();
        java.util.List<String> others = new java.util.ArrayList<>();
        for (var e : reservoir.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            String line;
            try {
                String name = e.getKey().getDisplayName().getString();
                line = name + " §7x" + com.ae2addon.block.InfiniteInterfaceBE.fmt(e.getValue());
            } catch (RuntimeException ignored) {
                continue;
            }
            AEKey key = e.getKey();
            if (key instanceof appeng.api.stacks.AEItemKey) {
                items.add(line);
            } else if (key instanceof appeng.api.stacks.AEFluidKey) {
                fluids.add(line);
            } else if (key instanceof me.ramidzkh.mekae2.ae2.MekanismKey) {
                chems.add(line);
            } else {
                others.add(line);
            }
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        appendGroup(lines, "§e物品", items, perTypeLimit);
        appendGroup(lines, "§b流体", fluids, perTypeLimit);
        appendGroup(lines, "§d化学", chems, perTypeLimit);
        appendGroup(lines, "§7其他", others, perTypeLimit);
        return lines;
    }

    private static void appendGroup(java.util.List<String> out, String header,
            java.util.List<String> group, int limit) {
        if (group.isEmpty()) {
            return;
        }
        out.add(header);
        for (int i = 0; i < Math.min(group.size(), limit); i++) {
            out.add("  " + group.get(i));
        }
        if (group.size() > limit) {
            out.add("  §7…等 " + (group.size() - limit) + " 种");
        }
    }

    /** 手持升级卡右键接口 = 直接插入（AE2 玩法；已满/不支持返回 false）。 */
    default boolean insertUpgradeCard(ItemStack held) {
        if (held.isEmpty() || !appeng.api.upgrades.Upgrades.isUpgradeCardItem(held)) {
            return false;
        }
        IUpgradeInventory inv = getUpgrades();
        net.minecraft.world.level.ItemLike card = held.getItem();
        try {
            int installed = inv.getInstalledUpgrades(card);
            int max = inv.getMaxInstalled(card);
            if (max <= 0 || installed >= max) {
                return false; // 本机不支持或已满
            }
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStackInSlot(i).isEmpty()) {
                    ItemStack put = held.copy();
                    put.setCount(1);
                    inv.setItemDirect(i, put);
                    held.shrink(1);
                    setChanged();
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }
}
