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
 * <p>The injection point is the end of {@code TileDrive.updateState()} -- the private method every grid
 * rebuild funnels through ({@code getCellArray()} calls it, and so does the inventory-change callback). AE2
 * keeps its own behaviour; we only append the channels it dropped. Re-implementing the loop instead would
 * couple this file to AE2's private control flow, which is worse than one extra pass.
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

    @Inject(method = "updateState", at = @At("RETURN"), remap = false)
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
