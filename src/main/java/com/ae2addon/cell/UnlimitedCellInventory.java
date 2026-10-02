package com.ae2addon.cell;

import java.math.BigInteger;
import java.util.Map;
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
        // openNbtData creates and attaches the compound when the stack has none.
        Platform.openNbtData(cellStack).setTag(NBT_ROOT, root);
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
        if (mode == 3) {
            if (mutate) {
                data.m3.add(key);
            }
            return true;
        }

        if (mode == 2) {
            if (workMode == 2) {
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

        if (mode == 2 && workMode == 1 && total.compareTo(BigInteger.valueOf(thresholdFor(key))) >= 0) {
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
        if (mode == 3) {
            if (mutate) {
                data.m3.add(key);
            }
            taken = BigInteger.valueOf(Math.min(requested, INFINITE));
        } else if (mode == 2 && (data.ul.contains(key) || data.wl.contains(key))) {
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
            case 3:
                // Deliberate P2 deviation from 1.20: 1.20 enumerates every registered item here (with a
                // versioned cache). On 1.12.2 that would push thousands of keys into the storage grid on
                // every rebuild, so we only list what we have actually seen. Extraction still works for
                // any item (insert/extract do not consult this list).
                reportKeys(out, data.m3, INFINITE);
                break;
            case 2:
                reportMap(out, data.s2);
                reportKeys(out, data.ul, INFINITE);
                break;
            default:
                reportMap(out, data.s1);
                break;
        }
        return out;
    }

    private static void reportMap(IItemList<IAEItemStack> out, Map<IAEItemStack, BigInteger> map) {
        for (Map.Entry<IAEItemStack, BigInteger> entry : map.entrySet()) {
            long size = clamp(entry.getValue());
            if (size <= 0) {
                continue;
            }
            IAEItemStack stack = entry.getKey().copy();
            stack.setStackSize(size);
            out.add(stack);
        }
    }

    private static void reportKeys(IItemList<IAEItemStack> out, Iterable<IAEItemStack> keys, long size) {
        for (IAEItemStack key : keys) {
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

    public boolean isQuantityLocked(IAEItemStack key) {
        return false; // not part of P2 (see spec section 1)
    }

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
        return data.s1.size() + data.s2.size();
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
    public static void appendSummary(ItemStack stack, Consumer<String> line) {
        NBTTagCompound stackTag = stack.getTagCompound();
        if (stackTag == null || !stackTag.hasKey(NBT_ROOT, 10)) {
            line.accept("mode 1（未初始化）");
            return;
        }
        NBTTagCompound root = stackTag.getCompoundTag(NBT_ROOT);
        int mode = root.getInteger(NBT_MODE);
        int wm = root.getInteger(NBT_WORKMODE);
        long thr = root.getLong(NBT_THR);
        line.accept(modeName(mode) + (mode == 2 ? " / " + workModeName(wm) : ""));
        if (mode == 2 && wm == 1) {
            line.accept("阈值: " + (thr <= 0 ? DEFAULT_THRESHOLD : thr));
        }
        if (mode == 3) {
            line.accept("任意物品均可取出");
        }
    }

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
