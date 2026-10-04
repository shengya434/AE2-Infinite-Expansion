package com.ae2addon.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ae2addon.cell.DriveChannelBinder;

import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.me.storage.DriveWatcher;
import appeng.tile.inventory.AppEngCellInventory;
import appeng.tile.storage.TileDrive;
import net.minecraft.item.ItemStack;

/**
 * Makes the AE2 drive register our universal cell for <b>every</b> channel it speaks, not just the first one
 * that answers. See {@link DriveChannelBinder} for the bytecode evidence behind that.
 *
 * <p>The injection point is the tail of {@code TileDrive.updateState()}'s <b>full</b> path -- the call to
 * {@code AENetworkProxy.setIdlePowerUsage}, which sits after the slot loop and just before
 * {@code isCached = true}. AE2 keeps its own behaviour; we only append the channels it dropped.
 *
 * <p><b>Why not {@code @At("RETURN")}</b> (this cost a real bug, 2026-10-04): {@code updateState} starts with
 * {@code if (isCached) return;} -- the cached call {@code getCellArray()} makes on every grid rebuild. A RETURN
 * injection fires on that early return too, so every grid rebuild appended another copy of our registrations to
 * the SAME lists AE2 only clears when it really rebuilds. The lists grew without bound (log evidence: the item
 * channel went 1 -> 21 -> 27 entries), the network summed the same cell that many times, and the terminal showed
 * sensei's "1 item becomes 2 / 10 / 14" phantom row. Anchoring inside the rebuild-only path fixes it at the
 * source; {@code defaultRequire = 1} in the config makes a future mismatch fail loudly instead of silently
 * binding nothing.
 *
 * <p>{@code remap = false} everywhere: the target is a mod class whose members are not obfuscated, so there is
 * nothing to remap and no refmap to generate. That is also why the config declares no refmap.
 */
@Mixin(value = TileDrive.class, remap = false)
public abstract class MixinTileDrive {

    /** {@code Map<IStorageChannel<...>, List<IMEInventoryHandler>>} -- raw on purpose, see the class javadoc. */
    @Shadow(remap = false)
    private Map inventoryHandlers;

    @Shadow(remap = false)
    private AppEngCellInventory inv;

    @Inject(method = "updateState",
            at = @At(value = "INVOKE",
                    target = "Lappeng/me/helpers/AENetworkProxy;setIdlePowerUsage(D)V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void ae2addon$bindEveryChannel(CallbackInfo ci) {
        final AppEngCellInventory cellSlots = this.inv;
        final Map handlers = this.inventoryHandlers;
        if (cellSlots == null || handlers == null) {
            return;
        }
        final TileDrive drive = (TileDrive) (Object) this;
        DriveChannelBinder.bindMissing(handlers, new DriveChannelBinder.Slots() {

            @Override
            public int count() {
                return cellSlots.getSlots();
            }

            @Override
            public ItemStack stackAt(int slot) {
                return cellSlots.getStackInSlot(slot);
            }

            @Override
            public ISaveProvider provider() {
                return drive;
            }

            @Override
            public int priority() {
                return drive.getPriority();
            }
        }, new DriveChannelBinder.Watchers() {

            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            public IMEInventoryHandler<?> create(ICellInventoryHandler<?> handler, ItemStack stack,
                    ICellHandler cellHandler, ISaveProvider provider, int priority) {
                // Same wrapper AE2 builds for the channel it did bind, so priority and the status/blink
                // plumbing behave identically for the channels we add.
                DriveWatcher watcher = new DriveWatcher(handler, stack, cellHandler, drive);
                watcher.setPriority(priority);
                return watcher;
            }
        });
    }
}
