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
import com.ae2addon.compat.CreateSequencedCompat;
import com.ae2addon.compat.EMCCompat;
import com.ae2addon.compat.GregTechCompat;
import com.ae2addon.gui.QianJiMenu;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.item.CatalystItem;
import com.ae2addon.util.ChatLog;
import com.ae2addon.util.RecipeByproducts;
import net.minecraft.world.item.crafting.Ingredient;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
 * - 副产物：实时查「真实配方」的次级产出，按概率额外给（催化剂 ×2/×5/×10）
 * - ICraftingProvider — AE2 合成提供商，直接响应合成 CPU 请求
 * - 处理流程：CPU 请求 → 瞬间处理 → 产物（+副产物）注入 ME 网络
 */
public class QianJiBE extends AENetworkBlockEntity implements MenuProvider, ICraftingProvider, ICraftingMachine, Formable {

    private static final int PATTERN_SLOTS = 1280;
    private static final int CATALYST_SLOTS = 1;

    private boolean formed = false;
    /** 副产物（真实配方的次级产出）开关，默认开；留给后续 GUI 开关 */
    private boolean byproductEnabled = true;

    private final PatternHandler patternHandler = new PatternHandler();
    private final CatalystHandler catalystHandler = new CatalystHandler();
    private final Set<AEKey> emitableItems = new HashSet<>();

    @Nullable
    private List<IPatternDetails> cachedPatterns = null;

    // ── 配方校验（防刷物品）──
    /** 输出物品 → 所有能产出它的配方的输入物品集合列表（静态缓存） */
    @Nullable
    private static Map<Item, List<Set<Item>>> recipeIndex = null;
    /** 输出物品 → 能产出它的配方对象（副产物查询用，同一次扫描建） */
    @Nullable
    private static Map<Item, List<Recipe<?>>> recipeObjects = null;
    /** 有配方但输入未知（自定义 RecipeType 如 ProjectE/Mekanism）的输出物品 */
    private static Set<Item> customRecipeOutputs = null;

    private static Map<Item, List<Set<Item>>> getRecipeIndex(Level level) {
        if (recipeIndex != null) return recipeIndex;
        var index = new HashMap<Item, List<Set<Item>>>();
        var objects = new HashMap<Item, List<Recipe<?>>>();
        var custom = new HashSet<Item>();
        try {
            for (var recipe : level.getRecipeManager().getRecipes()) {
                // 产出物品：标准 API + GT 多产出（GT 的 getResultItem 返回空，产出全在 outputs 映射里）
                var outputs = outputItemsOf(recipe, level);
                if (outputs.isEmpty()) continue;

                // 输入物品：GT 走 inputs 映射，其余走标准 getIngredients()
                var inputs = standardInputItems(recipe);
                boolean sequenced = CreateSequencedCompat.isSequencedAssembly(recipe);

                for (var item : outputs) {
                    objects.computeIfAbsent(item, k -> new ArrayList<>()).add(recipe);
                }
                if (inputs.isEmpty()) {
                    // 自定义配方类型（ProjectE 世界转换、Mekanism 机器等）：输入不走标准 API，
                    // 记录"输出可制造"但输入未知，校验时跳过输入匹配，避免误伤
                    // ⚠ 序列装配例外（2026-09-15）：Create 序列装配的 getIngredients() 只报
                    // 基础原料甚至为空，它**有完整可校验的需求**（CreateSequencedCompat）
                    // → 绝不能落入「自定义→放行」，否则「1 份原料→成品」直接被放行
                    if (!sequenced) {
                        custom.addAll(outputs);
                    }
                    continue;
                }
                for (var item : outputs) {
                    index.computeIfAbsent(item, k -> new ArrayList<>()).add(inputs);
                }
            }
        } catch (Exception e) {
            AE2Addon.LOGGER.warn("QianJi: recipe index build failed: {}", e.getMessage());
        }
        recipeIndex = index;
        recipeObjects = objects;
        customRecipeOutputs = custom;
        AE2Addon.LOGGER.info("QianJi: recipe index built, {} standard + {} custom outputs",
                index.size(), custom.size());
        return index;
    }

