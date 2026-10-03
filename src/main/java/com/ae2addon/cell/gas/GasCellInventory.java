package com.ae2addon.cell.gas;

import com.ae2addon.cell.ChannelCellCore;
import com.mekeng.github.common.me.data.IAEGasStack;

import appeng.api.storage.ISaveProvider;
import net.minecraft.item.ItemStack;

/**
 * The gas channel's face of the universal cell. Same judgement core, keyed by gas stacks, and the same
 * per-channel {@code Stocks} bucket of the shared cell record.
 *
 * <p>Optional dependency: only reached through {@link GasChannelDoor}.
 */
public class GasCellInventory extends ChannelCellCore<IAEGasStack> {

    public GasCellInventory(ItemStack cellStack, ISaveProvider container) {
        super(cellStack, container, GasCellChannel.INSTANCE);
    }
}