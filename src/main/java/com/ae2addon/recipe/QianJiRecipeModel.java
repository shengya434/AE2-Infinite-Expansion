package com.ae2addon.recipe;

import com.ae2addon.compat.GregTechCompat;
import com.ae2addon.compat.MekanismCompat;
import com.ae2addon.util.RecipeByproducts;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归一化配方提取层（2026-09-15 sensei 定稿的"独立样板体系"第一层）。
 * <p>
 * 职责：把世界里的真实配方（GT 机器配方 / Create 加工 / 原版熔炉…）统一转成
 * {@link QianJiPatternData} —— 千机与 JEI 页共用同一份数据，不再各自反推。
 * <p>
 * 提取原则：
 * <ul>
 *   <li>输入：优先各 mod 专用提取器（GT 的 inputs 映射，保留标签语义）→ 退回标准 {@code getIngredients()}</li>
 *   <li>主产物：标准 {@code getResultItem()} + GT 的确定性产出（几率≥100%）</li>
 *   <li>概率产出：{@link RecipeByproducts} 抽取（GT chanced / Create rollable / 序列装配结果池 / Mekanism 副产）</li>
 * </ul>
 */
public final class QianJiRecipeModel {

    /** 每个输入槽最多记多少可选物品（标签展开可能上千，截断） */
    private static final int MAX_OPTIONS_PER_SLOT = 32;

    // ── 产出→配方 索引缓存（供 ME 编码器实时匹配用；按 RecipeManager 实例缓存）──
    private static java.lang.ref.WeakReference<net.minecraft.world.item.crafting.RecipeManager> cachedManager =
            new java.lang.ref.WeakReference<>(null);
    private static Map<String, List<Recipe<?>>> cachedByOutput = Map.of();

    private QianJiRecipeModel() {}

    /**
     * ME 样板编码器用：拿编码的（输入, 输出）去我们的归一化表里找对应配方。
     * <p>
     * 命中就给输出样板挂上我们的几率元数据（主产物进样板输出、概率产出进元数据）；
     * 找不到 = 不是千机配方 → 返回 null（保持 AE2 原生行为）。
     */
    @Nullable
    public static QianJiPatternData match(net.minecraft.world.item.crafting.RecipeManager manager,
                                          net.minecraft.core.RegistryAccess access,
                                          appeng.api.stacks.GenericStack[] inputs,
                                          appeng.api.stacks.GenericStack[] outputs) {
        if (manager == null || outputs == null || outputs.length == 0) return null;
        var index = indexByOutput(manager, access);

        var inputKeys = new HashSet<String>();
        if (inputs != null) {
            for (var in : inputs) {
                if (in != null && in.what() != null) inputKeys.add(coarseKey(in.what()));
            }
        }

        QianJiPatternData best = null;
        int bestScore = -1;
        for (var out : outputs) {
            if (out == null || out.what() == null) continue;
            var candidates = index.get(coarseKey(out.what()));
            if (candidates == null) continue;
            for (var recipe : candidates) {
                QianJiPatternData data = fromRecipe(recipe, access);
                if (data == null || data.primary().isEmpty() || data.inputs().isEmpty()) continue;
                boolean covered = true;
                for (var slot : data.inputs()) {
                    boolean hit = false;
                    for (var option : slot.options()) {
                        if (inputKeys.contains(coarseKey(option.what()))) { hit = true; break; }
                    }
                    if (!hit) { covered = false; break; }
                }
                if (!covered) continue;
                int score = data.primary().size() + data.chanced().size();
                if (score > bestScore) {
                    bestScore = score;
                    best = data;
                }
            }
        }
        return best;
    }

    /** 产出（物品/流体）→ 配方 索引（懒建 + 缓存） */
    private static Map<String, List<Recipe<?>>> indexByOutput(
            net.minecraft.world.item.crafting.RecipeManager manager,
            net.minecraft.core.RegistryAccess access) {
        if (cachedManager.get() == manager) return cachedByOutput;
        var map = new java.util.HashMap<String, List<Recipe<?>>>();
        for (var recipe : manager.getRecipes()) {
            for (var key : allOutputKeys(recipe, access)) {
                if (key == null) continue;
                map.computeIfAbsent(coarseKey(key), k -> new ArrayList<>()).add(recipe);
            }
        }
        cachedManager = new java.lang.ref.WeakReference<>(manager);
        cachedByOutput = map;
        return map;
    }

