package com.ae2addon.cell;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.ae2addon.AE2Addon;

import appeng.api.storage.data.IAEStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/**
 * P2/P4: everything that belongs to ONE cell, keyed in {@link com.ae2addon.data.CellWorldData} by the
 * cell's UUID.
 *
 * <p><b>Layout (sensei's 2026-10-03 decision: "storage separate, settings shared")</b>:
 * <ul>
 * <li><b>per channel</b> ({@link Stocks}): the counts -- mode 1 store, mode 2 store and the committed
 *     amounts. These are the only things that differ from one storage channel to the next.</li>
 * <li><b>shared</b>: the modes/threshold/rules and all the per-key collections (whitelist, infinite set,
 *     seen variants, per-key thresholds, blacklist, quantity locks, reject list, Touch-rule evidence). Keys
 *     carry their own channel identity, so one map can hold item and fluid keys without collisions; every
 *     reader that walks them has to filter to its own channel.</li>
 * </ul>
 *
 * <p>Lives in world saved data rather than in the cell's ItemStack because these maps can hold hundreds of
 * thousands of keys; the item NBT only carries the small routing fields (mode / workMode / threshold / uuid).
 *
 * <p><b>Save compatibility</b>: keys used to be stored without a channel marker, and the three count maps used
 * to sit at the top level. Both are read as the item channel, so an existing save needs no migration.
 *
 * <p>All counts are {@link BigInteger}: the 1.20 line learned this the hard way -- it originally kept the
 * "committed" amount in a {@code long} and clamped on write, so the very first write truncated a real stored
 * quantity down to 9.22e18.
 */
public class CellStorageData {

    /** The three count maps of ONE storage channel. */
    public static final class Stocks {
        /** mode 1 real storage. */
        public final Map<IAEStack<?>, BigInteger> s1 = new LinkedHashMap<>();
        /** mode 2 real storage (not yet upgraded to infinite). */
        public final Map<IAEStack<?>, BigInteger> s2 = new LinkedHashMap<>();
        /** Committed amounts: what an unlimited key must give back when its infinity is cancelled. */
        public final Map<IAEStack<?>, BigInteger> ca = new LinkedHashMap<>();

