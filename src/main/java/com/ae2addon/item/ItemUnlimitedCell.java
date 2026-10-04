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
    public String getItemStackDisplayName(ItemStack stack) {
        // Ported from the 1.20 line, whose getDescription() renders "Universal Infinite [<mode>]". Mode 2
        // additionally shows the sub-mode, because "custom infinite" alone does not tell you whether the
        // cell is threshold-, store- or cherish-driven.
        //
        // Uses the translation-side I18n on purpose: a display name is also read server-side (command
        // output, chat), where net.minecraft.client.resources.I18n does not exist. Fully qualified because
        // both classes are named "I18n" and this file already imports the client one.
        // Method names verified with javap against stable_39: this class has translateToLocal /
        // translateToLocalFormatted (NOT format -- that one only exists on the client class).
        UnlimitedCellInventory.Summary summary = UnlimitedCellInventory.summaryOf(stack);
        String mode = net.minecraft.util.text.translation.I18n
                .translateToLocal("gui.ae2addon.cell.short" + summary.mode);
        if (summary.mode == 2) {
            mode = mode + "·" + net.minecraft.util.text.translation.I18n
                    .translateToLocal("gui.ae2addon.cell.workmode" + summary.workMode);
        }
        return net.minecraft.util.text.translation.I18n
                .translateToLocalFormatted("gui.ae2addon.cell.name", mode);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        // Ported from the 1.20 line's appendHoverText: mode line, mode description, then the summary the
        // cell writes into its own NBT (see UnlimitedCellInventory#writeSmallFields). The totals come from
        // those counters -- cell contents live in the world save keyed by uuid and are not readable on the
        // client, and walking every stored key here would be thousands of NBT reads per frame.
        //
        // One line per storage channel (sensei 2026-10-04: "can the drive slot show every channel?"). This is
        // the only place where that is possible: the cell really does hold several channels at once, its NBT
        // keeps one counter set per channel, and a multi-line tooltip is the one surface AE2 gives us here
        // (the drive slot itself has a single status light, and AE2's own per-slot line names one channel).
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

        for (com.ae2addon.cell.CellChannel<?> channel : com.ae2addon.cell.CellChannelRegistry.all()) {
            UnlimitedCellInventory.Summary channelSummary =
                    UnlimitedCellInventory.summaryOf(stack, channel.id());
            String types = mode == 3 ? "∞" : String.valueOf(channelSummary.types);
            String bytes = mode == 3 ? "∞" : SizeFormat.bytes(channelSummary.bytes);
            tooltip.add(I18n.format("gui.ae2addon.cell.channel_line", channel.channelName(), types, bytes));
        }

        if (mode == 3) {
            tooltip.add(I18n.format("gui.ae2addon.cell.limit"));
        }
        tooltip.add(I18n.format("gui.ae2addon.cell.switch_hint"));
    }
}
