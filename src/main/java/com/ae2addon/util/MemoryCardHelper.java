package com.ae2addon.util;

import appeng.items.tools.MemoryCardItem;
import com.ae2addon.block.InfiniteInterfaceBE;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 内存卡配置导出/导入共享逻辑（mixin 与方块 onActivated 共用）：
 * 导出 = 升级卡(完整NBT) + 每接口参数 + 标记 + 每标记缓存目标 + 开关 + 抽取方向。
 */
public final class MemoryCardHelper {

    public static final String CFG_KEY = "ae2addon:cfg";

    private MemoryCardHelper() {
    }

    /** 复制（写入卡）：右键。返回 true=已处理。 */
    public static boolean handleCopy(InfiniteInterfaceBE feeder, Player player, ItemStack card) {
        if (feeder == null || player == null || card.isEmpty()) {
            return false;
        }
        try {
            CompoundTag cfg = new CompoundTag();
            exportConfig(feeder, cfg);
            card.getOrCreateTag().put(CFG_KEY, cfg);
            card.getOrCreateTag().putString("ae2addon:name", "ME接口(无限级)");
            notify(player, card, false);
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 配置卡复制: 参数=({},{},{}) 开关=({},{}) 方向={} 样板数={} 标记数={} 目标数={}",
                    feeder.pStockTarget, feeder.pRestockInterval, feeder.pFeedBudget,
                    feeder.activeExtract, feeder.activeFeed, feeder.extractSide,
                    cfg.getList("patterns", 10).size(), cfg.getList("markers", 10).size(),
                    cfg.getList("markerTargets", 10).size());
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 配置卡复制失败", e);
        }
        return true;
    }

    /** 粘贴（读取卡 → 写入接口）：shift+右键。返回 true=已处理。 */
    public static boolean handlePaste(InfiniteInterfaceBE feeder, Player player, ItemStack card) {
        if (feeder == null || player == null || card.isEmpty()) {
            return false;
        }
        CompoundTag tag = card.getOrCreateTag();
        if (!tag.contains(CFG_KEY)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c[ae2addon] 卡是空的，先右键接口复制配置"), false);
            return true;
        }
        try {
            CompoundTag cfg = tag.getCompound(CFG_KEY);
            importConfig(feeder, cfg);
            notify(player, card, true);
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon] 配置卡粘贴: 参数=({},{},{}) 开关=({},{}) 方向={} 样板数={} 标记数={} 目标数={}",
                    feeder.pStockTarget, feeder.pRestockInterval, feeder.pFeedBudget,
                    feeder.activeExtract, feeder.activeFeed, feeder.extractSide,
                    cfg.getList("patterns", 10).size(), cfg.getList("markers", 10).size(),
                    cfg.getList("markerTargets", 10).size());
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 配置卡粘贴失败", e);
        }
        return true;
    }

    /** 操作提示：AE2 内存卡用原版气泡，配置存储卡用聊天消息。 */
    private static void notify(Player player, ItemStack card, boolean loaded) {
        if (card.getItem() instanceof MemoryCardItem mci) {
            mci.notifyUser(player, loaded
                    ? appeng.api.implementations.items.MemoryCardMessages.SETTINGS_LOADED
                    : appeng.api.implementations.items.MemoryCardMessages.SETTINGS_SAVED);
        } else {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    loaded ? "§a[ae2addon] 配置已载入" : "§a[ae2addon] 配置已保存"), false);
        }
    }

    private static void exportConfig(InfiniteInterfaceBE feeder, CompoundTag cfg) {
        // 样板槽（编码样板物品完整 NBT——样板定义直接转移，无需空白样板）
        ListTag patternList = new ListTag();
        var patternInv = feeder.getPatternInventory();
        for (int i = 0; i < patternInv.getContainerSize(); i++) {
            ItemStack st = patternInv.getItem(i);
            if (!st.isEmpty()) {
                CompoundTag ent = new CompoundTag();
                ent.putInt("Slot", i);
                st.save(ent);
                patternList.add(ent);
            }
        }
        cfg.put("patterns", patternList);
        // 升级卡（完整 ItemStack 含 NBT——频道卡频率/绑定不丢）
        ListTag upgradeList = new ListTag();
        var upgrades = feeder.getUpgrades();
        for (int i = 0; i < upgrades.size(); i++) {
            ItemStack st = upgrades.getStackInSlot(i);
            if (!st.isEmpty()) {
                CompoundTag ent = new CompoundTag();
                ent.putInt("Slot", i);
                st.save(ent);
                upgradeList.add(ent);
            }
        }
        cfg.put("upgrades", upgradeList);
        cfg.putLong("pStockTarget", feeder.pStockTarget);
        cfg.putInt("pRestockInterval", feeder.pRestockInterval);
        cfg.putInt("pFeedBudget", feeder.pFeedBudget);
        cfg.putBoolean("activeExtract", feeder.activeExtract);
        cfg.putBoolean("activeFeed", feeder.activeFeed);
        cfg.putString("extractSide", feeder.extractSide.name());
        // 标记槽（虚拟标记）
        ListTag markerList = new ListTag();
        var markerInv = feeder.getMarkerInventory();
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            ItemStack st = markerInv.getItem(i);
            if (!st.isEmpty()) {
                CompoundTag ent = new CompoundTag();
                ent.putInt("Slot", i);
                st.save(ent);
                markerList.add(ent);
            }
        }
        cfg.put("markers", markerList);
        // 每标记缓存目标
        ListTag targetList = new ListTag();
        for (var e : feeder.markerTargetsSnapshot().entrySet()) {
            CompoundTag ent = new CompoundTag();
            ent.put("Key", appeng.items.misc.WrappedGenericStack.wrap(e.getKey(), 1).save(new CompoundTag()));
            ent.putLong("Target", e.getValue());
            targetList.add(ent);
        }
        cfg.put("markerTargets", targetList);
    }

    private static void importConfig(InfiniteInterfaceBE feeder, CompoundTag cfg) {
        // 样板槽恢复（先清空再写入；编码样板 NBT 直接重建物品）
        var patternInv = feeder.getPatternInventory();
        patternInv.clearContent();
        ListTag patternList = cfg.getList("patterns", Tag.TAG_COMPOUND);
        for (int i = 0; i < patternList.size(); i++) {
            CompoundTag ent = patternList.getCompound(i);
            ItemStack st = ItemStack.of(ent);
            int slot = ent.getInt("Slot");
            if (slot >= 0 && slot < patternInv.getContainerSize() && !st.isEmpty()) {
                patternInv.setItem(slot, st);
            }
        }
        // 升级卡恢复（先清空再写入）
        var upgrades = feeder.getUpgrades();
        for (int i = 0; i < upgrades.size(); i++) {
            upgrades.setItemDirect(i, ItemStack.EMPTY);
        }
        ListTag upgradeList = cfg.getList("upgrades", Tag.TAG_COMPOUND);
        for (int i = 0; i < upgradeList.size(); i++) {
            CompoundTag ent = upgradeList.getCompound(i);
            ItemStack st = ItemStack.of(ent);
            int slot = ent.getInt("Slot");
            if (slot >= 0 && slot < upgrades.size() && !st.isEmpty()) {
                upgrades.setItemDirect(slot, st);
            }
        }
        feeder.pStockTarget = cfg.getLong("pStockTarget");
        feeder.pRestockInterval = cfg.getInt("pRestockInterval");
        feeder.pFeedBudget = cfg.getInt("pFeedBudget");
        feeder.activeExtract = cfg.getBoolean("activeExtract");
        feeder.activeFeed = cfg.getBoolean("activeFeed");
        try {
            feeder.extractSide = appeng.api.orientation.RelativeSide.valueOf(cfg.getString("extractSide"));
        } catch (RuntimeException ignored) {
        }
        // 标记
        var markerInv = feeder.getMarkerInventory();
        markerInv.clearContent();
        ListTag markerList = cfg.getList("markers", Tag.TAG_COMPOUND);
        for (int i = 0; i < markerList.size(); i++) {
            CompoundTag ent = markerList.getCompound(i);
            ItemStack st = ItemStack.of(ent);
            int slot = ent.getInt("Slot");
            if (slot >= 0 && slot < markerInv.getContainerSize() && !st.isEmpty()) {
                markerInv.setItem(slot, st);
            }
        }
        // 缓存目标
        feeder.markerTargetsClear();
        ListTag targetList = cfg.getList("markerTargets", Tag.TAG_COMPOUND);
        for (int i = 0; i < targetList.size(); i++) {
            CompoundTag ent = targetList.getCompound(i);
            try {
                ItemStack st = ItemStack.of(ent.getCompound("Key"));
                if (st.getItem() instanceof appeng.items.misc.WrappedGenericStack wgs) {
                    var key = wgs.unwrapWhat(st);
                    if (key != null) {
                        feeder.markerTargetsPut(key, ent.getLong("Target"));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        feeder.setChanged();
    }
}
