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
     * 热路径调试日志开关（config debugLogs 初始值，可在游戏内命令切换；
     * 配置热加载会同步覆盖）：高频日志全量打开会拖慢服务端，排查时才开启。
     */
    public static volatile boolean debugLogs = com.ae2addon.config.AE2AddonConfig.debugLogs();

    // ── 可热加载配置（config/ae2addon-common.toml 修改后自动生效，无需重启）──

    /** 批量推送翻倍上限（1×→2×→4× 指数暴涨的最大 N）。 */
    public static volatile long batchMaxMultiplier =
            com.ae2addon.config.AE2AddonConfig.batchMaxMultiplier();

    /** 批量经验共享继承上限（0 = 关闭共享，新 lane 从 1× 起步）。 */
    public static volatile long sharedExpCap =
            com.ae2addon.config.AE2AddonConfig.sharedExpCap();

    /** CPU 虚拟结算（v0.3 M1）：合成类样板节点不真实装配，直接注入产物瞬时完成。 */
    public static volatile boolean virtualSettleCraftingPatterns =
            com.ae2addon.config.AE2AddonConfig.virtualSettleCraftingPatterns();

    /** 小额订单免估算阈值（下单量 ≤ 此值不展开配方树，防普通订单卡顿）。 */
    public static volatile long cheapOrderAmount =
            com.ae2addon.config.AE2AddonConfig.cheapOrderAmount();

    /** 配置热加载时由 AE2AddonConfig 调用，同步最新值。 */
    public static void applyConfig() {
        debugLogs = com.ae2addon.config.AE2AddonConfig.debugLogs();
        batchMaxMultiplier = com.ae2addon.config.AE2AddonConfig.batchMaxMultiplier();
        sharedExpCap = com.ae2addon.config.AE2AddonConfig.sharedExpCap();
        cheapOrderAmount = com.ae2addon.config.AE2AddonConfig.cheapOrderAmount();
        virtualSettleCraftingPatterns =
                com.ae2addon.config.AE2AddonConfig.virtualSettleCraftingPatterns();
    }

    /**
     * 时间片限流是否已注入生效（由 CraftingCpuLogicMixin 的限流重定向置位）。
     * <p>
     * 供 {@link com.ae2addon.block.IntegratedCPUBE#getAcceleratorThreads()} 查询：
     * 若未生效（mixin 注入被其他 mod 干扰 / AE2 版本不兼容），线程数回退保守值，
     * 避免无时间片保护的高线程单 tick 循环爆炸（gtlcore/gtocore 共存场景）。
     */
    public static volatile boolean timeSliceActive;

    /**
     * 当前正在执行 pushPattern 推送的 CPU 簇（CraftingCPUCluster，Object 引用避免
     * 耦合）。由 CraftingCpuLogicMixin.pushBatch 在调用 provider.pushPattern 前后置位/清空；
     * 无限接口（InfiniteInterfaceBE）的 pushPattern 据此记录「哪个簇推了什么」，
     * 供任务取消时回退材料（2026-08-28 sensei 需求）。
     * <p>
     * MC 服务端单线程，普通 static 即可；非本 mod CPU（如 AE2-VM）的推送不置位
     * → 不参与回退跟踪（可接受的边界）。
     */
    public static volatile Object currentPushingCluster;
}