    /**
     * 校验处理样板：返回 null = 通过，否则返回拒绝原因（便于聊天栏/日志定位）。
     * <p>
     * 规则（2026-09-14 修订，修 sensei 实测的两个漏）：
     * 1. **主产物**：至少一个物品输出能被某条真实配方覆盖（样板输入 ⊇ 该配方输入）；
     *    一条都匹配不上时，看是否属于「自定义配方」输出（ProjectE/Mekanism 等输入未知类型）。
     * 2. **序列装配（Create）**：命中的若是序列装配配方，额外要求样板供齐「基础原料 + 各步原料」，
     *    且原料总量 ≥ 装配次数 loops——否则一次输入直接产出成品 = 跳过全部装配步骤。
     * 3. **次级产出（副产物）**：除主产物外的输出，允许「自身有配方覆盖」**或**
     *    「是命中配方的次级产出」——有写副产物的样板不再被误判为无效。
     * 4. **EMC 守恒**（ProjectE 在场时）。
     */
    @Nullable
    private String validatePattern(IPatternDetails details) {
        if (level == null) return "无世界上下文";
        var index = getRecipeIndex(level);
        var custom = customRecipeOutputs != null ? customRecipeOutputs : Set.<Item> of();
        if (index.isEmpty() && custom.isEmpty()) return null; // 索引构建失败时放行，避免误伤

        var outputs = details.getOutputs();
        if (outputs == null || outputs.length == 0) return "样板没有输出";

        boolean hasItemOutput = false;
        for (var out : outputs) {
            if (out != null && out.what() instanceof AEItemKey) { hasItemOutput = true; break; }
        }
        if (!hasItemOutput) return null; // 纯流体输出暂不校验

        var patternInputs = collectPatternInputs(details);
        var acceptedRecipes = new ArrayList<Recipe<?>>();
        var unmatched = new ArrayList<Item>();
        String sequencedReject = null;

        // 逐物品输出独立判：**只要存在一条可接受的配方**即可（2026-09-15 修：不再只认“第一条命中的配方”）
        for (var out : outputs) {
            if (out == null || out.amount() <= 0) continue;
            if (!(out.what() instanceof AEItemKey itemKey)) continue;
            var item = itemKey.getItem();

            boolean ok = false;
            var candidates = recipeObjects == null ? null : recipeObjects.get(item);
            if (candidates != null) {
                for (var candidate : candidates) {
                    // ① Create 序列装配：getIngredients() 不可信（只报基础原料甚至为空），
                    //    必须用它自己的完整需求校验（原料齐 + 装配次数）——绝不走“自定义放行”
                    if (CreateSequencedCompat.isSequencedAssembly(candidate)) {
                        var reason = sequencedRejectReason(candidate, details, patternInputs);
                        if (reason == null) { acceptedRecipes.add(candidate); ok = true; }
                        else if (sequencedReject == null) sequencedReject = reason;
                        continue;
                    }
                    // ② 标准配方（含 GT）：逐 Ingredient 命中判定（保留标签语义）
                    if (!hasAnyIngredient(candidate)) continue; // 输入未知 → 交给 ③ 判定
                    if (coversRecipeInputs(candidate, patternInputs)) { acceptedRecipes.add(candidate); ok = true; }
                }
            }
            // ③ 输入未知的自定义配方输出：放行（ProjectE/Mekanism 等，避免误伤）
            if (!ok && custom.contains(item)) ok = true;
            if (!ok) unmatched.add(item);
        }

        // 副产物豁免：匹配不上的输出，若它是**某条已接受配方**的次级产出 → 视为合法
        var stillUnmatched = new ArrayList<Item>();
        for (var item : unmatched) {
            boolean isByproduct = false;
            for (var recipe : acceptedRecipes) {
                for (var bp : RecipeByproducts.extract(recipe, level)) {
                    if (bp.stack().getItem() == item) { isByproduct = true; break; }
                }
                if (isByproduct) break;
            }
            if (!isByproduct) stillUnmatched.add(item);
        }

        if (!stillUnmatched.isEmpty()) {
            if (sequencedReject != null) return sequencedReject;
            return "输出「" + displayName(stillUnmatched.get(0)) + "」既无匹配配方，也不是该配方的次级产出";
        }

        if (EMCCompat.isProjectELoaded() && !emcConservationOK(details)) {
            return "EMC 价值不守恒（禁止低价值→高价值）";
        }
        return null;
    }

    private boolean isValidRecipePattern(IPatternDetails details) {
        return validatePattern(details) == null;
    }

