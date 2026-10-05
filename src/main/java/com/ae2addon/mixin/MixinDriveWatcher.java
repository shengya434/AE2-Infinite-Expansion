package com.ae2addon.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.ae2addon.cell.CellChannelRegistry;
import com.ae2addon.cell.ChannelCellCore;

import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.MEInventoryHandler;

/**
 * Stops the drive from posting the physical amount it moved for rows that are <b>infinite</b>.
 *
 * <p><b>Why</b> (sensei, 2026-10-05): with the announcement fixed, 「存入无限」 turned infinite immediately, but
 * "存入时数值溢出，取出时数值减少" -- the terminal's number moved. Cause: {@code DriveWatcher.injectItems} /
 * {@code extractItems} end with
 * <pre>
 *   postAlterationOfStoredItems(channel, singletonList(input.copy().setStackSize(size - remainder)), source)
 * </pre>
 * i.e. the drive tells the network "n items were moved". Correct bookkeeping for ordinary storage, but an infinite
 * row does not count items: adding n to {@code Long.MAX_VALUE} wraps the row NEGATIVE (it vanishes from the
 * terminal) and taking n out shrinks the displayed number.
 *
 * <p><b>Two cases have to be dropped, and the diagnostic of 2026-10-05 caught both</b>
 * ({@code MixinNetworkMonitorDiag}, since removed):
 * <ol>
 * <li>the movement was served by <i>our</i> cell -- {@link ChannelCellCore#reportsInfinite} says so;</li>
 * <li>the movement was served by <b>another</b> cell, while the same key is claimed infinite by one of ours.
 *     Then the row already reads an infinite-scale number and a {@code +-n} on top of it is just as meaningless
 *     -- that was the remaining flicker: the row read {@code Long.MAX - 1} right after taking one item out, and
 *     our announcer had to restore it on the next tick.</li>
 * </ol>
 *
 * <p><b>What this does</b>: redirects that one call, dropping only those entries, forwarding everything else
 * unchanged. Finite storage keeps AE2's own accounting exactly as before -- which our announcer relies on (it
 * never posts finite moves itself, or the two would double count).
 *
 * <p>{@code remap = false}: the target is a mod class, so there is nothing to remap. {@code injectors.require = 1}
 * in {@code ae2addon.mixins.json} makes a future signature change fail loudly instead of silently doing nothing
 * (sensei's rule: never fail silently).
 */
@Mixin(value = DriveWatcher.class, remap = false)
public abstract class MixinDriveWatcher {

    /**
     * @param grid    the storage grid the drive was about to post to (ignored unless we forward)
     * @param channel the channel that moved
     * @param moved   the singleton list AE2 built for the announcement
     * @param source  AE2's own action source for the drive
     */
    @Redirect(method = {"injectItems", "extractItems"}, at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/storage/IStorageGrid;postAlterationOfStoredItems("
                    + "Lappeng/api/storage/IStorageChannel;Ljava/lang/Iterable;"
                    + "Lappeng/api/networking/security/IActionSource;)V"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void ae2addon$skipInfiniteRows(IStorageGrid grid, IStorageChannel<?> channel,
            Iterable<IAEStack<?>> moved, IActionSource source) {
        if (grid == null || moved == null) {
            return;
        }
        // The drive itself is an MEInventoryHandler wrapping our cell inventory (BasicCellInventoryHandler ->
        // our ChannelCellCore); ChannelCellCore.reportsInfinite unwraps that chain defensively.
        MEInventoryHandler<?> self = (MEInventoryHandler<?>) (Object) this;
        long infinite = infiniteValueOf(channel);
        IItemList known = null;
        List<IAEStack<?>> keep = new ArrayList<>();
        for (IAEStack<?> item : moved) {
            if (item == null) {
                continue;
            }
            if (ChannelCellCore.reportsInfinite(self, item)) {
                continue;
            }
            if (infinite > 0) {
                if (known == null) {
                    known = storedList(grid, channel);
                }
                if (rowIsInfinite(known, item, infinite)) {
                    continue;
                }
            }
            keep.add(item);
        }
        if (keep.isEmpty()) {
            return;
        }
        ((IStorageGrid) grid).postAlterationOfStoredItems((IStorageChannel) channel, keep, source);
    }

    /** The value our channels publish for an infinite row on this channel, 0 when the channel is not ours. */
    private static long infiniteValueOf(IStorageChannel<?> channel) {
        try {
            com.ae2addon.cell.CellChannel<?> adapter = CellChannelRegistry.byStorage(channel);
            return adapter == null ? 0L : adapter.reportedInfinite();
        } catch (RuntimeException | LinkageError ignored) {
            return 0L; // never let this decision break a drive operation
        }
    }

    /** The network's current stock list for the channel, or null when it cannot be read yet. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IItemList storedList(IStorageGrid grid, IStorageChannel<?> channel) {
        try {
            IMEMonitor monitor = grid.getInventory((IStorageChannel) channel);
            return monitor == null ? null : monitor.getStorageList();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Whether the row already holds an infinite-scale amount.
     *
     * <p>"Half of infinity" is the test: no legitimate finite count comes anywhere near it, while a row that an
     * infinite claim owns may legitimately sit a few units below it (that is exactly the drift this patch exists
     * to stop). Both directions are checked, because an {@code infinite + n} row WRAPS to a huge negative number.
     */
    private static boolean rowIsInfinite(IItemList known, IAEStack<?> key, long infinite) {
        if (known == null) {
            return false;
        }
        IAEStack<?> there = known.findPrecise(key);
        if (there == null) {
            return false;
        }
        long size = there.getStackSize();
        long half = infinite / 2;
        return size >= half || size <= -half;
    }
}
