package com.ae2addon.network;

import com.ae2addon.gui.Mode2ConfigMenu;
import com.ae2addon.gui.ModeSelectMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

/** Resolves the live stack anchored by the currently open menu. */
public final class CellScreenTarget {
    public final ItemStack stack;
    public final EnumHand hand;

    private CellScreenTarget(ItemStack stack, EnumHand hand) {
        this.stack = stack;
        this.hand = hand;
    }

    public static CellScreenTarget resolve(EntityPlayerMP player) {
        if (player.openContainer instanceof Mode2ConfigMenu) {
            Mode2ConfigMenu menu = (Mode2ConfigMenu) player.openContainer;
            if (menu.canInteractWith(player)) return new CellScreenTarget(menu.cell(), menu.hand());
        }
        if (player.openContainer instanceof ModeSelectMenu) {
            ModeSelectMenu menu = (ModeSelectMenu) player.openContainer;
            if (menu.canInteractWith(player)) return new CellScreenTarget(menu.cell(), menu.hand());
        }
        return null;
    }
}
