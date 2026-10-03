package com.ae2addon.cell;

import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;

/**
 * Opens a cell inventory for a given CHANNEL -- what the config panel needs, because it switches channels while
 * the player looks at one cell.
 *
 * <p>Every instance reads the same {@link CellStorageData} for the cell's UUID (that is what makes one cell
 * universal) and writes only its own channel's {@link CellStorageData.Stocks}; the modes, thresholds and rules
 * are shared, so switching the panel's channel never changes those.
 */
public final class CellInventories {

    private CellInventories() {
    }

    /**
     * @param channelId a registered channel id ({@link CellChannelRegistry#ITEM} and friends)
     * @return that channel's view of the cell, or {@code null} when the id is not a channel we serve.
     */
    @SuppressWarnings("unchecked")
    public static ChannelCellCore<?> open(ItemStack cellStack, ISaveProvider saveProvider, String channelId) {
        CellChannel<?> channel = CellChannelRegistry.byId(channelId);
        if (channel == null) {
            return null;
        }
        return new ChannelCellCore(cellStack, saveProvider, (CellChannel) channel);
    }

    /** Same as {@link #open} but typed, for callers that know which channel they want. */
    public static <T extends IAEStack<T>> ChannelCellCore<T> openTyped(ItemStack cellStack,
            ISaveProvider saveProvider, CellChannel<T> channel) {
        return new ChannelCellCore<>(cellStack, saveProvider, channel);
    }
}