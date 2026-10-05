package com.ae2addon.cell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.ae2addon.AE2Addon;

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
 * <p><b>What AE2 already does for us, and what it does not</b> (javap on AE2-UEL v0.56.7, 2026-10-05): the
 * drive wraps our cell inventory in a {@code DriveWatcher}, and that class <i>itself</i> calls
 * {@code IStorageGrid.postAlterationOfStoredItems} for the items that physically move through it. So plain
 * storing and taking out is already accounted for -- and posting it a second time from here is exactly the
 * "the terminal shows 100-something too many" phantom sensei hit in the MeatballCraft pack (2026-10-04).
 * What nothing posts is the part AE2 cannot see: a change in what our cell <i>reports</i> that no item movement
 * caused -- storing into mode 2's "store = infinite" turns the key into an infinite row, a panel toggle or a
 * rule does the same, and in mode 3 rows simply exist. That is what this class posts now, and only that.
 *
 * <p><b>Single key deltas, not a re-listing</b> (2026-10-05): the previous shape computed the cell's whole
 * report and compared it with the network on every change. That is O(types) per change -- 35,740 keys on the
 * item channel of a 378-mod pack -- and the difference it posted was written as "the network should hold what
 * our cell reports", which silently erased any amount other cells contributed to the same key. Both problems
 * are gone now: the cell tells us which key changed and by how much (before/after of its own report), so a
 * change costs one map lookup, and we never look at the network to compute a number.
 *
 * <p><b>Why deferred by a tick</b>: the drive asks us for a channel view from inside
 * {@code GridStorageCache.cellUpdate()}, which clears its monitor table first -- posting right then would land
 * in a grid that has no monitors yet and be silently lost.
 */
public final class ChannelAnnouncer {

    private ChannelAnnouncer() {
    }

    /** provider -> channel -> the cell stacks bound there (weak keys: an unloaded drive must not be kept alive). */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, List<ItemStack>>> BOUND = new WeakHashMap<>();

    /** provider -> channels that owe an announcement on the next tick. */
    private static final Map<ISaveProvider, List<IStorageChannel<?>>> DIRTY = new WeakHashMap<>();

    /**
     * provider -> channel -> key -> the amount to tell the network about.
     *
     * <p>Accumulated instead of posted right away for two reasons: the post has to happen on a later tick (see
     * the class comment), and a pair whose grid cannot be resolved yet must keep its change instead of dropping
     * it -- that was the "infinite takes 90 seconds to show up" report of 2026-10-04 (announcement landed at
     * 22:52:03 for a store made at 22:50:31). Entries leave this map only after a successful post.
     */
    /**
     * provider -> channel -> key -> the value the row has to READ afterwards.
     *
     * <p><b>Only infinite rows are ever announced</b>, and they are SET, never accumulated:
     * <ul>
     * <li>a FINITE move needs no announcement at all -- the drive wraps our inventory in a {@code DriveWatcher},
     *     and that class posts the physical amount itself (javap, AE2-UEL v0.56.7). Posting it again from here is
     *     a double count (store 64 &rarr; the terminal shows 128), which is why this class no longer has a
     *     "difference" path;</li>
     * <li>an infinite row cannot be added either: the cell's own rule is set-max, never accumulate
     *     ({@code reportInfinite}), and the network's aggregate list sums whatever it is handed. Posting "0 to
     *     Long.MAX_VALUE" onto a row that already holds anything wraps NEGATIVE and the row disappears -- that
     *     is why 「存入无限」 took minutes to show up in sensei's MeatballCraft pack (2026-10-05: the announce
     *     went out with 变化=9223372036854775807). So the announcer asks the network what the row reads now and
     *     posts exactly the missing difference, which lands on the intended value and cannot overflow.</li>
     * </ul>
     */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, Map<IAEStack<?>, Long>>> PENDING =
            new WeakHashMap<>();

