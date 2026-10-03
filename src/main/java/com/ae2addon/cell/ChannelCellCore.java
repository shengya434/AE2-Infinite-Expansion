package com.ae2addon.cell;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.ae2addon.AE2Addon;
import com.ae2addon.data.CellWorldData;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.util.Platform;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/**
 * P2: three operating modes on top of the BigInteger storage engine.
 *
 * <p>Mode matrix (ported from the 1.20.1 line, which was read line by line -- see
 * {@code md/p2-modes-and-persistence-spec.md}):
 * <ul>
 * <li><b>mode 1</b> 无限制存储: everything goes into {@code s1}, no threshold logic.</li>
 * <li><b>mode 2</b> 自定义无限, driven by {@code workMode}:
 *   <ul>
 *   <li>wm 1 阈值: accumulate into {@code s2}; once the total reaches the item's threshold it is
 *       promoted to infinite -- the real total moves into {@code ca} (committed), the key joins
 *       {@code ul}, and leaves {@code s2}.</li>
 *   <li>wm 2 存入无限: storing anything at all promotes it immediately (same three-step promotion).</li>
 *   <li>wm 3 臻藏: only whitelisted / already-infinite items are claimed as infinite; everything else
 *       is ordinary finite storage in {@code s2}.</li>
 *   </ul>
 * </li>
 * <li><b>mode 3</b> 全类型无限: any item can be extracted up to {@link #INFINITE}; nothing is counted.</li>
 * </ul>
 *
 * <p>The promotion is one atomic three-step action on purpose: 1.20 once recorded only part of it and
 * "cancel infinity" then had nothing to give back.
 *
 * <p>{@code ca} is a BigInteger, never a long: the 1.20 line originally clamped the committed amount to
 * a long on write and truncated real quantities to 9.22e18 on the very first store.
 */
public class ChannelCellCore<T extends IAEStack<T>> implements ICellInventory<T> {

    /** Item NBT schema version. v1 = P1 (everything in the item), v2 = P2 (data in world saved data). */
    public static final int FORMAT_VERSION = 2;
    public static final long DEFAULT_THRESHOLD = 65536L;

    /** Amount reported for an "infinite" item (1.20 exposes this as config infiniteItemAmount). */
    public static volatile long INFINITE = Long.MAX_VALUE;
    /** Byte figure shown for infinite items in the panel (1.20: config cellDisplayBytes). */
    public static volatile long INFINITE_BYTES = 300_000_000L;

    private static final String NBT_ROOT = "ae2addon:cell";
    private static final String NBT_VERSION = "v";
    private static final String NBT_ITEMS = "items";   // v1 payload
    private static final String NBT_KEY = "key";
    private static final String NBT_COUNT = "count";
    private static final String NBT_MODE = "mode";
    private static final String NBT_WORKMODE = "wm";
    private static final String NBT_THR = "thr";
    private static final String NBT_UUID = "uuid";
    // Summary counters for the item tooltip. Tag names deliberately match the 1.20 line.
    private static final String NBT_SUMMARY_BYTES = "_b2";        // byte[]: BigInteger, exceeds long
    private static final String NBT_SUMMARY_BYTES_COMPAT = "_b";  // long, clamped (older readers)
    private static final String NBT_SUMMARY_TYPES = "_t";

    private final ItemStack cellStack;
    private final ISaveProvider container;
    private final IStorageChannel<T> channel;

    /**
     * The channel adapter: the ONLY place the judgement layer asks channel-specific questions (how to read a
     * key from NBT, what the whole registry holds, namespace, extra data, ore names, display name).
     *
     * <p>P4 step 1 (2026-10-03): this class still speaks {@code T} everywhere, but every
     * item-specific call now goes through the adapter, so making it generic over {@code IAEStack<T>} later is
     * a mechanical change instead of a rewrite. The adapter interface is already generic.
     */
    private final CellChannel<T> cellChannel;

    private int mode = 1;
    private int workMode = 1;
    private long threshold = DEFAULT_THRESHOLD;
    private UUID uuid;

    /**
     * Shared with {@link CellWorldData} on purpose: two inventories opened for the same cell (drive
     * slot, player hand) must see one consistent picture. Null-safe: when there is no server world we
     * keep a detached instance so the cell still reads as empty rather than crashing.
     */
    private CellStorageData data = new CellStorageData();

    private final IItemHandler configInventory = new ItemStackHandler(0);
    private final IItemHandler upgradesInventory = new ItemStackHandler(0);

    public ChannelCellCore(ItemStack cellStack, ISaveProvider container,
            CellChannel<T> cellChannel) {
        this.cellStack = cellStack;
        this.container = container;
        this.cellChannel = cellChannel;
        this.channel = cellChannel.storage();
        NBTTagCompound v1Payload = loadSmallFields();
        attachStorage(v1Payload);
    }


    /**
     * This channel's count maps. "Storage separate, settings shared" (sensei 2026-10-03): the three counts
     * live per channel, everything else on {@link CellStorageData} is one shared set of settings.
     */
    private CellStorageData.Stocks stocks() {
        return data.stocks(cellChannel.id());
    }
    // ------------------------------------------------------------------
    // load / save
    // ------------------------------------------------------------------

    /** @return the v1 payload compound when the cell still carries P1-format data, else null. */
    private NBTTagCompound loadSmallFields() {
        NBTTagCompound stackTag = cellStack.getTagCompound();
        NBTTagCompound root = stackTag != null && stackTag.hasKey(NBT_ROOT, 10)
                ? stackTag.getCompoundTag(NBT_ROOT) : new NBTTagCompound();

        int version = root.getInteger(NBT_VERSION);
        mode = clampToRange(root.getInteger(NBT_MODE), 1, 3, 1);
        workMode = clampToRange(root.getInteger(NBT_WORKMODE), 1, 3, 1);
        threshold = root.getLong(NBT_THR);
        if (threshold <= 0) {
            threshold = DEFAULT_THRESHOLD;
        }

        String rawUuid = root.getString(NBT_UUID);
        if (!rawUuid.isEmpty()) {
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (RuntimeException ignored) {
                uuid = null;
            }
        }
        return version < FORMAT_VERSION && root.hasKey(NBT_ITEMS, 9) ? root : null;
    }

    private void attachStorage(NBTTagCompound v1Payload) {
        CellWorldData worldData = CellWorldData.get();
        if (worldData == null) {
            // Client side, or no world yet. Never mutate in this state; reads simply come up empty
            // (the terminal gets its numbers from the server anyway).
            if (v1Payload != null) {
                migrateV1(v1Payload, data);
                writeSmallFields();
            }
            return;
        }

        boolean freshUuid = uuid == null;
        if (freshUuid) {
            uuid = UUID.randomUUID();
        }
        data = worldData.getOrCreate(uuid);

        if (v1Payload != null) {
            int migrated = migrateV1(v1Payload, data);
            worldData.markDirty();
            AE2Addon.LOG.info("[ae2addon] 元件数据从 v1 迁到 v2：{} 条", migrated);
        }

        // Only touch the item NBT when something actually changed: a healthy v2 cell must not be
        // rewritten (and re-synced) every time the drive builds its handler list.
        if (freshUuid || v1Payload != null) {
            writeSmallFields();
        }
    }

