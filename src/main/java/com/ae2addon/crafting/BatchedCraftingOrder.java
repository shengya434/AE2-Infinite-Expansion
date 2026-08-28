package com.ae2addon.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingSubmitResult;
import com.ae2addon.util.ChatLog;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * 巨型订单（自动分批）：一个下单量超 long 记账上限的订单，
 * 被拆成多个安全批次，由 BatchedCraftingQueue 推进执行。
 * <p>
 * 执行模型（2026-08-20 并行化）：<b>有限并行</b>——同时最多
 * {@link #MAX_CONCURRENT} 个批次在跑（各自独立模拟+提交+link 跟踪），
 * 完成一批立即补一批，直到全部完成。全程异步，不阻塞服务端线程。
 * <p>
 * 状态机：QUEUED → RUNNING（批次窗口推进）→ DONE / FAILED / CANCELLED
 */
public final class BatchedCraftingOrder {

    public enum Status {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED
    }

    /** 单批材料需求安全上限：Long.MAX/4，给执行层（批量推送 N×）留足余量 */
    public static final long SAFE_LIMIT = Long.MAX_VALUE / 4;

    /**
     * 超限判定/拆批基准（2026-08-27 sensei 拍板）：真溢出线 = 2^63-1 = Long.MAX。
     * 需求 2^63 → 拆成 (2^63-1)+1；2^64 → (2^63-1)×2+2。
     * 不用 SAFE_LIMIT(Long.MAX/4) 或 Long.MAX/2——那些只是保守余量，
     * 会误伤 10^16~10^18 级合法订单（AE2-VM 精确计算不溢出）。
     */
    public static final long OVERFLOW_THRESHOLD = Long.MAX_VALUE;

    /** 单个巨型订单的最大批次数（config maxBatchCount 热加载，防呆：超出则拒绝，避免天文数字批次） */
    private static volatile int MAX_BATCH_COUNT = com.ae2addon.config.AE2AddonConfig.maxBatchCount();

    /**
     * 并行度：同时执行的批次数（config maxConcurrent，0 = 无限制全发）。
     * 2026-08-27 21:14 sensei 拍板无限制：lane 回收已修复（reapIdleLanes 对
     * link canceled 的 lane 强制 cancelJob + 回收），全发不再泄漏；
     * 21:17 改为 config 可配（maxConcurrent=0 无限制）。接受主线程 tick 开销。
     */
    public static volatile int MAX_CONCURRENT = com.ae2addon.config.AE2AddonConfig.maxConcurrent();

    /** 配置热加载时由 AE2AddonConfig 调用（更新并行度/批数上限）。 */
    public static void applyConfig() {
        MAX_CONCURRENT = com.ae2addon.config.AE2AddonConfig.maxConcurrent();
        MAX_BATCH_COUNT = com.ae2addon.config.AE2AddonConfig.maxBatchCount();
    }

    /**
     * 批次模拟复用缓存（2026-08-22）：key=批次量，value=该量第一次模拟的真计划。
     * 同量批次后续直接用 {@link #copyPlan} 深拷贝提交，不再重复跑全量模拟
     * （AE2 模拟含配方树遍历+库存检查，是巨型订单最大的单帧开销）。
     * 计划是不可变记录（CraftingPlan），深拷贝只复制 KeyCounter/Map 引用结构，成本极低。
     * 按订单实例持有：订单结束即随对象回收。
     */
    private final Map<Long, ICraftingPlan> simulatedPlans = new HashMap<>();

    /**
     * 正在跑真实模拟的批次量集合（2026-08-22）：全发模式下若每批都启动真实模拟，
     * CRAFTING_POOL 串行处理几百个模拟成为瓶颈（434 批 → 前 24 秒 0 进度）。
     * 同量批次只允许 1 个真实模拟，其余 {@link BatchProgress#waitingTemplate} 等模板。
     */
    private final java.util.Set<Long> simulatingAmounts = new HashSet<>();

    private final ServerLevel level;
    private IGrid grid;
    private IGridNode simNode;
    private final AEKey what;
    private final long totalAmount;
    private final ICraftingRequester requester;
    private final IActionSource source;
    private final List<Long> batchAmounts;
    private final List<BatchProgress> running = new ArrayList<>();

    /** 恢复锚点：订单所属网格的集成 CPU 方块位置（重进后重新绑定网格） */
    private net.minecraft.core.BlockPos anchorPos;

    /** 设置恢复锚点（下单时从网格 CPU 反查，供存档恢复快速绑定）。 */
    public void setAnchorPos(net.minecraft.core.BlockPos pos) {
        this.anchorPos = pos;
    }

    /** 强制终止（队列 tick 异常时兜底）：标记失败并取消进行中批次。 */
    public void forceTerminate() {
        status = Status.FAILED;
        cancelRunning();
    }

    private int nextBatchIndex = 0;
    private int completedCount = 0;
    private Status status = Status.QUEUED;
    /**
     * CPU 持续忙计时起点（NO_SUITABLE_CPU_FOUND 连续重试的 tick 起点）。
     * 按 tick 判死（6000 tick ≈ 5 分钟）而非按重试次数——多批同时卡住时
     * 重试次数每 tick 暴涨（几十批 × 每 tick），按次数会在几秒内误取消
     * （sensei 实测 16:44「取消的有点太快」）。任一批次提交成功即重置。
     */
    private long busySinceTick = Long.MIN_VALUE;

    /** 单个批次的运行状态 */
    private static final class BatchProgress {
        final long amount;
        Future<ICraftingPlan> pendingSimulation;
        ICraftingLink link;
        /** 同量批次正在模拟 → 等待模板（不重复模拟，见 simulatingAmounts） */
        boolean waitingTemplate;

        BatchProgress(long amount) {
            this.amount = amount;
        }
    }
    private BatchedCraftingOrder(ServerLevel level, IGrid grid, IGridNode simNode,
                                 AEKey what, long totalAmount,
                                 ICraftingRequester requester, IActionSource source,
                                 List<Long> batchAmounts) {
        this.level = level;
        this.grid = grid;
        this.simNode = simNode;
        this.what = what;
        this.totalAmount = totalAmount;
        this.requester = requester;
        this.source = source;

        // 后续构造逻辑（批次拆分等）
        this.batchAmounts = new java.util.ArrayList<>(batchAmounts);
    }

    /**
     * 订单是否属于目标网格（订单面板按网络隔离显示用）。
     * 订单网格未绑定（恢复期）时返回 true（宽松放行，避免恢复期订单消失）；
     * 目标网格为 null 时返回 true（全量视图兼容）。
     */
    public boolean sameGrid(IGrid target) {
        if (target == null || this.grid == null) {
            return true;
        }
        return this.grid == target;
    }

    private static List<Long> splitBatches(long total, long perBatch) {
        List<Long> batches = new ArrayList<>();
        long remaining = total;
        while (remaining > 0) {
            long batch = Math.min(perBatch, remaining);
            batches.add(batch);
            remaining -= batch;
        }
        return batches;
    }

    /**
     * 从存档快照恢复订单：网格/节点延迟绑定（锚点 CPU 方块加载后）。
     * <p>
     * <b>断点回退（2026-08-21 修复）</b>：nextBatchIndex 语义是「已启动的批次指针」，
     * 全发模式下首个 tick 就推到总批数，而进行中的批次（AE2 任务）重启后必然丢失——
     * 若按保存的 nextBatchIndex 恢复，fillWindow 会判定「无新批次可启动」→ 订单死锁，
     * 线程全空闲、永不完成（sensei 截图实锤：恢复后 0/434 进行中但 CPU 全空闲）。
     * 修复：未完成批次（nextBatchIndex - completedCount 个）全部回退重跑。
     * 代价：重启前已提取材料的批次材料损失（材料滞留 CPU 库存），产物不翻倍。
     */
    public static BatchedCraftingOrder restore(
            com.ae2addon.data.MegaOrderSavedData.OrderSnapshot snapshot,
            ServerLevel level) {
        List<Long> batches = splitBatches(snapshot.totalAmount, snapshot.perBatch);
        if (batches.isEmpty()) {
            return null;
        }
        var order = new BatchedCraftingOrder(level, null, null,
                snapshot.what, snapshot.totalAmount, null,
                appeng.api.networking.security.IActionSource.empty(), batches);
        order.completedCount = Math.max(0, Math.min(snapshot.completedCount, batches.size()));
        // 回退到已完成处：已启动未完成的批次全部重跑（任务不跨存档）
        order.nextBatchIndex = order.completedCount;
        order.anchorPos = snapshot.anchor;
        return order;
    }

    /** 导出存档快照（锚点取所属集成 CPU 方块位置）。 */
    public com.ae2addon.data.MegaOrderSavedData.OrderSnapshot snapshot() {
        return new com.ae2addon.data.MegaOrderSavedData.OrderSnapshot(
                what, totalAmount, batchAmounts.get(0),
                completedCount, nextBatchIndex, anchorPos, level.dimension());
    }

    /**
     * 从已损坏（超限）的 plan 创建分批订单；无需拆分时返回 null。
     */
    public static BatchedCraftingOrder create(ICraftingPlan badPlan,
                                              ICraftingRequester requester,
                                              IActionSource source) {
        if (badPlan == null || requester == null) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: 参数为空 plan={} requester={}",
                    badPlan == null ? "null" : "set",
                    requester == null ? "null" : requester.getClass().getSimpleName());
            return null;
        }
        IGridNode node;
        try {
            node = requester.getActionableNode();
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: getActionableNode 异常 requester={} err={}",
                    requester.getClass().getSimpleName(), e.toString());
            return null;
        }
        if (node == null || node.getGrid() == null) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: 节点/网格为空 requester={} node={}",
                    requester.getClass().getSimpleName(), node == null ? "null" : "set");
            return null;
        }
        ServerLevel level = node.getLevel();
        if (level == null) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: level 为空 requester={}",
                    requester.getClass().getSimpleName());
            return null;
        }
        var finalOutput = badPlan.finalOutput();
        if (finalOutput == null || finalOutput.what() == null || finalOutput.amount() <= 0) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: finalOutput 无效 requester={}",
                    requester.getClass().getSimpleName());
            return null;
        }

        IGrid grid = node.getGrid();
        // 2026-08-27：拆批粒度用 OVERFLOW_THRESHOLD（Long.MAX/2，接近真溢出 2^63）
        // 而非 SAFE_LIMIT——SAFE_LIMIT 拆太细，10^18 级订单会拆出 43 万批超上限；
        // 每批允许到接近溢出线，批数可控（10^18 订单约 217 批）。
        long perBatch;
        try {
            perBatch = RequirementCalculator.maxSafeBatch(
                    grid, finalOutput.what(), finalOutput.amount(), OVERFLOW_THRESHOLD);
        } catch (RuntimeException e) {
            // 2026-08-27：VM 环境下配方树/缓存异常时 create 会静默失败导致
            // 订单放行原版（超限不拆批）。记录异常便于定位。
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: maxSafeBatch 异常 what={} amount={} err={}",
                    finalOutput.what(), finalOutput.amount(), e.toString());
            return null;
        }
        if (perBatch >= finalOutput.amount()) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: perBatch>=amount 无法拆批 what={} amount={} perBatch={}",
                    finalOutput.what(), finalOutput.amount(), perBatch);
            return null;
        }

        List<Long> batches = splitBatches(finalOutput.amount(), perBatch);
        if (batches.size() <= 1 || batches.size() > MAX_BATCH_COUNT) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] create: 批数异常无法拆批 what={} amount={} perBatch={} 批数={}",
                    finalOutput.what(), finalOutput.amount(), perBatch, batches.size());
            return null;
        }

        var order = new BatchedCraftingOrder(level, grid, null, finalOutput.what(),
                finalOutput.amount(), requester, source, batches);
        order.anchorPos = findAnchorPos(grid);
        return order;
    }

    /**
     * requester 为 null 时（VM 提交路径）用 grid 直接构造分批订单。
     * 2026-08-27：VM 环境 submitJob 的 requester=null → create() 直接失败
     * （sensei 实测 20:59「create: 参数为空 requester=null」）→ 订单放行原版不拆批。
     * level 从 grid 的 pivot 节点拿。
     */
    public static BatchedCraftingOrder createFromGrid(ICraftingPlan badPlan,
                                                      IGrid grid,
                                                      IActionSource source) {
        if (badPlan == null || grid == null) {
            return null;
        }
        ServerLevel level = null;
        try {
            var pivot = grid.getPivot();
            if (pivot != null) {
                level = pivot.getLevel();
            }
        } catch (RuntimeException ignored) {
        }
        if (level == null) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromGrid: 无法获取 level（pivot 无效）");
            return null;
        }
        var finalOutput = badPlan.finalOutput();
        if (finalOutput == null || finalOutput.what() == null || finalOutput.amount() <= 0) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromGrid: finalOutput 无效");
            return null;
        }
        long perBatch;
        try {
            perBatch = RequirementCalculator.maxSafeBatch(
                    grid, finalOutput.what(), finalOutput.amount(), OVERFLOW_THRESHOLD);
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromGrid: maxSafeBatch 异常 what={} amount={} err={}",
                    finalOutput.what(), finalOutput.amount(), e.toString());
            return null;
        }
        if (perBatch >= finalOutput.amount()) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromGrid: perBatch>=amount 无法拆批 what={} amount={} perBatch={}",
                    finalOutput.what(), finalOutput.amount(), perBatch);
            return null;
        }
        List<Long> batches = splitBatches(finalOutput.amount(), perBatch);
        if (batches.size() <= 1 || batches.size() > MAX_BATCH_COUNT) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromGrid: 批数异常 what={} amount={} perBatch={} 批数={}",
                    finalOutput.what(), finalOutput.amount(), perBatch, batches.size());
            return null;
        }
        var order = new BatchedCraftingOrder(level, grid, grid.getPivot(), finalOutput.what(),
                finalOutput.amount(), null, source, batches);
        order.anchorPos = findAnchorPos(grid);
        return order;
    }

    /**
     * 从上下文创建分批订单（2026-08-21 替代 createFromDeferred：
     * 模拟拦截不再返回 DeferredCraftingPlan（GTL 界面 mixin 强转 CraftingPlan 崩溃），
     * 改为返回真 CraftingPlan + IdentityHashMap 上下文，提交时用上下文拆批）。
     */
    public static BatchedCraftingOrder createFromContext(ICraftingPlan plan,
                                                         IGrid grid,
                                                         ServerLevel level,
                                                         IGridNode simNode,
                                                         long total,
                                                         long perBatch,
                                                         ICraftingRequester requester,
                                                         IActionSource source) {
        if (plan == null || grid == null || level == null) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromContext: plan={} grid={} level={} 无法创建",
                    plan, grid, level);
            return null;
        }
        if (total <= 0 || perBatch <= 0 || perBatch >= total) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromContext: 无法拆批 total={} perBatch={}",
                    total, perBatch);
            return null;
        }

        List<Long> batches = splitBatches(total, perBatch);
        if (batches.size() <= 1 || batches.size() > MAX_BATCH_COUNT) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] createFromContext: 批次数量异常 total={} perBatch={} 批数={}",
                    total, perBatch, batches.size());
            return null;
        }

        var order = new BatchedCraftingOrder(level, grid, simNode,
                plan.finalOutput().what(), total, requester, source, batches);
        order.anchorPos = findAnchorPos(grid);
        return order;
    }

    /** 从网格反查集成 CPU 方块位置（恢复锚点），找不到返回 null（恢复时走扫描兜底）。 */
    private static net.minecraft.core.BlockPos findAnchorPos(IGrid grid) {
        try {
            for (var cpu : grid.getCraftingService().getCpus()) {
                if (cpu instanceof appeng.me.cluster.implementations.CraftingCPUCluster cluster) {
                    var owner = com.ae2addon.block.IntegratedCPURegistry.ownerOf(cluster);
                    if (owner != null) {
                        return owner.getBlockPos();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 网格未就绪：恢复时走扫描路径兜底
        }
        return null;
    }

    /**
     * 队列 tick 推进（服务端线程，不阻塞）：维护批次窗口——
     * 模拟完成→提交→等完成→补新批，保持 MAX_CONCURRENT 个并行。
     *
     * @return true = 订单仍在进行中；false = 订单已结束
     */
    public boolean tick() {
        switch (status) {
            case QUEUED -> {
                // 恢复的订单：等待锚点 CPU 方块加载并绑定网格
                if (grid == null && !tryBindGrid()) {
                    return true;
                }
                status = Status.RUNNING;
                fillWindow();
                return status == Status.RUNNING;
            }
            case RUNNING -> {
                // 1. 模拟完成的批次 → 提交；等待模板的批次 → 模板就绪后转入模拟完成
                for (BatchProgress batch : running) {
                    if (batch.waitingTemplate) {
                        ICraftingPlan template = simulatedPlans.get(batch.amount);
                        if (template != null) {
                            batch.waitingTemplate = false;
                            batch.pendingSimulation = CompletableFuture.completedFuture(template);
                        }
                        continue;
                    }
                    if (batch.pendingSimulation != null && batch.pendingSimulation.isDone()) {
                        Future<ICraftingPlan> done = batch.pendingSimulation;
                        batch.pendingSimulation = null;
                        ICraftingPlan plan;
                        try {
                            plan = done.get();
                        } catch (Exception e) {
                            com.ae2addon.AE2Addon.LOGGER.error(
                                    "[ae2addon] 分批模拟异常 what={}", what, e);
                            failAll();
                            return false;
                        }
                        if (plan == null || plan.simulation()) {
                            com.ae2addon.AE2Addon.LOGGER.warn(
                                    "[ae2addon] 分批模拟缺料/无效 what={} plan={}",
                                    what, plan);
                            ChatLog.err(level, null,
                                    "批次缺少材料或模拟无效，订单已取消（见日志）");
                            failAll();
                            return false;
                        }
                        // 模拟复用：首次模拟结果入缓存；同量批次深拷贝后提交（不提交缓存本体，
                        // 防任何 mixin 拿 plan 做身份判断/修改时炸——8/17 教训）
                        ICraftingPlan cached = simulatedPlans.get(batch.amount);
                        if (cached == null) {
                            simulatedPlans.put(batch.amount, plan);
                            simulatingAmounts.remove(batch.amount); // 模板就绪，等待批次可领
                        } else {
                            plan = copyPlan(cached);
                        }
                        // requester 为 null（玩家终端提交）时用虚拟请求方换取真实 link
                        ICraftingRequester effectiveRequester = requester != null
                                ? requester : new BatchedRequester(simNode, source);
                        ICraftingSubmitResult result = BatchedCraftingQueue.submitBypass(
                                grid, plan, effectiveRequester, source);
                        if (result == null || !result.successful()) {
                            // 2026-08-22：CPU 瞬时不可用（唯一集成 CPU 忙/量子分裂 lane
                            // 未就绪）不应取消整个订单——批次放回等下个 tick 重试。
                            // 原实现直接 failAll：388 批的订单第一批提交失败就全取消。
                            if (result != null && result.errorCode()
                                    == appeng.api.networking.crafting.CraftingSubmitErrorCode
                                            .NO_SUITABLE_CPU_FOUND) {
                                long nowTick = appeng.hooks.ticking.TickHandler.instance()
                                        .getCurrentTick();
                                if (busySinceTick == Long.MIN_VALUE) {
                                    busySinceTick = nowTick;
                                }
                                long busyTicks = nowTick - busySinceTick;
                                if (busyTicks > 0 && busyTicks % 200 == 0) {
                                    com.ae2addon.AE2Addon.LOGGER.info(
                                            "[ae2addon] 批次提交 CPU 忙，稍后重试 what={} 进度={}/{} 持续={}s",
                                            what, completedCount, batchAmounts.size(),
                                            busyTicks / 20);
                                }
                                if (busyTicks > 6000) {
                                    com.ae2addon.AE2Addon.LOGGER.warn(
                                            "[ae2addon] 批次提交 CPU 持续忙超过 5 分钟，订单取消 what={}",
                                            what);
                                    ChatLog.err(level, null,
                                            "CPU 持续忙超过 5 分钟，巨型订单已取消");
                                    failAll();
                                    return false;
                                }
                                batch.pendingSimulation = CompletableFuture.completedFuture(plan);
                                continue;
                            }
                            com.ae2addon.AE2Addon.LOGGER.warn(
                                    "[ae2addon] 分批提交失败: what={} 错误码={} 详情={}",
                                    what,
                                    result == null ? "null" : result.errorCode(),
                                    result == null ? "null" : result.errorDetail());
                            ChatLog.err(level, null,
                                    "批次提交失败，订单已取消");
                            failAll();
                            return false;
                        }
                        batch.link = result.link();
                        // 提交成功：CPU 不再持续忙，重置判死计时
                        busySinceTick = Long.MIN_VALUE;
                        if (CraftingCompat.debugLogs) {
                            com.ae2addon.AE2Addon.LOGGER.info(
                                    "[ae2addon] 批次已提交 what={} 进度={}/{}+{}",
                                    what, completedCount, batchAmounts.size(),
                                    running.size());
                        }
                    }
                }

                // 2. 清理完成的批次；检查取消
                Iterator<BatchProgress> iterator = running.iterator();
                while (iterator.hasNext()) {
                    BatchProgress batch = iterator.next();
                    if (batch.link == null) {
                        continue;
                    }
                    if (batch.link.isCanceled()) {
                        com.ae2addon.AE2Addon.LOGGER.warn(
                                "[ae2addon][debug] 批次link被取消触发订单取消 craftId={} what={}",
                                batch.link.getCraftingID(), what);
                        cancelAll();
                        return false;
                    }
                    if (batch.link.isDone()) {
                        completedCount++;
                        iterator.remove();
                        if (CraftingCompat.debugLogs) {
                            com.ae2addon.AE2Addon.LOGGER.info(
                                    "[ae2addon] 批次完成 what={} 进度={}/{}",
                                    what, completedCount, batchAmounts.size());
                        }
                    }
                }

                // 3. 补新批，保持并行度
                fillWindow();

                // 4. 全部完成？
                if (completedCount == batchAmounts.size() && running.isEmpty()) {
                    markDone();
                    return false;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 补充批次窗口：保持 MAX_CONCURRENT 个进行中（含模拟中），一次性全发。
     * 2026-08-22 恢复全发：beginCraftingCalculation 是异步提交到 CRAFTING_POOL 的
     * （已反编译确认），全发不卡主线程；限流反而让批次建立变慢
     * （sensei 实测 16:51「批次建立慢，一瞬间全部建立更好」）。
     */
    private void fillWindow() {
        while (status == Status.RUNNING
                && running.size() < MAX_CONCURRENT
                && nextBatchIndex < batchAmounts.size()) {
            BatchProgress batch = new BatchProgress(batchAmounts.get(nextBatchIndex));
            nextBatchIndex++;
            if (!startSimulation(batch)) {
                failAll();
                return;
            }
            running.add(batch);
        }
    }

    /** 恢复锚点绑定：锚点 CPU 方块优先，否则扫描已加载的集成 CPU（仅单网格场景）。
     *  2026-08-27：扫描兜底限定「全局只有一个集成 CPU」——多网络时扫描会绑到
     *  错误网络的 BE（sensei 实测：另一个网络的 CPU 出现本网络订单的运行线程）。
     *  多网络且锚点未就绪时返回 false 等重试（下个 tick 锚点 BE 加载后再绑）。 */
    private boolean tryBindGrid() {
        if (anchorPos != null) {
            try {
                if (level.getBlockEntity(anchorPos)
                        instanceof com.ae2addon.block.IntegratedCPUBE be && be.isFormed()) {
                    var mainNode = be.getMainNode();
                    var node = mainNode == null ? null : mainNode.getNode();
                    if (node != null && node.getGrid() != null) {
                        this.grid = node.getGrid();
                        this.simNode = node;
                        com.ae2addon.AE2Addon.LOGGER.info(
                                "[ae2addon] 巨型订单恢复绑定网格成功 what={} 进度={}/{} 锚点={}",
                                what, completedCount, batchAmounts.size(), anchorPos.toShortString());
                        return true;
                    }
                }
            } catch (RuntimeException ignored) {
                // 方块未加载/结构未成型，下个 tick 重试
            }
        }
        // 兜底扫描：仅当全局只有一个集成 CPU（单网格测试环境直接命中）
        com.ae2addon.block.IntegratedCPUBE only = null;
        int total = 0;
        for (var be : com.ae2addon.block.IntegratedCPURegistry.all()) {
            if (be.isRemoved() || !be.isFormed()) {
                continue;
            }
            total++;
            only = be;
        }
        if (total == 1 && only != null) {
            try {
                var mainNode = only.getMainNode();
                var node = mainNode == null ? null : mainNode.getNode();
                if (node != null && node.getGrid() != null) {
                    this.grid = node.getGrid();
                    this.simNode = node;
                    this.anchorPos = only.getBlockPos();
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon] 巨型订单恢复绑定网格成功(扫描·单网格) what={} 进度={}/{}",
                            what, completedCount, batchAmounts.size());
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 继续等待
            }
        }
        return false;
    }

    /**
     * 发起批次模拟（异步）。返回 false 表示发起失败。
     * <p>
     * 2026-08-22 模拟复用：同量批次已有缓存计划时不再调 beginCraftingCalculation
     * （AE2 模拟同步执行，是巨型订单最大单帧开销），直接返回已完成 Future，
     * 提交时由调用方深拷贝缓存计划。
     */
    private boolean startSimulation(BatchProgress batch) {
        ICraftingPlan cached = simulatedPlans.get(batch.amount);
        if (cached != null) {
            batch.pendingSimulation = CompletableFuture.completedFuture(cached);
            return true;
        }
        // 同量批次已有真实模拟在跑 → 等模板（2026-08-22：避免全发模式下
        // 几百个真实模拟把 CRAFTING_POOL 串行堵死）
        if (!simulatingAmounts.add(batch.amount)) {
            batch.waitingTemplate = true;
            batch.pendingSimulation = null;
            return true;
        }
        IGridNode node = simNode;
        if (node == null) {
            try {
                node = requester == null ? null : requester.getActionableNode();
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.warn(
                        "[ae2addon] 发起批次模拟: requester节点不可用", e);
            }
        }
        try {
            // 2026-08-27 方案2：批次模拟不再走原版 beginCraftingCalculation
            // （long 累加在 999999×999999×batch 中间溢出 → 损坏计划 → 批次全失败），
            // 改用 BigInteger 估算直接构造真计划（simulation=false），永不溢出。
            // 模拟复用缓存逻辑不变（同量批次深拷贝提交）。
            ICraftingPlan plan = RequirementCalculator.buildBatchPlan(
                    grid, what, batch.amount);
            if (plan == null) {
                com.ae2addon.AE2Addon.LOGGER.error(
                        "[ae2addon] 发起批次模拟: 估算失败/截断 what={} amount={}",
                        what, batch.amount);
                return false;
            }
            batch.pendingSimulation = CompletableFuture.completedFuture(plan);
            return true;
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.error(
                    "[ae2addon] 发起批次模拟异常 what={}", what, e);
            return false;
        }
    }

    /**
     * 深拷贝一个 ICraftingPlan（CraftingPlan 是 final record，不能直接复用实例，
     * 且任何 mixin 拿 plan 做身份判断/修改时共享实例会炸——8/17 教训）。
     * 只复制 KeyCounter 条目与 patternTimes 引用结构，成本极低。
     */
    private static ICraftingPlan copyPlan(ICraftingPlan source) {
        var used = new KeyCounter();
        for (var entry : source.usedItems()) {
            used.add(entry.getKey(), entry.getLongValue());
        }
        var emitted = new KeyCounter();
        for (var entry : source.emittedItems()) {
            emitted.add(entry.getKey(), entry.getLongValue());
        }
        var missing = new KeyCounter();
        for (var entry : source.missingItems()) {
            missing.add(entry.getKey(), entry.getLongValue());
        }
        return new CraftingPlan(source.finalOutput(), source.bytes(),
                source.simulation(), source.multiplePaths(),
                used, emitted, missing,
                new HashMap<>(source.patternTimes()));
    }

    private void failAll() {
        status = Status.FAILED;
        cancelRunning();
        com.ae2addon.AE2Addon.LOGGER.warn(
                "[ae2addon] 巨型订单失败 what={} 进度={}/{}",
                what, completedCount, batchAmounts.size());
    }

    private void cancelAll() {
        status = Status.CANCELLED;
        cancelRunning();
        com.ae2addon.AE2Addon.LOGGER.warn(
                "[ae2addon] 巨型订单被取消 what={} 进度={}/{}",
                what, completedCount, batchAmounts.size());
    }

    /** 外部（订单面板）取消整个订单：取消所有进行中批次并终止后续批次 */
    public void cancelOrder() {
        if (CraftingCompat.debugLogs) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][debug] 订单面板取消请求 what={} 进度={}/{}",
                    what, completedCount, batchAmounts.size());
        }
        cancelAll();
        ChatLog.warn(level, null,
                "巨型订单已取消（" + what + " " + completedCount
                        + "/" + batchAmounts.size() + " 批）");
    }

    private void cancelRunning() {
        for (BatchProgress batch : running) {
            if (batch.link != null && !batch.link.isDone() && !batch.link.isCanceled()) {
                try {
                    batch.link.cancel();
                } catch (RuntimeException ignored) {
                    // 链接已失效
                }
            }
        }
        running.clear();
    }

    public void markDone() {
        status = Status.DONE;
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon] 巨型订单完成 what={} total={} 批次={}",
                what, totalAmount, batchAmounts.size());
    }

    public Status getStatus() {
        return status;
    }

    public int getBatchCount() {
        return batchAmounts.size();
    }

    public int getCurrentBatchIndex() {
        return Math.min(completedCount + 1, batchAmounts.size());
    }

    public int getCompletedCount() {
        return completedCount;
    }

    public AEKey getWhat() {
        return what;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public ServerLevel getLevel() {
        return level;
    }
}
