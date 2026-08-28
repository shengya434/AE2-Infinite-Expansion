package com.ae2addon.block;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.me.helpers.MachineSource;
import com.ae2addon.config.AE2AddonConfig;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ME 接口（无限级）— 机器供料站（被动供料 + CPU 直灌合体）。
 * <p>
 * 三个核心能力（2026-08-28 开工，方案来自 08-27 讨论）：
 * <pre>
 *   ① 被动拉取：相邻机器/漏斗从正面抽 → 蓄水池无单 tick 上限地从网络补足
 *                （每 4 tick 一次，每次拉满缺口，不受原版接口 1 操作/槽/tick 限制）
 *   ② 接收 CPU ScaledPattern N×：实现 ICraftingProvider，pushPattern 无条件收下
 *                （普通 ME 接口是 ICraftingRequester 收不到推送——这是补的洞）
 *   ③ 按机器容量分批喂出：每 tick 把蓄水池物品往正面机器 insertItem，
 *                机器拒收的余量留在蓄水池，天然按机器缓冲上限分批
 * </pre>
 * 配置：正面（FACING 朝向）放样板 = 声明可处理的配方，CPU 会把该配方任务推过来；
 * 同时样板输入 = 自动补货清单（机器消耗后从网络拉回，目标量 config feederStockTarget）。
 * <p>
 * 数据流：CPU ──ScaledPattern N×──► [蓄水池 BigInteger] ──insertItem 分批──► 机器
 *         网络 ──无上限拉取─────────► [蓄水池] ◄──extractItem── 机器/漏斗
 */
public class InfiniteInterfaceBE extends AENetworkBlockEntity implements ICraftingProvider {

    // ── 配置（AE2AddonConfig.apply 热加载） ──

    /** 每个物品的蓄水池目标保有量（0 = 不自动补货，只收 CPU 推送）。 */
    public static volatile long STOCK_TARGET = 1_000_000L;
    /** 每 tick 喂给相邻机器的 insertItem 尝试次数上限（防单 tick 卡顿）。 */
    public static volatile int FEED_BUDGET = 1024;
    /** 每次 insertItem 尝试的最大堆叠数（受机器槽位上限约束）。 */
    public static volatile int FEED_STACK = 64;
    /** 补货间隔（tick）。4 = 每秒 5 次全量补货。 */
    public static volatile int RESTOCK_INTERVAL = 4;

    /** 配置热加载时由 AE2AddonConfig 调用。 */
    public static void applyConfig() {
        STOCK_TARGET = AE2AddonConfig.feederStockTarget();
        FEED_BUDGET = AE2AddonConfig.feederFeedBudget();
    }

    // ── 蓄水池（BigInteger 防溢出；CPU N× 直灌可达 2^63-1/批） ──

    private final Map<AEKey, BigInteger> reservoir = new LinkedHashMap<>();

    /** 累计已喂出总量（机器/漏斗实际收到的物品数；供料站流量可见性）。 */
    private BigInteger totalFed = BigInteger.ZERO;

    // ── 样板槽（3×3，声明可处理的配方；CPU 路由靠它） ──

    private final SimpleContainer patternInv = new SimpleContainer(9) {
        @Override
        public void setChanged() {
            super.setChanged();
            InfiniteInterfaceBE.this.onPatternsChanged();
        }
    };

    // ── 标记槽（3×3，声明自动补货物品；与样板定量语义解耦） ──
    // 样板 = 定量（CPU 推多少发多少，发完停）；标记 = 无限供料（标记的物品
    // 持续从网络补到 feederStockTarget，机器永远有货）。

    private final SimpleContainer markerInv = new SimpleContainer(9) {
        @Override
        public void setChanged() {
            super.setChanged();
            InfiniteInterfaceBE.this.onMarkersChanged();
        }
    };

    private List<IPatternDetails> patterns = List.of();
    private boolean patternDirty = false;

    private final IActionSource actionSource = new MachineSource(this);

    /** 正面 IItemHandler（机器/漏斗从这里抽；insertItem 一律拒收防回流死循环）。 */
    private final LazyOptional<IItemHandler> frontHandler =
            LazyOptional.of(() -> new FrontItemHandler());