        public boolean isEmpty() {
            return s1.isEmpty() && s2.isEmpty() && ca.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // settings: SHARED and AUTHORITATIVE
    //
    // These used to live in each ChannelCellCore instance (read once from the cell item NBT). That broke as soon
    // as one cell spoke several channels: the drive keeps one inventory per CHANNEL alive, so switching to mode 3
    // only reached the instance rebuilt afterwards -- the other channels kept running mode 1 and the terminal
    // showed "only one storage type is infinite" (sensei, 2026-10-03).
    //
    // Now they live here, in the per-cell record every channel view shares; the cell item NBT keeps a copy for
    // the tooltip only (the client cannot read the world save).
    // ------------------------------------------------------------------
    public int mode = 1;
    public int workMode = 1;
    public long threshold = 65536L;

    /**
     * False until a view seeded these from the cell item NBT. Saves written before the settings moved here have
     * no mode/wm/thr in the world data, so the first view to attach copies them across once.
     */
    public boolean settingsSeeded = false;

    /** Seeds the settings from the cell item NBT (once), for cells saved before the move. */
    public void seedSettingsIfAbsent(int modeFromNbt, int workModeFromNbt, long thresholdFromNbt) {
        if (settingsSeeded) {
            return;
        }
        mode = modeFromNbt;
        workMode = workModeFromNbt;
        threshold = thresholdFromNbt;
        settingsSeeded = true;
    }

    private final Map<String, Stocks> stocks = new LinkedHashMap<>();

    /** @return this channel's count maps, created on demand. */
    public Stocks stocks(String channelId) {
        Stocks found = stocks.get(channelId);
        if (found == null) {
            found = new Stocks();
            stocks.put(channelId, found);
        }
        return found;
    }

    /** @return this channel's count maps, or {@code null} when this cell never touched that channel. */
    public Stocks stocksOrNull(String channelId) {
        return stocks.get(channelId);
    }

    public Map<String, Stocks> allStocks() {
        return Collections.unmodifiableMap(stocks);
    }

    // ------------------------------------------------------------------
    // shared settings + per-key collections (keys know their channel)
    // ------------------------------------------------------------------

    /** Per-key thresholds; a missing entry means "use the global threshold". */
    public final Map<IAEStack<?>, Long> itemThr = new LinkedHashMap<>();
    /** Whitelist. */
    public final Set<IAEStack<?>> wl = new LinkedHashSet<>();
    /** Keys that have been upgraded to infinite. */
    public final Set<IAEStack<?>> ul = new LinkedHashSet<>();
    /** mode 3: keys we have seen (so they can be listed). */
    public final Set<IAEStack<?>> m3 = new LinkedHashSet<>();

    /**
     * Reject list, ported from the 1.20 line: a three-state model per key.
     * {@code rejectOn} = explicitly rejected, {@code rejectOff} = explicitly NOT rejected (overrides
     * the default), anything else follows {@link #rejectDefault}.
     */
    public final Set<IAEStack<?>> rejectOn = new LinkedHashSet<>();
    public final Set<IAEStack<?>> rejectOff = new LinkedHashSet<>();
    public boolean rejectDefault;

    /**
     * Batch "infinite" rules. 1.20 matches item *tags* here, but 1.12.2 predates vanilla item tags
     * (they arrived in 1.13), so the equivalent is an OreDictionary name -- "ingotIron", "logWood", ...
     * and, by sensei's 2026-10-03 decision, ore rules are item-only while mod rules apply to every channel.
     */
    public final Set<String> oreRules = new LinkedHashSet<>();
    public final Set<String> modRules = new LinkedHashSet<>();
    /** ruleKey ("ore:x" / "mod:x") -&gt; mode: 1 = Instant, 2 = Touch. Absent means 1. */
    public final Map<String, Integer> ruleModes = new LinkedHashMap<>();
    /** Keys actually stored while a Touch rule matched them. */
    public final Set<IAEStack<?>> touched = new LinkedHashSet<>();
    /** Blacklisted keys can still be stored, but never become infinite. */
    public final Set<IAEStack<?>> blacklist = new LinkedHashSet<>();
    /** Keys whose stored amount is pinned: inserts are swallowed, extracts are capped. */
    public final Set<IAEStack<?>> qtyLocked = new LinkedHashSet<>();
    public boolean ruleInstant = true;

    private static final String K_STOCKS = "st";
    private static final String K_S1 = "s1";
    private static final String K_S2 = "s2";
    private static final String K_CA = "ca";
    private static final String K_IT = "it";
    private static final String K_WL = "w";
    private static final String K_UL = "u";
    private static final String K_M3 = "m3";
    private static final String K_REJECT_ON = "ro";
    private static final String K_REJECT_OFF = "rf";
    private static final String K_REJECT_DEFAULT = "rd";
    private static final String K_ORE_RULES = "or";
    private static final String K_MOD_RULES = "md";
    private static final String K_RULE_MODES = "rm";
    private static final String K_TOUCHED = "rt";
    private static final String K_BLACKLIST = "bl";
    private static final String K_QTY_LOCKED = "ql";
    private static final String K_SET_MODE = "setmode";
    private static final String K_SET_WM = "setwm";
    private static final String K_SET_THR = "setthr";
    private static final String K_SET_SEEDED = "setseed";

    private static final String K_RULE_INSTANT = "ri";
    private static final String K_KEY = "k";
    private static final String K_NUM = "n";
    private static final String K_NAME = "s";
    private static final String K_MODE = "m";
    /** Channel marker on a shared per-key entry; absent means item (pre-P4 saves). */
    private static final String K_CHANNEL = "c";

    /** Unknown channel ids are reported once, not once per key. */
    private static final Set<String> WARNED_CHANNELS = new java.util.HashSet<>();

    public boolean isEmpty() {
        for (Stocks one : stocks.values()) {
            if (!one.isEmpty()) {
                return false;
            }
        }
        return itemThr.isEmpty() && wl.isEmpty() && ul.isEmpty() && m3.isEmpty()
                && rejectOn.isEmpty() && rejectOff.isEmpty()
                && oreRules.isEmpty() && modRules.isEmpty() && blacklist.isEmpty() && qtyLocked.isEmpty();
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    public void writeToNbt(NBTTagCompound root) {
        NBTTagCompound stockRoot = new NBTTagCompound();
        for (Map.Entry<String, Stocks> entry : stocks.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            NBTTagCompound one = new NBTTagCompound();
            one.setTag(K_S1, writeCountMap(entry.getValue().s1));
            one.setTag(K_S2, writeCountMap(entry.getValue().s2));
            one.setTag(K_CA, writeCountMap(entry.getValue().ca));
            stockRoot.setTag(entry.getKey(), one);
        }
        root.setTag(K_STOCKS, stockRoot);
        root.setInteger(K_SET_MODE, mode);
        root.setInteger(K_SET_WM, workMode);
        root.setLong(K_SET_THR, threshold);
        root.setBoolean(K_SET_SEEDED, settingsSeeded);
        root.setTag(K_IT, writeChannelLongMap(itemThr));
        root.setTag(K_WL, writeChannelKeySet(wl));
        root.setTag(K_UL, writeChannelKeySet(ul));
        root.setTag(K_M3, writeChannelKeySet(m3));
        root.setTag(K_REJECT_ON, writeChannelKeySet(rejectOn));
        root.setTag(K_REJECT_OFF, writeChannelKeySet(rejectOff));
        root.setBoolean(K_REJECT_DEFAULT, rejectDefault);
        root.setTag(K_ORE_RULES, writeStringSet(oreRules));
        root.setTag(K_MOD_RULES, writeStringSet(modRules));
        root.setTag(K_RULE_MODES, writeStringIntMap(ruleModes));
        root.setTag(K_TOUCHED, writeChannelKeySet(touched));
        root.setTag(K_BLACKLIST, writeChannelKeySet(blacklist));
        root.setTag(K_QTY_LOCKED, writeChannelKeySet(qtyLocked));
        root.setBoolean(K_RULE_INSTANT, ruleInstant);
    }

    public void readFromNbt(NBTTagCompound root) {
        stocks.clear();
        itemThr.clear();
        wl.clear();
        ul.clear();
        m3.clear();
        rejectOn.clear();
        rejectOff.clear();
        oreRules.clear();
        modRules.clear();
        ruleModes.clear();
        touched.clear();
        blacklist.clear();
        qtyLocked.clear();

        if (root.hasKey(K_STOCKS, 10)) {
            NBTTagCompound stockRoot = root.getCompoundTag(K_STOCKS);
            for (String channelId : stockRoot.getKeySet()) {
                NBTTagCompound one = stockRoot.getCompoundTag(channelId);
                Stocks target = stocks(channelId);
                // The sub-compound's NAME is the channel: keys in there carry no marker, so the reader has to
                // be told which adapter parses them. Reading them as items (the old behaviour) silently dropped
                // every non-item key on load -- "after re-entering the save, everything except items is gone,
                // while the cell tooltip still shows the numbers" (sensei, 2026-10-04). The tooltip survived
                // because it reads the cell item's own NBT copy.
                readCountMap(one.getTagList(K_S1, 10), target.s1, channelId);
                readCountMap(one.getTagList(K_S2, 10), target.s2, channelId);
                readCountMap(one.getTagList(K_CA, 10), target.ca, channelId);
            }
        } else {
            // Pre-P4 layout: the three maps sat at the top level and only ever held item keys.
            Stocks legacy = stocks(CellChannelRegistry.ITEM);
            readCountMap(root.getTagList(K_S1, 10), legacy.s1, CellChannelRegistry.ITEM);
            readCountMap(root.getTagList(K_S2, 10), legacy.s2, CellChannelRegistry.ITEM);
            readCountMap(root.getTagList(K_CA, 10), legacy.ca, CellChannelRegistry.ITEM);
        }
        readChannelLongMap(root.getTagList(K_IT, 10), itemThr);
        readChannelKeySet(root.getTagList(K_WL, 10), wl);
        readChannelKeySet(root.getTagList(K_UL, 10), ul);
        readChannelKeySet(root.getTagList(K_M3, 10), m3);
        readChannelKeySet(root.getTagList(K_REJECT_ON, 10), rejectOn);
        readChannelKeySet(root.getTagList(K_REJECT_OFF, 10), rejectOff);
        rejectDefault = root.getBoolean(K_REJECT_DEFAULT);
        readStringSet(root.getTagList(K_ORE_RULES, 8), oreRules);
        readStringSet(root.getTagList(K_MOD_RULES, 8), modRules);
        readStringIntMap(root.getTagList(K_RULE_MODES, 10), ruleModes);
        readChannelKeySet(root.getTagList(K_TOUCHED, 10), touched);
        readChannelKeySet(root.getTagList(K_BLACKLIST, 10), blacklist);
        readChannelKeySet(root.getTagList(K_QTY_LOCKED, 10), qtyLocked);
        // Default true, matching 1.20: a fresh rule is Instant unless the player switches it.
        ruleInstant = !root.hasKey(K_RULE_INSTANT) || root.getBoolean(K_RULE_INSTANT);
        // Settings moved here from the cell item NBT (see the field block above). A record without them keeps
        // settingsSeeded == false so the first view seeds from the item once.
        mode = root.hasKey(K_SET_MODE) ? root.getInteger(K_SET_MODE) : 1;
        workMode = root.hasKey(K_SET_WM) ? root.getInteger(K_SET_WM) : 1;
        threshold = root.hasKey(K_SET_THR) ? root.getLong(K_SET_THR) : 65536L;
        settingsSeeded = root.getBoolean(K_SET_SEEDED);
    }

    // ------------------------------------------------------------------
    // plain string / string->int helpers (rules are stored by name, not by key)
    // ------------------------------------------------------------------

    private static NBTTagList writeStringSet(Set<String> set) {
        NBTTagList list = new NBTTagList();
        for (String name : set) {
            list.appendTag(new NBTTagString(name));
        }
        return list;
    }

    private static void readStringSet(NBTTagList list, Set<String> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            // TAG_String entries expose their value through getStringTagAt
            String name = list.getStringTagAt(i);
            if (name != null && !name.isEmpty()) {
                out.add(name);
            }
        }
    }

    private static NBTTagList writeStringIntMap(Map<String, Integer> map) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString(K_NAME, e.getKey());
            entry.setInteger(K_MODE, e.getValue() == null ? 1 : e.getValue());
            list.appendTag(entry);
        }
        return list;
    }

