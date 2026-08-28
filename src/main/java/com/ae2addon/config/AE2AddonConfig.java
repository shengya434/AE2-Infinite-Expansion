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

    /** 批量推送翻倍上限（1×→2×→4×… 指数暴涨的最大 N）。 */
    public static final ForgeConfigSpec.LongValue BATCH_MAX_MULTIPLIER = BUILDER
            .comment("批量推送翻倍上限（N× 指数暴涨的最大值；999999999999999999 ≈ Long.MAX）",
                    "Max batch multiplier for exponential push growth")
            .defineInRange("batchMaxMultiplier", Long.MAX_VALUE, 1L, Long.MAX_VALUE);

    /** 批量经验共享继承上限（新 lane 起步 N，防单次巨量 push）。 */
    public static final ForgeConfigSpec.LongValue SHARED_EXP_CAP = BUILDER
            .comment("批量经验共享继承上限（新 lane 从该 N 起步，0=不共享经验）",
                    "Shared batch-experience inheritance cap (0 = disable sharing)")
            .defineInRange("sharedExpCap", 65536L, 0L, Long.MAX_VALUE);

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

    // ── ME接口（无限级） ──

    /** 每个物品的蓄水池目标保有量（机器消耗后自动从网络补足；0=不自动补货只收CPU推送）。 */
    public static final ForgeConfigSpec.LongValue FEEDER_STOCK_TARGET = BUILDER
            .comment("ME接口(无限级)每物品的蓄水池目标保有量（机器消耗后自动从网络补足，无单tick上限；0=关闭自动补货）",
                    "Infinite Interface reservoir target per item (auto-restock from network, no per-tick cap; 0=off)")
            .defineInRange("feederStockTarget", 1_000_000L, 0L, Long.MAX_VALUE);

    /** 每 tick 喂给相邻机器的 insertItem 尝试次数上限（防单 tick 卡顿）。 */
    public static final ForgeConfigSpec.IntValue FEEDER_FEED_BUDGET = BUILDER
            .comment("ME接口(无限级)每tick喂给相邻机器的尝试次数上限（防单tick卡顿）",
                    "Infinite Interface max feed attempts per tick (anti-lag)")
            .defineInRange("feederFeedBudget", 1024, 1, 1_000_000);

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

    /** 共享经验继承上限（0 = 关闭共享，新 lane 从 1× 起步）。 */
    public static long sharedExpCap() {
        return Math.max(0L, SHARED_EXP_CAP.get());
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

    /** 显示线程数（0 = Integer.MAX_VALUE-1 拉满）。 */
    public static int cpuDisplayThreads() {
        int v = CPU_DISPLAY_THREADS.get();
        return v <= 0 ? Integer.MAX_VALUE - 1 : v;
    }

    /** ME接口(无限级)：每物品蓄水池目标保有量（0=关闭自动补货）。 */
    public static long feederStockTarget() {
        return Math.max(0L, FEEDER_STOCK_TARGET.get());
    }

    /** ME接口(无限级)：每 tick 喂出尝试次数上限。 */
    public static int feederFeedBudget() {
        return Math.max(1, FEEDER_FEED_BUDGET.get());
    }
}
