package com.ae2addon.cell;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * P2: everything that belongs to ONE cell, keyed in {@link com.ae2addon.data.CellWorldData} by the
 * cell's UUID.
 *
 * <p>Lives in world saved data rather than in the cell's ItemStack because these maps can hold
 * hundreds of thousands of keys; the item NBT only carries the small routing fields
 * (mode / workMode / threshold / uuid).
 *
 * <p>All counts are {@link BigInteger}: the 1.20 line learned this the hard way -- it originally kept
 * the "committed" amount in a {@code long} and clamped on write, so the very first write truncated a
 * real stored quantity down to 9.22e18.
 */
public class CellStorageData {

    /** mode 1 real storage. */
    public final Map<IAEItemStack, BigInteger> s1 = new LinkedHashMap<>();
    /** mode 2 real storage (not yet upgraded to infinite). */
    public final Map<IAEItemStack, BigInteger> s2 = new LinkedHashMap<>();
    /** Committed amounts: what an unlimited item must give back when its infinity is cancelled. */
    public final Map<IAEItemStack, BigInteger> ca = new LinkedHashMap<>();
    /** Per-item thresholds; a missing entry means "use the global threshold". */
    public final Map<IAEItemStack, Long> itemThr = new LinkedHashMap<>();
    /** Whitelist. */
    public final Set<IAEItemStack> wl = new LinkedHashSet<>();
    /** Items that have been upgraded to infinite. */
    public final Set<IAEItemStack> ul = new LinkedHashSet<>();
    /** mode 3: items we have seen (so they can be listed). */
    public final Set<IAEItemStack> m3 = new LinkedHashSet<>();

    /**
     * Reject list, ported from the 1.20 line: a three-state model per key.
     * {@code rejectOn} = explicitly rejected, {@code rejectOff} = explicitly NOT rejected (overrides
     * the default), anything else follows {@link #rejectDefault}.
     */
    public final Set<IAEItemStack> rejectOn = new LinkedHashSet<>();
    public final Set<IAEItemStack> rejectOff = new LinkedHashSet<>();
    public boolean rejectDefault;

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
    private static final String K_KEY = "k";
    private static final String K_NUM = "n";

    public boolean isEmpty() {
        return s1.isEmpty() && s2.isEmpty() && ca.isEmpty() && wl.isEmpty() && ul.isEmpty() && m3.isEmpty()
                && rejectOn.isEmpty() && rejectOff.isEmpty();
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    public void writeToNbt(NBTTagCompound root) {
        root.setTag(K_S1, writeCountMap(s1));
        root.setTag(K_S2, writeCountMap(s2));
        root.setTag(K_CA, writeCountMap(ca));
        root.setTag(K_IT, writeLongMap(itemThr));
        root.setTag(K_WL, writeKeySet(wl));
        root.setTag(K_UL, writeKeySet(ul));
        root.setTag(K_M3, writeKeySet(m3));
        root.setTag(K_REJECT_ON, writeKeySet(rejectOn));
        root.setTag(K_REJECT_OFF, writeKeySet(rejectOff));
        root.setBoolean(K_REJECT_DEFAULT, rejectDefault);
    }

    public void readFromNbt(NBTTagCompound root) {
        s1.clear();
        s2.clear();
        ca.clear();
        itemThr.clear();
        wl.clear();
        ul.clear();
        m3.clear();
        rejectOn.clear();
        rejectOff.clear();
        readCountMap(root.getTagList(K_S1, 10), s1);
        readCountMap(root.getTagList(K_S2, 10), s2);
        readCountMap(root.getTagList(K_CA, 10), ca);
        readLongMap(root.getTagList(K_IT, 10), itemThr);
        readKeySet(root.getTagList(K_WL, 10), wl);
        readKeySet(root.getTagList(K_UL, 10), ul);
        readKeySet(root.getTagList(K_M3, 10), m3);
        readKeySet(root.getTagList(K_REJECT_ON, 10), rejectOn);
        readKeySet(root.getTagList(K_REJECT_OFF, 10), rejectOff);
        rejectDefault = root.getBoolean(K_REJECT_DEFAULT);
    }

    private static NBTTagList writeCountMap(Map<IAEItemStack, BigInteger> map) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<IAEItemStack, BigInteger> e : map.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() <= 0) {
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            NBTTagCompound key = new NBTTagCompound();
            e.getKey().writeToNBT(key);
            entry.setTag(K_KEY, key);
            entry.setString(K_NUM, e.getValue().toString());
            list.appendTag(entry);
        }
        return list;
    }

    private static void readCountMap(NBTTagList list, Map<IAEItemStack, BigInteger> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            IAEItemStack key = readKey(entry);
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

    private static NBTTagList writeLongMap(Map<IAEItemStack, Long> map) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<IAEItemStack, Long> e : map.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            NBTTagCompound entry = new NBTTagCompound();
            NBTTagCompound key = new NBTTagCompound();
            e.getKey().writeToNBT(key);
            entry.setTag(K_KEY, key);
            entry.setLong(K_NUM, e.getValue());
            list.appendTag(entry);
        }
        return list;
    }

    private static void readLongMap(NBTTagList list, Map<IAEItemStack, Long> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            IAEItemStack key = readKey(entry);
            if (key == null) {
                continue;
            }
            long v = entry.getLong(K_NUM);
            if (v > 0) {
                out.put(key, v);
            }
        }
    }

    private static NBTTagList writeKeySet(Set<IAEItemStack> set) {
        NBTTagList list = new NBTTagList();
        for (IAEItemStack key : set) {
            NBTTagCompound tag = new NBTTagCompound();
            key.writeToNBT(tag);
            list.appendTag(tag);
        }
        return list;
    }

    private static void readKeySet(NBTTagList list, Set<IAEItemStack> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            if (tag.hasKey(K_KEY, 10)) {
                tag = tag.getCompoundTag(K_KEY);
            }
            IAEItemStack key = AEItemStack.fromNBT(tag);
            if (key != null) {
                key.setStackSize(1);
                out.add(key);
            }
        }
    }

    private static IAEItemStack readKey(NBTTagCompound entry) {
        if (!entry.hasKey(K_KEY, 10)) {
            return null;
        }
        IAEItemStack key = AEItemStack.fromNBT(entry.getCompoundTag(K_KEY));
        if (key != null) {
            // identity ignores stack size; normalise so stored keys are uniform
            key.setStackSize(1);
        }
        return key;
    }
}