    /** 粗略键：物品/流体看注册名，其余（如 MEK 化学物）用 AEKeyType + 注册名（忽略 NBT），用于跨来源匹配 */
    private static String coarseKey(appeng.api.stacks.AEKey key) {
        if (key instanceof appeng.api.stacks.AEItemKey itemKey) {
            return "i:" + net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(itemKey.getItem());
        }
        if (key instanceof appeng.api.stacks.AEFluidKey fluidKey) {
            return "f:" + net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fluidKey.getFluid());
        }
        try {
            return "x:" + key.getType().getClass().getName() + "/" + key.getId();
        } catch (Throwable ignored) {
            return "x:" + key;
        }
    }

    /** 一条配方的全部产出键（物品 + 流体；标准 API + GT） */
    private static List<appeng.api.stacks.AEKey> allOutputKeys(
            Recipe<?> recipe, net.minecraft.core.RegistryAccess access) {
        var keys = new ArrayList<appeng.api.stacks.AEKey>();
        try {
            ItemStack standard = recipe.getResultItem(access);
            if (!standard.isEmpty()) keys.add(appeng.api.stacks.AEItemKey.of(standard));
        } catch (Throwable ignored) {
        }
        if (GregTechCompat.isGtRecipe(recipe)) {
            for (var stat : GregTechCompat.outputs(recipe)) {
                if (stat.stack() != null && stat.stack().what() != null) keys.add(stat.stack().what());
            }
        }
        // Mekanism：物品/流体/化学物产出都要进索引（否则 ME 编码器匹配不到）
        if (MekanismCompat.isMekanismRecipe(recipe)) {
            for (var stat : MekanismCompat.outputs(recipe)) {
                if (stat.stack() != null && stat.stack().what() != null) keys.add(stat.stack().what());
            }
        }
        return keys;
    }

    /** 世界里的「可处理配方」（有产出即可；供 JEI 页与指令列举） */
    public static List<Recipe<?>> candidates(Level level) {
        var out = new ArrayList<Recipe<?>>();
        for (var recipe : level.getRecipeManager().getRecipes()) {
            if (recipe == null) continue;
            if (outputsOf(recipe, level).isEmpty() && RecipeByproducts.extract(recipe, level).isEmpty()) continue;
            out.add(recipe);
        }
        return out;
    }

    /** 真实配方 → 我们的样板数据（**物品 + 流体**一起抽） */
    @Nullable
    public static QianJiPatternData fromRecipe(Recipe<?> recipe, Level level) {
        return level == null ? null : fromRecipe(recipe, level.registryAccess());
    }

    /** 真实配方 → 我们的样板数据（不依赖 Level：编码时用 RecipeManager.registries()） */
    @Nullable
    public static QianJiPatternData fromRecipe(Recipe<?> recipe, net.minecraft.core.RegistryAccess access) {
        if (recipe == null) return null;

        var inputs = new ArrayList<QianJiPatternData.Slot>();
        var primary = new ArrayList<QianJiPatternData.Out>();
        var chanced = new ArrayList<QianJiPatternData.Chanced>();

        if (GregTechCompat.isGtRecipe(recipe)) {
            // GT：走 inputs/outputs 映射，**物品与流体都读**（如矿石清洗机要耗水/产流体）
            for (var slot : GregTechCompat.inputSlots(recipe)) {
                inputs.add(new QianJiPatternData.Slot(slot));
            }
            for (var stat : GregTechCompat.outputs(recipe)) {
                var stack = stat.stack();
                if (stack == null || stack.amount() <= 0) continue;
                if (stat.chance() >= 1f) {
                    primary.add(new QianJiPatternData.Out(stack));
                } else {
                    chanced.add(new QianJiPatternData.Chanced(stack,
                            stat.chance() > 0f ? stat.chance() : -1f));
                }
            }
        } else if (MekanismCompat.isMekanismRecipe(recipe)) {
            // MEK：统一走「Ingredient 方法 + getOutputDefinition」——所有机器共用（含气体/流体/化学物）
            for (var slot : MekanismCompat.inputSlots(recipe)) {
                inputs.add(new QianJiPatternData.Slot(slot));
            }
            for (var stat : MekanismCompat.outputs(recipe)) {
                var stack = stat.stack();
                if (stack == null || stack.amount() <= 0) continue;
                if (stat.chance() >= 1f) {
                    primary.add(new QianJiPatternData.Out(stack));
                } else {
                    chanced.add(new QianJiPatternData.Chanced(stack,
                            stat.chance() > 0f ? stat.chance() : -1f));
                }
            }
        } else {
            // 标准路径（物品）：输入 Ingredient → 选项；主产物 = getResultItem；概率产出 = RecipeByproducts
            for (var ingredient : rawInputIngredients(recipe)) {
                var options = new ArrayList<appeng.api.stacks.GenericStack>();
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack.isEmpty()) continue;
                    options.add(appeng.api.stacks.GenericStack.fromItemStack(stack));
                    if (options.size() >= MAX_OPTIONS_PER_SLOT) break;
                }
                if (!options.isEmpty()) inputs.add(new QianJiPatternData.Slot(List.copyOf(options)));
            }
            ItemStack standard = recipe.getResultItem(access);
            if (!standard.isEmpty()) {
                primary.add(new QianJiPatternData.Out(appeng.api.stacks.GenericStack.fromItemStack(standard)));
            }
            for (var bp : RecipeByproducts.extract(recipe, access)) {
                if (bp.stack().isEmpty()) continue;
                chanced.add(new QianJiPatternData.Chanced(appeng.api.stacks.GenericStack.fromItemStack(bp.stack()),
                        bp.chance() > 0f ? bp.chance() : -1f));
            }
        }

        if (inputs.isEmpty() && primary.isEmpty() && chanced.isEmpty()) return null;

        ResourceLocation id = recipe.getId();
        return new QianJiPatternData(String.valueOf(recipe.getType()),
                id == null ? "" : id.toString(), inputs, primary, chanced);
    }

    /** 配方的输入 Ingredient 列表（GT 走 inputs 映射并保留标签语义） */
    public static List<Ingredient> rawInputIngredients(Recipe<?> recipe) {
        if (GregTechCompat.isGtRecipe(recipe)) {
            var gt = GregTechCompat.itemInputIngredients(recipe);
            if (!gt.isEmpty()) return gt;
        }
        return recipe.getIngredients();
    }

    /** 产出物品集合（标准 + GT + MEK，含流体/化学物） */
    private static Set<Object> outputsOf(Recipe<?> recipe, Level level) {
        var items = new HashSet<Object>();
        ItemStack standard = recipe.getResultItem(level.registryAccess());
        if (!standard.isEmpty()) items.add(standard.getItem());
        for (var c : GregTechCompat.outputs(recipe)) {
            if (c.stack() != null) items.add(c.stack().what());
        }
        if (MekanismCompat.isMekanismRecipe(recipe)) {
            for (var c : MekanismCompat.outputs(recipe)) {
                if (c.stack() != null) items.add(c.stack().what());
            }
        }
        return items;
    }
}
