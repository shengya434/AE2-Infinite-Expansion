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
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.me.helpers.MachineSource;
import net.minecraft.item.ItemStack;

/**
 * Tells the network which keys one of our cells reports, so that terminals which only react to change
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
 * <p><b>The keys are computed at announcement time, never cached.</b> First attempt stored the key list that
 * was current when the drive bound the cell, and that silently missed the whole case: binding happens during a
 * grid rebuild, which usually runs while the cell is still in mode 1 (zero essentia), and switching to mode 3
 * does not rebuild the drive. The log then showed the listener registering with nothing to announce. So all we
 * remember here is <i>which cell sits in which channel</i>; the keys come from the cell itself when we post.
 *
 * <p><b>Why deferred by a tick</b>: the drive asks us for a channel view from inside
 * {@code GridStorageCache.cellUpdate()}, which clears its monitor table first -- posting right then would land
 * in a grid that has no monitors yet and be silently lost.
 *
 * <p>Only non-item channels are announced: AE2's own item terminal pulls the list itself, and in mode 3 that
 * list is thousands of keys, which would be a pointless flood.
 */
public final class ChannelAnnouncer {

    private ChannelAnnouncer() {
    }

    /** provider -> channel -> the cell stacks bound there (weak keys: an unloaded drive must not be kept alive). */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, List<ItemStack>>> BOUND = new WeakHashMap<>();

    /** provider -> channels that owe an announcement on the next tick. */
    private static final Map<ISaveProvider, List<IStorageChannel<?>>> DIRTY = new WeakHashMap<>();

    /** Upper bound on tracked providers, so a pathological rebuild loop cannot grow this without limit. */
    private static final int MAX_PROVIDERS = 64;

    /** Upper bound on remembered cells per provider+channel. */
    private static final int MAX_CELLS = 64;

    /**
     * Remembers that a cell of ours is bound on this channel, and schedules an announcement for it. Called from
     * the drive patch on every real rebuild; safe to call from inside a grid rebuild.
     */
    public static synchronized void queue(ISaveProvider provider, IStorageChannel<?> channel, ItemStack stack) {
        if (provider == null || channel == null || stack == null || stack.isEmpty()) {
            return;
        }
        Map<IStorageChannel<?>, List<ItemStack>> perProvider = BOUND.get(provider);
        if (perProvider == null) {
            if (BOUND.size() >= MAX_PROVIDERS) {
                return;
            }
            perProvider = new LinkedHashMap<>();
            BOUND.put(provider, perProvider);
        }
        List<ItemStack> cells = perProvider.get(channel);
        if (cells == null) {
            cells = new ArrayList<>();
            perProvider.put(channel, cells);
        }
        // Identity on purpose: AE2 hands us the very stack that sits in the slot, so the same slot does not
        // pile up entries across rebuilds. A replaced stack simply adds one more entry, which is harmless.
        for (ItemStack existing : cells) {
            if (existing == stack) {
                markForFlush(provider, channel);
                return;
            }
        }
        if (cells.size() < MAX_CELLS) {
            cells.add(stack);
        }
        markForFlush(provider, channel);
    }

    /**
     * A new listener registered on this channel (AE2's {@code NetworkMonitor.addListener}) -- announce every
     * bound cell that speaks it.
     *
     * <p>This is the half that actually reaches third-party terminals: they register their listener when the
     * player OPENS them, and AE2 pushes nothing at that moment, so an announcement made when the cell was bound
     * is long past by the time anyone looks.
     */
    public static synchronized void onListenerRegistered(IStorageChannel<?> channel) {
        if (channel == null || BOUND.isEmpty()) {
            return;
        }
        for (Map.Entry<ISaveProvider, Map<IStorageChannel<?>, List<ItemStack>>> binding : BOUND.entrySet()) {
            if (binding.getValue().containsKey(channel)) {
                markForFlush(binding.getKey(), channel);
            }
        }
    }

