package com.ae2addon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ae2addon.cell.ChannelAnnouncer;

import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.IStorageChannel;
import appeng.me.cache.NetworkMonitor;

/**
 * Repeats the "these keys exist" announcement whenever something starts listening on a channel.
 *
 * <p>Why: third-party terminals (Thaumic Energistics, Mekanism Energistics, ...) fill their own key list from
 * {@code postChange} notifications only -- AE2's {@code NetworkMonitor.addListener} just stores the listener and
 * pushes nothing (javap, 2026-10-04). Rows that appear without an insert -- mode 3's "everything is infinite" --
 * therefore never reach them: sensei's BUG3, "mode 3 and the essentia terminal shows nothing". Announcing when
 * the cell is bound is not enough, because the terminal registers its listener later, when the player opens it;
 * this hook closes that gap.
 *
 * <p>{@code remap = false}: the target is a mod class whose members are not obfuscated.
 */
@Mixin(value = NetworkMonitor.class, remap = false)
public abstract class MixinNetworkMonitor {

    @Shadow(remap = false)
    private IStorageChannel myChannel;

    /** {@code Object2ObjectMap<IMEMonitorHandlerReceiver<T>, Object>} -- raw, only its size is of interest. */
    @Shadow(remap = false)
    private it.unimi.dsi.fastutil.objects.Object2ObjectMap listeners;

    @Inject(method = "addListener", at = @At("RETURN"), remap = false)
    private void ae2addon$announceToNewListener(IMEMonitorHandlerReceiver<?> listener, Object verificationToken,
            CallbackInfo ci) {
        // TEMPORARY evidence for BUG3 (2026-10-04): shows that a terminal really did register a listener on
        // this channel, and how many are now there. Pairs with the flush probe in ChannelAnnouncer: with both
        // lines in the log, whichever side stays silent is the broken link.
        ChannelAnnouncer.probe("监听者注册 channel=" + (myChannel == null ? "null" : myChannel.getClass().getSimpleName())
                + " 监听者数=" + (listeners == null ? -1 : listeners.size())
                + " 类=" + (listener == null ? "null" : listener.getClass().getName()));
        ChannelAnnouncer.onListenerRegistered(this.myChannel);
    }
}
