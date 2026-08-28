package com.ae2addon.mixin;

import appeng.items.tools.MemoryCardItem;
import appeng.util.InteractionUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AE2 内存卡对 ME接口（无限级）的配置复制支持（2026-08-28 sensei）：
 * 手持内存卡右键接口 = 导出（升级卡+每接口参数+标记+开关+方向），
 * 再右键另一接口 = 导入。Alt+右键仍为原版清卡。
 */
@Mixin(MemoryCardItem.class)
public class MemoryCardItemMixin {

    private static final String CFG_KEY = "ae2addon:cfg";

    static {
        com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] MemoryCardItemMixin 类已加载");
    }

    @Inject(method = "m_6225_", at = @At("HEAD"), cancellable = true)
    private void ae2addon$handleFeeder(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        com.ae2addon.AE2Addon.LOGGER.info("[ae2addon] 内存卡 useOn 触发（目标方块={}）",
                ctx.getLevel().getBlockEntity(ctx.getClickedPos()) == null ? "null"
                        : ctx.getLevel().getBlockEntity(ctx.getClickedPos()).getClass().getSimpleName());
        Level level = ctx.getLevel();
        BlockEntity be = level.getBlockEntity(ctx.getClickedPos());
        if (!(be instanceof com.ae2addon.block.InfiniteInterfaceBE feeder)) {
            return; // 非本 mod 方块：交给原逻辑
        }
        Player player = ctx.getPlayer();
        if (player == null) {
            return;
        }
        ItemStack card = ctx.getItemInHand();
        CompoundTag tag = card.getOrCreateTag();
        if (tag.contains(CFG_KEY)) {
            // 导入
            try {
                CompoundTag cfg = tag.getCompound(CFG_KEY);
                importFeederConfig(feeder, cfg); // 升级卡（完整NBT）+ 参数/标记/开关/方向
                ((MemoryCardItem) card.getItem()).notifyUser(player, appeng.api.implementations.items.MemoryCardMessages.SETTINGS_LOADED);
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 内存卡导入失败", e);
            }
        } else {
            // 导出
            try {
                CompoundTag cfg = new CompoundTag();
                exportFeederConfig(feeder, cfg); // 升级卡（完整NBT）+ 参数/标记/开关/方向
                tag.put(CFG_KEY, cfg);
                tag.putString("ae2addon:name", "ME接口(无限级)");
                ((MemoryCardItem) card.getItem()).notifyUser(player, appeng.api.implementations.items.MemoryCardMessages.SETTINGS_SAVED);
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon] 内存卡导出: 参数=({},{},{}) 开关=({},{}) 方向={} 标记数={} 目标数={}",
                        feeder.pStockTarget, feeder.pRestockInterval, feeder.pFeedBudget,
                        feeder.activeExtract, feeder.activeFeed, feeder.extractSide,
                        cfg.getList("markers", 10).size(), cfg.getList("markerTargets", 10).size());
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 内存卡导出失败", e);
            }
        }
        cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide));
    }

    private static void exportFeederConfig(com.ae2addon.block.InfiniteInterfaceBE feeder, CompoundTag cfg) {
        // 升级卡（完整 ItemStack 含 NBT——频道卡频率/绑定等不丢）
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

    private static void importFeederConfig(com.ae2addon.block.InfiniteInterfaceBE feeder, CompoundTag cfg) {
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
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon] 内存卡导入: 参数=({},{},{}) 开关=({},{}) 方向={} 标记数={} 目标数={}",
                feeder.pStockTarget, feeder.pRestockInterval, feeder.pFeedBudget,
                feeder.activeExtract, feeder.activeFeed, feeder.extractSide,
                cfg.getList("markers", 10).size(), cfg.getList("markerTargets", 10).size());
    }
}
