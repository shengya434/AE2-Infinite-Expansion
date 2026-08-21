package com.ae2addon.mixin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 条件加载插件：检测 AE2 是否为 GT 系 fork（如 15.267.4）。
 * <p>
 * GT 系 AE2 fork 深度魔改原版：缺 ExecutingCraftingJob 类、字段/方法名不同，
 * 我们基于原版 15.4.10 写的深度 mixin（CraftingCpuLogic/CraftingService/Grid 等）
 * 在其上必然 PREINJECT 失败（ClassMetadataNotFound / NPE / cannot inject merged）。
 * 检测到 fork 时禁用这些 mixin，保游戏能进（集成 CPU 退化为 16 线程基础功能，
 * 巨型订单等高级功能不可用，但绝不崩）。
 * <p>
 * ⚠ 血泪教训（2026-08-21 crash8/crash9）：
 * 1. 禁止用 Class.forName 探测——即使 initialize=false 也会加载类，
 *    会把 gtlcore 等 mod 的 mixin 目标类提前加载 → "loaded too early" 崩。
 *    用 getResource 查 class 文件是否存在（零类加载）。
 * 2. 禁止引用 AE2Addon 主类（LOGGER 等）——会触发 @Mod 类初始化
 *    （注册网络通道），在 mixin 早期阶段加载大量 MC 类，连累 modernfix 等
 *    其他 mod 的 mixin 全部崩。用独立的 LogManager.getLogger。
 */
public final class AE2AddonMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("ae2addon-mixin");

    /** GT 系 AE2 fork 检测结果（资源探测，不加载任何类） */
    private static final boolean GT_AE2 = detectGtAe2();

    private static boolean detectGtAe2() {
        boolean hasJobClass = AE2AddonMixinPlugin.class.getClassLoader()
                .getResource("appeng/crafting/execution/ExecutingCraftingJob.class") != null;
        if (!hasJobClass) {
            LOGGER.warn("[ae2addon] 检测到非标准 AE2（缺 ExecutingCraftingJob 类），"
                    + "深度 mixin 已禁用，集成 CPU 退化为基础功能（16 线程）");
        }
        return !hasJobClass;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!GT_AE2) {
            // 原版/兼容 AE2：CraftingCpuLogicMixin 将被应用（priority 1200 先于
            // gtlcore 等，注入基于原始方法，限流/批量推送全部生效）→ 立即置位，
            // 与运行状态无关（修复 CPU 空闲时线程数被压成 16 的时序 bug，
            // 2026-08-21 sensei 截图：合成CPU 两个处理器各显示 16）。
            // CraftingCompat 是无副作用普通类（仅 volatile boolean），引用安全。
            if (mixinClassName.endsWith("CraftingCpuLogicMixin")) {
                com.ae2addon.crafting.CraftingCompat.timeSliceActive = true;
            }
            return true;
        }
        // GT fork：保留 accessor + GridMixin（CPU 识别必需，目标方法稳定），
        // 禁用其余深度注入 mixin（它们基于原版 15.4.10 的类/字段，GT fork 必崩）。
        // GridMixin 必须保留：AE2 的 getMachines 按精确运行时类匹配，
        // 没有它集成 CPU 子类方块无法被识别为 CPU（sensei 截图：合成CPU 列表空）。
        boolean isAccessor = mixinClassName.endsWith("CraftingCPUClusterAccessor");
        boolean isGridMixin = mixinClassName.endsWith("GridMixin");
        boolean disabled = !(isAccessor || isGridMixin);
        if (disabled) {
            LOGGER.info("[ae2addon] GT AE2 兼容模式：跳过 mixin {}", mixinClassName);
        }
        return !disabled;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
