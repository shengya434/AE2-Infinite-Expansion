package com.ae2addon.cell.mana;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.CellChannelRegistry;

import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.BasicCellInventoryHandler;
import net.minecraft.item.ItemStack;

/**
 * The FACTORY stage of the optional-dependency pattern for 法力: the only place (besides the adapter) that
 * names botania_applie types. Loaded lazily, only once {@link ManaChannelDoor} is open.
 */
final class ManaChannelFactory {

    private ManaChannelFactory() {
    }

    static void register() {
        if (ManaCellChannel.INSTANCE.storage() == null) {
            AE2Addon.LOG.warn("[ae2addon] botania_applie 在，但取不到 法力 通道，法力 支持没有注册");
            return;
        }
        CellChannelRegistry.register(ManaCellChannel.INSTANCE);
        AE2Addon.LOG.info("[ae2addon] 法力通道已接入（botania_applie）");
    }

    static <T extends IAEStack<T>> ICellInventoryHandler<T> tryBuild(ItemStack stack, ISaveProvider saveProvider,
            IStorageChannel<T> channel) {
        if (CellChannelRegistry.byStorage(channel) != ManaCellChannel.INSTANCE) {
            return null;
        }
        @SuppressWarnings("unchecked")
        IStorageChannel<nyonio.ae2.ManaStack> typed = (IStorageChannel<nyonio.ae2.ManaStack>) channel;
        @SuppressWarnings("unchecked")
        ICellInventoryHandler<T> handler = (ICellInventoryHandler<T>) new BasicCellInventoryHandler<>(
                new ManaCellInventory(stack, saveProvider), typed);
        return handler;
    }
}