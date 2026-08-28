package com.ae2addon.cell;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.ae2addon.data.CellDataSavedData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
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

    /** 无限物品真实数量（config infiniteItemAmount 热加载）：提取/显示上限 */
    public static volatile long INFINITE = com.ae2addon.config.AE2AddonConfig.infiniteItemAmount();

    /** 无限类型在面板中显示的字节数（config cellDisplayBytes 热加载） */
    public static volatile long INFINITE_BYTES = com.ae2addon.config.AE2AddonConfig.cellDisplayBytes();

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
    /** Mode 2 按 tag 批量无限（如 "minecraft:logs"） */
    private Set<String> tags = new HashSet<>();
    /** Mode 2 按 mod 批量无限（如 "gtceu"） */
    private Set<String> mods = new HashSet<>();
    /** 规则生效模式：true=立即全量无限，false=触碰（存入过）后无限 */
    private boolean ruleInstant = true;
    /** 触碰模式下记录过的匹配物品 */
    private Set<AEKey> ruleTouched = new HashSet<>();
    /** 黑名单：即使命中规则也禁止无限 */
    private Set<AEKey> blacklist = new HashSet<>();

    private static List<AEKey> ALL_KEYS_CACHE = null;
    private static Set<AEKey> ALL_KEYS_SET = null;
    private static int ALL_KEYS_VERSION = -1;
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
        tags.clear();
        tags.addAll(data.tags);
        mods.clear();
        mods.addAll(data.mods);
        ruleInstant = data.ruleInstant;
        ruleTouched.clear();
        ruleTouched.addAll(data.ruleTouched);
        blacklist.clear();
        blacklist.addAll(data.blacklist);
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
        data.tags.clear();
        data.tags.addAll(tags);
        data.mods.clear();
        data.mods.addAll(mods);
        data.ruleInstant = ruleInstant;
        data.ruleTouched.clear();
        data.ruleTouched.addAll(ruleTouched);
        data.blacklist.clear();
        data.blacklist.addAll(blacklist);
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
        BigInteger bytes = BigInteger.ZERO;
        int types = 0;
        long infiniteCount = 0;
        if (mode == 1) {
            for (BigInteger v : s1.values()) {
                bytes = bytes.add(v);
                types++;
            }
        } else if (mode == 2) {
            // 批量规则也算无限类型（跳过已在 wl/ul 的，避免重复计数）
            int ruleCount = 0;
            if (!tags.isEmpty() || !mods.isEmpty()) {
                if (ruleInstant) {
                    ensureAllKeysCache();
                    for (AEKey k : ALL_KEYS_CACHE) {
                        if (!wl.contains(k) && !ul.contains(k)
                                && matchesRule(k) && !blacklist.contains(k)) ruleCount++;
                    }
                } else {
                    for (AEKey k : ruleTouched) {
                        if (!wl.contains(k) && !ul.contains(k)
                                && matchesRule(k) && !blacklist.contains(k)) ruleCount++;
                    }
                }
            }
            if (workMode == 1) {
                for (BigInteger v : s2.values()) {
                    bytes = bytes.add(v);
                    types++;
                }
                infiniteCount = ul.size() + ruleCount;
                types += infiniteCount;
            } else if (workMode == 2) {
                infiniteCount = ul.size() + ruleCount;
                types += infiniteCount;
            } else {
                // wm3: wl + ul（去重）为无限，s2 里非无限的要统计
                for (BigInteger v : s2.values()) {
                    bytes = bytes.add(v);
                }
                infiniteCount = wl.size() + ul.size() - countWlInUl() + ruleCount;
                // s2 中非 wl/ul/规则命中的才算类型数
                int s2Types = 0;
                for (AEKey k : s2.keySet()) {
                    if (!wl.contains(k) && !ul.contains(k) && !ruleActive(k)) s2Types++;
                }
                types = (int) (infiniteCount + s2Types);
            }
        }
        if (infiniteCount > 0) {
            bytes = bytes.add(BigInteger.valueOf(infiniteCount).multiply(BigInteger.valueOf(INFINITE_BYTES)));
        }
        // 新格式：BigInteger byte array（突破 Long 上限）
        byte[] newBytes = bytes.toByteArray();
        byte[] oldBytes = tag.getByteArray("_b2");
        if (!java.util.Arrays.equals(oldBytes, newBytes)) {
            tag.putByteArray("_b2", newBytes);
        }
        // 兼容旧格式：long 截断
        long compat = bytes.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        if (tag.getLong("_b") != compat) {
            tag.putLong("_b", compat);
        }
        if (tag.getInt("_t") != types) {
            tag.putInt("_t", types);
        }
    }

    /** 读取已存储字节数（BigInteger，突破 Long 上限；兼容旧 long 格式） */
    public BigInteger getCachedBytes() {
        CompoundTag tag = cellItem.getOrCreateTag();
        if (tag.contains("_b2", 7)) { // TAG_BYTE_ARRAY
            return new BigInteger(tag.getByteArray("_b2"));
        }
        return BigInteger.valueOf(tag.getLong("_b"));
    }

    public int getCachedTypes() {
        return cellItem.getOrCreateTag().getInt("_t");
    }

    public long insert(AEKey what, long amount, Actionable act, IActionSource src) {
        return insert(what, amount, act, src, 0);
    }

    /** 内部 insert：depth 为解包深度（物质球套球最多解 2 层）。 */
    private long insert(AEKey what, long amount, Actionable act, IActionSource src, int depth) {
        if (amount <= 0) return 0;
        if (act != Actionable.MODULATE) return amount;

        // ── 物质球特例（2026-08-27 22:16 sensei 要求）：存入物质球 → 自动解包入库 ──
        // 物质球是取消无限时打包的临时容器（NBT 存 innerKey+amount），
        // 存入元件时直接解包，球本身不入库。
        if (depth < 2 && what instanceof AEItemKey ballKey
                && ballKey.getItem() == com.ae2addon.init.ModItems.MATTER_BALL.get()
                && ballKey.hasTag()) {
            var ballStack = ballKey.toStack(1);
            var innerKey = com.ae2addon.item.MatterBallItem.getKey(ballStack);
            long innerAmount = com.ae2addon.item.MatterBallItem.getAmount(ballStack);
            if (innerKey != null && innerAmount > 0) {
                insert(innerKey, innerAmount, act, src, depth + 1);
            }
            dataDirty = true;
            save();
            return amount; // 物质球本身不入库（已解包）
        }

        // ── 无限路径：直接收下，不占内部存储 ──
        if (mode == 3) {
            if (m3.add(what)) {
                // 修复：Mode 3 插入记录必须持久化，否则重启后丢失
                dataDirty = true;
                save();
            }
            return amount;
        }
        if (mode == 2) {
            if (matchesRule(what) && !blacklist.contains(what)) {
                // 触碰模式下记录一下，之后显示无限
                if (!ruleInstant) {
                    ruleTouched.add(what);
                }
                // 双轨合一：s2 中的存量并入承诺额度，避免同一物品被规则段和白名单段重复报告
                BigInteger existing = s2.remove(what);
                if (existing != null && existing.signum() > 0) {
                    ca.put(what, Math.max(ca.getOrDefault(what, 0L), clampToLong(existing)));
                }
                dataDirty = true;
                save();
                return amount;
            }
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
            if (ruleActive(what)) {
                return Math.min(Math.max(amount, 0), INFINITE);
            }
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
        // 版本化缓存：注册表条目数变化时自动重建（新模组/数据包加载后不脏读）
        int version = BuiltInRegistries.ITEM.keySet().size() + BuiltInRegistries.FLUID.keySet().size();
        if (ALL_KEYS_INIT && ALL_KEYS_VERSION == version) return;
        ALL_KEYS_INIT = true;
        ALL_KEYS_VERSION = version;
        List<AEKey> list = new ArrayList<>();
        Set<AEKey> set = new HashSet<>();

        Iterator<Item> itemIt = BuiltInRegistries.ITEM.iterator();
        while (itemIt.hasNext()) {
            Item item = itemIt.next();
            try {
                AEItemKey k = AEItemKey.of(item);
                if (k != null) {
                    list.add(k);
                    set.add(k);
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
                        set.add(k);
                    }
                }
            } catch (Exception e) {
                // skip
            }
        }

        ALL_KEYS_CACHE = list;
        ALL_KEYS_SET = set;
    }

    /**
     * 安全报告无限库存：多个无限盘/普通存储聚合到同一 KeyCounter 时，
     * add(Long.MAX) 累加会溢出成负数（模拟看到负库存 → 提取失败 → 缺料）。
     * 改用 set 取最大值：多盘叠加仍为 Long.MAX，不会溢出。
     */
    private static void addInfinite(KeyCounter out, AEKey k) {
        if (out.get(k) < INFINITE) {
            out.set(k, INFINITE);
        }
    }

    public void getAvailableStacks(KeyCounter out) {
        if (mode == 3) {
            ensureAllKeysCache();
            for (AEKey k : ALL_KEYS_CACHE) {
                addInfinite(out, k);
            }
            for (AEKey k : m3) {
                // m3 中的 NBT 变体可能不在注册表缓存里；已在缓存中的不重复报告（防溢出）
                if (!ALL_KEYS_SET.contains(k)) {
                    addInfinite(out, k);
                }
            }
            return;
        }

        if (mode == 2) {
            // 规则命中的物品（tags/mods 批量无限）——跳过已在 wl/ul 的，避免重复报告导致 Long 溢出
            if (!tags.isEmpty() || !mods.isEmpty()) {
                if (ruleInstant) {
                    // 立即模式：全量遍历所有注册物品
                    ensureAllKeysCache();
                    for (AEKey k : ALL_KEYS_CACHE) {
                        if (!wl.contains(k) && !ul.contains(k)
                                && matchesRule(k) && !blacklist.contains(k)) {
                            addInfinite(out, k);
                        }
                    }
                } else {
                    // 触碰模式：只报告存入过的匹配物品
                    for (AEKey k : ruleTouched) {
                        if (!wl.contains(k) && !ul.contains(k)
                                && matchesRule(k) && !blacklist.contains(k)) {
                            addInfinite(out, k);
                        }
                    }
                }
            }

            if (workMode == 3) {
                for (AEKey k : wl) {
                    addInfinite(out, k);
                }
                for (AEKey k : ul) {
                    if (!wl.contains(k)) {
                        addInfinite(out, k);
                    }
                }
                for (Map.Entry<AEKey, BigInteger> e : s2.entrySet()) {
                    AEKey k = e.getKey();
                    if (!wl.contains(k) && !ul.contains(k) && !ruleActive(k)) {
                        out.add(k, clampToLong(e.getValue()));
                    }
                }
                return;
            }

            for (AEKey k : wl) {
                addInfinite(out, k);
            }
            for (AEKey k : ul) {
                if (!wl.contains(k)) {
                    addInfinite(out, k);
                }
            }

            if (workMode == 1) {
                for (Map.Entry<AEKey, BigInteger> e : s2.entrySet()) {
                    AEKey k = e.getKey();
                    if (!wl.contains(k) && !ul.contains(k) && !ruleActive(k)) {
                        out.add(k, clampToLong(e.getValue()));
                    }
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
        if (mode == 1 || mode == 3) {
            // 无限制存储 / 全类型无限：全收
            return true;
        }
        if (mode == 2) {
            if (workMode == 1 || workMode == 2) {
                // 阈值 / 存入无限：需要收下物品才能升级为无限，全收
                return true;
            }
            // 臻藏模式：只对白名单/已无限/规则命中的物品宣称首选，
            // 其他物品留给网络中的其他存储，避免抢走不该收的
            return wl.contains(what) || ul.contains(what) || ruleActive(what);
        }
        return true;
    }

    public Component getDescription() {
        String[] n = {"", "gui.ae2addon.mode.unlimited", "gui.ae2addon.mode.custom", "gui.ae2addon.mode.all"};
        return Component.translatable("gui.ae2addon.cell.name", Component.translatable(n[mode]));
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

    // ── tags / mods 批量无限规则 ──

    public Set<String> getTags() {
        return tags;
    }

    public Set<String> getMods() {
        return mods;
    }

    /** 添加 tag 规则（如 "minecraft:logs"） */
    public boolean addTagRule(String tag) {
        if (tag == null || tag.isBlank()) return false;
        String trimmed = tag.trim();
        if (tags.add(trimmed)) {
            dataDirty = true;
            save();
            return true;
        }
        return false;
    }
    /** 移除 tag 规则 */
    public boolean removeTagRule(String tag) {
        if (tags.remove(tag)) {
            dataDirty = true;
            save();
            return true;
        }
        return false;
    }

    /** 添加 mod 规则（如 "gtceu"） */
    public boolean addModRule(String mod) {
        if (mod == null || mod.isBlank()) return false;
        String trimmed = mod.trim();
        if (mods.add(trimmed)) {
            dataDirty = true;
            save();
            return true;
        }
        return false;
    }

    /** 移除 mod 规则 */
    public boolean removeModRule(String mod) {
        if (mods.remove(mod)) {
            dataDirty = true;
            save();
            return true;
        }
        return false;
    }

    /** 规则生效模式：true=立即全量无限，false=触碰（存入过）后无限 */
    public void setRuleInstant(boolean instant) {
        if (ruleInstant == instant) return;
        ruleInstant = instant;
        dataDirty = true;
        save();
    }

    public boolean isRuleInstant() {
        return ruleInstant;
    }

    /** 该 AEKey 是否命中任意 tag/mod 规则（Mode 2 专用） */
    private boolean matchesRule(AEKey key) {
        if (tags.isEmpty() && mods.isEmpty()) return false;
        if (key instanceof AEItemKey itemKey) {
            // 2026-08-27 修复：带 NBT 的变体不参与 tag/mod 无限规则。
            // 否则存入带 NBT 物品会被无限路径吞掉（return amount 不存内部）→
            // 原始带 NBT 物品消失，只剩虚拟无限（sensei 实测 22:14：
            // Mode2 tags/mods 规则下带 NBT 物品存入后消失）。
            // 带 NBT 物品走正常存储（s2 累加），NBT 完整保留。
            if (itemKey.hasTag()) return false;
            Item item = itemKey.getItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (mods.contains(id.getNamespace())) return true;
            for (String tag : tags) {
                TagKey<Item> tagKey = TagKey.create(Registries.ITEM, new ResourceLocation(tag));
                if (item.builtInRegistryHolder().is(tagKey)) return true;
            }
        } else if (key instanceof AEFluidKey fluidKey) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluidKey.getFluid());
            if (mods.contains(id.getNamespace())) return true;
            for (String tag : tags) {
                TagKey<Fluid> tagKey = TagKey.create(Registries.FLUID, new ResourceLocation(tag));
                if (fluidKey.getFluid().builtInRegistryHolder().is(tagKey)) return true;
            }
        }
        return false;
    }

    /** 规则是否对某 key 生效：立即模式全部命中，触碰模式需触碰过；黑名单永远排除 */
    private boolean ruleActive(AEKey key) {
        if (blacklist.contains(key)) return false;
        if (!matchesRule(key)) return false;
        return ruleInstant || ruleTouched.contains(key);
    }

    /** 是否在黑名单中 */
    public boolean isBlacklisted(AEKey key) {
        return blacklist.contains(key);
    }

    /** 切换黑名单状态，返回切换后是否在黑名单 */
    public boolean toggleBlacklist(AEKey key) {
        if (blacklist.contains(key)) {
            blacklist.remove(key);
            dataDirty = true;
            save();
            return false;
        }
        blacklist.add(key);
        // 从触碰集合里也去掉（黑名单物品不该再显示无限）
        ruleTouched.remove(key);
        dataDirty = true;
        save();
        return true;
    }

    public Set<AEKey> getBlacklist() {
        return blacklist;
    }

    /** Mode 2 是否命中规则（供外部判断） */
    public boolean isInfiniteByRule(AEKey key) {
        return mode == 2 && ruleActive(key);
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
