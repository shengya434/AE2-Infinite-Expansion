package com.ae2addon.block;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.hooks.ticking.TickHandler;
import com.ae2addon.AE2Addon;
import com.ae2addon.compat.EMCCompat;
import com.ae2addon.gui.QianJiMenu;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.item.CatalystItem;
import com.ae2addon.util.ChatLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 千机·阿比舒（无限级）方块实体。
 * <p>
 * - 1280 样板槽（仅接受编码处理样板）
 * - 催化剂槽（基础/高级/终极）
 * - ICraftingProvider — AE2 合成提供商，直接响应合成 CPU 请求
 * - 处理流程：CPU 请求 → 从 ME 网络拉取输入 → 瞬间处理 → 推回输出
 */
public class QianJiBE extends AENetworkBlockEntity implements MenuProvider, ICraftingProvider, ICraftingMachine {

    private static final int PATTERN_SLOTS = 1280;
    private static final int CATALYST_SLOTS = 1;

    private boolean formed = false;
    private boolean byproductEnabled = false;

    private final PatternHandler patternHandler = new PatternHandler();
    private final CatalystHandler catalystHandler = new CatalystHandler();
    /** 输入缓存：PatternProvider 把原料推进来，我们在 pushPattern 时消耗 */
    private final InputBuffer inputBuffer = new InputBuffer();
    private final LazyOptional<IItemHandler> inputBufferCap = LazyOptional.of(() -> inputBuffer);
    private final Set<AEKey> emitableItems = new HashSet<>();

    @Nullable
    private List<IPatternDetails> cachedPatterns = null;

    // ── 配方校验（防刷物品）──
    /** 输出物品 → 所有能产出它的配方的输入物品集合列表（静态缓存） */
    @Nullable
    private static Map<Item, List<Set<Item>>> recipeIndex = null;
    /** 有配方但输入未知（自定义 RecipeType 如 ProjectE/Mekanism）的输出物品 */
    private static Set<Item> customRecipeOutputs = null;

