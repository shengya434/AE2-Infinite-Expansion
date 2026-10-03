package com.ae2addon.cell.flux;

import com.ae2addon.cell.ChannelCellCore;

import appeng.api.storage.ISaveProvider;
import net.minecraft.item.ItemStack;

/**
 * The 能量 channel's face of the universal cell: the same judgement core, keyed by com.flux_applied.ae2.FluxStack.
 * Optional dependency, reached only through {@link FluxChannelDoor}.
 */
public class FluxCellInventory extends ChannelCellCore<com.flux_applied.ae2.FluxStack> {

    public FluxCellInventory(ItemStack cellStack, ISaveProvider container) {
        super(cellStack, container, FluxCellChannel.INSTANCE);
    }
}