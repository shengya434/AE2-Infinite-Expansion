package com.ae2addon.mixin;

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

    /** 内存卡处理入口：卡里有本 mod 配置 → 导入；否则导出。返回 true=已处理。 */
    public static boolean handleUse(InfiniteInterfaceBE feeder, Player player, ItemStack card) {
        if (feeder == null || player == null || card.isEmpty()
                || !(card.getItem() instanceof MemoryCardItem)) {
            return false;
        }
        CompoundTag tag = card.getOrCreateTag();
        if (tag.contains(CFG_KEY)) {
            // 导入
            try {
                CompoundTag cfg = tag.getCompound(CFG_KEY);
                importConfig(feeder, cfg);
                ((MemoryCardItem) card.getItem()).notifyUser(
                        player, appeng.api.implementations.items.MemoryCardMessages.SETTINGS_LOADED);
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon] 内存卡导入: 参数=({},{},{}) 开关=({},{}) 方向={} 标记数={} 目标数={}",
                        feeder.pStockTarget, feeder.pRestockInterval, feeder.pFeedBudget,
                        feeder.activeExtract, feeder.activeFeed, feeder.extractSide,
                        cfg.getList("markers", 10).size(), cfg.getList("markerTargets", 10).size());
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 内存卡导入失败", e);
            }
        } else {
            // 导出
            try {
                CompoundTag cfg = new CompoundTag();
                exportConfig(feeder, cfg);
                tag.put(CFG_KEY, cfg);
                tag.putString("ae2addon:name", "ME接口(无限级)");
                ((MemoryCardItem) card.getItem()).notifyUser(
                        player, appeng.api.implementations.items.MemoryCardMessages.SETTINGS_SAVED);
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon] 内存卡导出: 参数=({},{},{}) 开关=({},{}) 方向={} 标记数={} 目标数={}",
                        feeder.pStockTarget, feeder.pRestockInterval, feeder.pFeedBudget,
                        feeder.activeExtract, feeder.activeFeed, feeder.extractSide,
                        cfg.getList("markers", 10).size(), cfg.getList("markerTargets", 10).size());
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 内存卡导出失败", e);
            }
        }
        return true;
    }

    private static void exportConfig(InfiniteInterfaceBE feeder, CompoundTag cfg) {
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
