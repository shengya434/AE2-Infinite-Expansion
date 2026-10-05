package com.ae2addon.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.ae2addon.cell.ChannelCellCore;

import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.DriveWatcher;
import appeng.me.storage.MEInventoryHandler;

/**
 * Stops the drive from posting the physical amount it moved for the rows our cell reports as <b>infinite</b>.
 *
 * <p><b>Why</b> (sensei, 2026-10-05): with the announcement fixed, 「存入无限」 turned infinite immediately, but
 * "存入时数值溢出，取出时数值减少" -- the terminal's number moved. Cause: {@code DriveWatcher.injectItems} /
 * {@code extractItems} end with
 * <pre>
 *   postAlterationOfStoredItems(channel, singletonList(input.copy().setStackSize(size - remainder)), source)
 * </pre>
 * i.e. the drive tells the network "n items were moved". That is correct bookkeeping for ordinary storage, but an
 * infinite row does not count items: adding n to {@code Long.MAX_VALUE} wraps the row NEGATIVE (it disappears from
 * the terminal) and taking n out shrinks the displayed number. Our own announcement then had to repair the row
 * one tick later, which is what showed up as the number "flashing".
 *
 * <p><b>What this does</b>: redirects that one call and drops the entries whose key our cell reports as infinite,
 * forwarding everything else unchanged. Finite storage keeps AE2's own accounting exactly as before -- which is
 * also what our announcer relies on (it never posts finite moves itself, or the two would double count).
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
        List<IAEStack<?>> keep = new ArrayList<>();
        for (IAEStack<?> item : moved) {
            if (item != null && ChannelCellCore.reportsInfinite(self, item)) {
                continue;
            }
            keep.add(item);
        }
        if (keep.isEmpty()) {
            return;
        }
        ((IStorageGrid) grid).postAlterationOfStoredItems((IStorageChannel) channel, keep, source);
    }
}
