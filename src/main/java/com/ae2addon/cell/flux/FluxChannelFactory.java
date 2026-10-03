package com.ae2addon.cell.flux;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.CellChannelRegistry;

import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.BasicCellInventoryHandler;
import net.minecraft.item.ItemStack;

/**
 * The FACTORY stage of the optional-dependency pattern for 能量: the only place (besides the adapter) that
 * names flux_applied types. Loaded lazily, only once {@link FluxChannelDoor} is open.
 */
final class FluxChannelFactory {

    private FluxChannelFactory() {
    }

    static void register() {
        if (FluxCellChannel.INSTANCE.storage() == null) {
            AE2Addon.LOG.warn("[ae2addon] flux_applied 在，但取不到 能量 通道，能量 支持没有注册");
            return;
        }
        CellChannelRegistry.register(FluxCellChannel.INSTANCE);
        AE2Addon.LOG.info("[ae2addon] 能量通道已接入（flux_applied）");
    }

    static <T extends IAEStack<T>> ICellInventoryHandler<T> tryBuild(ItemStack stack, ISaveProvider saveProvider,
            IStorageChannel<T> channel) {
        if (CellChannelRegistry.byStorage(channel) != FluxCellChannel.INSTANCE) {
            return null;
        }
        @SuppressWarnings("unchecked")
        IStorageChannel<com.flux_applied.ae2.FluxStack> typed = (IStorageChannel<com.flux_applied.ae2.FluxStack>) channel;
        @SuppressWarnings("unchecked")
        ICellInventoryHandler<T> handler = (ICellInventoryHandler<T>) new BasicCellInventoryHandler<>(
                new FluxCellInventory(stack, saveProvider), typed);
        return handler;
    }
}