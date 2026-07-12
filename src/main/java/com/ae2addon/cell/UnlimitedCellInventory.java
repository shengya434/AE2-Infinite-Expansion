package com.ae2addon.cell;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.ae2addon.data.CellDataSavedData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.math.BigInteger;
import java.util.*;

/**
 * 万能无限存储元件的核心逻辑实现。
 * <p>
 * 三模式驱动：
 * - Mode 1: 无限制存储
 * - Mode 2: 自定义无限（白名单 + 阈值 + 臻藏）
 * - Mode 3: 全类型无限
 */
public class UnlimitedCellInventory implements StorageCell {

    public static final long INFINITE = Long.MAX_VALUE; // 9223372036854775807
    public static final long INFINITE_BYTES = 300000000; // 无限类型在面板中显示的字节数

    private final ItemStack cellItem;
    private final ISaveProvider saveProvider;
    private UUID uuid;
    private int mode = 1;
    private int workMode = 1;
    private long thr = 65536L;
    /** 内部存储：BigInteger 可超过 Long.MAX_VALUE */
    private Map<AEKey, BigInteger> s1 = new HashMap<>();
    private Map<AEKey, BigInteger> s2 = new HashMap<>();
    private Set<AEKey> wl = new HashSet<>();
    private Set<AEKey> ul = new HashSet<>();
    /** 承诺额度：升级为无限时的记录数（用 long 够用） */
    private Map<AEKey, Long> ca = new HashMap<>();
    private Set<AEKey> m3 = new HashSet<>();

    private static List<AEKey> ALL_KEYS_CACHE = null;
    private static boolean ALL_KEYS_INIT = false;

    private boolean dataDirty = false;

    public UnlimitedCellInventory(ItemStack cellItem, ISaveProvider saveProvider) {
        this.cellItem = cellItem;
        this.saveProvider = saveProvider;
        load();
    }

    private void load() {
        CompoundTag tag = cellItem.getOrCreateTag();
        mode = tag.getInt("umode");
        if (mode < 1 || mode > 3) {
            mode = 1;
        }
        workMode = tag.getInt("wm");
        if (workMode < 1 || workMode > 3) {
            workMode = 1;
        }
        thr = tag.getLong("thr");
        if (thr <= 0) {
            thr = 65536L;
        }
        if (tag.hasUUID("uuid")) {
            uuid = tag.getUUID("uuid");
        } else if (hasOldNbtData(tag)) {
            uuid = UUID.randomUUID();
            tag.putUUID("uuid", uuid);
            migrateFromOldNbt(tag);
        } else {
            uuid = UUID.randomUUID();
            tag.putUUID("uuid", uuid);
        }
        loadFromSavedData();
    }

    private boolean hasOldNbtData(CompoundTag tag) {
        return tag.contains("s1", 9) || tag.contains("s2", 9) || tag.contains("w", 9) || tag.contains("u", 9);
    }

    private void migrateFromOldNbt(CompoundTag tag) {
        ServerLevel level = getOverworld();
        if (level == null) return;
        CellDataSavedData savedData = CellDataSavedData.get(level);
        CellDataSavedData.CellData data = savedData.getOrCreate(uuid);
        getMapFromNbt(tag, "s1", data.s1);
        getMapFromNbt(tag, "s2", data.s2);
        getSetFromNbt(tag, "w", data.wl);
        getSetFromNbt(tag, "u", data.ul);
        getMapFromNbtLong(tag, "sa", data.ca);
        savedData.setDirty();
        tag.remove("s1");
        tag.remove("s2");
        tag.remove("w");
        tag.remove("u");
        tag.remove("sa");
        copyFromCellData(data);
    }

    private void loadFromSavedData() {
        ServerLevel level = getOverworld();
        if (level == null) return;
        CellDataSavedData savedData = CellDataSavedData.get(level);
        CellDataSavedData.CellData data = savedData.get(uuid);
        if (data != null) {
            copyFromCellData(data);
        }
    }

    private void copyFromCellData(CellDataSavedData.CellData data) {
        s1.clear();
        s1.putAll(data.s1);
        s2.clear();
        s2.putAll(data.s2);
        wl.clear();
        wl.addAll(data.wl);
        ul.clear();
        ul.addAll(data.ul);
        ca.clear();
        ca.putAll(data.ca);
        m3.clear();
        m3.addAll(data.m3);
    }

