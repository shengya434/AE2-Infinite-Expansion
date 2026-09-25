package com.ae2addon.block;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.api.crafting.PatternDetailsHelper;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无限级装配处理器·模块（v0.3 M3，2026-09-04）。
 * <p>
 * sensei 决策：不做独立合成单元，作为<b>集成 CPU 的拓展模块</b>——
 * crafting-unit 型方块贴入集成 CPU 簇（与 IntegratedCPUBE 同簇）即生效：
 * - 存储贡献 0（不干扰集成 CPU 的无限存储语义；同簇多块也不会溢出）
 * - 样板槽 5×9×200（9000 格）：声明「可虚拟结算的合成样板」白名单
 * - 集成 CPU（主簇/虚拟 lane）执行合成时遇白名单合成样板 →
 *   CraftingCpuLogicMixin 虚拟结算（材料销毁、产物瞬时注入）
 * - 实现 PatternContainer：样板管理终端可直接访问样板槽
 * - 单独放置（无集成 CPU 簇）→ 模块不激活，仅样板槽管理界面可用
 */
public class AssemblerCoreBE extends CraftingBlockEntity
        implements ICraftingProvider, appeng.helpers.patternprovider.PatternContainer {

    /** 样板槽规格（sensei 定稿）：5×9 每页 × 200 页。 */
    public static final int SLOT_COLS = 5;
    public static final int SLOT_ROWS = 9;
    public static final int PAGES = 200;
    public static final int PAGE_SIZE = SLOT_COLS * SLOT_ROWS; // 45
    public static final int TOTAL_SLOTS = PAGE_SIZE * PAGES;   // 9000

    /** 样板槽数据（稀疏 List，容量 TOTAL_SLOTS，NBT 只存非空）。 */
    private final List<ItemStack> patterns = new ArrayList<>();

    /** 当前 GUI 页（0..PAGES-1）。 */
    private int page;

    /** 所属集成 CPU（模块贴入集成 CPU 簇后由 updateStatus 记录；null=未激活）。 */
    private IntegratedCPUBE ownerCPU;

    // ── 白名单/样板缓存（槽位变化时失效重建）──

    private boolean cacheDirty = true;
    private List<appeng.api.crafting.IPatternDetails> cachedPatterns = List.of();
    private Set<AEKey> declaredOutputs = Set.of();

    public AssemblerCoreBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ASSEMBLER_CORE.get(), pos, state);
    }

    // ── 激活判定：**不靠 AE2 簇**（2026-09-24 sensei：改机制，让它能当多方块的拓展单元）──

    /**
     * ⚠ 2026-09-24 机制改动（sensei：「不然没办法作为这个多方块的拓展单元使用」+「单独给一套多方块结构成型检测，
     * 不然没装上集成 CPU 的多方块结构上也可以使用」）。
     * <p>
     * 原实现在服务端是 {@code cluster != null}（AE2 的 {@code CraftingBlockEntity.isFormed()}）——
     * 装配处理器**必须进 AE2 合成簇才算激活**。但它现在的用法是**集成 CPU 多方块结构里的一格**
     * （模板里 {@code ae2addon:assembler_core} 只 1 格），周围贴的是 {@code crafting_unit} /
     * {@code 256k_crafting_storage}，簇归属与"同类型才成簇"的规则根本对不上 →
     * 永远 {@code isFormed() == false} → 注册表直接跳过它 → 拓展单元形同虚设。
     * <p>
     * 现在改成**独立判定**，而且判定条件是"**装在已成型的集成 CPU 多方块结构里**"：
     * <ul>
     *   <li>装在结构里（且该 CPU 已成型）→ 激活：可被 {@code moduleFor} 找到、界面可开、白名单生效；</li>
     *   <li>散放（不在任何已成型的集成 CPU 结构里）→ **不激活**：和普通装饰方块一样不能用
     *       （这正是 sensei 要的"没有装上集成 CPU 就不能使用"）。</li>
     * </ul>
     */
    @Override
    public boolean isFormed() {
        if (isRemoved()) {
            return false;
        }
        // 位置归属：我在某个**已成型**的集成 CPU 结构里吗？
        return ownerCPU != null && !ownerCPU.isRemoved() && ownerCPU.isFormed()
                && ownerCPU.isAssemblerPos(worldPosition);
    }

    /** 是否已装入集成 CPU 多方块结构（界面/提示用，语义同 {@link #isFormed()}） */
    public boolean isInstalledInStructure() {
        return isFormed();
    }

    /**
     * 定期重新认领 + 诊断（由方块 ticker 调，2 秒一次）。
     * <p>
     * 为什么要定期：① 集成 CPU 可能在本模块**之后**才成型（认领要能跟上）；
     * ② 认领逻辑与自身状态互相依赖过（2026-09-24 修），定期重试能自愈。
     * 诊断只在状态**变化**时打一行，避免刷屏。
     */
    public void tickOwnerMaintenance() {
        if (level == null || level.isClientSide) {
            return;
        }
        // ⚠ 定性日志（2026-09-24）：**第一次被 tick 就叫一声**，用来区分
        //   "ticker 根本没跑"（连这行都不出现）与 "跑了但认领失败"（这行出现、状态=false）。
        if (!tickerSeen) {
            tickerSeen = true;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][assembler] ticker 首次运行 pos={} 已装入结构={} owner={}",
                    worldPosition, isFormed(),
                    ownerCPU == null ? "null" : ownerCPU.getBlockPos().toShortString());
        }
        boolean before = isFormed();
        refreshOwner();
        boolean after = isFormed();
        if (before != after || diagLoggedState != after) {
            diagLoggedState = after;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][assembler] pos={} 已装入结构={} owner={} owner成型={} 在结构内={}",
                    worldPosition, after,
                    ownerCPU == null ? "null" : ownerCPU.getBlockPos().toShortString(),
                    ownerCPU != null && ownerCPU.isFormed(),
                    ownerCPU != null && ownerCPU.isAssemblerPos(worldPosition));
        }
    }

    /** 是否已经打印过"ticker 首次运行"（定性用） */
    private boolean tickerSeen;

    /**
     * 诊断用：上次记录的激活状态（避免重复打日志）。
     * <p>
     * ⚠ 2026-09-24 崩溃修复：这里原来是包装类型 {@code Boolean}（初值 null），
     * 比较时自动拆箱 → `NullPointerException: ... "this.diagLoggedState" is null`，
     * 而它由**每 2 秒一次的 ticker** 调用 → 直接把服务器 tick 炸掉。
     * **基础类型字段不要用包装类型**，尤其不要让它参与 `!=`/比较运算。
     */
    private boolean diagLoggedState;

    /**
     * 网络连接面：**全 6 面**。
     * <p>
     * 本类下方已有一个 {@code getGridConnectableSides} 覆写（2026-09-04 加的），
     * 这里只留说明、不再重复定义（重复定义会编译失败）。
     * 提醒：{@code CraftingBlockEntity} 默认按"是否已成型"裁剪连接面（未成型返回空集），
     * 对这种"独立激活"的方块不适用。
     */

    // ── 注册表 ──

    @Override
    public void onReady() {
        super.onReady();
        AssemblerRegistry.register(this);
        refreshOwner();
    }

    /** 簇状态变化（成型/拆毁/重组）时刷新所属集成 CPU。 */
    @Override
    public void updateStatus(CraftingCPUCluster c) {
        super.updateStatus(c);
        refreshOwner();
    }

    /** 记录所属集成 CPU（模块簇的 owner 优先；同网格兜底——2026-09-04 修复：
     *  模块与集成 CPU 不必同 crafting unit 簇，接入同一网络即关联为拓展模块）。 */
    private void refreshOwner() {
        IntegratedCPUBE owner = null;
        var myCluster = getCluster();
        if (myCluster != null && !myCluster.isDestroyed()) {
            owner = IntegratedCPURegistry.ownerOf(myCluster);
        }
        // 2026-09-24 新增：**位置归属**优先于"同网格兜底" ——
        // 本方块现在是集成 CPU 多方块结构里的一格（模板里只有 1 格 assembler_core），
        // 所以"我在哪个集成 CPU 的结构里，就服务哪个 CPU"最准确。
        if (owner == null && level != null) {
            for (IntegratedCPUBE cpu : IntegratedCPURegistry.all()) {
                if (cpu.isRemoved() || !cpu.isFormed()) {
                    continue;
                }
                if (cpu.isAssemblerPos(worldPosition)) {
                    owner = cpu;
                    break;
                }
            }
        }
        if (owner == null) {
            // 同簇/位置都没命中 → 同网格兜底（模块与集成 CPU 接入同一网络即关联）
            try {
                var myGrid = getMainNode() == null ? null : getMainNode().getGrid();
                if (myGrid != null) {
                    for (IntegratedCPUBE cpu : IntegratedCPURegistry.all()) {
                        if (cpu.isRemoved()) {
                            continue;
                        }
                        var cpuGrid = cpu.getMainNode() == null ? null
                                : cpu.getMainNode().getGrid();
                        if (cpuGrid == myGrid) {
                            owner = cpu;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // 网格未就绪：保持 null，下次查询再试
            }
        }
        this.ownerCPU = owner;
    }

    /**
     * 外部（AssemblerRegistry.moduleFor）触发的惰性刷新。
     * <p>
     * ⚠ 2026-09-24 修死循环（sensei：「为啥成型后不能用，没成型能用」）：
     * 原实现是 {@code if (ownerCPU == null && isFormed()) refreshOwner();} ——
     * 而 {@link #isFormed()} 又要求 {@code ownerCPU != null}，
     * **两者互相依赖 → 认领永远发生不了**（散放时 ownerCPU 一直是 null，
     * 装进结构后也不会被认领），于是状态看起来像是"反的"。
     * 现在无条件重试认领：认领是由"我在哪个集成 CPU 的结构里"决定的，与自身状态无关。
     */
    public void refreshOwnerNow() {
        if (ownerCPU == null || ownerCPU.isRemoved()) {
            refreshOwner();
        }
    }

    public IntegratedCPUBE getOwnerCPU() {
        return ownerCPU;
    }

    @Override
    public void onChunkUnloaded() {
        AssemblerRegistry.unregister(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        AssemblerRegistry.unregister(this);
        super.setRemoved();
    }

    // ── 网格节点：注册为合成 provider（让 CPU 的 provider 循环找到我们）──

    @Override
    protected appeng.api.networking.IManagedGridNode createMainNode() {
        return super.createMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(ICraftingProvider.class, this);
    }

    // ── CraftingBlockEntity 覆写：无限存储、无加速线程 ──

    @Override
    public long getStorageBytes() {
        // 模块不贡献存储：存储由集成 CPU 提供（返回 0 避免多块累加溢出，
        // 2026-09-04 sensei 实测「负数字节 CPU」）
        return 0;
    }

    @Override
    public int getAcceleratorThreads() {
        return 0; // 虚拟结算 N× 一次到位，无需并行线程（时间片限流由 mixin 统一管理）
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        // 模块放置即接网：不依赖 crafting unit 成型（2026-09-04 sensei：贴其他
        // CPU/线缆也要能接入网络——作为集成 CPU 网络内的拓展模块）
        return super.getCableConnectionType(dir);
    }

    @Override
    public java.util.Set<Direction> getGridConnectableSides(
            appeng.api.orientation.BlockOrientation orientation) {
        // 恒 6 面可连（原版按成型裁剪：未成型 noneOf → 无法接网）
        return java.util.EnumSet.allOf(Direction.class);
    }

    // ── 样板槽访问（GUI 用）──

    public int getPage() {
        return page;
    }

    public void setPage(int p) {
        this.page = Math.max(0, Math.min(PAGES - 1, p));
        setChanged();
    }

    public ItemStack getSlot(int index) {
        return index >= 0 && index < patterns.size() ? patterns.get(index) : ItemStack.EMPTY;
    }

    /** 槽位是否只接受合成族样板（合成/切石机/锻造台，2026-09-04 sensei 指正：
     *  矩阵不只放合成样板）；处理样板/普通物品/空气一律拒绝。 */
    public static boolean isCraftingPatternItem(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || level == null) {
            return false;
        }
        try {
            var details = PatternDetailsHelper.decodePattern(stack, level);
            if (details == null) {
                return false;
            }
            String name = details.getClass().getName();
            if (name.endsWith("AEProcessingPattern")) {
                return false; // 处理样板 → 走 feeder/机器，不进装配处理器
            }
            return name.endsWith("AECraftingPattern")
                    || name.endsWith("AEStonecuttingPattern")
                    || name.endsWith("AESmithingTablePattern")
                    || name.contains("CraftingPattern");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 放样板（编码后的 pattern 物品）；index 越界/物品非法直接忽略。 */
    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= TOTAL_SLOTS) {
            return;
        }
        if (com.ae2addon.crafting.CraftingCompat.debugLogs) {
            // 诊断：谁在写样板槽（2026-09-04 GUI 吞样板排查）
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            String caller = st.length > 2 ? st[2].toString() : "?";
            String caller2 = st.length > 3 ? st[3].toString() : "?";
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[assembler][core] setSlot idx={} 物品={} 调用者={} <- {}",
                    index,
                    (stack == null || stack.isEmpty()) ? "空气"
                            : stack.getHoverName().getString(),
                    caller, caller2);
        }
        if (stack == null || stack.isEmpty()) {
            if (index < patterns.size()) {
                patterns.set(index, ItemStack.EMPTY);
            }
        } else {
            while (patterns.size() <= index) {
                patterns.add(ItemStack.EMPTY);
            }
            patterns.set(index, stack.copyWithCount(1));
        }
        cacheDirty = true;
        setChanged();
        syncToClient();
    }

    /** 样板槽变化后通知 CraftingService 刷新（provider 声明列表）。 */
    public void onPatternsChanged() {
        cacheDirty = true;
        setChanged();
        var node = getMainNode();
        if (node != null && node.isActive()) {
            ICraftingProvider.requestUpdate(node);
        }
    }

    /** 白名单判定：该合成样板是否被本核心声明（产物 key 匹配样板槽任一 encode）。 */
    public boolean declares(appeng.api.crafting.IPatternDetails pattern) {
        if (pattern == null) {
            return false;
        }
        var outs = pattern.getOutputs();
        if (outs == null || outs.length == 0) {
            return false;
        }
        AEKey output = outs[0].what();
        if (output == null) {
            return false;
        }
        return ensureCache().declared.contains(output);
    }

    // ── ICraftingProvider：报告样板槽内全部样板 ──
    //
    // ⚠ 2026-09-24 sensei：「不过没在结构里的也能成型」—— 这就是漏洞所在。
    //   我只在「界面能不能开」上做了门禁（isFormed 要求装在已成型的集成 CPU 结构里），
    //   却忘了这三个方法是 AE2 的**功能入口**：只要网络拿到这个 ICraftingProvider，
    //   不管它在不在结构里都可能被用上。所以这里全部按 isFormed() 过滤：
    //   - 未装入结构 → 报"零样板"，AE2 拿不到任何可用样板；
    //   - pushPattern 本来一律拒收（虚拟结算由 CPU mixin 拦截），保持 false。

    @Override
    public List<appeng.api.crafting.IPatternDetails> getAvailablePatterns() {
        if (!isFormed()) {
            // 没装在结构里 → 对外表现为"没有任何可用样板"（网络里等于不存在）
            return List.of();
        }
        return ensureCache().details;
    }

    /**
     * 防御性实现：虚拟结算由 CPU mixin 在 pushPattern 前拦截（从不真正推送本核心）。
     * 若绕过拦截直接推来（例如处理类样板误入），拒收。
     */
    @Override
    public boolean pushPattern(appeng.api.crafting.IPatternDetails patternDetails, KeyCounter[] inputs) {
        return false;
    }

    @Override
    public boolean isBusy() {
        // 虚拟结算无真实占用（pushPattern 一律拒收、由 mixin 拦截）。
        // 但**未装入结构时报"忙"**，让 AE2 的调度把它当不可用的 provider 跳过。
        return !isFormed();
    }

    // ── 缓存 ──

    private Cache ensureCache() {
        if (cacheDirty) {
            cacheDirty = false;
            Level lvl = getLevel();
            List<appeng.api.crafting.IPatternDetails> details = new ArrayList<>();
            Set<AEKey> outputs = new HashSet<>();
            if (lvl != null && !lvl.isClientSide) {
                for (ItemStack stack : patterns) {
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    try {
                        var decoded = PatternDetailsHelper.decodePattern(stack, lvl);
                        if (decoded == null) {
                            continue;
                        }
                        details.add(decoded);
                        var outs = decoded.getOutputs();
                        if (outs != null) {
                            for (GenericStack out : outs) {
                                if (out != null && out.what() != null) {
                                    outputs.add(out.what());
                                }
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // 槽位物品不是有效样板（如玩家误放普通物品）→ 跳过
                    }
                }
            }
            cachedPatterns = details;
            declaredOutputs = outputs;
        }
        return new Cache(cachedPatterns, declaredOutputs);
    }

    private record Cache(List<appeng.api.crafting.IPatternDetails> details, Set<AEKey> declared) {
    }

    // ── PatternContainer（样板管理终端兼容，2026-09-04 sensei：终端可访问样板槽）──
    // 终端全量暴露 9000 格：AE2 PAT 按每行 9 格拆行 + 滚动渲染（反编译确认
    // SlotsRow(container, offset, min(9, ...)) 拆行逻辑）——大容器天然支持。
    //
    // ⚠ 2026-09-24 sensei：「未成形的装配处理器还是可以通过样板管理终端插入样板」——
    //   样板管理终端是**直接读写 getTerminalPatternInventory()** 的，所以门禁必须加在
    //   这个库存上（只拦 ICraftingProvider 与 GUI 都不够）：
    //   未装入结构时 size()=0、setItemDirect() 直接忽略、isItemValid()=false，
    //   终端那边看到的就是"这个容器是空的、也塞不进东西"。

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
        return new appeng.api.implementations.blockentities.PatternContainerGroup(
                appeng.api.stacks.AEItemKey.of(
                        com.ae2addon.init.ModBlocks.ASSEMBLER_CORE.get()),
                net.minecraft.network.chat.Component.translatable(
                        getBlockState().getBlock().getDescriptionId()),
                java.util.List.of());
    }

    /** 终端适配：直接读写全部 9000 槽（与 GUI 同源 List，无页偏移、不吞样板）。 */
    private final appeng.api.inventories.InternalInventory terminalPatternInv =
            new appeng.api.inventories.InternalInventory() {
                @Override
                public int size() {
                    // 未装入结构 → 对外表现为"没有样板槽"（终端里看不到、也塞不进）
                    return isFormed() ? TOTAL_SLOTS : 0;
                }

                @Override
                public ItemStack getStackInSlot(int slot) {
                    if (!isFormed()) {
                        return ItemStack.EMPTY;
                    }
                    return getSlot(slot);
                }

                @Override
                public void setItemDirect(int slot, ItemStack stack) {
                    if (!isFormed()) {
                        return;   // 未装入结构：**拒绝写入**（这条就是终端的插入路径）
                    }
                    setSlot(slot, stack);
                    onPatternsChanged();
                }

                @Override
                public int getSlotLimit(int slot) {
                    return isFormed() ? 1 : 0;
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    return isFormed() && isCraftingPatternItem(stack, getLevel());
                }
            };

    // ── NBT ──

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("page", page);
        ListTag list = new ListTag();
        for (int i = 0; i < patterns.size(); i++) {
            ItemStack stack = patterns.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("i", i);
            entry.put("s", stack.save(new CompoundTag()));
            list.add(entry);
        }
        tag.put("patterns", list);
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        page = Math.max(0, Math.min(PAGES - 1, tag.getInt("page")));
        patterns.clear();
        ListTag list = tag.getList("patterns", Tag.TAG_COMPOUND);
        for (int k = 0; k < list.size(); k++) {
            CompoundTag entry = list.getCompound(k);
            int i = entry.getInt("i");
            ItemStack stack = ItemStack.of(entry.getCompound("s"));
            if (i >= 0 && i < TOTAL_SLOTS && !stack.isEmpty()) {
                while (patterns.size() <= i) {
                    patterns.add(ItemStack.EMPTY);
                }
                patterns.set(i, stack);
            }
        }
        cacheDirty = true;
    }

    /** 客户端同步（GUI 页刷新用）：服务端变化后由 menu 广播即可，这里留空占位。 */
    private void syncToClient() {
        // 容器变化经 Menu.broadcastChanges 自动下发，无需额外包
    }

    /**
     * 网络工具 / ME 控制器里机器的**图标与身份**（2026-09-15 sensei 陈年问题）。
     * <p>
     * AE2 的 `AENetworkBlockEntity`/`CraftingBlockEntity` 用本方法给网格节点设
     * `visualRepresentation`；默认实现取「方块对应的物品」——我们有几个方块是
     * **两个物品共用一个方块 id**（本体 + 已成型变体）→ `asItem()` 会拿到已成型变体甚至空气；
     * 继承 CraftingUnitBlock 的（集成CPU/装配处理器）还会被 type 名带成「256k 合成存储器」。
     * 这里显式返回**本方块的物品本体**。
     */
    @Override
    protected net.minecraft.world.item.Item getItemFromBlockEntity() {
        return com.ae2addon.init.ModItems.ASSEMBLER_CORE_ITEM.get();
    }
}
