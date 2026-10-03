package com.ae2addon.cell;

import com.ae2addon.AE2Addon;
import com.ae2addon.item.ItemUnlimitedCell;

import appeng.api.AEApi;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
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
        // Optional channels register here too: this runs in FMLInitializationEvent, and the door only reads
        // the mod list unless the bridge mod is actually there.
        com.ae2addon.cell.gas.GasChannelDoor.register();
        com.ae2addon.cell.essentia.EssentiaChannelDoor.register();
        com.ae2addon.cell.mana.ManaChannelDoor.register();
        com.ae2addon.cell.flux.FluxChannelDoor.register();
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
        // rv6 asks ONE CHANNEL AT A TIME, and the same universal cell answers for each channel it speaks:
        // that is what makes "one cell, every storage type" possible without AE2 knowing about it.
        CellChannel<?> adapter = CellChannelRegistry.byStorage(channel);
        if (adapter != null && AE2Addon.LOG.isDebugEnabled()) {
            AE2Addon.LOG.debug("[ae2addon] 被问通道 {}：provider={} nbt={}", adapter.id(), saveProvider != null,
                    stack.getTagCompound());
        }
        if (adapter == ItemCellChannel.INSTANCE) {
            return new BasicCellInventoryHandler<>(new UnlimitedCellInventory(stack, saveProvider), channel);
        }
        if (adapter == FluidCellChannel.INSTANCE) {
            @SuppressWarnings("unchecked")
            IStorageChannel<IAEFluidStack> fluidChannel = (IStorageChannel<IAEFluidStack>) channel;
            @SuppressWarnings("unchecked")
            ICellInventoryHandler<T> handler = (ICellInventoryHandler<T>) new BasicCellInventoryHandler<>(
                    new FluidCellInventory(stack, saveProvider), fluidChannel);
            return handler;
        }
        // Optional bridge channels (gas today, essentia next): asked through the door, which loads the
        // bridge-specific classes only when that mod is present.
        ICellInventoryHandler<T> optional = com.ae2addon.cell.gas.GasChannelDoor.tryBuild(stack, saveProvider, channel);
        if (optional != null) {
            return optional;
        }
        optional = com.ae2addon.cell.essentia.EssentiaChannelDoor.tryBuild(stack, saveProvider, channel);
        if (optional != null) {
            return optional;
        }
        optional = com.ae2addon.cell.mana.ManaChannelDoor.tryBuild(stack, saveProvider, channel);
        if (optional != null) {
            return optional;
        }
        optional = com.ae2addon.cell.flux.FluxChannelDoor.tryBuild(stack, saveProvider, channel);
        if (optional != null) {
            return optional;
        }
        // A channel we know about but have no inventory for yet (or one another mod owns): answer nothing
        // rather than guessing, so AE2 simply skips this cell for that channel.
        return null;
    }
}
