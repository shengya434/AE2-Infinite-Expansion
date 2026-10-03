package com.ae2addon.cell.essentia;

import com.ae2addon.cell.ChannelCellCore;
import com.ae2addon.cell.FluidCellInventory;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.command.CommandCellSelfTest;

import appeng.api.config.Actionable;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IItemList;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import appeng.fluids.util.AEFluidStack;
import thaumcraft.api.aspects.Aspect;
import thaumicenergistics.api.EssentiaStack;
import thaumicenergistics.api.storage.IAEEssentiaStack;
import thaumicenergistics.integration.appeng.AEEssentiaStack;

/**
 * N group: the essentia channel (Thaumic Energistics).
 *
 * <p>Optional package: the self-test host only names {@link EssentiaChannelDoor} and calls this class when the
 * door is open. The gas part of the isolation check is itself gated on the gas door, so this class loads even
 * in a pack that has Thaumic Energistics but not Mekanism Energistics.
 */
public final class EssentiaChannelSelfTest {

    private EssentiaChannelSelfTest() {
    }

    public static void run(ICommandSender sender, CommandCellSelfTest host) {
        IStorageChannel<IAEEssentiaStack> essentiaChannel = EssentiaCellChannel.INSTANCE.storage();
        Aspect sample = Aspect.AIR != null ? Aspect.AIR : firstAspect();
        if (sample == null || essentiaChannel == null) {
            host.report(sender, "N0", "源质通道可用（要素表非空且 AE2 能取到通道）", false,
                    "至少一个要素 + 通道非 null", "sample=" + sample + " channel=" + essentiaChannel);
            return;
        }
        IAEEssentiaStack sampleKey = essentiaKey(sample);
        int registered = Aspect.aspects.size();

        // N1: mode 3 lists every registered aspect as infinite, type count agreeing with the listing
        EssentiaCellInventory n1 = newEssentiaInventory(host, "n1");
        n1.setMode(3);
        IItemList<IAEEssentiaStack> n1list = essentiaChannel.createList();
        n1.getAvailableItems(n1list);
        boolean n1all = true;
        for (IAEEssentiaStack listed : n1list) {
            if (listed.getStackSize() != ChannelCellCore.INFINITE) {
                n1all = false;
                break;
            }
        }
        host.report(sender, "N1", "源质通道 mode3：列出全部注册要素且都算无限，类型数与条数一致",
                n1list.size() == registered && registered > 5 && n1all
                        && n1.getStoredItemTypes() == n1list.size(),
                "=" + registered + "/全无限/types=list",
                "listed=" + n1list.size() + " types=" + n1.getStoredItemTypes() + " all=" + n1all);

        // N2: a mod rule (thaumcraft) reaches aspects, whose tags carry no namespace
        EssentiaCellInventory n2 = newEssentiaInventory(host, "n2");
        n2.setMode(2);
        n2.setWorkMode(1);
        n2.addModRule("thaumcraft");
        boolean n2rule = n2.isInfiniteByRule(sampleKey);
        long n2listed = essentiaListedSize(n2, sampleKey);
        host.report(sender, "N2", "★ 模组规则 thaumcraft 命中要素（要素 tag 无命名空间，按 thaumcraft 算）",
                n2rule && n2listed == ChannelCellCore.INFINITE, "true/infinite", n2rule + "/" + n2listed);

        // N3: ore rules are item-only
        EssentiaCellInventory n3 = newEssentiaInventory(host, "n3");
        n3.setMode(2);
        n3.setWorkMode(1);
        n3.addOreRule("ingotIron");
        long n3listed = essentiaListedSize(n3, sampleKey);
        host.report(sender, "N3", "★ 矿词规则对源质无效（只有物品通道有矿物词典）",
                n3listed == -1L && !n3.isInfiniteByRule(sampleKey), "-1/false",
                n3listed + "/" + n3.isInfiniteByRule(sampleKey));

        // N4: up to four channels on ONE cell, each with its own counts (gas only if that door is open too)
        ItemStack shared = CommandCellSelfTest.testCell("n4");
        UnlimitedCellInventory n4items = new UnlimitedCellInventory(shared, null);
        CommandCellSelfTest.wipeCell(n4items.rawData());
        FluidCellInventory n4fluids = new FluidCellInventory(shared, null);
        EssentiaCellInventory n4essentia = new EssentiaCellInventory(shared, null);
        n4items.setMode(1);
        n4items.injectItems(CommandCellSelfTest.itemStackOf(Items.STICK, 1000L), Actionable.MODULATE, null);
        n4fluids.injectItems(AEFluidStack.fromFluidStack(new FluidStack(FluidRegistry.WATER, 500)),
                Actionable.MODULATE, null);
        n4essentia.injectItems(essentiaStackOf(sample, 300), Actionable.MODULATE, null);
        long n4i = n4items.getStoredItemCount();
        long n4f = n4fluids.getStoredItemCount();
        long n4e = n4essentia.getStoredItemCount();
        host.report(sender, "N4", "★ 同一只元件同时装物品/流体/源质：三者存量互不干扰（气体的隔离由 M4 那组负责）",
                n4i == 1000L && n4f == 500L && n4e == 300L, "1000/500/300",
                n4i + "/" + n4f + "/" + n4e);
        // N5: threshold promotion works in essentia units
        EssentiaCellInventory n5 = newEssentiaInventory(host, "n5");
        n5.setMode(2);
        n5.setWorkMode(1);
        n5.setThreshold(1000L);
        n5.injectItems(essentiaStackOf(sample, 999), Actionable.MODULATE, null);
        boolean n5before = n5.isInfinite(sampleKey);
        n5.injectItems(essentiaStackOf(sample, 1), Actionable.MODULATE, null);
        boolean n5after = n5.isInfinite(sampleKey);
        host.report(sender, "N5", "源质阈值：存 999 不升级、再存 1（共 1000）升级为无限",
                !n5before && n5after, "false/true", n5before + "/" + n5after);

        // N6: mode 3 cost for essentia, measured like the other channels
        IItemList<IAEEssentiaStack> cold = essentiaChannel.createList();
        long coldStart = System.nanoTime();
        n1.getAvailableItems(cold);
        long coldNanos = System.nanoTime() - coldStart;
        IItemList<IAEEssentiaStack> warm = essentiaChannel.createList();
        long warmStart = System.nanoTime();
        n1.getAvailableItems(warm);
        long warmNanos = System.nanoTime() - warmStart;
        sender.sendMessage(new TextComponentString(String.format(
                "[ae2addon] [N6] 源质 mode3 列出 %d 个键：冷 %.1f ms / 热 %.2f ms", warm.size(),
                coldNanos / 1e6, warmNanos / 1e6)));
        host.report(sender, "N6", "源质 mode3 热重建 < 50 ms", warmNanos < 50_000_000L, "<50ms",
                String.format("%.2f ms", warmNanos / 1e6));
    }

    private static Aspect firstAspect() {
        for (Aspect aspect : Aspect.aspects.values()) {
            if (aspect != null) {
                return aspect;
            }
        }
        return null;
    }



    private static EssentiaCellInventory newEssentiaInventory(CommandCellSelfTest host, String tag) {
        EssentiaCellInventory inv = new EssentiaCellInventory(CommandCellSelfTest.testCell(tag), null);
        CommandCellSelfTest.wipeCell(inv.rawData());
        return inv;
    }

    private static IAEEssentiaStack essentiaKey(Aspect aspect) {
        return AEEssentiaStack.fromEssentiaStack(new EssentiaStack(aspect, 1));
    }

    private static IAEEssentiaStack essentiaStackOf(Aspect aspect, int amount) {
        return AEEssentiaStack.fromEssentiaStack(new EssentiaStack(aspect, amount));
    }

    private static long essentiaListedSize(EssentiaCellInventory inv, IAEEssentiaStack key) {
        IItemList<IAEEssentiaStack> list = EssentiaCellChannel.INSTANCE.storage().createList();
        inv.getAvailableItems(list);
        IAEEssentiaStack found = list.findPrecise(key);
        return found == null ? -1L : found.getStackSize();
    }
}