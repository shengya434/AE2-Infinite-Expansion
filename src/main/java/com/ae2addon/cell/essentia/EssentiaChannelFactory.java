package com.ae2addon.cell.essentia;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.CellChannelRegistry;

import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.BasicCellInventoryHandler;
import net.minecraft.item.ItemStack;
import thaumicenergistics.api.storage.IAEEssentiaStack;

/**
 * The FACTORY stage of the optional-dependency pattern: the only place besides the adapter that names
 * Thaumic Energistics / Thaumcraft types. Loaded lazily, only once {@link EssentiaChannelDoor} is open.
 */
final class EssentiaChannelFactory {

    private EssentiaChannelFactory() {
    }

    static void register() {
        if (EssentiaCellChannel.INSTANCE.storage() == null) {
            AE2Addon.LOG.warn("[ae2addon] thaumicenergistics 在，但 AE2 里取不到源质通道，源质支持没有注册");
            return;
        }
        CellChannelRegistry.register(EssentiaCellChannel.INSTANCE);
        AE2Addon.LOG.info("[ae2addon] 源质通道已接入（Thaumic Energistics）");
    }

    static <T extends IAEStack<T>> ICellInventoryHandler<T> tryBuild(ItemStack stack, ISaveProvider saveProvider,
            IStorageChannel<T> channel) {
        if (CellChannelRegistry.byStorage(channel) != EssentiaCellChannel.INSTANCE) {
            return null;
        }
        @SuppressWarnings("unchecked")
        IStorageChannel<IAEEssentiaStack> essentiaChannel = (IStorageChannel<IAEEssentiaStack>) channel;
        @SuppressWarnings("unchecked")
        ICellInventoryHandler<T> handler = (ICellInventoryHandler<T>) new BasicCellInventoryHandler<>(
                new EssentiaCellInventory(stack, saveProvider), essentiaChannel);
        return handler;
    }
}