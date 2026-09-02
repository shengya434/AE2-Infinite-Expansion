package com.ae2addon.part;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.orientation.RelativeSide;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.items.misc.WrappedGenericStack;
import appeng.items.parts.PartModels;
import appeng.me.helpers.MachineSource;
import appeng.menu.locator.MenuLocators;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.FeederHost;
import com.ae2addon.block.InfiniteInterfaceBE;
import com.ae2addon.gui.InfiniteInterfaceMenu;
import com.ae2addon.util.MemoryCardHelper;

import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.network.chat.Component;

/**
 * ME 接口（无限级）· 线缆面板 part（2026-09-02 sensei：面板应能装进机器与线缆之间）。
 * <p>
 * 挂在 AE2 线缆上的部件形态：右键装到线缆上，面板朝 {@link #getSide()} 方向伸出喂机器——
 * 机器贴着线缆即可供料，不额外占格子。核心供料逻辑与方块版 {@link InfiniteInterfaceBE} 对齐
 * （蓄水池 BigInteger、CPU 直灌、按机器容量喂出、网络空间优先 + 待入网缓存、STORAGE 子网直连）。
 */
public class InfiniteInterfacePart extends AEBasePart
        implements FeederHost, ICraftingProvider, IGridTickable,
        appeng.helpers.patternprovider.PatternContainer {

    public static final ResourceLocation MODEL_BASE =
            new ResourceLocation(AE2Addon.MODID, "part/infinite_interface_panel");
    @PartModels
    public static final IPartModel MODELS_OFF = new PartModel(MODEL_BASE);

    private static final IGridNodeListener<InfiniteInterfacePart> NODE_LISTENER =
            new AEBasePart.NodeListener<>() {
            };

    // ── 状态（与方块版对齐；引用方块版 public static 配置常量） ──

    private final IUpgradeInventory upgrades;

    private final Map<AEKey, BigInteger> reservoir = new java.util.LinkedHashMap<>();
    private final Set<AEKey> patternKeys = new HashSet<>();
    private final Set<AEKey> pendingNetworkKeys = new HashSet<>();
    private final SimpleContainer patternInv = new SimpleContainer(45);
    private final SimpleContainer markerInv = new SimpleContainer(45);
    private final IActionSource actionSource = new MachineSource(this::getActionableNode);

    private boolean activeExtract = true;
    private boolean activeFeed = true;
    private boolean activeMarkerFeed = true;
    private RelativeSide extractSide = RelativeSide.FRONT;
    private boolean patternDirty = true;
    private List<IPatternDetails> patterns = List.of();
    private final Map<AEKey, Long> markerTargets = new java.util.LinkedHashMap<>();
    private long pStockTarget = -1;
    private int pRestockInterval = -1;
    private int pFeedBudget = -1;
    private boolean removed = false;
    private long lastFeederWarnTick = Long.MIN_VALUE;
    private long rateWindowFed;
    private long currentFeedRate;
    private long rejectWindow;
    private long currentRejectRate;
    private BigInteger totalFed = BigInteger.ZERO;

    public InfiniteInterfacePart(IPartItem<?> partItem) {
        super(partItem);
        this.upgrades = UpgradeInventories.forMachine(partItem.asItem(), 9, this::setChanged);
    }

    // ── 网络 ──

    @Override
    protected IManagedGridNode createMainNode() {
        return GridHelper.createManagedNode(this, NODE_LISTENER)
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(ICraftingProvider.class, this)
                .addService(IGridTickable.class, this);
    }

    @Override
    public void addToWorld() {
        super.addToWorld();
        removed = false;
    }

    @Override
    public void removeFromWorld() {
        super.removeFromWorld();
        removed = true;
    }

    // ── 模型/碰撞 ──

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        // 面板盒：贴 cable 面伸出（side 方向），2/16 厚视觉 + 少许连接段
        bch.addBox(2, 2, 14, 14, 14, 16);
        bch.addBox(5, 5, 12, 11, 11, 14);
    }

    @Override
    public IPartModel getStaticModels() {
        return MODELS_OFF;
    }

    @Override
    public float getCableConnectionLength(appeng.api.util.AECableType cable) {
        return 4;
    }

    // ── 方向：正面 = side（机器所在侧） ──

    @Override
    @Nullable
    public Direction getFront() {
        return getSide();
    }

    private @Nullable Direction resolveDir(RelativeSide rel) {
        Direction side = getSide();
        if (side == null) {
            return null;
        }
        return switch (rel) {
            case FRONT -> side;
            case BACK -> side.getOpposite();
            case LEFT -> side.getClockWise();
            case RIGHT -> side.getCounterClockWise();
            case TOP -> Direction.UP;
            case BOTTOM -> Direction.DOWN;
        };
    }

    // ── tick（IGridTickable；每 tick 由网格调度） ──

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, false, true);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        if (removed || isClientSide()) {
            return TickRateModulation.IDLE;
        }
        Level lvl = getLevel();
        if (lvl == null) {
            return TickRateModulation.SLEEP;
        }
        long t = lvl.getGameTime();
        if (patternDirty) {
            rebuildPatterns();
        }
        if ((t & 19) == 0) {
            currentFeedRate = rateWindowFed;
            rateWindowFed = 0;
            currentRejectRate = rejectWindow;
            rejectWindow = 0;
        }
        if ((t % Math.max(1, restockIntervalValue())) == 0) {
            restockFromNetwork();
        }
        if ((t % InfiniteInterfaceBE.EXTRACT_INTERVAL) == 0) {
            extractFromMachine();
        }
        if ((t % 10) == 0) {
            pushPendingToNetwork();
        }
        feedMachine();
        return TickRateModulation.IDLE;
    }

    // ── ICraftingProvider：CPU 直灌（与方块版一致） ──

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputs) {
        for (KeyCounter kc : inputs) {
            for (var entry : kc) {
                if (entry.getLongValue() > 0) {
                    addReservoir(entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
                    patternKeys.add(entry.getKey());
                }
            }
        }
        setChanged();
        return true; // 无条件收下
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    // ── 蓄水池 ──

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
            if (next.signum() <= 0) {
                patternKeys.remove(key);
                pendingNetworkKeys.remove(key);
                return null;
            }
            return next;
        });
    }

    private long reservoirAmount(AEKey key) {
        BigInteger amt = reservoir.get(key);
        return amt == null ? 0 : amt.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    private void cacheForNetwork(AEKey key, long amount) {
        if (amount <= 0) {
            return;
        }
        addReservoir(key, BigInteger.valueOf(amount));
        pendingNetworkKeys.add(key);
        setChanged();
    }

    private void setChanged() {
        getHost().markForSave();
    }

    // ── 补货：网络 → 蓄水池（按标记/样板目标） ──

    private void restockFromNetwork() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        var storage = grid.getStorageService().getInventory();
        // 补货清单：标记槽 + 蓄水池中 patternKeys（有缺口才拉）
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            ItemStack st = markerInv.getItem(i);
            if (st.isEmpty() || !(st.getItem() instanceof WrappedGenericStack wgs)) {
                continue;
            }
            AEKey key = wgs.unwrapWhat(st);
            if (key == null) {
                continue;
            }
            long have = reservoirAmount(key);
            long target = targetFor(key);
            if (have >= target) {
                continue;
            }
            long want = target - have;
            long got = storage.extract(key, Math.min(want, Long.MAX_VALUE),
                    Actionable.MODULATE, actionSource);
            if (got > 0) {
                addReservoir(key, BigInteger.valueOf(got));
                setChanged();
            }
        }
    }

    // ── 喂出：蓄水池 → 机器（side 方向） ──

    private void feedMachine() {
        if (!activeFeed) {
            return;
        }
        Level lvl = getLevel();
        Direction front = getFront();
        if (lvl == null || front == null) {
            return;
        }
        BlockEntity target = lvl.getBlockEntity(getBlockEntity().getBlockPos().relative(front));
        if (target == null) {
            return;
        }
        Direction machineSide = front.getOpposite();
        var itemCap = target.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER,
                machineSide);
        IItemHandler handler = itemCap.isPresent() ? itemCap.orElse(null) : null;
        int slots = handler == null ? 0 : handler.getSlots();

        // 可喂种类（物品/流体）
        int feedable = 0;
        for (var entry : reservoir.entrySet()) {
            if ((entry.getKey() instanceof appeng.api.stacks.AEItemKey
                    || entry.getKey() instanceof appeng.api.stacks.AEFluidKey)
                    && entry.getValue().signum() > 0) {
                if (pendingNetworkKeys.contains(entry.getKey())) {
                    continue;
                }
                if (!activeMarkerFeed && !patternKeys.contains(entry.getKey())) {
                    continue;
                }
                feedable++;
            }
        }
        if (feedable <= 0) {
            return;
        }
        // 速度卡：每张喂出预算 ×2（最高 ×4；与方块版一致）
        int mult = 1 << Math.min(speedCards(), 2);
        int budget = Math.min(feedBudgetValue() * mult, 1_000_000);
        int perItemBudget = Math.max(1, budget / feedable);
        int totalBudget = budget;
        long fedAll = 0;
        for (var it = reservoir.entrySet().iterator(); it.hasNext() && totalBudget > 0; ) {
            var entry = it.next();
            AEKey key = entry.getKey();
            BigInteger remain = entry.getValue();
            if (remain.signum() <= 0) {
                it.remove();
                continue;
            }
            if (pendingNetworkKeys.contains(key)) {
                continue;
            }
            if (!activeMarkerFeed && !patternKeys.contains(key)) {
                continue;
            }
            long amount = remain.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            long fed = 0;
            int itemBudget = perItemBudget;
            if (key instanceof appeng.api.stacks.AEItemKey itemKey && handler != null && slots > 0) {
                while (amount > 0 && itemBudget > 0 && totalBudget > 0) {
                    int chunk = (int) Math.min(InfiniteInterfaceBE.FEED_STACK, amount);
                    ItemStack stack = itemKey.toStack(chunk);
                    ItemStack leftover = stack;
                    for (int slot = 0; slot < slots && !leftover.isEmpty(); slot++) {
                        leftover = handler.insertItem(slot, leftover, false);
                    }
                    int inserted = chunk - leftover.getCount();
                    if (inserted <= 0) {
                        break;
                    }
                    fed += inserted;
                    amount -= inserted;
                    itemBudget--;
                    totalBudget--;
                }
            } else if (key instanceof appeng.api.stacks.AEFluidKey fluidKey) {
                var fluidCap = target.getCapability(
                        net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, machineSide);
                if (fluidCap.isPresent()) {
                    var fh = fluidCap.orElse(null);
                    if (fh != null) {
                        int mb = (int) Math.min(Math.min(amount, 1000), Integer.MAX_VALUE);
                        int filled = fh.fill(new net.minecraftforge.fluids.FluidStack(
                                fluidKey.getFluid(), mb),
                                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                        if (filled > 0) {
                            fed += filled;
                            amount -= filled;
                        }
                    }
                }
            }
            if (fed > 0) {
                BigInteger next = entry.getValue().subtract(BigInteger.valueOf(fed));
                if (next.signum() > 0) {
                    entry.setValue(next);
                } else {
                    patternKeys.remove(key);
                    it.remove();
                }
                fedAll += fed;
            }
        }
        if (fedAll > 0) {
            totalFed = totalFed.add(BigInteger.valueOf(fedAll));
            rateWindowFed += fedAll;
            setChanged();
        } else {
            rejectWindow++;
        }
    }

    // ── 主动抽取：机器 → 网络/待入网缓存 ──

    private void extractFromMachine() {
        if (!activeExtract) {
            return;
        }
        Level lvl = getLevel();
        Direction dir = resolveDir(extractSide);
        if (lvl == null || dir == null) {
            return;
        }
        BlockEntity target = lvl.getBlockEntity(getBlockEntity().getBlockPos().relative(dir));
        if (target == null) {
            return;
        }
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        var storage = grid.getStorageService().getInventory();
        Direction side = dir.getOpposite();
        try {
            var itemCap = target.getCapability(
                    net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, side);
            if (itemCap.isPresent()) {
                IItemHandler handler = itemCap.orElse(null);
                if (handler != null && handler.getSlots() > 0) {
                    for (int slot = 0; slot < handler.getSlots(); slot++) {
                        var extracted = handler.extractItem(slot, InfiniteInterfaceBE.EXTRACT_STACK, true);
                        if (extracted.isEmpty()) {
                            continue;
                        }
                        var key = appeng.api.stacks.AEItemKey.of(extracted);
                        if (key == null || isMarkedMaterial(key)) {
                            continue;
                        }
                        long available = extracted.getCount();
                        long inserted = storage.insert(key, available, Actionable.MODULATE, actionSource);
                        long cached = available - inserted;
                        if (inserted > 0 || cached > 0) {
                            int toExtract = (int) Math.min(available, Integer.MAX_VALUE);
                            while (toExtract > 0) {
                                var got = handler.extractItem(slot, toExtract, false);
                                if (got.isEmpty()) {
                                    break;
                                }
                                toExtract -= Math.min(got.getCount(), toExtract);
                            }
                            if (cached > 0) {
                                cacheForNetwork(key, cached);
                            }
                        }
                    }
                }
            }
            // 流体逐罐抽（产物）
            var fluidCap = target.getCapability(
                    net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, side);
            if (fluidCap.isPresent()) {
                var fh = fluidCap.orElse(null);
                if (fh != null && fh.getTanks() > 0) {
                    for (int tank = 0; tank < fh.getTanks(); tank++) {
                        var inTank = fh.getFluidInTank(tank);
                        if (inTank.isEmpty()) {
                            continue;
                        }
                        var key = appeng.api.stacks.AEFluidKey.of(inTank.getFluid());
                        if (key == null || isMarkedMaterial(key)) {
                            continue;
                        }
                        int amount = (int) Math.min(inTank.getAmount(), InfiniteInterfaceBE.EXTRACT_FLUID);
                        var drained = fh.drain(new net.minecraftforge.fluids.FluidStack(inTank.getFluid(), amount),
                                net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
                        if (drained.isEmpty()) {
                            continue;
                        }
                        long inserted = storage.insert(key, drained.getAmount(), Actionable.MODULATE, actionSource);
                        long cached = drained.getAmount() - inserted;
                        if (inserted > 0 || cached > 0) {
                            fh.drain(new net.minecraftforge.fluids.FluidStack(inTank.getFluid(), (int) (inserted + cached)),
                                    net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                            if (cached > 0) {
                                cacheForNetwork(key, cached);
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isMarkedMaterial(AEKey key) {
        if (targetFor(key) > 0 && markerContains(key)) {
            return true;
        }
        var have = reservoir.get(key);
        return have != null && have.signum() > 0;
    }

    private boolean markerContains(AEKey key) {
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            ItemStack st = markerInv.getItem(i);
            if (st.isEmpty() || !(st.getItem() instanceof WrappedGenericStack wgs)) {
                continue;
            }
            AEKey wrapped = wgs.unwrapWhat(st);
            if (wrapped != null && wrapped.equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ── 待入网缓存自动补送 ──

    private void pushPendingToNetwork() {
        if (pendingNetworkKeys.isEmpty()) {
            return;
        }
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        var storage = grid.getStorageService().getInventory();
        for (AEKey key : new java.util.ArrayList<>(pendingNetworkKeys)) {
            BigInteger amt = reservoir.get(key);
            if (amt == null || amt.signum() <= 0) {
                pendingNetworkKeys.remove(key);
                continue;
            }
            long want = amt.min(BigInteger.valueOf(Integer.MAX_VALUE)).longValue();
            long inserted;
            try {
                inserted = storage.insert(key, want, Actionable.MODULATE, actionSource);
            } catch (RuntimeException e) {
                continue;
            }
            if (inserted > 0) {
                subtractReservoir(key, inserted);
                setChanged();
            }
        }
    }

    // ── FeederHost ──

    @Override
    public SimpleContainer getPatternInventory() {
        return patternInv;
    }

    @Override
    public SimpleContainer getMarkerInventory() {
        return markerInv;
    }

    @Override
    public IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    @Override
    public int capacityCards() {
        return upgrades.getInstalledUpgrades(
                appeng.core.definitions.AEItems.CAPACITY_CARD.asItem());
    }

    /** 速度卡数量（每张喂出预算 ×2，最高 ×4）。 */
    private int speedCards() {
        return upgrades.getInstalledUpgrades(
                appeng.core.definitions.AEItems.SPEED_CARD.asItem());
    }

    @Override
    public int maxPage() {
        return capacityCards();
    }

    @Override
    public BlockPos getBlockPos() {
        return getBlockEntity().getBlockPos();
    }

    /**
     * ⚠️ 必须显式覆写：AE2 jar 官方映射直用（方法名 getLevel 未混淆），但本 mod reobf 时
     * getLevel() 签名与 MC BlockEntity.getLevel 相同会被 SRG 映射成 m_58904_ → 不覆写则
     * 运行期 AbstractMethodError（2026-09-02 崩溃实锤 mclo.gs/rMn1RLH）。
     */
    @Override
    public Level getLevel() {
        var be = getBlockEntity();
        return be == null ? null : be.getLevel();
    }

    @Override
    public boolean isRemoved() {
        return removed;
    }

    @Override
    public boolean handleMarkerClick(int markerIndex, ItemStack carried, boolean content) {
        if (markerIndex < 0 || markerIndex >= markerInv.getContainerSize()) {
            return false;
        }
        if (carried.isEmpty()) {
            markerInv.setItem(markerIndex, ItemStack.EMPTY);
            onMarkersChanged();
            return true;
        }
        if (content) {
            // 右键：容器内容物优先（流体/气体容器 → 内容物）
            var contained = net.minecraftforge.fluids.FluidUtil.getFluidContained(carried);
            if (contained.isPresent() && !contained.get().isEmpty()) {
                markByKey(markerIndex, appeng.api.stacks.AEFluidKey.of(contained.get()));
                return true;
            }
            AEKey gasKey = com.ae2addon.compat.MekanismGasCompat.chemicalInContainer(carried);
            if (gasKey != null) {
                markByKey(markerIndex, gasKey);
                return true;
            }
        }
        // 普通物品 / 左键：一律标记本体（WGS 虚拟标记，不占用真实物品）
        var itemKey = appeng.api.stacks.AEItemKey.of(carried);
        if (itemKey != null) {
            markByKey(markerIndex, itemKey);
            return true;
        }
        return false;
    }

    /** 当前标记的 key 集合（退缓存对比用）。 */
    private Set<AEKey> wantedKeys() {
        Set<AEKey> keys = new HashSet<>();
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            ItemStack st = markerInv.getItem(i);
            if (st.isEmpty() || !(st.getItem() instanceof WrappedGenericStack wgs)) {
                continue;
            }
            AEKey key = wgs.unwrapWhat(st);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 上次标记的 key 集合（变化检测：消失的标记 → 退回蓄水池缓存到网络）。 */
    private Set<AEKey> lastMarkedKeys = java.util.Collections.emptySet();

    private void onMarkersChanged() {
        setChanged();
        // 标记区不占真实存储：标记取消 → 对应蓄水池缓存退回网络（2026-09-02 part 对齐方块版）
        Set<AEKey> now = wantedKeys();
        Level lvl = getLevel();
        if (lvl == null || lvl.isClientSide() || now.equals(lastMarkedKeys)) {
            lastMarkedKeys = now;
            return;
        }
        IGrid grid = getMainNode().getGrid();
        if (grid != null) {
            var storage = grid.getStorageService().getInventory();
            for (AEKey gone : lastMarkedKeys) {
                if (now.contains(gone)) {
                    continue;
                }
                if (patternKeys.contains(gone)) {
                    continue; // 样板喂料中的材料不退（2026-09-02：防取消标记误退喂料致机器断料）
                }
                BigInteger amount = reservoir.remove(gone);
                if (amount != null && amount.signum() > 0 && storage != null) {
                    try {
                        long back = amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
                        long inserted = storage.insert(gone, back, Actionable.MODULATE, actionSource);
                        if (inserted < back) {
                            // 网络空间不足：未退回部分放回蓄水池，不丢（2026-09-02）
                            reservoir.merge(gone, BigInteger.valueOf(back - inserted),
                                    BigInteger::add);
                        }
                    } catch (RuntimeException ignored) {
                        reservoir.merge(gone, amount, BigInteger::add); // 回滚防丢
                    }
                }
            }
        }
        lastMarkedKeys = now;
    }

    @Override
    public void markByKey(int markerIndex, AEKey key) {
        if (markerIndex < 0 || markerIndex >= markerInv.getContainerSize()) {
            return;
        }
        if (key == null) {
            markerInv.setItem(markerIndex, ItemStack.EMPTY);
        } else {
            markerInv.setItem(markerIndex,
                    appeng.items.misc.WrappedGenericStack.wrap(key, 1));
        }
        setChanged();
        onMarkersChanged(); // 消失的标记 → 退回蓄水池缓存（2026-09-02）
    }

    @Override
    public void clearMarker(int markerIndex) {
        markByKey(markerIndex, null);
    }

    @Override
    public String[] reservoirSummary() {
        int types = 0;
        BigInteger total = BigInteger.ZERO;
        for (var entry : reservoir.entrySet()) {
            if (entry.getValue().signum() > 0) {
                types++;
                total = total.add(entry.getValue());
            }
        }
        return new String[] { String.valueOf(types), InfiniteInterfaceBE.fmt(total) };
    }

    @Override
    public BigInteger totalFed() {
        return totalFed;
    }

    @Override
    public long feedRatePerSecond() {
        return currentFeedRate;
    }

    @Override
    public long rejectRatePerSecond() {
        return currentRejectRate;
    }

    /** 每标记缓存目标步进（中键循环；与方块版一致）。 */
    private static final long[] TARGET_STEPS = {1_000L, 10_000L, 100_000L, 1_000_000L, Long.MAX_VALUE};

    // ── 每接口参数（GUI 保存；缺省 -1 用全局） ──

    @Override
    public long stockTargetValue() {
        return pStockTarget > 0 ? pStockTarget : InfiniteInterfaceBE.STOCK_TARGET;
    }

    @Override
    public int restockIntervalValue() {
        return pRestockInterval > 0 ? pRestockInterval : InfiniteInterfaceBE.RESTOCK_INTERVAL;
    }

    @Override
    public int feedBudgetValue() {
        return pFeedBudget > 0 ? pFeedBudget : InfiniteInterfaceBE.FEED_BUDGET;
    }

    @Override
    public void setPerBlockParam(String key, long value) {
        switch (key) {
            case "stockTarget" -> pStockTarget = Math.max(0, Math.min(Long.MAX_VALUE, value));
            case "restockInterval" -> pRestockInterval = (int) Math.max(0, Math.min(10000, value));
            case "feedBudget" -> pFeedBudget = (int) Math.max(0, Math.min(1_000_000, value));
            default -> {
                return;
            }
        }
        setChanged();
    }

    @Override
    public long targetFor(AEKey key) {
        Long v = markerTargets.get(key);
        if (v != null && v > 0) {
            return v;
        }
        return stockTargetValue();
    }

    @Override
    public void cycleMarkerTarget(int markerIndex) {
        if (markerIndex < 0 || markerIndex >= markerInv.getContainerSize()) {
            return;
        }
        ItemStack st = markerInv.getItem(markerIndex);
        if (st.isEmpty() || !(st.getItem() instanceof WrappedGenericStack wgs)) {
            return;
        }
        AEKey key = wgs.unwrapWhat(st);
        if (key == null) {
            return;
        }
        long cur = markerTargets.getOrDefault(key, 0L);
        long next = 0;
        for (long step : TARGET_STEPS) {
            if (step > cur) {
                next = step;
                break;
            }
        }
        if (next > 0) {
            markerTargets.put(key, next);
        } else {
            markerTargets.remove(key);
        }
        setChanged();
    }

    @Override
    public void setMarkerTarget(int markerIndex, long target) {
        if (markerIndex < 0 || markerIndex >= markerInv.getContainerSize()) {
            return;
        }
        ItemStack st = markerInv.getItem(markerIndex);
        if (st.isEmpty() || !(st.getItem() instanceof WrappedGenericStack wgs)) {
            return;
        }
        AEKey key = wgs.unwrapWhat(st);
        if (key == null) {
            return;
        }
        if (target > 0) {
            markerTargets.put(key, target);
        } else {
            markerTargets.remove(key);
        }
        setChanged();
    }

    // ── PatternContainer（样板管理终端兼容，2026-09-02 part 补齐） ──

    private final appeng.api.inventories.InternalInventory terminalPatternInv =
            new appeng.api.inventories.InternalInventory() {
                @Override
                public int size() {
                    return Math.min(patternInv.getContainerSize(), 9 + capacityCards() * 9);
                }

                @Override
                public ItemStack getStackInSlot(int slot) {
                    return slot >= 0 && slot < patternInv.getContainerSize()
                            ? patternInv.getItem(slot) : ItemStack.EMPTY;
                }

                @Override
                public void setItemDirect(int slot, ItemStack stack) {
                    if (slot >= 0 && slot < patternInv.getContainerSize()) {
                        patternInv.setItem(slot, stack);
                    }
                }

                @Override
                public int getSlotLimit(int slot) {
                    return 1;
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    if (slot >= 9 + capacityCards() * 9) {
                        return false;
                    }
                    return stack.isEmpty()
                            || appeng.api.crafting.PatternDetailsHelper.isEncodedPattern(stack);
                }
            };

    @Override
    public appeng.api.networking.IGrid getGrid() {
        return getMainNode().getGrid();
    }

    @Override
    public appeng.api.inventories.InternalInventory getTerminalPatternInventory() {
        return terminalPatternInv;
    }

    @Override
    public appeng.api.implementations.blockentities.PatternContainerGroup getTerminalGroup() {
        Direction front = getFront();
        Level lvl = getLevel();
        if (front != null && lvl != null) {
            var machine = lvl.getBlockEntity(getBlockPos().relative(front));
            if (machine != null) {
                var group = appeng.api.implementations.blockentities.PatternContainerGroup
                        .fromMachine(lvl, machine.getBlockPos(), front.getOpposite());
                if (group != null) {
                    return group;
                }
                net.minecraft.world.level.block.Block machineBlock =
                        machine.getBlockState().getBlock();
                return new appeng.api.implementations.blockentities.PatternContainerGroup(
                        appeng.api.stacks.AEItemKey.of(machineBlock),
                        machineBlock.getName(),
                        java.util.List.of());
            }
        }
        // 兜底：面板本体
        return new appeng.api.implementations.blockentities.PatternContainerGroup(
                appeng.api.stacks.AEItemKey.of(getPartItem().asItem()),
                getPartItem().asItem().getDescription(),
                java.util.List.of());
    }


    @Override
    public boolean activeExtract() {
        return activeExtract;
    }

    @Override
    public boolean activeFeed() {
        return activeFeed;
    }

    @Override
    public boolean activeMarkerFeed() {
        return activeMarkerFeed;
    }

    @Override
    public RelativeSide extractSide() {
        return extractSide;
    }

    @Override
    public void toggleActive(String which) {
        if ("extract".equals(which)) {
            activeExtract = !activeExtract;
        } else if ("feed".equals(which)) {
            activeFeed = !activeFeed;
        } else if ("markerFeed".equals(which)) {
            activeMarkerFeed = !activeMarkerFeed;
        } else if ("dir".equals(which)) {
            cycleExtractSide();
            return;
        }
        setChanged();
    }

    @Override
    public void cycleExtractSide() {
        var all = RelativeSide.values();
        int idx = java.util.Arrays.asList(all).indexOf(extractSide);
        extractSide = all[(idx + 1) % all.length];
        setChanged();
    }

    // ── capability（由线缆 host 暴露） ──

    private final net.minecraftforge.items.IItemHandler networkItemHandler =
            new net.minecraftforge.items.IItemHandler() {
                @Override
                public int getSlots() {
                    return 1;
                }

                @Override
                public ItemStack getStackInSlot(int slot) {
                    return ItemStack.EMPTY;
                }

                @Override
                public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    if (stack.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                    IGrid grid = getMainNode().getGrid();
                    if (grid == null) {
                        return stack;
                    }
                    try {
                        var key = appeng.api.stacks.AEItemKey.of(stack);
                        if (key == null) {
                            return stack;
                        }
                        long inserted = grid.getStorageService().getInventory().insert(
                                key, stack.getCount(),
                                simulate ? Actionable.SIMULATE : Actionable.MODULATE,
                                actionSource);
                        long rest = stack.getCount() - inserted;
                        if (rest > 0 && !simulate) {
                            cacheForNetwork(key, rest);
                        }
                        return ItemStack.EMPTY; // 全接收（入网 + 待入网缓存）
                    } catch (RuntimeException e) {
                        return stack;
                    }
                }

                @Override
                public ItemStack extractItem(int slot, int amount, boolean simulate) {
                    if (slot != 0 || amount <= 0) {
                        return ItemStack.EMPTY;
                    }
                    for (var entry : reservoir.entrySet()) {
                        if (entry.getValue().signum() <= 0
                                || !(entry.getKey() instanceof appeng.api.stacks.AEItemKey itemKey)) {
                            continue;
                        }
                        long take = entry.getValue()
                                .min(BigInteger.valueOf(amount)).min(BigInteger.valueOf(InfiniteInterfaceBE.FEED_STACK))
                                .longValue();
                        if (take <= 0) {
                            continue;
                        }
                        if (!simulate) {
                            subtractReservoir(itemKey, take);
                            totalFed = totalFed.add(BigInteger.valueOf(take));
                            setChanged();
                        }
                        return itemKey.toStack((int) take);
                    }
                    return ItemStack.EMPTY;
                }

                @Override
                public int getSlotLimit(int slot) {
                    return (int) Math.min(Math.max(InfiniteInterfaceBE.STOCK_TARGET, 1), Integer.MAX_VALUE);
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    return !stack.isEmpty();
                }
            };

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capabilityClass) {
        if (capabilityClass == appeng.capabilities.Capabilities.STORAGE) {
            var grid = getMainNode().getGrid();
            if (grid == null) {
                return LazyOptional.empty();
            }
            var networkStorage = grid.getStorageService().getInventory();
            return networkStorage == null ? LazyOptional.empty()
                    : LazyOptional.of(() -> networkStorage).cast();
        }
        if (capabilityClass == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER) {
            return LazyOptional.of(() -> networkItemHandler).cast();
        }
        return super.getCapability(capabilityClass);
    }

    // ── GUI ──

    @Override
    public boolean onPartActivate(Player p, InteractionHand hand, Vec3 pos) {
        if (!p.getCommandSenderWorld().isClientSide()) {
            if (p instanceof ServerPlayer sp) {
                NetworkHooks.openScreen(sp,
                        new SimpleMenuProvider((containerId, inventory, ignored) ->
                                new InfiniteInterfaceMenu(containerId, inventory, this),
                                Component.translatable("gui.ae2addon.infinite_interface.title")),
                        buffer -> MenuLocators.writeToPacket(buffer, MenuLocators.forPart(this)));
            }
        }
        return true;
    }

    // ── NBT ──

    @Override
    public void readFromNBT(CompoundTag data) {
        super.readFromNBT(data);
        activeExtract = data.getBoolean("activeExtract");
        activeFeed = data.getBoolean("activeFeed");
        if (data.contains("activeMarkerFeed")) {
            activeMarkerFeed = data.getBoolean("activeMarkerFeed");
        }
        try {
            extractSide = RelativeSide.valueOf(data.getString("extractSide"));
        } catch (RuntimeException ignored) {
            extractSide = RelativeSide.FRONT;
        }
        pStockTarget = data.contains("pStockTarget") ? data.getLong("pStockTarget") : -1;
        pRestockInterval = data.contains("pRestockInterval") ? data.getInt("pRestockInterval") : -1;
        pFeedBudget = data.contains("pFeedBudget") ? data.getInt("pFeedBudget") : -1;
        markerTargets.clear();
        ListTag targetList = data.getList("markerTargets", Tag.TAG_COMPOUND);
        for (int i = 0; i < targetList.size(); i++) {
            CompoundTag entry = targetList.getCompound(i);
            try {
                var stack = ItemStack.of(entry.getCompound("Key"));
                AEKey wrapped = null;
                if (stack.getItem() instanceof WrappedGenericStack wgs) {
                    wrapped = wgs.unwrapWhat(stack);
                }
                if (wrapped != null) {
                    markerTargets.put(wrapped, entry.getLong("Target"));
                }
            } catch (RuntimeException ignored) {
            }
        }
        reservoir.clear();
        patternKeys.clear();
        pendingNetworkKeys.clear();
        ListTag reservoirList = data.getList("reservoir", Tag.TAG_COMPOUND);
        for (int i = 0; i < reservoirList.size(); i++) {
            CompoundTag entry = reservoirList.getCompound(i);
            try {
                AEKey key = AEKey.fromTagGeneric(entry.getCompound("key"));
                BigInteger amount = new BigInteger(entry.getString("amount"));
                if (key != null && amount.signum() > 0) {
                    reservoir.put(key, amount);
                }
            } catch (RuntimeException ignored) {
            }
        }
        for (int i = 0; i < patternInv.getContainerSize(); i++) {
            ItemStack st = ItemStack.of(data.getCompound("pat" + i));
            patternInv.setItem(i, st);
            if (!st.isEmpty()) {
                patternDirty = true;
            }
        }
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            markerInv.setItem(i, ItemStack.of(data.getCompound("mark" + i)));
        }
        ListTag pendingList = data.getList("pendingNetwork", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            try {
                AEKey key = AEKey.fromTagGeneric(pendingList.getCompound(i).getCompound("key"));
                if (key != null && reservoir.containsKey(key)) {
                    pendingNetworkKeys.add(key);
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void writeToNBT(CompoundTag data) {
        super.writeToNBT(data);
        data.putBoolean("activeExtract", activeExtract);
        data.putBoolean("activeFeed", activeFeed);
        data.putBoolean("activeMarkerFeed", activeMarkerFeed);
        data.putString("extractSide", extractSide.name());
        data.putLong("pStockTarget", pStockTarget);
        data.putInt("pRestockInterval", pRestockInterval);
        data.putInt("pFeedBudget", pFeedBudget);
        ListTag targetList = new ListTag();
        for (var e : markerTargets.entrySet()) {
            CompoundTag ent = new CompoundTag();
            ent.put("Key", WrappedGenericStack.wrap(e.getKey(), 1).save(new CompoundTag()));
            ent.putLong("Target", e.getValue());
            targetList.add(ent);
        }
        data.put("markerTargets", targetList);
        ListTag reservoirList = new ListTag();
        for (var entry : reservoir.entrySet()) {
            CompoundTag ent = new CompoundTag();
            ent.put("key", entry.getKey().toTagGeneric());
            ent.putString("amount", entry.getValue().toString());
            reservoirList.add(ent);
        }
        data.put("reservoir", reservoirList);
        for (int i = 0; i < patternInv.getContainerSize(); i++) {
            ItemStack st = patternInv.getItem(i);
            if (!st.isEmpty()) {
                CompoundTag ent = new CompoundTag();
                st.save(ent);
                data.put("pat" + i, ent);
            }
        }
        for (int i = 0; i < markerInv.getContainerSize(); i++) {
            ItemStack st = markerInv.getItem(i);
            if (!st.isEmpty()) {
                CompoundTag ent = new CompoundTag();
                st.save(ent);
                data.put("mark" + i, ent);
            }
        }
        ListTag pendingList = new ListTag();
        for (AEKey key : pendingNetworkKeys) {
            CompoundTag ent = new CompoundTag();
            ent.put("key", key.toTagGeneric());
            pendingList.add(ent);
        }
        data.put("pendingNetwork", pendingList);
    }

    // ── 样板解码 ──

    private void rebuildPatterns() {
        patternDirty = false;
        Level lvl = getLevel();
        List<IPatternDetails> list = new ArrayList<>();
        if (lvl != null) {
            for (int i = 0; i < patternInv.getContainerSize(); i++) {
                ItemStack stack = patternInv.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                IPatternDetails details = PatternDetailsHelper.decodePattern(stack, lvl);
                if (details != null && !details.getClass().getName().endsWith("AECraftingPattern")) {
                    list.add(details);
                }
            }
        }
        patterns = List.copyOf(list);
        if (getMainNode().isReady()) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    // ── 其他 ──

    public void saveChanges() {
        getHost().markForSave();
    }

    @Override
    public void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
    }

    @Override
    public boolean readFromStream(FriendlyByteBuf data) {
        return super.readFromStream(data);
    }

    private static final class Holder {
        static final java.util.Map<BlockPos, Boolean> UNUSED = new java.util.HashMap<>();
    }
}
