package com.ae2addon.cell.mana;

import com.ae2addon.cell.ChannelCellCore;

import appeng.api.storage.ISaveProvider;
import net.minecraft.item.ItemStack;

/**
 * The 法力 channel's face of the universal cell: the same judgement core, keyed by nyonio.ae2.ManaStack.
 * Optional dependency, reached only through {@link ManaChannelDoor}.
 */
public class ManaCellInventory extends ChannelCellCore<nyonio.ae2.ManaStack> {

    public ManaCellInventory(ItemStack cellStack, ISaveProvider container) {
        super(cellStack, container, ManaCellChannel.INSTANCE);
    }
}