    /**
     * provider -> channel -> the size we last TOLD the network for a key.
     *
     * <p>Needed for the one case the monitor cannot answer: a key we used to list and no longer do (a mode
     * switch back to finite storage). AE2's aggregate list adds what it is handed, so the old amount has to be
     * taken back explicitly; the monitor itself cannot tell "our" keys from another cell's.
     */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, Map<IAEStack<?>, Long>>> ANNOUNCED =
            new WeakHashMap<>();

    /** Pairs whose next pass is a pure notification (a terminal just started listening), not a stock change. */
    private static final Map<ISaveProvider, List<IStorageChannel<?>>> NOTIFY_ONLY = new WeakHashMap<>();

    /**
     * Pairs that owe the FULL pass (re-list the cells and difference against what we announced before).
     *
     * <p>Only settings changes need this: a mode switch, a threshold, a rule, "format" -- anything that can move
     * many keys at once, where naming the affected keys up front is not possible. Item movement never comes
     * here; it is handled per key.
     */
    private static final Map<ISaveProvider, List<IStorageChannel<?>>> GLOBAL = new WeakHashMap<>();

    /** provider -> channel -> how many ticks in a row the post failed; gives up (loudly) past the cap. */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, Integer>> ATTEMPTS = new WeakHashMap<>();

    /** provider -> channel -> the tick of the last full pass, so settings bursts cannot cause a listing storm. */
    private static final Map<ISaveProvider, Map<IStorageChannel<?>, Long>> LAST_GLOBAL =
            new WeakHashMap<>();

    /** Upper bound on tracked providers, so a pathological rebuild loop cannot grow this without limit. */
    private static final int MAX_PROVIDERS = 64;

    /** Upper bound on remembered cells per provider+channel. */
    private static final int MAX_CELLS = 64;

    /** Ticks between two full passes on the same pair (0.5 s at 20 tps). */
    private static final int GLOBAL_COOLDOWN = 10;

    /** How many ticks a post may keep failing before it is dropped with a warning (10 s at 20 tps). */
    private static final int MAX_ATTEMPTS = 200;