    private static void readStringIntMap(NBTTagList list, Map<String, Integer> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            String name = entry.getString(K_NAME);
            if (name != null && !name.isEmpty()) {
                out.put(name, entry.getInteger(K_MODE));
            }
        }
    }

    // ------------------------------------------------------------------
    // per-channel count maps (inside a channel's own compound: no marker needed)
    // ------------------------------------------------------------------

    private static NBTTagList writeCountMap(Map<IAEStack<?>, BigInteger> map) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<IAEStack<?>, BigInteger> e : map.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() <= 0) {
                continue;
            }
            list.appendTag(countEntry(e.getKey(), e.getValue().toString()));
        }
        return list;
    }

    private static void readCountMap(NBTTagList list, Map<IAEStack<?>, BigInteger> out, String channelId) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            // Explicit channel: entries in a channel compound carry no marker, and defaulting to item dropped
            // every non-item key (see the caller for the symptom this caused).
            IAEStack<?> key = readKeyEntry(entry, channelId);
            if (key == null) {
                continue;
            }
            try {
                BigInteger n = new BigInteger(entry.getString(K_NUM));
                if (n.signum() > 0) {
                    out.put(key, n);
                }
            } catch (NumberFormatException ignored) {
                // one broken entry must not cost the whole cell
            }
        }
    }

    private static NBTTagCompound countEntry(IAEStack<?> key, String number) {
        NBTTagCompound entry = new NBTTagCompound();
        NBTTagCompound keyTag = new NBTTagCompound();
        key.writeToNBT(keyTag);
        entry.setTag(K_KEY, keyTag);
        entry.setString(K_NUM, number);
        return entry;
    }

    // ------------------------------------------------------------------
    // shared per-key collections (each entry names its channel)
    // ------------------------------------------------------------------

    private static NBTTagList writeChannelKeySet(Set<IAEStack<?>> set) {
        NBTTagList list = new NBTTagList();
        for (IAEStack<?> key : set) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString(K_CHANNEL, channelIdOf(key));
            NBTTagCompound keyTag = new NBTTagCompound();
            key.writeToNBT(keyTag);
            entry.setTag(K_KEY, keyTag);
            list.appendTag(entry);
        }
        return list;
    }

    private static void readChannelKeySet(NBTTagList list, Set<IAEStack<?>> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            IAEStack<?> key = readKeyEntry(list.getCompoundTagAt(i), true);
            if (key != null) {
                out.add(key);
            }
        }
    }

    private static NBTTagList writeChannelLongMap(Map<IAEStack<?>, Long> map) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<IAEStack<?>, Long> e : map.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString(K_CHANNEL, channelIdOf(e.getKey()));
            NBTTagCompound keyTag = new NBTTagCompound();
            e.getKey().writeToNBT(keyTag);
            entry.setTag(K_KEY, keyTag);
            entry.setLong(K_NUM, e.getValue());
            list.appendTag(entry);
        }
        return list;
    }

    private static void readChannelLongMap(NBTTagList list, Map<IAEStack<?>, Long> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            IAEStack<?> key = readKeyEntry(entry, true);
            if (key == null) {
                continue;
            }
            long v = entry.getLong(K_NUM);
            if (v > 0) {
                out.put(key, v);
            }
        }
    }

    /** The channel a stored key belongs to, by its own identity -- no side table needed. */
    private static String channelIdOf(IAEStack<?> key) {
        CellChannel<?> channel = CellChannelRegistry.byStorage(key.getChannel());
        return channel == null ? CellChannelRegistry.ITEM : channel.id();
    }

    /**
     * Reads one stored key with an EXPLICIT channel -- for entries inside a channel's own compound, which carry
     * no marker because the compound already named the channel.
     */
    private static IAEStack<?> readKeyEntry(NBTTagCompound entry, String channelId) {
        CellChannel<?> channel = CellChannelRegistry.byId(channelId);
        if (channel == null) {
            warnMissingChannel(channelId);
            return null;
        }
        NBTTagCompound keyTag = entry.hasKey(K_KEY, 10) ? entry.getCompoundTag(K_KEY) : entry;
        IAEStack<?> key = channel.readKeyUntyped(keyTag);
        if (key != null) {
            // identity ignores stack size; normalise so stored keys are uniform
            key.setStackSize(1);
        }
        return key;
    }

    /**
     * Reads one stored key whose channel may be marked in the entry itself.
     *
     * @param withChannelMarker when true the entry may carry {@code c}; a missing marker means item, which is
     *                          what makes pre-P4 saves readable. Shared collections use this; a channel's own
     *                          stock maps use {@link #readKeyEntry(NBTTagCompound, String)} instead.
     */
    private static IAEStack<?> readKeyEntry(NBTTagCompound entry, boolean withChannelMarker) {
        String channelId = CellChannelRegistry.ITEM;
        if (withChannelMarker && entry.hasKey(K_CHANNEL, 8)) {
            channelId = entry.getString(K_CHANNEL);
        }
        return readKeyEntry(entry, channelId);
    }

    /** An unknown channel id means a bridge mod is gone: report it once, skip the keys, keep the cell. */
    private static void warnMissingChannel(String channelId) {
        if (WARNED_CHANNELS.add(channelId) && AE2Addon.LOG != null) {
            AE2Addon.LOG.warn("[ae2addon] 存档里有通道 {} 的数据，但该通道当前不可用（对应的桥接模组没装？），"
                    + "已跳过这些条目", channelId);
        }
    }
}
