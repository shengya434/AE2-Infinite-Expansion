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
import appeng.api.stacks.AEFluidKey;
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
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
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
public class InfiniteInterfaceBE extends AENetworkBlockEntity
        implements ICraftingProvider, appeng.helpers.patternprovider.PatternContainer,
        appeng.api.upgrades.IUpgradeableObject,
        appeng.api.networking.crafting.ICraftingRequester {

    // ── 全局注册表（供 CPU mixin 查询：全量推送判定 / 取消回退） ──

    private static final java.util.Set<InfiniteInterfaceBE> ACTIVE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 当前 CPU 任务推送到本接口的材料归属：CPU簇 → 物品 → 数量。 */
    private final Map<Object, Map<AEKey, BigInteger>> pushedByCluster = new HashMap<>();

    /**
     * 是否有同网格的无限接口声明了该样板（CPU mixin 全量推送判定用）。
     * 只查同网格：避免跨网络误判导致全量推给不相干的 provider 全部拒收卡任务。
     */
    public static boolean hasFeederFor(appeng.api.networking.IGrid grid, IPatternDetails pattern) {
        if (grid == null || pattern == null) {
            return false;
        }
        for (var be : ACTIVE) {
            if (be.isRemoved()) {
                continue;
            }
            try {
                if (be.getMainNode().getGrid() != grid) {
                    continue;
                }
                if (be.patterns.contains(pattern)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    /** CPU 任务取消：所有接口把该簇推送的未喂出材料插回网络。 */
    public static void returnPushedFor(Object cluster) {
        if (cluster == null) {
            return;
        }
        for (var be : ACTIVE) {
            if (be.isRemoved()) {
                continue;
            }
            be.returnPushedForCluster(cluster);
        }
    }

    /** 新任务开始：清空该簇的归属记录（材料保留在接口，正常交付语义）。 */
    public static void resetPushedFor(Object cluster) {
        if (cluster == null) {
            return;
        }
        for (var be : ACTIVE) {
            if (be.isRemoved()) {
                continue;
            }
            be.pushedByCluster.remove(cluster);
        }
    }

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
        FEED_STACK = AE2AddonConfig.feederFeedStack();
        RESTOCK_INTERVAL = Math.max(1, AE2AddonConfig.feederRestockInterval());
    }

    // ── 蓄水池（BigInteger 防溢出；CPU N× 直灌可达 2^63-1/批） ──

    private final Map<AEKey, BigInteger> reservoir = new LinkedHashMap<>();

    /** 累计已喂出总量（机器/漏斗实际收到的物品数；供料站流量可见性）。 */
    private BigInteger totalFed = BigInteger.ZERO;

    /** 补货提取失败计数（诊断节流用）。 */
    private long restockFailCount;

    /** 推送速率统计：当前 1 秒窗口内喂出数 / 上一秒速率（items/s）。 */
    private long rateWindowFed;
    private long currentFeedRate;

    /** 拒收统计：整 tick 零喂出（机器满/拒收）的次数窗口 / 上一秒速率。 */
    private long rejectWindow;
    private long currentRejectRate;

    // ── 样板槽（3×3，声明可处理的配方；CPU 路由靠它） ──

    private final SimpleContainer patternInv = new SimpleContainer(45) {
        @Override
        public void setChanged() {
            super.setChanged();
            InfiniteInterfaceBE.this.onPatternsChanged();
        }
    };

    // ── 标记槽（3×3，声明自动补货物品；与样板定量语义解耦） ──
    // 样板 = 定量（CPU 推多少发多少，发完停）；标记 = 无限供料（标记的物品
    // 持续从网络补到 feederStockTarget，机器永远有货）。

    private final SimpleContainer markerInv = new SimpleContainer(45) {
        @Override
        public void setChanged() {
            super.setChanged();
            InfiniteInterfaceBE.this.onMarkersChanged();
        }
    };

    private List<IPatternDetails> patterns = List.of();
    private boolean patternDirty = false;

    // ── 升级槽（容量卡=双槽各+1行；加速卡=喂出预算×2） ──

    private final appeng.api.upgrades.IUpgradeInventory upgrades =
            appeng.api.upgrades.UpgradeInventories.forMachine(
                    com.ae2addon.init.ModBlocks.INFINITE_INTERFACE.get(), 9,
                    this::onUpgradesChanged);

    /**
     * 样板管理终端（Pattern Access Terminal）兼容适配器：终端通过 PatternContainer
     * 发现本方块并远程读写样板槽（2026-08-28 sensei 反馈：此前不实现 PatternContainer
     * → 终端看不到/管不了我们的样板）。
     * 写入走 patternInv.setItem → SimpleContainer.setChanged → onPatternsChanged →
     * requestUpdate，CPU 路由即时刷新。
     */
    private final appeng.api.inventories.InternalInventory terminalPatternInv =
            new appeng.api.inventories.InternalInventory() {
                @Override
                public int size() {
                    return patternInv.getContainerSize();
                }

                @Override
                public ItemStack getStackInSlot(int slot) {
                    return patternInv.getItem(slot);
                }

                @Override
                public void setItemDirect(int slot, ItemStack stack) {
                    patternInv.setItem(slot, stack);
                }

                @Override
                public int getSlotLimit(int slot) {
                    return 1;
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    return stack.isEmpty() || PatternDetailsHelper.isEncodedPattern(stack);
                }
            };

    private final IActionSource actionSource = new MachineSource(this);

    /** 正面 IItemHandler（机器/漏斗从这里抽；insertItem 一律拒收防回流死循环）。 */
    private final LazyOptional<IItemHandler> frontHandler =
            LazyOptional.of(() -> new FrontItemHandler());

    public InfiniteInterfaceBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INFINITE_INTERFACE.get(), pos, state);
        // 世界加载时触发跨 mod 升级卡懒注册（AppFlux/ExtendedAE+；构造器里注册会崩）
        com.ae2addon.AE2Addon.ensureCompatUpgrades();
    }

    // ── 诊断日志（定位供料问题用） ──

    private boolean feederDiagLogged;

    // ── 虚拟合成卡（CRAFTING_CARD）：补货提取失败且可合成时请求 CPU 合成 ──

    /** key → 上次发起合成请求的 gameTime（防重复请求节流）。 */
    private final java.util.Map<AEKey, Long> craftingRequests = new java.util.HashMap<>();

    /** 同 key 合成请求冷却（tick；5 秒）。 */
    private static final long CRAFT_COOLDOWN = 100;

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
        // 蓄水池构成明细（物品/流体/气体/其他）
        int items = 0, fluids = 0, gases = 0, others = 0;
        for (var entry : reservoir.entrySet()) {
            if (entry.getValue().signum() <= 0) {
                continue;
            }
            if (entry.getKey() instanceof AEItemKey) {
                items++;
            } else if (entry.getKey() instanceof AEFluidKey) {
                fluids++;
            } else if (com.ae2addon.compat.MekanismGasCompat.isFeedable(entry.getKey())) {
                gases++;
            } else {
                others++;
            }
        }
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][feeder] {} pos={} facing={} front={} 目标方块={} 蓄水池={}种"
                        + "（物品{} 流体{} 气体{} 其他{}）/合计{}",
                tag, worldPosition, facing, front, targetName, summary[0],
                items, fluids, gases, others, summary[1]);
    }

    @Override
    public void onReady() {
        super.onReady();
        ACTIVE.add(this);
    }

    @Override
    public void onChunkUnloaded() {
        ACTIVE.remove(this);
        disconnectChannelLink();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        ACTIVE.remove(this);
        disconnectChannelLink();
        super.setRemoved();
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
        Object pusher = com.ae2addon.crafting.CraftingCompat.currentPushingCluster;
        Map<AEKey, BigInteger> perCluster = null;
        if (pusher != null) {
            perCluster = pushedByCluster.computeIfAbsent(pusher, k -> new HashMap<>());
        }
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
                        if (perCluster != null) {
                            perCluster.merge(key, BigInteger.valueOf(amount), BigInteger::add);
                        }
                    }
                }
            }
        }
        var summary = reservoirSummary();
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][feeder] pushPattern 接收 pattern={} 本次{}种/{}个 → 蓄水池={}种/合计{}（推送源={}）",
                patternDetails == null ? "null" : patternDetails.getClass().getSimpleName(),
                inputTypes, inputCount, summary[0], summary[1],
                pusher == null ? "外部/未知" : pusher.getClass().getSimpleName());
        setChanged();
        return true;
    }

    /** 取消回退：把指定 CPU 簇推送、尚未喂出的材料插回网络。 */
    private void returnPushedForCluster(Object cluster) {
        Map<AEKey, BigInteger> pushed = pushedByCluster.remove(cluster);
        if (pushed == null || pushed.isEmpty()) {
            return;
        }
        appeng.api.networking.IGrid grid = getMainNode().getGrid();
        appeng.api.storage.MEStorage storage = grid == null
                ? null : grid.getStorageService().getInventory();
        BigInteger returned = BigInteger.ZERO;
        for (var entry : pushed.entrySet()) {
            AEKey key = entry.getKey();
            long have = reservoirAmount(key);
            long back = entry.getValue()
                    .min(BigInteger.valueOf(have))
                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            if (back <= 0) {
                continue;
            }
            if (storage != null) {
                long inserted = storage.insert(key, back, Actionable.MODULATE, actionSource);
                if (inserted > 0) {
                    subtractReservoir(key, inserted);
                    returned = returned.add(BigInteger.valueOf(inserted));
                }
            } else {
                subtractReservoir(key, back);
                returned = returned.add(BigInteger.valueOf(back));
            }
        }
        if (returned.signum() > 0) {
            setChanged();
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][feeder] CPU任务取消，材料回退网络 {} 个（{}种），剩余蓄水池={}种/合计{}",
                    fmt(returned), pushed.size(), reservoirSummary()[0],
                    fmt(totalAmount()));
        }
    }

    /** 永不拒收（蓄水池无限）。 */
    @Override
    public boolean isBusy() {
        return false;
    }

    // ── 升级（IUpgradeableObject） ──

    @Override
    public appeng.api.upgrades.IUpgradeInventory getUpgrades() {
        return upgrades;
    }

    // ── 网络入口（2026-08-28 sensei：接口当然要有入口）──
    // 非正面暴露物品/流体能力：外界（管道/漏斗/其他 mod）塞进来的东西直接进网络。
    // 正面保持喂机器。抽走暂不支持（虚拟槽不可见；要抽从网络其他口抽）。

    private final net.minecraftforge.items.IItemHandler networkItemHandler =
            new net.minecraftforge.items.IItemHandler() {
                @Override
                public int getSlots() {
                    return 1;
                }

                @Override
                public net.minecraft.world.item.ItemStack getStackInSlot(int slot) {
                    if (slot != 0) {
                        return net.minecraft.world.item.ItemStack.EMPTY;
                    }
                    // 蓄水池最多物品预览（管道能看到 → 可抽取）
                    var best = largestItem();
                    if (best == null || !(best.getKey() instanceof AEItemKey itemKey)) {
                        return net.minecraft.world.item.ItemStack.EMPTY;
                    }
                    long amount = best.getValue()
                            .min(BigInteger.valueOf(FEED_STACK)).longValue();
                    return itemKey.toStack((int) Math.max(1, amount));
                }

                @Override
                public net.minecraft.world.item.ItemStack insertItem(int slot,
                        net.minecraft.world.item.ItemStack stack, boolean simulate) {
                    if (stack.isEmpty()) {
                        return net.minecraft.world.item.ItemStack.EMPTY;
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
                        if (inserted <= 0) {
                            return stack;
                        }
                        if (inserted >= stack.getCount()) {
                            return net.minecraft.world.item.ItemStack.EMPTY;
                        }
                        net.minecraft.world.item.ItemStack rest = stack.copy();
                        rest.setCount(stack.getCount() - (int) inserted);
                        return rest;
                    } catch (RuntimeException e) {
                        return stack;
                    }
                }

                @Override
                public net.minecraft.world.item.ItemStack extractItem(int slot, int amount,
                        boolean simulate) {
                    // 从蓄水池抽取（与正面一致；管道/漏斗可从侧面抽缓存）
                    if (slot != 0 || amount <= 0) {
                        return net.minecraft.world.item.ItemStack.EMPTY;
                    }
                    var best = largestItem();
                    if (best == null || !(best.getKey() instanceof AEItemKey itemKey)) {
                        return net.minecraft.world.item.ItemStack.EMPTY;
                    }
                    long take = best.getValue()
                            .min(BigInteger.valueOf(amount)).min(BigInteger.valueOf(FEED_STACK))
                            .longValue();
                    if (take <= 0) {
                        return net.minecraft.world.item.ItemStack.EMPTY;
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
                    return 64;
                }

                @Override
                public boolean isItemValid(int slot, net.minecraft.world.item.ItemStack stack) {
                    return !stack.isEmpty();
                }
            };

    private final net.minecraftforge.fluids.capability.IFluidHandler networkFluidHandler =
            new net.minecraftforge.fluids.capability.IFluidHandler() {
                @Override
                public int getTanks() {
                    return 1;
                }

                @Override
                public net.minecraftforge.fluids.FluidStack getFluidInTank(int tank) {
                    return net.minecraftforge.fluids.FluidStack.EMPTY;
                }

                @Override
                public int getTankCapacity(int tank) {
                    return Integer.MAX_VALUE;
                }

                @Override
                public boolean isFluidValid(int tank, net.minecraftforge.fluids.FluidStack stack) {
                    return !stack.isEmpty();
                }

                @Override
                public int fill(net.minecraftforge.fluids.FluidStack resource,
                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction action) {
                    if (resource.isEmpty()) {
                        return 0;
                    }
                    IGrid grid = getMainNode().getGrid();
                    if (grid == null) {
                        return 0;
                    }
                    try {
                        var key = appeng.api.stacks.AEFluidKey.of(resource.getFluid());
                        long inserted = grid.getStorageService().getInventory().insert(
                                key, resource.getAmount(),
                                action.simulate() ? Actionable.SIMULATE : Actionable.MODULATE,
                                actionSource);
                        return (int) inserted;
                    } catch (RuntimeException e) {
                        return 0;
                    }
                }

                @Override
                public net.minecraftforge.fluids.FluidStack drain(
                        net.minecraftforge.fluids.FluidStack resource,
                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction action) {
                    return net.minecraftforge.fluids.FluidStack.EMPTY; // 不支持抽出
                }

                @Override
                public net.minecraftforge.fluids.FluidStack drain(int maxDrain,
                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction action) {
                    return net.minecraftforge.fluids.FluidStack.EMPTY; // 不支持抽出
                }
            };



    /** 容量卡数量（0-2）：每张样板槽+标记槽各加一行（3格）。 */
    public int capacityCards() {
        return upgrades.getInstalledUpgrades(
                appeng.core.definitions.AEItems.CAPACITY_CARD.asItem());
    }

    /** 速度卡数量（0-2）：每张喂出预算 ×2。 */
    /** 当前活动的样板槽数（9 + 容量卡×9，分页显示）。 */
    public int activePatternSlots() {
        return 9 + capacityCards() * 9;
    }

    /** 当前活动的标记槽数（9 + 容量卡×9，分页显示）。 */
    public int activeMarkerSlots() {
        return 9 + capacityCards() * 9;
    }

    /** 最大页数（0 基）：容量卡数（基础页 + 每卡一页）。 */
    public int maxPage() {
        return capacityCards();
    }

    /** 感应卡（红石门控喂出）。 */
    public boolean hasRedstoneCard() {
        return upgrades.getInstalledUpgrades(
                appeng.core.definitions.AEItems.REDSTONE_CARD.asItem()) > 0;
    }

    /** 反向卡（反转红石信号；无感应卡时无效）。 */
    public boolean hasInverterCard() {
        return upgrades.getInstalledUpgrades(
                appeng.core.definitions.AEItems.INVERTER_CARD.asItem()) > 0;
    }

    /** 虚拟合成卡（补货不足时请求合成，待实现）。 */
    public boolean hasCraftingCard() {
        return upgrades.getInstalledUpgrades(
                appeng.core.definitions.AEItems.CRAFTING_CARD.asItem()) > 0;
    }

    /** ICraftingSimulationRequester：合成模拟请求的来源。 */
    public appeng.api.networking.security.IActionSource getActionSource() {
        return actionSource;
    }

    /** AppFlux 感应卡（给机器供电）。 */
    public boolean hasInductionCard() {
        var card = com.ae2addon.compat.AppFluxPowerCompat.inductionCard();
        return card != null && upgrades.getInstalledUpgrades(card) > 0;
    }

    /** ExtendedAE+ 频道卡（无线连网）。 */
    public boolean hasChannelCard() {
        var card = com.ae2addon.compat.ExtendedAEPlusCompat.channelCard();
        return card != null && upgrades.getInstalledUpgrades(card) > 0;
    }

    /** ExtendedAE+ 虚拟合成卡（最后一次发配即完成）。 */
    public boolean hasVirtualCraftingCard() {
        var card = com.ae2addon.compat.ExtendedAEPlusCompat.virtualCraftingCard();
        return card != null && upgrades.getInstalledUpgrades(card) > 0;
    }

    // ── 频道卡无线链路（ExtendedAE+） ──

    /** 无线从端链路（WirelessSlaveLink；仅装卡且 mod 加载时非 null）。 */
    private com.extendedae_plus.ae.wireless.WirelessSlaveLink channelLink;

    private void updateChannelLink() {
        if (!com.ae2addon.compat.ExtendedAEPlusCompat.isLoaded()) {
            return;
        }
        var card = com.ae2addon.compat.ExtendedAEPlusCompat.channelCard();
        long channel = -1;
        java.util.UUID owner = null;
        if (card != null) {
            for (int i = 0; i < upgrades.size(); i++) {
                var stack = upgrades.getStackInSlot(i);
                if (stack.getItem() == card) {
                    channel = com.extendedae_plus.items.materials.ChannelCardItem.getChannel(stack);
                    owner = com.extendedae_plus.items.materials.ChannelCardItem.getOwnerUUID(stack);
                    break;
                }
            }
        }
        try {
            if (channel < 0) {
                // 无卡/无效卡：断开
                if (channelLink != null) {
                    com.extendedae_plus.util.wireless.ChannelCardLinkHelper.disconnect(channelLink);
                    channelLink = null;
                }
                return;
            }
            if (channelLink == null) {
                var endpoint = new com.extendedae_plus.ae.wireless.endpoint.GenericNodeEndpointImpl(
                        () -> this, () -> getMainNode().getNode());
                channelLink = new com.extendedae_plus.ae.wireless.WirelessSlaveLink(endpoint);
            }
            channelLink.setPlacerId(owner);
            channelLink.setFrequency(channel);
            channelLink.updateStatus();
        } catch (RuntimeException ignored) {
            // 无线系统异常不影响主功能
        }
    }

    private void disconnectChannelLink() {
        if (channelLink != null) {
            try {
                channelLink.onUnloadOrRemove();
            } catch (RuntimeException ignored) {
            }
            channelLink = null;
        }
    }

    /** 红石门控：感应卡安装时，信号高=喂出（反向卡则反转）。 */
    private boolean redstoneAllowsFeed() {
        if (!hasRedstoneCard()) {
            return true;
        }
        boolean powered = level != null && level.hasNeighborSignal(worldPosition);
        return hasInverterCard() ? !powered : powered;
    }

    private void onUpgradesChanged() {
        // 容量卡强制上限 4（双保险；容器过滤失效时兜底）
        var capCard = appeng.core.definitions.AEItems.CAPACITY_CARD.asItem();
        if (upgrades.getInstalledUpgrades(capCard) > 4) {
            for (int i = 0; i < upgrades.size(); i++) {
                if (upgrades.getStackInSlot(i).getItem() == capCard) {
                    upgrades.setItemDirect(i, net.minecraft.world.item.ItemStack.EMPTY);
                    break;
                }
            }
        }
        setChanged();
        updateChannelLink();
        dumpUpgradeCards(); // 插拔卡立即打日志（不再等 64 tick 门控）
    }

    /** 升级卡诊断（每 64 tick 或升级变化时打一次）。 */
    private void dumpUpgradeCards() {
        com.ae2addon.AE2Addon.ensureCompatUpgrades(); // 幂等补注册（BE 构造失败时重试）
        try {
            var block = com.ae2addon.init.ModBlocks.INFINITE_INTERFACE.get();
            var cap = appeng.core.definitions.AEItems.CAPACITY_CARD.asItem();
            var blockItem = block.asItem();
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][cards] 注册表诊断: block.asItem={} max(blockItem)={} max(AIR)={} max(block)={}",
                    blockItem,
                    appeng.api.upgrades.Upgrades.getMaxInstallable(cap, blockItem),
                    appeng.api.upgrades.Upgrades.getMaxInstallable(cap, net.minecraft.world.item.Items.AIR),
                    appeng.api.upgrades.Upgrades.getMaxInstallable(cap, net.minecraft.world.item.Item.byBlock(block)));
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][cards] 注册表诊断失败", e);
        }
        try {
            java.util.List<String> contents = new java.util.ArrayList<>();
            for (int i = 0; i < upgrades.size(); i++) {
                contents.add(upgrades.getStackInSlot(i).getItem().toString());
            }
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][cards] 容量={} 红石={} 反向={} 合成={} 感应={} 频道={} 虚拟={} | 总页={} | 槽: {}",
                    upgrades.getInstalledUpgrades(appeng.core.definitions.AEItems.CAPACITY_CARD.asItem()),
                    upgrades.getInstalledUpgrades(appeng.core.definitions.AEItems.REDSTONE_CARD.asItem()),
                    upgrades.getInstalledUpgrades(appeng.core.definitions.AEItems.INVERTER_CARD.asItem()),
                    upgrades.getInstalledUpgrades(appeng.core.definitions.AEItems.CRAFTING_CARD.asItem()),
                    hasInductionCard() ? 1 : 0,
                    hasChannelCard() ? 1 : 0,
                    hasVirtualCraftingCard() ? 1 : 0,
                    maxPage() + 1,
                    contents);
        } catch (RuntimeException ignored) {
        }
    }

    // ── PatternContainer（样板管理终端兼容） ──

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
        // 优先显示正面贴着的机器（2026-08-28 sensei 要求）
        Direction front = getFront();
        if (front != null && level != null) {
            var machine = level.getBlockEntity(worldPosition.relative(front));
            if (machine != null) {
                // ICraftingMachine 机器（ExtendedAE 等）→ 官方信息；否则取方块图标+名称
                var group = appeng.api.implementations.blockentities.PatternContainerGroup
                        .fromMachine(level, machine.getBlockPos(), front.getOpposite());
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
        // 兜底：本方块
        return new appeng.api.implementations.blockentities.PatternContainerGroup(
                appeng.api.stacks.AEItemKey.of(com.ae2addon.init.ModBlocks.INFINITE_INTERFACE.get()),
                net.minecraft.network.chat.Component.translatable(
                        getBlockState().getBlock().getDescriptionId()),
                java.util.List.of());
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
        if ((lvl.getGameTime() & 0x3F) == 0 && hasChannelCard()) {
            updateChannelLink(); // 每 3 秒刷新无线链路（主端变动/延迟连接）
        }
        if ((lvl.getGameTime() & 19) == 0) {
            currentFeedRate = rateWindowFed;
            rateWindowFed = 0;
            currentRejectRate = rejectWindow;
            rejectWindow = 0;
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
        feedMachinePower(); // 感应卡供电独立于喂出（蓄水池空也供电）
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
            long have = reservoirAmount(key);
            long want = STOCK_TARGET - have;
            if (want <= 0) {
                continue;
            }
            long got = storage.extract(key, want, Actionable.MODULATE, actionSource);
            if (got > 0) {
                addReservoir(key, BigInteger.valueOf(got));
                setChanged();
            } else if (hasCraftingCard() && isCraftable(key)) {
                // 虚拟合成卡：网络没有 → 请求 CPU 合成
                requestCrafting(key, want);
            } else if (key instanceof AEFluidKey
                    || com.ae2addon.compat.MekanismGasCompat.isFeedable(key)) {
                // 流体/气体提取失败诊断（节流：每 100 次打一条）
                if ((++restockFailCount & 99) == 0) {
                    com.ae2addon.AE2Addon.LOGGER.warn(
                            "[ae2addon][feeder] 补货提取失败: key={} 想要={} 网络无该流体/气体？",
                            key, want);
                }
            }
        }
    }

    /** 网络是否可合成该 key（虚拟合成卡）。 */
    private boolean isCraftable(AEKey key) {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return false;
        }
        try {
            return grid.getCraftingService().isCraftable(key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 发起 CPU 合成请求（异步；AE2-VM 兼容——beginCraftingCalculation 由 VM 接管）。 */
    private void requestCrafting(AEKey key, long amount) {
        IGrid grid = getMainNode().getGrid();
        if (grid == null || level == null || level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        Long last = craftingRequests.get(key);
        if (last != null && now - last < CRAFT_COOLDOWN) {
            return; // 冷却中，防刷屏
        }
        craftingRequests.put(key, now);
        try {
            var service = grid.getCraftingService();
            // 匿名模拟请求者（BE 不能 implements：getGridNode default 与父类冲突）
            var simulationRequester = new appeng.api.networking.crafting.ICraftingSimulationRequester() {
                @Override
                public appeng.api.networking.security.IActionSource getActionSource() {
                    return actionSource;
                }
            };
            java.util.concurrent.Future<appeng.api.networking.crafting.ICraftingPlan> future =
                    service.beginCraftingCalculation(level, simulationRequester, key, amount,
                            appeng.api.networking.crafting.CalculationStrategy.CRAFT_LESS);
            // Future（非 CompletableFuture）：后台线程等待计算结果，完成后切主线程提交
            java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return future.get(15, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception e) {
                            com.ae2addon.AE2Addon.LOGGER.warn(
                                    "[ae2addon][feeder] 虚拟合成计算失败: {} {}", key, e);
                            return null;
                        }
                    })
                    .thenAccept(plan -> {
                        if (plan == null || plan.simulation() || plan.bytes() <= 0) {
                            return;
                        }
                        if (level != null && level.getServer() != null) {
                            level.getServer().execute(() -> {
                                try {
                                    var result = service.submitJob(plan, this, null, false, actionSource);
                                    if (result != null && result.successful()) {
                                        com.ae2addon.AE2Addon.LOGGER.info(
                                                "[ae2addon][feeder] 虚拟合成卡: 提交合成 {} x{}",
                                                key, plan.bytes());
                                    } else {
                                        com.ae2addon.AE2Addon.LOGGER.warn(
                                                "[ae2addon][feeder] 虚拟合成卡提交未成功: {} 错误={}",
                                                key, result == null ? "null" : result.errorCode());
                                    }
                                } catch (RuntimeException e) {
                                    com.ae2addon.AE2Addon.LOGGER.warn(
                                            "[ae2addon][feeder] 虚拟合成卡提交失败: {} {}", key, e);
                                }
                            });
                        }
                    });
        } catch (RuntimeException e) {
            craftingRequests.remove(key);
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon][feeder] 虚拟合成请求异常: {} {}", key, e);
        }
    }

    // ── ICraftingRequester（虚拟合成卡：CPU 产物直接进蓄水池） ──

    @Override
    public com.google.common.collect.ImmutableSet<appeng.api.networking.crafting.ICraftingLink> getRequestedJobs() {
        return com.google.common.collect.ImmutableSet.of();
    }

    @Override
    public long insertCraftedItems(appeng.api.networking.crafting.ICraftingLink link,
            AEKey what, long amount, appeng.api.config.Actionable actionable) {
        if (actionable == appeng.api.config.Actionable.MODULATE && amount > 0) {
            addReservoir(what, BigInteger.valueOf(amount));
            setChanged();
        }
        return amount; // 全收（进蓄水池，随后按机器容量喂出）
    }

    @Override
    public void jobStateChange(appeng.api.networking.crafting.ICraftingLink link) {
        // 合成结束/取消：清冷却，允许稍后重试
        if (link != null && link.getCraftingID() != null) {
            craftingRequests.entrySet().removeIf(e ->
                    e.getKey().toString().equals(link.getCraftingID().toString()));
        } else {
            craftingRequests.clear();
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
            // 右键标记的 WrappedGenericStack（流体/气体等任意 key）→ 直接取 key
            if (stack.getItem() instanceof appeng.items.misc.WrappedGenericStack wgs) {
                AEKey wrapped = wgs.unwrapWhat(stack);
                if (wrapped != null) {
                    keys.add(wrapped);
                    continue;
                }
            }
            // 旧方式兜底：流体容器（桶/罐/蓄液罐）→ 标记内部流体；否则标记物品
            var contained = FluidUtil.getFluidContained(stack);
            if (contained.isPresent() && !contained.get().isEmpty()) {
                keys.add(AEFluidKey.of(contained.get()));
                continue;
            }
            // 气体容器（气罐/气桶）→ 标记内部气体
            AEKey gasKey = com.ae2addon.compat.MekanismGasCompat.chemicalInContainer(stack);
            if (gasKey != null) {
                keys.add(gasKey);
                continue;
            }
            AEItemKey key = AEItemKey.of(stack);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 查找机器的流体 handler：指定面优先，找不到遍历其余面。 */
    private static IFluidHandler findFluidHandler(BlockEntity target, Direction primary) {
        if (target == null) {
            return null;
        }
        LazyOptional<IFluidHandler> cap = target.getCapability(
                ForgeCapabilities.FLUID_HANDLER, primary);
        if (cap.isPresent()) {
            IFluidHandler handler = cap.orElse(null);
            if (handler != null) {
                return handler;
            }
        }
        for (Direction side : Direction.values()) {
            if (side == primary) {
                continue;
            }
            LazyOptional<IFluidHandler> c = target.getCapability(
                    ForgeCapabilities.FLUID_HANDLER, side);
            if (c.isPresent()) {
                IFluidHandler handler = c.orElse(null);
                if (handler != null) {
                    return handler;
                }
            }
        }
        return null;
    }

    /** ③ 按机器容量分批喂出：insertItem 拒收的余量留在蓄水池。 */
    private void feedMachine() {
        if (!redstoneAllowsFeed()) {
            return; // 感应卡：红石信号不允许时暂停喂出
        }
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
        IItemHandler handler = cap.isPresent() ? cap.orElse(null) : null;
        // 流体 handler：前脸优先，找不到遍历机器所有面（部分机器输入面配置不同）
        IFluidHandler fluidHandler = findFluidHandler(target, front.getOpposite());
        int slots = handler == null ? 0 : handler.getSlots();
        if (slots <= 0 && fluidHandler == null) {
            if (!feederDiagLogged) {
                feederDiagLogged = true;
                logFeederStatus("启动(无IItemHandler/无IFluidHandler)");
            }
            return;
        }
        // 可喂种类数（物品+流体，并行轮转基数）：预算均分，所有种类同时推进
        int feedable = 0;
        for (var entry : reservoir.entrySet()) {
            if ((entry.getKey() instanceof AEItemKey || entry.getKey() instanceof AEFluidKey
                    || com.ae2addon.compat.MekanismGasCompat.isFeedable(entry.getKey()))
                    && entry.getValue().signum() > 0) {
                feedable++;
            }
        }
        if (feedable <= 0) {
            return;
        }
        // 速度卡：每张喂出预算 ×2（最高 ×4）
        int budget = Math.min(FEED_BUDGET, 1_000_000);
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
            long amount = remain.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
            long fed = 0;
            int itemBudget = perItemBudget;
            if (key instanceof AEItemKey itemKey && handler != null) {
                while (amount > 0 && itemBudget > 0 && totalBudget > 0) {
                    int chunk = (int) Math.min(FEED_STACK, amount);
                    ItemStack stack = itemKey.toStack(chunk);
                    ItemStack leftover = stack;
                    // 一轮 = 依次尝试所有输入槽（多槽机器并行填满）
                    for (int slot = 0; slot < slots && !leftover.isEmpty(); slot++) {
                        leftover = handler.insertItem(slot, leftover, false);
                    }
                    int inserted = chunk - leftover.getCount();
                    if (inserted <= 0) {
                        break; // 该物品本轮拒收 → 换下一种
                    }
                    fed += inserted;
                    amount -= inserted;
                    itemBudget--;
                    totalBudget--;
                }
            } else if (key instanceof AEFluidKey fluidKey && fluidHandler != null) {
                // 流体喂出：fill 到机器液体槽（FluidStack 上限 int）
                while (amount > 0 && itemBudget > 0 && totalBudget > 0) {
                    int chunk = (int) Math.min(Integer.MAX_VALUE, amount);
                    FluidStack fs = fluidKey.toStack(chunk);
                    int filled = fluidHandler.fill(fs, FluidAction.EXECUTE);
                    if (filled <= 0) {
                        break; // 机器液体槽满/不吃该流体
                    }
                    fed += filled;
                    amount -= filled;
                    itemBudget--;
                    totalBudget--;
                }
            } else if (com.ae2addon.compat.MekanismGasCompat.isFeedable(key)) {
                // 气体喂出：insertChemical 到机器气体槽（Mekanism 可选集成）
                while (amount > 0 && itemBudget > 0 && totalBudget > 0) {
                    long fedOnce = com.ae2addon.compat.MekanismGasCompat.feed(
                            target, front.getOpposite(), key, amount);
                    if (fedOnce <= 0) {
                        break; // 机器气体槽满/不吃该气体
                    }
                    fed += fedOnce;
                    amount -= fedOnce;
                    itemBudget--;
                    totalBudget--;
                }
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
            rateWindowFed += fedAll;
            setChanged();
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][feeder] 喂出 {} 个（{}种并行）→ 蓄水池={}种/合计{}，累计已喂出={}",
                    fedAll, feedable, reservoirSummary()[0],
                    fmt(totalAmount()), fmt(totalFed));
        } else {
            rejectWindow++; // 有货但整 tick 零喂出 → 机器满/拒收（诊断瓶颈）
            // 诊断：蓄水池含流体/气体但喂不出（机器无对应槽 or 拒收）
            if (hasUnfedFluidOrGas() && (level.getGameTime() & 0x7F) == 0) {
                com.ae2addon.AE2Addon.LOGGER.warn(
                        "[ae2addon][feeder] 蓄水池含流体/气体但未喂出：物品槽={} 流体槽={} 气体可喂={}，"
                                + "蓄水池={}种/合计{}",
                        handler != null, fluidHandler != null,
                        com.ae2addon.compat.MekanismGasCompat.isLoaded(),
                        reservoirSummary()[0], fmt(totalAmount()));
            }
        }
    }

    /** 蓄水池中数量最多的物品（正面/侧面抽取预览共用）。 */
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

    /** 感应卡供电：网络 FE → 正面机器能量槽（独立于喂出；蓄水池空也供电）。 */
    private void feedMachinePower() {
        if (!hasInductionCard()) {
            return;
        }
        if (level == null || level.isClientSide) {
            return;
        }
        try {
            Direction front = getFront();
            if (front == null) {
                return;
            }
            BlockEntity target = level.getBlockEntity(worldPosition.relative(front));
            if (target == null) {
                return;
            }
            long fe = com.ae2addon.compat.AppFluxPowerCompat.feedEnergy(
                    target, front.getOpposite(), getMainNode().getGrid(), actionSource);
            if (fe > 0 && (level.getGameTime() & 0x3F) == 0) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][feeder] 供电 {} FE/tick（感应卡）", fe);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** 蓄水池是否含流体/气体 key（诊断用）。 */
    private boolean hasUnfedFluidOrGas() {
        for (var entry : reservoir.entrySet()) {
            AEKey key = entry.getKey();
            if ((key instanceof AEFluidKey
                    || com.ae2addon.compat.MekanismGasCompat.isFeedable(key))
                    && entry.getValue().signum() > 0) {
                return true;
            }
        }
        return false;
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

    /** 上次标记的 key 集合（变化检测：消失的标记 → 退回蓄水池缓存）。 */
    private java.util.Set<AEKey> lastMarkedKeys = java.util.Collections.emptySet();

    private void onMarkersChanged() {
        setChanged();
        // 标记区不占真实存储：标记取消 → 对应蓄水池缓存退回网络（2026-08-28 sensei）
        java.util.Set<AEKey> now = wantedKeys();
        if (level == null || level.isClientSide || now.equals(lastMarkedKeys)) {
            lastMarkedKeys = now;
            return;
        }
        IGrid grid = getMainNode().getGrid();
        if (grid != null) {
            MEStorage storage = grid.getStorageService().getInventory();
            for (AEKey gone : lastMarkedKeys) {
                if (now.contains(gone)) {
                    continue;
                }
                java.math.BigInteger amount = reservoir.remove(gone);
                if (amount != null && amount.signum() > 0 && storage != null) {
                    try {
                        long back = amount.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValue();
                        long inserted = storage.insert(gone, back, Actionable.MODULATE, actionSource);
                        com.ae2addon.AE2Addon.LOGGER.info(
                                "[ae2addon][feeder] 标记取消，退回缓存 {} x{}（蓄水池余 {}）",
                                gone, inserted, reservoirAmount(gone));
                    } catch (RuntimeException e) {
                        com.ae2addon.AE2Addon.LOGGER.warn(
                                "[ae2addon][feeder] 退回缓存失败 {} {}", gone, e);
                    }
                }
            }
        }
        lastMarkedKeys = now;
    }

    /**
     * 右键标记槽：用手中物品直接标记流体/气体/物品（2026-08-28 sensei 要求，
     * 不放入槽、不消耗容器）。流体/气体包成 WrappedGenericStack 显示，
     * 普通物品放 1 个作视觉；空手右键 = 清空标记。
     */
    public boolean handleMarkerRightClick(int markerIndex, ItemStack carried) {
        if (markerIndex < 0 || markerIndex >= markerInv.getContainerSize()) {
            return false;
        }
        if (carried.isEmpty()) {
            markerInv.setItem(markerIndex, ItemStack.EMPTY);
            return true;
        }
        // 流体容器 → 标记流体
        var contained = FluidUtil.getFluidContained(carried);
        if (contained.isPresent() && !contained.get().isEmpty()) {
            markByKey(markerIndex, AEFluidKey.of(contained.get()));
            return true;
        }
        // 气体容器（气罐/气桶）→ 标记气体
        AEKey gasKey = com.ae2addon.compat.MekanismGasCompat.chemicalInContainer(carried);
        if (gasKey != null) {
            markByKey(markerIndex, gasKey);
            return true;
        }
        // 普通物品 → 虚拟标记（WrappedGenericStack，不占用真实物品）
        var itemKey = appeng.api.stacks.AEItemKey.of(carried);
        if (itemKey != null) {
            markByKey(markerIndex, itemKey);
            return true;
        }
        return false;
    }

    /** 按 AEKey 直接标记（JEI 拖取/右键共用）；null key = 清空。 */
    public void markByKey(int markerIndex, AEKey key) {
        if (markerIndex < 0 || markerIndex >= markerInv.getContainerSize()) {
            return;
        }
        if (key == null) {
            markerInv.setItem(markerIndex, ItemStack.EMPTY);
            return;
        }
        markerInv.setItem(markerIndex,
                appeng.items.misc.WrappedGenericStack.wrap(key, 1));
    }

    /** 清空标记（JEI/网络包用）。 */
    public void clearMarker(int markerIndex) {
        markByKey(markerIndex, null);
    }

    /** 方块拆除时掉落样板槽物品 + 升级卡（玩家资源；蓄水池物品属于网络/CPU，不返还防刷）。 */
    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        for (int i = 0; i < patternInv.getContainerSize(); i++) {
            ItemStack stack = patternInv.getItem(i);
            if (!stack.isEmpty()) {
                drops.add(stack);
            }
        }
        for (int i = 0; i < upgrades.size(); i++) {
            ItemStack stack = upgrades.getStackInSlot(i);
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
                // 2026-08-28 兼容：接口不接合成样板（AECraftingPattern）——合成需分子
                // 装配室/样板供应器执行，接口只能喂处理机器；接了会导致 CPU 把合成任务
                // 推给接口而机器无法执行（sensei 提醒）
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
        // 网络入口（非正面）：外界送入的物品/流体直接进网络
        if (side != getFront()) {
            if (cap == ForgeCapabilities.ITEM_HANDLER) {
                return net.minecraftforge.common.util.LazyOptional.of(() -> networkItemHandler).cast();
            }
            if (cap == ForgeCapabilities.FLUID_HANDLER) {
                return net.minecraftforge.common.util.LazyOptional.of(() -> networkFluidHandler).cast();
            }
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
        upgrades.writeToNBT(tag, "upgrades");
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
        upgrades.readFromNBT(tag, "upgrades");
        updateChannelLink();
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

    /** 上一秒喂出速率（items/s，GUI 状态用）。 */
    public long feedRatePerSecond() {
        return currentFeedRate;
    }

    /** 上一秒整 tick 零喂出次数（机器满/拒收诊断；0=正常）。 */
    public long rejectRatePerSecond() {
        return currentRejectRate;
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