    /** Schedules one (provider, channel) pair for the next tick. Caller holds the lock. */
    private static void markForFlush(ISaveProvider provider, IStorageChannel<?> channel) {
        List<IStorageChannel<?>> channels = DIRTY.get(provider);
        if (channels == null) {
            channels = new ArrayList<>();
            DIRTY.put(provider, channels);
        }
        if (!channels.contains(channel)) {
            channels.add(channel);
        }
    }

    /** Posts everything scheduled so far. Called once per server tick, server thread only. */
    public static synchronized void flush() {
        if (DIRTY.isEmpty()) {
            return;
        }
        List<Map.Entry<ISaveProvider, List<IStorageChannel<?>>>> work = new ArrayList<>(DIRTY.entrySet());
        DIRTY.clear();
        for (Map.Entry<ISaveProvider, List<IStorageChannel<?>>> entry : work) {
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
                for (IStorageChannel<?> channel : entry.getValue()) {
                    List<IAEStack<?>> keys = keysNow(provider, channel);
                    if (keys.isEmpty()) {
                        continue;
                    }
                    storage.postAlterationOfStoredItems(channel, keys, source);
                }
            } catch (RuntimeException | LinkageError failed) {
                // Never let a notification problem escape into the tick loop: the cell itself is fine, the
                // terminals simply keep their previous picture until the next announcement.
                com.ae2addon.AE2Addon.LOG.warn("[ae2addon] 通知网络库存变化失败：{}", failed.toString());
            }
        }
    }

    /**
     * What the bound cells report for a channel RIGHT NOW. Asking the cell (through AE2's cell registry, the
     * same call a drive makes) is what makes mode switches work: the keys are a property of the current mode,
     * not of the moment the drive happened to bind us.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<IAEStack<?>> keysNow(ISaveProvider provider, IStorageChannel<?> channel) {
        List<IAEStack<?>> keys = new ArrayList<>();
        Map<IStorageChannel<?>, List<ItemStack>> perProvider = BOUND.get(provider);
        List<ItemStack> cells = perProvider == null ? null : perProvider.get(channel);
        if (cells == null) {
            return keys;
        }
        for (ItemStack stack : new ArrayList<>(cells)) {
            try {
                ICellInventoryHandler<?> handler = AEApi.instance().registries().cell()
                        .getCellInventory(stack, provider, (IStorageChannel) channel);
                if (handler == null) {
                    continue;
                }
                appeng.api.storage.data.IItemList list = channel.createList();
                // List through the CELL INVENTORY, not through the handler.
                //
                // MEInventoryHandler.getAvailableItems starts with
                //     if (gettingAvailableContent || !hasReadAccess) return out;
                // and a handler taken straight from the cell registry has not been granted read access -- the
                // drive grants it when it wraps the handler in a DriveWatcher. Asking the handler therefore
                // listed NOTHING, while the self-test and the diag command, which go through getCellInv(), see
                // the whole mode-3 row set. (Cost a full debugging round on 2026-10-04.)
                appeng.api.storage.ICellInventory cellInv = handler.getCellInv();
                if (cellInv != null) {
                    cellInv.getAvailableItems(list);
                } else {
                    handler.getAvailableItems(list);
                }
                for (Object element : list) {
                    keys.add((IAEStack<?>) element);
                }
            } catch (RuntimeException | LinkageError failed) {
                // A cell that cannot be asked right now simply contributes nothing this round. Note by
                // experience (2026-10-04): a silent catch here made four different failures look identical --
                // if this ever needs debugging again, log the exception instead of swallowing it.
                com.ae2addon.AE2Addon.LOG.warn("[ae2addon] 播报前向元件取键失败：{}", failed.toString());
            }
        }
        return keys;
    }

    /** True when this channel is served by AE2's own terminal, which pulls the list instead of waiting. */
    public static boolean pullsItself(IStorageChannel<?> channel) {
        return channel != null && channel == AEApi.instance().storage()
                .getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class);
    }
}
