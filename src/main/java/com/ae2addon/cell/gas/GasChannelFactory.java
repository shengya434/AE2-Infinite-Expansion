package com.ae2addon.cell.gas;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.CellChannelRegistry;
import com.mekeng.github.common.me.data.IAEGasStack;

import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.BasicCellInventoryHandler;
import net.minecraft.item.ItemStack;

/**
 * The FACTORY stage of the optional-dependency pattern: the only place besides the adapter that names
 * Mekanism Energistics types. Loaded lazily, and only from {@link GasChannelDoor} once the door is open.
 */
final class GasChannelFactory {

    private GasChannelFactory() {
    }

    static void register() {
        if (GasCellChannel.INSTANCE.storage() == null) {
            AE2Addon.LOG.warn("[ae2addon] mekeng 在，但 AE2 里取不到气体通道，气体支持没有注册");
            return;
        }
        CellChannelRegistry.register(GasCellChannel.INSTANCE);
        AE2Addon.LOG.info("[ae2addon] 气体通道已接入（Mekanism Energistics）");
    }

    static <T extends IAEStack<T>> ICellInventoryHandler<T> tryBuild(ItemStack stack, ISaveProvider saveProvider,
            IStorageChannel<T> channel) {
        if (CellChannelRegistry.byStorage(channel) != GasCellChannel.INSTANCE) {
            return null;
        }
        @SuppressWarnings("unchecked")
        IStorageChannel<IAEGasStack> gasChannel = (IStorageChannel<IAEGasStack>) channel;
        @SuppressWarnings("unchecked")
        ICellInventoryHandler<T> handler = (ICellInventoryHandler<T>) new BasicCellInventoryHandler<>(
                new GasCellInventory(stack, saveProvider), gasChannel);
        return handler;
    }
}