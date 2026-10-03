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
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import net.minecraft.item.Item;
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
public class UnlimitedCellInventory implements ICellInventory<IAEItemStack> {

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
    private final IStorageChannel<IAEItemStack> channel;

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

    public UnlimitedCellInventory(ItemStack cellStack, ISaveProvider container) {
        this.cellStack = cellStack;
        this.container = container;
        this.channel = itemChannel();
        NBTTagCompound v1Payload = loadSmallFields();
        attachStorage(v1Payload);
    }

    public static IStorageChannel<IAEItemStack> itemChannel() {
        return AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
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
                IAEItemStack key = AEItemStack.fromNBT(entry.getCompoundTag(NBT_KEY));
                if (key == null) {
                    continue;
                }
                BigInteger count = new BigInteger(entry.getString(NBT_COUNT));
                if (count.signum() <= 0) {
                    continue;
                }
                key.setStackSize(1);
                target.s1.merge(key, count, BigInteger::add);
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
            for (BigInteger amount : data.s1.values()) {
                bytes = bytes.add(amount);
            }
            return bytes;
        }
        if (mode != 2) {
            return bytes; // mode 3 reports infinity in the tooltip, no accounting needed
        }
        for (BigInteger amount : data.s2.values()) {
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
    private Set<IAEItemStack> infiniteKeys() {
        Set<IAEItemStack> infinite = new LinkedHashSet<>(data.wl);
        infinite.addAll(data.ul);
        infinite.addAll(ruleInfiniteKeys());
        return infinite;
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
    public IAEItemStack injectItems(IAEItemStack input, Actionable action, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) {
            return null;
        }
        IAEItemStack key = lookupKey(input);
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
    private boolean insertInternal(IAEItemStack key, BigInteger amount, boolean mutate) {
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
                    BigInteger existing = data.s2.remove(key);
                    if (existing != null && existing.signum() > 0) {
                        data.ca.merge(key, existing, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                    }
                    data.ca.merge(key, amount, BigInteger::add);
                    saveChanges();
                }
                return true;
            }
            if (workMode == 2 && !data.blacklist.contains(key)) {
                // 存入无限: storing anything at all makes it infinite.
                if (mutate) {
                    data.ca.merge(key, amount, BigInteger::add);
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

        Map<IAEItemStack, BigInteger> map = mode == 1 ? data.s1 : data.s2;
        BigInteger total = map.merge(key, amount, BigInteger::add);

        // Blacklisted keys may be stored, but never promoted (1.20 L465-467).
        if (mode == 2 && workMode == 1 && !data.blacklist.contains(key)
                && total.compareTo(BigInteger.valueOf(thresholdFor(key))) >= 0) {
            // Promotion, three steps, atomically: real total -> committed, join the infinite set,
            // leave the finite store.
            data.ca.put(key, total);
            data.ul.add(key);
            map.remove(key);
        }

        saveChanges();
        return true;
    }

    @Override
    public IAEItemStack extractItems(IAEItemStack input, Actionable action, IActionSource src) {
        if (input == null || input.getStackSize() <= 0) {
            return null;
        }
        IAEItemStack key = lookupKey(input);
        long requested = input.getStackSize();
        boolean mutate = action == Actionable.MODULATE;

        BigInteger taken;
        if (mode == 2 && data.qtyLocked.contains(key)) {
            // Locked: never hand out more than what is recorded, and do not change the record
            // (1.20 L498-500 -- the pinned amount behaves the same in both directions).
            taken = extractFromMap(data.s2, key, requested, false);
        } else if (mode == 3) {
            if (mutate) {
                data.m3.add(key);
            }
            taken = BigInteger.valueOf(Math.min(requested, INFINITE));
        } else if (mode == 2 && (ruleActive(key) || data.ul.contains(key) || data.wl.contains(key))) {
            taken = BigInteger.valueOf(Math.min(requested, INFINITE));
        } else {
            taken = extractFromMap(mode == 1 ? data.s1 : data.s2, key, requested, mutate);
        }

        if (taken.signum() <= 0) {
            // Nothing extracted -> null (0 would make callers think they got something).
            return null;
        }
        if (mutate) {
            saveChanges();
        }
        IAEItemStack result = input.copy();
        result.setStackSize(clamp(taken));
        return result;
    }

    private BigInteger extractFromMap(Map<IAEItemStack, BigInteger> map, IAEItemStack key, long requested,
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
    public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out) {
        switch (mode) {
            case 3: {
                // 1.20 parity: mode 3 means "every registered item is infinite", so every registered item has
                // to be listed -- otherwise you cannot see (or pull) what you never stored. sensei asked for
                // this on 2026-10-02, conditional on it not being laggy, so the self-test's H group measures
                // the cost instead of guessing. The scan is cached forever (the registry is frozen once the
                // game has loaded) and reporting uses reportInfinite (set-max, never accumulate), so what a
                // grid rebuild actually pays is one walk over the cached key list.
                reportInfinite(out, allRegistryKeys());
                reportInfinite(out, data.m3); // NBT variants we have seen, which the registry scan cannot name
                break;
            }
            case 2: {
                // EVERYTHING that can hand items out has to be listed here, or the terminal shows nothing
                // even though extraction works. That covers four separate sources, and missing any of them
                // makes items invisible in the terminal:
                //   * wl  -- the whitelist, which is what the panel's "mark infinite" and a JEI drag add to
                //   * ul  -- the infinite set written by promotion and by togglePanelInfinite
                //   * ca  -- the committed-amount map; a rule promotion writes ca WITHOUT touching ul
                //   * the rules themselves -- an Instant rule must show up before anything is ever stored
                // (Bugs found by sensei 2026-10-02: first wl and ca were missing; then rule matches were,
                // which made "Instant" indistinguishable from "Touch" from the player's side.)
                Set<IAEItemStack> infinite = infiniteKeys();
                reportInfinite(out, infinite);
                // Finite rows must not land on top of a key that is already infinite -- in this cell or in
                // another cell of the same network. reportMap decides that per entry via findPrecise, the
                // identity IItemList.add itself uses, because the list SUMS and INFINITE + n wraps negative
                // (sensei's "the item vanished from the terminal", 2026-10-03).
                reportMap(out, data.s2);
                break;
            }
            default:
                reportMap(out, data.s1);
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
    private static void reportInfinite(IItemList<IAEItemStack> out, Iterable<IAEItemStack> keys) {
        for (IAEItemStack key : keys) {
            IAEItemStack existing = out.findPrecise(key);
            if (existing == null) {
                IAEItemStack stack = key.copy();
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
    private static void reportMap(IItemList<IAEItemStack> out, Map<IAEItemStack, BigInteger> map) {
        for (Map.Entry<IAEItemStack, BigInteger> entry : map.entrySet()) {
            long size = clamp(entry.getValue());
            if (size <= 0) {
                continue;
            }
            IAEItemStack key = entry.getKey();
            IAEItemStack existing = out.findPrecise(key);
            if (existing != null && existing.getStackSize() >= INFINITE) {
                continue;
            }
            IAEItemStack stack = key.copy();
            stack.setStackSize(size);
            out.add(stack);
        }
    }

    @Override
    public IStorageChannel<IAEItemStack> getChannel() {
        return channel;
    }

    private static IAEItemStack lookupKey(IAEItemStack input) {
        // Identity ignores size (AEItemStack compares the shared stack, count forced to 1), but
        // normalising keeps stored keys uniform.
        IAEItemStack key = input.copy();
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
     * Switching mode migrates stored counts instead of hiding them: 1.20 keeps s1 and s2 side by side,
     * so a mode switch makes the other map's contents invisible (not lost, but alarming). P2 merges
     * them so a player never sees numbers disappear.
     */
    public void setMode(int newMode) {
        int target = clampToRange(newMode, 1, 3, 1);
        if (target == mode) {
            return;
        }
        if (target == 1) {
            mergeInto(data.s1, data.s2);
        } else {
            mergeInto(data.s2, data.s1);
        }
        mode = target;
        saveChanges();
    }

    private static void mergeInto(Map<IAEItemStack, BigInteger> target, Map<IAEItemStack, BigInteger> source) {
        for (Map.Entry<IAEItemStack, BigInteger> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), BigInteger::add);
        }
        source.clear();
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
        java.util.List<IAEItemStack> keys = new java.util.ArrayList<>(data.s2.keySet());
        for (IAEItemStack key : keys) {
            BigInteger total = data.s2.get(key);
            if (total == null || total.signum() <= 0) {
                continue;
            }
            boolean promote = promoteAll
                    || total.compareTo(BigInteger.valueOf(thresholdFor(key))) >= 0;
            if (promote) {
                data.ca.put(key, total);
                data.ul.add(key);
                data.s2.remove(key);
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

    public long thresholdFor(IAEItemStack key) {
        Long perItem = data.itemThr.get(lookupKey(key));
        return perItem != null && perItem > 0 ? perItem : threshold;
    }

    /** value &lt;= 0 clears the per-item threshold so the global one applies again. */
    public void setItemThreshold(IAEItemStack key, long value) {
        IAEItemStack k = lookupKey(key);
        if (value <= 0) {
            data.itemThr.remove(k);
        } else {
            data.itemThr.put(k, value);
        }
        saveChanges();
    }

    public boolean addWhitelist(IAEItemStack key) {
        boolean changed = data.wl.add(lookupKey(key));
        if (changed) {
            saveChanges();
        }
        return changed;
    }

    public boolean removeWhitelist(IAEItemStack key) {
        boolean changed = data.wl.remove(lookupKey(key));
        if (changed) {
            saveChanges();
        }
        return changed;
    }

    public boolean isWhitelisted(IAEItemStack key) {
        return data.wl.contains(lookupKey(key));
    }

    public boolean isInfinite(IAEItemStack key) {
        return data.ul.contains(lookupKey(key));
    }

    // isQuantityLocked() now lives with the quantity-lock API further down.

    // ------------------------------------------------------------------
    // reject list (three-state, ported from the 1.20 line L762-799)
    // ------------------------------------------------------------------

    /** Reject check on an already-normalised key (hot path: no extra copy). */
    private boolean isRejectingKeyed(IAEItemStack key) {
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
    public boolean isRejecting(IAEItemStack key) {
        return isRejectingKeyed(lookupKey(key));
    }

    /** Flips the key: anything not currently rejected becomes explicitly rejected, and vice versa. */
    public boolean toggleReject(IAEItemStack key) {
        IAEItemStack k = lookupKey(key);
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
    public boolean clearReject(IAEItemStack key) {
        IAEItemStack k = lookupKey(key);
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
     */
    public boolean cancelInfinite(IAEItemStack key) {
        IAEItemStack k = lookupKey(key);
        if (!data.ul.remove(k)) {
            return false;
        }
        BigInteger committed = data.ca.remove(k);
        if (committed != null && committed.signum() > 0) {
            Map<IAEItemStack, BigInteger> map = mode == 1 ? data.s1 : data.s2;
            map.merge(k, committed, BigInteger::add);
        }
        saveChanges();
        return true;
    }

    /** Stored amount for display/tests: finite store, committed amount, or flat zero. */
    public BigInteger storedAmount(IAEItemStack key) {
        IAEItemStack k = lookupKey(key);
        BigInteger inS1 = data.s1.get(k);
        BigInteger inS2 = data.s2.get(k);
        BigInteger committed = data.ca.get(k);
        BigInteger total = BigInteger.ZERO;
        if (inS1 != null) {
            total = total.add(inS1);
        }
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
                mode, workMode, threshold, data.wl.size(), data.ul.size(), data.s1.size(), data.s2.size(),
                data.ca.size(), data.rejectOn.size(), data.rejectOff.size(), data.rejectDefault, uuid);
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

    private static boolean hasTag(IAEItemStack key) {
        ItemStack def = key.getDefinition();
        return def != null && !def.isEmpty() && def.hasTagCompound();
    }

    private static String namespaceOf(IAEItemStack key) {
        ItemStack def = key.getDefinition();
        if (def == null || def.isEmpty()) {
            return null;
        }
        net.minecraft.util.ResourceLocation id = def.getItem().getRegistryName();
        return id == null ? null : id.getNamespace();
    }

    /** NBT variants never participate in rules (1.20 L1023-1028 records why they must not). */
    private boolean matchesRule(IAEItemStack key) {
        if (data.oreRules.isEmpty() && data.modRules.isEmpty()) {
            return false;
        }
        if (hasTag(key)) {
            return false;
        }
        String namespace = namespaceOf(key);
        if (namespace != null && data.modRules.contains(namespace)) {
            return true;
        }
        return matchesAnyOre(key);
    }

    private boolean matchesAnyOre(IAEItemStack key) {
        if (data.oreRules.isEmpty()) {
            return false;
        }
        ItemStack def = key.getDefinition();
        if (def == null || def.isEmpty()) {
            return false;
        }
        int[] ids = net.minecraftforge.oredict.OreDictionary.getOreIDs(def);
        if (ids == null) {
            return false;
        }
        for (int id : ids) {
            String ore = net.minecraftforge.oredict.OreDictionary.getOreName(id);
            if (ore != null && data.oreRules.contains(ore)) {
                return true;
            }
        }
        return false;
    }

    /** Instant rules allow infinity immediately; Touch rules need the key to have been stored. */
    public boolean ruleAllowsInfinite(IAEItemStack key) {
        if (data.oreRules.isEmpty() && data.modRules.isEmpty()) {
            return false;
        }
        IAEItemStack k = lookupKey(key);
        if (hasTag(k)) {
            return false;
        }
        boolean matched = false;

        String namespace = namespaceOf(k);
        if (namespace != null && data.modRules.contains(namespace)) {
            if (getRuleMode(ruleKey(false, namespace)) == 1) {
                return true;
            }
            matched = true;
        }

        ItemStack def = k.getDefinition();
        if (def != null && !def.isEmpty()) {
            int[] ids = net.minecraftforge.oredict.OreDictionary.getOreIDs(def);
            if (ids != null) {
                for (int id : ids) {
                    String ore = net.minecraftforge.oredict.OreDictionary.getOreName(id);
                    if (ore != null && data.oreRules.contains(ore)) {
                        if (getRuleMode(ruleKey(true, ore)) == 1) {
                            return true;
                        }
                        matched = true;
                    }
                }
            }
        }
        return matched && data.touched.contains(k);
    }

    /** Blacklist, quantity lock and "currently in the finite store" all veto a rule (1.20 L1077-1080). */
    private boolean ruleActive(IAEItemStack key) {
        if (data.blacklist.contains(key) || data.qtyLocked.contains(key) || data.s2.containsKey(key)) {
            return false;
        }
        return ruleAllowsInfinite(key);
    }

    public boolean isInfiniteByRule(IAEItemStack key) {
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
        if (data.ca.isEmpty()) {
            return false;
        }
        Map<IAEItemStack, BigInteger> back = mode == 1 ? data.s1 : data.s2;
        List<IAEItemStack> keys = new ArrayList<>(data.ca.keySet());
        boolean changed = false;
        for (IAEItemStack k : keys) {
            if (data.wl.contains(k) || data.ul.contains(k) || ruleActive(k)) {
                continue;
            }
            BigInteger amount = data.ca.remove(k);
            if (amount != null && amount.signum() > 0) {
                back.merge(k, amount, BigInteger::add);
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
    private transient List<IAEItemStack> instantMatchCache = null;
    private transient int instantMatchVersion = -1;
    private transient int ruleStateVersion = 0;

    private void invalidateInstantMatchCache() {
        ruleStateVersion++;
    }

    /** Pure rule test: does any rule matching this key run in Instant mode? Blacklist is applied later. */
    private boolean matchesInstantRule(IAEItemStack key) {
        if (data.oreRules.isEmpty() && data.modRules.isEmpty() || hasTag(key)) {
            return false;
        }
        String namespace = namespaceOf(key);
        if (namespace != null && data.modRules.contains(namespace)
                && getRuleMode(ruleKey(false, namespace)) == 1) {
            return true;
        }
        ItemStack def = key.getDefinition();
        if (def == null || def.isEmpty()) {
            return false;
        }
        int[] ids = net.minecraftforge.oredict.OreDictionary.getOreIDs(def);
        if (ids == null) {
            return false;
        }
        for (int id : ids) {
            String ore = net.minecraftforge.oredict.OreDictionary.getOreName(id);
            if (ore != null && data.oreRules.contains(ore) && getRuleMode(ruleKey(true, ore)) == 1) {
                return true;
            }
        }
        return false;
    }

    private boolean ruleListingVetoed(IAEItemStack key) {
        return data.blacklist.contains(key) || data.qtyLocked.contains(key) || data.s2.containsKey(key)
                || data.wl.contains(key) || data.ul.contains(key) || data.ca.containsKey(key);
    }

    /**
     * Every key the rules currently hand out as infinite, ready to be listed in the terminal or the panel.
     *
     * <p>Two sources: the cached registry scan (Instant rules -- items that were never stored) and the
     * touched set (Touch rules -- items that were). Storage vetoes (blacklist, quantity lock, "already in
     * the finite store") are applied here rather than baked into the cache, so storing or extracting an item
     * never invalidates the scan.
     */
    private Set<IAEItemStack> ruleInfiniteKeys() {
        Set<IAEItemStack> out = new LinkedHashSet<>();
        if (mode != 2 || (data.oreRules.isEmpty() && data.modRules.isEmpty())) {
            return out;
        }
        for (IAEItemStack key : instantMatchedKeys()) {
            if (!ruleListingVetoed(key) && ruleAllowsInfinite(key)) {
                out.add(key);
            }
        }
        for (IAEItemStack key : data.touched) {
            if (!ruleListingVetoed(key) && ruleAllowsInfinite(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Registry scan for Instant rules, cached until the rules change. Thousands of ItemStacks, so: once. */
    private List<IAEItemStack> instantMatchedKeys() {
        if (instantMatchCache != null && instantMatchVersion == ruleStateVersion) {
            return instantMatchCache;
        }
        List<IAEItemStack> found = new ArrayList<>();
        if (!data.oreRules.isEmpty() || !data.modRules.isEmpty()) {
            for (IAEItemStack key : allRegistryKeys()) {
                if (matchesInstantRule(key)) {
                    found.add(key);
                }
            }
        }
        instantMatchCache = found;
        instantMatchVersion = ruleStateVersion;
        return found;
    }

    /**
     * Canonical key for every registered item, <b>metadata variants included</b>, no NBT.
     *
     * <p>Meta 0 alone is not enough: a pack registers one Item with many metas and nothing else distinguishes
     * them. Mekanism CE's {@code ItemIngot} implements {@code IMetaItem}, so refined obsidian and osmium share
     * one item id and differ only by meta -- scanning just {@code new ItemStack(item)} made an Instant rule
     * infinite for the first meta of such a sequence and left every other meta Touch-only. That is
     * 2026-10-03 bug 3 (sensei: "立即无限只会无限某一序列号的第一位物品").
     *
     * <p>So every item is asked for its sub-items the way the creative inventory does it
     * ({@code Item#getSubItems}). {@code CreativeTabs.displayAllRelevantItems} is deliberately not used: it
     * is client-only, and this scan also runs on the server. Items that refuse to answer fall back to
     * meta 0, and a mod whose getSubItems throws cannot kill the scan.
     *
     * <p>Tagged stacks are skipped on purpose: the canonical key is (item, meta, no NBT) -- the rule
     * predicate cannot speak about NBT variants, and mode 3 reports the ones we have actually seen (m3)
     * separately.
     *
     * <p>The whole scan is cached forever: the item registry is frozen once the game has loaded.
     */
    private transient List<IAEItemStack> registryKeyCache = null;

    private List<IAEItemStack> allRegistryKeys() {
        if (registryKeyCache != null) {
            return registryKeyCache;
        }
        List<IAEItemStack> found = new ArrayList<>();
        Set<IAEItemStack> seen = new LinkedHashSet<>();
        for (net.minecraft.util.ResourceLocation id : net.minecraft.item.Item.REGISTRY.getKeys()) {
            Item item = net.minecraft.item.Item.REGISTRY.getObject(id);
            // minecraft:air is IN the item registry, and new ItemStack(air) is an EMPTY stack, for which
            // AEItemStack.fromItemStack returns null -- that NPE'd the whole server tick loop on 2026-10-02
            // (thrown from here, via getAvailableItems <- TileDrive.recalculateDisplay). Skip it, and skip
            // anything else that cannot produce a key, instead of trusting every registry entry to be
            // convertible.
            if (item == null || item == net.minecraft.init.Items.AIR) {
                continue;
            }
            for (ItemStack def : subItemStacks(item)) {
                IAEItemStack key = AEItemStack.fromItemStack(def);
                if (key == null) {
                    continue;
                }
                if (seen.add(lookupKey(key))) {
                    found.add(key);
                }
            }
        }
        registryKeyCache = found;
        return found;
    }

    /** Every stack this item offers to the creative inventory, meta 0 when it will not answer. */
    private static List<ItemStack> subItemStacks(Item item) {
        List<ItemStack> out = new ArrayList<>();
        collectSubItems(item, net.minecraft.creativetab.CreativeTabs.SEARCH, out);
        // Mods that gate their variants on their own tab rather than going through isInCreativeTab only
        // answer for that tab, so ask those too.
        net.minecraft.creativetab.CreativeTabs[] tabs = item.getCreativeTabs();
        if (tabs != null) {
            for (net.minecraft.creativetab.CreativeTabs tab : tabs) {
                collectSubItems(item, tab, out);
            }
        }
        if (out.isEmpty()) {
            out.add(new ItemStack(item));
        }
        return out;
    }

    private static void collectSubItems(Item item, net.minecraft.creativetab.CreativeTabs tab,
            List<ItemStack> out) {
        if (tab == null) {
            return;
        }
        try {
            net.minecraft.util.NonNullList<ItemStack> sub = net.minecraft.util.NonNullList.create();
            item.getSubItems(tab, sub);
            for (ItemStack stack : sub) {
                if (stack == null || stack.isEmpty() || stack.getItem() == net.minecraft.init.Items.AIR
                        || stack.hasTagCompound()) {
                    continue;
                }
                out.add(stack);
            }
        } catch (Throwable t) {
            // One bad mod must not cost us the whole scan: fall back to whatever else answered (meta 0 at
            // the very least).
            AE2Addon.LOG.warn("[ae2addon] {} 的 getSubItems 失败，跳过该来源：{}", item.getRegistryName(), t);
        }
    }

    /** How many canonical keys the item registry yields (for the self-test's report). */
    public int registryKeyCount() {
        return allRegistryKeys().size();
    }

    // ------------------------------------------------------------------
    // blacklist / quantity lock
    // ------------------------------------------------------------------

    /** @return whether the key is blacklisted after the toggle. */
    public boolean toggleBlacklist(IAEItemStack key) {
        IAEItemStack k = lookupKey(key);
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

    public boolean isBlacklisted(IAEItemStack key) {
        return data.blacklist.contains(lookupKey(key));
    }

    public Set<IAEItemStack> getBlacklist() {
        return new LinkedHashSet<>(data.blacklist);
    }

    /** Pins a key's stored amount; {@code lock} also marks it so inserts/extracts stay capped. */
    public void setStoredQuantity(IAEItemStack key, BigInteger quantity, boolean lock) {
        if (key == null || quantity == null) {
            return;
        }
        IAEItemStack k = lookupKey(key);
        data.ul.remove(k);
        data.wl.remove(k);
        data.ca.remove(k);
        Map<IAEItemStack, BigInteger> map = mode == 1 ? data.s1 : data.s2;
        if (quantity.signum() <= 0) {
            map.remove(k);
        } else {
            map.put(k, quantity);
        }
        if (lock) {
            data.qtyLocked.add(k);
        } else {
            data.qtyLocked.remove(k);
        }
        saveChanges();
    }

    public boolean isQuantityLocked(IAEItemStack key) {
        return data.qtyLocked.contains(lookupKey(key));
    }

    public Set<IAEItemStack> quantityLockedKeys() {
        return new LinkedHashSet<>(data.qtyLocked);
    }

    // ------------------------------------------------------------------
    // panel enumeration / infinite toggle / committed amount
    // ------------------------------------------------------------------

    /** One row of the config panel. {@code bytes} stays 0 for finite rows: we do no byte accounting. */
    public static final class PanelItem {
        public final IAEItemStack key;
        public final BigInteger amount;
        public final boolean isInfinite;
        public final long bytes;
        public final long itemThreshold;
        public final boolean quantityLocked;

        PanelItem(IAEItemStack key, BigInteger amount, boolean isInfinite, long itemThreshold,
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
        for (IAEItemStack k : data.wl) {
            items.add(panelItem(k, BigInteger.valueOf(INFINITE), true));
        }
        for (IAEItemStack k : data.ul) {
            if (!data.wl.contains(k)) {
                items.add(panelItem(k, BigInteger.valueOf(INFINITE), true));
            }
        }
        // Rule matches: an Instant rule must be visible (and toggleable) in the panel before anything is
        // ever stored, otherwise the player cannot tell Instant from Touch.
        for (IAEItemStack k : ruleInfiniteKeys()) {
            if (!data.wl.contains(k) && !data.ul.contains(k) && !data.s2.containsKey(k)) {
                items.add(panelItem(k, BigInteger.valueOf(INFINITE), true));
            }
        }
        for (Map.Entry<IAEItemStack, BigInteger> entry : data.s2.entrySet()) {
            if (!data.wl.contains(entry.getKey()) && !data.ul.contains(entry.getKey())) {
                items.add(panelItem(entry.getKey(), entry.getValue(), false));
            }
        }
        // Zero-count entries: a key that only carries a per-item threshold must stay listed, otherwise
        // there is no way to edit that threshold again once its count reaches zero.
        for (Map.Entry<IAEItemStack, Long> entry : data.itemThr.entrySet()) {
            IAEItemStack k = entry.getKey();
            if (data.wl.contains(k) || data.ul.contains(k) || data.s2.containsKey(k)) {
                continue;
            }
            items.add(panelItem(k, BigInteger.ZERO, false));
        }
        return items;
    }

    private PanelItem panelItem(IAEItemStack key, BigInteger amount, boolean infinite) {
        IAEItemStack k = lookupKey(key);
        Long override = data.itemThr.get(k);
        return new PanelItem(k, amount, infinite, override == null ? 0L : override,
                data.qtyLocked.contains(k));
    }

    /** @return whether the key is infinite after the toggle. Locked keys are never toggled. */
    public boolean togglePanelInfinite(IAEItemStack key) {
        IAEItemStack k = lookupKey(key);
        if (data.qtyLocked.contains(k)) {
            return false;
        }
        if (data.ul.contains(k) || data.wl.contains(k)) {
            data.ul.remove(k);
            data.wl.remove(k);
            // NOTE (deliberate deviation from 1.20 L1158-1165): 1.20 just drops the committed amount
            // here, so cancelling from the panel silently destroys the recorded quantity. We put it
            // back into the finite store instead, matching cancelInfinite() and the 1.20 line's own
            // fix elsewhere ("取消无限要排真值").
            BigInteger committed = data.ca.remove(k);
            if (committed != null && committed.signum() > 0) {
                Map<IAEItemStack, BigInteger> map = mode == 1 ? data.s1 : data.s2;
                map.merge(k, committed, BigInteger::add);
            }
            saveChanges();
            return false;
        }
        BigInteger amount = data.s2.get(k);
        if (amount != null && amount.signum() > 0) {
            data.ca.put(k, amount);
            data.s2.remove(k);
        }
        data.ul.add(k);
        saveChanges();
        return true;
    }

    /** Falls back to this item's effective threshold when nothing was ever committed (1.20 L1185). */
    public BigInteger getCommitedAmount(IAEItemStack key) {
        IAEItemStack k = lookupKey(key);
        BigInteger committed = data.ca.get(k);
        return committed != null ? committed : BigInteger.valueOf(thresholdFor(k));
    }

    public boolean hasCommitedAmount(IAEItemStack key) {
        return data.ca.containsKey(lookupKey(key));
    }

    // ------------------------------------------------------------------
    // wipe / accessors for the GUI + network layer
    // ------------------------------------------------------------------

    /** Clears this cell's configuration and both finite stores. mode 3's seen-set is kept (1.20 L243). */
    public void wipeAllData() {
        data.s1.clear();
        data.s2.clear();
        data.wl.clear();
        data.ul.clear();
        data.ca.clear();
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

    public Set<IAEItemStack> getWl() {
        return new LinkedHashSet<>(data.wl);
    }

    public Set<IAEItemStack> getUl() {
        return new LinkedHashSet<>(data.ul);
    }

    public Set<IAEItemStack> getRejectOnKeys() {
        return new LinkedHashSet<>(data.rejectOn);
    }

    public Set<IAEItemStack> getRejectOffKeys() {
        return new LinkedHashSet<>(data.rejectOff);
    }

    public Map<IAEItemStack, Long> itemThresholds() {
        return new java.util.LinkedHashMap<>(data.itemThr);
    }

    public Map<IAEItemStack, BigInteger> getS2() {
        return new java.util.LinkedHashMap<>(data.s2);
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
        BigInteger total = BigInteger.ZERO;
        for (BigInteger amount : data.s1.values()) {
            total = total.add(amount);
        }
        for (BigInteger amount : data.s2.values()) {
            total = total.add(amount);
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
                Set<IAEItemStack> all = new LinkedHashSet<>(allRegistryKeys());
                all.addAll(data.m3);
                return all.size();
            }
            case 2: {
                Set<IAEItemStack> all = infiniteKeys();
                all.addAll(data.s2.keySet());
                return all.size();
            }
            default:
                return data.s1.size();
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