    private void save() {
        if (!dataDirty) return;
        dataDirty = false;
        ServerLevel level = getOverworld();
        if (level == null) return;
        CellDataSavedData savedData = CellDataSavedData.get(level);
        CellDataSavedData.CellData data = savedData.getOrCreate(uuid);
        data.s1.clear();
        data.s1.putAll(s1);
        data.s2.clear();
        data.s2.putAll(s2);
        data.wl.clear();
        data.wl.addAll(wl);
        data.ul.clear();
        data.ul.addAll(ul);
        data.ca.clear();
        data.ca.putAll(ca);
        data.m3.clear();
        data.m3.addAll(m3);
        savedData.setDirty();
        updateSummary();
        if (saveProvider != null) {
            saveProvider.saveChanges();
        }
    }

    /** wl 中有多少也在 ul 中的（用于去重计数） */
    private int countWlInUl() {
        int c = 0;
        for (AEKey k : wl) { if (ul.contains(k)) c++; }
        return c;
    }

    /** 从 BigInteger 安全截取 long 值（上限 Long.MAX_VALUE） */
    private static long clampToLong(BigInteger val) {
        return val.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    private void updateSummary() {
        CompoundTag tag = cellItem.getOrCreateTag();
        long bytes = 0L;
        int types = 0;
        long infiniteCount = 0;
        if (mode == 1) {
            for (BigInteger v : s1.values()) {
                bytes += v.min(BigInteger.valueOf(Long.MAX_VALUE - bytes)).longValue();
                if (bytes < 0) bytes = Long.MAX_VALUE;
                types++;
            }
        } else if (mode == 2) {
            if (workMode == 1) {
                for (BigInteger v : s2.values()) {
                    bytes += v.min(BigInteger.valueOf(Long.MAX_VALUE - bytes)).longValue();
                    if (bytes < 0) bytes = Long.MAX_VALUE;
                    types++;
                }
                infiniteCount = ul.size();
                types += infiniteCount;
            } else if (workMode == 2) {
                infiniteCount = ul.size();
                types += infiniteCount;
            } else {
                // wm3: wl + ul（去重）为无限，s2 里非无限的要统计
                for (BigInteger v : s2.values()) {
                    bytes += v.min(BigInteger.valueOf(Long.MAX_VALUE - bytes)).longValue();
                    if (bytes < 0) bytes = Long.MAX_VALUE;
                }
                infiniteCount = wl.size() + ul.size() - countWlInUl();
                // s2 中非 wl/ul 的才算类型数
                int s2Types = 0;
                for (AEKey k : s2.keySet()) {
                    if (!wl.contains(k) && !ul.contains(k)) s2Types++;
                }
                types = (int) (infiniteCount + s2Types);
            }
        }
        if (infiniteCount > 0) {
            long addBytes = infiniteCount * INFINITE_BYTES;
            bytes += addBytes;
            if (bytes < 0) bytes = Long.MAX_VALUE;
        }
        if (tag.getLong("_b") != bytes) {
            tag.putLong("_b", bytes);
        }
        if (tag.getInt("_t") != types) {
            tag.putInt("_t", types);
        }
    }

    public long getCachedBytes() {
        return cellItem.getOrCreateTag().getLong("_b");
    }

    public int getCachedTypes() {
        return cellItem.getOrCreateTag().getInt("_t");
    }

    public long insert(AEKey what, long amount, Actionable act, IActionSource src) {
        if (amount <= 0) return 0;
        if (act != Actionable.MODULATE) return amount;

        // ── 无限路径：直接收下，不占内部存储 ──
        if (mode == 3) {
            m3.add(what);
            return amount;
        }
        if (mode == 2 && workMode == 2) {
            if (!ul.contains(what)) ul.add(what);
            dataDirty = true;
            save();
            return amount;
        }
        if (mode == 2 && workMode == 3 && (wl.contains(what) || ul.contains(what))) {
            return amount;
        }

        // ── 非无限路径：用 BigInteger 累加，永不溢出 ──
        Map<AEKey, BigInteger> map = (mode == 1) ? s1 : s2;
        BigInteger biAmount = BigInteger.valueOf(amount);
        map.merge(what, biAmount, BigInteger::add);
        dataDirty = true;

        if (mode == 2 && workMode == 1) {
            // 检查是否要升级为无限
            BigInteger total = map.get(what);
            if (total.compareTo(BigInteger.valueOf(thr)) >= 0) {
                ca.put(what, clampToLong(total));
                ul.add(what);
                map.remove(what);
            }
        }

        save();
        return amount;
    }

    /** 从 BigInteger map 中安全提取，返回 long（上限 Long.MAX_VALUE） */
    private long extractFromMap(Map<AEKey, BigInteger> map, AEKey what, long amount, Actionable act) {
        BigInteger avail = map.getOrDefault(what, BigInteger.ZERO);
        if (avail.signum() <= 0) return 0;
        long ext = Math.min(Math.max(amount, 0), clampToLong(avail));
        if (act == Actionable.MODULATE) {
            BigInteger remaining = avail.subtract(BigInteger.valueOf(ext));
            if (remaining.signum() <= 0) {
                map.remove(what);
            } else {
                map.put(what, remaining);
            }
            dataDirty = true;
            save();
        }
        return ext;
    }

    public long extract(AEKey what, long amount, Actionable act, IActionSource src) {
        if (amount <= 0) return 0;

        if (mode == 3) {
            if (act == Actionable.MODULATE) m3.add(what);
            return Math.min(Math.max(amount, 0), INFINITE);
        }

        if (mode == 2) {
            if (workMode == 3) {
                if (wl.contains(what) || ul.contains(what)) return Math.min(Math.max(amount, 0), INFINITE);
                return extractFromMap(s2, what, amount, act);
            } else if (workMode == 2) {
                if (ul.contains(what) || wl.contains(what)) {
                    return Math.min(Math.max(amount, 0), INFINITE);
                }
                return 0;
            } else {
                if (ul.contains(what) || wl.contains(what)) {
                    return Math.min(Math.max(amount, 0), INFINITE);
                }
            }
        }

        return extractFromMap((mode == 1) ? s1 : s2, what, amount, act);
    }

    private static void ensureAllKeysCache() {
        if (ALL_KEYS_INIT) return;
        ALL_KEYS_INIT = true;
        List<AEKey> list = new ArrayList<>();

        Iterator<Item> itemIt = BuiltInRegistries.ITEM.iterator();
        while (itemIt.hasNext()) {
            Item item = itemIt.next();
            try {
                AEItemKey k = AEItemKey.of(item);
                if (k != null) {
                    list.add(k);
                }
            } catch (Exception e) {
                // skip
            }
        }

        Iterator<Fluid> fluidIt = BuiltInRegistries.FLUID.iterator();
        while (fluidIt.hasNext()) {
            Fluid fluid = fluidIt.next();
            try {
                if (fluid != Fluids.EMPTY) {
                    AEFluidKey k = AEFluidKey.of(fluid);
                    if (k != null) {
                        list.add(k);
                    }
                }
            } catch (Exception e) {
                // skip
            }
        }

        ALL_KEYS_CACHE = list;
    }

    public void getAvailableStacks(KeyCounter out) {
        if (mode == 3) {
            ensureAllKeysCache();
            for (AEKey k : ALL_KEYS_CACHE) {
                out.add(k, INFINITE);
            }
            for (AEKey k : m3) {
                out.add(k, INFINITE);
            }
            return;
        }

        if (mode == 2) {
            if (workMode == 3) {
                for (AEKey k : wl) {
                    out.add(k, INFINITE);
                }
                for (AEKey k : ul) {
                    if (!wl.contains(k)) {
                        out.add(k, INFINITE);
                    }
                }
                for (Map.Entry<AEKey, BigInteger> e : s2.entrySet()) {
                    if (!wl.contains(e.getKey()) && !ul.contains(e.getKey())) {
                        out.add(e.getKey(), clampToLong(e.getValue()));
                    }
                }
                return;
            }

            for (AEKey k : wl) {
                out.add(k, INFINITE);
            }
            for (AEKey k : ul) {
                if (!wl.contains(k)) {
                    out.add(k, INFINITE);
                }
            }

            if (workMode == 1) {
                for (Map.Entry<AEKey, BigInteger> e : s2.entrySet()) {
                    out.add(e.getKey(), clampToLong(e.getValue()));
                }
            }
            return;
        }

        // mode == 1
        for (Map.Entry<AEKey, BigInteger> e : s1.entrySet()) {
            out.add(e.getKey(), clampToLong(e.getValue()));
        }
    }

    public boolean isPreferredStorageFor(AEKey what, IActionSource src) {
        return true;
    }

    public Component getDescription() {
        String[] n = {"", "无限制", "自定义", "全类型"};
        return Component.literal("§5万能无限 [" + n[mode] + "]");
    }

    public CellState getStatus() {
        if (s1.isEmpty() && s2.isEmpty() && wl.isEmpty() && ul.isEmpty()) {
            return CellState.ABSENT;
        }
        return CellState.TYPES_FULL;
    }

    public double getIdleDrain() {
        return 2.0;
    }

    public boolean canFitInsideCell() {
        return true;
    }

    public void persist() {
        save();
    }

    public void setMode(int m) {
        mode = m;
        cellItem.getOrCreateTag().putInt("umode", m);
        updateSummary();
        save();
    }

    public int getMode() {
        return mode;
    }

    public void setThreshold(long t) {
        thr = Math.max(1, Math.min(t, INFINITE));
        cellItem.getOrCreateTag().putLong("thr", thr);
        save();
    }

    public long getThreshold() {
        return thr;
    }

    public int getWorkMode() {
        return workMode;
    }

    public void setWorkMode(int newWm) {
        if (mode != 2) return;
        int newWm2 = Math.max(1, Math.min(3, newWm));
        if (workMode == newWm2) return;

        switch (newWm2) {
            case 1: // → 阈值模式：s2 中达阈值或已在 wl 的 → 升无限
                Iterator<Map.Entry<AEKey, BigInteger>> it1 = s2.entrySet().iterator();
                while (it1.hasNext()) {
                    Map.Entry<AEKey, BigInteger> entry = it1.next();
                    if (entry.getValue().signum() <= 0) { it1.remove(); continue; }
                    AEKey key = entry.getKey();
                    if (wl.contains(key) || entry.getValue().compareTo(BigInteger.valueOf(thr)) >= 0) {
                        ca.put(key, clampToLong(entry.getValue()));
                        ul.add(key);
                        it1.remove();
                    }
                }
                break;

            case 2: // → 存入无限：s2 中所有有数量的 → 升无限
                Iterator<Map.Entry<AEKey, BigInteger>> it2 = s2.entrySet().iterator();
                while (it2.hasNext()) {
                    Map.Entry<AEKey, BigInteger> entry = it2.next();
                    if (entry.getValue().signum() > 0
                            && !wl.contains(entry.getKey()) && !ul.contains(entry.getKey())) {
                        ca.put(entry.getKey(), clampToLong(entry.getValue()));
                        ul.add(entry.getKey());
                        it2.remove();
                    }
                }
                break;

            case 3: // → 臻藏模式：s2 中在 wl 或 ul 的 → 升无限
                Iterator<Map.Entry<AEKey, BigInteger>> it3 = s2.entrySet().iterator();
                while (it3.hasNext()) {
                    Map.Entry<AEKey, BigInteger> entry = it3.next();
                    if (entry.getValue().signum() <= 0) { it3.remove(); continue; }
                    AEKey key = entry.getKey();
                    if (wl.contains(key) || ul.contains(key)) {
                        ca.put(key, clampToLong(entry.getValue()));
                        ul.add(key);
                        it3.remove();
                    }
                }
                break;
        }

        workMode = newWm2;
        cellItem.getOrCreateTag().putInt("wm", newWm2);
        updateSummary();
        dataDirty = true;
        save();
    }

    public void addWl(AEKey key) {
        if (wl.contains(key)) return;

        AEKey plainKey = stripNbt(key);
        if (plainKey != null && !plainKey.equals(key)) {
            BigInteger s2Amount = s2.remove(plainKey);
            if (s2Amount != null && s2Amount.signum() > 0) {
                ca.put(key, clampToLong(s2Amount));
            }
            ul.remove(plainKey);
            wl.remove(plainKey);
        } else {
            BigInteger s2Amount = s2.get(key);
            if (s2Amount != null && s2Amount.signum() > 0) {
                ca.put(key, clampToLong(s2Amount));
            }
        }

        wl.add(key);
        ul.add(key);
        s2.remove(key);
        dataDirty = true;
        save();
    }

    private static AEKey stripNbt(AEKey key) {
        if (key instanceof AEItemKey) {
            AEItemKey itemKey = (AEItemKey) key;
            ItemStack stack = itemKey.toStack();
            if (stack.hasTag()) {
                AEItemKey plain = AEItemKey.of(stack.getItem());
                if (plain != null && !plain.equals(itemKey)) {
                    return plain;
                }
            }
        }
        return null;
    }

    public void removeWl(AEKey key) {
        wl.remove(key);
        ul.remove(key);
        dataDirty = true;
        save();
    }

    public Set<AEKey> getWl() {
        return wl;
    }

    public Map<AEKey, BigInteger> getS2() {
        return s2;
    }

    public Set<AEKey> getUl() {
        return ul;
    }

    public UUID getUuid() {
        return uuid;
    }

    public List<PanelItem> getPanelItems() {
        List<PanelItem> items = new ArrayList<>();
        if (mode != 2) return items;

        if (workMode == 3) {
            for (AEKey k : wl) {
                items.add(new PanelItem(k, INFINITE, true));
            }
            for (AEKey k : ul) {
                if (!wl.contains(k)) {
                    items.add(new PanelItem(k, INFINITE, true));
                }
            }
            for (Map.Entry<AEKey, BigInteger> e : s2.entrySet()) {
                if (!wl.contains(e.getKey()) && !ul.contains(e.getKey())) {
                    items.add(new PanelItem(e.getKey(), clampToLong(e.getValue()), false));
                }
            }
            return items;
        }

        for (AEKey k : wl) {
            items.add(new PanelItem(k, INFINITE, true));
        }
        for (AEKey k : ul) {
            if (!wl.contains(k)) {
                items.add(new PanelItem(k, INFINITE, true));
            }
        }
        if (workMode == 1) {
            for (Map.Entry<AEKey, BigInteger> e : s2.entrySet()) {
                if (!wl.contains(e.getKey()) && !ul.contains(e.getKey())) {
                    items.add(new PanelItem(e.getKey(), clampToLong(e.getValue()), false));
                }
            }
        }

        return items;
    }

    public boolean togglePanelInfinite(AEKey key) {
        if (ul.contains(key) || wl.contains(key)) {
            ul.remove(key);
            wl.remove(key);
            ca.remove(key);
            dataDirty = true;
            save();
            return false;
        }

        BigInteger amount = s2.getOrDefault(key, BigInteger.ZERO);
        if (amount.signum() > 0) {
            ca.put(key, clampToLong(amount));
            s2.remove(key);
        }

        ul.add(key);
        dataDirty = true;
        save();
        return true;
    }

    public long getCommitedAmount(AEKey key) {
        return ca.getOrDefault(key, thr).longValue();
    }

    public boolean hasCommitedAmount(AEKey key) {
        return ca.containsKey(key);
    }

    // Internal helpers

    public static ServerLevel getOverworld() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld();
    }

