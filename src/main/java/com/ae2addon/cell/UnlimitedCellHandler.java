package com.ae2addon.cell;

import com.ae2addon.AE2Addon;
import com.ae2addon.item.ItemUnlimitedCell;

import appeng.api.AEApi;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.BasicCellInventoryHandler;
import net.minecraft.item.ItemStack;

/**
 * Hooks the unlimited cell into AE2's cell registry.
 *
 * <p>Why the cell item deliberately does NOT implement {@code IStorageCell}: AE2's own
 * {@code BasicCellHandler.isCell(stack)} is exactly {@code stack.getItem() instanceof IStorageCell},
 * and it is registered before us. If our item implemented that interface, the registry (a plain
 * {@code ArrayList} scanned in order) would hand our stacks to AE2's handler and our inventory would
 * never be consulted. Verified by javap on AE2-UEL v0.56.7.
 */
public class UnlimitedCellHandler implements ICellHandler {

    private static final UnlimitedCellHandler INSTANCE = new UnlimitedCellHandler();

    public static void register() {
        AEApi.instance().registries().cell().addCellHandler(INSTANCE);
        AE2Addon.LOG.info("[ae2addon] 无限存储元件的 ICellHandler 已注册");
    }

    @Override
    public boolean isCell(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ItemUnlimitedCell;
    }

    @Override
    public <T extends IAEStack<T>> ICellInventoryHandler<T> getCellInventory(ItemStack stack, ISaveProvider saveProvider,
            IStorageChannel<T> channel) {
        if (!isCell(stack)) {
            return null;
        }
        // One handler per channel: rv6 asks per channel, so a future fluid/gas cell registers its own.
        if (channel != (IStorageChannel<?>) UnlimitedCellInventory.itemChannel()) {
            return null;
        }
        // BasicCellInventoryHandler is AE2's own wrapper (CreativeCellInventory uses it too). Handing it a
        // real ICellInventory is what makes getCellInv() non-null -- which is what the Drive's status LED,
        // the upgrade-card scan and the partition list all depend on.
        return new BasicCellInventoryHandler<>(new UnlimitedCellInventory(stack, saveProvider), channel);
    }
}