    private static String displayName(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    /** 配方的产出物品集合：标准 API + GT 多产出 + Create 序列装配结果池 */
    private static Set<Item> outputItemsOf(Recipe<?> recipe, Level level) {
        var items = new java.util.LinkedHashSet<Item>();
        var standard = recipe.getResultItem(level.registryAccess());
        if (!standard.isEmpty()) items.add(standard.getItem());
        for (var chanced : GregTechCompat.itemOutputs(recipe)) {
            if (!chanced.stack().isEmpty()) items.add(chanced.stack().getItem());
        }
        return items;
    }

    /** 配方所需输入物品集合：GT 走 inputs 映射，其余走标准 getIngredients() */
    private static Set<Item> standardInputItems(Recipe<?> recipe) {
        if (GregTechCompat.isGtRecipe(recipe)) {
            var gt = GregTechCompat.itemInputs(recipe);
            if (!gt.isEmpty()) return gt;
        }
        var items = new HashSet<Item>();
        for (var ing : recipe.getIngredients()) {
            for (var stack : ing.getItems()) {
                if (!stack.isEmpty()) items.add(stack.getItem());
            }
        }
        return items;
    }

    /** 样板输入物品集合（展开所有可能输入） */
    private static Set<Item> collectPatternInputs(IPatternDetails details) {
        var patternInputs = new HashSet<Item>();
        for (var input : details.getInputs()) {
            for (var option : input.getPossibleInputs()) {
                if (option != null && option.what() instanceof AEItemKey k) patternInputs.add(k.getItem());
            }
        }
        return patternInputs;
    }

    /** 配方的输入物品集合（标准 Recipe API + GT；序列装配不适用，见 CreateSequencedCompat） */
    private static Set<Item> recipeItems(Recipe<?> recipe) {
        return standardInputItems(recipe);
    }

    /** 按「样板输入 ⊇ 配方全部输入」找一条能产出该物品的真实配方（副产物查询用） */
    @Nullable
    private static Recipe<?> matchRecipe(Item output, Set<Item> patternInputs) {
        var objects = recipeObjects;
        if (objects == null) return null;
        var candidates = objects.get(output);
        if (candidates == null) return null;
        for (var candidate : candidates) {
            if (coversRecipeInputs(candidate, patternInputs)) return candidate;
        }
        return null;
    }

    /** 配方是否至少有一条标准原料（没有 = 输入未知） */
    private static boolean hasAnyIngredient(Recipe<?> recipe) {
        if (GregTechCompat.isGtRecipe(recipe)) {
            if (!GregTechCompat.itemInputIngredients(recipe).isEmpty()) return true;
        }
        return !recipe.getIngredients().isEmpty();
    }

    /** 样板输入是否覆盖配方全部原料（逐 Ingredient 命中：标签只要求命中其一） */
    private static boolean coversRecipeInputs(Recipe<?> recipe, Set<Item> patternInputs) {
        if (GregTechCompat.isGtRecipe(recipe)) {
            var gt = GregTechCompat.itemInputIngredients(recipe);
            if (!gt.isEmpty()) return coversAllIngredients(gt, patternInputs);
        }
        return coversAllIngredients(recipe.getIngredients(), patternInputs);
    }

    /** 逐 Ingredient：每条至少有一个可选物品出现在样板输入里（标签类原料不必列出全部变体） */
    private static boolean coversAllIngredients(List<Ingredient> ingredients, Set<Item> patternInputs) {
        boolean any = false;
        for (var ingredient : ingredients) {
            var options = ingredient.getItems();
            if (options.length == 0) continue;
            boolean hit = false;
            for (var stack : options) {
                if (!stack.isEmpty() && patternInputs.contains(stack.getItem())) { hit = true; break; }
            }
            if (!hit) return false;
            any = true;
        }
        return any;
    }

    /**
     * Create 序列装配校验（返回 null = 通过，否则拒绝原因）。
     * <p>
     * 为什么单独一条：序列装配配方的 {@code getIngredients()} **只报基础原料**，
     * 装配链上各步原料与装配次数（loops）都不在里面——“1 份原料 → 1 份成品”
     * 就是靠这个漏洞被放行的（2026-09-15 sensei 实测）。
     * 规则：① 样板输入供齐「基础 + 各步原料」；② 各步原料总量 ≥ loops
     * （真实机制里每推进一次装配消耗一份该步原料）。
     */
    @Nullable
    private String sequencedRejectReason(Recipe<?> recipe, IPatternDetails details, Set<Item> patternInputs) {
        var requirement = CreateSequencedCompat.requirement(recipe);
        if (requirement == null) return null;

        // ① 样板里带了「过渡物品」→ 这是序列中的**某一步**（机械手/压床那一刀），按步骤校验。
        //    这正是 Create 自动化的正常用法：机器只负责一步一步推进（2026-09-15 sensei 指出）。
        if (requirement.transitionalItem() != null
                && patternInputs.contains(requirement.transitionalItem())) {
            for (var stepIngredients : requirement.stepIngredients()) {
                if (coversAllIngredients(stepIngredients, patternInputs)) return null;
            }
            return "Create 序列装配：样板带了过渡物品，但缺该步骤所需原料";
        }

        // ② 没带过渡物品 → 想一步到位产出成品，必须供齐全链（基础 + 各步）且原料总量 ≥ loops
        if (!patternInputs.containsAll(requirement.allItems())) {
            var missing = new ArrayList<Item>();
            for (var item : requirement.allItems()) {
                if (!patternInputs.contains(item)) missing.add(item);
            }
            return "Create 序列装配：样板缺少装配所需原料（缺 " + missing.size() + " 种："
                    + displayName(missing.get(0)) + (missing.size() > 1 ? " 等" : "")
                    + "；或改用带过渡物品的步骤样板）";
        }
        long supplied = 0;
        for (var input : details.getInputs()) {
            boolean relevant = false;
            for (var option : input.getPossibleInputs()) {
                if (option != null && option.what() instanceof AEItemKey k
                        && requirement.stepItems().contains(k.getItem())) {
                    relevant = true;
                    break;
                }
            }
            if (relevant) supplied += input.getMultiplier();
        }
        if (supplied < requirement.loops()) {
            return "Create 序列装配：装配次数不足（需 " + requirement.loops()
                    + " 份装配原料，样板只给了 " + supplied + "）";
        }
        return null;
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

    /** 耗电倍率（基础×4 / 高级×20 / 终极×400；与副产物倍率是两回事） */
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
        // 最终防线：配方校验（拒绝时说清原因）
        var rejectReason = validatePattern(pattern);
        if (rejectReason != null) {
            ChatLog.err(level, worldPosition, "任务被拒(" + source + "): " + rejectReason);
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
     * 瞬间合成（2026-09-15 语义升级：**不再照拄样板的产出声明**）。
     * <p>
     * 做法：把样板当「配方查询钥匙」——用真实配方（含各 mod 兼容层）自己推产出，
     * 并逐条对**概率产出**掷骰：
     * <ul>
     *   <li>样板声明的输出：配方标为概率产（GT chanced / Create rollable / 序列装配结果池）→ 掷骰；否则必出</li>
     *   <li>配方里有、样板没声明的概率产出 → 同样掷骰，掷中就作为机器的真实副产注入</li>
     * </ul>
     * 几率模型：{@code min(100%, 配方自带几率 + 催化剂加成(0/2/5/10 个百分点))}
     * —— 基线永远是**配方自己写的几率**；催化剂只给小幅加成，不会把 15% 顶成必然。
     * 配方没标几率的确定性次级产出照给（不受影响）。
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
        var src = IActionSource.ofMachine(this);

        var recipe = findRecipeFor(pattern);
        var chanced = (byproductEnabled && recipe != null)
                ? RecipeByproducts.extract(recipe, level)
                : List.<RecipeByproducts.Chanced> of();
        double bonus = catalystByproductBonus();

        // ── ① 先算这次「真正产出了什么」（含掷骰）──
        var produced = new ArrayList<GenericStack>();
        var declaredItems = new HashSet<Item>();

        for (var out : pattern.getOutputs()) {
            if (out == null || out.amount() <= 0) continue;
            AEItemKey key = out.what() instanceof AEItemKey k ? k : null;
            float chance = -1f;
            if (key != null) {
                declaredItems.add(key.getItem());
                chance = recipeChanceFor(key.getItem(), chanced);
            }
            if (chance >= 0f) {
                float effective = (float) Math.min(1.0, chance + bonus);
                if (level.random.nextFloat() >= effective) {
                    ChatLog.info(level, worldPosition, "概率产出未触发: " + out.what().getDisplayName()
                            + " ×" + out.amount() + "（概率 " + Math.round(effective * 100) + "%）");
                    continue;
                }
            }
            produced.add(new GenericStack(out.what(), out.amount()));
        }

        for (var bp : chanced) {
            if (bp.stack().isEmpty()) continue;
            if (declaredItems.contains(bp.stack().getItem())) continue; // 已在 ① 处理
            float chance = bp.chance() > 0f ? bp.chance() : 1.0f;
            float effective = (float) Math.min(1.0, chance + bonus);
            if (level.random.nextFloat() >= effective) continue;
            var key = AEItemKey.of(bp.stack());
            produced.add(new GenericStack(key, bp.stack().getCount()));
            ChatLog.ok(level, worldPosition, "副产物: " + key.getDisplayName().getString()
                    + " ×" + bp.stack().getCount() + "（概率 " + Math.round(effective * 100) + "%）");
        }

        if (produced.isEmpty()) {
            ChatLog.info(level, worldPosition, "本次没有任何产出（概率全未触发）");
            return true;
        }

        // ── ② 空间检查（模拟）──
        for (var out : produced) {
            long room = netInv.insert(out.what(), out.amount(), Actionable.SIMULATE, src);
            if (room < out.amount()) {
                ChatLog.err(level, worldPosition, "输出空间不足: " + out.what().getDisplayName()
                        + " 需要 " + out.amount() + " 可放 " + room);
                return false;
            }
        }

        // ── ③ 真正注入 ──
        for (var out : produced) {
            long inserted = netInv.insert(out.what(), out.amount(), Actionable.MODULATE, src);
            if (inserted < out.amount()) {
                ChatLog.err(level, worldPosition, "输出注入不完整: " + out.what().getDisplayName()
                        + " 预期 " + out.amount() + " 实际 " + inserted);
                return false;
            }
        }
        return true;
    }

    /**
     * 催化剂对「副产物几率」的加成（**加法百分点**）。
     * <p>
     * ⚠ 2026-09-15 修正：原来是「配方几率 × 催化剂倍率（×2/×5/×10）」，
     * 结果 GT 一个 15% 的副产在高级/终极催化剂下直接变 75%/100%
     * （sensei 实测：15% 副产 5 中 5）。改成加法百分点：15% → 17%/20%/25%，
     * 永不把真实几率顶成必然。
     * 要调就改这四个数（或全置 0 = 催化剂不影响副产几率）。
     */
    private double catalystByproductBonus() {
        return switch (getCatalystLevel()) {
            case 1 -> 0.02;
            case 2 -> 0.05;
            case 3 -> 0.10;
            default -> 0.0;
        };
    }

    /**
     * 该物品在「配方的概率产出」里的几率。
     *
     * @return &lt;0 表示配方没把它列为概率产出（= 必出）
     */
    private static float recipeChanceFor(Item item, List<RecipeByproducts.Chanced> chanced) {
        for (var c : chanced) {
            if (!c.stack().isEmpty() && c.stack().getItem() == item) {
                return c.chance() > 0f ? c.chance() : 1.0f;
            }
        }
        return -1f;
    }

    /** 样板 → 真实配方：与校验同规则（样板输入覆盖配方全部输入） */
    @Nullable
    private Recipe<?> findRecipeFor(IPatternDetails details) {
        if (level == null) return null;
        getRecipeIndex(level);
        if (recipeObjects == null) return null;

        var patternInputs = collectPatternInputs(details);
        for (var out : details.getOutputs()) {
            if (out == null || out.amount() <= 0) continue;
            if (!(out.what() instanceof AEItemKey itemKey)) continue;
            var matched = matchRecipe(itemKey.getItem(), patternInputs);
            if (matched != null) return matched;
        }
        return null;
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
            var rejectReason = validatePattern(details);
            if (rejectReason != null) {
                // 配方校验：输入输出组合必须匹配真实配方，防止刷物品
                skipped++;
                ChatLog.warn(level, worldPosition, "样板槽 " + i + " 被拒：" + rejectReason);
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
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        byproductEnabled = !tag.contains("byproduct") || tag.getBoolean("byproduct");
        if (tag.contains("patterns")) patternHandler.deserializeNBT(tag.getCompound("patterns"));
        if (tag.contains("catalyst")) catalystHandler.deserializeNBT(tag.getCompound("catalyst"));
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
                if (details != null) {
                    var rejectReason = validatePattern(details);
                    if (rejectReason != null) {
                        ChatLog.warn(level, worldPosition, "该样板被拒收：" + rejectReason);
                        return false;
                    }
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

    /** 基础副产物几率常量已废弃（2026-09-15）：几率直接用配方自带值 × 催化剂倍率 */
    // （保留备注，避免后人又把「基础 10%」的乘法模型加回来）
}
