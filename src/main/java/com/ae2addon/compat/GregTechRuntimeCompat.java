package com.ae2addon.compat;

import com.ae2addon.AE2Addon;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * GregTech **运行时生成配方**适配（反射，零硬依赖）。2026-09-15 新增。
 * <p>
 * 为什么需要：GT 有一批配方**根本不在 {@code RecipeManager} 里** —— 它们由机器的
 * 「自定义配方逻辑」（{@code GTRecipeType.ICustomRecipeLogic}）在运行时现算现造，
 * 例如酿造台（{@code BreweryLogic}：药水 → 药水 + 酿造材料）、
 * 电弧炉/研磨机的自动分解（{@code ArcFurnaceLogic}/{@code MaceratorLogic}）、
 * 压印机命名（{@code FormingPressLogic}）、研究站数据球复制（{@code DataStickCopyScannerLogic}）。
 * 千机原来的索引只扫 {@code RecipeManager} → 这些配方**一条都看不到**
 * （症状：JEI「千机·自用配方」页没有酿造/药水配方）。
 * <p>
 * 捞取方式（gtceu 1.20.1-7.5.3 javap 实测）：
 * <ol>
 *   <li>遍历 {@code GTRegistries.RECIPE_TYPES}（{@code GTRegistry$RL implements Iterable}）；</li>
 *   <li>只挑 {@code getCustomRecipeLogicRunners()} 非空的类型（= 有运行时配方的类型）；</li>
 *   <li>对该类型调一次 {@code buildRepresentativeRecipes()}（**每类型只调一次**，静态标记防重）；</li>
 *   <li>{@code getCategories()} × {@code getRecipesInCategory(cat)} 收集 {@code GTRecipe}，
 *       按稳定 id 去重（{@code LinkedHashMap.putIfAbsent}）。</li>
 * </ol>
 * <p>
 * ⚠ <b>与 GT 自己的调用重复吗？</b>（已实测确认，见下）
 * GT 在 {@code GTJEIPlugin.registerRecipes → GTRecipeJEICategory.registerRecipes} 里对
 * {@code RECIPE_CATEGORIES} 每个可见分类调一次同一方法。也就是**可能**与我们这次重复。但重复是**无害**的：
 * <ul>
 *   <li>{@code buildRepresentativeRecipes} 的唯一副作用是往 {@code GTRecipeType.categoryMap} 里
 *       {@code Set.add(recipe)}，而该 Set 是 {@code ObjectLinkedOpenHashSet}，
 *       且 {@code GTRecipe.equals/hashCode} **只比 id 字段**；</li>
 *   <li>5 个 custom logic 实现生成的 id 都是确定性的（{@code recipeBuilder(常量名)} + {@code withSuffix("/")}，
 *       例如 {@code gtceu:rotor_decomp/}、{@code gtceu:<potion>_<n>/}），
 *       所以重复调用产生的同名 recipe 会被 Set 按 id 去重 → **GT 自己的 JEI 页不会多出条目**；</li>
 *   <li>它**不写** {@code RecipeManager}（不影响真实配方表）也不写 {@code RecipeDB}（不影响机器查找）。</li>
 * </ul>
 * 另：{@code RecipeManagerMixin} 在每次配方重载时调 {@code beginStagingRecipes()} —— 它会
 * **clear 掉 categoryMap**，所以 {@code /reload} 后代表配方会消失（GT 自己的 JEI 页也一样）。
 * 本层因此在「按 id 查不到」时允许重建一次（{@link #findByStableId}）。
 * <p>
 * 任何一步反射失败都返回空集合 / null（= 不做额外处理），宁可漏也不误伤。
 */
public final class GregTechRuntimeCompat {

    private static final String GT_REGISTRIES = "com.gregtechceu.gtceu.api.registry.GTRegistries";

    private static final Object LOCK = new Object();
    /** 收集结果缓存（null = 还没收集过） */
    private static List<Recipe<?>> cache;
    /** 已经调过 buildRepresentativeRecipes 的类型（identity 集合；每个类型只调一次） */
    private static final Set<Object> BUILT = Collections.newSetFromMap(new IdentityHashMap<>());

    private GregTechRuntimeCompat() {}

    /** GT 是否存在（类加载探测） */
    public static boolean available() {
        try {
            Class.forName(GT_REGISTRIES);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 额外配方来源：GT 的运行时配方（一次收集 + 缓存）。供 JEI 页扫描「并入」。
     * <p>
     * 没有 GT / 反射失败 → 空列表。
     */
    public static List<Recipe<?>> extraRecipes() {
        synchronized (LOCK) {
            if (cache != null) return cache;
            var collected = collect();
            cache = List.copyOf(collected);
            return cache;
        }
    }

    /**
     * 按稳定 id 找一条运行时配方（供「编码」按钮在服务端解析用 —— 这些配方不在 {@link
     * net.minecraft.world.item.crafting.RecipeManager} 里，{@code byKey} 找不到）。
     * <p>
     * 先查缓存；未命中则**重建一次**（覆盖 {@code /reload} 清空 categoryMap 的情况）。
     */
    @Nullable
    public static Recipe<?> findByStableId(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        synchronized (LOCK) {
            if (cache != null) {
                for (var recipe : cache) {
                    if (id.equals(stableId(recipe))) return recipe;
                }
            }
            // 缓存未命中：可能是 /reload 之后 GT 清空过 categoryMap（beginStagingRecipes）→
            // 允许重建一次（buildRepresentativeRecipes 幂等，见类注释）
            BUILT.clear();
            var rebuilt = collect();
            cache = List.copyOf(rebuilt);
            for (var recipe : rebuilt) {
                if (id.equals(stableId(recipe))) return recipe;
            }
            return null;
        }
    }

    /**
     * 稳定 id：优先{@code ResourceLocation}，为空时退化成 {@code 类型/身份}。
     * <p>
     * 注：GTRecipe 的 {@code id} 字段是 public 且可为 null —— 但**带 null id 的 GTRecipe 进不了
     * categoryMap**（那个 Set 按 id 求哈希，会 NPE），所以本分支实际不可达，只是防御。
     */
    public static String stableId(@Nullable Recipe<?> recipe) {
        if (recipe == null) return "";
        try {
            var id = recipe.getId();
            if (id != null) return id.toString();
        } catch (Throwable ignored) {
        }
        try {
            return recipe.getType() + "/" + Integer.toHexString(System.identityHashCode(recipe));
        } catch (Throwable ignored) {
            return String.valueOf(recipe);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  收集
    // ══════════════════════════════════════════════════════════

    private static List<Recipe<?>> collect() {
        var out = new LinkedHashMap<String, Recipe<?>>();
        Object registry;
        try {
            registry = Class.forName(GT_REGISTRIES).getField("RECIPE_TYPES").get(null);
        } catch (Throwable ignored) {
            return List.of();
        }
        if (!(registry instanceof Iterable<?> types)) return List.of();

        int withLogic = 0;
        for (Object type : types) {
            if (type == null) continue;
            try {
                // 只处理「有自定义配方逻辑」的类型 —— 那才是 RecipeManager 里没有的配方
                var runners = customLogicRunners(type);
                if (runners.isEmpty()) continue;
                withLogic++;

                // 每个类型只调一次（GT 自己那次重复调用由 categoryMap 的 id 去重兜底）
                if (BUILT.add(type)) {
                    invokeNoArg(type, "buildRepresentativeRecipes");
                }

                for (Object category : categories(type)) {
                    for (Object recipe : recipesInCategory(type, category)) {
                        if (recipe instanceof Recipe<?> r) {
                            out.putIfAbsent(stableId(r), r);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 单个类型失败不影响其它类型
            }
        }

        if (withLogic > 0) {
            AE2Addon.LOGGER.info("[ae2addon] GT 运行时配方：{} 个带自定义逻辑的类型，收集到 {} 条（额外配方来源）",
                    withLogic, out.size());
        }
        return new ArrayList<>(out.values());
    }

    private static List<?> customLogicRunners(Object type) {
        Object runners = invokeNoArg(type, "getCustomRecipeLogicRunners");
        return runners instanceof List<?> list ? list : List.of();
    }

    private static Set<?> categories(Object type) {
        Object cats = invokeNoArg(type, "getCategories");
        return cats instanceof Set<?> set ? set : Set.of();
    }

    private static Set<?> recipesInCategory(Object type, Object category) {
        Object recipes = invoke1(type, "getRecipesInCategory", category);
        return recipes instanceof Set<?> set ? set : Set.of();
    }

    // ══════════════════════════════════════════════════════════
    //  反射小工具（GT 是运行时依赖，编译期没有它的类）
    // ══════════════════════════════════════════════════════════

    @Nullable
    private static Object invokeNoArg(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按「名字 + 1 个可接收 arg 的参数」找方法并调用（避免写死 GTRecipeCategory 的类名） */
    @Nullable
    private static Object invoke1(Object target, String name, Object arg) {
        try {
            Class<?> argClass = arg.getClass();
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(argClass)) continue;
                return method.invoke(target, arg);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

}
