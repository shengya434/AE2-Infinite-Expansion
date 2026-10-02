package com.ae2addon.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

/** No slots: this menu only anchors the selected cell on the server. */
public final class ModeSelectMenu extends Container {
    private final ItemStack cell;
    private final EnumHand hand;

    public ModeSelectMenu(ItemStack cell, EnumHand hand) {
        this.cell = cell;
        this.hand = hand;
    }

    public ItemStack cell() { return cell; }
    public EnumHand hand() { return hand; }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return player.world.isRemote || (!cell.isEmpty() && player.getHeldItem(hand) == cell);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        return ItemStack.EMPTY;
    }
}