    private int migrateV1(NBTTagCompound root, CellStorageData target) {
        NBTTagList items = root.getTagList(NBT_ITEMS, 10);
        int moved = 0;
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound entry = items.getCompoundTagAt(i);
            try {
                if (!entry.hasKey(NBT_KEY, 10)) {
                    continue;
                }
                T key = cellChannel.readNbt(entry.getCompoundTag(NBT_KEY));
                if (key == null) {
                    continue;
                }
                BigInteger count = new BigInteger(entry.getString(NBT_COUNT));
                if (count.signum() <= 0) {
                    continue;
                }
                key.setStackSize(1);
                target.stocks(cellChannel.id()).s1.merge(key, count, BigInteger::add);
                moved++;
            } catch (RuntimeException ex) {
                AE2Addon.LOG.warn("[ae2addon] v1 数据里有一条读不出来，已跳过: {}", ex.toString());
            }
        }
        root.removeTag(NBT_ITEMS);
        return moved;
    }

    private void writeSmallFields() {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger(NBT_VERSION, FORMAT_VERSION);
        root.setInteger(NBT_MODE, mode);
        root.setInteger(NBT_WORKMODE, workMode);
        root.setLong(NBT_THR, threshold);
        root.setString(NBT_UUID, uuid == null ? "" : uuid.toString());
        // Small summary counters, so the item tooltip can show bytes/types without the client needing the
        // world save (cell data lives in CellWorldData, keyed by uuid, and is not available client-side).
        // Tag names match the 1.20 line ("_b2" byte array for BigInteger bytes, "_b" long compat, "_t" types).
        BigInteger summaryBytes = summaryBytes();
        root.setByteArray(NBT_SUMMARY_BYTES, summaryBytes.toByteArray());
        root.setLong(NBT_SUMMARY_BYTES_COMPAT, clamp(summaryBytes));
        root.setInteger(NBT_SUMMARY_TYPES, (int) getStoredItemTypes());
        // openNbtData creates and attaches the compound when the stack has none.
        Platform.openNbtData(cellStack).setTag(NBT_ROOT, root);
    }

    /**
     * Bytes the cell reports in its tooltip: the finite amounts plus {@code INFINITE_BYTES} per infinite
     * type -- the same accounting the 1.20 line uses in {@code updateSummary()}, so both versions show the
     * same number for the same content.
     */
    private BigInteger summaryBytes() {
        BigInteger bytes = BigInteger.ZERO;
        if (mode == 1) {
            for (BigInteger amount : stocks().s1.values()) {
                bytes = bytes.add(amount);
            }
            return bytes;
        }
        if (mode != 2) {
            return bytes; // mode 3 reports infinity in the tooltip, no accounting needed
        }
        for (BigInteger amount : stocks().s2.values()) {
            bytes = bytes.add(amount);
        }
        int infiniteTypes = getInfiniteTypeCount();
        if (infiniteTypes > 0) {
            bytes = bytes.add(BigInteger.valueOf(infiniteTypes)
                    .multiply(BigInteger.valueOf(INFINITE_BYTES)));
        }
        return bytes;
    }

    /**
     * Distinct keys the cell can hand out as infinite in mode 2: the whitelist (wl), the promoted/infinite
     * set (ul) and the live rule matches.
     *
     * <p>{@code ca} is deliberately NOT a source here. It records how much an infinite key absorbed, so that
     * cancelling the infinite state can hand it back -- it is not itself an infinite claim. Every legitimate
     * infinite state that writes ca also writes ul (promotion, 存入无限, panel toggle) or matches a live rule
     * (rule hits write touched), so nothing is lost by leaving ca out. Listing it was 2026-10-03 bug 2:
     * delete the rule and the item stayed "infinite" in the terminal, while extraction fell through to the
     * finite store and returned nothing.
     */
    private Set<T> infiniteKeys() {
        Set<T> infinite = mine(data.wl);
        infinite.addAll(mine(data.ul));
        infinite.addAll(ruleInfiniteKeys());
        return infinite;
    }

    /**
     * This channel's keys out of one of the SHARED per-key collections.
     *
     * <p>sensei's layout (2026-10-03): only the three counts are per channel, so these collections hold
     * keys of every channel at once. A key knows its own channel, so every reader filters -- otherwise the
     * item view would list fluid keys and vice versa.
     */
    private Set<T> mine(Set<IAEStack<?>> source) {
        Set<T> out = new LinkedHashSet<>();
        for (IAEStack<?> key : source) {
            if (isMine(key)) {
                out.add(castKey(key));
            }
        }
        return out;
    }

    /** Whether a shared-collection key belongs to this cell inventory's channel. */
    private boolean isMine(IAEStack<?> key) {
        return java.util.Objects.equals(key.getChannel(), channel);
    }

    /** Distinct keys the cell can hand out as infinite in mode 2 (wl u ul u live rule matches). */
    private int getInfiniteTypeCount() {
        return infiniteKeys().size();
    }

    /** Raw values for the item tooltip. The caller localises, so this class stays client-free. */
    public static final class Summary {
        public final int mode;
        public final int workMode;
        public final long threshold;
        public final int types;
        public final BigInteger bytes;

        Summary(int mode, int workMode, long threshold, int types, BigInteger bytes) {
            this.mode = mode;
            this.workMode = workMode;
            this.threshold = threshold;
            this.types = types;
            this.bytes = bytes;
        }
    }

    /** Reads the summary counters straight off the stack, so a client tooltip needs no world data. */
    public static Summary summaryOf(ItemStack stack) {
        NBTTagCompound stackTag = stack == null ? null : stack.getTagCompound();
        if (stackTag == null || !stackTag.hasKey(NBT_ROOT, 10)) {
            return new Summary(1, 1, DEFAULT_THRESHOLD, 0, BigInteger.ZERO);
        }
        NBTTagCompound root = stackTag.getCompoundTag(NBT_ROOT);
        int mode = root.getInteger(NBT_MODE);
        if (mode < 1 || mode > 3) {
            mode = 1;
        }
        int workMode = root.getInteger(NBT_WORKMODE);
        long threshold = root.getLong(NBT_THR);
        int types = root.getInteger(NBT_SUMMARY_TYPES);
        BigInteger bytes = root.hasKey(NBT_SUMMARY_BYTES, 7)
                ? new BigInteger(root.getByteArray(NBT_SUMMARY_BYTES))
                : BigInteger.valueOf(root.getLong(NBT_SUMMARY_BYTES_COMPAT));
        return new Summary(mode, workMode, threshold, types, bytes);
    }

    private void saveChanges() {
        writeSmallFields();
        CellWorldData worldData = CellWorldData.get();
        if (worldData == null) {
            AE2Addon.LOG.warn("[ae2addon] 没有可用的世界存档，元件改动只留在内存里（uuid={}）", uuid);
            return;
        }
        worldData.put(uuid, data);
        if (container != null) {
            container.saveChanges(this);
        }
    }

    @Override
    public void persist() {
        saveChanges();
    }

    // ------------------------------------------------------------------
    // judgement layer
    // ------------------------------------------------------------------

    @Override
    public T injectItems(T input, Actionable action, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) {
            return null;
        }
        T key = lookupKey(input);
        if (isRejectingKeyed(key)) {
            // Rejected -> hand the whole input back untouched (in rv6 the "remainder" *is* the input).
            // Same answer for SIMULATE and MODULATE: the reject list is a filter, not an action.
            return input;
        }
        BigInteger amount = BigInteger.valueOf(input.getStackSize());

        // The shape is kept deliberately: decide first, then mutate (rv6 reports the same remainder
        // for SIMULATE and MODULATE; see the P1 spec, section 3.4).
        boolean accepted = insertInternal(key, amount, action == Actionable.MODULATE);
        if (!accepted) {
            return input;
        }
        return null;
    }

    /**
     * @param mutate false for SIMULATE: the decision must be identical, but nothing may change.
     * @return whether the amount is accepted.
     */
    private boolean insertInternal(T key, BigInteger amount, boolean mutate) {
        // Quantity-locked entries swallow everything without recording it (1.20 L366/L375/L392).
        if (mode == 2 && data.qtyLocked.contains(key)) {
            return true;
        }

        if (mode == 3) {
            if (mutate) {
                data.m3.add(key);
            }
            return true;
        }

        if (mode == 2) {
            // Batch rule hit -> infinite right away. Blacklisted keys are excluded, and NBT variants
            // never match a rule: 1.20 L1028 records that otherwise the stack is swallowed by the
            // "accept without recording" path and the original item simply vanishes.
            if (!data.blacklist.contains(key) && matchesRule(key)) {
                if (mutate) {
                    data.touched.add(key);
                    // Fold any finite amount into the committed total so the same key is not reported
                    // twice (1.20 L430-437).
                    BigInteger existing = stocks().s2.remove(key);
                    if (existing != null && existing.signum() > 0) {
                        stocks().ca.merge(key, existing, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                    }
                    stocks().ca.merge(key, amount, BigInteger::add);
                    saveChanges();
                }
                return true;
            }
            if (workMode == 2 && !data.blacklist.contains(key)) {
                // 存入无限: storing anything at all makes it infinite.
                if (mutate) {
                    stocks().ca.merge(key, amount, BigInteger::add);
                    data.ul.add(key);
                }
                return true;
            }
            if (workMode == 3 && (data.wl.contains(key) || data.ul.contains(key))) {
                // 臻藏: already infinite, nothing to account for.
                return true;
            }
        }

        if (!mutate) {
            return true;
        }

        Map<IAEStack<?>, BigInteger> map = mode == 1 ? stocks().s1 : stocks().s2;
        BigInteger total = map.merge(key, amount, BigInteger::add);

        // Blacklisted keys may be stored, but never promoted (1.20 L465-467).
        if (mode == 2 && workMode == 1 && !data.blacklist.contains(key)
                && total.compareTo(BigInteger.valueOf(thresholdFor(key))) >= 0) {
            // Promotion, three steps, atomically: real total -> committed, join the infinite set,
            // leave the finite store.
            stocks().ca.put(key, total);
            data.ul.add(key);
            map.remove(key);
        }

        saveChanges();
        return true;
    }

    @Override
    public T extractItems(T input, Actionable action, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) {
            return null;
        }
        T key = lookupKey(input);
        long requested = input.getStackSize();
        boolean mutate = action == Actionable.MODULATE;

        BigInteger taken;
        if (mode == 2 && data.qtyLocked.contains(key)) {
            // Locked: never hand out more than what is recorded, and do not change the record
            // (1.20 L498-500 -- the pinned amount behaves the same in both directions).
            taken = extractFromMap(stocks().s2, key, requested, false);
        } else if (mode == 3) {
            if (mutate) {
                data.m3.add(key);
            }
            taken = BigInteger.valueOf(Math.min(requested, INFINITE));
        } else if (mode == 2 && (ruleActive(key) || data.ul.contains(key) || data.wl.contains(key))) {
            taken = BigInteger.valueOf(Math.min(requested, INFINITE));
        } else {
            taken = extractFromMap(mode == 1 ? stocks().s1 : stocks().s2, key, requested, mutate);
        }

        if (taken.signum() <= 0) {
            // Nothing extracted -> null (0 would make callers think they got something).
            return null;
        }
        if (mutate) {
            saveChanges();
        }
        T result = input.copy();
        result.setStackSize(clamp(taken));
        return result;
    }

    private BigInteger extractFromMap(Map<IAEStack<?>, BigInteger> map, IAEStack<?> key, long requested,
            boolean mutate) {
        BigInteger available = map.get(key);
        if (available == null || available.signum() <= 0) {
            return BigInteger.ZERO;
        }
        long extractable = Math.min(Math.max(requested, 0L), clamp(available));
        if (extractable <= 0) {
            return BigInteger.ZERO;
        }
        if (mutate) {
            BigInteger remaining = available.subtract(BigInteger.valueOf(extractable));
            if (remaining.signum() <= 0) {
                map.remove(key);
            } else {
                map.put(key, remaining);
            }
        }
        return BigInteger.valueOf(extractable);
    }

    @Override
    public IItemList<T> getAvailableItems(IItemList<T> out) {
        switch (mode) {
            case 3: {
                // 1.20 parity: mode 3 means "every registered item is infinite", so every registered item has
                // to be listed -- otherwise you cannot see (or pull) what you never stored. sensei asked for
                // this on 2026-10-02, conditional on it not being laggy, so the self-test's H group measures
                // the cost instead of guessing. The scan is cached forever (the registry is frozen once the
                // game has loaded) and reporting uses reportInfinite (set-max, never accumulate), so what a
                // grid rebuild actually pays is one walk over the cached key list.
                reportInfinite(out, allRegistryKeys());
                reportInfinite(out, mine(data.m3)); // NBT variants we have seen, not nameable by the scan
                break;
            }
            case 2: {
                // EVERYTHING that can hand items out has to be listed here, or the terminal shows nothing
                // even though extraction works. That covers three separate sources, and missing any of them
                // makes items invisible in the terminal:
                //   * wl  -- the whitelist, which is what the panel's "mark infinite" and a JEI drag add to
                //   * ul  -- the infinite set written by promotion and by togglePanelInfinite
                //   * the rules themselves -- an Instant rule must show up before anything is ever stored,
                //     and a Touch rule has to show the item it took in (touched)
                // (Bugs found by sensei 2026-10-02: first wl was missing; then rule matches were, which made
                // "Instant" indistinguishable from "Touch" from the player's side. 2026-10-03: ca is NOT a
                // source -- it is only the absorbed-amount record, and listing it made a rule-released item
                // show as infinite while extraction returned nothing.)
                Set<T> infinite = infiniteKeys();
                reportInfinite(out, infinite);
                // Finite rows must not land on top of a key that is already infinite -- in this cell or in
                // another cell of the same network. reportMap decides that per entry via findPrecise, the
                // identity IItemList.add itself uses, because the list SUMS and INFINITE + n wraps negative
                // (sensei's "the item vanished from the terminal", 2026-10-03).
                reportMap(out, stocks().s2);
                break;
            }
            default:
                reportMap(out, stocks().s1);
                break;
        }
        return out;
    }

    /**
     * Reports keys as infinite WITHOUT accumulating.
     *
     * <p>rv6's {@code IItemList.add} merges by summing sizes, so when two unlimited cells report
     * {@code Long.MAX_VALUE} into the same grid list the result overflows to a negative number -- the
     * terminal then shows negative stock and extraction fails. The 1.20 line hit exactly this and fixed it
     * by taking the maximum instead of adding. rv6 has no {@code set}, so we mutate the entry that
     * {@code findPrecise} hands back.
     */
    private static <S extends IAEStack<S>> void reportInfinite(IItemList<S> out, Iterable<S> keys) {
        for (S key : keys) {
            S existing = out.findPrecise(key);
            if (existing == null) {
                S stack = key.copy();
                stack.setStackSize(INFINITE);
                out.add(stack);
            } else if (existing.getStackSize() < INFINITE) {
                existing.setStackSize(INFINITE);
            }
        }
    }

    /**
     * Reports finite amounts, never ON TOP of an entry that is already infinite.
     *
     * <p>{@code IItemList.add} SUMS (rv6: {@code AEItemStack.add -> incStackSize}), so adding a finite
     * amount to an {@code INFINITE} row wraps around to a negative size -- and a negative row is simply
     * not there as far as the terminal is concerned. That is 2026-10-03 bug 1 ("the item disappears from
     * the list"), and it happens whenever two reports for one key meet in one list: two cells of the same
     * network, one claiming infinity by rule and the other still holding the item as ordinary storage.
     *
     * <p>So the decision is made with {@code findPrecise} -- the very identity {@code ItemList.add} uses to
     * find its record -- instead of a Set lookup, which can disagree with it and let the add through.
     */
    private static <S extends IAEStack<S>> void reportMap(IItemList<S> out, Map<IAEStack<?>, BigInteger> map) {
        for (Map.Entry<IAEStack<?>, BigInteger> entry : map.entrySet()) {
            long size = clamp(entry.getValue());
            if (size <= 0) {
                continue;
            }
            S key = castKey(entry.getKey());
            S existing = out.findPrecise(key);
            if (existing != null && existing.getStackSize() >= INFINITE) {
                continue;
            }
            S stack = key.copy();
            stack.setStackSize(size);
            out.add(stack);
        }
    }

    /**
     * A channel's own store only ever holds keys of that channel, so this cast is guaranteed by the data --
     * the store is reached through {@code data.stocks(cellChannel.id())}.
     */
    @SuppressWarnings("unchecked")
    private static <S extends IAEStack<S>> S castKey(IAEStack<?> key) {
        return (S) key;
    }

    @Override
    public IStorageChannel<T> getChannel() {
        return channel;
    }

    private T lookupKey(T input) {
        // Identity ignores size (AEItemStack compares the shared stack, count forced to 1), but
        // normalising keeps stored keys uniform.
        T key = input.copy();
        key.setStackSize(1);
        return key;
    }



    private static long clamp(BigInteger value) {
        if (value == null || value.signum() <= 0) {
            return 0L;
        }
        if (value.bitLength() > 63) {
            return Long.MAX_VALUE;
        }
        return value.longValueExact();
    }

    private static int clampToRange(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    // ------------------------------------------------------------------
    // settings (driven by /ae2addoncell until P3 builds the GUI)
    // ------------------------------------------------------------------

    public int getMode() {
        return mode;
    }

    /**
     * Switching the main mode switches to a COMPLETELY ISOLATED storage space -- sensei's original spec for
     * this cell, restated on 2026-10-03: mode 1's pile, mode 2's pile and mode 3 ("nothing is stored,
     * everything is handed out") are three different rooms. Nothing moves between them, so nothing is lost
     * either: switching back finds the previous room exactly as it was left.
     *
     * <p>Before 2026-10-03 this method MERGED the two piles so that a mode switch could not make the other
     * store invisible. That was my own invention and it contradicted the spec -- switching to mode 1 showed
     * mode 2's contents, so the two modes behaved like one shared store, which is exactly what sensei
     * noticed. Removed.
     */
    public void setMode(int newMode) {
        int target = clampToRange(newMode, 1, 3, 1);
        if (target == mode) {
            return;
        }
        mode = target;
        saveChanges();
    }

    public int getWorkMode() {
        return workMode;
    }

    /**
     * Switching work mode promotes everything that already qualifies; it never demotes. Promotion is
     * the same atomic three-step action as on insert. Demotion is a deliberate user action
     * ({@link #cancelInfinite}).
     */
    public void setWorkMode(int newWorkMode) {
        int target = clampToRange(newWorkMode, 1, 3, 1);
        workMode = target;
        if (mode == 2 && target != 3) {
            promoteEligibleS2(target == 2);
        }
        saveChanges();
    }

    private void promoteEligibleS2(boolean promoteAll) {
        java.util.List<IAEStack<?>> keys = new java.util.ArrayList<>(stocks().s2.keySet());
        for (IAEStack<?> raw : keys) {
            T key = castKey(raw);
            BigInteger total = stocks().s2.get(key);
            if (total == null || total.signum() <= 0) {
                continue;
            }
            boolean promote = promoteAll
                    || total.compareTo(BigInteger.valueOf(thresholdFor(key))) >= 0;
            if (promote) {
                stocks().ca.put(key, total);
                data.ul.add(key);
                stocks().s2.remove(key);
            }
        }
    }

    public long getThreshold() {
        return threshold;
    }

    public void setThreshold(long value) {
        threshold = value > 0 ? value : DEFAULT_THRESHOLD;
        saveChanges();
    }

    public long thresholdFor(T key) {
        Long perItem = data.itemThr.get(lookupKey(key));
        return perItem != null && perItem > 0 ? perItem : threshold;
    }

    /** value &lt;= 0 clears the per-item threshold so the global one applies again. */
    public void setItemThreshold(T key, long value) {
        T k = lookupKey(key);
        if (value <= 0) {
            data.itemThr.remove(k);
        } else {
            data.itemThr.put(k, value);
        }
        saveChanges();
    }

    public boolean addWhitelist(T key) {
        boolean changed = data.wl.add(lookupKey(key));
        if (changed) {
            saveChanges();
        }
        return changed;
    }

    public boolean removeWhitelist(T key) {
        boolean changed = data.wl.remove(lookupKey(key));
        if (changed) {
            saveChanges();
        }
        return changed;
    }

    public boolean isWhitelisted(IAEStack<?> key) {
        return data.wl.contains(lookupKey(castKey(key)));
    }

    public boolean isInfinite(T key) {
        return data.ul.contains(lookupKey(key));
    }

    // isQuantityLocked() now lives with the quantity-lock API further down.

    // ------------------------------------------------------------------
    // reject list (three-state, ported from the 1.20 line L762-799)
    // ------------------------------------------------------------------

    /** Reject check on an already-normalised key (hot path: no extra copy). */
    private boolean isRejectingKeyed(T key) {
        if (data.rejectOn.contains(key)) {
            return true;
        }
        if (data.rejectOff.contains(key)) {
            return false;
        }
        return data.rejectDefault;
    }

    /**
     * Three-state: explicitly rejected &gt; explicitly not rejected &gt; the cell's default.
     * Only insertion consults this; extraction is unaffected (1.20 behaves the same), so an item
     * already inside can always be pulled back out.
     */
    public boolean isRejecting(T key) {
        return isRejectingKeyed(lookupKey(key));
    }

    /** Flips the key: anything not currently rejected becomes explicitly rejected, and vice versa. */
    public boolean toggleReject(T key) {
        T k = lookupKey(key);
        boolean reject = !isRejectingKeyed(k);
        data.rejectOn.remove(k);
        data.rejectOff.remove(k);
        if (reject) {
            data.rejectOn.add(k);
        } else {
            data.rejectOff.add(k);
        }
        saveChanges();
        return reject;
    }

    /** Drops both explicit overrides for this key, so it follows the default again. */
    public boolean clearReject(T key) {
        T k = lookupKey(key);
        boolean changed = data.rejectOn.remove(k) | data.rejectOff.remove(k);
        if (changed) {
            saveChanges();
        }
        return changed;
    }

    /** The value every key without an explicit override follows. */
    public boolean toggleRejectDefault() {
        return setRejectDefault(!data.rejectDefault);
    }

    public boolean setRejectDefault(boolean value) {
        if (data.rejectDefault != value) {
            data.rejectDefault = value;
            saveChanges();
        }
        return data.rejectDefault;
    }

    public boolean rejectDefault() {
        return data.rejectDefault;
    }

    public int rejectOnCount() {
        return data.rejectOn.size();
    }

    public int rejectOffCount() {
        return data.rejectOff.size();
    }

    /**
     * Cancel a key's infinity: the committed amount goes back into the finite store so nothing is lost.
     * 1.20 shipped a bug here (it promoted without recording, so cancelling gave back nothing) -- hence
     * the explicit committed map.
     *
     * <p>{@code ca} only ever exists because of a MODE 2 feature (rule hit, threshold promotion, 存入无限, the
     * panel toggle), so its refund belongs to mode 2's room -- {@code s2} -- whatever mode you happen to be
     * standing in. Writing it to "the current mode's store" would drop mode 2's items into mode 1's isolated
     * room when the cancel is issued from there (the command works in every mode), which is exactly the
     * isolation the 2026-10-03 spec correction is about.
     */
    public boolean cancelInfinite(T key) {
        T k = lookupKey(key);
        if (!data.ul.remove(k)) {
            return false;
        }
        BigInteger committed = stocks().ca.remove(k);
        if (committed != null && committed.signum() > 0) {
            stocks().s2.merge(k, committed, BigInteger::add);
        }
        saveChanges();
        return true;
    }

    /**
     * Stored amount for display/tests, read from the CURRENT mode's own store (they are isolated):
     * mode 1 -> s1; mode 2 -> s2 plus the committed amount; mode 3 -> flat zero, because mode 3 stores nothing
     * and hands every item out from nothing.
     */
    public BigInteger storedAmount(T key) {
        T k = lookupKey(key);
        BigInteger total = BigInteger.ZERO;
        if (mode == 1) {
            BigInteger inS1 = stocks().s1.get(k);
            return inS1 == null ? total : inS1;
        }
        if (mode != 2) {
            return total;
        }
        BigInteger inS2 = stocks().s2.get(k);
        BigInteger committed = stocks().ca.get(k);
        if (inS2 != null) {
            total = total.add(inS2);
        }
        if (committed != null) {
            total = total.add(committed);
        }
        return total;
    }

    public String describeSettings() {
        return String.format("mode=%d wm=%d thr=%d wl=%d ul=%d s1=%d s2=%d ca=%d rej=%d/%d@%s uuid=%s",
                mode, workMode, threshold, data.wl.size(), data.ul.size(), stocks().s1.size(), stocks().s2.size(),
                stocks().ca.size(), data.rejectOn.size(), data.rejectOff.size(), data.rejectDefault, uuid);
    }

    public UUID getUuid() {
        return uuid;
    }

    public CellStorageData rawData() {
        return data;
    }

    // ------------------------------------------------------------------
    // batch rules -- 1.12.2 uses OreDictionary where 1.20 used item tags
    // ------------------------------------------------------------------

    public static String ruleKey(boolean isOre, String name) {
        return (isOre ? "ore:" : "mod:") + name;
    }

    public Set<String> getOreRules() {
        return new LinkedHashSet<>(data.oreRules);
    }

    public Set<String> getModRules() {
        return new LinkedHashSet<>(data.modRules);
    }

    public boolean addOreRule(String oreName) {
        if (oreName == null || oreName.trim().isEmpty()) {
            return false;
        }
        String name = oreName.trim();
        if (!data.oreRules.add(name)) {
            return false;
        }
        data.ruleModes.put(ruleKey(true, name), data.ruleInstant ? 1 : 2);
        invalidateInstantMatchCache();
        saveChanges();
        return true;
    }

    public boolean removeOreRule(String oreName) {
        if (oreName == null) {
            return false;
        }
        String name = oreName.trim();
        if (!data.oreRules.remove(name)) {
            return false;
        }
        data.ruleModes.remove(ruleKey(true, name));
        invalidateInstantMatchCache();
        reconcileCommitted();
        saveChanges();
        return true;
    }

    public boolean addModRule(String mod) {
        if (mod == null || mod.trim().isEmpty()) {
            return false;
        }
        String name = mod.trim();
        if (!data.modRules.add(name)) {
            return false;
        }
        data.ruleModes.put(ruleKey(false, name), data.ruleInstant ? 1 : 2);
        invalidateInstantMatchCache();
        saveChanges();
        return true;
    }

    public boolean removeModRule(String mod) {
        if (mod == null) {
            return false;
        }
        String name = mod.trim();
        if (!data.modRules.remove(name)) {
            return false;
        }
        data.ruleModes.remove(ruleKey(false, name));
        invalidateInstantMatchCache();
        reconcileCommitted();
        saveChanges();
        return true;
    }

    /** 1 = Instant, 2 = Touch; anything else is treated as 1 (1.20 L938-940). */
    public int getRuleMode(String key) {
        Integer mode = data.ruleModes.get(key);
        return mode == null ? 1 : mode;
    }

    public void setRuleMode(String key, int mode) {
        if (key == null) {
            return;
        }
        boolean isOre = key.startsWith("ore:");
        boolean isMod = key.startsWith("mod:");
        if (!isOre && !isMod) {
            return;
        }
        String name = key.substring(4);
        if (!(isOre ? data.oreRules : data.modRules).contains(name)) {
            return; // unknown rule: no-op
        }
        int normalized = mode == 2 ? 2 : 1;
        if (getRuleMode(key) == normalized) {
            return;
        }
        data.ruleModes.put(key, normalized);
        invalidateInstantMatchCache();
        reconcileCommitted();
        saveChanges();
    }

    public boolean isRuleInstant() {
        return data.ruleInstant;
    }

    /**
     * Global "make every rule instant" switch.
     *
     * <p>The 1.20 line only flips this flag and never consults it while matching (each rule carries its
     * own mode), which would make the switch a no-op. Here it actually does something: flipping it
     * rewrites every existing rule's mode, which is what the UI toggle implies.
     */
    public void setRuleInstant(boolean instant) {
        if (data.ruleInstant == instant) {
            return;
        }
        data.ruleInstant = instant;
        for (String ore : data.oreRules) {
            data.ruleModes.put(ruleKey(true, ore), instant ? 1 : 2);
        }
        for (String mod : data.modRules) {
            data.ruleModes.put(ruleKey(false, mod), instant ? 1 : 2);
        }
        invalidateInstantMatchCache();
        saveChanges();
    }

    /** NBT variants never participate in rules (1.20 L1023-1028 records why they must not). */
    private boolean matchesRule(T key) {
        if (data.oreRules.isEmpty() && data.modRules.isEmpty()) {
            return false;
        }
        if (cellChannel.hasExtraData(key)) {
            return false;
        }
        String namespace = cellChannel.namespace(key);
        if (namespace != null && data.modRules.contains(namespace)) {
            return true;
        }
        return !cellChannel.matchedOreRules(key, data.oreRules).isEmpty();
    }

    /** Instant rules allow infinity immediately; Touch rules need the key to have been stored. */
    public boolean ruleAllowsInfinite(T key) {
        if (data.oreRules.isEmpty() && data.modRules.isEmpty()) {
            return false;
        }
        T k = lookupKey(key);
        if (cellChannel.hasExtraData(k)) {
            return false;
        }
        boolean matched = false;

        String namespace = cellChannel.namespace(k);
        if (namespace != null && data.modRules.contains(namespace)) {
            if (getRuleMode(ruleKey(false, namespace)) == 1) {
                return true;
            }
            matched = true;
        }

        // Ore rules are item-only by decision (2026-10-03): every other channel's adapter answers with an
        // empty set, so this loop also encodes "no ore names -> no ore rules".
        for (String ore : cellChannel.matchedOreRules(k, data.oreRules)) {
            if (getRuleMode(ruleKey(true, ore)) == 1) {
                return true;
            }
            matched = true;
        }
        return matched && data.touched.contains(k);
    }

    /** Blacklist, quantity lock and "currently in the finite store" all veto a rule (1.20 L1077-1080). */
    private boolean ruleActive(T key) {
        if (data.blacklist.contains(key) || data.qtyLocked.contains(key) || stocks().s2.containsKey(key)) {
            return false;
        }
        return ruleAllowsInfinite(key);
    }

    public boolean isInfiniteByRule(T key) {
        return mode == 2 && ruleActive(lookupKey(key));
    }

    /**
     * Hands orphaned committed amounts back to the finite store.
     *
     * <p>{@code ca} holds what an infinite key absorbed, so cancelling infinity can give it back. It is not
     * itself an infinite claim: a key is infinite only through wl, ul or a rule that is still ACTIVE.
     * Whenever a rule goes away (deleted, or vetoed by a fresh blacklist entry) a key whose only trace was ca
     * would otherwise be listed as infinite while extraction fell through to the finite store and returned
     * nothing -- exactly 2026-10-03 bug 2, where the goods were visible as "infinite" but could not be pulled
     * out. Moving the amount back into the finite store keeps every item and makes listing and extraction
     * agree again.
     *
     * <p>The "still infinite?" test is {@link #ruleActive}, not {@link #ruleAllowsInfinite}: it has to be the
     * same predicate the listing and extraction use, blacklist and quantity lock included, or the amount is
     * stranded by exactly the vetoes this method exists to respect.
     *
     * @return whether anything moved.
     */
    private boolean reconcileCommitted() {
        if (stocks().ca.isEmpty()) {
            return false;
        }
        List<IAEStack<?>> keys = new ArrayList<>(stocks().ca.keySet());
        boolean changed = false;
        for (IAEStack<?> raw : keys) {
            T k = castKey(raw);
            if (data.wl.contains(k) || data.ul.contains(k) || ruleActive(k)) {
                continue;
            }
            BigInteger amount = stocks().ca.remove(k);
            if (amount != null && amount.signum() > 0) {
                // Always mode 2's room: ca is a mode 2 concept (see cancelInfinite), so a rule deleted while
                // the cell sits in mode 1 must not dump mode 2's amount into mode 1's isolated store.
                stocks().s2.merge(k, amount, BigInteger::add);
            }
            changed = true;
        }
        if (changed) {
            saveChanges();
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // rule-match enumeration
    //
    // An Instant rule has to make items infinite BEFORE anyone stores them, which means the cell must be
    // able to name those keys up front -- that is what the 1.20 line does with its cached registry scan.
    // Without this, "Instant" is indistinguishable from "Touch" from the player's point of view: neither
    // rule shows up anywhere until an item has been stored. (sensei hit exactly this on 2026-10-02.)
    // ------------------------------------------------------------------

    /** Cached Instant-rule matches; entries are pure rule hits, storage vetoes are applied at use time. */
    private transient List<T> instantMatchCache = null;
    private transient int instantMatchVersion = -1;
    private transient int ruleStateVersion = 0;

    private void invalidateInstantMatchCache() {
        ruleStateVersion++;
    }

    /** Pure rule test: does any rule matching this key run in Instant mode? Blacklist is applied later. */
    private boolean matchesInstantRule(T key) {
        if (data.oreRules.isEmpty() && data.modRules.isEmpty() || cellChannel.hasExtraData(key)) {
            return false;
        }
        String namespace = cellChannel.namespace(key);
        if (namespace != null && data.modRules.contains(namespace)
                && getRuleMode(ruleKey(false, namespace)) == 1) {
            return true;
        }
        for (String ore : cellChannel.matchedOreRules(key, data.oreRules)) {
            if (getRuleMode(ruleKey(true, ore)) == 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why a rule match must NOT be listed as infinite.
     *
     * <p>The list has to mirror exactly what extraction does, which is {@link #ruleActive}: blacklist,
     * quantity lock and "the key is currently in the finite store" veto a rule on both sides.
     *
     * <p>{@code ca} is deliberately absent. It records a rule hit's absorbed amount, so a Touch rule's own
     * item carries a ca entry -- vetoing on ca made the rule hide the very item it had just taken in, and
     * with ca no longer being listed as a source (2026-10-03 bug 2) that item disappeared from the terminal
     * entirely. wl/ul stay in the list only as harmless deduplication: those keys are listed by
     * {@link #infiniteKeys} anyway.
     */
    private boolean ruleListingVetoed(T key) {
        return data.blacklist.contains(key) || data.qtyLocked.contains(key) || stocks().s2.containsKey(key)
                || data.wl.contains(key) || data.ul.contains(key);
    }

    /**
     * Every key the rules currently hand out as infinite, ready to be listed in the terminal or the panel.
     *
     * <p>Two sources: the cached registry scan (Instant rules -- items that were never stored) and the
     * touched set (Touch rules -- items that were). Storage vetoes (blacklist, quantity lock, "already in
     * the finite store") are applied here rather than baked into the cache, so storing or extracting an item
     * never invalidates the scan.
     */
    private Set<T> ruleInfiniteKeys() {
        Set<T> out = new LinkedHashSet<>();
        if (mode != 2 || (data.oreRules.isEmpty() && data.modRules.isEmpty())) {
            return out;
        }
        for (T key : instantMatchedKeys()) {
            if (!ruleListingVetoed(key) && ruleAllowsInfinite(key)) {
                out.add(key);
            }
        }
        for (T key : mine(data.touched)) {
            if (!ruleListingVetoed(key) && ruleAllowsInfinite(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Registry scan for Instant rules, cached until the rules change. Thousands of ItemStacks, so: once. */
    private List<T> instantMatchedKeys() {
        if (instantMatchCache != null && instantMatchVersion == ruleStateVersion) {
            return instantMatchCache;
        }
        List<T> found = new ArrayList<>();
        if (!data.oreRules.isEmpty() || !data.modRules.isEmpty()) {
            for (T key : allRegistryKeys()) {
                if (matchesInstantRule(key)) {
                    found.add(key);
                }
            }
        }
        instantMatchCache = found;
        instantMatchVersion = ruleStateVersion;
        return found;
    }

    /** Every key this channel can ever hand out (mode 3 listing / Instant-rule scan). Read-only. */
    private List<T> allRegistryKeys() {
        return cellChannel.allKeys();
    }

    /** How many canonical keys this channel's registry yields (for the self-test's report). */
    public int registryKeyCount() {
        return cellChannel.allKeys().size();
    }

    // ------------------------------------------------------------------
    // blacklist / quantity lock
    // ------------------------------------------------------------------

    /** @return whether the key is blacklisted after the toggle. */
    public boolean toggleBlacklist(T key) {
        T k = lookupKey(key);
        if (data.blacklist.remove(k)) {
            saveChanges();
            return false;
        }
        data.blacklist.add(k);
        // A blacklisted key must not keep showing as infinite through a rule.
        data.touched.remove(k);
        // ... and if a rule had already absorbed its amount, that amount is now orphaned: give it back to
        // the finite store so it stays visible and extractable (same class as bug 2).
        reconcileCommitted();
        saveChanges();
        return true;
    }

    public boolean isBlacklisted(T key) {
        return data.blacklist.contains(lookupKey(key));
    }

    public Set<IAEStack<?>> getBlacklist() {
        return new LinkedHashSet<>(data.blacklist);
    }

    /**
     * Pins a key's stored amount; {@code lock} also marks it so inserts/extracts stay capped.
     *
     * <p>Always writes mode 2's store ({@code s2}): this is the mode 2 config screen's "元件内数量" field, and
     * the whole quantity-lock / whitelist / committed family is a mode 2 concept, so it must not be able to
     * write into mode 1's isolated room.
     */
    public void setStoredQuantity(T key, BigInteger quantity, boolean lock) {
        if (key == null || quantity == null) {
            return;
        }
        T k = lookupKey(key);
        data.ul.remove(k);
        data.wl.remove(k);
        stocks().ca.remove(k);
        if (quantity.signum() <= 0) {
            stocks().s2.remove(k);
        } else {
            stocks().s2.put(k, quantity);
        }
        if (lock) {
            data.qtyLocked.add(k);
        } else {
            data.qtyLocked.remove(k);
        }
        saveChanges();
    }

    public boolean isQuantityLocked(T key) {
        return data.qtyLocked.contains(lookupKey(key));
    }

    public Set<IAEStack<?>> quantityLockedKeys() {
        return new LinkedHashSet<>(data.qtyLocked);
    }

    // ------------------------------------------------------------------
    // panel enumeration / infinite toggle / committed amount
    // ------------------------------------------------------------------

    /** One row of the config panel. {@code bytes} stays 0 for finite rows: we do no byte accounting. */
    public static final class PanelItem {
        public final IAEStack<?> key;
        public final BigInteger amount;
        public final boolean isInfinite;
        public final long bytes;
        public final long itemThreshold;
        public final boolean quantityLocked;

        PanelItem(IAEStack<?> key, BigInteger amount, boolean isInfinite, long itemThreshold,
                boolean quantityLocked) {
            this.key = key;
            this.amount = amount;
            this.isInfinite = isInfinite;
            this.bytes = isInfinite ? INFINITE_BYTES : 0L;
            this.itemThreshold = itemThreshold;
            this.quantityLocked = quantityLocked;
        }
    }

    public List<PanelItem> getPanelItems() {
        List<PanelItem> items = new ArrayList<>();
        if (mode != 2) {
            return items;
        }
        for (T k : mine(data.wl)) {
            items.add(panelItem(k, BigInteger.valueOf(INFINITE), true));
        }
        for (T k : mine(data.ul)) {
            if (!data.wl.contains(k)) {
                items.add(panelItem(k, BigInteger.valueOf(INFINITE), true));
            }
        }
        // Rule matches: an Instant rule must be visible (and toggleable) in the panel before anything is
        // ever stored, otherwise the player cannot tell Instant from Touch.
        for (T k : ruleInfiniteKeys()) {
            if (!data.wl.contains(k) && !data.ul.contains(k) && !stocks().s2.containsKey(k)) {
                items.add(panelItem(k, BigInteger.valueOf(INFINITE), true));
            }
        }
        for (Map.Entry<IAEStack<?>, BigInteger> entry : stocks().s2.entrySet()) {
            if (!data.wl.contains(entry.getKey()) && !data.ul.contains(entry.getKey())) {
                items.add(panelItem(castKey(entry.getKey()), entry.getValue(), false));
            }
        }
        // Zero-count entries: a key that only carries a per-item threshold must stay listed, otherwise
        // there is no way to edit that threshold again once its count reaches zero.
        for (Map.Entry<IAEStack<?>, Long> entry : data.itemThr.entrySet()) {
            T k = castKey(entry.getKey());
            if (data.wl.contains(k) || data.ul.contains(k) || stocks().s2.containsKey(k)) {
                continue;
            }
            items.add(panelItem(k, BigInteger.ZERO, false));
        }
        return items;
    }

    private PanelItem panelItem(T key, BigInteger amount, boolean infinite) {
        T k = lookupKey(key);
        Long override = data.itemThr.get(k);
        return new PanelItem(k, amount, infinite, override == null ? 0L : override,
                data.qtyLocked.contains(k));
    }

    /** @return whether the key is infinite after the toggle. Locked keys are never toggled. */
    public boolean togglePanelInfinite(T key) {
        T k = lookupKey(key);
        if (data.qtyLocked.contains(k)) {
            return false;
        }
        if (data.ul.contains(k) || data.wl.contains(k)) {
            data.ul.remove(k);
            data.wl.remove(k);
            // NOTE (deliberate deviation from 1.20 L1158-1165): 1.20 just drops the committed amount
            // here, so cancelling from the panel silently destroys the recorded quantity. We put it
            // back into the finite store instead, matching cancelInfinite() and the 1.20 line's own
            // fix elsewhere ("取消无限要排真值"). Mode 2's room, always -- see cancelInfinite().
            BigInteger committed = stocks().ca.remove(k);
            if (committed != null && committed.signum() > 0) {
                stocks().s2.merge(k, committed, BigInteger::add);
            }
            saveChanges();
            return false;
        }
        BigInteger amount = stocks().s2.get(k);
        if (amount != null && amount.signum() > 0) {
            stocks().ca.put(k, amount);
            stocks().s2.remove(k);
        }
        data.ul.add(k);
        saveChanges();
        return true;
    }

    /** Falls back to this item's effective threshold when nothing was ever committed (1.20 L1185). */
    public BigInteger getCommitedAmount(IAEStack<?> key) {
        T k = lookupKey(castKey(key));
        BigInteger committed = stocks().ca.get(k);
        return committed != null ? committed : BigInteger.valueOf(thresholdFor(k));
    }

    public boolean hasCommitedAmount(IAEStack<?> key) {
        return stocks().ca.containsKey(lookupKey(castKey(key)));
    }

    // ------------------------------------------------------------------
    // wipe / accessors for the GUI + network layer
    // ------------------------------------------------------------------

    /** Clears this cell's configuration and both finite stores. mode 3's seen-set is kept (1.20 L243). */
    public void wipeAllData() {
        stocks().s1.clear();
        stocks().s2.clear();
        data.wl.clear();
        data.ul.clear();
        stocks().ca.clear();
        data.itemThr.clear();
        data.oreRules.clear();
        data.modRules.clear();
        data.ruleModes.clear();
        data.touched.clear();
        data.blacklist.clear();
        data.qtyLocked.clear();
        data.rejectOn.clear();
        data.rejectOff.clear();
        data.rejectDefault = false;
        data.ruleInstant = true;
        workMode = 1;
        threshold = DEFAULT_THRESHOLD;
        invalidateInstantMatchCache();
        saveChanges();
    }

    /**
     * Diagnostic for the self-test (and anyone debugging the terminal): how many rows THIS channel would report
     * right now. Lives here because only the core knows T, and the per-channel completeness check has to be
     * run for every registered channel without naming their stack types.
     */
    public int listedRowCount() {
        IItemList<T> list = channel.createList();
        getAvailableItems(list);
        int rows = 0;
        for (T ignored : list) {
            rows++;
        }
        return rows;
    }

    /** The channel id this core speaks (handy for diagnostics and for the panel's row tags). */
    public String channelId() {
        return cellChannel.id();
    }

    // ------------------------------------------------------------------
    // untyped delegates for the config panel
    //
    // The panel serves EVERY channel the cell speaks (sensei 2026-10-03), so it holds keys as IAEStack<?> and
    // reaches them through the channel's own core. The casts are safe by construction: a key that came out of
    // this channel's data is of this channel's type.
    // ------------------------------------------------------------------

    public boolean togglePanelInfiniteOf(IAEStack<?> key) {
        return togglePanelInfinite(castKey(key));
    }

    public boolean toggleBlacklistOf(IAEStack<?> key) {
        return toggleBlacklist(castKey(key));
    }

    /**
     * Blacklist query for a key of ANY channel: the blacklist is a shared collection of {@code IAEStack<?>},
     * so this needs no cast and works when the panel asks from another channel's view.
     */
    public boolean isBlacklistedOf(IAEStack<?> key) {
        return key != null && data.blacklist.contains(key);
    }

    public boolean toggleRejectOf(IAEStack<?> key) {
        return toggleReject(castKey(key));
    }

    public boolean clearRejectOf(IAEStack<?> key) {
        return clearReject(castKey(key));
    }

    public boolean addWhitelistOf(IAEStack<?> key) {
        return addWhitelist(castKey(key));
    }

    public boolean removeWhitelistOf(IAEStack<?> key) {
        return removeWhitelist(castKey(key));
    }

    public void setItemThresholdOf(IAEStack<?> key, long value) {
        setItemThreshold(castKey(key), value);
    }

    public void setStoredQuantityOf(IAEStack<?> key, BigInteger quantity, boolean lock) {
        setStoredQuantity(castKey(key), quantity, lock);
    }

    public BigInteger storedAmountOf(IAEStack<?> key) {
        return storedAmount(castKey(key));
    }

    public BigInteger getCommitedAmountOf(IAEStack<?> key) {
        return getCommitedAmount(key);
    }

    public boolean hasCommitedAmountOf(IAEStack<?> key) {
        return hasCommitedAmount(key);
    }

    /** Display name of a key of THIS channel, for the panel's label column. */
    public String displayNameOf(IAEStack<?> key) {
        return cellChannel.displayNameUntyped(key);
    }
    public Set<IAEStack<?>> getWl() {
        return new LinkedHashSet<>(data.wl);
    }

    public Set<IAEStack<?>> getUl() {
        return new LinkedHashSet<>(data.ul);
    }

    public Set<IAEStack<?>> getRejectOnKeys() {
        return new LinkedHashSet<>(data.rejectOn);
    }

    public Set<IAEStack<?>> getRejectOffKeys() {
        return new LinkedHashSet<>(data.rejectOff);
    }

    public Map<IAEStack<?>, Long> itemThresholds() {
        return new java.util.LinkedHashMap<>(data.itemThr);
    }

    public Map<IAEStack<?>, BigInteger> getS2() {
        return new java.util.LinkedHashMap<>(stocks().s2);
    }

    // ------------------------------------------------------------------
    // ICellInventory surface (unchanged semantics from P1)
    // ------------------------------------------------------------------

    @Override
    public ItemStack getItemStack() {
        return cellStack;
    }

    @Override
    public double getIdleDrain() {
        return 0.0;
    }

    @Override
    public FuzzyMode getFuzzyMode() {
        return FuzzyMode.IGNORE_ALL;
    }

    @Override
    public IItemHandler getConfigInventory() {
        return configInventory;
    }

    @Override
    public IItemHandler getUpgradesInventory() {
        return upgradesInventory;
    }

    @Override
    public int getBytesPerType() {
        return 0;
    }

    @Override
    public boolean canHoldNewItem() {
        return true;
    }

    @Override
    public long getTotalBytes() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getFreeBytes() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getUsedBytes() {
        return 0L;
    }

    @Override
    public long getTotalItemTypes() {
        return Integer.MAX_VALUE;
    }

    @Override
    public long getStoredItemCount() {
        // Mode-aware on purpose: the three main modes are ISOLATED stores, so folding another mode's pile in
        // here would make the figure the cell reports disagree with what its terminal actually lists (the
        // same "two sides drift apart" class that produced the 2026-10-03 listing bugs).
        //   mode 1 -> s1; mode 2 -> s2 (committed amounts belong to infinite keys, which are not counted);
        //   mode 3 -> nothing is ever stored.
        BigInteger total = BigInteger.ZERO;
        if (mode == 1) {
            for (BigInteger amount : stocks().s1.values()) {
                total = total.add(amount);
            }
        } else if (mode == 2) {
            for (BigInteger amount : stocks().s2.values()) {
                total = total.add(amount);
            }
        }
        return clamp(total);
    }

    @Override
    public long getStoredItemTypes() {
        // This MUST agree with what getAvailableItems() reports, or the cell's tooltip contradicts the
        // terminal. Before 2026-10-02 it counted only s1 + s2, so a cell holding nothing but whitelisted /
        // promoted / rule-reached items claimed "0 types" while the terminal listed them -- the same
        // omission class as the "invisible in the terminal" bug sensei hit.
        switch (mode) {
            case 3: {
                // Must match what getAvailableItems reports in mode 3: everything the registry yields, plus
                // the NBT variants we have seen.
                Set<IAEStack<?>> all = new LinkedHashSet<>(allRegistryKeys());
                // mine(...) on purpose: m3 is a SHARED collection, so an unfiltered addAll would count other
                // channels' seen keys here and make the reported type count disagree with the listing.
                all.addAll(mine(data.m3));
                return all.size();
            }
            case 2: {
                Set<IAEStack<?>> all = new LinkedHashSet<>(infiniteKeys());
                all.addAll(stocks().s2.keySet());
                return all.size();
            }
            default:
                return stocks().s1.size();
        }
    }

    @Override
    public long getRemainingItemTypes() {
        return Integer.MAX_VALUE - getStoredItemTypes();
    }

    @Override
    public long getRemainingItemCount() {
        return Long.MAX_VALUE;
    }

    @Override
    public int getUnusedItemCount() {
        return 0;
    }

    @Override
    public int getStatusForCell() {
        return 1;
    }

    // ------------------------------------------------------------------
    // tooltip
    // ------------------------------------------------------------------

    /**
     * P2 tooltip shows routing settings only -- no counts. The counts live in world saved data now, so a
     * client-side tooltip cannot read them; the terminal gets them from the server instead.
     */
    public static String modeName(int mode) {
        switch (mode) {
            case 2:
                return "模式2 自定义无限";
            case 3:
                return "模式3 全类型无限";
            default:
                return "模式1 无限制存储";
        }
    }

    public static String workModeName(int workMode) {
        switch (workMode) {
            case 2:
                return "存入无限";
            case 3:
                return "臻藏";
            default:
                return "阈值";
        }
    }
}
