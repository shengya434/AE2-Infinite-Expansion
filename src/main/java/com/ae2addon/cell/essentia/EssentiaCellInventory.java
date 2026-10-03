package com.ae2addon.cell.essentia;

import com.ae2addon.cell.ChannelCellCore;

import appeng.api.storage.ISaveProvider;
import net.minecraft.item.ItemStack;
import thaumicenergistics.api.storage.IAEEssentiaStack;

/**
 * The essentia channel's face of the universal cell: the same judgement core, keyed by essentia stacks.
 * Optional dependency, reached only through {@link EssentiaChannelDoor}.
 */
public class EssentiaCellInventory extends ChannelCellCore<IAEEssentiaStack> {

    public EssentiaCellInventory(ItemStack cellStack, ISaveProvider container) {
        super(cellStack, container, EssentiaCellChannel.INSTANCE);
    }
}