package com.ae2addon.cell.gas;

import java.util.List;

import com.ae2addon.cell.CellChannelRegistry;
import com.ae2addon.cell.ChannelCellCore;
import com.ae2addon.cell.FluidCellChannel;
import com.ae2addon.cell.FluidCellInventory;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.command.CommandCellSelfTest;
import com.mekeng.github.common.me.data.IAEGasStack;
import com.mekeng.github.common.me.data.impl.AEGasStack;

import appeng.fluids.util.AEFluidStack;

import appeng.api.config.Actionable;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IItemList;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * M group: the gas channel (Mekanism Energistics) -- the first OPTIONAL channel.
 *
 * <p>Lives in the optional package on purpose: the self-test host only names {@link GasChannelDoor} and calls
 * this class when the door is open, so a player without the bridge mod still gets a full self-test instead of
 * a class-loading crash.
 */
public final class GasChannelSelfTest {

    private GasChannelSelfTest() {
    }

    public static void run(ICommandSender sender, CommandCellSelfTest host) {
        IStorageChannel<IAEGasStack> gasChannel = GasCellChannel.INSTANCE.storage();
        List<Gas> gases = GasRegistry.getRegisteredGasses();
        Gas sample = pickSample(gases);
        if (sample == null || gasChannel == null) {
            host.report(sender, "M0", "气体通道可用（注册表非空且 AE2 能取到通道）", false,
                    "至少一种气体 + 通道非 null", "gases=" + gases.size() + " channel=" + gasChannel);
            return;
        }
        IAEGasStack sampleKey = gasKey(sample);

        // M1: mode 3 lists every registered gas as infinite, type count agreeing with the listing
        GasCellInventory m1 = newGasInventory(host, "m1");
        m1.setMode(3);
        IItemList<IAEGasStack> m1list = gasChannel.createList();
        m1.getAvailableItems(m1list);
        boolean m1all = true;
        for (IAEGasStack listed : m1list) {
            if (listed.getStackSize() != ChannelCellCore.INFINITE) {
                m1all = false;
                break;
            }
        }
        host.report(sender, "M1", "气体通道 mode3：列出全部注册气体且都算无限，类型数与条数一致",
                m1list.size() == gases.size() && !gases.isEmpty() && m1all
                        && m1.getStoredItemTypes() == m1list.size(),
                "=" + gases.size() + "/全无限/types=list",
                "listed=" + m1list.size() + " types=" + m1.getStoredItemTypes() + " all=" + m1all);

        // M2: a mod rule (mekanism) reaches gases, whose names carry no namespace
        GasCellInventory m2 = newGasInventory(host, "m2");
        m2.setMode(2);
        m2.setWorkMode(1);
        m2.addModRule("mekanism");
        boolean m2rule = m2.isInfiniteByRule(sampleKey);
        long m2listed = gasListedSize(m2, sampleKey);
        host.report(sender, "M2", "★ 模组规则 mekanism 命中气体（气体名无命名空间，按 mekanism 算）",
                m2rule && m2listed == ChannelCellCore.INFINITE, "true/infinite", m2rule + "/" + m2listed);

        // M3: ore rules are item-only
        GasCellInventory m3 = newGasInventory(host, "m3");
        m3.setMode(2);
        m3.setWorkMode(1);
        m3.addOreRule("ingotIron");
        long m3listed = gasListedSize(m3, sampleKey);
        host.report(sender, "M3", "★ 矿词规则对气体无效（只有物品通道有矿物词典）",
                m3listed == -1L && !m3.isInfiniteByRule(sampleKey), "-1/false",
                m3listed + "/" + m3.isInfiniteByRule(sampleKey));

        // M4: the shared cell holds three channels at once, each with its own counts
        ItemStack shared = CommandCellSelfTest.testCell("m4");
        UnlimitedCellInventory m4items = new UnlimitedCellInventory(shared, null);
        CommandCellSelfTest.wipeCell(m4items.rawData());
        FluidCellInventory m4fluids = new FluidCellInventory(shared, null);
        GasCellInventory m4gases = new GasCellInventory(shared, null);
        m4items.setMode(1);
        m4items.injectItems(CommandCellSelfTest.itemStackOf(Items.STICK, 1000L), Actionable.MODULATE, null);
        m4fluids.injectItems(AEFluidStack.fromFluidStack(new FluidStack(FluidRegistry.WATER, 500)), Actionable.MODULATE, null);
        m4gases.injectItems(gasStackOf(sample, 700), Actionable.MODULATE, null);
        long m4i = m4items.getStoredItemCount();
        long m4f = m4fluids.getStoredItemCount();
        long m4g = m4gases.getStoredItemCount();
        host.report(sender, "M4", "★ 同一只元件同时装物品/流体/气体：三者存量互不干扰",
                m4i == 1000L && m4f == 500L && m4g == 700L, "1000/500/700", m4i + "/" + m4f + "/" + m4g);

        // M5: threshold promotion works in gas units too
        GasCellInventory m5 = newGasInventory(host, "m5");
        m5.setMode(2);
        m5.setWorkMode(1);
        m5.setThreshold(1000L);
        m5.injectItems(gasStackOf(sample, 999), Actionable.MODULATE, null);
        boolean m5before = m5.isInfinite(sampleKey);
        m5.injectItems(gasStackOf(sample, 1), Actionable.MODULATE, null);
        boolean m5after = m5.isInfinite(sampleKey);
        host.report(sender, "M5", "气体阈值：存 999 不升级、再存 1（共 1000）升级为无限",
                !m5before && m5after, "false/true", m5before + "/" + m5after);

        // M6: mode 3 cost for gases, measured like the other channels
        IItemList<IAEGasStack> cold = gasChannel.createList();
        long coldStart = System.nanoTime();
        m1.getAvailableItems(cold);
        long coldNanos = System.nanoTime() - coldStart;
        IItemList<IAEGasStack> warm = gasChannel.createList();
        long warmStart = System.nanoTime();
        m1.getAvailableItems(warm);
        long warmNanos = System.nanoTime() - warmStart;
        sender.sendMessage(new TextComponentString(String.format(
                "[ae2addon] [M6] 气体 mode3 列出 %d 个键：冷 %.1f ms / 热 %.2f ms", warm.size(),
                coldNanos / 1e6, warmNanos / 1e6)));
        host.report(sender, "M6", "气体 mode3 热重建 < 50 ms", warmNanos < 50_000_000L, "<50ms",
                String.format("%.2f ms", warmNanos / 1e6));
    }

    /** Prefers hydrogen (always registered by Mekanism) so the assertions are readable. */
    private static Gas pickSample(List<Gas> gases) {
        for (Gas gas : gases) {
            if (gas != null && "hydrogen".equals(gas.getName())) {
                return gas;
            }
        }
        for (Gas gas : gases) {
            if (gas != null) {
                return gas;
            }
        }
        return null;
    }

    private static GasCellInventory newGasInventory(CommandCellSelfTest host, String tag) {
        GasCellInventory inv = new GasCellInventory(CommandCellSelfTest.testCell(tag), null);
        CommandCellSelfTest.wipeCell(inv.rawData());
        return inv;
    }

    private static IAEGasStack gasKey(Gas gas) {
        return AEGasStack.of(new GasStack(gas, 1));
    }

    private static IAEGasStack gasStackOf(Gas gas, int amount) {
        return AEGasStack.of(new GasStack(gas, amount));
    }

    private static long gasListedSize(GasCellInventory inv, IAEGasStack key) {
        IItemList<IAEGasStack> list = GasCellChannel.INSTANCE.storage().createList();
        inv.getAvailableItems(list);
        IAEGasStack found = list.findPrecise(key);
        return found == null ? -1L : found.getStackSize();
    }
}