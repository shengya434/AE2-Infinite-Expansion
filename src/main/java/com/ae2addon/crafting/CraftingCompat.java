package com.ae2addon.crafting;

/**
 * 与整合包/其他 mod 的兼容性状态中心（独立于 mixin 类的普通类）。
 * <p>
 * mixin 类（如 CraftingCpuLogicMixin）合并进目标类的方法必须全部 private，
 * 不能暴露 public static 方法供外部调用（Mixin 规范，2026-08-21 教训：
 * 在 mixin 里写 public static ae2addon$isTimeSliceActive() 导致
 * "contains non-private static method" 应用失败，两个整合包全崩）。
 * 需要跨类共享的状态放在这里，mixin 只写、业务类只读。
 */
public final class CraftingCompat {

    private CraftingCompat() {}

    /**
     * 时间片限流是否已注入生效（由 CraftingCpuLogicMixin 的限流重定向置位）。
     * <p>
     * 供 {@link com.ae2addon.block.IntegratedCPUBE#getAcceleratorThreads()} 查询：
     * 若未生效（mixin 注入被其他 mod 干扰 / AE2 版本不兼容），线程数回退保守值，
     * 避免无时间片保护的高线程单 tick 循环爆炸（gtlcore/gtocore 共存场景）。
     */
    public static volatile boolean timeSliceActive;
}