    /** Ticks this class has been flushed for. Only used for the full-pass cooldown. */
    private static long tick;

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
     * Tells the network how much OUR contribution to some rows just moved.
     *
     * <p>Called only for changes that involve an infinite row (a key turning infinite, or leaving the infinite
     * set); purely finite movement is announced by AE2's own DriveWatcher and must not be posted twice. The
     * amounts are differences of our own report ({@code after - before}), which is the one shape that is safe
     * for a list that SUMS -- and since 2026-10-05 {@code INFINITE} is {@code Long.MAX_VALUE / 4}, so several
     * cells claiming the same key still add up instead of wrapping negative.
     *
     * @param deltas key -> how much our reported amount for it changed (accumulated while the post waits)
     * @param stack  the cell stack, so the binding can be learned even if the drive patch never ran
     */
    public static synchronized void onReportChanged(ISaveProvider provider, IStorageChannel<?> channel,
            ItemStack stack, Map<IAEStack<?>, Long> deltas) {
        if (provider == null || channel == null || deltas == null || deltas.isEmpty()) {
            return;
        }
        Map<IAEStack<?>, Long> perPair = pendingFor(provider, channel);
        if (perPair == null) {
            return;
        }
        learnBinding(provider, channel, stack);
        for (Map.Entry<IAEStack<?>, Long> entry : deltas.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() == 0L) {
                continue;
            }
            Long already = perPair.get(entry.getKey());
            long merged = (already == null ? 0L : already) + entry.getValue();
            if (merged == 0L) {
                perPair.remove(entry.getKey());
            } else {
                perPair.put(entry.getKey(), merged);
            }
        }
        if (!perPair.isEmpty()) {
            markForFlush(provider, channel);
        }
    }

    /**
     * A cell of ours changed in a way that can move many keys at once (mode switch, threshold, rules, format).
     *
     * <p>Without any announcement the network keeps its old picture until something unrelated rebuilds the
     * grid: sensei's MeatballCraft run showed mode-2 "store = infinite" not showing up until the cell was taken
     * out and put back (2026-10-04). This is the fallback for the changes that cannot name their keys; item
     * movement uses {@link #onReportChanged} instead, which is O(1).
     */
    public static synchronized void onCellChanged(ISaveProvider provider, IStorageChannel<?> channel) {
        if (provider == null || channel == null) {
            return;
        }
        markGlobal(provider, channel);
    }

    /**
     * Same as {@link #onCellChanged(ISaveProvider, IStorageChannel)}, but it also carries the cell stack so the
     * binding can be learned even if the drive patch never ran.
     *
     * <p>That last part matters: in sensei's MeatballCraft pack (378 mods) the notification produced nothing,
     * and the reason is that this path used to REQUIRE a binding recorded by the drive patch. If that patch is
     * absent or displaced there, {@code BOUND} stays empty and no announcement is ever made -- silently. Since
     * the cell record does change in that pack (the diag proves it), routing the same information through
     * {@code saveChanges} makes the notification independent of the mixin.
     *
     * <p>Registering an unknown pair here is safe: it only happens when a cell of ours actually saved a change,
     * and {@code flush} resolves the grid through the provider (a drive) anyway. A cell that is not in a drive
     * never gets this far -- a GUI view passes a null provider.
     */
    public static synchronized void onCellChanged(ISaveProvider provider, IStorageChannel<?> channel,
            ItemStack stack) {
        if (provider == null || channel == null) {
            return;
        }
        learnBinding(provider, channel, stack);
        markGlobal(provider, channel);
    }

    /** Channels whose binding was first learned through the save path; logged once each per launch. */
    private static final java.util.Set<String> BINDINGS_FROM_SAVE = new java.util.HashSet<>();

    /** Channels that have announced at least once this launch; logged once each, for diagnosis. */
    private static final java.util.Set<String> ANNOUNCED_CHANNELS = new java.util.HashSet<>();

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
                markForFlush(binding.getKey(), channel, true);
            }
        }
    }

    /** Schedules one (provider, channel) pair for the next tick. Caller holds the lock. */
    private static void markForFlush(ISaveProvider provider, IStorageChannel<?> channel) {
        markForFlush(provider, channel, false);
    }

    /**
     * Schedules one (provider, channel) pair for the next tick.
     *
     * @param notifyOnly true when the pass exists because a terminal started listening: then keys whose stock
     *                   did not change are still sent, with size 0. Third-party terminals learn keys only from
     *                   a notification and read the real amount out of the monitor themselves (ThE's
     *                   {@code sendDelta} does exactly that), so the notification must not carry stock.
     */
    private static void markForFlush(ISaveProvider provider, IStorageChannel<?> channel, boolean notifyOnly) {
        List<IStorageChannel<?>> channels = DIRTY.get(provider);
        if (channels == null) {
            channels = new ArrayList<>();
            DIRTY.put(provider, channels);
        }
        if (!channels.contains(channel)) {
            channels.add(channel);
        }
        if (notifyOnly) {
            List<IStorageChannel<?>> flags = NOTIFY_ONLY.get(provider);
            if (flags == null) {
                flags = new ArrayList<>();
                NOTIFY_ONLY.put(provider, flags);
            }
            if (!flags.contains(channel)) {
                flags.add(channel);
            }
        }
    }

    /** Remembers a binding learned outside the drive patch, and says so once per channel per launch. */
    private static void learnBinding(ISaveProvider provider, IStorageChannel<?> channel, ItemStack stack) {
        Map<IStorageChannel<?>, List<ItemStack>> perProvider = BOUND.get(provider);
        if (perProvider != null && perProvider.containsKey(channel)) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (BINDINGS_FROM_SAVE.add(channel.getClass().getName()) && AE2Addon.LOG != null) {
            // One line per channel per launch: evidence about whether the drive patch is doing its job in this
            // installation. Not a warning about a mistake -- the fallback is legitimate, just less precise
            // (the drive knows exactly which slots hold which cell; this only knows "this cell is bound here").
            AE2Addon.LOG.info("[ae2addon] 通道 {} 的绑定记录来自元件存档路径（驱动器补丁未登记该绑定）",
                    channel.getClass().getSimpleName());
        }
        queue(provider, channel, stack);
    }

    /** The pending per-key amounts for one pair, created on demand; null when nothing may be tracked. */
    private static Map<IAEStack<?>, Long> pendingFor(ISaveProvider provider, IStorageChannel<?> channel) {
        Map<IStorageChannel<?>, Map<IAEStack<?>, Long>> perProvider = PENDING.get(provider);
        if (perProvider == null) {
            if (PENDING.size() >= MAX_PROVIDERS) {
                return null;
            }
            perProvider = new LinkedHashMap<>();
            PENDING.put(provider, perProvider);
        }
        Map<IAEStack<?>, Long> perPair = perProvider.get(channel);
        if (perPair == null) {
            perPair = new LinkedHashMap<>();
            perProvider.put(channel, perPair);
        }
        return perPair;
    }

    /** Marks a pair as owing the full pass. Caller holds the lock. */
    private static void markGlobal(ISaveProvider provider, IStorageChannel<?> channel) {
        Map<IStorageChannel<?>, List<ItemStack>> perProvider = BOUND.get(provider);
        if (perProvider == null || !perProvider.containsKey(channel)) {
            // No binding recorded: the only way in is a cell that saved a change and told us its stack, which
            // onCellChanged(provider, channel, stack) has already queued by now.
            return;
        }
        List<IStorageChannel<?>> channels = GLOBAL.get(provider);
        if (channels == null) {
            if (GLOBAL.size() >= MAX_PROVIDERS) {
                return;
            }
            channels = new ArrayList<>();
            GLOBAL.put(provider, channels);
        }
        if (!channels.contains(channel)) {
            channels.add(channel);
        }
        markForFlush(provider, channel);
    }

    /** Posts everything scheduled so far. Called once per server tick, server thread only. */
    public static synchronized void flush() {
        tick++;
        if (DIRTY.isEmpty()) {
            return;
        }
        List<Map.Entry<ISaveProvider, List<IStorageChannel<?>>>> work = new ArrayList<>(DIRTY.entrySet());
        DIRTY.clear();
        for (Map.Entry<ISaveProvider, List<IStorageChannel<?>>> entry : work) {
            ISaveProvider provider = entry.getKey();
            if (!(provider instanceof IActionHost)) {
                drop(provider, entry.getValue());
                continue;
            }
            IActionHost host = (IActionHost) provider;
            try {
                IGridNode node = host.getActionableNode();
                IStorageGrid storage = node == null || node.getGrid() == null ? null
                        // rv6 has no IGrid#getStorage(): the storage grid is a grid cache.
                        : node.getGrid().getCache(IStorageGrid.class);
                if (storage == null) {
                    // Keep the change for the next tick instead of dropping it -- see PENDING. Nothing is
                    // recomputed on the retry, so this cannot turn into per-tick work on a big cell.
                    defer(provider, entry.getValue());
                    continue;
                }
                IActionSource source = new MachineSource(host);
                List<IStorageChannel<?>> notifyFlags = NOTIFY_ONLY.get(provider);
                List<IStorageChannel<?>> globalFlags = GLOBAL.get(provider);
                for (IStorageChannel<?> channel : entry.getValue()) {
                    postPendingKeys(storage, provider, channel, source);
                    boolean notifyOnly = notifyFlags != null && notifyFlags.remove(channel);
                    boolean global = globalFlags != null && globalFlags.remove(channel);
                    if (global || notifyOnly) {
                        if (notifyOnly || cooldownElapsed(provider, channel)) {
                            rememberGlobalPass(provider, channel);
                            fullPass(storage, provider, channel, source, notifyOnly);
                        } else {
                            // Held back by the cooldown: put it back so a burst of settings clicks ends in
                            // exactly one pass instead of one per click.
                            markGlobal(provider, channel);
                            if (notifyOnly) {
                                markForFlush(provider, channel, true);
                            }
                        }
                    }
                }
                clearAttempts(provider);
            } catch (RuntimeException | LinkageError failed) {
                // Never let a notification problem escape into the tick loop: the cell itself is fine, the
                // terminals simply keep their previous picture until the next announcement.
                AE2Addon.LOG.warn("[ae2addon] 通知网络库存变化失败：{}", failed.toString());
            }
        }
    }

    /**
     * Posts the accumulated per-key differences for one pair.
     *
     * <p>Each entry is "our contribution to this key moved by this much", which is the one shape AE2's summing
     * inventory can absorb. No network lookup is needed any more: with a sum-safe {@code INFINITE}
     * ({@code Long.MAX_VALUE / 4}) the arithmetic cannot wrap, and a difference is exactly what the aggregate
     * expects -- the previous "read the row and set it to our value" shape existed only to dodge the overflow,
     * and it had the side effect of erasing whatever other cells contributed to the same key.
     */
    private static void postPendingKeys(IStorageGrid storage, ISaveProvider provider, IStorageChannel<?> channel,
            IActionSource source) {
        Map<IAEStack<?>, Long> perPair = PENDING.containsKey(provider) ? PENDING.get(provider).get(channel) : null;
        if (perPair == null || perPair.isEmpty()) {
            return;
        }
        List<IAEStack<?>> out = new ArrayList<>(perPair.size());
        for (Map.Entry<IAEStack<?>, Long> e : perPair.entrySet()) {
            if (e.getValue() == 0L) {
                continue;
            }
            out.add(sized(e.getKey(), e.getValue()));
        }
        if (out.isEmpty()) {
            perPair.clear();
            return;
        }
        try {
            post(storage, channel, out, source);
        } catch (RuntimeException | LinkageError failed) {
            attemptFailed(provider, channel, failed);
            markForFlush(provider, channel);
            return;
        }
        Map<IAEStack<?>, Long> was = announcedFor(provider, channel);
        for (Map.Entry<IAEStack<?>, Long> e : perPair.entrySet()) {
            Long before = was.get(e.getKey());
            long total = (before == null ? 0L : before) + e.getValue();
            was.put(e.getKey(), total);
            logKeyPost(channel, describe(e.getKey()) + " 变化=" + e.getValue() + " 我方累计=" + total);
        }
        perPair.clear();
        clearAttempts(provider, channel);
    }

    /** Channels whose single-key posts have been logged this launch, and how many. */
    private static final Map<String, Integer> KEY_LOGS = new java.util.HashMap<>();

    /** How many single-key posts per channel are written to the log before it goes quiet again. */
    private static final int MAX_KEY_LOGS = 8;

    /**
     * Writes the first few single-key announcements of a channel to the log.
     *
     * <p>Why: the once-per-channel "已通知网络" line above proves the path ran ONCE, and after that a run is
     * blind -- on 2026-10-05 the MeatballCraft check could not tell "we never posted the change" apart from
     * "we posted it and the network ignored it", which is the whole question. It now also carries what the row
     * read before the post, which is what showed the overflow of that same day. Bounded, so a busy run cannot
     * flood the log.
     */
    private static void logKeyPost(IStorageChannel<?> channel, String note) {
        if (AE2Addon.LOG == null) {
            return;
        }
        String name = channel.getClass().getSimpleName();
        int shown = KEY_LOGS.getOrDefault(name, 0);
        if (shown >= MAX_KEY_LOGS) {
            return;
        }
        KEY_LOGS.put(name, shown + 1);
        AE2Addon.LOG.info("[ae2addon] 已通知网络(单键) channel={} {}{}",
                name, note, shown + 1 == MAX_KEY_LOGS ? "（后续同类不再记录）" : "");
    }

    /** A stable, non-localised name for a key, for the log only. */
    private static String describe(IAEStack<?> key) {
        if (key == null) {
            return "?";
        }
        try {
            net.minecraft.item.ItemStack stack = key.asItemStackRepresentation();
            if (stack != null && !stack.isEmpty() && stack.getItem().getRegistryName() != null) {
                return stack.getItem().getRegistryName().toString();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Logging must never be able to break an announcement.
        }
        return String.valueOf(key);
    }

    /** Posts one alteration and logs the once-per-channel evidence line. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void post(IStorageGrid storage, IStorageChannel<?> channel, List<IAEStack<?>> delta,
            IActionSource source) {
        if (delta.isEmpty()) {
            return;
        }
        storage.postAlterationOfStoredItems((IStorageChannel) channel, delta, source);
        if (AE2Addon.LOG != null && ANNOUNCED_CHANNELS.add(channel.getClass().getSimpleName())) {
            // One line per channel per launch: proves the whole path ran (grid found, keys asked from the cell,
            // difference posted). Enough to tell "nothing was announced" apart from "announced but the terminal
            // ignored it" without a second pack run.
            AE2Addon.LOG.info("[ae2addon] 已通知网络 channel={} 变化条目={}（库存变化）",
                    channel.getClass().getSimpleName(), delta.size());
        }
    }

    /** Keeps a pair that could not be posted yet, and gives up loudly after {@link #MAX_ATTEMPTS}. */
    private static void attemptFailed(ISaveProvider provider, IStorageChannel<?> channel, Throwable failed) {
        Map<IStorageChannel<?>, Integer> perProvider = ATTEMPTS.get(provider);
        if (perProvider == null) {
            perProvider = new LinkedHashMap<>();
            ATTEMPTS.put(provider, perProvider);
        }
        int attempts = perProvider.getOrDefault(channel, 0) + 1;
        perProvider.put(channel, attempts);
        if (attempts > MAX_ATTEMPTS) {
            AE2Addon.LOG.warn("[ae2addon] 通道 {} 的通知一直发不出去，已放弃（{} 次）：{}",
                    channel.getClass().getSimpleName(), MAX_ATTEMPTS, failed.toString());
            perProvider.remove(channel);
            Map<IAEStack<?>, Long> perPair = PENDING.containsKey(provider) ? PENDING.get(provider).get(channel)
                    : null;
            if (perPair != null) {
                perPair.clear();
            }
        }
    }

    /** Keeps a pair that has no grid yet for the next tick. */
    private static void defer(ISaveProvider provider, List<IStorageChannel<?>> channels) {
        Map<IStorageChannel<?>, Integer> perProvider = ATTEMPTS.get(provider);
        if (perProvider == null) {
            if (ATTEMPTS.size() >= MAX_PROVIDERS) {
                return;
            }
            perProvider = new LinkedHashMap<>();
            ATTEMPTS.put(provider, perProvider);
        }
        for (IStorageChannel<?> channel : channels) {
            int attempts = perProvider.getOrDefault(channel, 0) + 1;
            perProvider.put(channel, attempts);
            if (attempts <= MAX_ATTEMPTS) {
                markForFlush(provider, channel);
            } else if (AE2Addon.LOG != null && attempts == MAX_ATTEMPTS + 1) {
                AE2Addon.LOG.warn("[ae2addon] 通道 {} 的通知一直找不到网格，已放弃（{} 次）",
                        channel.getClass().getSimpleName(), MAX_ATTEMPTS);
                perProvider.remove(channel);
            }
        }
    }

    /** Forgets a pair entirely (no host, no point in retrying). */
    private static void drop(ISaveProvider provider, List<IStorageChannel<?>> channels) {
        for (IStorageChannel<?> channel : channels) {
            Map<IAEStack<?>, Long> perPair = PENDING.containsKey(provider) ? PENDING.get(provider).get(channel)
                    : null;
            if (perPair != null) {
                perPair.clear();
            }
            List<IStorageChannel<?>> flags = GLOBAL.get(provider);
            if (flags != null) {
                flags.remove(channel);
            }
        }
    }

    private static void clearAttempts(ISaveProvider provider) {
        Map<IStorageChannel<?>, Integer> perProvider = ATTEMPTS.get(provider);
        if (perProvider != null && perProvider.isEmpty()) {
            ATTEMPTS.remove(provider);
        }
    }

    private static void clearAttempts(ISaveProvider provider, IStorageChannel<?> channel) {
        Map<IStorageChannel<?>, Integer> perProvider = ATTEMPTS.get(provider);
        if (perProvider != null) {
            perProvider.remove(channel);
        }
    }

    /** Whether the pair may run another full pass now (settings bursts must not cause a listing storm). */
    private static boolean cooldownElapsed(ISaveProvider provider, IStorageChannel<?> channel) {
        Map<IStorageChannel<?>, Long> perProvider = LAST_GLOBAL.get(provider);
        Long last = perProvider == null ? null : perProvider.get(channel);
        return last == null || tick - last >= GLOBAL_COOLDOWN;
    }

    private static void rememberGlobalPass(ISaveProvider provider, IStorageChannel<?> channel) {
        Map<IStorageChannel<?>, Long> perProvider = LAST_GLOBAL.get(provider);
        if (perProvider == null) {
            if (LAST_GLOBAL.size() >= MAX_PROVIDERS) {
                return;
            }
            perProvider = new LinkedHashMap<>();
            LAST_GLOBAL.put(provider, perProvider);
        }
        perProvider.put(channel, tick);
    }

    /**
     * The full pass: list what the bound cells report right now and difference it against what we announced
     * before (never against the whole network -- the network's row also holds other cells' amounts).
     *
     * <p>This is the expensive path (it lists the cells), so it is used only for changes that cannot name their
     * keys. Item movement uses the per-key path.
     *
     * <p>The difference is posted in the direction the network can add: keys we list that it does not have yet
     * are sent whole, keys we no longer list are taken back, and unchanged keys are sent with size 0 when the
     * pass exists because a terminal just started listening (those terminals learn keys only from a
     * notification and read the amount from the monitor themselves).
     *
     * <p>This pass keeps the shape sensei validated on 2026-10-04 (commit 9845835), including asking the
     * network what it holds ({@code mine - theirs}). It is deliberately NOT rewritten to a "difference against
     * our own last announcement" here: after a grid rebuild the network re-reads the cells, so our own record
     * would be stale and the full amount would be added twice -- the "mana reads 2x" bug of that same day.
     * The trade-off of {@code mine - theirs} (it makes the row read exactly what our cell reports, so another
     * cell's share of the same key is not added) is confined to settings changes; plain item movement no longer
     * comes here at all.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void fullPass(IStorageGrid storage, ISaveProvider provider, IStorageChannel<?> channel,
            IActionSource source, boolean notifyOnly) {
        Map<IAEStack<?>, Long> now = keysNow(provider, channel);
        Map<IAEStack<?>, Long> was = announcedFor(provider, channel);
        appeng.api.storage.data.IItemList known = null;
        try {
            appeng.api.storage.IMEMonitor monitor = storage.getInventory((IStorageChannel) channel);
            if (monitor != null) {
                known = monitor.getStorageList();
            }
        } catch (RuntimeException | LinkageError ignored) {
            known = null; // no monitor yet (grid still building): then everything we list counts as new
        }
        List<IAEStack<?>> out = new ArrayList<>();
        for (Map.Entry<IAEStack<?>, Long> entry : now.entrySet()) {
            long mine = entry.getValue();
            long theirs = 0L;
            if (known != null) {
                IAEStack<?> there = known.findPrecise(entry.getKey());
                theirs = there == null ? 0L : there.getStackSize();
            }
            long diff = mine - theirs;
            if (diff != 0L) {
                out.add(sized(entry.getKey(), diff));
            } else if (notifyOnly && (!pullsItself(channel) || was.containsKey(entry.getKey()))) {
                // A pure notification, so the terminal learns the key without the network counting stock twice.
                // On the ITEM channel only keys we already announced are re-sent: mode 3 lists tens of thousands
                // of rows there (35,740 in sensei's MeatballCraft pack, whose diag run cost 28 s of server time),
                // and AE2's own terminal pulls that channel anyway -- this pass exists for the few rows our cell
                // is the only source of.
                out.add(sized(entry.getKey(), 0L));
            }
        }
        for (Map.Entry<IAEStack<?>, Long> entry : was.entrySet()) {
            if (!now.containsKey(entry.getKey()) && entry.getValue() != 0L) {
                out.add(sized(entry.getKey(), -entry.getValue()));
            }
        }
        try {
            post(storage, channel, out, source);
        } catch (RuntimeException | LinkageError failed) {
            AE2Addon.LOG.warn("[ae2addon] 全量播报失败：{}", failed.toString());
            return;
        }
        was.clear();
        was.putAll(now);
    }

    /** The remembered announcement sizes for one (provider, channel). Caller holds the lock. */
    private static Map<IAEStack<?>, Long> announcedFor(ISaveProvider provider, IStorageChannel<?> channel) {
        Map<IStorageChannel<?>, Map<IAEStack<?>, Long>> perProvider = ANNOUNCED.get(provider);
        if (perProvider == null) {
            if (ANNOUNCED.size() >= MAX_PROVIDERS) {
                return new LinkedHashMap<>();
            }
            perProvider = new LinkedHashMap<>();
            ANNOUNCED.put(provider, perProvider);
        }
        Map<IAEStack<?>, Long> perChannel = perProvider.get(channel);
        if (perChannel == null) {
            perChannel = new LinkedHashMap<>();
            perProvider.put(channel, perChannel);
        }
        return perChannel;
    }

    /**
     * What the bound cells report for a channel RIGHT NOW. Asking the cell (through AE2's cell registry, the
     * same call a drive makes) is what makes mode switches work: the keys are a property of the current mode,
     * not of the moment the drive happened to bind us.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<IAEStack<?>, Long> keysNow(ISaveProvider provider, IStorageChannel<?> channel) {
        Map<IAEStack<?>, Long> keys = new LinkedHashMap<>();
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
                    IAEStack<?> key = (IAEStack<?>) element;
                    Long already = keys.get(key);
                    keys.put(key, (already == null ? 0L : already) + key.getStackSize());
                }
            } catch (RuntimeException | LinkageError failed) {
                // A cell that cannot be asked right now simply contributes nothing this round. Note by
                // experience (2026-10-04): a silent catch here made four different failures look identical --
                // if this ever needs debugging again, log the exception instead of swallowing it.
                AE2Addon.LOG.warn("[ae2addon] 播报前向元件取键失败：{}", failed.toString());
            }
        }
        return keys;
    }

    /** A copy of the key carrying the given amount (never the shared key object itself). */
    private static IAEStack<?> sized(IAEStack<?> key, long size) {
        IAEStack<?> copy = key.copy();
        copy.setStackSize(size);
        return copy;
    }

    /** True when this channel is served by AE2's own terminal, which pulls the list instead of waiting. */
    public static boolean pullsItself(IStorageChannel<?> channel) {
        return channel != null && channel == AEApi.instance().storage()
                .getStorageChannel(appeng.api.storage.channels.IItemStorageChannel.class);
    }
}