    private static Map<Item, List<Set<Item>>> getRecipeIndex(Level level) {
        if (recipeIndex != null) return recipeIndex;
        var index = new HashMap<Item, List<Set<Item>>>();
        var custom = new HashSet<Item>();
        try {
            for (var recipe : level.getRecipeManager().getRecipes()) {
                var result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty()) continue;
                var inputs = new HashSet<Item>();
                for (var ing : recipe.getIngredients()) {
                    for (var stack : ing.getItems()) {
                        if (!stack.isEmpty()) inputs.add(stack.getItem());
                    }
                }
                if (inputs.isEmpty()) {
                    // 自定义配方类型（ProjectE 世界转换、Mekanism 机器等）：输入不走标准 API，
                    // 记录"输出可制造"但输入未知，校验时跳过输入匹配，避免误伤
                    custom.add(result.getItem());
                    continue;
                }
                index.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(inputs);
            }
        } catch (Exception e) {
            AE2Addon.LOGGER.warn("QianJi: recipe index build failed: {}", e.getMessage());
        }
        recipeIndex = index;
        customRecipeOutputs = custom;
        AE2Addon.LOGGER.info("QianJi: recipe index built, {} standard + {} custom outputs",
                index.size(), custom.size());
        return index;
    }

    /**
     * 校验处理样板：
     * 1. 每个物品输出必须有配方（标准或自定义），否则拒绝（防凭空创造）
     * 2. 对输入已知的标准配方：样板输入必须覆盖某个配方的全部输入（防少输入刷物品）
     * 3. 自定义配方（输入未知）跳过输入匹配，避免误伤 ProjectE/Mekanism 等
     */
    private boolean isValidRecipePattern(IPatternDetails details) {
        if (level == null) return false;
        var index = getRecipeIndex(level);
        var custom = customRecipeOutputs != null ? customRecipeOutputs : Set.of();
        if (index.isEmpty() && custom.isEmpty()) return true; // 索引构建失败时放行，避免误伤

        var outputs = details.getOutputs();
        if (outputs == null || outputs.length == 0) return false;

        // 纯流体输出暂不校验（放行）
        boolean hasItemOutput = false;
        for (var out : outputs) {
            if (out != null && out.what() instanceof AEItemKey) { hasItemOutput = true; break; }
        }
        if (!hasItemOutput) return true;

        // 样板输入物品集合（展开所有可能输入）
        var patternInputs = new HashSet<Item>();
        for (var input : details.getInputs()) {
            for (var option : input.getPossibleInputs()) {
                if (option != null && option.what() instanceof AEItemKey k) {
                    patternInputs.add(k.getItem());
                }
            }
        }

        // 每个物品输出都必须有匹配的配方
        for (var out : outputs) {
            if (out == null || out.amount() <= 0) continue;
            if (!(out.what() instanceof AEItemKey itemKey)) continue;

            var recipeInputs = index.get(itemKey.getItem());
            boolean hasStandard = recipeInputs != null && !recipeInputs.isEmpty();
            boolean hasCustom = custom.contains(itemKey.getItem());
            if (!hasStandard && !hasCustom) return false; // 输出完全无配方 → 拒绝
            if (!hasStandard) continue; // 只有自定义配方（输入未知）→ 跳过输入匹配

            // 输入检查：样板输入必须覆盖某个配方的全部输入
            boolean inputCovered = false;
            for (var ri : recipeInputs) {
                if (patternInputs.containsAll(ri)) { inputCovered = true; break; }
            }
            if (!inputCovered) return false;
        }

        // EMC 价值守恒（防"低价值→高价值"刷物品）：仅当 ProjectE 可用时生效
        if (EMCCompat.isProjectELoaded()) {
            if (!emcConservationOK(details)) return false;
        }
        return true;
    }

    /**
     * EMC 守恒校验：
     * - 输入输出都有 EMC 值 → 输出总价值 ≤ 输入总价值（允许损耗，禁止增值）
     * - 输出有值但输入有无值物品 → 拒绝（"免费→有价值"）
     * - 输出无值：输入也必须有无值物品（同等级），否则拒绝（"有值→无值"偷换，如 橡木→创造元件）
     */
    private boolean emcConservationOK(IPatternDetails details) {
        long inEmc = 0;
        boolean anyIn = false, allInHaveValue = true;
        for (var input : details.getInputs()) {
            for (var option : input.getPossibleInputs()) {
                if (option == null || option.amount() <= 0) continue;
                if (!(option.what() instanceof AEItemKey k)) continue;
                anyIn = true;
                long v = EMCCompat.getEmcValue(k.toStack());
                if (v > 0) inEmc += v * option.amount();
                else allInHaveValue = false;
            }
        }

        long outEmc = 0;
        boolean anyOut = false, allOutHaveValue = true;
        for (var out : details.getOutputs()) {
            if (out == null || out.amount() <= 0) continue;
            if (!(out.what() instanceof AEItemKey k)) continue;
            anyOut = true;
            long v = EMCCompat.getEmcValue(k.toStack());
            if (v > 0) outEmc += v * out.amount();
            else allOutHaveValue = false;
        }

        if (!anyIn || !anyOut) return true; // 无物品输入/输出（纯流体等）→ 放行
        if (allInHaveValue && allOutHaveValue) return outEmc <= inEmc; // 全有值 → 守恒
        if (allOutHaveValue) return false; // 输出有值但输入有无值物品 → 免费→贵，拒绝
        // 输出无值：输入也需有无值物品（同等级）；输入全有值（如 橡木→创造元件）→ 拒绝
        return !allInHaveValue;
    }

    public QianJiBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.QIAN_JI.get(), pos, state);
    }

    @Override
    public void onReady() {
        super.onReady();
        updateSideExposure();
        syncPowerUsage();
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode()
                .addService(ICraftingProvider.class, this);
    }

    // ── 成型状态 ──

    public boolean isFormed() { return formed; }

    public void setFormed(boolean formed) {
        if (this.formed == formed) return;
        this.formed = formed;
        if (level != null && !level.isClientSide) {
            updateSideExposure();
            syncPowerUsage();
            if (formed) {
                ChatLog.ok(level, worldPosition, "千机已成型，接入 AE 网络，催化剂等级 " + getCatalystLevel()
                        + "（耗电 ×" + (long) getPowerMultiplier() + "）");
                ChatLog.info(level, worldPosition, "接线提示: 线缆必须接到核心方块（成型用的那格）外露面上");
            } else {
                ChatLog.warn(level, worldPosition, "千机解除成型，断开 AE 网络");
            }
            if (getMainNode().isReady()) {
                getMainNode().ifPresent((grid, node) -> {
                    var cs = grid.getService(appeng.api.networking.crafting.ICraftingService.class);
                    if (cs != null) cs.refreshNodeCraftingProvider(node);
                });
            }
        }
        setChanged();
    }

    private void updateSideExposure() {
        getMainNode().setExposedOnSides(
                formed ? EnumSet.allOf(Direction.class) : EnumSet.noneOf(Direction.class));
    }

    // ── AE 网格 ──

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return formed ? super.getGridNode(dir) : null;
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return formed ? AECableType.SMART : AECableType.NONE;
    }

    @Override
    public <T> LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> cap, @Nullable Direction side) {
        if (formed && cap == ForgeCapabilities.ITEM_HANDLER) {
            return inputBufferCap.cast();
        }
        return super.getCapability(cap, side);
    }

    public double getIdlePowerUsage() {
        return formed ? 20000.0 * getPowerMultiplier() : 0.0;
    }

    private void syncPowerUsage() {
        if (getMainNode() != null) getMainNode().setIdlePowerUsage(getIdlePowerUsage());
    }

    // ── 催化剂 ──

    public int getCatalystLevel() {
        ItemStack stack = catalystHandler.getStackInSlot(0);
        if (stack.isEmpty() || !(stack.getItem() instanceof CatalystItem c)) return 0;
        return c.getTier();
    }

    public double getPowerMultiplier() {
        return switch (getCatalystLevel()) {
            case 1 -> 4.0;
            case 2 -> 20.0;
            case 3 -> 400.0;
            default -> 1.0;
        };
    }

    public double getPowerUsage() { return 20000.0 * getPowerMultiplier(); }

    // ════════════════════════════════════════════════════════
    //  ICraftingProvider — AE2 合成提供商
    // ════════════════════════════════════════════════════════

    @Override
    public @NotNull List<IPatternDetails> getAvailablePatterns() {
        if (!formed) return Collections.emptyList();
        if (cachedPatterns == null) {
            cachedPatterns = decodePatterns();
            ChatLog.info(level, worldPosition, "千机向网络暴露 " + cachedPatterns.size() + " 个样板");
            AE2Addon.LOGGER.info("QianJi: Decoded {} patterns", cachedPatterns.size());
        }
        return cachedPatterns;
    }

    @Override
    public int getPatternPriority() { return 0; }

    /**
     * ICraftingProvider.pushPattern — CPU 直接调用
     * <p>
     * AE2 语义：CPU 已从自己的合成库存提取原料（inputHolder 仅作记录），
     * 机器只需产出并注入网络。CPU 通过差异监听检测到输出后任务完成。
     */
    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputCounts) {
        return handlePush("CPU", pattern);
    }

    /**
     * ICraftingMachine.pushPattern — 相邻 PatternProvider 推送任务
     */
    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] patternDetails, Direction direction) {
        return handlePush("PatternProvider", pattern);
    }

    private boolean handlePush(String source, IPatternDetails pattern) {
        if (!formed) {
            ChatLog.warn(level, worldPosition, "千机未成型，拒绝任务(" + source + ")");
            return false;
        }
        // 最终防线：配方校验
        if (!isValidRecipePattern(pattern)) {
            ChatLog.err(level, worldPosition, "任务被拒(" + source + "): 样板的输入输出无匹配的真实配方");
            return false;
        }
        ChatLog.info(level, worldPosition, "收到合成任务(" + source + "): " + describeOutputs(pattern));

        // 关键时序：产物必须延迟 1 tick 注入！
        // CPU 在 pushPattern 返回后才把预期产物登记进 waitingFor，
        // 若在 pushPattern 内同步注入，insertIntoCpus 认领时 waitingFor 为空
        // → 产物直接进网络存储 → 任务永远"缺 N 个"卡死。
        final var lvl = level;
        final var p = pattern;
        final var src = source;
        TickHandler.instance().addCallable(lvl, () -> {
            if (lvl == null || lvl.isClientSide || !formed) return;
            boolean ok = instantCraft(p);
            if (ok) {
                ChatLog.ok(lvl, worldPosition, "千机完成合成: " + describeOutputs(p));
            } else {
                ChatLog.err(lvl, worldPosition, "千机拒绝任务(" + src + "): 未接入网络或输出空间不足");
            }
        });
        return true;
    }

    /**
     * 瞬间合成：输入已由 CPU/PatternProvider 处理，这里只把输出注入 ME 网络。
     */
    private boolean instantCraft(IPatternDetails pattern) {
        var grid = getMainNode().getGrid();
        if (grid == null) {
            ChatLog.err(level, worldPosition, "instantCraft: 未接入网格");
            return false;
        }

        var storage = grid.getService(IStorageService.class);
        if (storage == null) {
            ChatLog.err(level, worldPosition, "instantCraft: 无 IStorageService");
            return false;
        }
        var netInv = storage.getInventory();

        AE2Addon.LOGGER.info("instantCraft: netInv={}", netInv.getClass().getName());

        var src = IActionSource.ofMachine(this);
        var outputs = pattern.getOutputs();

        // 先模拟检查输出空间
        for (var out : outputs) {
            if (out == null || out.amount() <= 0) continue;
            long room = netInv.insert(out.what(), out.amount(), Actionable.SIMULATE, src);
            AE2Addon.LOGGER.info("instantCraft SIMULATE: key={} amount={} room={}", out.what(), out.amount(), room);
            if (room < out.amount()) {
                ChatLog.err(level, worldPosition, "输出空间不足: " + out.what().getDisplayName()
                        + " 需要 " + out.amount() + " 可放 " + room);
                return false;
            }
        }

        // 真正注入输出（记录实际注入量，排查"缺少目标"问题）
        for (var out : outputs) {
            if (out == null || out.amount() <= 0) continue;
            long inserted = netInv.insert(out.what(), out.amount(), Actionable.MODULATE, src);
            AE2Addon.LOGGER.info("instantCraft MODULATE: key={} amount={} inserted={}", out.what(), out.amount(), inserted);
            if (inserted < out.amount()) {
                ChatLog.err(level, worldPosition, "输出注入不完整: " + out.what().getDisplayName()
                        + " 预期 " + out.amount() + " 实际 " + inserted);
                return false;
            }
        }

        return true;
    }

    private String describeOutputs(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        StringBuilder sb = new StringBuilder();
        for (var out : outputs) {
            if (out == null || out.amount() <= 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(out.what().getDisplayName().getString()).append(" ×").append(out.amount());
        }
        return sb.length() == 0 ? "无输出" : sb.toString();
    }

    @Override
    public boolean isBusy() { return false; }

    @Override
    public @NotNull Set<AEKey> getEmitableItems() { return emitableItems; }

    // ════════════════════════════════════════════════════════
    //  ICraftingMachine — PatternProvider 相邻机器
    // ════════════════════════════════════════════════════════

    @Override
    public boolean acceptsPlans() {
        return formed;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        if (!formed || level == null) return PatternContainerGroup.nothing();
        return PatternContainerGroup.fromMachine(level, worldPosition, Direction.UP);
    }

    public void requestUpdate(IManagedGridNode node) {
        invalidatePatternCache();
        if (formed && node != null && node.isReady()) {
            node.ifPresent((grid, gridNode) -> {
                var cs = grid.getService(appeng.api.networking.crafting.ICraftingService.class);
                if (cs != null) cs.refreshNodeCraftingProvider(gridNode);
            });
        }
    }

    // ── 样板管理 ──

    public ItemStackHandler getPatternHandler() { return patternHandler; }
    public ItemStackHandler getCatalystHandler() { return catalystHandler; }

    private void invalidatePatternCache() { cachedPatterns = null; }

    private List<IPatternDetails> decodePatterns() {
        if (level == null) return Collections.emptyList();
        var result = new ArrayList<IPatternDetails>();
        int skipped = 0;
        for (int i = 0; i < patternHandler.getSlots(); i++) {
            ItemStack stack = patternHandler.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var details = PatternDetailsHelper.decodePattern(stack, level);
            if (details == null) {
                skipped++;
                continue;
            }
            if (details instanceof IMolecularAssemblerSupportedPattern) {
                // 合成样板走分子装配室路径，CPU 不会调用千机的 pushPattern → 会卡死在计划阶段
                skipped++;
                ChatLog.warn(level, worldPosition, "样板槽 " + i + " 是合成样板，千机只支持处理样板，已跳过");
                continue;
            }
            if (!isValidRecipePattern(details)) {
                // 配方校验：输入输出组合必须匹配真实配方，防止刷物品
                skipped++;
                ChatLog.warn(level, worldPosition, "样板槽 " + i + " 的输入输出无匹配的真实配方，已跳过");
                continue;
            }
            result.add(details);
        }
        if (skipped > 0) {
            ChatLog.info(level, worldPosition, "样板解码: " + result.size() + " 个处理样板可用，跳过 " + skipped + " 个不适用样板");
        }
        return result;
    }

    // ── NBT ──

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("formed", formed);
        tag.putBoolean("byproduct", byproductEnabled);
        tag.put("patterns", patternHandler.serializeNBT());
        tag.put("catalyst", catalystHandler.serializeNBT());
        tag.put("inputBuf", inputBuffer.serializeNBT());
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        byproductEnabled = tag.getBoolean("byproduct");
        if (tag.contains("patterns")) patternHandler.deserializeNBT(tag.getCompound("patterns"));
        if (tag.contains("catalyst")) catalystHandler.deserializeNBT(tag.getCompound("catalyst"));
        if (tag.contains("inputBuf")) inputBuffer.deserializeNBT(tag.getCompound("inputBuf"));
        invalidatePatternCache();
        syncPowerUsage();
    }

    // ── GUI ──

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.ae2addon.qianji");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (!formed) return null;
        return new QianJiMenu(containerId, inventory, worldPosition);
    }

    // ════════════════════════════════════════════════════════
    //  内部槽位处理器
    // ════════════════════════════════════════════════════════

    /**
     * 样板处理器：只接受编码后的样板物品。
     */
    private class PatternHandler extends ItemStackHandler {
        PatternHandler() { super(PATTERN_SLOTS); }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (!PatternDetailsHelper.isEncodedPattern(stack)) return false;
            // 配方校验：只接受"输入输出组合有真实配方"的处理样板（防刷物品）
            if (level != null) {
                var details = PatternDetailsHelper.decodePattern(stack, level);
                if (details instanceof IMolecularAssemblerSupportedPattern) {
                    ChatLog.warn(level, worldPosition, "合成样板不支持，千机只接受处理样板");
                    return false;
                }
                if (details != null && !isValidRecipePattern(details)) {
                    ChatLog.warn(level, worldPosition, "该样板输入输出无匹配的真实配方，已拒收");
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void onContentsChanged(int slot) {
            invalidatePatternCache();
            ChatLog.info(level, worldPosition, "样板槽更新，准备刷新网络样板列表");
            if (formed && getMainNode().isReady()) {
                getMainNode().ifPresent((grid, node) -> {
                    var cs = grid.getService(appeng.api.networking.crafting.ICraftingService.class);
                    if (cs != null) cs.refreshNodeCraftingProvider(node);
                });
            }
            setChanged();
        }
    }

    /**
     * 催化剂处理器
     */
    private class CatalystHandler extends ItemStackHandler {
        CatalystHandler() { super(CATALYST_SLOTS); }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return stack.getItem() instanceof CatalystItem;
        }

        @Override
        protected void onContentsChanged(int slot) {
            syncPowerUsage();
            ChatLog.info(level, worldPosition, "催化剂更换: 等级 " + getCatalystLevel()
                    + "（耗电 ×" + (long) getPowerMultiplier() + "）");
            setChanged();
        }
    }

    /** 输入缓存：PatternProvider 推原料进来 */
    private class InputBuffer extends ItemStackHandler {
        InputBuffer() { super(36); }
        @Override
        protected void onContentsChanged(int slot) { setChanged(); }
    }
}