    public InfiniteInterfaceBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_INTERFACE.get(), pos, state);
    }

    // ── 诊断日志（定位供料问题用） ──

    private boolean feederDiagLogged;

    private void logFeederStatus(String tag) {
        Direction front = getFront();
        Direction facing = null;
        try {
            facing = getBlockState().getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
        } catch (RuntimeException ignored) {
        }
        BlockEntity target = (front == null || level == null)
                ? null : level.getBlockEntity(worldPosition.relative(front));
        String targetName = target == null
                ? "无" : target.getBlockState().getBlock().getName().getString();
        var summary = reservoirSummary();
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][feeder] {} pos={} facing={} front={} 目标方块={} 蓄水池={}种/合计{}",
                tag, worldPosition, facing, front, targetName, summary[0], summary[1]);
    }

    // ── 网格节点：注册为合成 provider（CPU 才能找到我们推样板） ──

    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(ICraftingProvider.class, this);
    }

    // ── ICraftingProvider：无条件接收 N× ──

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patterns;
    }

    /**
     * 无条件收下 CPU 推送的样板输入（含 ScaledPattern N× 缩放后的 KeyCounter）。
     * 输入全部进蓄水池，CPU 推完即走，不阻塞。
     */
    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs) {
        long inputCount = 0;
        long inputTypes = 0;
        if (inputs != null) {
            for (var counter : inputs) {
                if (counter == null) {
                    continue;
                }
                for (var entry : counter) {
                    AEKey key = entry.getKey();
                    long amount = entry.getLongValue();
                    if (key != null && amount > 0) {
                        addReservoir(key, BigInteger.valueOf(amount));
                        inputCount += amount;
                        inputTypes++;
                    }
                }
            }
        }
        var summary = reservoirSummary();
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][feeder] pushPattern 接收 pattern={} 本次{}种/{}个 → 蓄水池={}种/合计{}",
                patternDetails == null ? "null" : patternDetails.getClass().getSimpleName(),
                inputTypes, inputCount, summary[0], summary[1]);
        setChanged();
        return true;
    }

    /** 永不拒收（蓄水池无限）。 */
    @Override
    public boolean isBusy() {
        return false;
    }

    /** 样板输出 = 网络「可发射物品」声明（终端可见，不影响实际功能）。 */
    @Override
    public Set<AEKey> getEmitableItems() {
        Set<AEKey> out = new HashSet<>();
        for (var pattern : patterns) {
            for (var output : pattern.getOutputs()) {
                if (output != null && output.what() != null) {
                    out.add(output.what());
                }
            }
        }
        return out;
    }

    // ── 每 tick：补货 + 喂出 ──

    public void serverTick() {
        Level lvl = level;
        if (lvl == null || lvl.isClientSide) {
            return;
        }
        if (patternDirty) {
            rebuildPatterns();
        }
        if (!feederDiagLogged) {
            feederDiagLogged = true;
            logFeederStatus("启动");
        } else if ((lvl.getGameTime() & 0x3F) == 0
                && com.ae2addon.config.AE2AddonConfig.debugLogs()) {
            logFeederStatus("心跳"); // 每 64 tick（约 3 秒）一条，仅 debugLogs 开
        }
        if ((lvl.getGameTime() & (RESTOCK_INTERVAL - 1)) == 0) {
            restockFromNetwork();
        }
        feedMachine();
    }

    /** ① 从网络无上限拉取：对每个待补物品一次拉满缺口（每 RESTOCK_INTERVAL tick）。 */
    private void restockFromNetwork() {
        if (STOCK_TARGET <= 0) {
            return; // 配置关闭自动补货
        }
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        for (AEKey key : wantedKeys()) {
            // 只补物品（流体无法通过 IItemHandler 喂给机器）
            if (!(key instanceof AEItemKey)) {
                continue;
            }
            long have = reservoirAmount(key);
            long want = STOCK_TARGET - have;
            if (want <= 0) {
                continue;
            }
            long got = storage.extract(key, want, Actionable.MODULATE, actionSource);
            if (got > 0) {
                addReservoir(key, BigInteger.valueOf(got));
                setChanged();
            }
        }
    }

    /** 待补物品 = 标记槽物品（样板输入不再自动补货——定量语义）。 */
    private Set<AEKey> wantedKeys() {
        Set<AEKey> keys = new HashSet<>();
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            ItemStack stack = markerInv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            AEItemKey key = AEItemKey.of(stack);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** ③ 按机器容量分批喂出：insertItem 拒收的余量留在蓄水池。 */
    private void feedMachine() {
        Direction front = getFront();
        if (front == null) {
            return;
        }
        BlockEntity target = level.getBlockEntity(worldPosition.relative(front));
        if (target == null) {
            return;
        }
        LazyOptional<IItemHandler> cap = target.getCapability(
                ForgeCapabilities.ITEM_HANDLER, front.getOpposite());
        if (!cap.isPresent()) {
            if (!feederDiagLogged) {
                feederDiagLogged = true;
                logFeederStatus("启动(无IItemHandler)");
            }
            return;
        }
        IItemHandler handler = cap.orElse(null);
        if (handler == null) {
            return;
        }
        int budget = FEED_BUDGET;
        int slots = handler.getSlots();
        if (slots <= 0) {
            return;
        }
        long fedAll = 0;
        for (var it = reservoir.entrySet().iterator(); it.hasNext() && budget > 0; ) {
            var entry = it.next();
            AEKey key = entry.getKey();
            if (!(key instanceof AEItemKey itemKey)) {
                continue;
            }
            BigInteger remain = entry.getValue();
            if (remain.signum() <= 0) {
                it.remove();
                continue;
            }
            long amount = remain.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            long fed = 0;
            while (amount > 0 && budget > 0) {
                int chunk = (int) Math.min(FEED_STACK, amount);
                ItemStack stack = itemKey.toStack(chunk);
                ItemStack leftover = stack;
                for (int slot = 0; slot < slots && !leftover.isEmpty(); slot++) {
                    leftover = handler.insertItem(slot, leftover, false);
                }
                int inserted = chunk - leftover.getCount();
                if (inserted <= 0) {
                    break; // 机器满了/拒收该物品 → 换下一种
                }
                fed += inserted;
                amount -= inserted;
                budget--;
            }
            if (fed > 0) {
                // ⚠️ 用 entry.setValue/it.remove 而非 computeIfPresent(null)：
                // 迭代中通过 map 删除会 ConcurrentModificationException（潜在崩溃）
                BigInteger next = entry.getValue().subtract(BigInteger.valueOf(fed));
                if (next.signum() > 0) {
                    entry.setValue(next);
                } else {
                    it.remove();
                }
                fedAll += fed;
            }
        }
        if (fedAll > 0) {
            totalFed = totalFed.add(BigInteger.valueOf(fedAll));
            setChanged();
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][feeder] 喂出 {} 个 → 蓄水池={}种/合计{}，累计已喂出={}",
                    fedAll, reservoirSummary()[0],
                    fmt(totalAmount()), fmt(totalFed));
        }
    }

    // ── 蓄水池操作 ──

    private void addReservoir(AEKey key, BigInteger amount) {
        if (amount.signum() <= 0) {
            return;
        }
        reservoir.merge(key, amount, BigInteger::add);
    }

    private void subtractReservoir(AEKey key, long amount) {
        if (amount <= 0) {
            return;
        }
        reservoir.computeIfPresent(key, (k, v) -> {
            BigInteger next = v.subtract(BigInteger.valueOf(amount));
            return next.signum() > 0 ? next : null;
        });
    }

    private long reservoirAmount(AEKey key) {
        BigInteger amount = reservoir.get(key);
        if (amount == null) {
            return 0;
        }
        return amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    // ── 样板管理 ──

    public SimpleContainer getPatternInventory() {
        return patternInv;
    }

    public SimpleContainer getMarkerInventory() {
        return markerInv;
    }

    /** 标记槽中已放置的物品种数（GUI 状态用）。 */
    public int markerCount() {
        int count = 0;
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            if (!markerInv.getItem(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private void onMarkersChanged() {
        setChanged();
    }

    /** 方块拆除时掉落样板槽物品（玩家资源；蓄水池物品属于网络/CPU，不返还防刷）。 */
    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        for (int i = 0; i < patternInv.getContainerSize(); i++) {
            ItemStack stack = patternInv.getItem(i);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
    }

    private void onPatternsChanged() {
        patternDirty = true;
        patterns = List.of();
        if (level != null && level.isClientSide) {
            return;
        }
        if (getMainNode().isReady()) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
        setChanged();
    }

    private void rebuildPatterns() {
        patternDirty = false;
        Level lvl = level;
        List<IPatternDetails> list = new ArrayList<>();
        if (lvl != null) {
            for (int i = 0; i < patternInv.getContainerSize(); i++) {
                ItemStack stack = patternInv.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                IPatternDetails details = PatternDetailsHelper.decodePattern(stack, lvl);
                if (details != null) {
                    list.add(details);
                }
            }
        }
        patterns = List.copyOf(list);
        if (getMainNode().isReady()) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    // ── 方向 ──

    /** 正面 = 方块 FACING 朝向（机器所在侧）。 */
    @Nullable
    public Direction getFront() {
        BlockState state = getBlockState();
        if (state == null) {
            return null;
        }
        BlockOrientation orientation = BlockOrientation.get(state);
        return orientation.getSide(RelativeSide.FRONT);
    }

    // ── 正面 IItemHandler：机器/漏斗从这里抽 ──

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER && side == getFront()) {
            return frontHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        frontHandler.invalidate();
    }

    /**
     * 正面虚拟单槽：slot 0 = 蓄水池中数量最多的物品。
     * extractItem 从蓄水池扣；insertItem 一律拒收（防机器回流 → 死循环）。
     */
    private final class FrontItemHandler implements IItemHandler {

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot != 0) {
                return ItemStack.EMPTY;
            }
            var best = largestItem();
            if (best == null) {
                return ItemStack.EMPTY;
            }
            long amount = best.getValue()
                    .min(BigInteger.valueOf(FEED_STACK)).longValue();
            return ((AEItemKey) best.getKey()).toStack((int) Math.max(1, amount));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack; // 拒收：防回流
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 0 || amount <= 0) {
                return ItemStack.EMPTY;
            }
            var best = largestItem();
            if (best == null) {
                return ItemStack.EMPTY;
            }
            AEItemKey itemKey = (AEItemKey) best.getKey();
            long take = best.getValue()
                    .min(BigInteger.valueOf(amount)).min(BigInteger.valueOf(FEED_STACK))
                    .longValue();
            if (take <= 0) {
                return ItemStack.EMPTY;
            }
            if (!simulate) {
                subtractReservoir(itemKey, take);
                totalFed = totalFed.add(BigInteger.valueOf(take));
                setChanged();
            }
            return itemKey.toStack((int) take);
        }

        @Override
        public int getSlotLimit(int slot) {
            return FEED_STACK;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false; // 只出不进
        }

        private Map.Entry<AEKey, BigInteger> largestItem() {
            Map.Entry<AEKey, BigInteger> best = null;
            for (var entry : reservoir.entrySet()) {
                if (!(entry.getKey() instanceof AEItemKey) || entry.getValue().signum() <= 0) {
                    continue;
                }
                if (best == null
                        || entry.getValue().compareTo(best.getValue()) > 0) {
                    best = entry;
                }
            }
            return best;
        }
    }

    // ── NBT ──

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        ListTag patternList = new ListTag();
        for (int i = 0; i < patternInv.getContainerSize(); i++) {
            ItemStack stack = patternInv.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                stack.save(entry);
                entry.putInt("Slot", i);
                patternList.add(entry);
            }
        }
        tag.put("patterns", patternList);

        ListTag markerList = new ListTag();
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            ItemStack stack = markerInv.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                stack.save(entry);
                entry.putInt("Slot", i);
                markerList.add(entry);
            }
        }
        tag.put("markers", markerList);

        ListTag reservoirList = new ListTag();
        for (var entry : reservoir.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.put("key", entry.getKey().toTagGeneric());
            entryTag.putString("amount", entry.getValue().toString());
            reservoirList.add(entryTag);
        }
        tag.put("reservoir", reservoirList);
        tag.putString("totalFed", totalFed.toString());
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        reservoir.clear();

        ListTag patternList = tag.getList("patterns", Tag.TAG_COMPOUND);
        patternInv.clearContent();
        for (int i = 0; i < patternList.size(); i++) {
            CompoundTag entry = patternList.getCompound(i);
            ItemStack stack = ItemStack.of(entry);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < patternInv.getContainerSize() && !stack.isEmpty()) {
                patternInv.setItem(slot, stack);
            }
        }
        patternDirty = true; // level 可能为 null，解码延迟到首个 tick

        ListTag markerList = tag.getList("markers", Tag.TAG_COMPOUND);
        markerInv.clearContent();
        for (int i = 0; i < markerList.size(); i++) {
            CompoundTag entry = markerList.getCompound(i);
            ItemStack stack = ItemStack.of(entry);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < markerInv.getContainerSize() && !stack.isEmpty()) {
                markerInv.setItem(slot, stack);
            }
        }

        ListTag reservoirList = tag.getList("reservoir", Tag.TAG_COMPOUND);
        for (int i = 0; i < reservoirList.size(); i++) {
            CompoundTag entry = reservoirList.getCompound(i);
            try {
                AEKey key = AEKey.fromTagGeneric(entry.getCompound("key"));
                BigInteger amount = new BigInteger(entry.getString("amount"));
                if (key != null && amount.signum() > 0) {
                    reservoir.put(key, amount);
                }
            } catch (RuntimeException ignored) {
                // 单条损坏不影响整体
            }
        }
        try {
            totalFed = new BigInteger(tag.getString("totalFed"));
        } catch (RuntimeException ignored) {
            totalFed = BigInteger.ZERO;
        }
    }

    /** 蓄水池概览（GUI 状态用）。返回 [物品种类数, 合计(字符串)]。 */
    public String[] reservoirSummary() {
        int types = 0;
        BigInteger total = BigInteger.ZERO;
        for (var entry : reservoir.entrySet()) {
            if (entry.getValue().signum() > 0) {
                types++;
                total = total.add(entry.getValue());
            }
        }
        return new String[]{String.valueOf(types), fmt(total)};
    }

    /** 蓄水池 top N 物品描述（GUI 状态用）。 */
    public List<String> topItems(int limit) {
        List<Map.Entry<AEKey, BigInteger>> list = new ArrayList<>();
        for (var entry : reservoir.entrySet()) {
            if (entry.getValue().signum() > 0) {
                list.add(entry);
            }
        }
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            var entry = list.get(i);
            String name;
            try {
                name = entry.getKey().getDisplayName().getString();
            } catch (RuntimeException e) {
                name = entry.getKey().toString();
            }
            out.add(name + " × " + fmt(entry.getValue()));
        }
        return out;
    }

    /** 累计已喂出总量（GUI 状态用）。 */
    public BigInteger totalFed() {
        return totalFed;
    }

    /** 蓄水池内物品合计（BigInteger，GUI 显示）。 */
    public BigInteger totalAmount() {
        BigInteger total = BigInteger.ZERO;
        for (BigInteger amount : reservoir.values()) {
            if (amount.signum() > 0) {
                total = total.add(amount);
            }
        }
        return total;
    }

    /** 大数格式化：K/M/G/T/P/E 后缀。 */
    public static String fmt(BigInteger value) {
        if (value == null || value.signum() < 0) {
            return "0";
        }
        String[] units = {"", "K", "M", "G", "T", "P", "E"};
        java.math.BigDecimal d = new java.math.BigDecimal(value);
        int unit = 0;
        java.math.BigDecimal thousand = java.math.BigDecimal.valueOf(1000);
        while (d.compareTo(thousand) >= 0 && unit < units.length - 1) {
            d = d.divide(thousand);
            unit++;
        }
        if (unit == 0) {
            return d.toBigInteger().toString();
        }
        d = d.setScale(1, java.math.RoundingMode.DOWN);
        if (d.compareTo(java.math.BigDecimal.valueOf(1000)) >= 0 && unit < units.length - 1) {
            d = d.divide(thousand).setScale(1, java.math.RoundingMode.DOWN);
            unit++;
        }
        return d.toPlainString() + units[unit];
    }
}
