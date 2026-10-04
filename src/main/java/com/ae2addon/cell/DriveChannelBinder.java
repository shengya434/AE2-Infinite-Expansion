package com.ae2addon.cell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import appeng.api.AEApi;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;

/**
 * Registers our universal cell for the storage channels AE2's drive leaves out.
 *
 * <p><b>Why this exists</b> (javap on AE2-UEL v0.56.7 {@code TileDrive.updateState()}, on its 0.56.6
 * predecessor, and on CrazyAE's {@code TileImprovedDrive} -- all three byte-for-byte the same shape):
 * the drive walks every registered {@code IStorageChannel}, calls
 * {@code ICellHandler.getCellInventory(stack, drive, channel)}, and on the <b>first</b> channel that answers
 * non-null it registers the cell and jumps straight to the next slot. So one slot holds exactly one channel,
 * and which one depends on the iteration order of {@code IStorageHelper.storageChannels()} -- a Guava
 * ClassToInstanceMap over a HashMap, i.e. Class identity hashes, i.e. a different order on every launch.
 *
 * <p>That is right for a single-channel cell and fatal for ours: we answer for six channels, so the drive
 * picked one at random and the ME network never saw the other five. That is exactly sensei's report --
 * "the terminal shows one storage type, or nothing at all, and nothing can be inserted", with the storage
 * type changing between launches (BUG1/BUG2, 2026-10-03/04).
 *
 * <p><b>What this does</b>: after AE2 has done its own pass, it adds the channels that pass skipped. It
 * deliberately touches nothing else -- only slots whose {@code ICellHandler} is ours are considered, so other
 * mods' cells keep AE2's one-channel-per-slot behaviour untouched.
 *
 * <p>Kept free of {@code TileDrive} types on purpose: the mixin adapts the drive, and the self-test drives
 * this same code with a fake slot source (U group), so the patch logic is covered without a world.
 */
public final class DriveChannelBinder {

    private DriveChannelBinder() {
    }

    /** Where the slots come from: the drive in production, a fake in the self-test. */
    public interface Slots {

        int count();

        ItemStack stackAt(int slot);

        ISaveProvider provider();

        int priority();
    }

    /** How one channel's registration is materialised: a {@code DriveWatcher} in production. */
    public interface Watchers {

        IMEInventoryHandler<?> create(ICellInventoryHandler<?> handler, ItemStack stack, ICellHandler cellHandler,
                ISaveProvider provider, int priority);
    }

    /**
     * Adds every channel the drive skipped for our cell.
     *
     * @return how many registrations were added (0 when there was nothing to do)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int bindMissing(Map inventoryHandlers, Slots slots, Watchers watchers) {
        if (inventoryHandlers == null || slots == null || watchers == null) {
            return 0;
        }
        // Raw on purpose: AE2 declares this as Collection<IStorageChannel<? extends IAEStack<?>>>, and every
        // per-channel call below is made through the raw IStorageChannel anyway (the binder never names a
        // stack type). Note it is the SAME collection instance AE2's updateState iterated, and it is the
        // order of that iteration that decides which channel the drive bound.
        java.util.Collection channels = AEApi.instance().storage().storageChannels();
        if (channels == null || channels.isEmpty()) {
            return 0;
        }
        List order = new ArrayList(channels);
        int added = 0;
        final int slotCount = slots.count();
        for (int slot = 0; slot < slotCount; slot++) {
            ItemStack stack = slots.stackAt(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ICellHandler cellHandler = AEApi.instance().registries().cell().getHandler(stack);
            if (!(cellHandler instanceof UnlimitedCellHandler)) {
                continue;
            }
            // Reproduce AE2's own choice: the first channel in this order that answers. The drive already
            // registered that one -- adding it again would list the cell twice in that channel.
            IStorageChannel alreadyBound = null;
            for (Object element : order) {
                IStorageChannel channel = (IStorageChannel) element;
                if (cellHandler.getCellInventory(stack, slots.provider(), channel) != null) {
                    alreadyBound = channel;
                    break;
                }
            }
            for (Object element : order) {
                IStorageChannel channel = (IStorageChannel) element;
                if (channel == alreadyBound) {
                    continue;
                }
                ICellInventoryHandler<?> handler = cellHandler.getCellInventory(stack, slots.provider(), channel);
                if (handler == null) {
                    continue;
                }
                List<IMEInventoryHandler> list = (List<IMEInventoryHandler>) inventoryHandlers.get(channel);
                if (list == null) {
                    continue;
                }
                list.add(watchers.create(handler, stack, cellHandler, slots.provider(), slots.priority()));
                added++;
                // Remember that this cell is bound on this channel. The keys are NOT computed here on purpose:
                // binding happens during a grid rebuild, which usually runs while the cell is still in mode 1,
                // and switching modes does not rebuild the drive -- so a key list captured now would be empty
                // forever. ChannelAnnouncer asks the cell again every time it announces.
                //
                // The ITEM channel is tracked too. It used to be skipped ("AE2's own terminal pulls the list"),
                // but a cell that changes what it reports between rebuilds never reaches that list on its own:
                // sensei's mode-2 "store = infinite" items stayed finite in the terminal until the cell was
                // pulled out and put back (2026-10-04). Announcements now carry the DIFFERENCE, so including the
                // item channel costs nothing when nothing changed (and mode 3's thousands of rows are already in
                // the network's list, so they contribute a difference of zero).
                ChannelAnnouncer.queue(slots.provider(), channel, stack);
            }
        }
        return added;
    }
}
