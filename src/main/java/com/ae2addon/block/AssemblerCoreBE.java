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

    /** 记录所属集成 CPU（本模块簇的 owner；owner 覆盖其全部虚拟 lane）。 */
    private void refreshOwner() {
        IntegratedCPUBE owner = null;
        var myCluster = getCluster();
        if (myCluster != null && !myCluster.isDestroyed()) {
            owner = IntegratedCPURegistry.ownerOf(myCluster);
        }
        this.ownerCPU = owner;
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
        return isFormed() ? super.getCableConnectionType(dir) : AECableType.NONE;
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

    /** 放样板（编码后的 pattern 物品）；index 越界/物品非法直接忽略。 */
    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= TOTAL_SLOTS) {
            return;
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

    @Override
    public List<appeng.api.crafting.IPatternDetails> getAvailablePatterns() {
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
        return false; // 虚拟结算无真实占用；pushPattern 一律拒收（由 mixin 拦截）
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
    // 终端窗口 = 当前 GUI 页 45 格（服务端 page 状态驱动；翻页在方块 GUI 操作）。

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

    /** 终端适配：读写当前 GUI 页 45 格（直接操作 List，与 GUI 同源不吞样板）。 */
    private final appeng.api.inventories.InternalInventory terminalPatternInv =
            new appeng.api.inventories.InternalInventory() {
                @Override
                public int size() {
                    return PAGE_SIZE;
                }

                @Override
                public ItemStack getStackInSlot(int slot) {
                    if (slot < 0 || slot >= PAGE_SIZE) {
                        return ItemStack.EMPTY;
                    }
                    return getSlot(page * PAGE_SIZE + slot);
                }

                @Override
                public void setItemDirect(int slot, ItemStack stack) {
                    if (slot < 0 || slot >= PAGE_SIZE) {
                        return;
                    }
                    setSlot(page * PAGE_SIZE + slot, stack);
                    onPatternsChanged();
                }

                @Override
                public int getSlotLimit(int slot) {
                    return 1;
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    return !stack.isEmpty();
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
}
