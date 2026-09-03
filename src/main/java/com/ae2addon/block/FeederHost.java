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

    /** Jade 等外部显示：蓄水池全部条目（物品/流体/化学物每类都列，不截断）；文本 translatable，语言在客户端解析。 */
    java.util.List<net.minecraft.network.chat.Component> reservoirTooltipLines();

    /** Jade 等外部显示：蓄水池条目按类型分组，每类最多 {@code perTypeLimit} 行（其余隐去）。 */
    static java.util.List<net.minecraft.network.chat.Component> buildReservoirLines(
            java.util.Map<AEKey, java.math.BigInteger> reservoir) {
        return buildReservoirLines(reservoir, 3);
    }

    /** 通用实现：物品/流体/化学物分组，每类限行数，超出显示「…等 N 种」；文本 translatable（2026-09-03 i18n）。 */
    static java.util.List<net.minecraft.network.chat.Component> buildReservoirLines(
            java.util.Map<AEKey, java.math.BigInteger> reservoir, int perTypeLimit) {
        java.util.List<net.minecraft.network.chat.Component> items = new java.util.ArrayList<>();
        java.util.List<net.minecraft.network.chat.Component> fluids = new java.util.ArrayList<>();
        java.util.List<net.minecraft.network.chat.Component> chems = new java.util.ArrayList<>();
        java.util.List<net.minecraft.network.chat.Component> others = new java.util.ArrayList<>();
        for (var e : reservoir.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            net.minecraft.network.chat.Component line;
            try {
                // 显示名 Component（translatable 保留 key → 客户端本地化）
                line = e.getKey().getDisplayName().copy()
                        .append(" §7x" + com.ae2addon.block.InfiniteInterfaceBE.fmt(e.getValue()));
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
        java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
        appendGroup(lines, "ae2addon.jade.group.items", items, perTypeLimit);
        appendGroup(lines, "ae2addon.jade.group.fluids", fluids, perTypeLimit);
        appendGroup(lines, "ae2addon.jade.group.chems", chems, perTypeLimit);
        appendGroup(lines, "ae2addon.jade.group.others", others, perTypeLimit);
        return lines;
    }

    private static void appendGroup(java.util.List<net.minecraft.network.chat.Component> out,
            String headerKey, java.util.List<net.minecraft.network.chat.Component> group, int limit) {
        if (group.isEmpty()) {
            return;
        }
        out.add(net.minecraft.network.chat.Component.translatable(headerKey));
        for (int i = 0; i < Math.min(group.size(), limit); i++) {
            out.add(net.minecraft.network.chat.Component.literal("  ").copy()
                    .append(group.get(i)));
        }
        if (group.size() > limit) {
            out.add(net.minecraft.network.chat.Component.translatable(
                    "ae2addon.jade.more", group.size() - limit));
        }
    }

    /** 手持升级卡右键接口 = 直接插入（AE2 玩法；已满/不支持返回 false）。 */
    default boolean insertUpgradeCard(ItemStack held) {
        return insertUpgradeCards(held, 1);
    }

    /**
     * 插入升级卡：limit=1 插一张（右键）；limit&lt;=0 全插直到满/手持空（shift+右键，2026-09-03）。
     *
     * @return 是否插入了至少一张
     */
    default boolean insertUpgradeCards(ItemStack held, int limit) {
        if (held.isEmpty() || !appeng.api.upgrades.Upgrades.isUpgradeCardItem(held)) {
            return false;
        }
        IUpgradeInventory inv = getUpgrades();
        net.minecraft.world.level.ItemLike card = held.getItem();
        boolean insertedAny = false;
        try {
            int max = inv.getMaxInstalled(card);
            if (max <= 0) {
                return false; // 本机不支持
            }
            int inserted = 0;
            while (held.getCount() > 0) {
                if (inv.getInstalledUpgrades(card) >= max) {
                    break; // 已满
                }
                if (limit > 0 && inserted >= limit) {
                    break;
                }
                boolean placed = false;
                for (int i = 0; i < inv.size(); i++) {
                    if (inv.getStackInSlot(i).isEmpty()) {
                        ItemStack put = held.copy();
                        put.setCount(1);
                        inv.setItemDirect(i, put);
                        held.shrink(1);
                        placed = true;
                        inserted++;
                        break;
                    }
                }
                if (!placed) {
                    break; // 无空槽
                }
            }
            insertedAny = inserted > 0;
        } catch (RuntimeException ignored) {
        }
        if (insertedAny) {
            setChanged();
        }
        return insertedAny;
    }
}
