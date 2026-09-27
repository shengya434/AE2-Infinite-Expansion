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
    /** 单物品阈值，优先于全局 thr。 */
    private Map<AEKey, Long> itemThr = new HashMap<>();
    /** 内部存储：BigInteger 可超过 Long.MAX_VALUE */
    private Map<AEKey, BigInteger> s1 = new HashMap<>();
    private Map<AEKey, BigInteger> s2 = new HashMap<>();
    private Set<AEKey> wl = new HashSet<>();
    private Set<AEKey> ul = new HashSet<>();
    /**
     * 承诺额度：升级为无限时记录的真实数量。
     * <p>
     * ⚠ 2026-09-19（sensei：「Mode 2 取消无限排出数量也是 9.2E，即使真实存储量大得多」）：
     * 原来这里是 {@code Map<AEKey, Long>} 且写入时 `clampToLong` → **第一刀就把真实数量
     * 截成 Long.MAX（9.22e18）**，面板显示与物质球打包都只是照抄这个假数字。
     * 现在改成 BigInteger：与 s1/s2 同一精度，取消无限时排出的是**真实数量**。
     */
    private Map<AEKey, BigInteger> ca = new HashMap<>();
    private Set<AEKey> m3 = new HashSet<>();
    /** Mode 2 按 tag 批量无限（如 "minecraft:logs"） */
    private Set<String> tags = new HashSet<>();
    /** Mode 2 按 mod 批量无限（如 "gtceu"） */
    private Set<String> mods = new HashSet<>();
    /** tag/mod 每条规则的无限模式（1=立即，2=触碰） */
    private Map<String, Integer> ruleModes = new HashMap<>();
    /** 规则生效模式：true=立即全量无限，false=触碰（存入过）后无限 */
    private boolean ruleInstant = true;
    /** 触碰模式下记录过的匹配物品 */
    private Set<AEKey> ruleTouched = new HashSet<>();
    /** 黑名单：即使命中规则也禁止无限 */
    private Set<AEKey> blacklist = new HashSet<>();
    /** 数量被锁定的条目。 */
    private Set<AEKey> qtyLocked = new HashSet<>();

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
        itemThr.clear();
        itemThr.putAll(data.itemThr);
        m3.clear();
        m3.addAll(data.m3);
        tags.clear();
        tags.addAll(data.tags);
        mods.clear();
        mods.addAll(data.mods);
        ruleModes.clear();
        ruleModes.putAll(data.ruleModes);
        ruleInstant = data.ruleInstant;
        ruleTouched.clear();
        ruleTouched.addAll(data.ruleTouched);
        blacklist.clear();
        blacklist.addAll(data.blacklist);
        qtyLocked.clear();
        qtyLocked.addAll(data.qtyLocked);
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
        data.itemThr.clear();
        data.itemThr.putAll(itemThr);
        data.m3.clear();
        data.m3.addAll(m3);
        data.tags.clear();
        data.tags.addAll(tags);
        data.mods.clear();
        data.mods.addAll(mods);
        data.ruleModes.clear();
        data.ruleModes.putAll(ruleModes);
        data.ruleTouched.clear();
        data.ruleTouched.addAll(ruleTouched);
        data.blacklist.clear();
        data.blacklist.addAll(blacklist);
        data.qtyLocked.clear();
        data.qtyLocked.addAll(qtyLocked);
        savedData.setDirty();
        updateSummary();
        if (saveProvider != null) {
            saveProvider.saveChanges();
        }
    }

    /**
     * 一键格式化：销毁本元件 mode1 + mode2 的全部数据。
     * 保留 mode3 数据和 uuid，使当前存档条目被清空后的数据覆盖。
     */
    public void wipeAllData() {
        s1.clear(); s2.clear(); wl.clear(); ul.clear(); ca.clear(); itemThr.clear();
        tags.clear(); mods.clear(); ruleModes.clear(); ruleTouched.clear(); blacklist.clear();
        qtyLocked.clear();
        // mode3 的 m3 不清
        ruleInstant = true;
        workMode = 1;
        thr = 65536L;
        CompoundTag t = cellItem.getOrCreateTag();
        t.putLong("thr", thr);
        t.putInt("wm", workMode);
        // 摘要计数回到初始值，save() 会调用 updateSummary 重算
        t.remove("_b"); t.remove("_b2"); t.remove("_t");
        dataDirty = true;
        save();
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
                boolean scanCache = hasInstantRule();
                if (scanCache) {
                    ensureAllKeysCache();
                    for (AEKey k : ALL_KEYS_CACHE) {
                        if (!wl.contains(k) && !ul.contains(k) && !blacklist.contains(k)
                                && !qtyLocked.contains(k) && !s2.containsKey(k)
                                && ruleAllowsInfinite(k)) ruleCount++;
                    }
                }
                for (AEKey k : ruleTouched) {
                    if ((!scanCache || !ALL_KEYS_SET.contains(k)) && !wl.contains(k) && !ul.contains(k)
                            && !blacklist.contains(k) && !qtyLocked.contains(k) && !s2.containsKey(k)
                            && ruleAllowsInfinite(k)) ruleCount++;
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
                for (BigInteger v : s2.values()) {
                    bytes = bytes.add(v);
                    types++;
                }
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
        if (amount <= 0) return 0;
        // 锁定条目：收下但不上账（2026-09-27 sensei）—— 来源侧正常扣除，本条目数量被钉死不变。
        // TODO: “禁止存入（拒收）”以后单开一个通用设置，不与锁定绑定。
        if (mode == 2 && qtyLocked.contains(what)) return amount;
        return insert(what, amount, act, src, 0);
    }

    /** 内部 insert：depth 为解包深度（物质球套球最多解 2 层）。 */
    private long insert(AEKey what, long amount, Actionable act, IActionSource src, int depth) {
        if (amount <= 0) return 0;
        // 锁定条目：收下但不上账（2026-09-27 sensei）—— 来源侧正常扣除，本条目数量被钉死不变。
        // TODO: “禁止存入（拒收）”以后单开一个通用设置，不与锁定绑定。
        if (mode == 2 && qtyLocked.contains(what)) return amount;
        insertBI(what, BigInteger.valueOf(amount), act, src, depth);
        // 原语义：MODULATE 收下全额；非 MODULATE 也返回 amount（调用方据此判断"可收"）
        return amount;
    }

    /**
     * 入库内核（**BigInteger**，真实数量可远超 Long.MAX）。
     * <p>
     * 2026-09-19：把原 {@code insert} 的实体抽到这里，{@link #insert}（long 入口）与
     * 物质球解包（可能是天文数字的真实数量）**共用同一份语义**，
     * 避免"解包走一条简化路径"导致模式 3 / 规则无限 / 升级判定被漏掉。
     *
     * @param depth 解包深度（物质球套球最多解 2 层）
     */
    private void insertBI(AEKey what, BigInteger amount, Actionable act, IActionSource src, int depth) {
        if (what == null || amount == null || amount.signum() <= 0) return;
        // 锁定条目：收下但不上账（2026-09-27 sensei）—— 来源侧正常扣除，本条目数量被钉死不变。
        // TODO: “禁止存入（拒收）”以后单开一个通用设置，不与锁定绑定。
        if (mode == 2 && qtyLocked.contains(what)) return;
        if (act != Actionable.MODULATE) return;

        // ── 物质球特例（2026-08-27 22:16 sensei 要求）：存入物质球 → 自动解包入库 ──
        // 物质球是取消无限时打包的临时容器（NBT 存 innerKey+amount），
        // 存入元件时直接解包，球本身不入库。
        if (depth < 2 && what instanceof AEItemKey ballKey
                && ballKey.getItem() == com.ae2addon.init.ModItems.MATTER_BALL.get()
                && ballKey.hasTag()) {
            var ballStack = ballKey.toStack(1);
            AEKey innerKey = com.ae2addon.item.MatterBallItem.getKey(ballStack);
            // ⚠ BigInteger：解开物质球时按真实数量入库（可能远超 Long.MAX）
            BigInteger innerAmount = com.ae2addon.item.MatterBallItem.getAmount(ballStack);
            if (innerKey != null && innerAmount.signum() > 0) {
                insertBI(innerKey, innerAmount, act, src, depth + 1);
            }
            dataDirty = true;
            save();
            return; // 物质球本身不入库（已解包）
        }

        // ── 无限路径：直接收下，不占内部存储 ──
        if (mode == 3) {
            if (m3.add(what)) {
                // 修复：Mode 3 插入记录必须持久化，否则重启后丢失
                dataDirty = true;
                save();
            }
            return;
        }
        if (mode == 2) {
            if (!blacklist.contains(what) && matchesRule(what)) {
                // 记录存入过的匹配物品，供触碰模式使用。
                ruleTouched.add(what);
                // 双轨合一：s2 中的存量并入承诺额度，避免同一物品被规则段和白名单段重复报告
                BigInteger existing = s2.remove(what);
                if (existing != null && existing.signum() > 0) {
                    // ⚠ 取较大值（BigInteger，不截断）
                    ca.merge(what, existing, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                }
                // ⚠ 2026-09-27 修复：规则命中时也要记下本次真实存入量，取消无限时按承诺额度吐回。
                ca.merge(what, amount, BigInteger::add);
                dataDirty = true;
                save();
                return;
            }
        }
        // 黑名单：无法无限（2026-09-27 sensei：阈值/存入无限模式也要拦）
        if (mode == 2 && workMode == 2 && !blacklist.contains(what)) {
            // ⚠ 2026-09-27 修复（sensei：面板取消无限时取不出东西）：原来只 ul.add、不记 ca，
            //   togglePanelInfinite 算出 committed=0 就什么都不吐。确实存进来的量应计入承诺额度。
            ca.merge(what, amount, BigInteger::add);
            if (!ul.contains(what)) ul.add(what);
            dataDirty = true;
            save();
            return;
        }
        if (mode == 2 && workMode == 3 && (wl.contains(what) || ul.contains(what))) {
            return;
        }

        // ── 非无限路径：用 BigInteger 累加，永不溢出 ──
        Map<AEKey, BigInteger> map = (mode == 1) ? s1 : s2;
        map.merge(what, amount, BigInteger::add);
        dataDirty = true;

        if (mode == 2 && workMode == 1) {
            // 检查是否要升级为无限
            BigInteger total = map.get(what);
            // 黑名单：无法无限（2026-09-27 sensei：阈值/存入无限模式也要拦）
            if (!blacklist.contains(what) && total != null
                    && total.compareTo(BigInteger.valueOf(thresholdFor(what))) >= 0) {
                ca.put(what, total);   // ⚠ 2026-09-19：不再 clampToLong（真实数量要留住）
                ul.add(what);
                map.remove(what);
            }
        }

        save();
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
        if (mode == 2 && qtyLocked.contains(what)) {
            return Math.min(amount, clampToLong(s2.getOrDefault(what, BigInteger.ZERO)));
        }

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
                return extractFromMap(s2, what, amount, act);
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
                boolean scanCache = hasInstantRule();
                if (scanCache) {
                    ensureAllKeysCache();
                    for (AEKey k : ALL_KEYS_CACHE) {
                        if (!wl.contains(k) && !ul.contains(k) && !blacklist.contains(k)
                                && !qtyLocked.contains(k) && !s2.containsKey(k)
                                && ruleAllowsInfinite(k)) addInfinite(out, k);
                    }
                }
                // 触碰物品在全量扫描时只补缓存外的，避免重复报告。
                for (AEKey k : ruleTouched) {
                    if ((!scanCache || !ALL_KEYS_SET.contains(k)) && !wl.contains(k) && !ul.contains(k)
                            && !blacklist.contains(k) && !qtyLocked.contains(k) && !s2.containsKey(k)
                            && ruleAllowsInfinite(k)) {
                        addInfinite(out, k);
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

            if (workMode == 1 || workMode == 2) {
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

    /** 该物品实际生效的阈值：单物品优先，否则全局。 */
    public long thresholdFor(AEKey key) {
        Long v = itemThr.get(key);
        return v != null && v > 0 ? v : thr;
    }

    /** value <= 0 清除单物品阈值，回落全局。 */
    public void setItemThreshold(AEKey key, long value) {
        if (key == null) return;
        if (value <= 0) itemThr.remove(key);
        else itemThr.put(key, value);
        dataDirty = true;
        save();
    }

    public Map<AEKey, Long> itemThresholds() { return itemThr; }

    /** 直接设定「元件内数量」；原无限条目先退回有限存储。 */
    public void setStoredQuantity(AEKey key, BigInteger q, boolean lock) {
        if (key == null || q == null) return;
        ul.remove(key);
        wl.remove(key);
        ca.remove(key);
        Map<AEKey, BigInteger> map = mode == 1 ? s1 : s2;
        if (q.signum() <= 0) map.remove(key);
        else map.put(key, q);
        if (lock) qtyLocked.add(key);
        else qtyLocked.remove(key);
        dataDirty = true;
        save();
    }

    public boolean isQuantityLocked(AEKey key) { return qtyLocked.contains(key); }

    public Set<AEKey> quantityLockedKeys() { return qtyLocked; }

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
                    if (!qtyLocked.contains(key) && (wl.contains(key) || entry.getValue().compareTo(BigInteger.valueOf(thresholdFor(key))) >= 0)) {
                        ca.put(key, entry.getValue());   // ⚠ 不再 clampToLong
                        ul.add(key);
                        it1.remove();
                    }
                }
                break;

            case 2: // → 存入无限：s2 中所有有数量的 → 升无限
                Iterator<Map.Entry<AEKey, BigInteger>> it2 = s2.entrySet().iterator();
                while (it2.hasNext()) {
                    Map.Entry<AEKey, BigInteger> entry = it2.next();
                    if (entry.getValue().signum() > 0 && !qtyLocked.contains(entry.getKey())
                            && !wl.contains(entry.getKey()) && !ul.contains(entry.getKey())) {
                        ca.put(entry.getKey(), entry.getValue());   // ⚠ 不再 clampToLong
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
                    if (!qtyLocked.contains(key) && (wl.contains(key) || ul.contains(key))) {
                        ca.put(key, entry.getValue());   // ⚠ 不再 clampToLong
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
        if (qtyLocked.contains(key)) return;
        if (wl.contains(key)) return;

        AEKey plainKey = stripNbt(key);
        if (plainKey != null && qtyLocked.contains(plainKey)) return;
        if (plainKey != null && !plainKey.equals(key)) {
            BigInteger s2Amount = s2.remove(plainKey);
            if (s2Amount != null && s2Amount.signum() > 0) {
                ca.put(key, s2Amount);   // ⚠ 不再 clampToLong
            }
            ul.remove(plainKey);
            wl.remove(plainKey);
        } else {
            BigInteger s2Amount = s2.get(key);
            if (s2Amount != null && s2Amount.signum() > 0) {
                ca.put(key, s2Amount);   // ⚠ 不再 clampToLong
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

    public static String ruleKey(boolean isTag, String name) {
        return (isTag ? "tag:" : "mod:") + name;
    }

    public int getRuleMode(String ruleKey) {
        return ruleModes.getOrDefault(ruleKey, 1);
    }

    private boolean hasInstantRule() {
        for (String tag : tags) if (getRuleMode(ruleKey(true, tag)) == 1) return true;
        for (String mod : mods) if (getRuleMode(ruleKey(false, mod)) == 1) return true;
        return false;
    }

    public void setRuleMode(String ruleKey, int mode) {
        if (!ruleKey.startsWith("tag:") && !ruleKey.startsWith("mod:")) return;
        boolean isTag = ruleKey.startsWith("tag:");
        String name = ruleKey.substring(4);
        if (!(isTag ? tags : mods).contains(name)) return;
        int normalized = mode == 2 ? 2 : 1;
        if (getRuleMode(ruleKey) == normalized) return;
        ruleModes.put(ruleKey, normalized);
        dataDirty = true;
        save();
    }

    /** 添加 tag 规则（如 "minecraft:logs"） */
    public boolean addTagRule(String tag) {
        if (tag == null || tag.isBlank()) return false;
        String trimmed = tag.trim();
        if (tags.add(trimmed)) {
            ruleModes.put(ruleKey(true, trimmed), 1);
            dataDirty = true;
            save();
            return true;
        }
        return false;
    }
    /** 移除 tag 规则 */
    public boolean removeTagRule(String tag) {
        if (tags.remove(tag)) {
            ruleModes.remove(ruleKey(true, tag));
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
            ruleModes.put(ruleKey(false, trimmed), 1);
            dataDirty = true;
            save();
            return true;
        }
        return false;
    }

    /** 移除 mod 规则 */
    public boolean removeModRule(String mod) {
        if (mods.remove(mod)) {
            ruleModes.remove(ruleKey(false, mod));
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
            if (mods.contains(BuiltInRegistries.ITEM.getKey(itemKey.getItem()).getNamespace())) return true;
            for (String tag : tags) {
                if (matchesTag(key, tag)) return true;
            }
        } else if (key instanceof AEFluidKey fluidKey) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluidKey.getFluid());
            if (mods.contains(id.getNamespace())) return true;
            for (String tag : tags) {
                if (matchesTag(key, tag)) return true;
            }
        }
        return false;
    }

    private boolean matchesTag(AEKey key, String tag) {
        if (key instanceof AEItemKey itemKey) {
            TagKey<Item> tagKey = TagKey.create(Registries.ITEM, new ResourceLocation(tag));
            return itemKey.getItem().builtInRegistryHolder().is(tagKey);
        }
        if (key instanceof AEFluidKey fluidKey) {
            TagKey<Fluid> tagKey = TagKey.create(Registries.FLUID, new ResourceLocation(tag));
            return fluidKey.getFluid().builtInRegistryHolder().is(tagKey);
        }
        return false;
    }

    /** 任一命中规则为立即则无限；全是触碰时需存入过。黑名单由调用方判断。 */
    public boolean ruleAllowsInfinite(AEKey key) {
        if (tags.isEmpty() && mods.isEmpty()) return false;
        String namespace;
        if (key instanceof AEItemKey itemKey) {
            if (itemKey.hasTag()) return false;
            namespace = BuiltInRegistries.ITEM.getKey(itemKey.getItem()).getNamespace();
        } else if (key instanceof AEFluidKey fluidKey) {
            namespace = BuiltInRegistries.FLUID.getKey(fluidKey.getFluid()).getNamespace();
        } else return false;
        boolean matched = mods.contains(namespace);
        if (matched && getRuleMode(ruleKey(false, namespace)) == 1) return true;
        for (String tag : tags) {
            if (matchesTag(key, tag)) {
                matched = true;
                if (getRuleMode(ruleKey(true, tag)) == 1) return true;
            }
        }
        return matched && ruleTouched.contains(key);
    }

    /** 规则是否对某 key 生效：立即模式全部命中，触碰模式需触碰过；黑名单永远排除 */
    private boolean ruleActive(AEKey key) {
        if (blacklist.contains(key) || qtyLocked.contains(key) || s2.containsKey(key)) return false;
        return ruleAllowsInfinite(key);
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
        // 黑名单只阻止之后升级为无限；已在 ul 里的条目保持原样。
        // TODO: 若需处理已有无限条目，另行确定其承诺额度和存量的迁移规则。
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
                    // ⚠ 2026-09-19：传真实 BigInteger（原来 clampToLong → 面板顶在 9.2E）
                    items.add(new PanelItem(e.getKey(), e.getValue(), false));
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
        if (workMode == 1 || workMode == 2) {
            for (Map.Entry<AEKey, BigInteger> e : s2.entrySet()) {
                if (!wl.contains(e.getKey()) && !ul.contains(e.getKey())) {
                    // ⚠ 2026-09-19：传真实 BigInteger（原来 clampToLong → 面板顶在 9.2E）
                    items.add(new PanelItem(e.getKey(), e.getValue(), false));
                }
            }
        }

        return items;
    }

    public boolean togglePanelInfinite(AEKey key) {
        if (qtyLocked.contains(key)) return false;
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
            ca.put(key, amount);   // ⚠ 2026-09-19：不再 clampToLong —— 取消无限时要排真值
            s2.remove(key);
        }

        ul.add(key);
        dataDirty = true;
        save();
        return true;
    }

    /**
     * 承诺额度（真实数量，可远超 Long.MAX）。
     * <p>
     * ⚠ 2026-09-19（sensei）：原返回 long（且写入时已被 clampToLong）→ 取消无限只排出 9.2E。
     * 现在返回 BigInteger。取不到时回落该物品的生效阈值。
     */
    public BigInteger getCommitedAmount(AEKey key) {
        return ca.getOrDefault(key, BigInteger.valueOf(thresholdFor(key)));
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

    /** 读旧版 ByteArray/Long map（兼容两种格式）。
     *  2026-09-19：`ca` 也改成 BigInteger 了，这里统一支持 byte[] 与 long 两种写法。 */
    private static void getMapFromNbt(CompoundTag tag, String key, Map<AEKey, BigInteger> map) {
        map.clear();
        if (!tag.contains(key)) return;
        for (Tag t : tag.getList(key, 10)) {
            CompoundTag ct = (CompoundTag) t;
            AEKey k = AEKey.fromTagGeneric(ct);
            if (k == null) continue;
            if (ct.contains("#", Tag.TAG_BYTE_ARRAY)) {
                map.put(k, new BigInteger(ct.getByteArray("#")));
            } else {
                map.put(k, BigInteger.valueOf(ct.getLong("#")));
            }
        }
    }

    /** 旧版 Long map 的 NBT 迁移（内容按 BigInteger 承接，不再受 Long 上限约束） */
    private static void getMapFromNbtLong(CompoundTag tag, String key, Map<AEKey, BigInteger> map) {
        getMapFromNbt(tag, key, map);
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
        /**
         * 数量。⚠ 2026-09-19：从 long 改成 **BigInteger** —— Mode 2 的存储本来就是
         * BigInteger（可超 Long.MAX），沿用 long 会让面板把真实数量显示成 9.2E
         * （sensei 实测："最大显示数量为 9.2E，即使真实存储量大得多"）。
         */
        public final BigInteger amount;
        public final boolean isInfinite;
        public final long bytes;
        /** 单物品阈值；0 表示使用全局。 */
        public final long itemThreshold;
        /** 元件内数量是否锁定。 */
        public final boolean quantityLocked;

        public PanelItem(AEKey key, long amount, boolean isInfinite) {
            this(key, BigInteger.valueOf(amount), isInfinite, 0L);
        }

        public PanelItem(AEKey key, BigInteger amount, boolean isInfinite) {
            this(key, amount, isInfinite, 0L);
        }

        public PanelItem(AEKey key, long amount, boolean isInfinite, long bytes) {
            this(key, BigInteger.valueOf(amount), isInfinite, bytes);
        }

        public PanelItem(AEKey key, BigInteger amount, boolean isInfinite, long bytes) {
            this(key, amount, isInfinite, bytes, 0L);
        }

        public PanelItem(AEKey key, BigInteger amount, boolean isInfinite, long bytes, long itemThreshold) {
            this(key, amount, isInfinite, bytes, itemThreshold, false);
        }

        public PanelItem(AEKey key, BigInteger amount, boolean isInfinite, long bytes, long itemThreshold,
                         boolean quantityLocked) {
            this.key = key;
            this.amount = amount == null ? BigInteger.ZERO : amount;
            this.isInfinite = isInfinite;
            this.bytes = bytes;
            this.itemThreshold = itemThreshold;
            this.quantityLocked = quantityLocked;
        }
    }

}
