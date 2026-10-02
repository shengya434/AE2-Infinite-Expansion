package com.ae2addon.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

/** Player slots are shown only on the full configuration page. */
public final class Mode2ConfigMenu extends Container {
    private final ItemStack cell;
    private final EnumHand hand;
    public boolean slotsHidden = true;

    public Mode2ConfigMenu(InventoryPlayer inventory, ItemStack cell, EnumHand hand) {
        this.cell = cell;
        this.hand = hand;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(inventory, 9 + row * 9 + col, 48 + col * 18, 192 + row * 18) {
                    @Override public boolean isEnabled() { return !slotsHidden; }
                });
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(inventory, col, 48 + col * 18, 250) {
                @Override public boolean isEnabled() { return !slotsHidden; }
            });
        }
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
