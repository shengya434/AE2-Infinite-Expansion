package com.ae2addon;

import appeng.api.AEApi;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;

/**
 * TEMPORARY compile probe (P1 step 0).
 *
 * Purpose: prove the AE2-UEL deobfCompile dependency actually resolves and that the 1.12.2 (rv6) API
 * surface we plan to build on is reachable with MCP stable_39 names. Delete this file once the real
 * cell classes land.
 */
public final class ApiProbe {

    private ApiProbe() {
    }

    /** Mirrors the real registration call we will use in the mod's init phase. */
    public static void register(ICellHandler handler) {
        AEApi.instance().registries().cell().addCellHandler(handler);
    }

    /** Mirrors the lookup the ME Drive performs, forcing javac to resolve the exact generic signature. */
    @SuppressWarnings("unused")
    public static <T extends IAEStack<T>> ICellInventoryHandler<T> lookup(ItemStack cell, ISaveProvider host,
            IStorageChannel<T> channel) {
        return AEApi.instance().registries().cell().getCellInventory(cell, host, channel);
    }

    /** Pins the item channel handle and the ICellInventory contract our inventory class must satisfy. */
    public static IStorageChannel<IAEItemStack> itemChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
    }

    @SuppressWarnings("unused")
    private static int statusOf(ICellInventory<IAEItemStack> inv, IMEInventoryHandler<IAEItemStack> handler) {
        return inv.getStatusForCell() + handler.getSlot();
    }
}
