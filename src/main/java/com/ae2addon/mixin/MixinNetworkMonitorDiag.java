package com.ae2addon.mixin;

import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ae2addon.AE2Addon;

import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.NetworkMonitor;
import net.minecraft.item.ItemStack;

/**
 * TEMPORARY DIAGNOSTIC (2026-10-05): who posts changes to the network's stock list?
 *
 * <p>Question it answers: sensei's infinite row keeps drifting by exactly the amount he takes out, even after
 * {@link MixinDriveWatcher} stopped the drive itself from posting those amounts -- so something else posts them,
 * and guessing was not getting us anywhere. {@code NetworkMonitor.postChange} is the funnel every source goes
 * through (drive watchers, storage buses, the ME chest, the cell-change tracker, and our own announcements), so
 * one line per distinct caller + channel + key names the culprit.
 *
 * <p>Remove this class and its entry in {@code ae2addon.mixins.json} once the source is known.
 */
@Mixin(value = NetworkMonitor.class, remap = false)
public abstract class MixinNetworkMonitorDiag {

    /** Distinct (caller, channel, key) combinations already logged; keeps a busy run from flooding the log. */
    private static final Set<String> SEEN = new HashSet<>();

    @Inject(method = "postChange", at = @At("HEAD"))
    @SuppressWarnings("rawtypes")
    private void ae2addon$whoPosts(boolean sticky, Iterable changed, IActionSource src, CallbackInfo ci) {
        if (AE2Addon.LOG == null) {
            return;
        }
        String caller = callerOf();
        for (Object element : changed) {
            IAEStack<?> item = (IAEStack<?>) element;
            String line = caller + " | 量=" + item.getStackSize() + " | " + describe(item);
            if (!SEEN.add(line)) {
                break; // already reported this exact combination
            }
            AE2Addon.LOG.info("[ae2addon][diag] 播报来源 = {}", line);
            break;
        }
    }

    /** The first frame outside of AE2's own monitor/collection plumbing. */
    private static String callerOf() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("appeng.me.cache.NetworkMonitor") || name.startsWith("appeng.me.cache.GridStorageCache")
                    || name.startsWith("java.") || name.startsWith("net.minecraft.")) {
                continue;
            }
            return name + "#" + frame.getMethodName();
        }
        return "?";
    }

    /** A stable, non-localised key name, for the log only. */
    private static String describe(IAEStack<?> key) {
        try {
            ItemStack stack = key.asItemStackRepresentation();
            if (stack != null && !stack.isEmpty() && stack.getItem().getRegistryName() != null) {
                return stack.getItem().getRegistryName().toString();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // logging must never break the game
        }
        return String.valueOf(key);
    }
}
