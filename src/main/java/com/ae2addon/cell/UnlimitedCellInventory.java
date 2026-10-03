package com.ae2addon.cell;

import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import net.minecraft.item.ItemStack;

/**
 * The item channel's face of the universal cell.
 *
 * <p>P4 (2026-10-03): the judgement layer moved into {@link ChannelCellCore}, which is generic over the
 * channel's stack type. This class is the item-channel instance of it -- the type the cell handler, the
 * commands and the self-test already know. Every member is inherited, so callers keep working unchanged
 * ({@code UnlimitedCellInventory.INFINITE}, {@code .Summary}, {@code .ruleKey(...)} all resolve through the
 * superclass).
 *
 * <p>Paired with {@link ItemCellChannel}: the item channel is always present, so an item-only cell never
 * depends on an optional bridge mod having loaded.
 */
public class UnlimitedCellInventory extends ChannelCellCore<IAEItemStack> {

    public UnlimitedCellInventory(ItemStack cellStack, ISaveProvider container) {
        super(cellStack, container, ItemCellChannel.INSTANCE);
    }

    /** The AE2 item channel this cell speaks (kept here: the generic core must not name a concrete channel). */
    public static IStorageChannel<IAEItemStack> itemChannel() {
        return ItemCellChannel.INSTANCE.storage();
    }
}