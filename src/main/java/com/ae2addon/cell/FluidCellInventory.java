package com.ae2addon.cell;

import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEFluidStack;
import net.minecraft.item.ItemStack;

/**
 * The fluid channel's face of the universal cell: the same judgement core, keyed by fluid stacks.
 *
 * <p>Both faces read the SAME {@link CellStorageData} for a given cell UUID (that is what makes one cell
 * universal), but only their own {@link CellStorageData.Stocks} bucket: the modes, thresholds and rules are
 * shared, the counts are not (sensei's "storage separate, settings shared", 2026-10-03).
 */
public class FluidCellInventory extends ChannelCellCore<IAEFluidStack> {

    public FluidCellInventory(ItemStack cellStack, ISaveProvider container) {
        super(cellStack, container, FluidCellChannel.INSTANCE);
    }
}