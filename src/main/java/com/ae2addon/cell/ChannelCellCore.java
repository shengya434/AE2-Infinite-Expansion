package com.ae2addon.cell;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.ae2addon.AE2Addon;
import com.ae2addon.data.CellWorldData;
import com.ae2addon.util.PinyinIndex;

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
    // 3: the tooltip summary is stored per channel (_ch table) instead of one shared pair of tags (2026-10-04).
    // 2: settings moved into the shared world record; cell item NBT keeps a copy for the tooltip only.
    // 1: P1 layout, payload in the cell item (migrated on load).
    public static final int FORMAT_VERSION = 3;
    public static final long DEFAULT_THRESHOLD = 65536L;

    /**
     * Amount reported for an "infinite" item (1.20 exposes this as config infiniteItemAmount).
     *
     * <p><b>Why {@code Long.MAX_VALUE / 4} and not {@code Long.MAX_VALUE}</b> (sensei, 2026-10-05, matching how
     * the 1.20.1/1.21.1 fork does it): AE2's network inventory is a list that SUMS whatever the cells report, and
     * our value is a real number in that sum. At {@code Long.MAX_VALUE} the very first thing another cell adds to
     * the same key -- a physical movement the drive posts, a second unlimited cell, anything -- wrapped the row
     * NEGATIVE, and a negative row simply is not there. That produced a whole family of "the item vanished",
     * "the number moved", "a ghost row is left behind" reports (see the 2026-10-05 chain in the diary).
     *
     * <p>{@code Long.MAX_VALUE / 4} keeps four such claims inside the {@code long} range (4 x it is exactly
     * {@code Long.MAX_VALUE}), so a few cells and any number of {@code +-n} movements stay far from the edge.
     * The "this is infinite" reading is not lost: the client renders any size at or above {@link #INFINITE_SIGHT}
     * as an infinity symbol (see {@code MixinReadableNumberConverter}), which is the other half of the fork's
     * approach -- a sum-safe value plus a display that says what it means.
     */
    public static volatile long INFINITE = Long.MAX_VALUE / 4;

    /**
     * A size at or above which a row is treated as "infinite" for display and for guarding the drive's posts.
     *
     * <p>Deliberately half of {@link #INFINITE}: a row owned by an infinite claim may sit a few units below the
     * exact value (the drive posts the physical amount it moved right on top of it), and no legitimate finite
     * count in this game comes anywhere near 1.15e18.
     */
    public static final long INFINITE_SIGHT = INFINITE / 2;

    /**
     * Historic alias for the sum-safe value, kept because the essentia channel's self-test and older comments
     * refer to it. All channels now publish {@link #INFINITE}; this is the same idea at an unnecessarily small
     * scale ({@code Integer.MAX_VALUE}), from the 2026-10-04 experiment that proved the aggregate sums.
     */
    public static final long SAFE_INFINITE = Integer.MAX_VALUE;

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
    /**
     * Per-channel summary table: {@code _ch/<channelId> -> {_b2,_b,_t}}. The cell answers for several channels,
     * so its counters have to be per channel -- see {@link #writeSmallFields()} for why a shared pair of tags
     * was a bug (2026-10-04).
     */
    private static final String NBT_CHANNELS = "_ch";

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

    // ------------------------------------------------------------------
    // settings are NOT instance state any more
    //
    // They used to be cached here (read once from the cell item NBT). The drive keeps one inventory per CHANNEL
    // alive, so a mode switch only reached the instance rebuilt afterwards: the other channels kept running the
    // old mode and the terminal showed "only one storage type is infinite" (sensei, 2026-10-03). They now live in
    // CellStorageData -- one record per cell, shared by every channel view -- and these three below are only the
    // values read from the cell item NBT at construction, used to seed that record for pre-existing saves.
    // ------------------------------------------------------------------
    private int nbtMode = 1;
    private int nbtWorkMode = 1;
    private long nbtThreshold = DEFAULT_THRESHOLD;

    /** Live mode: always the shared record, never a per-instance copy. */
    public int getMode() {
        return data.mode;
    }

    private int mode() {
        return data.mode;
    }

    private int workMode() {
        return data.workMode;
    }

    private long threshold() {
        return data.threshold;
    }
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
        nbtMode = clampToRange(root.getInteger(NBT_MODE), 1, 3, 1);
        nbtWorkMode = clampToRange(root.getInteger(NBT_WORKMODE), 1, 3, 1);
        nbtThreshold = root.getLong(NBT_THR);
        if (nbtThreshold <= 0) {
            nbtThreshold = DEFAULT_THRESHOLD;
        }

        String rawUuid = root.getString(NBT_UUID);
        if (!rawUuid.isEmpty()) {
            try {
                uuid = UUID.fromString(rawUuid);
            } catch (RuntimeException ignored) {
                uuid = null;
            }
        }
        // "Version 1" means the P1 layout, where the payload lived in the cell item itself. Pinned to the
        // literal 1 rather than FORMAT_VERSION: that constant moves forward (it is 3 now that the summary table
        // is per channel), while this test is about a layout that will never come back.
        return version < 2 && root.hasKey(NBT_ITEMS, 9) ? root : null;
    }

    private void attachStorage(NBTTagCompound v1Payload) {
        CellWorldData worldData = CellWorldData.get();
        if (worldData == null) {
            // Client side, or no world yet. Never mutate in this state; reads simply come up empty
            // (the terminal gets its numbers from the server anyway).
            // Still mirror the settings the cell item carries: a client-side view (the drive draws its LEDs on
            // the client too) must show the same mode/threshold as the server instead of defaulting to mode 1.
            data.seedSettingsIfAbsent(nbtMode, nbtWorkMode, nbtThreshold);
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
        // Migration for cells saved before the settings moved into the shared record: the first view to
        // attach copies them across from the cell item NBT, once.
        data.seedSettingsIfAbsent(nbtMode, nbtWorkMode, nbtThreshold);

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
        if (CellWorldData.get() == null) {
            // No authoritative data (client side, or no world yet): this instance holds a DETACHED, empty
            // CellStorageData, so writing it would stamp "0 bytes / 0 types" onto the cell item and the tooltip
            // would lie until the server happens to write again -- that was sensei's BUG2 of 2026-10-03 ("the
            // cell in the drive shows 0 bytes/types; opening the config panel restores them"). The cell item's
            // NBT copy belongs to the server, which always has the world data.
            return;
        }
        // Mutate the EXISTING root instead of replacing it. The cell serves several channels at once, and each
        // of those channel views persists independently (the drive keeps one inventory per channel): replacing
        // the whole compound meant the last writer wiped the others' entries, so the tooltip's numbers belonged
        // to whichever channel happened to write last -- sensei saw that as "the drive slot shows one random
        // storage type / 0 bytes, and opening the panel restores it" (2026-10-03/04).
        NBTTagCompound stackTag = Platform.openNbtData(cellStack);
        NBTTagCompound root = stackTag.hasKey(NBT_ROOT, 10) ? stackTag.getCompoundTag(NBT_ROOT)
                : new NBTTagCompound();
        root.setInteger(NBT_VERSION, FORMAT_VERSION);
        root.setInteger(NBT_MODE, mode());
        root.setInteger(NBT_WORKMODE, workMode());
        root.setLong(NBT_THR, threshold());
        root.setString(NBT_UUID, uuid == null ? "" : uuid.toString());
        BigInteger summaryBytes = summaryBytes();
        int summaryTypes = (int) getStoredItemTypes();
        // This channel's counters go into their own slot in the per-channel table, so every channel keeps its
        // own numbers and the tooltip can show them side by side.
        NBTTagCompound perChannel = root.hasKey(NBT_CHANNELS, 10) ? root.getCompoundTag(NBT_CHANNELS)
                : new NBTTagCompound();
        NBTTagCompound mine = new NBTTagCompound();
        mine.setByteArray(NBT_SUMMARY_BYTES, summaryBytes.toByteArray());
        mine.setLong(NBT_SUMMARY_BYTES_COMPAT, clamp(summaryBytes));
        mine.setInteger(NBT_SUMMARY_TYPES, summaryTypes);
        perChannel.setTag(cellChannel.id(), mine);
        root.setTag(NBT_CHANNELS, perChannel);
        // The flat tags are the pre-multi-channel layout and are still read as "the item channel" by older
        // readers (and by our own diag command). Only the item channel view writes them, so a fluid persist
        // cannot stamp fluid numbers onto a field that readers take for item numbers.
        if (CellChannelRegistry.ITEM.equals(cellChannel.id())) {
            root.setByteArray(NBT_SUMMARY_BYTES, summaryBytes.toByteArray());
            root.setLong(NBT_SUMMARY_BYTES_COMPAT, clamp(summaryBytes));
            root.setInteger(NBT_SUMMARY_TYPES, summaryTypes);
        }
        stackTag.setTag(NBT_ROOT, root);
    }

    /**
     * Bytes the cell reports in its tooltip: the finite amounts plus {@code INFINITE_BYTES} per infinite
     * type -- the same accounting the 1.20 line uses in {@code updateSummary()}, so both versions show the
     * same number for the same content.
     */
    private BigInteger summaryBytes() {
        BigInteger bytes = BigInteger.ZERO;
        if (mode() == 1) {
            for (BigInteger amount : stocks().s1.values()) {
                bytes = bytes.add(amount);
            }
            return bytes;
        }
        if (mode() != 2) {
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
        return summaryOf(stack, CellChannelRegistry.ITEM);
    }

    /**
     * The summary of ONE channel of the cell, read from the cell item's NBT.
     *
     * <p>Why per channel: the cell serves several channels at once, so "how full is it" has one answer per
     * channel, and the tooltip lists all of them (sensei 2026-10-04: "can the drive slot show every channel?").
     * Channels are stored under {@code _ch/<channelId>}; cells written before that layout have only the flat
     * tags, which were always the item channel's numbers -- so those are read as the item channel and every
     * other channel reads zero until its view persists once (self-healing, no migration pass).
     */
    public static Summary summaryOf(ItemStack stack, String channelId) {
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
        NBTTagCompound counters = null;
        if (root.hasKey(NBT_CHANNELS, 10)) {
            NBTTagCompound perChannel = root.getCompoundTag(NBT_CHANNELS);
            if (perChannel.hasKey(channelId, 10)) {
                counters = perChannel.getCompoundTag(channelId);
            }
        }
        if (counters == null && CellChannelRegistry.ITEM.equals(channelId)) {
            counters = root;   // legacy layout: the flat tags are the item channel's
        }
        int types = counters == null ? 0 : counters.getInteger(NBT_SUMMARY_TYPES);
        BigInteger bytes = BigInteger.ZERO;
        if (counters != null) {
            bytes = counters.hasKey(NBT_SUMMARY_BYTES, 7)
                    ? new BigInteger(counters.getByteArray(NBT_SUMMARY_BYTES))
                    : BigInteger.valueOf(counters.getLong(NBT_SUMMARY_BYTES_COMPAT));
        }
        return new Summary(mode, workMode, threshold, types, bytes);
    }

    private void saveChanges() {
        writeSmallFields();
        CellWorldData worldData = CellWorldData.get();
        if (worldData == null) {
            AE2Addon.LOG.warn("[ae2addon] 没有可用的世界存档，元件改动只留在内存里（uuid={}）", uuid);
            // Still tell the network: the change is real even when it cannot be written down (client-side views
            // come here too, and they must not announce -- onCellChanged ignores null providers).
            announceChange();
            return;
        }
        worldData.put(uuid, data);
        if (container != null) {
            container.saveChanges(this);
        }
        announceChange();
    }

    /**
     * Tells the network what changed, without waiting for something else to rebuild the grid.
     *
     * <p>sensei's MeatballCraft run (2026-10-04) showed mode-2 "store = infinite" rows staying finite until the
     * cell was pulled out and put back, while the diag proved our record was already right -- the missing piece
     * was this notification.
     *
     * <p><b>Two shapes, and why the cheap one is the normal one</b> (2026-10-05). AE2 already posts the items
     * that physically move through our cell: the drive wraps our inventory in a {@code DriveWatcher}, and that
     * class calls {@code postAlterationOfStoredItems} itself (javap on AE2-UEL v0.56.7). What nothing posts is a
     * change in what the cell REPORTS that no movement caused -- a key turning infinite through promotion, a
     * toggle, a rule. So:
     * <ul>
     * <li>a mutation that named its key (item movement, one panel toggle) announces the difference of that key's
     *     reported amount, one map lookup, no listing, and nothing at all when the report did not move;</li>
     * <li>everything else (mode switch, threshold, rules, format) re-lists the cells -- expensive, so it is the
     *     fallback and the announcer rate-limits it.</li>
     * </ul>
     */
    private void announceChange() {
        boolean global = pendingGlobal;
        Map<IAEStack<?>, Long> before = new LinkedHashMap<>(pendingBefore);
        pendingGlobal = false;
        pendingBefore.clear();
        if (container == null) {
            return; // a GUI view: nothing to announce (and onCellChanged would ignore it anyway)
        }
        if (!global && !before.isEmpty()) {
            long infiniteReport = cellChannel.reportedInfinite();
            Map<IAEStack<?>, Long> deltas = new LinkedHashMap<>();
            for (Map.Entry<IAEStack<?>, Long> entry : before.entrySet()) {
                long after = reportFor(entry.getKey());
                long was = entry.getValue();
                if (after == was) {
                    continue; // this key's reported amount did not move
                }
                // ONLY changes that involve an infinite row are ours to announce:
                //   * a purely finite move is already accounted for -- the drive wraps our inventory in a
                //     DriveWatcher and that class posts the physical amount itself (javap, AE2-UEL v0.56.7), so
                //     posting it again here would double count it (store 64 -> the terminal reads 128);
                //   * a key turning infinite (promotion, 存入无限, a rule) or leaving the infinite set
                //     (cancel-infinite, mode switch) is something no one else knows about.
                // The amount posted is the CHANGE OF OUR OWN CONTRIBUTION (after - before), which is safe to add
                // now that INFINITE is sum-safe: two cells claiming the same key add up instead of wrapping, and
                // cancelling infinite takes back exactly what this cell claimed.
                if (after != infiniteReport && was != infiniteReport) {
                    continue;
                }
                Long already = deltas.get(entry.getKey());
                deltas.put(entry.getKey(), (already == null ? 0L : already) + (after - was));
            }
            if (!deltas.isEmpty()) {
                ChannelAnnouncer.onReportChanged(container, channel, cellStack, deltas);
            }
            return;
        }
        ChannelAnnouncer.onCellChanged(container, channel, cellStack);
    }

    /** Keys whose reported amount may have moved since the last save; see {@link #markChanged}. */
    private final Map<IAEStack<?>, Long> pendingBefore = new LinkedHashMap<>();

    /** Set when a change can move many keys at once and naming them is not possible. */
    private boolean pendingGlobal;

    /** Cap on tracked keys per save; past it the change is announced as a full pass instead. */
    private static final int MAX_PENDING_KEYS = 32;

    /**
     * Remembers what this cell reported for a key BEFORE the caller mutates it, so {@link #saveChanges()} can
     * announce the difference. Must be called before the mutation, on the normalised key.
     */
    private void markChanged(T key) {
        if (key == null || pendingGlobal) {
            return;
        }
        if (pendingBefore.size() >= MAX_PENDING_KEYS) {
            markGlobalChange();
            return;
        }
        if (!pendingBefore.containsKey(key)) {
            pendingBefore.put(key, reportFor(key));
        }
    }

    /** A change that can move many keys at once (mode switch, threshold, rules): announce as a full pass. */
    private void markGlobalChange() {
        pendingGlobal = true;
        pendingBefore.clear();
    }

    /**
     * The value this channel publishes for an infinite row ({@link CellChannel#reportedInfinite()}).
     *
     * <p>Per channel on purpose: AE2's aggregate list SUMS what the cells report, so a value that two infinite
     * cells both claim has to survive being added twice -- that is why essentia publishes
     * {@link #SAFE_INFINITE} while the item channel keeps {@link #INFINITE} (sensei's decision, 2026-10-04).
     */
    public long reportedInfinite() {
        return cellChannel.reportedInfinite();
    }

    /**
     * Whether the inventory behind a drive slot (our cell, possibly nested in AE2's wrappers) reports this key as
     * infinite right now.
     *
     * <p>Used by {@code MixinDriveWatcher}: the drive posts the amount it physically moved, which is meaningless
     * for a row the cell reports as infinite -- adding it wraps the row negative, taking it away shrinks the
     * number (sensei: "存入时数值溢出，取出时数值减少", 2026-10-05). Skipping that post at the source means the row
     * never moves in the first place.
     *
     * <p>Unwrapping is bounded and defensive: this runs inside AE2's drive path, so an unexpected shape must
     * simply answer "no" instead of throwing.
     */
    public static boolean reportsInfinite(appeng.api.storage.IMEInventory<?> inventory, IAEStack<?> key) {
        if (inventory == null || key == null) {
            return false;
        }
        Object current = inventory;
        for (int depth = 0; depth < 8; depth++) {
            if (!(current instanceof appeng.me.storage.MEInventoryHandler)) {
                break;
            }
            Object next = ((appeng.me.storage.MEInventoryHandler<?>) current).getInternal();
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        if (!(current instanceof ChannelCellCore)) {
            return false;
        }
        ChannelCellCore<?> core = (ChannelCellCore<?>) current;
        return core.reportFor(key) == core.reportedInfinite();
    }

    /**
     * What this cell reports for one key RIGHT NOW: the same number {@link #getAvailableItems} would put into the
     * network's list, or 0 when the key is not listed at all. One lookup, so the announcer can work per key.
     *
     * <p>Mode 2 lists the whitelist, the infinite set and live rule matches as infinite, and everything else at
     * its finite amount -- exactly the three sources and the "ca is not a source" rule of
     * {@link #infiniteKeys()}. Mode 3 lists everything as infinite.
     */
    public long reportFor(IAEStack<?> rawKey) {
        if (rawKey == null) {
            return 0L;
        }
        T key = lookupKey(castKey(rawKey));
        if (key == null) {
            return 0L;
        }
        if (mode() == 3) {
            return cellChannel.reportedInfinite();
        }
        if (mode() == 2) {
            if (data.wl.contains(key) || data.ul.contains(key) || ruleActive(key)) {
                return cellChannel.reportedInfinite();
            }
            BigInteger finite = stocks().s2.get(key);
            return finite == null ? 0L : clamp(finite);
        }
        BigInteger finite = stocks().s1.get(key);
        return finite == null ? 0L : clamp(finite);
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
        if (mutate) {
            // Before anything moves: what we report for this key right now. The announcer posts the difference
            // afterwards, which is the only part AE2's own accounting cannot know (see announceChange).
            markChanged(key);
        }
        // Quantity-locked entries swallow everything without recording it (1.20 L366/L375/L392).
        if (mode() == 2 && data.qtyLocked.contains(key)) {
            return true;
        }

        if (mode() == 3) {
            if (mutate) {
                data.m3.add(key);
            }
            return true;
        }

        if (mode() == 2) {
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
            if (workMode() == 2 && !data.blacklist.contains(key)) {
                // 存入无限: storing anything at all makes it infinite.
                if (mutate) {
                    stocks().ca.merge(key, amount, BigInteger::add);
                    data.ul.add(key);
                    // Save right here, not "whenever something else saves": this branch is the whole point of
                    // 存入无限, and without the save the new infinite row is announced only on the NEXT save
                    // (AE2 calls persist() on its own schedule). sensei's MeatballCraft run showed exactly that
                    // gap on 2026-10-05 -- the announcement landed minutes after the store, by which time the
                    // network's row had grown and the "0 -> infinite" difference overflowed it away. Saving
                    // here makes the change immediate and persistent, like the finite path below.
                    saveChanges();
                }
                return true;
            }
            if (workMode() == 3 && (data.wl.contains(key) || data.ul.contains(key))) {
                // 臻藏: already infinite, nothing to account for.
                return true;
            }
        }

        if (!mutate) {
            return true;
        }

        Map<IAEStack<?>, BigInteger> map = mode() == 1 ? stocks().s1 : stocks().s2;
        BigInteger total = map.merge(key, amount, BigInteger::add);

        // Blacklisted keys may be stored, but never promoted (1.20 L465-467).
        if (mode() == 2 && workMode() == 1 && !data.blacklist.contains(key)
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
        if (mutate) {
            // Same reason as in insertInternal: remember the reported amount first, announce the difference after.
            markChanged(key);
        }

        BigInteger taken;
        if (mode() == 2 && data.qtyLocked.contains(key)) {
            // Locked: never hand out more than what is recorded, and do not change the record
            // (1.20 L498-500 -- the pinned amount behaves the same in both directions).
            taken = extractFromMap(stocks().s2, key, requested, false);
        } else if (mode() == 3) {
            if (mutate) {
                data.m3.add(key);
            }
            taken = BigInteger.valueOf(Math.min(requested, INFINITE));
        } else if (mode() == 2 && (ruleActive(key) || data.ul.contains(key) || data.wl.contains(key))) {
            taken = BigInteger.valueOf(Math.min(requested, INFINITE));
        } else {
            taken = extractFromMap(mode() == 1 ? stocks().s1 : stocks().s2, key, requested, mutate);
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
        switch (mode()) {
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
     * Reports keys as infinite WITHOUT accumulating, publishing what this channel publishes for an infinite
     * row ({@link CellChannel#reportedInfinite()}).
     *
     * <p>rv6's {@code IItemList.add} merges by summing sizes, so when two unlimited cells report a truly huge
     * value into the same grid list the result overflows to a negative number -- the terminal then shows
     * negative stock and extraction fails. The 1.20 line hit exactly this and fixed it by taking the maximum
     * instead of adding. rv6 has no {@code set}, so we mutate the entry that {@code findPrecise} hands back.
     *
     * <p>The value itself is per channel for the same reason one level up: AE2's aggregate list sums whatever
     * the cells report. See {@link #SAFE_INFINITE} for the experiment that proved it.
     */
    private <S extends IAEStack<S>> void reportInfinite(IItemList<S> out, Iterable<S> keys) {
        long infinite = cellChannel.reportedInfinite();
        for (S key : keys) {
            S existing = out.findPrecise(key);
            if (existing == null) {
                S stack = key.copy();
                stack.setStackSize(infinite);
                out.add(stack);
            } else if (existing.getStackSize() < infinite) {
                existing.setStackSize(infinite);
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
    private <S extends IAEStack<S>> void reportMap(IItemList<S> out, Map<IAEStack<?>, BigInteger> map) {
        long infinite = cellChannel.reportedInfinite();
        for (Map.Entry<IAEStack<?>, BigInteger> entry : map.entrySet()) {
            long size = clamp(entry.getValue());
            if (size <= 0) {
                continue;
            }
            S key = castKey(entry.getKey());
            S existing = out.findPrecise(key);
            if (existing != null && existing.getStackSize() >= infinite) {
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
        // Identity ignores size (AEItemStack compares the shared stack, count forced to 1), and normalising keeps
        // stored keys uniform -- but it goes through the CHANNEL: a single-key resource must come back as its
        // canonical instance, not a copy (FluxStack overrides equals without hashCode, so a copy never matches;
        // that was self-test P2/P4, 2026-10-03).
        return input == null ? null : cellChannel.normalize(input);
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
        if (target == mode()) {
            return;
        }
        data.mode = target;
        saveChanges();
    }

    public int getWorkMode() {
        return workMode();
    }

    /**
     * Switching work mode promotes everything that already qualifies; it never demotes. Promotion is
     * the same atomic three-step action as on insert. Demotion is a deliberate user action
     * ({@link #cancelInfinite}).
     */
    public void setWorkMode(int newWorkMode) {
        int target = clampToRange(newWorkMode, 1, 3, 1);
        data.workMode = target;
        if (mode() == 2 && target != 3) {
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
        return threshold();
    }

    public void setThreshold(long value) {
        data.threshold = value > 0 ? value : DEFAULT_THRESHOLD;
        saveChanges();
    }

    public long thresholdFor(T key) {
        Long perItem = data.itemThr.get(lookupKey(key));
        return perItem != null && perItem > 0 ? perItem : threshold();
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

    /**
     * The non-saving core of {@link #addWhitelist} / {@link #removeWhitelist}.
     *
     * <p>Split out 2026-10-10 for the batch tab, which changes hundreds of keys at once and must persist them
     * ONCE (see {@link #applyInfiniteToSearch}). Behaviour is otherwise unchanged: same normalisation, same
     * {@code data.wl} set, same "did anything change" answer.
     */
    private boolean addWhitelistQuiet(T key) {
        return data.wl.add(lookupKey(key));
    }

    private boolean removeWhitelistQuiet(T key) {
        return data.wl.remove(lookupKey(key));
    }

    /**
     * 取消"手动标的无限"，**两种标记一起清**：白名单 {@code wl}（面板上的绿 W）与无限集 {@code ul}
     * （面板手动点出来的那种，没有标识）。
     *
     * <p>为什么必须两个都清（sensei 2026-10-10：「批量无限页的全部取消无限，对于手动面板记无限不适用
     * （即无绿 W 标识的无限状态）」）：批量取消以前只调 {@link #removeWhitelistQuiet}，
     * 而面板手动标无限写的是 {@code ul} ✗ ⇒ 那批键照样无限。两者本来就是分开存的两个集合
     * （面板标记还会把存量挪进「承诺」），所以取消时得一起清 ✓。
     *
     * <p>承诺量按 {@link #togglePanelInfinite} 的口径**排回有限存储** ✓：批量取消不能顺手把玩家记录的数量抹掉。
     */
    private boolean removeInfiniteQuiet(T key) {
        T k = lookupKey(key);
        boolean changed = data.wl.remove(k);
        changed |= data.ul.remove(k);
        if (changed) {
            BigInteger committed = stocks().ca.remove(k);
            if (committed != null && committed.signum() > 0) {
                stocks().s2.merge(k, committed, BigInteger::add);
            }
        }
        return changed;
    }

    public boolean addWhitelist(T key) {
        boolean changed = addWhitelistQuiet(key);
        if (changed) {
            saveChanges();
        }
        return changed;
    }

    public boolean removeWhitelist(T key) {
        boolean changed = removeWhitelistQuiet(key);
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
        if (mode() == 1) {
            BigInteger inS1 = stocks().s1.get(k);
            return inS1 == null ? total : inS1;
        }
        if (mode() != 2) {
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
        return String.format("mode()=%d wm=%d thr=%d wl=%d ul=%d s1=%d s2=%d ca=%d rej=%d/%d@%s uuid=%s",
                mode(), workMode(), threshold(), data.wl.size(), data.ul.size(), stocks().s1.size(), stocks().s2.size(),
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
        return mode() == 2 && ruleActive(lookupKey(key));
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
            T k = lookupKey(castKey(raw));
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
        if (mode() != 2 || (data.oreRules.isEmpty() && data.modRules.isEmpty())) {
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

    /**
     * The search index's id for a key, or {@code null} when even the channel cannot name it.
     *
     * <p>This is the third argument of {@link PinyinIndex#matches} -- the same string the panel's own filter
     * passes for an item ({@code minecraft:stone}). It is asked of the CHANNEL because only the channel knows
     * how to name its keys. A bridge mod's key must never be able to take the whole search down with it, hence
     * the catch (the same defensive shape {@code ItemCellChannel} uses for {@code getSubItems}).
     */
    private String searchIdOf(T key) {
        try {
            String id = cellChannel.registryId(key);
            return id == null || id.isEmpty() ? null : id;
        } catch (Throwable brokenBridge) {
            return null;
        }
    }

    /**
     * 「批量无限」页签的模糊搜索：在整个注册表上匹配，返回最多 {@code limit} 个键。
     *
     * <p><b>为什么要有这个方法</b>（2026-10-10，sensei 的需求）：面板原来的搜索框只过滤「已经存进元件的条目」
     * （客户端在 {@code filtered} 上过一遍 {@link PinyinIndex}），所以没存过的键根本搜不到。新页签要的是
     * 「在三千多个物品里找 iron_ingot」，那就只能由服务端拿 {@link #allRegistryKeys()} 自己扫一遍。
     *
     * <p>匹配口径与面板一致：{@link PinyinIndex#matches}(查询串, 显示名, 注册表 id)，所以全拼（shitou）、
     * 首字母（st）、中文名和 {@code minecraft:stone} 都能命中。
     *
     * <p><b>只读</b>：不动任何状态，也不落盘；匹配满 {@code limit} 条就立刻停手，所以"匹配很多的查询"比
     * "匹配很少的查询"更便宜。
     *
     * @param query 查询串，空 / null = 不过滤（返回注册表的前 {@code limit} 个键）
     * @param limit 最多返回多少条（&lt;= 0 时返回空表）
     */
    public List<IAEStack<?>> searchRegistry(String query, int limit) {
        List<IAEStack<?>> found = new ArrayList<>();
        if (limit <= 0) {
            return found;
        }
        String q = query == null ? "" : query.trim();
        for (T key : allRegistryKeys()) {
            if (found.size() >= limit) {
                break;
            }
            if (q.isEmpty() || PinyinIndex.matches(q, cellChannel.displayNameUntyped(key), searchIdOf(key))) {
                found.add(key);
            }
        }
        return found;
    }

    /**
     * 把「批量无限」页签搜到的键一次性全部标无限（或取消），返回真正改动的条数。
     *
     * <p><b>为什么不能直接循环调 {@link #addWhitelist}</b>（2026-10-10，sensei 的需求）：那两个方法每改一个键就
     * 调一次 {@link #saveChanges()}，一批几百条 = 几百次世界存档写入 + 几百次网格播报。这里改用不落盘的内部
     * 变体把整批改完，最后只落盘一次。
     *
     * <p>匹配口径与 {@link #searchRegistry} 完全一致（同一个查询串、同一个上限），所以"界面上列出来的那几条"
     * 就是这里真正会去改的那一批。
     *
     * @param add {@code true} = 全部标无限（加白名单），{@code false} = 全部取消（白名单与无限集一起清，
     *            见 {@link #removeInfiniteQuiet}：只去白名单会漏掉"面板手动记的无限"）
     * @return 实际改动的条数（本来就已经是目标状态的键不计入，所以没必要落盘时也不会白写一次）
     */
    public int applyInfiniteToSearch(String query, int limit, boolean add) {
        int changed = 0;
        for (IAEStack<?> raw : searchRegistry(query, limit)) {
            T key = castKey(raw);
            boolean modified = add ? addWhitelistQuiet(key) : removeInfiniteQuiet(key);
            if (modified) {
                changed++;
            }
        }
        if (changed > 0) {
            saveChanges();
        }
        return changed;
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
        if (mode() != 2) {
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
            // itemThr is a SHARED collection (every channel's per-key threshold lives in it), so a key of
            // another channel must be skipped BEFORE the cast. Without this guard the item channel tried to
            // normalise a ManaStack into an IAEItemStack and the whole state packet died with
            // ClassCastException nyonio.ae2.ManaStack -> IAEItemStack -- which is exactly what happened once
            // the mana channel's rows became clickable (sensei, 2026-10-10: "保存数量和锁定按钮弹不出来，
            // 输入数量后确认也没有效果" -- the exception killed sendState, so the client never saw the update).
            if (!isMine(entry.getKey())) {
                continue;
            }
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
        data.workMode = 1;
        data.threshold = DEFAULT_THRESHOLD;
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
        if (mode() == 1) {
            for (BigInteger amount : stocks().s1.values()) {
                total = total.add(amount);
            }
        } else if (mode() == 2) {
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
        switch (mode()) {
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
