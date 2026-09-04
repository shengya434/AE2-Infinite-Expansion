package com.ae2addon.block;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.CraftingBlockEntity;
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
 * 无限级装配处理器·核心（v0.3 M3，2026-09-04）。
 * <p>
 * AE2 原版 crafting-unit 型核心：与普通 crafting unit/集成 CPU 拼成多方块簇
 * （3×3×3 框架+核心，簇成型由 AE2 原版 CraftingCPUCluster 机制自动完成）。
 * <p>
 * 职责：
 * - 作为合成 CPU 提供无限存储（显示值 cpuDisplayBytes），承接合成类订单
 * - 样板槽 5×9×200（9000 格）：声明「可虚拟结算的合成样板」白名单
 * - 簇内 CPU 的 executeCrafting 遇白名单合成样板 → CraftingCpuLogicMixin 虚拟结算
 *   （材料销毁、产物瞬时注入），不再需要分子装配室
 * - 实现 ICraftingProvider 报告样板 → 纯装配处理器网络（无 MA）也能触发结算
 */
public class AssemblerCoreBE extends CraftingBlockEntity implements ICraftingProvider {

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
        // 不能返回 Long.MAX_VALUE：CraftingCPUCluster 累加各块 getStorageBytes，
        // 与集成 CPU（Long.MAX）或另一个装配处理器同簇时相加溢出为负（2026-09-04
        // sensei 实测「负数字节 CPU」）。MAX/8 ≈ 1.15e18 字节：与 7 个同值块或集成
        // CPU 同簇都不溢出，容量远超任何实际订单（含 Long.MAX 级巨型订单按物品数
        // 结算，不受字节容量限制）。CPU 显示用 ∞ 覆盖（cpuStorageText）。
        return isFormed() ? Long.MAX_VALUE / 8 : 0;
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
