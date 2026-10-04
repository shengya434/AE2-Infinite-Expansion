package com.ae2addon.cell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import appeng.api.AEApi;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.me.helpers.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;

/**
 * Tells the network which keys one of our cells now reports, so that terminals which only react to change
 * notifications learn about rows that appeared "by themselves".
 *
 * <p><b>Why this is needed</b> (sensei BUG3, 2026-10-04: "in mode 3 nothing shows up in the essentia
 * terminal"): AE2's own terminals PULL the monitor's list when they open ({@code ContainerMEMonitorable} asks
 * {@code IMEMonitor.getStorageList()}), but third-party terminals -- Thaumic Energistics, Mekanism
 * Energistics and friends -- only accumulate what they are told changed:
 * {@code ContainerEssentiaTerminal.postChange} just does {@code items.add(...)} (javap), and
 * {@code NetworkMonitor.addListener} merely puts the listener in a map without pushing anything. So a cell
 * whose rows appear without an insert or extract (mode 3's "everything is infinite", or a rule that lists an
 * item nobody stored) is invisible to those terminals until something posts an alteration mentioning those
 * keys.
 *
 * <p><b>Why deferred by a tick</b>: the drive asks us for a channel view from inside
 * {@code GridStorageCache.cellUpdate()}, which clears its monitor table first -- posting right then would land
 * in a grid that has no monitors yet and be silently lost. Queueing and flushing on the next server tick
 * posts into a fully built grid.
 *
 * <p>Only non-item channels are announced: AE2's own item terminal pulls the list itself, and in mode 3 that
 * list is thousands of keys, which would be a pointless flood.
 */
public final class ChannelAnnouncer {

    private ChannelAnnouncer() {
    }

    /** provider -> channel -> the keys to announce. Weak keys: an unloaded drive must not be kept alive. */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, List<IAEStack<?>>>> PENDING =
            new WeakHashMap<>();

    /**
     * What each bound cell currently lists, kept so we can repeat the announcement when a NEW listener shows
     * up. That repeat is the actual fix for sensei's BUG3: a third-party terminal registers its listener when
     * the player OPENS it, and AE2 pushes nothing at that moment, so an announcement that only happens when
     * the cell is bound is already history by the time anyone looks.
     */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, List<IAEStack<?>>>> BOUND =
            new WeakHashMap<>();

    /** Upper bound on queued providers, so a pathological rebuild loop cannot grow this without limit. */
    private static final int MAX_PROVIDERS = 64;

    /**
     * Queues "these keys exist in this channel" for the next server tick. Safe to call from inside a grid
     * rebuild; identical requests from the same provider collapse into one.
     */
    public static synchronized void queue(ISaveProvider provider, IStorageChannel<?> channel,
            List<? extends IAEStack<?>> keys) {
        if (provider == null || channel == null || keys == null || keys.isEmpty()) {
            return;
        }
        Map<IStorageChannel<?>, List<IAEStack<?>>> perProvider = BOUND.get(provider);
        if (perProvider == null) {
            if (BOUND.size() >= MAX_PROVIDERS) {
                return;
            }
            perProvider = new LinkedHashMap<>();
            BOUND.put(provider, perProvider);
        }
        perProvider.put(channel, new ArrayList<IAEStack<?>>(keys));
        markForFlush(provider, channel);
    }

    /**
     * A new listener registered on this channel (AE2's {@code NetworkMonitor.addListener}) -- repeat the
     * announcement for every bound cell that speaks it.
     *
     * <p>This is the half that actually fixes sensei's BUG3: third-party terminals register their listener when
     * the player OPENS them, and AE2 pushes nothing at that moment, so the announcement made when the cell was
     * bound is long past by the time anyone looks.
     */
    public static synchronized void onListenerRegistered(IStorageChannel<?> channel) {
        if (channel == null || BOUND.isEmpty()) {
            return;
        }
        for (Map.Entry<ISaveProvider, Map<IStorageChannel<?>, List<IAEStack<?>>>> binding : BOUND.entrySet()) {
            if (binding.getValue().containsKey(channel)) {
                markForFlush(binding.getKey(), channel);
            }
        }
    }

    /** Moves one (provider, channel) pair into the next tick's flush list. Caller holds the lock. */
    private static void markForFlush(ISaveProvider provider, IStorageChannel<?> channel) {
        List<IAEStack<?>> keys = BOUND.get(provider) == null ? null : BOUND.get(provider).get(channel);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Map<IStorageChannel<?>, List<IAEStack<?>>> perProvider = PENDING.get(provider);
        if (perProvider == null) {
            perProvider = new LinkedHashMap<>();
            PENDING.put(provider, perProvider);
        }
        perProvider.put(channel, keys);
    }

    /** Posts everything queued so far. Called once per server tick, server thread only. */
    public static synchronized void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Map.Entry<ISaveProvider, Map<IStorageChannel<?>, List<IAEStack<?>>>>> work =
                new ArrayList<>(PENDING.entrySet());
        PENDING.clear();
        for (Map.Entry<ISaveProvider, Map<IStorageChannel<?>, List<IAEStack<?>>>> entry : work) {
            ISaveProvider provider = entry.getKey();
            if (!(provider instanceof IActionHost)) {
                continue;
            }
            IActionHost host = (IActionHost) provider;
            try {
                IGridNode node = host.getActionableNode();
                if (node == null || node.getGrid() == null) {
                    continue;
                }
                // rv6 has no IGrid#getStorage(): the storage grid is a grid cache.
                IStorageGrid storage = node.getGrid().getCache(IStorageGrid.class);
                if (storage == null) {
                    continue;
                }
                IActionSource source = new MachineSource(host);
                for (Map.Entry<IStorageChannel<?>, List<IAEStack<?>>> perChannel : entry.getValue().entrySet()) {
                    storage.postAlterationOfStoredItems(perChannel.getKey(), perChannel.getValue(), source);
                }
            } catch (RuntimeException | LinkageError failed) {
                // Never let a notification problem escape into the tick loop: the cell itself is fine, the
                // terminals simply keep their previous picture until the next announcement.
                com.ae2addon.AE2Addon.LOG.warn("[ae2addon] 通知网络库存变化失败：{}", failed.toString());
            }
        }
    }

    /** True when this channel is served by AE2's own terminal, which pulls the list instead of waiting. */
    public static boolean pullsItself(IStorageChannel<?> channel) {
        return channel != null && channel == AEApi.instance().storage()
                .getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class);
    }
}
