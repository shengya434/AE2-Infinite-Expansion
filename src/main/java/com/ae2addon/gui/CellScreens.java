package com.ae2addon.gui;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.item.ItemUnlimitedCell;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;

/** Hand held cell GUI registration and server side opening. */
public final class CellScreens implements IGuiHandler {
    public static final int SELECT = 0;
    public static final int CONFIG = 1;

    private CellScreens() {}

    public static void register(AE2Addon mod) {
        NetworkRegistry.INSTANCE.registerGuiHandler(mod, new CellScreens());
    }

    public static void open(EntityPlayer player, ItemStack stack, EnumHand hand) {
        if (player.world.isRemote || stack.isEmpty() || !(stack.getItem() instanceof ItemUnlimitedCell)) {
            return;
        }
        int mode = new UnlimitedCellInventory(stack, null).getMode();
        open(player, mode == 2 ? CONFIG : SELECT, hand);
    }

    public static void open(EntityPlayer player, int guiId, EnumHand hand) {
        if (!player.world.isRemote) {
            player.openGui(AE2Addon.INSTANCE, guiId, player.world, hand == EnumHand.OFF_HAND ? 1 : 0, 0, 0);
        }
    }

    private static EnumHand hand(int x) {
        return x == 1 ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
    }

    private static boolean valid(EntityPlayer player, int x) {
        ItemStack stack = player.getHeldItem(hand(x));
        return !stack.isEmpty() && stack.getItem() instanceof ItemUnlimitedCell;
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (!valid(player, x)) return null;
        ItemStack stack = player.getHeldItem(hand(x));
        return id == CONFIG ? new Mode2ConfigMenu(player.inventory, stack, hand(x))
                : id == SELECT ? new ModeSelectMenu(stack, hand(x)) : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (!valid(player, x)) return null;
        ItemStack stack = player.getHeldItem(hand(x));
        return id == CONFIG ? new Mode2ConfigScreen(new Mode2ConfigMenu(player.inventory, stack, hand(x)))
                : id == SELECT ? new ModeSelectScreen(new ModeSelectMenu(stack, hand(x))) : null;
    }
}
