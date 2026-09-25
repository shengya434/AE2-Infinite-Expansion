package com.ae2addon.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * AE2Addon 配置文件（config/ae2addon-common.toml）。
 * <p>
 * 2026-08-27 21:17 sensei 要求：并行上限可配（0=无限制），顺带把其他
 * 调参点全部暴露。所有配置项在游戏内改完后需重启生效（静态缓存）。
 */
public final class AE2AddonConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ── 批次/并行 ──

    /** 最大同时执行批次（0 = 无限制全发）。默认 0（无限制）。 */
    public static final ForgeConfigSpec.IntValue MAX_CONCURRENT = BUILDER
            .comment("最大同时执行的巨型订单批次数量（0 = 无限制全发，所有批次一次性并行）",
                    "Max concurrent mega-order batches (0 = unlimited, all batches at once)")
            .defineInRange("maxConcurrent", 0, 0, Integer.MAX_VALUE);

    /** 常驻空闲虚拟 lane 数（主簇忙时维持的空闲线程池大小）。 */
    public static final ForgeConfigSpec.IntValue IDLE_LANE_TARGET = BUILDER
            .comment("主簇忙时常驻的空闲虚拟 lane 数（量子分裂线程池）",
                    "Idle virtual lane pool size when main cluster is busy")
            .defineInRange("idleLaneTarget", 16, 1, 4096);

    /** 单个巨型订单最大批次数（防呆：超出拒绝订单）。 */
    public static final ForgeConfigSpec.IntValue MAX_BATCH_COUNT = BUILDER
            .comment("单个巨型订单的最大批次数（防呆，超出则拒绝订单）",
                    "Max batches per mega-order (safety, reject beyond)")
            .defineInRange("maxBatchCount", 1_000_000, 2, 10_000_000);

    // ── 批量推送 ──

    /**
     * 批量推送翻倍上限（1×→2×→4×… 指数暴涨的最大 N）。
     * <p>
     * ⚠ 2026-09-18 sensei：千机样板改走**虚拟结算**（每 tick 结算 round(并行/20) 份）后，
     * 「批量通道」计划退役 —— 本项属于批量通道，**暂时仍被引用**，退役后即失效。
     * 新代码不要再依赖它。
     */
    public static final ForgeConfigSpec.LongValue BATCH_MAX_MULTIPLIER = BUILDER
            .comment("批量推送翻倍上限（N× 指数暴涨的最大值；999999999999999999 ≈ Long.MAX）",
                    "Max batch multiplier for exponential push growth")
            .defineInRange("batchMaxMultiplier", Long.MAX_VALUE, 1L, Long.MAX_VALUE);

    /**
     * 全网格每 tick 成功 push 次数共享预算（0=不限制；防巨型订单独占服务端 tick）。
     * <p>
     * ⚠ 2026-09-18：属于**批量通道 / 推送路径**的限流。千机样板改虚拟结算后不再走这条，
     * 退役批量通道时会一并处理；在此之前仍然生效。
     */
    public static final ForgeConfigSpec.IntValue DISPATCH_BUDGET_PER_TICK = BUILDER
            .comment("全网格每 tick 成功 push 次数共享预算（0=不限制=旧行为；",
                    "与时间片纳秒预算正交，按成功 push 调用计数，批量大 N 一次推送不受影响；",
                    "防多个巨型订单同 tick 抢占把服务端拖垮）",
                    "Grid-wide successful-push budget per tick (0=unlimited; ",
                    "orthogonal to time-slice ns budget; counts push calls not items)")
            .defineInRange("dispatchBudgetPerTick", 20_000, 0, 10_000_000);

    /**
     * 千机虚拟结算的每 tick 份数硬上限（**集成型CPU 在线时**生效）。
     * <p>
     * ⚠ 2026-09-18 sensei 反馈「下大单严重卡顿、集成型CPU 在线时更明显」：
     * 原来的口径是「有集成型CPU 就不限」，于是一个 tick 里要掷骰结算整个任务量
     * （几十万~上千万份）→ 服务端单 tick 卡死。改成**任何情况下都有每 tick 上限**：
     * 无集成型CPU = round(网络并行数 / 20)；有集成型CPU = 本项（默认 4096）。
     * <p>
     * 逐份掷骰是概率正确性的要求（不做期望值取巧），所以这里只能靠"分片"摊到多个 tick。
     * 调大 = 更快但更卡；调小 = 更平滑但耗时更长。
     */
    public static final ForgeConfigSpec.IntValue QIANJI_SETTLE_CAP = BUILDER
            .comment("千机虚拟结算每 tick 份数上限（集成型CPU 在线时；无集成CPU 时按 网络并行数/20）",
                    "QianJi virtual-settlement batches per tick (integrated CPU online; ",
                    "without it, the cap is round(networkParallelSum / 20))")
            .defineInRange("qianjiSettleCap", 4096, 1, 10_000_000);

    /**
     * 千机虚拟结算**每 tick 结算次数**上限（默认 8；0 = 不限制）。
     * <p>
     * ⚠ 2026-09-18 实测（sensei 日志）：单片 N 有上限也挡不住卡顿 ——
     * 4.4 万次结算里 2.6 万次是 N=1，一秒上百次，每次都单独往网络 insert 一次产物
     * （回收路径每次只处理一份），巨型网络里一次 insert = 全存储扫描 → 每 tick 上百次 = 卡死。
     * 所以必须同时限制「次数」。调大 = 更快但每 tick 干的活更多。
     * <p>
     * ⚠ 配置项必须在 {@code SPEC = BUILDER.build()} **之前**定义 ——
     * 定义在之后的话，别的类静态初始化时读到的是 null，
     * 抛「Cannot get config value before spec is built」直接把游戏崩在加载阶段（2026-09-18 实际踩过）。
     */
    public static final ForgeConfigSpec.IntValue QIANJI_SETTLE_CALLS_PER_TICK = BUILDER
            .comment("千机虚拟结算每 tick 结算次数上限（0 = 不限制）",
                    "QianJi virtual-settlement calls per tick (0 = unlimited)")
            .defineInRange("qianjiSettleCallsPerTick", 512, 0, 100_000);

    /**
     * 热路径每 tick 日志行数预算（默认 200；0 = 不限制）。
     * <p>
     * ⚠ 2026-09-18：开着 debugLogs 时结算路径每次刷 4 行，一秒几百行同步磁盘写，
     * 本身就能把服务端拖死。超预算的行会被丢弃（信息不丢：有汇总）。
     */
    public static final ForgeConfigSpec.IntValue HOT_LOG_BUDGET_PER_TICK = BUILDER
            .comment("热路径每 tick 日志行数预算（0 = 不限制；防 debugLogs 把服务端写死）",
                    "Hot-path log lines per tick (0 = unlimited)")
            .defineInRange("hotLogBudgetPerTick", 200, 0, 1_000_000);

    /** 批量经验共享继承上限（新 lane 起步 N，防单次巨量 push）。 */
    public static final ForgeConfigSpec.LongValue SHARED_EXP_CAP = BUILDER
            .comment("批量经验共享继承上限（新 lane/新任务从该 N 起步，0=不共享经验）",
                    "**巨型订单提速关键**：拆成 N 批时，每批从该 N 起步就不用每批重新翻倍（原来默认 65536，",
                    "500 批订单 = 500 次重新爬坡 → 这就是“9.2E 要几分钟”。千机这类无上限接收方可以直接拉高",
                    "（它会按 taskRemaining 夹住，不会超发）；保守接收方（真实机器）拉太高会因拒收而锁 1×",
                    "Shared batch-experience inheritance cap (0 = disable sharing)")
            .defineInRange("sharedExpCap", 1L << 50, 0L, Long.MAX_VALUE);

    /** CPU 调度时间片目标（毫秒）：巨型订单期间允许 CPU 每 tick 多嘸一点。 */
    public static final ForgeConfigSpec.IntValue CPU_TIME_SLICE_TARGET_MS = BUILDER
            .comment("CPU 调度时间片目标（毫秒）：预算 = clamp(目标 − 服务器MSPT, 1ms, 48ms)",
                    "调大 → 巨型订单更快，代价是那几秒 MSPT 变高（5=极度保守，45=默认，100=激进）",
                    "Target MSPT headroom for the crafting-CPU time slice")
            .defineInRange("cpuTimeSliceTargetMs", 45, 1, 500);

    // ── 模拟拦截 ──

    /** 小额订单免估算阈值（≤ 该值直接走原版模拟，不展开配方树）。 */
    public static final ForgeConfigSpec.LongValue CHEAP_ORDER_AMOUNT = BUILDER
            .comment("小额订单免估算阈值（下单量 ≤ 此值不展开配方树，防普通订单卡顿）",
                    "Small orders skip recipe-tree expansion below this amount")
            .defineInRange("cheapOrderAmount", 1_000_000L, 1L, Long.MAX_VALUE);

    // ── 显示数值（面板/终端显示用，不影响真实功能）──

    /** 无限物品的显示字节数（纯外观） */
    public static final ForgeConfigSpec.LongValue CELL_DISPLAY_BYTES = BUILDER
            .comment("无限元件在面板中显示的字节数（真实存储无限，这只是显示值）",
                    "Display bytes for infinite cells in AE2 terminals (cosmetic)")
            .defineInRange("cellDisplayBytes", 300_000_000L, 1L, Long.MAX_VALUE);

    /** 无限物品的真实数量（提取/显示上限）。 */
    public static final ForgeConfigSpec.LongValue INFINITE_ITEM_AMOUNT = BUILDER
            .comment("无限物品的真实数量（元件内「无限」物品的提取/显示上限，",
                    "例如 9223372036854775807=Long.MAX；调小可限制每次提取量）",
                    "Real amount behind infinite items in cells (extract/display cap)")
            .defineInRange("infiniteItemAmount", Long.MAX_VALUE, 1L, Long.MAX_VALUE);

    /** 集成 CPU 的显示字节数（合成 CPU 终端显示值；真实存储无限）。 */
    public static final ForgeConfigSpec.LongValue CPU_DISPLAY_BYTES = BUILDER
            .comment("集成 CPU 在合成终端显示的字节数（真实无限，这只是显示值）",
                    "Display bytes for integrated CPU (cosmetic, real storage is infinite)")
            .defineInRange("cpuDisplayBytes", Long.MAX_VALUE, 1L, Long.MAX_VALUE);

    /** 集成 CPU 的显示并行线程数（有并行处理器时；真实执行由时间片限流接管）。 */
    public static final ForgeConfigSpec.IntValue CPU_DISPLAY_THREADS = BUILDER
            .comment("集成 CPU 显示的并行线程数（0 = 拉满 Integer.MAX_VALUE；真实执行由时间片限流接管）",
                    "Display thread count for integrated CPU (0 = max out)")
            .defineInRange("cpuDisplayThreads", 0, 0, 100_000_000);

    /** 存储显示文本覆盖（非空时直接显示该文本，如「无限」「MAX」；空 = 数值/∞ 逻辑）。 */
    public static final ForgeConfigSpec.ConfigValue<String> CPU_STORAGE_TEXT = BUILDER
            .comment("集成 CPU 存储显示文本覆盖（非空时直接显示，如 无限/MAX/∞；留空=数值或∞）",
                    "Storage display text override for integrated CPU (non-empty wins; empty = number/∞)")
            .define("cpuStorageText", "");

    /** 并行显示文本覆盖（非空时直接显示该文本，如「拉满」「MAX」；空 = 数值/∞ 逻辑）。 */
    public static final ForgeConfigSpec.ConfigValue<String> CPU_THREADS_TEXT = BUILDER
            .comment("集成 CPU 并行显示文本覆盖（非空时直接显示，如 拉满/MAX/∞；留空=数值或∞）",
                    "Parallel display text override for integrated CPU (non-empty wins; empty = number/∞)")
            .define("cpuThreadsText", "");

    // ── ME接口（无限级） ──

    /** 每个物品的蓄水池目标保有量（机器消耗后自动从网络补足；0=不自动补货只收CPU推送）。 */
    public static final ForgeConfigSpec.LongValue FEEDER_STOCK_TARGET = BUILDER
            .comment("ME接口(无限级)每物品的蓄水池目标保有量（机器消耗后自动从网络补足，无单tick上限；0=关闭自动补货）",
                    "Infinite Interface reservoir target per item (auto-restock from network, no per-tick cap; 0=off)")
            .defineInRange("feederStockTarget", 1_000_000L, 0L, Long.MAX_VALUE);

    /** 每 tick 喂给相邻机器的 insertItem 尝试次数上限（防单 tick 卡顿）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_FEED_BUDGET = BUILDER
            .comment("ME接口(无限级)每tick喂给相邻机器的尝试次数上限（防单tick卡顿；发送速度主旋钮）",
                    "Infinite Interface max feed attempts per tick (anti-lag; main send-speed knob)")
            .defineInRange("feederFeedBudget", 4096, 1, 1_000_000);

    /** 每次 insertItem 尝试的最大堆叠数（默认 64=原版物品堆叠上限；大堆叠机器可调更大）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_FEED_STACK = BUILDER
            .comment("ME接口(无限级)每次尝试的最大堆叠数（默认64=原版堆叠上限；",
                    "GT/ExtendedAE 等大槽位机器可调大，一次塞更多）",
                    "Infinite Interface max stack per feed attempt (64=vanilla cap; raise for big-slot machines)")
            .defineInRange("feederFeedStack", 64, 1, Integer.MAX_VALUE);

    /** 补货间隔（tick；1 = 每 tick 补货 = 网络拉取最快）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_RESTOCK_INTERVAL = BUILDER
            .comment("ME接口(无限级)自动补货间隔 tick（1=每tick补货最快；调大省网络操作）",
                    "Infinite Interface restock interval ticks (1 = fastest)")
            .defineInRange("feederRestockInterval", 4, 1, 200);

    // ── ME接口（无限级）主动抽取 ──

    /** 主动抽取间隔（tick；1=每tick抽 = 最快）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_EXTRACT_INTERVAL = BUILDER
            .comment("ME接口(无限级)主动抽取间隔 tick（1=每tick抽最快；默认4）",
                    "Infinite Interface active-extract interval ticks (1 = fastest)")
            .defineInRange("feederExtractInterval", 4, 1, 10000);

    /** 主动抽取每次物品数量（默认64；大槽机器可调大提速）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_EXTRACT_STACK = BUILDER
            .comment("ME接口(无限级)主动抽取每次物品数量（默认64=原版堆叠；调大提速）",
                    "Infinite Interface items per extract (64=vanilla cap; raise for speed)")
            .defineInRange("feederExtractStack", 64, 1, Integer.MAX_VALUE);

    /** 主动抽取每次流体 mB（默认1000=1桶）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_EXTRACT_FLUID = BUILDER
            .comment("ME接口(无限级)主动抽取每次流体量 mB（默认1000=1桶）",
                    "Infinite Interface fluid mB per extract (1000=1 bucket)")
            .defineInRange("feederExtractFluid", 1000, 1, Integer.MAX_VALUE);

    /** 主动抽取每次气体量（默认1000）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_EXTRACT_GAS = BUILDER
            .comment("ME接口(无限级)主动抽取每次气体量（默认1000）",
                    "Infinite Interface gas units per extract (default 1000)")
            .defineInRange("feederExtractGas", 1000, 1, Integer.MAX_VALUE);

    /** 主动抽取循环累计上限（0=关闭循环；0=off, loop accumulate; 2026-09-03）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_EXTRACT_LOOP_CAP = BUILDER
            .comment("Infinite Interface active-extract loop limit",
                    "feederExtractLoopLimit")
            .defineInRange("feederExtractLoopLimit", 1_000_000, 0, 2_000_000_000);

    // ── ME接口（无限级）感应卡供电 ──

    /** 感应卡单轮供电 FE 上限（1~int.MAX；默认 1 亿；插1张速度卡×16，插2张每轮灌满）。 */
    public static final ForgeConfigSpec.LongValue FEEDER_POWER_FE_CAP = BUILDER
            .comment("ME接口(无限级)感应卡单轮供电 FE 上限（1~2147483647；",
                    "默认 100000000=1亿。Forge 能量槽是 int，单轮灌入上限即机器缺口",
                    "（≤21.4亿），设再大也等效。速度卡：0张=此值，1张=此值×16(≤21.4亿)，",
                    "2张=无上限(Long.MAX哨兵)每轮灌满缺口。多 tick 总吞吐由轮数×单轮叠加）",
                    "Infinite Interface induction-card FE cap per pass (1~int.MAX;",
                    "0 speed cards = this; 1 = ×16; 2 = unlimited Long.MAX sentinel)")
            .defineInRange("feederPowerFeCap", 100_000_000L, 1L, Integer.MAX_VALUE);

    /** 感应卡每 tick 供电轮数（每轮上限 FE_CAP；1=原行为，N=N×FE_CAP FE/t 上限）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_POWER_PASSES = BUILDER
            .comment("ME接口(无限级)感应卡每tick供电轮数（每轮上限见 feederPowerFeCap，",
                    "N轮=上限N×FE/t；默认1=单轮。机器收得慢时调大无效，",
                    "瓶颈在机器接收速率时请先看机器侧）",
                    "Infinite Interface induction-card power passes per tick",
                    "(each pass capped at feederPowerFeCap; N passes = N× cap ceiling)")
            .defineInRange("feederPowerPassesPerTick", 1, 1, 1024);

    // ── 无线千机·样板终端 ──

    /**
     * 无线千机·样板终端的**能源上限**（AE，2026-09-22 v281 sensei 要求）。
     * <p>
     * 基数本来是 AE2 的 config（{@code config/ae2/common.json} 里的 {@code wirelessTerminal}，
     * 默认 1600000），我们的物品在 {@code getAEMaxPower} 里取 {@code max(AE2 的值, 这个值)}。
     * <b>0 = 完全跟随 AE2</b>（等于没改）。
     * <p>
     * 充电速率会**按同一比例放大**，所以容量变大不会让"充满"变得更慢。
     * ⚠ 只作用于**我们自己的无线终端物品**；放进 AE2WTLib 通用终端里当一种状态时，
     * 电量属于通用终端物品本身（那边是 AE2WTLib 的容量）。
     */
    public static final ForgeConfigSpec.LongValue WIRELESS_TERMINAL_CAPACITY = BUILDER
            .comment("无线千机·样板终端的能源上限（AE；0 = 跟随 AE2 的 wirelessTerminal）",
                    "Energy capacity (AE) for the QianJi wireless terminal; 0 = follow AE2's wirelessTerminal")
            .defineInRange("wirelessTerminalCapacity", 8_000_000L, 0L, Long.MAX_VALUE);

    // ── 调试 ──

    /** 热路径调试日志（submitJob/批次进度/批量推送等高频日志）。 */
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGS = BUILDER
            .comment("调试日志开关（热路径高频日志，排查问题时开启；开启会略微掉刻）",
                    "Debug logs for hot paths (submitJob/batch/push), may lag slightly")
            .define("debugLogs", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private AE2AddonConfig() {
    }

    /** 在 mod 构造时调用注册。 */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
        // 运行时诊断：确认 loopCap 已注册且可读（排查 config 界面缺失选项问题，2026-09-03）
        try {
            com.ae2addon.AE2Addon.LOGGER.info("[ae2addon][config] loopCap 注册诊断: get()={} 默认={}",
                    FEEDER_EXTRACT_LOOP_CAP.get(), FEEDER_EXTRACT_LOOP_CAP.getDefault());
        } catch (Throwable t) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][config] loopCap 诊断失败", t);
        }
    }

    /**
     * 配置加载/热重载事件（ModConfigEvent.Loading / Reloading）：
     * 把最新值同步到各使用类，<b>改配置无需重启</b>（2026-08-27 21:26 sensei 要求）。
     */
    public static void onConfigEvent(net.minecraftforge.fml.event.config.ModConfigEvent event) {
        if (event.getConfig() != null && event.getConfig().getSpec() == SPEC) {
            apply();
        }
    }

    /** 把当前配置值写入各使用点（热加载生效）。 */
    public static void apply() {
        com.ae2addon.crafting.CraftingCompat.applyConfig();
        com.ae2addon.crafting.BatchedCraftingOrder.applyConfig();
        com.ae2addon.block.IntegratedCPUBE.applyConfig();
        com.ae2addon.block.InfiniteInterfaceBE.applyConfig();
        // 无限物品真实数量（两处引用同步）
        com.ae2addon.cell.UnlimitedCellInventory.INFINITE_BYTES = cellDisplayBytes();
        com.ae2addon.data.CellDataSavedData.CellData.INFINITE_BYTES = cellDisplayBytes();
        com.ae2addon.cell.UnlimitedCellInventory.INFINITE = infiniteItemAmount();
    }

    // ── 读取（静态缓存，重启生效）──

    /** 最大同时执行批次（0 → Integer.MAX_VALUE 无限制）。 */
    public static int maxConcurrent() {
        int v = MAX_CONCURRENT.get();
        return v <= 0 ? Integer.MAX_VALUE : v;
    }

    public static int idleLaneTarget() {
        return Math.max(1, IDLE_LANE_TARGET.get());
    }

    public static int maxBatchCount() {
        return Math.max(2, MAX_BATCH_COUNT.get());
    }

    public static long batchMaxMultiplier() {
        return Math.max(1L, BATCH_MAX_MULTIPLIER.get());
    }

    /** 全网格每 tick 成功 push 次数共享预算（0 = 不限制）。 */
    public static int dispatchBudgetPerTick() {
        return Math.max(0, DISPATCH_BUDGET_PER_TICK.get());
    }

    /** 共享经验继承上限（0 = 关闭共享，新 lane 从 1× 起步）。 */
    public static long sharedExpCap() {
        return Math.max(0L, SHARED_EXP_CAP.get());
    }

    public static int qianjiSettleCap() {
        return Math.max(1, QIANJI_SETTLE_CAP.get());
    }

    public static int qianjiSettleCallsPerTick() {
        return Math.max(0, QIANJI_SETTLE_CALLS_PER_TICK.get());
    }

    public static int hotLogBudgetPerTick() {
        return Math.max(0, HOT_LOG_BUDGET_PER_TICK.get());
    }

    /** CPU 调度时间片目标（毫秒；巨型订单提速旋钮）。 */
    public static int cpuTimeSliceTargetMs() {
        return Math.max(1, CPU_TIME_SLICE_TARGET_MS.get());
    }

    public static long cheapOrderAmount() {
        return Math.max(1L, CHEAP_ORDER_AMOUNT.get());
    }

    public static boolean debugLogs() {
        return DEBUG_LOGS.get();
    }

    public static long cellDisplayBytes() {
        return Math.max(1L, CELL_DISPLAY_BYTES.get());
    }

    /** 无限物品真实数量（提取/显示上限）。 */
    public static long infiniteItemAmount() {
        return Math.max(1L, INFINITE_ITEM_AMOUNT.get());
    }

    public static long cpuDisplayBytes() {
        return Math.max(1L, CPU_DISPLAY_BYTES.get());
    }

    /**
     * 「无限并行」的显示哨兵值。
     * <p>
     * ⚠ 2026-09-25 修「重新成型后 AE2 合成 CPU 列表里并行数不显示」：
     * AE2 的并行数是 {@code CraftingCPUCluster.accelerator}（**int**），
     * 由 {@code addBlockEntity} 里的 {@code accelerator += be.getAcceleratorThreads()} 累加。
     * 我们按 {@code idleLaneTarget} 建多条 lane，每条都把自己那个控制器 addBlockEntity 一次
     * → 旧哨兵 {@code Integer.MAX_VALUE-1} 只要累加 ≥2 次就**溢出成负数**，
     * AE2 列表于是只显示存储字节、并行数整项消失。
     * <p>
     * 取 {@code (Integer.MAX_VALUE-1)/16}：累加 16 条 lane 也不会溢出，
     * 数值上仍是"天文数字"，显示层再把它渲染成 ∞。
     */
    public static final int SAFE_DISPLAY_THREADS = (Integer.MAX_VALUE - 1) / 16;

    /** @return config 里是否配的是"拉满/无限"（0 或负数） */
    public static boolean cpuDisplayThreadsIsInfinite() {
        return CPU_DISPLAY_THREADS.get() <= 0;
    }

    /** 显示线程数（0 = {@link #SAFE_DISPLAY_THREADS}；防多条 lane 累加时 int 溢出）。 */
    public static int cpuDisplayThreads() {
        int v = CPU_DISPLAY_THREADS.get();
        return v <= 0 ? SAFE_DISPLAY_THREADS : v;
    }

    /** 存储显示文本覆盖（去空格；空 = 未设置）。 */
    public static String cpuStorageText() {
        return CPU_STORAGE_TEXT.get() == null ? "" : CPU_STORAGE_TEXT.get().trim();
    }

    /** 并行显示文本覆盖（去空格；空 = 未设置）。 */
    public static String cpuThreadsText() {
        return CPU_THREADS_TEXT.get() == null ? "" : CPU_THREADS_TEXT.get().trim();
    }

    /** ME接口(无限级)：每物品蓄水池目标保有量（0=关闭自动补货）。 */
    public static long feederStockTarget() {
        return Math.max(0L, FEEDER_STOCK_TARGET.get());
    }

    /** ME接口(无限级)：每 tick 喂出尝试次数上限。 */
    public static int feederFeedBudget() {
        return Math.max(1, FEEDER_FEED_BUDGET.get());
    }

    /** ME接口(无限级)：每次尝试的最大堆叠数（大堆叠机器可超 64）。 */
    public static int feederFeedStack() {
        return Math.max(1, FEEDER_FEED_STACK.get());
    }

    /** ME接口(无限级)：补货间隔 tick。 */
    public static int feederExtractInterval() {
        return FEEDER_EXTRACT_INTERVAL.get();
    }

    public static int feederExtractStack() {
        return FEEDER_EXTRACT_STACK.get();
    }

    /** 主动抽取循环累计上限（0 = 关闭循环）。 */
    public static int feederExtractLoopCap() {
        return Math.max(0, FEEDER_EXTRACT_LOOP_CAP.get());
    }

    public static int feederExtractFluid() {
        return FEEDER_EXTRACT_FLUID.get();
    }

    public static int feederExtractGas() {
        return FEEDER_EXTRACT_GAS.get();
    }

    public static int feederRestockInterval() {
        return Math.max(1, FEEDER_RESTOCK_INTERVAL.get());
    }

    /** 感应卡单轮供电 FE 上限（默认 1 亿；每 tick 总上限=此值×轮数）。 */
    public static long feederPowerFeCap() {
        return Math.max(1L, FEEDER_POWER_FE_CAP.get());
    }

    /**
     * 感应卡有效供电上限（FE/轮）：按速度卡数量倍率。
     * 0 张 = config 原值；1 张 = ×16（钳 int.MAX 防溢出）；≥2 张 = 无上限
     * （Long.MAX_VALUE 哨兵：单轮灌满机器缺口，缺口本身 ≤ int.MAX 故安全）。
     */
    public static long feederPowerEffectiveFeCap(int speedCards) {
        if (speedCards >= 2) {
            return Long.MAX_VALUE; // 无上限哨兵（灌满缺口即止）
        }
        long cap = feederPowerFeCap();
        if (speedCards == 1) {
            // long 域乘法防溢出，再钳到 int.MAX（单轮超过缺口无意义）
            return Math.min((long) Integer.MAX_VALUE, cap * 16L);
        }
        return cap;
    }

    /** 感应卡每 tick 供电轮数（每轮上限 feederPowerFeCap）。 */
    public static int feederPowerPasses() {
        return Math.max(1, FEEDER_POWER_PASSES.get());
    }

    /** 无线千机·样板终端的能源上限（AE）；0 = 跟随 AE2 自己的 wirelessTerminal。 */
    public static long wirelessTerminalCapacity() {
        try {
            return Math.max(0L, WIRELESS_TERMINAL_CAPACITY.get());
        } catch (Throwable t) {
            return 0L;   // 配置还没加载时按"跟随 AE2"处理，绝不让物品取值抛异常
        }
    }

    /** AE2 那一档无线终端容量（拿不到就给默认 1600000，与 AE2 的默认 config 一致）。 */
    public static double ae2WirelessTerminalCapacity() {
        try {
            return Math.max(1.0, appeng.core.AEConfig.instance().getWirelessTerminalBattery().getAsDouble());
        } catch (Throwable t) {
            return 1_600_000.0;
        }
    }
}
