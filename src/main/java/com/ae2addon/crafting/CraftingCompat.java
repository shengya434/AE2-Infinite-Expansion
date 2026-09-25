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

    /** 千机虚拟结算每 tick 份数上限（集成型CPU 在线时；无集成CPU 按 并行/20）。 */
    public static volatile int qianjiSettleCap =
            com.ae2addon.config.AE2AddonConfig.qianjiSettleCap();

    /**
     * 千机虚拟结算**每 tick 结算次数**上限（不是份数）。
     * <p>
     * ⚠ 2026-09-18 实测发现：单片 N 有上限也没用 —— 日志里 4.4 万次结算里 2.6 万次是 N=1，
     * 一秒上百次，每次都要单独往网络 insert 一次产物（`flushPendingSettle` 每次只能回收一份），
     * 巨型网络里一次 insert 就是全存储扫描 → 每 tick 上百次 = 卡死。
     * 所以必须同时限制「次数」。默认 8，可用 config `qianjiSettleCallsPerTick` 调。
     */
    public static volatile int qianjiSettleCallsPerTick =
            com.ae2addon.config.AE2AddonConfig.qianjiSettleCallsPerTick();

    /** 结算次数记账（惰性 tick 切换重置；服务端单线程安全）。 */
    private static long settleCallTick = Long.MIN_VALUE;
    private static int settleCallsUsed;

    /** 结算一次时调用；返回 false = 本 tick 结算次数已用尽（本次不做虚拟结算）。 */
    public static boolean tryConsumeSettleCall() {
        int budget = qianjiSettleCallsPerTick;
        if (budget <= 0) {
            return true; // 0 = 不限制
        }
        long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
        if (tick != settleCallTick) {
            settleCallTick = tick;
            settleCallsUsed = 0;
        }
        if (settleCallsUsed >= budget) {
            return false;
        }
        settleCallsUsed++;
        return true;
    }

    /** 本 tick 已用结算次数（诊断用） */
    public static int settleCallsUsedThisTick() {
        return settleCallsUsed;
    }

    /** 本 tick 还剩结算次数吗（不消耗；供提取阶段提前判断要不要只提 1 份） */
    public static boolean pendingSettleCallBudget() {
        int budget = qianjiSettleCallsPerTick;
        if (budget <= 0) {
            return true;
        }
        long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
        if (tick != settleCallTick) {
            return true;
        }
        return settleCallsUsed < budget;
    }

    // ── 热路径日志预算（2026-09-18）──
    // 生产环境开着 debugLogs 时，[settle] 系列每次结算刷 4 行，一秒几百行同步磁盘写
    // → 本身就能把服务端拖死。这里给热路径一个统一的每 tick 行数预算：
    // 超预算的行直接丢弃，并在该 tick 末尾补一条汇总，保证信息不丢但 I/O 可控。

    /** 热路径每 tick 允许的日志行数（0 = 不限制）。 */
    public static volatile int hotLogBudgetPerTick =
            com.ae2addon.config.AE2AddonConfig.hotLogBudgetPerTick();

    private static long hotLogTick = Long.MIN_VALUE;
    private static int hotLogUsed;
    private static int hotLogDropped;

    /**
     * 热路径日志闸门。
     *
     * @return true = 可以打印；false = 超出本 tick 预算，应丢弃
     */
    public static boolean allowHotLog() {
        int budget = hotLogBudgetPerTick;
        if (budget <= 0) {
            return true;
        }
        long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
        if (tick != hotLogTick) {
            hotLogTick = tick;
            hotLogUsed = 0;
            hotLogDropped = 0;
        }
        if (hotLogUsed >= budget) {
            hotLogDropped++;
            return false;
        }
        hotLogUsed++;
        return true;
    }

    /** 本 tick 被日志预算丢掉了多少行（0 表示没丢） */
    public static int consumeDroppedLogCount() {
        int d = hotLogDropped;
        hotLogDropped = 0;
        return d;
    }

    /** CPU 调度时间片目标（毫秒）：预算 = clamp(目标 − MSPT, 1ms, 48ms)。 */
    public static volatile int cpuTimeSliceTargetMs =
            com.ae2addon.config.AE2AddonConfig.cpuTimeSliceTargetMs();

    /** 全网格每 tick 成功 push 共享预算（0 = 不限制；2026-09-08 学 ae2lt 双预算思想）。 */
    public static volatile int dispatchBudgetPerTick =
            com.ae2addon.config.AE2AddonConfig.dispatchBudgetPerTick();

    /** 预算记账：当前 tick 与已用成功 push 数（惰性 tick 切换重置；服务端单线程安全）。 */
    private static long dispatchBudgetTick = Long.MIN_VALUE;
    private static int dispatchUsed;

    /** push 成功时调用；返回 false = 本 tick 共享预算已耗尽（拒绝本次 push）。 */
    public static boolean tryConsumeDispatch() {        int budget = dispatchBudgetPerTick;
        if (budget <= 0) {
            return true; // 不限制（旧行为）
        }
        long tick = appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
        if (tick != dispatchBudgetTick) {
            dispatchBudgetTick = tick;
            dispatchUsed = 0;
        }
        if (dispatchUsed >= budget) {
            return false;
        }
        dispatchUsed++;
        return true;
    }

    /** push 失败/未实际发生：退回本 tick 已用配额（预算按成功调用计费）。 */
    public static void refundDispatch() {
        if (dispatchUsed > 0) {
            dispatchUsed--;
        }
    }

    /** 诊断：本 tick 已用预算（0=未启用）。 */
    public static int dispatchUsedThisTick() {
        return dispatchBudgetPerTick <= 0 ? 0 : dispatchUsed;
    }

    /** 小额订单免估算阈值（下单量 ≤ 此值不展开配方树，防普通订单卡顿）。 */
    public static volatile long cheapOrderAmount =
            com.ae2addon.config.AE2AddonConfig.cheapOrderAmount();

    /**
     * 集成CPU **急停开关**（2026-09-19 sensei：AE2-VM 遇到超 Long.MAX 的请求会直接报错，
     * 需要一个"强行停止所有线程工作"的按钮）。
     * <p>
     * 打开后：我们的**虚拟 lane 派发 + 批量通道 + 虚拟结算**全部立即停工
     * （`ae2addon$budgetActive` 被压成 false），不再往网络里推/结算；
     * **不动玩家已下的订单与已提取的材料**。关掉即恢复。
     * 属于"卡死时的救命闸"，不持久化（重启即复位）。
     */
    public static volatile boolean cpuHalted;

    /** 急停开关（服务端权威） */
    public static void setCpuHalted(boolean halted) {
        cpuHalted = halted;
        com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon] 集成CPU 急停开关 → {}",
                halted ? "已停工（不再派发/结算）" : "已恢复");
    }

    /** 配置热加载时由 AE2AddonConfig 调用，同步最新值。 */
    public static void applyConfig() {
        debugLogs = com.ae2addon.config.AE2AddonConfig.debugLogs();
        batchMaxMultiplier = com.ae2addon.config.AE2AddonConfig.batchMaxMultiplier();
        sharedExpCap = com.ae2addon.config.AE2AddonConfig.sharedExpCap();
        cpuTimeSliceTargetMs = com.ae2addon.config.AE2AddonConfig.cpuTimeSliceTargetMs();
        dispatchBudgetPerTick = com.ae2addon.config.AE2AddonConfig.dispatchBudgetPerTick();
        qianjiSettleCap = com.ae2addon.config.AE2AddonConfig.qianjiSettleCap();
        qianjiSettleCallsPerTick = com.ae2addon.config.AE2AddonConfig.qianjiSettleCallsPerTick();
        hotLogBudgetPerTick = com.ae2addon.config.AE2AddonConfig.hotLogBudgetPerTick();
        cheapOrderAmount = com.ae2addon.config.AE2AddonConfig.cheapOrderAmount();
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
