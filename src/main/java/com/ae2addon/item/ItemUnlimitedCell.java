package com.ae2addon.item;

import java.util.List;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.gui.CellScreens;
import com.ae2addon.util.SizeFormat;

import net.minecraft.client.resources.I18n;
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
        // Ported from the 1.20 line's appendHoverText: mode line, mode description, then the summary the
        // cell writes into its own NBT (see UnlimitedCellInventory#writeSmallFields). The totals come from
        // those counters -- cell contents live in the world save keyed by uuid and are not readable on the
        // client, and walking every stored key here would be thousands of NBT reads per frame.
        UnlimitedCellInventory.Summary summary = UnlimitedCellInventory.summaryOf(stack);
        int mode = summary.mode;
        String[] keys = {"", "gui.ae2addon.cell.mode1", "gui.ae2addon.cell.mode2", "gui.ae2addon.cell.mode3"};
        tooltip.add(I18n.format("gui.ae2addon.cell.mode", I18n.format(keys[mode] + ".name")));
        tooltip.add(I18n.format(keys[mode] + ".desc"));

        if (mode == 2) {
            tooltip.add(I18n.format("gui.ae2addon.cell.workmode",
                    I18n.format("gui.ae2addon.cell.workmode" + summary.workMode)));
            if (summary.workMode == 1) {
                long threshold = summary.threshold <= 0
                        ? UnlimitedCellInventory.DEFAULT_THRESHOLD : summary.threshold;
                tooltip.add(I18n.format("gui.ae2addon.cell.threshold", threshold));
            }
        }

        if (mode == 3) {
            tooltip.add(I18n.format("gui.ae2addon.cell.bytes", "∞"));
            tooltip.add(I18n.format("gui.ae2addon.cell.types", "∞"));
            tooltip.add(I18n.format("gui.ae2addon.cell.limit"));
            tooltip.add(I18n.format("gui.ae2addon.cell.switch_hint"));
            return;
        }

        tooltip.add(I18n.format("gui.ae2addon.cell.bytes", SizeFormat.bytes(summary.bytes)));
        tooltip.add(I18n.format("gui.ae2addon.cell.types", summary.types));
        tooltip.add(I18n.format("gui.ae2addon.cell.switch_hint"));
    }
}
