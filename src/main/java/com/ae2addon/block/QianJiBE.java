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
import com.ae2addon.compat.ProductiveBeesCompat;
import com.ae2addon.compat.ThermalCompat;
import com.ae2addon.gui.QianJiMenu;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.item.CatalystItem;
import com.ae2addon.crafting.QianJiByproducts;
import com.ae2addon.crafting.QianJiPatternDetails;
import com.ae2addon.recipe.QianJiPatternData;
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
 * - 副产物：实时查「真实配方」的次级产出，按概率额外给
 * - 催化剂：**所有产出（主产物 + 副产物）的数量倍数**（每次合成掷一次）
 * - ICraftingProvider — AE2 合成提供商，直接响应合成 CPU 请求
 * - 处理流程：CPU 请求 → 瞬间处理 → 产物（+副产物）注入 ME 网络
 */
public class QianJiBE extends AENetworkBlockEntity implements MenuProvider, ICraftingProvider, ICraftingMachine, Formable,
        appeng.helpers.patternprovider.PatternContainer, com.ae2addon.crafting.QianJiByproducts.RecipeLookup {

    private static final int PATTERN_SLOTS = 1280;
    private static final int CATALYST_SLOTS = 1;

    private boolean formed = false;
    /** 多方块朝向（成型时方向检测得出；见 {@link #getFacing()}） */
    private Direction facing = Direction.NORTH;
    /** 副产物（真实配方的次级产出）开关，默认开；留给后续 GUI 开关 */
    private boolean byproductEnabled = true;

    private final PatternHandler patternHandler = new PatternHandler();
    private final CatalystHandler catalystHandler = new CatalystHandler();
    private final Set<AEKey> emitableItems = new HashSet<>();

    // ── 推送账：CPU 把材料推给我们后、我们**还没合成**的部分（2026-09-15 sensei：取消后材料没返还）──
    //
    // 千机不像无限接口那样有蓄水池（材料一到手就当成本吃掉）→ 只记「已推送、尚未合成」的账：
    //   合成成功 → 销账（材料确实变成了产物）
    //   任务取消 / 新任务开始 → 把剩下的退回网络，并把簇标为已取消，
    //   避免延迟到下一 tick 的 callable 又合成一次（那等于复制）

    /** 活跃千机（取消回退时遍历） */
    private static final Set<QianJiBE> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 簇 → 已推送未合成的材料（key → 量） */
    private final Map<Object, Map<AEKey, Long>> pushedByCluster = new HashMap<>();
    /** 刚被取消的簇 → 取消时的 tick（只挡「取消前已排队、尚未跑」的那批 callable） */
    private static final Map<Object, Long> CANCELLED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 本次推送是否已被取消（**按 tick 判**，不做长期标记）：
     * 只当「取消发生在本次 push 之后」才跳过 —— 否则一旦取消过，后续所有任务都会被误伤
     * （2026-09-15 sensei 实测：「取消一次后所有千机合成任务都自动取消」，就是长期标记造成的）
     */
    private static boolean wasCancelled(@Nullable Object cluster, long pushTick) {
        if (cluster == null) return false;
        Long cancelledAt = CANCELLED.get(cluster);
        return cancelledAt != null && cancelledAt >= pushTick;
    }

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

    /**
     * 兼容层配方的输入槽缓存（**按配方实例**，见 {@link RecipeByproducts} 同类注释：
     * 同一类不同实例的输入完全不同，绝不能按类缓存）。
     * <p>
     * 为什么要缓存：建索引时每个 Thermal/PB 配方要调一次 {@code inputSlots}（纯反射，里面会读
     * {@code getInputItems/getInputFluids/getBeeType/…}），而校验每张样板又要把候选配方的槽遍历一遍；
     * 不缓存的话每次校验都要重跑一遍反射（30 个 Thermal 类型 + 8 个 PB 类型）。
     */
    private static final Map<Recipe<?>, List<List<GenericStack>>> COMPAT_INPUT_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static final int COMPAT_INPUT_CACHE_LIMIT = 20_000;

    private static Map<Item, List<Set<Item>>> getRecipeIndex(Level level) {
        if (recipeIndex != null) return recipeIndex;
        var index = new HashMap<Item, List<Set<Item>>>();
        var objects = new HashMap<Item, List<Recipe<?>>>();
        var custom = new HashSet<Item>();
        try {
            for (var recipe : level.getRecipeManager().getRecipes()) {
                // 产出物品：标准 API + GT 多产出 + 兼容层（见 outputItemsOf 注释）
                var outputs = outputItemsOf(recipe, level);
                if (outputs.isEmpty()) continue;

                // 输入物品：GT 走 inputs 映射，Thermal/PB 走兼容层，其余走标准 getIngredients()
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
                    //
                    // ⚠ 2026-09-20：兼容层配方（Thermal/PB）**已经走上面两条 compat 分支**把输入读出来了，
                    // 所以正常情况下不该再落到这里；真落进来（反射没打通 → 输入恒空）时，
                    // 至少不会把「本来能认的产出」直接退化成「输入未知 → 放行」。
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
            // 日志兜底：LOGGER 是 AE2Addon 里第一个赋值的静态字段，正常情况下一直可用；
            // 这里仍然兜一层 Throwable，避免"日志本身出问题"把整台机器的索引构建带崩（离线/异常期自保）
            try {
                AE2Addon.LOGGER.warn("QianJi: recipe index build failed: {}", e.getMessage());
            } catch (Throwable ignored) {
                // 连日志都打不出来 → 只能算了
            }
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

    /**
     * 配方的产出物品集合：标准 API + GT 多产出 + 兼容层（Thermal / Productive Bees）。
     * <p>
     * ⚠ 2026-09-20 修本 bug（sensei 实机证据 16:18:31，v221）：
     * {@code [ae2addon][settle] 结算被拒: 经验蜜蜂蜜脾 ×36 (输出「蜜脾」既无匹配配方，也不是该配方的次级产出)}。
     * <p>
     * <b>为什么必须带上兼容层</b>：Thermal 与 PB 的配方把数据放在**自己的字段**里，标准 API 全是空的 ——
     * 已用 javap 逐条证实（本次复核，不是推断）：
     * <ul>
     *   <li>PB {@code AdvancedBeehiveRecipe.m_8043_}（= getResultItem）直接 {@code return ItemStack.EMPTY}；
     *       产出在 {@code TagOutputRecipe.itemOutput} / {@code getRecipeOutputs()} 里
     *       （实机那条 = {@code data/productivebees/recipes/bee_produce/experience_bee.json}：
     *        {@code productivebees:configurable_honeycomb} + {@code {EntityTag:{type:"productivebees:experience"}}}，chance 40）</li>
     *   <li>Thermal {@code SerializableRecipe.m_8043_} 同样直接 {@code return ItemStack.EMPTY}，
     *       产出在 {@code getOutputItems()} / {@code getOutputItemChances()}</li>
     * </ul>
     * 于是索引里**根本没有这些产出物品** → 从这两个 mod 提取出来的样板，一律被自己的反作弊校验
     * 判成「无匹配配方」而拒掉（settle / 推送 / 样板槽解码三条路都会中招）。取兼容层的
     * {@code outputs()} 就解决了，而且**不需要动兼容层本身**。
     * <p>
     * <b>为什么不能简单把这类配方丢进 {@code custom}（"输入未知 → 放行"）</b>：那等于把这两个 mod
     * 的**全部输入校验关掉** —— 「1 个泥土 → 1 个铁板」这种样板也会被放行（只要有人把输入编成泥土）。
     * 本条只补"产出认得出来"，输入侧另由 {@link #coversRecipeInputs} 用兼容层的 {@code inputSlots()}
     * 真校验（那一处必须一起改，否则 {@link #hasAnyIngredient} 会认为"输入未知"而整条跳过覆盖判定）。
     */
    private static Set<Item> outputItemsOf(Recipe<?> recipe, Level level) {
        var items = new java.util.LinkedHashSet<Item>();
        var standard = recipe.getResultItem(level.registryAccess());
        if (!standard.isEmpty()) items.add(standard.getItem());
        for (var chanced : GregTechCompat.itemOutputs(recipe)) {
            if (!chanced.stack().isEmpty()) items.add(chanced.stack().getItem());
        }
        // 兼容层产出（按**物品**入索引；流体产出天然被 `instanceof AEItemKey` 滤掉，与既有粒度一致）
        for (var key : compatOutputItems(recipe)) {
            if (key != null) items.add(key);
        }
        return items;
    }

    /**
     * 兼容层（Thermal / Productive Bees）产出里的**物品**部分。
     * <p>
     * 判据与 {@code QianJiRecipeModel.fromRecipe} 里那两条分支**同一个**（{@code isThermalRecipe} /
     * {@code isProductiveBeesRecipe}），避免"提取时认、校验时不认"这种自相矛盾再次出现。
     * 用 {@code LinkedHashSet} 去重：{@code productivebees} 命名空间下也有 Thermal 配方
     * （thermal:smelter / chiller / bottler），两个 compat 可能都认。
     * <p>
     * 注意 {@link GenericStack#what()} 可能是物品也可能是流体 → **只有 {@link AEItemKey} 才取
     * {@link AEItemKey#getItem()}**（流体产出按现状不进物品索引）。
     */
    private static Set<Item> compatOutputItems(Recipe<?> recipe) {
        var items = new java.util.LinkedHashSet<Item>();
        try {
            if (ThermalCompat.isThermalRecipe(recipe)) {
                for (var stat : ThermalCompat.outputs(recipe)) {
                    if (stat != null && stat.stack() != null && stat.stack().what() instanceof AEItemKey k) {
                        items.add(k.getItem());
                    }
                }
            }
            if (ProductiveBeesCompat.isProductiveBeesRecipe(recipe)) {
                for (var stat : ProductiveBeesCompat.outputs(recipe)) {
                    if (stat != null && stat.stack() != null && stat.stack().what() instanceof AEItemKey k) {
                        items.add(k.getItem());
                    }
                }
            }
        } catch (Throwable t) {
            // 兼容层自己已经把反射异常吞干净了；这里再兜一层，绝不让校验路径把异常抛给调用方
            AE2Addon.LOGGER.warn("QianJi: compat outputs failed for {}: {}", recipe, t.toString());
        }
        return items;
    }

    /** 配方所需输入物品集合：GT 走 inputs 映射，Thermal/PB 走兼容层，其余走标准 getIngredients() */
    private static Set<Item> standardInputItems(Recipe<?> recipe) {
        if (GregTechCompat.isGtRecipe(recipe)) {
            var gt = GregTechCompat.itemInputs(recipe);
            if (!gt.isEmpty()) return gt;
        }
        var compat = compatInputItems(recipe);
        if (!compat.isEmpty()) return compat;
        var items = new HashSet<Item>();
        for (var ing : recipe.getIngredients()) {
            for (var stack : ing.getItems()) {
                if (!stack.isEmpty()) items.add(stack.getItem());
            }
        }
        return items;
    }

    /**
     * 兼容层配方的输入物品集合（**入索引 + 覆盖判定共用同一份**）。
     * <p>
     * 与 {@link #compatOutputItems} 同一天同一条实机证据（16:18:31 的 settle 被拒）：
     * Thermal/PB 配方的 {@code getIngredients()} 也是空的（PB {@code TagOutputRecipe} 根本没覆写它，
     * CoFH {@code SerializableRecipe} 也没有）→ 光补产出、不补输入的话，
     * {@link #hasAnyIngredient} 仍判"输入未知"→ 覆盖判定被整条跳过 → 等于没校验。
     * <p>
     * 流体槽、设备环境方块槽都会出现在兼容层的 slot 列表里（见各 compat 的 {@code inputSlots}），
     * 这里取它们的 **{@link AEItemKey} 候选**；纯流体槽没有物品候选 → 该槽为空集合
     * （覆盖判定按"该槽不参与"处理，与既有 {@code coversAllIngredients} 里"空 Ingredient 跳过"同义）。
     */
    private static Set<Item> compatInputItems(Recipe<?> recipe) {
        var items = new java.util.LinkedHashSet<Item>();
        for (var slot : compatInputSlots(recipe)) {
            for (var option : slot) {
                if (option != null && option.what() instanceof AEItemKey k) items.add(k.getItem());
            }
        }
        return items;
    }

    /**
     * 兼容层配方的输入槽（与兼容层 {@code inputSlots(recipe)} 完全同序、同粒度）——索引与覆盖判定
     * 都从这一个方法取，保证"进索引的那一份"和"校验用的那一份"永远一致。
     */
    private static List<List<GenericStack>> compatInputSlots(Recipe<?> recipe) {
        var cached = COMPAT_INPUT_CACHE.get(recipe);
        if (cached != null) return cached;
        var slots = new ArrayList<List<GenericStack>>();
        try {
            if (ThermalCompat.isThermalRecipe(recipe)) {
                slots.addAll(ThermalCompat.inputSlots(recipe));
            }
            if (ProductiveBeesCompat.isProductiveBeesRecipe(recipe)) {
                slots.addAll(ProductiveBeesCompat.inputSlots(recipe));
            }
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("QianJi: compat inputSlots failed for {}: {}", recipe, t.toString());
        }
        // 与 RecipeByproducts 的缓存同款用法：按实例身份缓存（不依赖 equals），超上限整表清空
        if (COMPAT_INPUT_CACHE.size() > COMPAT_INPUT_CACHE_LIMIT) COMPAT_INPUT_CACHE.clear();
        COMPAT_INPUT_CACHE.put(recipe, slots);
        return slots;
    }

    /**
     * 兼容层配方的覆盖判定：样板输入必须覆盖**所有非催化剂槽**。
     * <p>
     * ① 为什么跳过催化剂槽：{@link ThermalCompat#isCatalystSlot} / {@link ProductiveBeesCompat#isCatalystSlot}
     * 认的是"模具 / 设备环境方块 / 留在箱子里的蜜蜂" —— 这些在千机自用样板里是 {@code catalyst=true} 槽
     * （见 {@code QianJiPatternDetails}：非消耗槽**不向 AE2 声明**，但 {@link #collectPatternInputs}
     * 对自有样板会把它们并回来）。若对"非自有样板"（直接从 AE2 编码器来的）也要求它们，
     * 就会因为"我们自己的执行层不消耗它"而拒掉一张本来正确的样板。
     * <p>
     * ② 其余槽一律**逐个要求命中**（标签只要求命中其一）—— 这条就是反作弊的本体：
     * 任何"少给原料"的样板都过不了，不存在"输入未知 → 放行"的后门。
     */
    private static boolean coversCompatInputs(Recipe<?> recipe, Set<Item> patternInputs) {
        var slots = compatInputSlots(recipe);
        for (int i = 0; i < slots.size(); i++) {
            var options = slots.get(i);
            var items = new ArrayList<Item>();
            for (var option : options) {
                if (option != null && option.what() instanceof AEItemKey k) items.add(k.getItem());
            }
            if (items.isEmpty()) continue;                 // 纯流体槽：不参与物品校验（与既有语义一致）
            if (isCompatCatalystSlot(recipe, i, options)) continue; // 不消耗的槽：不要求样板声明
            boolean hit = false;
            for (var item : items) {
                if (patternInputs.contains(item)) { hit = true; break; }
            }
            if (!hit) return false;
        }
        // 全部槽都是"不消耗"（或没有物品槽）→ 没有可要求的物品输入，判为满足。
        // ⚠ 这一条是按**执行语义**定的：不消耗的槽本来就不参与千机的提取/消耗，样板里有没有它
        //   都不影响能不能合成（如 rock_gen：环境方块是设备旁边的一个方块，千机是虚拟执行的）。
        //   若这里返回 false，设备映射那 2 类（rock_gen / tree_extractor）会**整类**被判成"输入不覆盖"而拒掉。
        //   ⚠ 它只对"全是催化剂槽"的配方生效；只要有一个真消耗的物品槽，就必须逐个命中（反作弊主体）。
        return true;
    }

    /**
     * 该兼容层槽是不是「不消耗」（模具 / 设备环境方块 / 留在原地的蜜蜂）。
     * 分别问两个 compat 自己的判据 —— **不在这里重写一套启发式**（各自的语义差异见它们的 javadoc：
     * Thermal 只有 {@code _die} 与设备映射环境方块不消耗；PB 按槽序号区分"蜜蜂留下来"与"蜜蜂被换掉"）。
     */
    private static boolean isCompatCatalystSlot(Recipe<?> recipe, int slotIndex, List<GenericStack> options) {
        try {
            if (ThermalCompat.isThermalRecipe(recipe) && ThermalCompat.isCatalystSlot(recipe, slotIndex, options)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 反射层异常 → 保守当"消耗"处理（宁可要求样板声明，也不放过刷物品）
        }
        try {
            if (ProductiveBeesCompat.isProductiveBeesRecipe(recipe)
                    && ProductiveBeesCompat.isCatalystSlot(recipe, slotIndex, options)) {
                return true;
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return false;
    }

    /**
     * 样板输入物品集合（展开所有可能输入）。
     * <p>
     * 2026-09-15 sensei 实测修复：**输入含「催化剂类（不消耗）」的千机自用配方，下单报
     * 「输出「X」既无匹配配方，也不是该配方的次级产出」**。
     * <p>
     * 根因：自有样板里的非消耗输入（GT notConsumable / 模具 / AE2 压印模板）**刻意不向 AE2 声明**
     * （见 {@code QianJiPatternDetails} 构造里的 {@code if (slot.catalyst()) continue;} ——
     * 免得 CPU 抽取并吞掉模具），但它在**真实配方里确实是要求的输入**；
     * 这里若只按 {@code details.getInputs()} 收集，覆盖判定就会认为「配方要的东西样板没有」→ 直接拒绝。
     * <p>
     * 修法：自有样板把**全部槽（含 catalyst）**并进来 —— 它们本来就是样板声明过的输入，
     * 只是「不消耗」而已（消耗语义由执行层按 catalyst 标记处理，与校验无关）。
     */
    private static Set<Item> collectPatternInputs(IPatternDetails details) {
        // 批量推送会用 ScaledPattern(N×) 包住自有样板 → 先拆包再判类型（技能书 §2c-7 同类坑）
        if (details instanceof com.ae2addon.crafting.ScaledPattern scaled && scaled.base() != null) {
            details = scaled.base();
        }
        var patternInputs = new HashSet<Item>();
        for (var input : details.getInputs()) {
            for (var option : input.getPossibleInputs()) {
                if (option != null && option.what() instanceof AEItemKey k) patternInputs.add(k.getItem());
            }
        }
        if (details instanceof com.ae2addon.crafting.QianJiPatternDetails own) {
            for (var slot : own.data().inputs()) {
                for (var option : slot.options()) {
                    if (option != null && option.what() instanceof AEItemKey k) patternInputs.add(k.getItem());
                }
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
        // 2026-09-20：Thermal / PB 的标准 getIngredients() 是空的（数据在自己字段里），
        // 但兼容层能给出真实输入槽 → 这里必须认，否则下面 coversRecipeInputs 会被整条跳过，
        // 等于这两个 mod 的配方**根本没有输入校验**（"1 泥土 → 1 铁板"照样放行）。
        if (!compatInputSlots(recipe).isEmpty()) return true;
        return !recipe.getIngredients().isEmpty();
    }

    /** 样板输入是否覆盖配方全部原料（逐 Ingredient 命中：标签只要求命中其一） */
    private static boolean coversRecipeInputs(Recipe<?> recipe, Set<Item> patternInputs) {
        if (GregTechCompat.isGtRecipe(recipe)) {
            var gt = GregTechCompat.itemInputIngredients(recipe);
            if (!gt.isEmpty()) return coversAllIngredients(gt, patternInputs);
        }
        // 兼容层配方（Thermal/PB）：走它们自己的 inputSlots（非催化剂槽逐个必须命中）
        var compatSlots = compatInputSlots(recipe);
        if (!compatSlots.isEmpty()) return coversCompatInputs(recipe, patternInputs);
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
        ACTIVE.add(this);
        updateSideExposure();
        syncPowerUsage();
    }

    @Override
    public void onChunkUnloaded() {
        ACTIVE.remove(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        ACTIVE.remove(this);
        super.setRemoved();
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode()
                .addService(ICraftingProvider.class, this);
    }

    // ── 成型状态 ──

    public boolean isFormed() { return formed; }

    /**
     * 多方块**朝向**（成型时由方向检测得出；创造变体按放置时玩家的水平朝向）。
     * <p>
     * 2026-09-15 sensei「完善多方块方向检测」：结构四个水平朝向都认识，
     * 识别出的朝向存在这里（供后续 IO/渲染/条件判断使用），并随 NBT 持久化。
     */
    public Direction getFacing() { return facing; }

    public void setFacing(@Nullable Direction facing) {
        if (facing == null || !facing.getAxis().isHorizontal()) return;
        if (this.facing == facing) return;
        this.facing = facing;
        setChanged();
    }

    @Override
    public void applyCreativeFormed(@Nullable Player player) {
        if (player != null) setFacing(player.getDirection().getOpposite());
        setFormed(true);
    }

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
            // 2026-09-22 v286：原来这里往聊天栏广播"千机向网络暴露 N 个样板"（sensei：放/取样板时
            // 聊天栏会冒数字），每次样板槽变化都会触发 → 聊天栏消息整个去掉，日志收进 debugLogs。
            if (com.ae2addon.config.AE2AddonConfig.debugLogs()) {
                AE2Addon.LOGGER.info("QianJi: Decoded {} patterns", cachedPatterns.size());
            }
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
        return handlePush("CPU", pattern, inputCounts);
    }

    /**
     * ICraftingMachine.pushPattern — 相邻 PatternProvider 推送任务
     */
    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] patternDetails, Direction direction) {
        return handlePush("PatternProvider", pattern, patternDetails);
    }

    private boolean handlePush(String source, IPatternDetails pattern, @Nullable KeyCounter[] inputCounts) {
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
        // ⚠ 2026-09-19（sensei：「网络内没有催化剂物品时无法合成」）：
        // 「输入全是催化剂（不消耗）」的配方**必须确认网络里真有这些催化剂**才能合成。
        // 原因：催化剂槽刻意不参与提取（免得 CPU 吞掉模具）⇒ 本来"没有催化剂也能白跑"，
        // 这类配方就成了纯粹的白拿（精华更是白送）。这里直接拒绝并说明缺什么。
        var gateData = ownDataOf(pattern);
        if (gateData != null && QianJiByproducts.looksAllCatalyst(gateData)
                && !networkHasAllCatalysts(gateData)) {
            warnMissingCatalysts(gateData, source);
            return false;
        }
        // 2026-09-22 v286：每条任务都往聊天栏报一句太吵 → 只在诊断开关下说
        if (craftDiagnostics()) {
            ChatLog.info(level, worldPosition, "收到合成任务(" + source + "): " + describeOutputs(pattern));
        }

        // 关键时序：产物必须延迟 1 tick 注入！
        // CPU 在 pushPattern 返回后才把预期产物登记进 waitingFor，
        // 若在 pushPattern 内同步注入，insertIntoCpus 认领时 waitingFor 为空
        // → 产物直接进网络存储 → 任务永远"缺 N 个"卡死。
        final var lvl = level;
        // 批量推送（ScaledPattern N×）先**拆开**：不拆的话「自有样板」会被当成普通 AE2 样板
        // → 退化成兼容通道（去查真实配方、把无关的概率副产也注入）——
        // 巨型订单的症状：莫名往网络里塞样板里根本没有的物品。
        final long scale;
        final IPatternDetails basePattern;
        if (pattern instanceof com.ae2addon.crafting.ScaledPattern scaled) {
            scale = scaled.multiplier();
            basePattern = scaled.base();
        } else {
            scale = 1;
            basePattern = pattern;
        }
        final var p = basePattern;
        final var src = source;
        final QianJiPatternData ownData = basePattern instanceof QianJiPatternDetails qp ? qp.data() : null;
        // 推送账：记到当前推送的 CPU 簇上（mixin 在 dispatch 期间设了 currentPushingCluster）
        final Object cluster = com.ae2addon.crafting.CraftingCompat.currentPushingCluster;
        recordPushed(cluster, inputCounts);
        final long pushTick = TickHandler.instance().getCurrentTick();
        TickHandler.instance().addCallable(lvl, () -> {
            if (lvl == null || lvl.isClientSide || !formed) return;
            if (wasCancelled(cluster, pushTick)) {
                // 任务已取消：材料已随取消回退，这里再合成 = 复制
                ChatLog.info(lvl, worldPosition, "任务已取消，跳过本次合成（材料已退回网络）");
                return;
            }
            if (scale > 1 && craftDiagnostics()) {
                ChatLog.info(lvl, worldPosition, "批量任务 ×" + scale + "（自有样板=" + (ownData != null) + "）");
            }
            boolean ok = instantCraft(p, ownData, scale);
            if (ok) {
                clearPushed(cluster, inputCounts);
                ChatLog.ok(lvl, worldPosition, "千机完成合成: " + describeOutputs(p));
            } else {
                // 失败不吞料：账保留，任务取消/新任务时会退回网络
                ChatLog.err(lvl, worldPosition, "千机拒绝任务(" + src
                        + "): 未接入网络或输出空间不足（材料保留，取消任务可退回）");
            }
        });
        return true;
    }

    // ── 虚拟结算（2026-09-18 sensei 定：千机样板由**插了该样板的那台千机**结算）──

    /**
     * 这台千机自己插了该样板吗（按样板定义比对）。
     * <p>
     * sensei 定稿：**只有插入了该千机样板的千机可以执行结算** —— 不允许"样板在 A 机、
     * 借用 B 机身份结算"。
     */
    public boolean declaresPattern(IPatternDetails pattern) {
        if (pattern == null || !formed) return false;
        var want = pattern.getDefinition();
        if (want == null) return false;
        return ownPatternKeys().contains(want);
    }

    /**
     * 本机样板槽的**定义 key 集合**（缓存）。
     * <p>
     * ⚠ 2026-09-18：原来 {@code declaresPattern} 每次遍历 1280 个槽并逐个 {@code AEItemKey.of}，
     * 而归属查表（{@code settleOwner}）在网络里每台千机上都要调一次 ——
     * 策划阶段对每个样板 × 每台千机各扫一遍槽位，直接把「计划合成」卡死（sensei 实测）。
     * 现在只扫一次并缓存；样板装/卸时（{@code cachedPatterns} 置 null）跟着失效。
     */
    private final java.util.Set<AEItemKey> ownPatternKeyCache = new HashSet<>();

    private java.util.Set<AEItemKey> ownPatternKeys() {
        if (!ownPatternKeyCache.isEmpty()) return ownPatternKeyCache;
        for (int i = 0; i < patternHandler.getSlots(); i++) {
            var stack = patternHandler.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var key = AEItemKey.of(stack);
            if (key != null) ownPatternKeyCache.add(key);
        }
        return ownPatternKeyCache;
    }

    /** 结算机器查表缓存：样板定义 → 机器（含"没有"的否定结果），每 tick 清一次 */
    private static final Map<AEItemKey, Object> SETTLE_OWNER_CACHE = new HashMap<>();
    private static final Object SETTLE_NONE = new Object();
    private static long settleCacheTick = Long.MIN_VALUE;

    /**
     * 找出「插了该样板的那台成型千机」——虚拟结算的执行者。
     * <p>
     * 找不到 → 返回 null，判定处即拒绝虚拟结算（避免样板在网络里、机器不在时凭空结算）。
     * 结果按 tick 缓存：同一 tick 内同一个样板只扫一遍。
     */
    @Nullable
    public static QianJiBE settleOwner(IPatternDetails pattern) {
        if (pattern == null) return null;
        var key = pattern.getDefinition();
        if (key == null) return null;
        long now = TickHandler.instance().getCurrentTick();
        if (now != settleCacheTick) {
            SETTLE_OWNER_CACHE.clear();
            settleCacheTick = now;
        }
        var cached = SETTLE_OWNER_CACHE.get(key);
        if (cached != null) return cached == SETTLE_NONE ? null : (QianJiBE) cached;

        QianJiBE found = null;
        for (var be : ACTIVE) {
            if (be.isRemoved() || be.level == null || be.level.isClientSide) continue;
            try {
                if (be.declaresPattern(pattern)) {
                    found = be;
                    break;
                }
            } catch (RuntimeException ignored) {
                // 单台异常不影响其余
            }
        }
        SETTLE_OWNER_CACHE.put(key, found == null ? SETTLE_NONE : found);
        return found;
    }

    /**
     * 虚拟结算 N 份：**只算不注入**（CPU 侧模拟，材料由调用方在同一步提取并消耗）。
     * <p>
     * 与 {@link #instantCraft} 共用同一份 {@link QianJiByproducts} 逻辑，所以
     * 主产物、概率副产（逐份掷骰）、催化剂倍数、副产开关的口径完全一致。
     *
     * @return 掷骰结果（可能为空）；样板不合法或不在本机 → null（调用方应回退真实推送）
     */
    @Nullable
    public QianJiByproducts.Outcome settle(IPatternDetails pattern, long batches) {
        if (!formed || level == null) return null;
        var reason = validatePattern(pattern);
        if (reason != null) {
            // ⚠ 2026-09-19（sensei：化学品千机配方卡住）：把拒绝原因**无条件**打出来
            // （原来只在 craftDiagnostics() 下打，导致"卡住但日志无声"）
            AE2Addon.LOGGER.info("[ae2addon][settle] 结算被拒: {} ({})",
                    describeOutputs(pattern), reason);
            return null;
        }
        var ownData = pattern instanceof QianJiPatternDetails qp ? qp.data() : null;
        // 「输入全是催化剂」的配方要额外确认**催化剂真的在网络里**（sensei 2026-09-19）。
        // 只在真判成这种配方时才查网络 —— 其它配方不必多花这次存储查询。
        boolean missingCatalysts = false;
        boolean catalystsPresent = false;
        if (ownData != null && QianJiByproducts.looksAllCatalyst(ownData)) {
            catalystsPresent = networkHasAllCatalysts(ownData);
            missingCatalysts = !catalystsPresent;
        }
        if (missingCatalysts) {
            // ⚠ sensei：「网络内没有催化剂物品时无法合成」——虚拟结算这条路也拒绝
            // （handlePush 那条路另有一道同样的门，两条路都堵上）
            warnMissingCatalysts(ownData, "虚拟结算");
            return null;
        }
        var ctx = new QianJiByproducts.Context(settleRandom(), level, byproductEnabled,
                Math.max(1, batches), this::rollCatalystMultiplier, catalystKind(), catalystsPresent);
        return ownData != null
                ? QianJiByproducts.roll(ownData, ctx)
                : QianJiByproducts.rollCompat(pattern, this, ctx);
    }

    /**
     * 「输入全是催化剂」的配方：**网络里必须真的有这些催化剂物品**（sensei 2026-09-19）。
     * <p>
     * 为什么需要：催化剂槽（GT notConsumable / 模具 / AE2 压印模板）**刻意不参与提取**
     * （{@code QianJiPatternDetails} 构造里跳过它们，免得 CPU 把模具吞掉），
     * 于是机器本来可以"网络里没有催化剂也照跑" —— 那精华就等于白送。
     * <p>
     * 用 **SIMULATE** 探测：网络里一点东西都不会被抽走；每个催化剂槽只要**任一候选**
     * 满足所需数量即可（与配方"多候选任选其一"的语义一致）。
     */
    private boolean networkHasAllCatalysts(QianJiPatternData data) {
        if (data == null || data.inputs() == null || data.inputs().isEmpty()) {
            return false;
        }
        var grid = getGrid();
        if (grid == null) {
            return false;
        }
        var storage = grid.getStorageService().getInventory();
        var src = appeng.api.networking.security.IActionSource.ofMachine(this);
        for (var slot : data.inputs()) {
            if (slot == null || !slot.catalyst()) {
                continue;
            }
            boolean found = false;
            for (var option : slot.options()) {
                if (option == null || option.what() == null || option.amount() <= 0) {
                    continue;
                }
                if (networkHasCatalyst(storage, src, option.what(), option.amount())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** 本 tick 的网络可提取内容快照（按 tick 缓存：一个 tick 里可能结算很多批） */
    private appeng.api.stacks.KeyCounter catalystSnapshot;
    private long catalystSnapshotTick = Long.MIN_VALUE;

    private appeng.api.stacks.KeyCounter networkSnapshot(
            appeng.api.storage.MEStorage storage) {
        long tick = TickHandler.instance().getCurrentTick();
        if (catalystSnapshot == null || catalystSnapshotTick != tick) {
            var snap = new appeng.api.stacks.KeyCounter();
            storage.getAvailableStacks(snap);
            catalystSnapshot = snap;
            catalystSnapshotTick = tick;
        }
        return catalystSnapshot;
    }

    /**
     * 网络里有没有这个催化剂（**按数量**）。
     * <p>
     * ⚠ 2026-09-19 sensei：「匹配是不是写得太死了？NBT 也要算吧」——
     * 原来只做 `extract(精确 key, SIMULATE)`，而 AE2 的 key **把 NBT 算在内**：
     * 模具换过一次耐久、工具带点损伤、同名不同变体，就全被判成"网络里没有" ⇒ 误拒。
     * 现在两级判定：
     * <ol>
     *   <li>精确 key（含 NBT）—— 最准，先试；</li>
     *   <li>**同物品、任意 NBT 合计** —— 只对物品键做这一步（流体/化学品本就是无 NBT 语义，
     *       它们的"变体"体现在 key 自身，精确匹配即可）。</li>
     * </ol>
     * 第二级是**放宽**：宁可让"手里有同类催化剂"通过，也不要把玩家的模具/工具卡在门外。
     * 目录快照按 tick 缓存（一个 tick 里可能结算很多批，别每批都枚举一遍网络）。
     */
    private boolean networkHasCatalyst(appeng.api.storage.MEStorage storage,
                                       appeng.api.networking.security.IActionSource src,
                                       appeng.api.stacks.AEKey want, long need) {
        // ① 精确匹配（含 NBT）
        if (storage.extract(want, need, appeng.api.config.Actionable.SIMULATE, src) >= need) {
            return true;
        }
        // ② 同物品、忽略 NBT 累加
        if (want instanceof appeng.api.stacks.AEItemKey wantItem) {
            long sum = 0;
            for (var e : networkSnapshot(storage)) {
                if (e.getKey() instanceof appeng.api.stacks.AEItemKey k
                        && k.getItem() == wantItem.getItem()) {
                    sum += e.getLongValue();
                    if (sum >= need) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 自有样板的查询数据（批量包装 {@code ScaledPattern} 先拆包；非自有样板返回 null） */
    @Nullable
    private static QianJiPatternData ownDataOf(IPatternDetails pattern) {
        if (pattern instanceof com.ae2addon.crafting.ScaledPattern scaled && scaled.base() != null) {
            pattern = scaled.base();
        }
        return pattern instanceof QianJiPatternDetails qp ? qp.data() : null;
    }

    /** 缺催化剂警告的节流游标（哨兵 Long.MIN_VALUE = 还没警告过） */
    private long lastCatalystWarnTick = Long.MIN_VALUE;

    /**
     * 「缺催化剂」拒绝提示（写入聊天栏 + 日志；**节流 5 秒一条**，避免每 tick 刷屏）。
     * <p>
     * ⚠ 节流判定用哨兵安全写法：别写成 {@code tick - Long.MIN_VALUE < 100}
     * （会整数溢出成负数、永远为真 —— 今天在别的诊断上刚栽过）。
     */
    private void warnMissingCatalysts(QianJiPatternData data, String source) {
        long tick = TickHandler.instance().getCurrentTick();
        if (lastCatalystWarnTick != Long.MIN_VALUE && tick - lastCatalystWarnTick < 100L) {
            return;
        }
        lastCatalystWarnTick = tick;
        StringBuilder need = new StringBuilder();
        for (var slot : data.inputs()) {
            if (slot == null || !slot.catalyst() || slot.options().isEmpty()) {
                continue;
            }
            var first = slot.options().get(0);
            if (first == null || first.what() == null) {
                continue;
            }
            if (need.length() > 0) {
                need.append('、');
            }
            need.append(first.what().getDisplayName().getString());
        }
        ChatLog.warn(level, worldPosition,
                "缺少催化剂，无法合成（" + source + "）：网络里需要 " + need);
        AE2Addon.LOGGER.info("[ae2addon] 千机因缺少催化剂拒绝合成（{}）：需要 {}", source, need);
    }

    /** 当前催化剂档位（供 O(1) 抽样判定；无则 null） */    @Nullable
    private CatalystItem catalystKind() {
        if (level == null || catalystHandler == null) return null;
        ItemStack stack = catalystHandler.getStackInSlot(0);
        return stack.getItem() instanceof CatalystItem catalyst ? catalyst : null;
    }

    /** 批量掷骰的随机源（每 tick 从世界随机源取一次种子；见 {@code QianJiByproducts.threadRandom}） */
    private net.minecraft.util.RandomSource settleRandom() {
        long tick = TickHandler.instance().getCurrentTick();
        return QianJiByproducts.threadRandom(level.random, tick);
    }

    // ── 推送账 ──

    /** 记下「CPU 推来、还没合成」的材料 */
    private void recordPushed(@Nullable Object cluster, @Nullable KeyCounter[] inputs) {
        if (cluster == null || inputs == null) return;
        var per = pushedByCluster.computeIfAbsent(cluster, k -> new HashMap<>());
        for (var counter : inputs) {
            if (counter == null) continue;
            for (var entry : counter) {
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (key == null || amount <= 0) continue;
                per.merge(key, amount, Long::sum);
            }
        }
    }

    /** 合成成功：这批材料确实变成了产物，销账 */
    private void clearPushed(@Nullable Object cluster, @Nullable KeyCounter[] inputs) {
        if (cluster == null || inputs == null) return;
        var per = pushedByCluster.get(cluster);
        if (per == null) return;
        for (var counter : inputs) {
            if (counter == null) continue;
            for (var entry : counter) {
                per.remove(entry.getKey());
            }
        }
        if (per.isEmpty()) pushedByCluster.remove(cluster);
    }

    /** CPU 任务取消：所有千机把该簇「已推送未合成」的材料插回网络 */
    public static void returnPushedFor(Object cluster) {
        if (cluster == null) return;
        CANCELLED.put(cluster, TickHandler.instance().getCurrentTick());
        for (var be : ACTIVE) {
            if (be.isRemoved()) continue;
            try {
                be.returnPushedForCluster(cluster);
            } catch (RuntimeException ignored) {
                // 单个千机异常不影响其余
            }
        }
        purgeCancelled();
    }

    /** 新任务开始：把上一轮残留的推送账退回（材料不销毁），并清掉取消标记 */
    public static void resetPushedFor(Object cluster) {
        if (cluster == null) return;
        CANCELLED.remove(cluster);
        for (var be : ACTIVE) {
            if (be.isRemoved()) continue;
            try {
                be.returnPushedForCluster(cluster);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** 把该簇的推送账插回网络；网络拒收/断网的部分保留记账（下次取消/新任务再退，不丢料） */
    private void returnPushedForCluster(Object cluster) {
        Map<AEKey, Long> pushed = pushedByCluster.get(cluster);
        if (pushed == null || pushed.isEmpty()) return;
        var grid = getMainNode().getGrid();
        var storage = grid == null ? null : grid.getStorageService().getInventory();
        if (storage == null) return;
        var actionSource = IActionSource.ofMachine(this);
        long returned = 0;
        var it = pushed.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            long want = entry.getValue();
            if (want <= 0) {
                it.remove();
                continue;
            }
            long inserted = storage.insert(entry.getKey(), want, Actionable.MODULATE, actionSource);
            if (inserted >= want) {
                returned += inserted;
                it.remove();
            } else if (inserted > 0) {
                returned += inserted;
                entry.setValue(want - inserted); // 余量留账，下次再退
            }
        }
        if (pushed.isEmpty()) pushedByCluster.remove(cluster);
        if (returned > 0) {
            setChanged();
            if (level != null && !level.isClientSide) {
                ChatLog.ok(level, worldPosition, "任务取消：未合成的推送材料已退回网络");
            }
        }
    }

    /** 取消标记清理：只服务于「取消后同一 tick 内已排队」的 callable，不长期留存 */
    private static void purgeCancelled() {
        long now = TickHandler.instance().getCurrentTick();
        CANCELLED.entrySet().removeIf(e -> now - e.getValue() > 200);
    }

    /**
     * 瞬间合成（2026-09-15 语义升级：**不再照拄样板的产出声明**）。
     * <p>
     * 做法：把样板当「配方查询钥匙」——用真实配方（含各 mod 兼容层）自己推产出，
     * 并逐条对**概率产出**掷骰：
     * <ul>
     *   <li>样板声明的输出：**照给**（配方标为概率产也不掷骰 —— 让 CPU 的期望一定被满足，
     *       否则巨型订单会因「差 N」永不完成、产物被 CPU 囤着不进网络）</li>
     *   <li>配方里有、样板没声明的概率产出 → 掷骰，掷中就作为机器的真实副产注入</li>
     * </ul>
     * 几率模型：{@code min(100%, 配方自带几率)} —— 概率**只认配方自带值**，催化剂不改几率。
     * 催化剂改为**产出数量倍数**（每次合成掷一次：基础 1~2 / 高级 2 / 终极 3~4）：
     * **主产物与副产物都乘**（{@code 数量 × 倍数}）；配方没标几率的确定性产出照给。
     */
    private boolean instantCraft(IPatternDetails pattern, @Nullable QianJiPatternData ownData, long scale) {
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

        // 产出统一走 QianJiByproducts（与虚拟结算共用一份逻辑，勿在这里另写）
        // 「输入全是催化剂」时同样要确认催化剂在网络里（与 settle 口径一致）
        boolean catalystsPresent = ownData != null
                && QianJiByproducts.looksAllCatalyst(ownData)
                && networkHasAllCatalysts(ownData);
        var ctx = new QianJiByproducts.Context(settleRandom(), level, byproductEnabled, scale,
                this::rollCatalystMultiplier, catalystKind(), catalystsPresent);
        QianJiByproducts.Outcome outcome;
        if (ownData != null) {
            outcome = QianJiByproducts.roll(ownData, ctx);
            // 2026-09-22 v285：每次合成都打一行太吵 → 收进 debugLogs
            if (com.ae2addon.config.AE2AddonConfig.debugLogs()) {
                AE2Addon.LOGGER.info("QianJi craft(自有样板): recipe={} primary={} chanced={} batches={}",
                        ownData.recipeId(), ownData.primary().size(), ownData.chanced().size(), scale);
            }
        } else {
            var recipe = findRecipeFor(pattern); // 只用于日志（rollCompat 内部会自己再查一次，索引有缓存，开销可忽略）
            outcome = QianJiByproducts.rollCompat(pattern, this, ctx);
            if (recipe == null) {
                ChatLog.warn(level, worldPosition, "未匹配到真实配方 → 产出按样板声明直接给（不掷骰）");
            } else if (scale <= 1) {
                // 2026-09-22 v286：每次合成都往聊天栏报"配方命中…"太吵 → 去掉（要排查开 debugLogs）
            }
        }
        reportOutcome(ownData, scale, outcome);

        var produced = new ArrayList<GenericStack>();
        outcome.addInto(produced);
        if (produced.isEmpty()) {
            ChatLog.info(level, worldPosition, "本次没有任何产出（概率全未触发）");
            return true;
        }

        // ── 空间检查（模拟）──
        for (var out : produced) {
            long room = netInv.insert(out.what(), out.amount(), Actionable.SIMULATE, src);
            if (room < out.amount()) {
                ChatLog.err(level, worldPosition, "输出空间不足: " + out.what().getDisplayName()
                        + " 需要 " + out.amount() + " 可放 " + room);
                return false;
            }
        }

        // ── 真正注入 ──
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

    /** 把这次掷骰结果写进聊天栏/日志（单份时逐条；批量时只报汇总，避免刷屏） */
    private void reportOutcome(@Nullable QianJiPatternData ownData, long scale,
                               QianJiByproducts.Outcome outcome) {
        boolean verbose = scale <= 1 || craftDiagnostics();
        if (!verbose) {
            var sb = new StringBuilder();
            for (var e : outcome.byproductLog().values()) {
                if (!sb.isEmpty()) sb.append("、");
                sb.append(e.key().getDisplayName().getString())
                        .append(' ').append(e.hits()).append('/').append(e.rolls());
            }
            ChatLog.ok(level, worldPosition, "批量 ×" + scale + " 产出汇总: 主产物 "
                    + outcome.stacks().size() + " 种 · 概率产出[" + (sb.isEmpty() ? "无" : sb) + "]");
            return;
        }
        for (var e : outcome.byproductLog().values()) {
            String name = e.key().getDisplayName().getString();
            if (e.hits() <= 0) {
                ChatLog.info(level, worldPosition, (ownData != null ? "概率产出未触发: " : "副产未触发: ")
                        + name + "（概率 " + Math.round(e.chance() * 100) + "%）");
                continue;
            }
            ChatLog.ok(level, worldPosition, (ownData != null ? "概率产出: " : "副产物: ") + name
                    + " ×" + e.amount()
                    + "（概率 " + Math.round(e.chance() * 100) + "%）");
        }
    }

    /** 该配方抽出的概率副产摘要（仅日志） */
    private String describeChanced(Recipe<?> recipe) {
        var chanced = RecipeByproducts.extract(recipe, level);
        if (chanced.isEmpty()) return "无";
        var sb = new StringBuilder();
        for (var c : chanced) {
            if (!sb.isEmpty()) sb.append('、');
            sb.append(c.stack().getHoverName().getString()).append(' ')
                    .append(c.chance() > 0f ? Math.round(c.chance() * 100) + "%" : "几率未知");
        }
        return sb.toString();
    }

    /**
     * 催化剂槽 → **本份**的产出数量倍数（虚拟结算路径与推送路径共用）。
     * <p>
     * ⚠ 2026-09-15 定稿改成倍数制且乘在产出数量上（基础 50%×2 / 高级 ×2 / 终极 50%×4），
     * 由 {@link CatalystItem#rollOutputMultiplier(net.minecraft.util.RandomSource)} 掷骰；
     * 逐份调用（批量 N 份 = 掷 N 次）。无催化剂 = 1，不掷骰。
     */
    private int rollCatalystMultiplier(net.minecraft.util.RandomSource random) {
        if (level == null) return 1;
        ItemStack stack = catalystHandler.getStackInSlot(0);
        if (stack.getItem() instanceof CatalystItem catalyst) {
            return catalyst.rollOutputMultiplier(random);
        }
        return 1;
    }

    /** 合成细节日志开关（跟全局 debug 开关走；默认关，避免巨型订单刷屏） */
    private static boolean craftDiagnostics() {
        return com.ae2addon.crafting.CraftingCompat.debugLogs;
    }

    /**
     * 样板 → 真实配方。
     * <p>
     * 2026-09-15 修：原来取「第一条输入覆盖的配方」——但 GT 一个产物常有多条配方
     * （不同机器/等级），取错那一条就会让该配方的概率副产读不出来，
     * 样板声明的副产就被当成「必出」→ 100%（sensei 实测 15% 副产 5 中 5 的嫌犯之一）。
     * 现在在候选里按**能解释样板声明产出的条数**打分，取最高分。
     */
    @Nullable
    @Override
    public Recipe<?> findRecipeFor(IPatternDetails details) {
        if (level == null) return null;
        getRecipeIndex(level);
        if (recipeObjects == null) return null;

        var patternInputs = collectPatternInputs(details);
        var declared = new HashSet<Item>();
        for (var out : details.getOutputs()) {
            if (out != null && out.what() instanceof AEItemKey k) declared.add(k.getItem());
        }

        Recipe<?> best = null;
        int bestScore = -1;
        for (var out : details.getOutputs()) {
            if (out == null || out.amount() <= 0) continue;
            if (!(out.what() instanceof AEItemKey key)) continue;
            var candidates = recipeObjects.get(key.getItem());
            if (candidates == null) continue;
            for (var candidate : candidates) {
                if (!coversRecipeInputs(candidate, patternInputs)) continue;
                int score = explainedOutputs(candidate, declared);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** 这条配方能解释样板声明产出里的几个物品（自身产出 ∪ 概率产出） */
    private int explainedOutputs(Recipe<?> recipe, Set<Item> declared) {
        int score = 0;
        var produced = outputItemsOf(recipe, level);
        for (var item : declared) {
            if (produced.contains(item)) { score++; continue; }
            for (var bp : RecipeByproducts.extract(recipe, level)) {
                if (!bp.stack().isEmpty() && bp.stack().getItem() == item) { score++; break; }
            }
        }
        return score;
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

    /**
     * 样板管理终端适配（2026-09-15 sensei：千机样板槽并未向样板管理终端暴露）。
     * <p>
     * 直接读写全部样板槽（与 GUI 同源），校验沿用 {@link PatternHandler#isItemValid}；
     * 终端分组用**我们自己的物品 + 名称**，不再让 AE2 根据方块反推（千机有两个共用方块 id 的物品，
     * 反推会拿到已成型变体甚至空气）。
     */
    private final appeng.api.inventories.InternalInventory terminalPatternInv =
            new appeng.api.inventories.InternalInventory() {
                @Override
                public int size() {
                    return PATTERN_SLOTS;
                }

                @Override
                public ItemStack getStackInSlot(int slot) {
                    return patternHandler.getStackInSlot(slot);
                }

                @Override
                public void setItemDirect(int slot, ItemStack stack) {
                    // ⚠ 2026-09-21 sensei 实测「别的样板也能塞进千机样板槽」：
                    // 这个方法以前**不做任何校验**直接把栈写进槽位，而 AE2 的样板管理终端
                    // 正是走 setItemDirect/直接写入那一条（不走 isItemValid）→ 漏洞就在这里。
                    // 现在与 GUI 槽位用同一条规则（PatternHandler#isItemValid）：只放行千机样板。
                    if (!stack.isEmpty() && !patternHandler.isItemValid(slot, stack)) {
                        AE2Addon.LOGGER.info("[ae2addon][gate] 拒绝非千机样板进入千机槽 {}：{}",
                                slot, stack.getItem());
                        return;
                    }
                    patternHandler.setStackInSlot(slot, stack);
                    invalidatePatternCache();
                    setChanged();
                }

                @Override
                public int getSlotLimit(int slot) {
                    return 1;
                }

                @Override
                public boolean isItemValid(int slot, ItemStack stack) {
                    return patternHandler.isItemValid(slot, stack);
                }
            };

    @Override
    public appeng.api.networking.IGrid getGrid() {
        return getMainNode().getGrid();
    }

    /**
     * 网络里有没有「已成型」的集成型CPU（2026-09-17 sensei 门禁）。
     * <p>
     * sensei 定调：**接入集成型CPU 后千机解除所有限制**；没接入时只允许插「原版合成 / 熔炼类 / 锻造台」样板、
     * 并行数压到 1、催化剂不能插。判定走 AE2 的网格机器表（我们的 GridMixin 已保证自家 BE 能被查到）。
     * <p>
     * ⚠ 客户端一律返回 true：槽位校验在客户端也会被调用，而客户端拿不到网格；
     * 真正的门禁在服务端（{@link PatternHandler#isItemValid} / {@link CatalystHandler#isItemValid}）。
     * 界面上的两条状态读的是菜单同步数据，不直接调这里。
     */
    public boolean isIntegratedCpuOnline() {
        if (level == null || level.isClientSide()) return true;
        try {
            var grid = getGrid();
            if (grid == null) return false;
            for (var cpu : grid.getMachines(IntegratedCPUBE.class)) {
                if (cpu != null && !cpu.isRemoved() && cpu.isFormed()) return true;
            }
        } catch (Throwable ignored) {
            // 网格未就绪 → 当作不在线
        }
        return false;
    }

    /** 未接入集成型CPU 时允许插入的样板类型：原版合成 / 熔炼类 / 锻造台（2026-09-17 sensei 定） */
    public static boolean isBasicPatternType(@org.jetbrains.annotations.Nullable String machine) {
        // 白名单只有一份，放在数据类里（编码匹配也要用同一份），这里直接转发
        return com.ae2addon.recipe.QianJiPatternData.isBasicType(machine);
    }

    /**
     * 当前并行上限（2026-09-17 sensei 修正）：
     * <ul>
     *   <li>接入集成型CPU → <b>0</b>（不限，界面显示 ∞）</li>
     *   <li>没接入 → <b>网络内并行数总和</b>（见 {@link #networkParallelSum}）</li>
     * </ul>
     */
    public int parallelLimit() {
        if (isIntegratedCpuOnline()) return 0;
        return networkParallelSum(getGrid());
    }

    /**
     * 网络内并行数总和：把网格里所有在线合成 CPU 的并行处理单元数加起来
     * （每个 CPU 自身再算 1 条线程，跟 AE2 界面「并行处理单元 + 本体」的口径一致）。
     * <p>
     * 没有任何 CPU（或网格未就绪）→ 1（至少让它跑一条）。
     */
    public static int networkParallelSum(@org.jetbrains.annotations.Nullable appeng.api.networking.IGrid grid) {
        if (grid == null) return 1;
        int sum = 0;
        try {
            var service = grid.getCraftingService();
            if (service == null) return 1;
            for (var cpu : service.getCpus()) {
                if (cpu == null) continue;
                sum += Math.max(1, cpu.getCoProcessors() + 1);
                if (sum < 0) return Integer.MAX_VALUE;   // 溢出兜底（拉满的哨兵值）
            }
        } catch (Throwable ignored) {
            return 1;
        }
        return Math.max(1, sum);
    }

    @Override
    public appeng.api.inventories.InternalInventory getTerminalPatternInventory() {
        return terminalPatternInv;
    }

    @Override
    public appeng.api.implementations.blockentities.PatternContainerGroup getTerminalGroup() {
        if (!formed) return PatternContainerGroup.nothing();
        return new PatternContainerGroup(
                appeng.api.stacks.AEItemKey.of(new ItemStack(com.ae2addon.init.ModItems.QIAN_JI_ITEM.get())),
                Component.translatable("block.ae2addon.qianji"),
                java.util.List.of());
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

    /** 搜索命中一行：槽位号 + 该槽样板的数据（2026-09-20 千机自有搜索） */
    public record PatternHit(int slot, QianJiPatternData data) {}

    /**
     * 2026-09-20 sensei：**在千机里自写一套"专门对千机样板"的搜索**。
     * <p>
     * 为什么不是修 AE2 的搜索：样板管理终端的匹配只读样板 NBT 里的 {@code out} 列表
     * （AE2 自己样板的产物格式），我们补写过也没用 —— 那条路已放弃（耗了 sensei 6+ 次重启）。
     * 千机的数据（机器/配方 id/输入/主产物/概率产出）全在我们手里，所以自己做更准。
     * <p>
     * **做法：服务端扫全部 1280 槽，只回"命中的槽位 + 数据"。** 客户端不参与、不整包同步 →
     * 不违反本项目"大槽位 GUI 绝不全量同步"的铁律（GUI 那步只拿命中结果，见 v244）。
     *
     * @param term  关键词；空白 → 返回空表（**不做**"空词全命中"这种容易误伤的行为）
     * @param limit 条数上限；{@code <=0} = 不限（命令要总数，GUI 只要前 N 条）
     */
    public List<PatternHit> searchPatterns(String term, int limit) {
        var hits = new ArrayList<PatternHit>();
        String needle = term == null ? "" : term.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) return hits;
        for (int i = 0; i < patternHandler.getSlots(); i++) {
            ItemStack stack = patternHandler.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var data = QianJiPatternData.of(stack);
            if (data == null || data.isEmpty()) continue;
            if (data.searchText().contains(needle)) {
                hits.add(new PatternHit(i, data));
                if (limit > 0 && hits.size() >= limit) break;
            }
        }
        return hits;
    }

    private void invalidatePatternCache() {
        cachedPatterns = null;
        ownPatternKeyCache.clear(); // 归属查表缓存同步失效（见 ownPatternKeys）
    }

    private List<IPatternDetails> decodePatterns() {
        if (level == null) return Collections.emptyList();
        var result = new ArrayList<IPatternDetails>();
        int skipped = 0;
        for (int i = 0; i < patternHandler.getSlots(); i++) {
            ItemStack stack = patternHandler.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            // 自有样板（自有物品 或 AE2 样板 + 我们的元数据）：直接合成我们的 IPatternDetails
            var own = QianJiPatternData.of(stack);
            if (own != null) {
                if (own.isEmpty()) { skipped++; continue; }
                result.add(new QianJiPatternDetails(own, stack));
                continue;
            }
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
            // 2026-09-22 v286：这两条（"样板解码: N 个…"）每次样板槽变化都刷聊天栏 → 去掉
        }
        return result;
    }

    // ── NBT ──

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("formed", formed);
        tag.putString("facing", facing.getName());
        tag.putBoolean("byproduct", byproductEnabled);
        tag.put("patterns", patternHandler.serializeNBT());
        tag.put("catalyst", catalystHandler.serializeNBT());
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        facing = Direction.byName(tag.getString("facing"));
        if (facing == null || !facing.getAxis().isHorizontal()) facing = Direction.NORTH;
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
            // 自有样板（2026-09-15）：我们的样板物品，或 AE2 样板 + 我们的元数据
            // （ME 样板编码器编码千机配方时会挂上元数据，见 ProcessingPatternEncodingMixin）
            var own = QianJiPatternData.of(stack);
            // 2026-09-21 sensei 定调：**只认我们自己的样板物品**（ae2addon:qianji_pattern）。
            // 起因：AE2 处理样板外壳 + 我们元数据（ME 编码器产物）以前被当成"千机样板换了个壳"放行，
            // 从 GUI 看就是"处理样板也能塞进千机槽"。现在**外壳不对一律不收**（数据对不对都不看）。
            // 代价（sensei 知情并同意）：ME 编码器那条编千机样板的路等于退役，改用我们自己的终端/JEI 编码。
            if (own != null && stack.getItem() != com.ae2addon.init.ModItems.QIAN_JI_PATTERN.get()) {
                ChatLog.warn(level, worldPosition,
                        "千机只收千机样板物品（ae2addon:qianji_pattern）：AE2 样板外壳不再接收，"
                                + "请用千机终端或 JEI 千机配方页的「编码」");
                return false;
            }
            if (own != null) {
                if (own.isEmpty()) {
                    ChatLog.warn(level, worldPosition, "空千机样板（未写入配方数据），已拒收");
                    return false;
                }
                // AE2 的合成计划靠「主产物」建树：没有主产物的样板放进来也永远不会被推任务 → 直接不收
                if (own.primary().isEmpty()) {
                    ChatLog.warn(level, worldPosition, "千机样板没有主产物（只有概率产出），AE2 无法建计划，已拒收");
                    return false;
                }
                if (own.inputs().isEmpty()) {
                    ChatLog.warn(level, worldPosition, "千机样板没有输入（无原料消耗），已拒收");
                    return false;
                }
                // 2026-09-17 sensei 门禁：未接入集成型CPU → 只允许合成 / 熔炼 / 锻造台三类样板
                // （own.machine() 就是配方类型，如 minecraft:crafting_shaped / minecraft:smelting）
                if (!isIntegratedCpuOnline() && !isBasicPatternType(own.machine())) {
                    ChatLog.warn(level, worldPosition, "未接入集成型CPU：只允许插入合成 / 熔炼 / 锻造台类样板"
                            + "（本样板类型 " + own.machine() + "），已拒收");
                    return false;
                }
                return true;
            }
            // 2026-09-17 sensei 定调：**只收千机样板**（AE2 原生样板一律不收）。
            // 原因：原来这里还收 AE2 原生处理样板，于是「合成样板不支持」与「未接入集成型CPU 时
            // 只许插合成/熔炼类」两条规则互相掐架（合成样板本来就被拒，却又说合成类可插）。
            // 收敛后逻辑自洽：想要哪条配方，就去「千机·自用配方页」点「编码」或「+」生成千机样板。
            if (PatternDetailsHelper.isEncodedPattern(stack)) {
                ChatLog.warn(level, worldPosition,
                        "千机只接受千机样板：请在「千机·自用配方页」点「编码」或「+」生成（AE2 原生样板不再接收）");
            }
            return false;
        }

        /**
         * 2026-09-21 **真正的最后一道闸**：sensei 实测"处理样板还能过"、而 `insertItem` 探针**没响**
         * → 说明它根本没走 insertItem，而是**直接调 `setStackInSlot`**（Forge 这个方法不做任何校验，
         * 谁都能写）。所以校验必须放在这里 —— 非千机样板一律不写进槽。
         */
        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            if (!stack.isEmpty() && !isItemValid(slot, stack)) {
                AE2Addon.LOGGER.info("[ae2addon][gate] setStackInSlot 拒绝 {}（槽 {}）",
                        stack.getItem(), slot);
                return;
            }
            super.setStackInSlot(slot, stack);
        }

        /** 2026-09-21 诊断探针（sensei：处理样板还是可以放过）；2026-09-22 v285 把取证日志清了，校验保留 */
        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!stack.isEmpty() && !isItemValid(slot, stack)) {
                return stack;
            }
            return super.insertItem(slot, stack, simulate);
        }

        @Override
        protected void onContentsChanged(int slot) {
            invalidatePatternCache();
            // 2026-09-22 v286：原来这里每次都往聊天栏广播"样板槽更新，准备刷新网络样板列表"
            // （放/取一张样板就冒一条）→ 去掉
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
            if (!(stack.getItem() instanceof CatalystItem)) return false;
            // 2026-09-17 sensei 门禁：未接入集成型CPU 时不能插催化剂（接入后解除限制）
            return isIntegratedCpuOnline();
        }

        @Override
        protected void onContentsChanged(int slot) {
            syncPowerUsage();
            ChatLog.info(level, worldPosition, "催化剂更换: 等级 " + getCatalystLevel()
                    + "（耗电 ×" + (long) getPowerMultiplier() + "）");
            setChanged();
        }
    }

    /** 副产几率 = **配方自带值**（催化剂不改几率，只倍增副产物数量 —— 2026-09-15 sensei 定稿） */
    // （保留备注，避免后人又把「基础 10%」的乘法模型或「+2% 加法」加回来）

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
        return com.ae2addon.init.ModItems.QIAN_JI_ITEM.get();
    }
}
