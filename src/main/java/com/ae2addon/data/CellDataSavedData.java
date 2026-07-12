package com.ae2addon.data;

import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单元格存储数据的持久化方案 — WorldSavedData。
 * <p>
 * 数据按 UUID 索引保存到世界存档文件中，ItemStack 的 NBT 只保留 uuid、mode、threshold。
 * 这样即使存储了几千种物品，ItemStack 依然轻量，不会导致网络包溢出或 FPS 骤降。
 */
public class CellDataSavedData extends SavedData {

    private static final String DATA_NAME = "ae2addon_cell_data";

    /** 使用 ConcurrentHashMap 防止多线程并发修改导致 ConcurrentModificationException */
    private final Map<UUID, CellData> cells = new ConcurrentHashMap<>();

    // ── 获取实例 ──

    public static CellDataSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CellDataSavedData::load, CellDataSavedData::new, DATA_NAME);
    }

    // ── 数据操作 ──

    /** 获取或创建指定 UUID 的 CellData */
    public CellData getOrCreate(UUID uuid) {
        markAsDirtyIfNew(uuid);
        return cells.computeIfAbsent(uuid, k -> new CellData());
    }

    /** 获取指定 UUID 的 CellData，不存在返回 null */
    public CellData get(UUID uuid) {
        return cells.get(uuid);
    }

    /** 删除指定 UUID 的 CellData */
    public void remove(UUID uuid) {
        if (cells.remove(uuid) != null) setDirty();
    }

    /** 检查 UUID 是否存在 */
    public boolean has(UUID uuid) {
        return cells.containsKey(uuid);
    }

    /** 惰性 setDirty：只有真正创建新条目时才标记 */
    private void markAsDirtyIfNew(UUID uuid) {
        if (!cells.containsKey(uuid)) setDirty();
    }

    // ── NBT 序列化 ──

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        ListTag list = new ListTag();
        for (var entry : cells.entrySet()) {
            CompoundTag cellTag = new CompoundTag();
            cellTag.putUUID("uuid", entry.getKey());
            entry.getValue().save(cellTag);
            list.add(cellTag);
        }
        tag.put("cells", list);
        return tag;
    }

    public static CellDataSavedData load(CompoundTag tag) {
        CellDataSavedData data = new CellDataSavedData();
        if (tag.contains("cells", Tag.TAG_LIST)) {
            ListTag list = tag.getList("cells", Tag.TAG_COMPOUND);
            for (Tag t : list) {
                CompoundTag cellTag = (CompoundTag) t;
                UUID uuid = cellTag.getUUID("uuid");
                CellData cellData = CellData.load(cellTag);
                data.cells.put(uuid, cellData);
            }
        }
        return data;
    }

    // ══════════════════════════════════════════════
    //  CellData — 单个个单元格的数据
    // ══════════════════════════════════════════════

    public static class CellData {
        /** 无限物品在面板中显示的字节数 — 与合成 CPU 存储量一致 */
        public static final long INFINITE_BYTES = 300000000;

        /** BigInteger 存储，突破 Long.MAX_VALUE 限制 */
        public final Map<AEKey, BigInteger> s1 = new HashMap<>();
        public final Map<AEKey, BigInteger> s2 = new HashMap<>();
        public final Set<AEKey> wl = new HashSet<>();
        public final Set<AEKey> ul = new HashSet<>();
        public final Map<AEKey, Long> ca = new HashMap<>();
        /** Mode 3 已插入的物品（含 NBT 变体），跨存档持久化 */
        public final Set<AEKey> m3 = new HashSet<>();

        public void save(CompoundTag tag) {
            putBigIntMap(tag, "s1", s1);
            putBigIntMap(tag, "s2", s2);
            putSet(tag, "wl", wl);
            putSet(tag, "ul", ul);
            putLongMap(tag, "ca", ca);
            putSet(tag, "m3", m3);
        }

        public static CellData load(CompoundTag tag) {
            CellData data = new CellData();
            getBigIntMap(tag, "s1", data.s1);
            getBigIntMap(tag, "s2", data.s2);
            getSet(tag, "wl", data.wl);
            getSet(tag, "ul", data.ul);
            getLongMap(tag, "ca", data.ca);
            getSet(tag, "m3", data.m3);
            return data;
        }

        // ── 工具：统计摘要 ──

        public long getTotalBytes() {
            long total = 0;
            for (BigInteger v : s1.values()) {
                long add = v.min(BigInteger.valueOf(Long.MAX_VALUE - total)).longValue();
                total += add;
                if (total < 0) { total = Long.MAX_VALUE; break; }
            }
            for (BigInteger v : s2.values()) {
                long add = v.min(BigInteger.valueOf(Long.MAX_VALUE - total)).longValue();
                total += add;
                if (total < 0) { total = Long.MAX_VALUE; break; }
            }
            long infiniteCount = (long) wl.size() + (long) (ul.size() - countInBoth(wl, ul));
            if (infiniteCount > 0) {
                long add = infiniteCount * INFINITE_BYTES;
                total += add;
                if (total < 0) total = Long.MAX_VALUE;
            }
            return total;
        }

        public int getTypeCount() {
            // wl ⊆ ul，所以 ul.size() 已经包含了 wl，不重复计数
            return s1.size() + s2.size() + ul.size();
        }

        private static int countInBoth(Set<?> a, Set<?> b) {
            int c = 0;
            for (Object o : a) { if (b.contains(o)) c++; }
            return c;
        }

        // ── NBT 工具 ──

        /** 写 BigInteger map：以 byte array 格式存储 */
        private static void putBigIntMap(CompoundTag t, String k, Map<AEKey, BigInteger> m) {
            ListTag l = new ListTag();
            for (var e : m.entrySet()) {
                CompoundTag n = e.getKey().toTagGeneric();
                n.putByteArray("#", e.getValue().toByteArray());
                l.add(n);
            }
            t.put(k, l);
        }

        /**
         * 读 BigInteger map：兼容旧版 Long 格式和新版 byte array 格式。
         * 旧格式：e.contains("#", TAG_LONG)
         * 新格式：e.contains("#", TAG_BYTE_ARRAY)
         */
        private static void getBigIntMap(CompoundTag t, String k, Map<AEKey, BigInteger> m) {
            m.clear();
            if (!t.contains(k)) return;
            for (Tag tag : t.getList(k, Tag.TAG_COMPOUND)) {
                CompoundTag e = (CompoundTag) tag;
                AEKey key = AEKey.fromTagGeneric(e);
                if (key == null) continue;
                if (e.contains("#", Tag.TAG_BYTE_ARRAY)) {
                    m.put(key, new BigInteger(e.getByteArray("#")));
                } else if (e.contains("#", Tag.TAG_LONG)) {
                    // 旧版 Long 格式兼容
                    m.put(key, BigInteger.valueOf(e.getLong("#")));
                }
            }
        }

        /** 写 Long map：ca 等仍用 long 存储 */
        private static void putLongMap(CompoundTag t, String k, Map<AEKey, Long> m) {
            ListTag l = new ListTag();
            for (var e : m.entrySet()) {
                CompoundTag n = e.getKey().toTagGeneric();
                n.putLong("#", e.getValue());
                l.add(n);
            }
            t.put(k, l);
        }

        /** 读 Long map */
        private static void getLongMap(CompoundTag t, String k, Map<AEKey, Long> m) {
            m.clear();
            if (!t.contains(k)) return;
            for (Tag tag : t.getList(k, Tag.TAG_COMPOUND)) {
                CompoundTag e = (CompoundTag) tag;
                AEKey key = AEKey.fromTagGeneric(e);
                if (key != null) m.put(key, e.getLong("#"));
            }
        }

        private static void putSet(CompoundTag t, String k, Set<AEKey> s) {
            ListTag l = new ListTag();
            for (AEKey key : s) l.add(key.toTagGeneric());
            t.put(k, l);
        }

        private static void getSet(CompoundTag t, String k, Set<AEKey> s) {
            s.clear();
            if (!t.contains(k)) return;
            for (Tag tag : t.getList(k, Tag.TAG_COMPOUND)) {
                AEKey key = AEKey.fromTagGeneric((CompoundTag) tag);
                if (key != null) s.add(key);
            }
        }
    }
}
