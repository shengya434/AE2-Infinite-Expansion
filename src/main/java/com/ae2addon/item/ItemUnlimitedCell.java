package com.ae2addon.item;

import java.util.List;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.gui.CellScreens;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

/**
 * The P1 unlimited storage cell item.
 *
 * <p>Intentionally a plain {@link Item}: implementing {@code IStorageCell} would let AE2's own
 * {@code BasicCellHandler} claim it (see {@link com.ae2addon.cell.UnlimitedCellHandler}).
 */
public class ItemUnlimitedCell extends Item {

    public ItemUnlimitedCell() {
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.MISC);
        setRegistryName(AE2Addon.MODID, AE2Addon.CELL_NAME);
        // stable_39 spells this setTranslationKey(). getTranslationKey() prefixes "item." and
        // getItemStackDisplayName appends ".name", so the lang key is:
        //     item.ae2addon:unlimited_cell.name
        setTranslationKey(AE2Addon.MODID + ":" + AE2Addon.CELL_NAME);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!world.isRemote) {
            CellScreens.open(player, stack, hand);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        // Reads the NBT directly so stored amounts are visible without opening a terminal --
        // handy for verifying that persistence actually worked.
        UnlimitedCellInventory.appendSummary(stack, tooltip::add);
    }
}
