package com.ae2addon.cell.flux;

import com.ae2addon.cell.SingleKeyCellChannel;
import com.flux_applied.ae2.FluxStack;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;

/**
 * The 能量 channel, provided by flux_applied.
 *
 * <p>A SINGLE-KEY resource: FE (Flux Applied) has one key and only the amount varies, so this adapter is a handful of
 * names on top of {@link SingleKeyCellChannel}. Optional-dependency code: only reachable through
 * {@link FluxChannelDoor}, which names no type from here.
 */
public final class FluxCellChannel extends SingleKeyCellChannel<com.flux_applied.ae2.FluxStack> {

    public static final FluxCellChannel INSTANCE = new FluxCellChannel();

    private FluxCellChannel() {
    }

    @Override
    public String id() {
        return "flux";
    }

    @Override
    public IStorageChannel<com.flux_applied.ae2.FluxStack> storage() {
        // Prefer whatever AE2's storage helper knows (that is the instance AE2 hands the cell handler), and
        // fall back to the bridge mod's own singleton if it never registered one.
        IStorageChannel<com.flux_applied.ae2.FluxStack> viaApi = AEApi.instance().storage().getStorageChannel(com.flux_applied.ae2.FluxStorageChannel.class);
        return viaApi != null ? viaApi : com.flux_applied.ae2.FluxStorageChannel.INSTANCE;
    }

    @Override
    protected com.flux_applied.ae2.FluxStack singleKey() {
        return FluxStack.STATIC_STACK;
    }

    @Override
    protected String namespaceId() {
        // Mod rules mean "everything from this mod"; the bridge mod owns this channel, so it is its namespace.
        return "flux_applied";
    }

    @Override
    protected String displayNameText() {
        return "FE (Flux Applied)";
    }
}