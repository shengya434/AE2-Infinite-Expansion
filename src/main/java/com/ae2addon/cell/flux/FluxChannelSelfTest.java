package com.ae2addon.cell.flux;

import com.ae2addon.cell.ChannelCellCore;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.command.CommandCellSelfTest;
import com.flux_applied.ae2.FluxStack;

import appeng.api.config.Actionable;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IItemList;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;

/**
 * P group: the 能量 channel (flux_applied) -- a SINGLE-KEY resource channel.
 *
 * <p>Optional package: the self-test host only names {@link FluxChannelDoor} and calls this class when the
 * door is open, so a pack without that bridge mod still gets a full self-test.
 */
public final class FluxChannelSelfTest {

    private FluxChannelSelfTest() {
    }

    public static void run(ICommandSender sender, CommandCellSelfTest host) {
        IStorageChannel<com.flux_applied.ae2.FluxStack> channel = FluxCellChannel.INSTANCE.storage();
        if (channel == null) {
            host.report(sender, "P0", "能量通道可用（AE2 能取到该通道）", false, "通道非 null", "null");
            return;
        }
        com.flux_applied.ae2.FluxStack key = new FluxStack(1);

        // P1: this channel has exactly ONE key, and mode 3 lists it as infinite
        FluxCellInventory g1 = newFluxInventory(host, "g1");
        g1.setMode(3);
        IItemList<com.flux_applied.ae2.FluxStack> list = channel.createList();
        g1.getAvailableItems(list);
        long listedSize = sizeOf(list.findPrecise(key));
        host.report(sender, "P1", "能量通道 mode3：单键通道列出恰好 1 个键且算无限，类型数与条数一致",
                list.size() == 1 && g1.getStoredItemTypes() == 1 && listedSize == ChannelCellCore.INFINITE,
                "size=1/types=1/INFINITE", "size=" + list.size() + " types=" + g1.getStoredItemTypes()
                        + " noted=" + listedSize);

        // P2: ordinary storage round trip (mode 1)
        FluxCellInventory g2 = newFluxInventory(host, "g2");
        g2.setMode(1);
        g2.injectItems(that(512L), Actionable.MODULATE, null);
        long stored = g2.getStoredItemCount();
        long taken = sizeOfStack(g2.extractItems(that(512L), Actionable.MODULATE, null));
        long left = g2.getStoredItemCount();
        host.report(sender, "P2", "能量普通存取：存 512 → 读数 512，取 512 → 返回 512 且归零",
                stored == 512L && taken == 512L && left == 0L, "512/512/0", stored + "/" + taken + "/" + left);

        // P3: a mod rule reaches it without anything ever being stored
        FluxCellInventory g3 = newFluxInventory(host, "g3");
        g3.setMode(2);
        g3.setWorkMode(1);
        g3.addModRule("flux_applied");
        boolean byRule = g3.isInfiniteByRule(key);
        host.report(sender, "P3", "★ 模组规则 flux_applied 命中该能量（没存过也无限）",
                byRule, "true", String.valueOf(byRule));

        // P4: threshold promotion in this channel's units
        FluxCellInventory g4 = newFluxInventory(host, "g4");
        g4.setMode(2);
        g4.setWorkMode(1);
        g4.setThreshold(1000L);
        g4.injectItems(that(999L), Actionable.MODULATE, null);
        boolean before = g4.isInfinite(key);
        g4.injectItems(that(1L), Actionable.MODULATE, null);
        boolean after = g4.isInfinite(key);
        host.report(sender, "P4", "能量阈值：存 999 不升级、再存 1（共 1000）升级为无限",
                !before && after, "false/true", before + "/" + after);

        // P5: the item channel and this one keep separate counts on ONE cell
        ItemStack shared = CommandCellSelfTest.testCell("g5" + "flux");
        UnlimitedCellInventory items = new UnlimitedCellInventory(shared, null);
        CommandCellSelfTest.wipeCell(items.rawData());
        FluxCellInventory special = new FluxCellInventory(shared, null);
        items.setMode(1);
        items.injectItems(CommandCellSelfTest.itemStackOf(Items.STICK, 1000L), Actionable.MODULATE, null);
        special.injectItems(that(700L), Actionable.MODULATE, null);
        long itemCount = items.getStoredItemCount();
        long specialCount = special.getStoredItemCount();
        host.report(sender, "P5", "★ 同一只元件：物品与能量的存量互不干扰",
                itemCount == 1000L && specialCount == 700L, "1000/700",
                itemCount + "/" + specialCount);
    }

    private static FluxCellInventory newFluxInventory(CommandCellSelfTest host, String tag) {
        FluxCellInventory inv = new FluxCellInventory(CommandCellSelfTest.testCell(tag), null);
        CommandCellSelfTest.wipeCell(inv.rawData());
        return inv;
    }

    private static com.flux_applied.ae2.FluxStack that(long amount) {
        return new FluxStack(amount);
    }

    private static long sizeOf(com.flux_applied.ae2.FluxStack stack) {
        return stack == null ? -1L : stack.getStackSize();
    }

    private static long sizeOfStack(com.flux_applied.ae2.FluxStack stack) {
        return stack == null ? -1L : stack.getStackSize();
    }
}