    private static void getMapFromNbt(CompoundTag tag, String key, Map<AEKey, BigInteger> map) {
        map.clear();
        if (!tag.contains(key)) return;
        for (Tag t : tag.getList(key, 10)) {
            CompoundTag ct = (CompoundTag) t;
            AEKey k = AEKey.fromTagGeneric(ct);
            if (k != null) {
                map.put(k, BigInteger.valueOf(ct.getLong("#")));
            }
        }
    }

    /** ca 等 Long map 的旧 NBT 迁移 */
    private static void getMapFromNbtLong(CompoundTag tag, String key, Map<AEKey, Long> map) {
        map.clear();
        if (!tag.contains(key)) return;
        for (Tag t : tag.getList(key, 10)) {
            CompoundTag ct = (CompoundTag) t;
            AEKey k = AEKey.fromTagGeneric(ct);
            if (k != null) {
                map.put(k, ct.getLong("#"));
            }
        }
    }

    private static void getSetFromNbt(CompoundTag tag, String key, Set<AEKey> set) {
        set.clear();
        if (!tag.contains(key)) return;
        for (Tag t : tag.getList(key, 10)) {
            AEKey k = AEKey.fromTagGeneric((CompoundTag) t);
            if (k != null) {
                set.add(k);
            }
        }
    }

    public static class PanelItem {
        public final AEKey key;
        public final long amount;
        public final boolean isInfinite;
        public final long bytes;

        public PanelItem(AEKey key, long amount, boolean isInfinite) {
            this(key, amount, isInfinite, 0L);
        }

        public PanelItem(AEKey key, long amount, boolean isInfinite, long bytes) {
            this.key = key;
            this.amount = amount;
            this.isInfinite = isInfinite;
            this.bytes = bytes;
        }
    }

}
