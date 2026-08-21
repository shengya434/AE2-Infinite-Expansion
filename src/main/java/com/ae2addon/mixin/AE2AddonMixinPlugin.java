package com.ae2addon.mixin;

import com.ae2addon.AE2Addon;
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
 */
public final class AE2AddonMixinPlugin implements IMixinConfigPlugin {

    /** GT 系 AE2 fork 检测结果（类加载器探测，不初始化类） */
    private static final boolean GT_AE2 = detectGtAe2();

    private static boolean detectGtAe2() {
        try {
            Class.forName("appeng.crafting.execution.ExecutingCraftingJob", false,
                    AE2AddonMixinPlugin.class.getClassLoader());
            return false;
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn(
                    "[ae2addon] 检测到非标准 AE2（缺 ExecutingCraftingJob 类），"
                            + "深度 mixin 已禁用，集成 CPU 退化为基础功能（16 线程）");
            return true;
        }
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
            if (mixinClassName.endsWith("CraftingCpuLogicMixin")) {
                com.ae2addon.crafting.CraftingCompat.timeSliceActive = true;
            }
            return true;
        }
        // GT fork：保留 accessor（启动不崩），禁用所有深度注入 mixin
        boolean disabled = !mixinClassName.endsWith("CraftingCPUClusterAccessor");
        if (disabled) {
            AE2Addon.LOGGER.info(
                    "[ae2addon] GT AE2 兼容模式：跳过 mixin {}", mixinClassName);
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
