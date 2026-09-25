package com.ae2addon.block;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2addon.api.IntegratedCraftingServiceBridge;
import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.mixin.CraftingCPUClusterAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集成型 CPU 方块实体。
 * <p>
 * 多方块结构（2026-09-24 起为 16×44×34、2223 块要放置的大结构，定义在资源文件里）成型后：
 * - 始终提供无限合成存储（Long.MAX_VALUE，单核心方块不溢出；
 *   若簇内混入其他贡献存储的方块会相加溢出，请勿混用）
 * - 如果结构内包含无限并行处理器，额外提供拉满并行（Integer.MAX_VALUE−1 账面值，
 *   真实执行由 CraftingCpuLogicMixin 的时间片限流）
 * - 未成型时存储/线程都返回 0 → **CPU 功能禁用**，但控制器**始终联网**
 *   （一键成型要取料、拆解退料要入网，见 {@link #getGridNode(Direction)}）
 * <p>
 * 教训（2026-07-10 + 2026-08-06 + 2026-08-10）：
 * - Long.MAX_VALUE → CraftingCPUCluster.addBlockEntity 里 storage += bytes 会溢出为负 → 存不下材料（多个贡献方块相加时）
 * - Integer.MAX_VALUE → tickCraftingLogic 里 getCoProcessors()+1 溢出为负 → ops<=0 → CPU 永不执行（故用 MAX_VALUE−1）
 * - 高线程数 → 单 tick 循环爆炸 → 时间片限流（2026-08-10，参考 OmniSequence-Transfinite）
 */
public class IntegratedCPUBE extends CraftingBlockEntity implements Formable {

    private boolean formed = false;
    private boolean hasCoProcessing = false;

    /**
     * 成型来源标志（2026-09-24）。
     * <p>
     * ⚠ 必须区分，否则创造模式的「已成型」变体会被结构判定推翻：
     * <ul>
     *   <li>{@code structureFormed = true} —— 由**结构判定**成功置位。
     *       结构一旦不完整（拆了一块 / 定时复检发现不符）就该退回未成型；</li>
     *   <li>{@code creativeFormed = true} —— 由创造模式「已成型」变体物品放置时置位
     *       （{@code FormedBlockItem} → {@code applyCreativeFormed}）。
     *       **周围没结构也永久保持成型**，结构复检不能把它打回去。</li>
     * </ul>
     * 旧存档兼容：只有 {@code formed} 没有这两个标志时，按"结构成型"处理（保持旧行为）。
     */
    private boolean structureFormed = false;
    private boolean creativeFormed = false;

    /**
     * 虚拟 CPU lane（量子分裂线程）：主簇忙时，新任务自动分配到空闲 lane，
     * 多个订单并行执行。思路来自 OmniSequence-Transfinite 的 Omni-Computation Core。
     */
    private final List<CraftingCPUCluster> virtualCpus = new ArrayList<>();

    /**
     * 常驻空闲 lane 数（2026-08-22：1 → 16；2026-08-27：config idleLaneTarget 可配）。
     * 主簇忙时一个 tick 内 burst 建满，让巨型订单批次立即并行——原来 1 lane/tick
     * 串行建立（每批占掉 lane 后下 tick 才补 1 个），sensei 实测「线程创建速度
     * 1线程/t」批次推进极慢。主簇空闲时回收全部空闲 lane，不空转。
     */
    private static volatile int IDLE_LANE_TARGET = com.ae2addon.config.AE2AddonConfig.idleLaneTarget();

    /** 配置热加载时由 AE2AddonConfig 调用（更新空闲 lane 池大小）。 */
    public static void applyConfig() {
        IDLE_LANE_TARGET = com.ae2addon.config.AE2AddonConfig.idleLaneTarget();
    }

    public IntegratedCPUBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INTEGRATED_CPU.get(), pos, state);
    }

    @Override
    public void onReady() {
        super.onReady();
        IntegratedCPURegistry.register(this);
    }

    @Override
    public void onChunkUnloaded() {
        IntegratedCPURegistry.unregister(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        pushRingStateToClients(false);
        pushOutlineStateToClients(true);
        IntegratedCPURegistry.unregister(this);
        super.setRemoved();
    }

    public boolean isFormed() {
        return formed;
    }

    /**
     * 置成型标志（**只改 BE 标志，不碰任务取消/结构判定**）。
     * <p>
     * 结构判定之后的统一入口是 {@link #reevaluateStructure()}，它会负责
     * 「先退料、再置未成型」的顺序。别在别处直接调本方法置 false。
     */
    public void setFormed(boolean formed) {
        this.formed = formed;
        if (formed) {
            ensureOneIdleCpu();
        }
        setChanged();
        pushRingStateToClients();
        pushOutlineStateToClients();
    }

    /**
     * 主动催 AE2 重算多方块（2026-09-24 修"线程数 0"）。
     * <p>
     * 我们的结构判定是自研的，AE2 的 {@code CraftingCPUCalculator} 并不知道"结构已就位" ——
     * 它只在 {@code onReady} / 邻居变动时自己跑一遍。结构刚搭好（尤其是一键成型批量 setBlock）
     * 那一刻它可能没被触发，于是控制器一直是"孤块"（诊断里 簇=null），
     * 而线程只对**簇内成员**生效 → 线程恒 0。
     * <p>
     * 结构判定通过时调这里，让 AE2 按真实邻居关系重建簇。
     */
    private void kickClusterRecalc() {
        if (level == null || level.isClientSide) {
            return;
        }
        try {
            updateMultiBlock(worldPosition);
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][cpu] 催算多方块失败: {}", e.toString());
        }
    }

    /**
     * 线程为 0 时的决定性诊断（2026-09-24 sensei 报障「线程数一直是0」）。
     * <p>
     * AE2 的线程数走 {@code CraftingCPUCluster.addBlockEntity → getAcceleratorThreads()}，
     * **只有簇内成员才有线程**；而控制器能不能进簇，取决于 AE2 的 {@code CraftingCPUCalculator}
     * 认不认它周围那些 crafting 方块（同类型才成簇）。
     * <p>
     * 所以一次打全：簇是否存在、簇里有几块、hasCo 标志、本发明块类型、六个邻居是什么。
     */
    private void logThreadDiagnostics() {
        if (level == null || level.isClientSide) {
            return;
        }
        try {
            var cluster = getCluster();
            int clusterSize = 0;
            if (cluster != null && !cluster.isDestroyed()) {
                var it = cluster.getBlockEntities();
                while (it.hasNext()) {
                    it.next();
                    clusterSize++;
                }
            }
            var sb = new StringBuilder();
            for (Direction d : Direction.values()) {
                var nb = level.getBlockState(worldPosition.relative(d));
                sb.append(d.getName().charAt(0)).append('=')
                        .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(nb.getBlock()))
                        .append(' ');
            }
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][cpu] 成型判定通过 pos={} 朝向={} 簇={} 簇内块数={} hasCo={} 本块类型={} 邻居: {}",
                    worldPosition, com.ae2addon.block.multiblock.StructureMatcher.describe(structureBody),
                    cluster == null ? "null" : (cluster.isDestroyed() ? "destroyed" : "有"),
                    clusterSize, hasCoProcessing,
                    getUnitBlock() == null ? "?"
                            : net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(getUnitBlock()),
                    sb.toString().trim());
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][cpu] 诊断失败: {}", e.toString());
        }
    }

    /**
     * **重新判定结构**（2026-09-24 sensei：废弃木棍，改成控制器放下后 / 周边方块变动时自动判定）。
     * <p>
     * 判定完全交给 {@code IntegratedCpuStructure.match}（4 个水平朝向都认，一格可接受多个方块）。
     * <ul>
     *   <li>匹配上 → 置成型（同时刷新 blockstate 的 {@code formed} 属性，贴图才会切到成型版）；</li>
     *   <li>匹配不上 → 若本来已成型，**先退料再置未成型**（顺序不能反：退回靠簇的网格节点，
     *       节点断了料就退不进网络），并把并行处理器标志一并清掉；</li>
     *   <li>本来就没成型且仍然不匹配 → 什么都不做（避免反复触发退料）。</li>
     * </ul>
     *
     * @return 判定后是否成型
     */
    /**
     * ⚠ 重入保护：{@code reevaluateStructure} 里会 {@code setBlock} 同步 formed 属性，
     * 而 setBlock 又会触发邻居变动回调 → 再次复检 → 又 setBlock ……
     * （理论上属性没变时 setBlock 不会再触发，但别赌实现细节，直接加锁最稳。）
     */
    private boolean structureCheckRunning;

    /** 上一次结构判定失败时的原因（每格一行，最多存几条），供界面/聊天栏提示 */
    private final List<String> lastStructureProblems = new ArrayList<>();

    public List<String> getLastStructureProblems() {
        return List.copyOf(lastStructureProblems);
    }

    public boolean reevaluateStructure() {
        if (level == null || level.isClientSide) {
            return formed;
        }
        if (structureCheckRunning) {
            return formed;
        }
        structureCheckRunning = true;
        try {
            var problems = new ArrayList<com.ae2addon.block.multiblock.StructureMatcher.Problem>();
            // 2026-09-25 sensei 选 2：两份结构都试（含拓展优先）—— 命中哪份就记哪份，
            // 后续"哪些格算结构的一部分"全部跟着它走（见 structurePattern 字段注释）。
            var patterns = new ArrayList<com.ae2addon.block.multiblock.IntegratedCpuStructure.Pattern>();
            patterns.add(com.ae2addon.block.multiblock.IntegratedCpuStructure.get());          // 含拓展（优先）
            patterns.add(com.ae2addon.block.multiblock.IntegratedCpuStructure
                    .loadVariant("/data/ae2addon/integrated_cpu_structure_noexpand.txt"));     // 无拓展
            var matched = com.ae2addon.block.multiblock.IntegratedCpuStructure.matchAny(
                    (net.minecraft.server.level.ServerLevel) level, worldPosition, patterns, problems);
            boolean nowFormed = matched != null;

            if (nowFormed) {
                lastStructureProblems.clear();
                lastProblemNotifyText = null;
                recentProblemNotices.clear();
                var match = matched.match();
                structureBody = match.body();     // 构建一键成型时沿用这个朝向
                structurePattern = matched.pattern();   // 记住命中的是哪一份（后续格子判断都跟着走）
                // ⚠ 诊断（2026-09-24 sensei：「线程数一直是0」）：线程来自 AE2 簇（getAcceleratorThreads
                //   只对**簇内成员**生效），而控制器能不能进簇取决于「相邻有没有同类型的 crafting 方块」。
                //   这行日志把判定当场的关键状态一次打全，避免靠猜。
                logThreadDiagnostics();
                // ⚠ 主动让 AE2 重算一次多方块（2026-09-24 修"线程数 0"）：
                //   诊断显示 簇=null，而控制器西面明明贴着 crafting_unit（同为 AbstractCraftingUnitBlock，
                //   按 AE2 规则应该成簇）。原因是**结构判定是我们自己做的**，AE2 的 CraftingCPUCalculator
                //   只在 onReady / 方块邻居变动时自己跑；结构搭好那一刻它未必被触发过。
                //   这里判定通过就显式催一次，让簇按真实邻居关系重建。
                kickClusterRecalc();
                pushStatusToClients(true);   // 成型变动立刻刷新网络侧显示
                if (!formed) {
                    setFormed(true);
                    structureFormed = true;
                    creativeFormed = false;
                    syncFormedBlockState();
                } else if (!creativeFormed) {
                    structureFormed = true;
                }
                // 结构里有没有无限并行处理器（决定给不给线程）
                setHasCoProcessing(scanCoProcessing(match.body()));
            } else {
                // 记下错在哪（界面/日志用；判定本身不看它）
                lastStructureProblems.clear();
                for (int i = 0; i < problems.size() && i < 5; i++) {
                    var p = problems.get(i);
                    lastStructureProblems.add(p.pos().toShortString() + " 应为 " + p.expectedName()
                            + "，实际 " + p.found().getBlock().getName().getString());
                }
                // ⚠ 2026-09-24 sensei：创造模式「已成型」变体**不能被结构判定推翻** ——
                //   它周围本来就没结构，判定必然失败；那种方块要永久保持成型。
                if (formed && creativeFormed) {
                    return true;
                }
                if (formed) {
                    // ⚠ 顺序：先取消任务（材料退回网络）→ 再置未成型
                    cancelAllLanes();
                    setHasCoProcessing(false);
                    setFormed(false);
                    structureFormed = false;
                    syncFormedBlockState();
                    notifyProblems();
                } else {
                    notifyProblemsThrottled();
                }
            }
            return nowFormed;
        } finally {
            structureCheckRunning = false;
            pushOutlineStateToClients();
        }
    }

    /**
     * 把 blockstate 里名叫 {@code formed} 的布尔属性同步成当前状态（贴图切换用）。
     * <p>
     * 旧实现只在创造模式「已成型」物品放置时同步（{@code FormedBlockItem}），
     * 手工搭出来的结构逻辑成型了、贴图还是未成型。
     */
    private void syncFormedBlockState() {
        if (level == null || level.isClientSide) {
            return;
        }
        var state = getBlockState();
        for (var property : state.getProperties()) {
            if (property instanceof net.minecraft.world.level.block.state.properties.BooleanProperty bp
                    && property.getName().equals("formed")) {
                if (state.getValue(bp) != formed) {
                    level.setBlock(worldPosition, state.setValue(bp, formed), 3);
                }
                return;
            }
        }
    }

    // ── 一键成型（2026-09-24 第 3 阶段）────────────────────────────────────────

    /** 当前构建任务（null = 没有在跑；完成后置 null） */
    private com.ae2addon.block.multiblock.StructureBuilder.Job buildJob;

    /** 构建状态（给界面显示） */
    private com.ae2addon.block.multiblock.StructureBuilder.State buildState =
            com.ae2addon.block.multiblock.StructureBuilder.State.IDLE;

    /** 构建状态补充说明（缺料清单 / 冲突坐标 / 已成型提示） */
    private String buildMessage = "";

    /** 结构成型朝向（结构判定命中时记录；构建时用它算世界坐标） */
    @Nullable
    private Direction structureBody;

    /**
     * 判定命中的**结构定义**（2026-09-25 sensei 选 2：无拓展单元结构也要能成型）。
     * <p>
     * 集成 CPU 有两份结构：含拓展单元（31×53×41）与无拓展单元（27×44×42）。
     * 判定时两份都试（{@code IntegratedCpuStructure.matchAny}），命中哪份就记在这里 ——
     * 之后所有"这格算不算结构的一部分"的判断（{@link #containsStructurePos} /
     * {@link #isAssemblerPos} / {@link #scanCoProcessing} / 一键回收的覆盖格）**都必须跟着它走**，
     * 否则按无拓展搭的建筑会被拿含拓展那份去比对，拆错格子。
     * <p>
     * 为 {@code null} 时（还没判定过）各处退回默认结构 {@code IntegratedCpuStructure.get()}。
     */
    @Nullable
    private com.ae2addon.block.multiblock.IntegratedCpuStructure.Pattern structurePattern;

    /** 当前生效的结构定义（判定命中过就用命中的，否则用默认含拓展那份） */
    private com.ae2addon.block.multiblock.IntegratedCpuStructure.Pattern activePattern() {
        var p = structurePattern;
        return p != null ? p : com.ae2addon.block.multiblock.IntegratedCpuStructure.get();
    }

    // ── 结构形态选择（2026-09-25 sensei 选 A1：界面加形态切换）──────────────
    // 「一键成型放哪一份」由玩家在界面选，默认含拓展。与 activePattern() 的区别：
    //   activePattern() = 判定**命中**的那份（用于"这格算不算结构的一部分"）
    //   buildPattern()  = 玩家**选定**的那份（用于一键成型放哪一份/回收按哪份范围）
    // 两者在结构成型后通常一致（玩家会选对应形态），但未成型/换形态时以玩家选择为准。

    /** 选定的结构形态：{@code false} = 含拓展单元（默认），{@code true} = 无拓展单元。 */
    private boolean formVariant;

    /** -1 = automatic; 0..3 = south, west, north, east. */
    private int buildFacing = -1;

    public int getBuildFacing() {
        return buildFacing;
    }

    public void setBuildFacing(int facing) {
        int selected = Math.max(-1, Math.min(3, facing));
        if (buildFacing == selected) return;
        buildFacing = selected;
        setChanged();
        pushOutlineStateToClients();
    }

    private static Direction bodyForSelection(int selection) {
        return com.ae2addon.block.multiblock.StructureMatcher.rotateAroundY(Direction.SOUTH, selection);
    }

    /** Neither controller variant has a facing property; use the player's horizontal side. */
    private Direction bodyToward(net.minecraft.world.entity.player.Player player) {
        double dx = player.getX() - (worldPosition.getX() + 0.5);
        double dz = player.getZ() - (worldPosition.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Direction preferredBody(@Nullable net.minecraft.world.entity.player.Player player) {
        if (buildFacing >= 0) return bodyForSelection(buildFacing);
        if (player != null) return bodyToward(player);
        return structureBody != null ? structureBody : Direction.SOUTH;
    }

    private Direction outlineBody() {
        if (formed || buildJob != null) return structureBody != null ? structureBody : Direction.SOUTH;
        if (buildFacing >= 0) return bodyForSelection(buildFacing);
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            var nearest = server.getNearestPlayer(worldPosition.getX() + 0.5,
                    worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, 256, false);
            if (nearest != null) return bodyToward(nearest);
        }
        return structureBody != null ? structureBody : Direction.SOUTH;
    }

    /** 无拓展单元那份结构定义（懒加载一次） */
    @Nullable
    private com.ae2addon.block.multiblock.IntegratedCpuStructure.Pattern noExpandPattern;

    /** 取"无拓展单元"结构定义（读第二份资源；失败时退回含拓展那份，不让游戏崩） */
    private com.ae2addon.block.multiblock.IntegratedCpuStructure.Pattern noExpand() {
        var p = noExpandPattern;
        if (p == null) {
            try {
                p = com.ae2addon.block.multiblock.IntegratedCpuStructure
                        .loadVariant("/data/ae2addon/integrated_cpu_structure_noexpand.txt");
            } catch (RuntimeException e) {
                com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][cpu] 读无拓展结构失败，退回含拓展: {}",
                        e.toString());
                p = com.ae2addon.block.multiblock.IntegratedCpuStructure.get();
            }
            noExpandPattern = p;
        }
        return p;
    }

    /** 界面用：当前选定的形态是不是"无拓展单元" */
    public boolean isNoExpandForm() {
        return formVariant;
    }

    /** 界面用：切换结构形态（true = 无拓展） */
    public void setNoExpandForm(boolean noExpand) {
        if (formVariant == noExpand) {
            return;
        }
        formVariant = noExpand;
        setChanged();
        pushOutlineStateToClients();
    }

    /** 一键成型要放的那份结构（玩家选定） */
    private com.ae2addon.block.multiblock.IntegratedCpuStructure.Pattern buildPattern() {
        return formVariant ? noExpand() : com.ae2addon.block.multiblock.IntegratedCpuStructure.get();
    }

    /** 一键回收要按哪份范围拆：**优先判定命中的那份**，没命中才用玩家选定的。 */
    private com.ae2addon.block.multiblock.IntegratedCpuStructure.Pattern recyclePattern() {
        var p = structurePattern;
        return p != null ? p : buildPattern();
    }

    /** 完成后的进度保留值（任务对象置 null 后界面还能显示 N/N） */
    private int lastBuildPlaced;
    private int lastBuildTotal;

    /** 构建被阻挡的位置（方块类拿去画红色高亮），消费一次后清空 */
    @Nullable
    private BlockPos pendingConflictHighlight;

    public com.ae2addon.block.multiblock.StructureBuilder.State getBuildState() {
        return buildState;
    }

    public String getBuildMessage() {
        return buildMessage == null ? "" : buildMessage;
    }

    public int getBuildPlaced() {
        return buildJob == null ? lastBuildPlaced : buildJob.placed();
    }

    public int getBuildTotal() {
        return buildJob == null ? lastBuildTotal : buildJob.total();
    }

    public boolean isBuilding() {
        return buildJob != null;
    }

    // ── 一键回收（2026-09-25 sensei）────────────────────────────────────────
    // 除控制器以外，把结构里所有方块拆掉并把物品还回玩家背包/网络。
    // 分批（每 tick 一批）执行，避免 2200+ 次 setBlock 卡住服务端。

    /** 回收任务：待拆位置 + 进度（NBT 不持久化，中途退出世界就作废，结构还在原样） */
    private record RecycleJob(List<BlockPos> queue, int cursor, int total) {}

    @Nullable
    private RecycleJob recycleJob;
    /** -1 = 还没回收过；>=0 = 上一次回收拆了多少块（任务结束后仍保留，供界面显示"已回收 N"） */
    private int lastRecycleTotal = -1;
    /** 上一次回收是否已经跑完（界面据此显示"已回收 N"，而不是"回收中"） */
    private boolean lastRecycleFinished;

    /** 界面用：上一次回收是否已经结束（true 且 total>0 → 显示"已回收 N"） */
    public boolean isRecycleFinished() {
        return recycleJob == null && lastRecycleFinished;
    }

    /** 每 tick 最多拆多少块 */
    private static final int RECYCLE_PER_TICK = 256;

    public boolean isRecycling() {
        return recycleJob != null;
    }

    public int getRecycleProgress() {
        RecycleJob j = recycleJob;
        return (j == null ? lastRecycleTotal : j.cursor());
    }

    public int getRecycleTotal() {
        RecycleJob j = recycleJob;
        return j == null ? lastRecycleTotal : j.total();
    }

    /**
     * 开始一键回收：先取消本机所有任务（材料按既有链路退回网络），再退成型，最后分批拆方块。
     * <p>
     * 顺序不能反：取消任务要靠簇的网格节点，节点随结构拆掉就没了 ——
     * 这是 v300 那轮踩过的坑（「删除后订单没有全部取消完」）。
     *
     * @return 本次要拆的方块数；0 = 没什么可拆的
     */
    public int startRecycle(@Nullable net.minecraft.world.entity.player.Player player) {
        if (level == null || level.isClientSide || isBuilding() || isRecycling()) {
            return 0;
        }
        var serverLevel = (net.minecraft.server.level.ServerLevel) level;

        // ① 先取消任务、退料
        cancelAllLanes();
        // ② 结构要拆了 → 立刻退成型（否则拆一半时每 3 秒的复检会反复进出）
        if (formed) {
            setHasCoProcessing(false);
            setFormed(false);
            structureFormed = false;
            syncFormedBlockState();
        }
        // ③ 按当前朝向列出结构格（并集；空气格自动跳过）
        //    2026-09-25：**优先用判定命中的那份**（已建成的结构按它的真实范围拆最安全），
        //    没命中过才退回玩家选定的形态。拿错尺寸会把范围外的方块一起拆掉。
        Direction body = structureBody;
        var positions = com.ae2addon.block.multiblock.IntegratedCpuStructure
                .coveredPositions(serverLevel, worldPosition, recyclePattern(), body);

        this.recycleReturnPlayer = player;

        List<BlockPos> drops = new ArrayList<>(positions);
        // 按 Y 降序拆（从上往下拆，避免上面的方块失去支撑后先掉一次）
        drops.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        recycleJob = new RecycleJob(List.copyOf(drops), 0, drops.size());
        lastRecycleTotal = drops.size();
        lastRecycleFinished = false;
        return drops.size();
    }

    /** 回收时用于把物品还回去的玩家（可能为 null → 直接掉在地上） */
    @Nullable
    private net.minecraft.world.entity.player.Player recycleReturnPlayer;

    /** 每 tick 推进一步回收；返回是否仍有活干 */
    public boolean tickRecycle() {
        RecycleJob job = recycleJob;
        if (job == null) {
            return false;
        }
        if (level == null || level.isClientSide) {
            recycleJob = null;
            return false;
        }
        var serverLevel = (net.minecraft.server.level.ServerLevel) level;
        int end = Math.min(job.cursor() + RECYCLE_PER_TICK, job.total());
        for (int i = job.cursor(); i < end; i++) {
            BlockPos pos = job.queue().get(i);
            BlockState state = serverLevel.getBlockState(pos);
            if (state.isAir() || pos.equals(worldPosition)) {
                continue;   // 已经是空气 / 控制器自己（双保险，绝不动控制器）
            }
            giveBack(serverLevel, pos, state);
            serverLevel.removeBlock(pos, false);
        }
        recycleJob = end >= job.total() ? null : new RecycleJob(job.queue(), end, job.total());
        if (recycleJob == null) {
            lastRecycleFinished = true;   // 跑完 → 界面显示一次"已回收 N"
        }
        return recycleJob != null;
    }
    /** 把一块还原成物品塞回玩家背包；塞不下就掉在原地（不丢东西） */
    private void giveBack(net.minecraft.server.level.ServerLevel serverLevel, BlockPos pos,
                          BlockState state) {
        try {
            var stack = new net.minecraft.world.item.ItemStack(state.getBlock());
            if (stack.isEmpty()) {
                return;
            }
            var p = recycleReturnPlayer;
            if (p != null && p.isAlive()
                    && p.getInventory().add(stack)) {
                return;
            }
            net.minecraft.world.Containers.dropItemStack(serverLevel,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        } catch (RuntimeException ignored) {
            // 单块失败不影响其余（比如没有对应物品的方块：直接消失，不阻塞回收）
        }
    }

    @Nullable
    public BlockPos consumeConflictHighlight() {
        BlockPos p = pendingConflictHighlight;
        pendingConflictHighlight = null;
        return p;
    }

    /**
     * 曾经用红色 dust 粒子画方框的计时逻辑 —— **2026-09-24 弃用**。
     * <p>
     * sensei 实测「红色方框无法穿墙显示」：粒子是普通渲染，会被方块挡住，
     * 而且每 tick 撒上百个粒子会撞粒子限流。现在改用**客户端描边渲染**
     * （{@code client.IntegratedCpuConflictRenderer}，关深度测试 = 穿墙），
     * 位置通过 {@code IntegratedCpuConflictPacket} 下发，这里不再需要每 tick 做事。
     */
    public void tickConflictHighlight() {
        // 保留空实现：方块 ticker 还在调它，删掉调用点要动方块类，不值当。
    }

    @Nullable
    public Direction getStructureBody() {
        return structureBody;
    }

    /**
     * 给定世界坐标是否落在这个集成 CPU 的多方块结构里（含朝向变换）。**只判包围盒**。
     * <p>
     * ⚠ 2026-09-24 重要更正（sensei：「其实这个日志是没有摆在结构内的装配处理器报的」）：
     * 包围盒判定**太宽松**了 —— 结构是 31×53×41 的**空壳**，内部大片是空气，
     * 所以随便摆在结构范围内的装配处理器都会被判成"在结构内"。
     * **装配处理器的归属请用 {@link #isAssemblerPos(BlockPos)}**（要求落在模板指定的那一格）。
     */
    public boolean containsStructurePos(BlockPos world) {
        Direction body = structureBody;
        if (body == null) {
            return false;
        }
        // 用"判定命中的那一份"结构来算（无拓展版尺寸不同，拿错会算到别的格子上）
        var p = activePattern();
        Direction right = body.getCounterClockWise();
        BlockPos core = p.coreOffset();
        int dx = world.getX() - worldPosition.getX();
        int dy = world.getY() - worldPosition.getY();
        int dz = world.getZ() - worldPosition.getZ();
        int alongRight = dx * right.getStepX() + dz * right.getStepZ();
        int alongBody = dx * body.getStepX() + dz * body.getStepZ();
        int lx = alongRight + core.getX();
        int lz = alongBody + core.getZ();
        int ly = dy + core.getY();
        return lx >= 0 && lx < p.width() && ly >= 0 && ly < p.height()
                && lz >= 0 && lz < p.depth();
    }

    /**
     * 这个坐标是不是**模板指定的装配处理器那一格**（世界坐标系，含朝向变换）。
     * <p>
     * 2026-09-24 新增：装配处理器的归属必须用这个判定，不能只判"在包围盒内"——
     * 结构是空壳，包围盒里绝大部分格子是空气，只判包围盒等于"随便放哪都算在结构里"。
     * <p>
     * 判据：把世界坐标反算回局部坐标，再看模板在那一格期望的方块里
     * **是否包含 {@code ae2addon:assembler_core}**。
     */
    public boolean isAssemblerPos(BlockPos world) {
        Direction body = structureBody;
        if (body == null) {
            return false;
        }
        // 同样必须用"判定命中的那一份"：装配处理器在含拓展版里才有，
        // 用错结构会导致散放的装配处理器被误判成"在结构内"
        var p = activePattern();
        Direction right = body.getCounterClockWise();
        BlockPos core = p.coreOffset();
        int dx = world.getX() - worldPosition.getX();
        int dy = world.getY() - worldPosition.getY();
        int dz = world.getZ() - worldPosition.getZ();
        int lx = dx * right.getStepX() + dz * right.getStepZ() + core.getX();
        int lz = dx * body.getStepX() + dz * body.getStepZ() + core.getZ();
        int ly = dy + core.getY();
        if (lx < 0 || lx >= p.width() || ly < 0 || ly >= p.height()
                || lz < 0 || lz >= p.depth()) {
            return false;
        }
        List<Block> expected = p.expectedAt(lx, ly, lz);
        if (expected == null) {
            return false;
        }
        Block assembler = com.ae2addon.init.ModBlocks.ASSEMBLER_CORE.get();
        return expected.contains(assembler);
    }

    /** 取控制器所在网络的存储；网络不可用返回 null */
    @Nullable
    private appeng.api.storage.MEStorage networkStorage() {
        try {
            var node = getMainNode().getNode();
            if (node != null && node.getGrid() != null) {
                return node.getGrid().getStorageService().getInventory();
            }
        } catch (RuntimeException ignored) {
            // 网格未就绪
        }
        return null;
    }

    /** 取料/放置用的操作来源（绑定到本方块，便于 AE2 记账） */
    private appeng.api.networking.security.IActionSource actionSource() {
        return appeng.api.networking.security.IActionSource.ofMachine(this);
    }

    /**
     * **一键成型**（sensei 第 3 阶段规格）。
     * <p>
     * 流程：已成型 → 直接提示"已成型，无需摆放"；否则
     * ① 确定结构朝向（已判定过就用记录的，否则 4 个朝向里找第一个"预检无冲突"的）；
     * ② 编制放置计划（模板里真实存在的格子，排除控制器自身与忽略格）；
     * ③ SIMULATE 核对材料 —— **缺料就报缺什么缺多少、一块都不放**；
     * ④ 预检冲突 —— 有非空气占位就**取消整个放置**、记下位置做红色高亮、聊天栏提示"放置被阻挡"；
     * ⑤ 按 y 从低到高、每 tick 一批快速放置。
     *
     * @return 是否成功**开始**构建（或本来就已经成型）
     */
    public boolean startBuild(@Nullable net.minecraft.world.entity.player.Player player) {
        if (level == null || level.isClientSide) {
            return false;
        }
        var lvl = (net.minecraft.server.level.ServerLevel) level;

        if (formed) {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State.DONE;
            buildMessage = "已成型，无需摆放";
            setChanged();
            return true;
        }
        var storage = networkStorage();
        if (storage == null) {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State.MISSING;
            buildMessage = "控制器未接入网络，无法取料";
            notifyPlayer(player, "§c" + buildMessage);
            setChanged();
            return false;
        }
        var src = actionSource();

        // Prefer the selected front (or the player's side), then try the other bodies.
        List<Direction> bodies = new ArrayList<>();
        Direction preferred = preferredBody(player);
        bodies.add(preferred);
        if (structureBody != null && structureBody != preferred) {
            bodies.add(structureBody);
        }
        for (int turn = 0; turn < 4; turn++) {
            Direction d = com.ae2addon.block.multiblock.StructureMatcher.rotateAroundY(Direction.SOUTH, turn);
            if (!bodies.contains(d)) {
                bodies.add(d);
            }
        }

        Map<Block, Long> missing = new LinkedHashMap<>();
        BlockPos firstConflict = null;
        for (Direction body : bodies) {
            missing.clear();
            var plan = com.ae2addon.block.multiblock.StructureBuilder.plan(
                    lvl, worldPosition, body, buildPattern(), storage, src, missing);

            // ③ 缺料：换朝向也解决不了，直接报完返回
            if (!missing.isEmpty()) {
                buildState = com.ae2addon.block.multiblock.StructureBuilder.State.MISSING;
                buildMessage = com.ae2addon.block.multiblock.StructureBuilder
                        .describeShortage(missing).getString();
                notifyPlayer(player, buildMessage);
                setChanged();
                return false;
            }
            // ④ 冲突预检
            BlockPos conflict = com.ae2addon.block.multiblock.StructureBuilder.findConflict(
                    lvl, worldPosition, body, plan, buildPattern());
            if (conflict != null) {
                if (firstConflict == null) firstConflict = conflict;
                continue;
            }
            // 可行 → 开工
            structureBody = body;
            buildJob = new com.ae2addon.block.multiblock.StructureBuilder.Job(plan);
            pushOutlineStateToClients();
            lastBuildPlaced = 0;
            lastBuildTotal = plan.size();
            buildState = buildJob.done()
                    ? com.ae2addon.block.multiblock.StructureBuilder.State.DONE
                    : com.ae2addon.block.multiblock.StructureBuilder.State.BUILDING;
            buildMessage = "";
            setChanged();
            return true;
        }

        buildState = com.ae2addon.block.multiblock.StructureBuilder.State.BLOCKED;
        buildMessage = "4 个朝向都有方块阻挡，放置已取消";
        if (firstConflict != null) {
            pendingConflictHighlight = firstConflict;
            buildMessage += "，第一处：(" + firstConflict.getX() + ", "
                    + firstConflict.getY() + ", " + firstConflict.getZ() + ")";
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.ae2addon.AE2Addon.NETWORK.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                        new com.ae2addon.network.IntegratedCpuConflictPacket(firstConflict, 100));
            }
        }
        notifyPlayer(player, "§c" + buildMessage);
        setChanged();
        return false;
    }

    /** 每 tick 推进一步构建（由方块类的 ticker 调） */
    public void tickBuild() {
        if (buildJob == null || level == null || level.isClientSide || structureBody == null) {
            return;
        }
        var lvl = (net.minecraft.server.level.ServerLevel) level;
        var storage = networkStorage();
        if (storage == null) {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State.MISSING;
            buildMessage = "控制器未接入网络，构建暂停";
            setChanged();
            return;
        }
        int placed = com.ae2addon.block.multiblock.StructureBuilder.placeBatch(
                lvl, worldPosition, structureBody, buildJob, buildPattern(), storage, actionSource());
        lastBuildPlaced = buildJob.placed();
        lastBuildTotal = buildJob.total();
        if (buildJob.done()) {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State.DONE;
            buildMessage = "结构已摆放完成，等待成型判定";
            buildJob = null;
        } else if (placed == 0) {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State.BLOCKED;
            buildMessage = "放置中断（位置被占或取不到材料）";
        } else {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State.BUILDING;
        }
        setChanged();
    }

    /** 构建任务 + 状态持久化（重登可续） */
    private void saveBuild(CompoundTag tag) {
        tag.putString("buildState", buildState.name());
        tag.putString("buildMessage", getBuildMessage());
        tag.putInt("lastBuildPlaced", lastBuildPlaced);
        tag.putInt("lastBuildTotal", lastBuildTotal);
        if (structureBody != null) {
            tag.putInt("structureBody", structureBody.ordinal());
        }
        tag.putBoolean("formVariantNoExpand", formVariant);   // 玩家选的形态（无拓展）
        tag.putInt("buildFacing", buildFacing);
        if (buildJob != null) {
            CompoundTag job = new CompoundTag();
            job.putInt("cursor", buildJob.cursor());
            job.putInt("total", buildJob.total());
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (var place : buildJob.queue()) {
                CompoundTag e = new CompoundTag();
                e.putInt("x", place.x());
                e.putInt("y", place.y());
                e.putInt("z", place.z());
                var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(place.block());
                e.putString("block", id == null ? "" : id.toString());
                list.add(e);
            }
            job.put("queue", list);
            tag.put("buildJob", job);
        }
    }

    private void loadBuild(CompoundTag tag) {
        try {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State
                    .valueOf(tag.getString("buildState"));
        } catch (IllegalArgumentException e) {
            buildState = com.ae2addon.block.multiblock.StructureBuilder.State.IDLE;
        }
        buildMessage = tag.getString("buildMessage");
        lastBuildPlaced = tag.getInt("lastBuildPlaced");
        lastBuildTotal = tag.getInt("lastBuildTotal");
        structureBody = tag.contains("structureBody")
                ? Direction.values()[tag.getInt("structureBody")] : null;
        formVariant = tag.getBoolean("formVariantNoExpand");   // 存量存档没有这个键 → false（含拓展）
        buildFacing = tag.contains("buildFacing")
                ? Math.max(-1, Math.min(3, tag.getInt("buildFacing"))) : -1;
        if (tag.contains("buildJob")) {
            CompoundTag job = tag.getCompound("buildJob");
            List<com.ae2addon.block.multiblock.StructureBuilder.Place> queue = new ArrayList<>();
            var list = job.getList("queue", 10);
            for (int i = 0; i < list.size(); i++) {
                var e = list.getCompound(i);
                var id = net.minecraft.resources.ResourceLocation.tryParse(e.getString("block"));
                if (id == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) {
                    continue;
                }
                queue.add(new com.ae2addon.block.multiblock.StructureBuilder.Place(
                        e.getInt("x"), e.getInt("y"), e.getInt("z"),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id)));
            }
            buildJob = queue.isEmpty() ? null
                    : new com.ae2addon.block.multiblock.StructureBuilder.Job(
                            queue, job.getInt("cursor"), job.getInt("total"));
        }
    }

    /**
     * 把网络侧展示数据推给附近客户端（限频 1 秒；AE2 自己的列表拿不到我们的数值）。
     * <p>
     * ⚠ 2026-09-25（Codex 核对时发现）：初值**不能**用 {@code Long.MIN_VALUE} ——
     * {@code now - Long.MIN_VALUE} 会**溢出成负数**，而守卫写的是 {@code < 20}，
     * 负数成立 → 直接 return → **第一次推送总是被吞掉**（要等下一次 20 tick 后才开始工作）。
     * 初值改用 0（`now - 0` 正常为正数）。
     */
    private long lastStatusPushTick = 0;

    /** 稳态诊断日志限频（5 秒）。初值同样用 0，理由见上。 */
    private long lastStatusLogTick = 0;

    private long statusEntryCalls;

    public void pushStatusToClients(boolean force) {
        long calls = ++statusEntryCalls;
        if (calls % 100 == 0) {
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][cpu-diag] push入口 calls={} levelNull={} clientSide={} gameTime={}",
                    calls, level == null, level != null && level.isClientSide,
                    level == null ? -1 : level.getGameTime());
        }
        if (level == null || level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        if (!force && now - lastStatusPushTick < 20) {
            return;
        }
        lastStatusPushTick = now;
        pushRingStateToClients();
        // 稳态诊断（2026-09-24 sensei：重新成型后 AE2 列表里并行数又不显示）：
        // 每 5 秒打一行"我现在对外报什么值"，用来确认数值到底有没有算出来。
        if (now - lastStatusLogTick >= 100) {
            lastStatusLogTick = now;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][cpu] 对外状态 pos={} formed={} lane数={} 线程={} 存储={} 主簇={}",
                    worldPosition, formed, allCpus().size(), getAcceleratorThreads(),
                    getStorageBytes(), getCluster() == null ? "null" : "有");
        }
        try {
            var packet = new com.ae2addon.network.IntegratedCpuStatusPacket(worldPosition,
                    allCpus().size(), getActiveJobCount(), getStorageBytes(),
                    getAcceleratorThreads(), formed);
            com.ae2addon.AE2Addon.NETWORK.send(
                    net.minecraftforge.network.PacketDistributor.NEAR.with(
                            () -> new net.minecraftforge.network.PacketDistributor.TargetPoint(
                                    worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                                    96, level.dimension())),
                    packet);
        } catch (RuntimeException ignored) {
            // 发不出去不影响游戏逻辑
        }
    }

    /** Keep ring visibility and orientation in sync for players up to 256 blocks away. */
    private void pushRingStateToClients() {
        pushRingStateToClients(formed);
    }

    private long lastRingDebugTick = Long.MIN_VALUE;

    private void pushRingStateToClients(boolean visible) {
        if (level == null || level.isClientSide) return;
        Direction sentBody = structureBody != null ? structureBody : Direction.SOUTH;
        long now = level.getGameTime();
        if (visible && (lastRingDebugTick == Long.MIN_VALUE || now - lastRingDebugTick >= 100)) {
            lastRingDebugTick = now;
            com.ae2addon.AE2Addon.LOGGER.info(
                    "[ae2addon][ring-debug] 服务端 pos={} bodySent={} structureBody={} outlineBody={} buildFacing={} formed={}",
                    worldPosition, sentBody, structureBody, outlineBody(), buildFacing, formed);
        }
        try {
            com.ae2addon.AE2Addon.NETWORK.send(
                    net.minecraftforge.network.PacketDistributor.NEAR.with(
                            () -> new net.minecraftforge.network.PacketDistributor.TargetPoint(
                                    worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                                    256, level.dimension())),
                    new com.ae2addon.network.IntegratedCpuRingPacket(worldPosition,
                            visible, sentBody));
        } catch (RuntimeException ignored) {
            // Visual sync must not affect controller behavior.
        }
    }

    /** Small state packet; clients derive the outline from the bundled structure resources. */
    public void pushOutlineStateToClients() {
        pushOutlineStateToClients(false);
    }

    private void pushOutlineStateToClients(boolean hide) {
        if (level == null || level.isClientSide) return;
        try {
            com.ae2addon.AE2Addon.NETWORK.send(
                    net.minecraftforge.network.PacketDistributor.NEAR.with(
                            () -> new net.minecraftforge.network.PacketDistributor.TargetPoint(
                                    worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                                    256, level.dimension())),
                    new com.ae2addon.network.IntegratedCpuOutlinePacket(worldPosition,
                            formed || hide, formVariant, outlineBody()));
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn("[ae2addon][cpu-outline] 状态同步失败: {}", e.toString());
        }
    }

    /** 把缺口清单写成一句人话（聊天栏提示用，最多列 6 种） */
    private void notifyPlayer(@Nullable net.minecraft.world.entity.player.Player player, String message) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(message));
        } else {
            com.ae2addon.util.ChatLog.info(level, worldPosition, message);
        }
    }

    /**
     * 在结构里找无限并行处理器（局部坐标里期望值含 {@code infinite_co_processing} 的格子）。
     * <p>
     * 只查**期望包含该方块的格子**（结构定义里就 1 格），所以开销可以忽略。
     */
    private boolean scanCoProcessing(Direction body) {
        // 并行处理器在两份结构里的局部坐标不同，必须按命中的那份扫
        var pattern = activePattern();
        Block want = com.ae2addon.init.ModBlocks.INFINITE_CO_PROCESSING.get();
        Direction right = body.getCounterClockWise();
        BlockPos core = pattern.coreOffset();
        for (int y = 0; y < pattern.height(); y++) {
            for (int z = 0; z < pattern.depth(); z++) {
                for (int x = 0; x < pattern.width(); x++) {
                    List<Block> exp = pattern.expectedAt(x, y, z);
                    if (exp == null || !exp.contains(want)) {
                        continue;
                    }
                    BlockPos world = worldPosition.offset(
                            right.getStepX() * (x - core.getX()) + body.getStepX() * (z - core.getZ()),
                            y - core.getY(),
                            right.getStepZ() * (x - core.getX()) + body.getStepZ() * (z - core.getZ()));
                    if (level.getBlockState(world).getBlock() == want) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 结构判定失败时，把「差在哪」告诉附近玩家（限频；玩家不一定在，所以只走 ChatLog） */
    private void notifyProblems() {
        if (lastStructureProblems.isEmpty()) {
            return;
        }
        lastProblemNotifyText = lastStructureProblems.get(0);
        recentProblemNotices.put(lastProblemNotifyText, level.getGameTime());
        com.ae2addon.util.ChatLog.err(level, worldPosition,
                "集成 CPU 结构不完整，已退回未成型。第一处问题：" + lastStructureProblems.get(0));
    }

    /** Content-aware suppression of repeated notices while a controller remains unformed. */
    private String lastProblemNotifyText;
    private final Map<String, Long> recentProblemNotices = new java.util.HashMap<>();

    private void notifyProblemsThrottled() {
        if (lastStructureProblems.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        String problem = lastStructureProblems.get(0);
        if (problem.equals(lastProblemNotifyText)) {
            return;
        }
        lastProblemNotifyText = problem;
        Long previous = recentProblemNotices.get(problem);
        if (previous != null && now - previous < 6000) return;
        recentProblemNotices.put(problem, now);
        com.ae2addon.util.ChatLog.info(level, worldPosition,
                "集成 CPU 尚未成型，第一处问题：" + problem);
    }

    /**
     * 创造模式「已成型」变体：直接给完整形态 ——
     * 结构成型时会由内部元件检测置位的并行处理器标志（{@link #setHasCoProcessing}）
     * 也一并置位，否则置了 formed 但 {@code getAcceleratorThreads()} 仍返回 0（没线程）。
     * <p>
     * ⚠ 2026-09-24 sensei 实测报障：「失手把集成型CPU的已成型状态也改成必须结构存在才能成型了」。
     * 根因是结构复检改成 3 秒定时轮询后，这种方块周围**本来就没结构** → 必然判失败 → 被打回未成型。
     * 修法：置 {@code creativeFormed} 标志，结构判定**不得推翻**它（见 {@link #reevaluateStructure()}）。
     */
    @Override
    public void applyCreativeFormed() {
        setHasCoProcessing(true);
        setFormed(true);
        creativeFormed = true;
        structureFormed = false;
    }

    /**
     * 在结构检测时设置是否包含无限并行处理器。
     */
    public void setHasCoProcessing(boolean has) {
        this.hasCoProcessing = has;
        setChanged();
    }

    public boolean hasCoProcessing() {
        return hasCoProcessing;
    }

    @Override
    public long getStorageBytes() {
        // 未成型 → 不贡献存储，无法接入网络
        // config cpuDisplayBytes（默认 Long.MAX_VALUE）：无限存储显示。
        // 单个核心方块相加不会溢出；若同簇混入其他贡献存储的
        // CraftingBlockEntity 会溢出为负（历史教训）。真实存储无限。
        return formed ? com.ae2addon.config.AE2AddonConfig.cpuDisplayBytes() : 0;
    }

    /**
     * 只有成型且包含并行处理器时才有线程。
     * Integer.MAX_VALUE−1：拉满账面并行度（AE2 内部 +1 后恰好 Integer.MAX_VALUE，不溢出）。
     * CraftingCpuLogicMixin 的时间片限流接管真实执行，线程数只是好看的数字，
     * 单 tick 实际只跑预算内的工作量，不会爆炸。
     * <p>
     * 兼容性保险（2026-08-21）：限流 mixin 注入被其他 mod（gtlcore/gtocore 等）
     * 干扰或 AE2 版本不兼容时，{@link com.ae2addon.mixin.CraftingCpuLogicMixin#ae2addon$isTimeSliceActive()}
     * 为 false → 回退保守线程数（16），避免无时间片保护的高线程单 tick 循环爆炸。
     */
    @Override
    public int getAcceleratorThreads() {
        if (!formed || !hasCoProcessing) {
            return 0;
        }
        if (!com.ae2addon.crafting.CraftingCompat.timeSliceActive) {
            return 16;
        }
        // config cpuDisplayThreads（0 = Integer.MAX_VALUE-1 拉满）：
        // 账面并行度显示值；CraftingCpuLogicMixin 的时间片限流接管真实执行，
        // 单 tick 实际只跑预算内的工作量，不会爆炸。
        return com.ae2addon.config.AE2AddonConfig.cpuDisplayThreads();
    }

    /**
     * 所有 CPU lane（主簇 + 虚拟 lane）。
     */
    public List<CraftingCPUCluster> allCpus() {
        var result = new ArrayList<CraftingCPUCluster>(virtualCpus.size() + 1);
        var primary = getCluster();
        if (primary != null && !primary.isDestroyed()) {
            result.add(primary);
        }
        for (var cpu : virtualCpus) {
            if (!cpu.isDestroyed()) {
                result.add(cpu);
            }
        }
        return result;
    }

    /**
     * **强制取消本机所有线程上的任务**（主簇 + 全部虚拟 lane），返回取消的数量。
     * <p>
     * ⚠ 2026-09-19（sensei：「删除后订单没有全部取消完，仍然会有线程在工作」）：
     * 界面上的「删除」原来只 `core.getCluster().cancelJob()` —— **只取消主簇**，
     * 几十条量子分裂 lane 上的任务一个都没动，所以删完还有线程在跑。
     * 这里走 {@link #allCpus()}（主簇 + 虚拟 lane），逐簇取消；
     * 取消会触发我们挂在 `CraftingCpuLogic.cancel` 上的回退钩子
     * （把该簇"已推送未合成"的材料退回网络，见 QianJiBE / InfiniteInterfaceBE.returnPushedFor）。
     */
    public int cancelAllLanes() {
        int cancelled = 0;
        for (var cpu : allCpus()) {
            try {
                boolean busy = cpu.isBusy();
                cpu.cancelJob();
                if (busy) {
                    cancelled++;
                }
            } catch (Throwable ignored) {
                // 单条 lane 异常不影响其余
            }
        }
        return cancelled;
    }

    /**
     * 创建虚拟 CPU lane（挂在本方块的网格节点上）。
     */
    private CraftingCPUCluster createVirtualCpu() {
        var cpu = new CraftingCPUCluster(worldPosition, worldPosition);
        var accessor = (CraftingCPUClusterAccessor) (Object) cpu;
        accessor.ae2addon$addBlockEntity(this);
        accessor.ae2addon$finishCluster();
        virtualCpus.add(cpu);
        return cpu;
    }

    /**
     * 每 tick lane 维护（2026-08-22）：确保空闲 lane 存在并注册进 CraftingService。
     * 由 CraftingCpuLogicMixin.beginDispatchBudget（tickCraftingLogic HEAD，每 tick
     * 对每个已注册簇触发）调用——updateCPUClusters 是事件驱动（updateList 脏标记），
     * 稳态运行时不触发，靠它建 lane 会饿死（sensei 实测：主簇忙时线程1不出现）。
     * <p>
     * ⚠ 2026-09-24 修「线程数一直是0」：**不再要求主簇存在**。
     * <p>
     * 原实现第一行是 {@code if (!formed || getCluster() == null) return;} —— 而我们的多方块结构
     * **永远不可能让 AE2 建出主簇**：AE2 的 {@code CraftingCPUCalculator.checkMultiblockScale}
     * 硬性要求簇包围盒 ≤16 格/轴，{@code verifyInternalStructure} 还要求**内部每一格都是
     * crafting 方块实体**（不能是空气/装饰）。31×53×41 且内部大空洞的结构两条都不满足
     * （javap 字节码确认：常量 bipush 16 + 逐格 isValid 检查）。
     * <p>
     * 所以主簇对本方块只是"锦上添花"：真正的算力走 {@link #allCpus()} 里的**量子分裂 lane**
     * （我们自己用 {@code CraftingCPUClusterAccessor} 造的，不经过 AE2 计算器）。
     * 结构判定通过就算成型，lane 由本方法与 {@code ensureOneIdleCpu()} 负责建。
     */
    public void refreshLanes() {
        if (!formed) {
            return;
        }
        ensureOneIdleCpu();
        var bridge = ae2addon$craftingBridge();
        if (bridge == null) {
            return;
        }
        for (var cpu : allCpus()) {
            if (!cpu.isDestroyed() && cpu.isActive()) {
                bridge.ae2addon$registerCpu(cpu);
            }
        }
    }

    /**
     * 保证常驻线程数（IDLE_LANE_TARGET 个 lane，含主簇），并回收多余空闲。
     * <p>
     * ⚠ 同 {@link #refreshLanes()}：**不再要求主簇存在**（我们的结构不可能建出 AE2 主簇，
     * 详见那里的注释）。主簇为 null 时，"主簇忙不忙"这段逻辑整体跳过，直接维护虚拟 lane。
     */
    public void ensureOneIdleCpu() {
        if (!formed) {
            return;
        }
        var primary = getCluster();
        if (primary == null || primary.isDestroyed()) {
            // 没有主簇：直接按"主簇忙"处理 —— 保证 IDLE_LANE_TARGET 个空闲 lane，
            // 并回收超额的空闲 lane（那些 lane 自己会注册进 CraftingService）
            ensureIdleLaneTarget();
            return;
        }
        if (!primary.isBusy()) {
            // 主簇空闲：清空空闲虚拟 lane（只留主簇），避免 16 个空转
            reapIdleLanes(0);
            return;
        }
        ensureIdleLaneTarget();
    }

    /** 把虚拟 lane 维持到 IDLE_LANE_TARGET 个空闲，并回收超标的（主簇有无都能用） */
    private void ensureIdleLaneTarget() {
        // ⚠ 2026-09-24 sensei：「始终空闲虚拟并行数为什么不按照 config 文件来」。
        //   config 项 idleLaneTarget 默认 16（本机配置成 2），语义 = **常驻多少个空闲 lane**。
        //   这里显式读 IDLE_LANE_TARGET（它由 AE2AddonConfig.applyConfig() 在配置变更时同步），
        //   并要求至少 1（否则 CPU 会没有可用 lane）。
        int target = Math.max(1, IDLE_LANE_TARGET);
        int idleCount = 0;
        for (var cpu : virtualCpus) {
            if (!cpu.isBusy() && !cpu.isDestroyed()) {
                idleCount++;
            }
        }
        while (idleCount < target) {
            createVirtualCpu();
            idleCount++;
        }
        reapIdleLanes(target);
        logLaneState(target, idleCount);
    }

    /** 诊断用：上次记录的 (target, idle)（避免每 tick 刷日志） */
    private int lastLoggedLaneTarget = -1;
    private int lastLoggedIdle = -1;

    /** lane 数量变化时打一行，确认 config 是否被采纳 */
    private void logLaneState(int target, int idle) {
        int actualIdle = 0;
        for (var cpu : virtualCpus) {
            if (!cpu.isBusy() && !cpu.isDestroyed()) {
                actualIdle++;
            }
        }
        if (target == lastLoggedLaneTarget && actualIdle == lastLoggedIdle) {
            return;
        }
        lastLoggedLaneTarget = target;
        lastLoggedIdle = actualIdle;
        com.ae2addon.AE2Addon.LOGGER.info(
                "[ae2addon][cpu] lane 维护 pos={} config空闲目标={} 实际空闲={} lane总数={} 主簇={}",
                worldPosition, target, actualIdle, allCpus().size(),
                getCluster() == null ? "null" : "有");
    }

    /** 回收空闲虚拟 lane，保留前 keepIdle 个空闲（按列表顺序）。
     *  2026-08-27：增加对「isBusy 假阳性」lane 的强制回收——批次 link 取消后
     *  AE2 job 清理可能延迟/丢失，cluster.isBusy() 永久 true → lane 泄漏
     *  （sensei 实测：恢复订单取消后 lane 累积到 64+ 个）。
     *  通过公开 API getLastLink().isCanceled() 判断任务已取消 → 强制 cancelJob 后回收。 */
    private void reapIdleLanes(int keepIdle) {
        var bridge = ae2addon$craftingBridge();
        int idleKept = 0;
        Iterator<CraftingCPUCluster> iterator = virtualCpus.iterator();
        while (iterator.hasNext()) {
            var cpu = iterator.next();
            if (cpu.isDestroyed()) {
                iterator.remove();
                if (bridge != null) {
                    bridge.ae2addon$unregisterCpu(cpu);
                }
                continue;
            }
            if (!cpu.isBusy()) {
                if (idleKept < keepIdle) {
                    idleKept++;
                    continue;
                }
                iterator.remove();
                if (bridge != null) {
                    bridge.ae2addon$unregisterCpu(cpu);
                }
                continue;
            }
            // isBusy 但底层任务已取消（link canceled）：强制清理后回收，防泄漏
            try {
                var link = cpu.craftingLogic.getLastLink();
                if (link != null && link.isCanceled()) {
                    cpu.cancelJob();
                    iterator.remove();
                    if (bridge != null) {
                        bridge.ae2addon$unregisterCpu(cpu);
                    }
                }
            } catch (RuntimeException ignored) {
                // 反射/状态读取失败：保留 lane，下轮再试
            }
        }
    }

    /**
     * 按需创建 lane 并立即注册（2026-08-22：批次无空闲 CPU 时调用，
     * 每批一个 lane 全并行——「int 级别」并行度，但按需创建不会像
     * IDLE_LANE_TARGET=Integer.MAX_VALUE 那样无界建到 OOM）。
     * 已有空闲 lane 时直接返回（不新建）。
     */
    public CraftingCPUCluster createAndRegisterLane() {
        var cpu = getOrCreateIdleCpu();
        if (cpu != null && !cpu.isDestroyed() && cpu.isActive()) {
            var bridge = ae2addon$craftingBridge();
            if (bridge != null) {
                bridge.ae2addon$registerCpu(cpu);
            }
            return cpu;
        }
        return null;
    }

    /**
     * 获取空闲 lane；没有则创建。
     */
    public CraftingCPUCluster getOrCreateIdleCpu() {
        if (!formed || getCluster() == null) {
            return null;
        }
        for (var cpu : allCpus()) {
            if (!cpu.isBusy() && !cpu.isDestroyed()) {
                return cpu;
            }
        }
        return createVirtualCpu();
    }

    /**
     * 虚拟 CPU lane 手动移除（2026-08-22 起不再由 done() 自动调用——
     * 改为常驻空闲 lane，由 {@link #ensureOneIdleCpu} 的回收逻辑在
     * 主簇空闲时清理）。保留供手动管理/未来使用。
     * <p>
     * 从 lane 列表移除，并从 CraftingService 的 craftingCPUClusters 集合剔除，
     * 界面线程列表立即消失。主簇（cpu == getCluster()）不隐藏。
     */
    public void removeVirtualCpu(CraftingCPUCluster cpu) {
        if (cpu == null || cpu == getCluster()) {
            return;
        }
        virtualCpus.remove(cpu);
        var bridge = ae2addon$craftingBridge();
        if (bridge != null) {
            bridge.ae2addon$unregisterCpu(cpu);
        }
    }

    /**
     * 从 AE 网格拿到 CraftingService 桥接（用于注册/注销虚拟 lane）。
     * 网格不可用时返回 null（下次 refresh 会重建集合）。
     */
    private IntegratedCraftingServiceBridge ae2addon$craftingBridge() {
        try {
            var node = getMainNode().getNode();
            if (node != null && node.getGrid() != null
                    && node.getGrid().getCraftingService()
                            instanceof IntegratedCraftingServiceBridge bridge) {
                return bridge;
            }
        } catch (RuntimeException ignored) {
            // 网格未就绪
        }
        return null;
    }

    public int getCpuLaneCount() {
        return allCpus().size();
    }

    public int getActiveJobCount() {
        int active = 0;
        for (var cpu : allCpus()) {
            if (cpu.isBusy()) {
                active++;
            }
        }
        return active;
    }

    // ── AE 网格控制：**始终联网**（2026-09-24 改）──

    /**
     * ⚠ 2026-09-24 sensei：「保证从始至终控制器可以接入网络」。
     * <p>
     * 旧实现是 {@code if (!formed) return null} —— 未成型就完全不联网。但**一键成型要从
     * 已连接的网络里取 2223 块材料**，未成型时若断网，控制器根本拿不到料，功能无法启动。
     * 现在改成**始终联网**：
     * <ul>
     *   <li>未成型：仍在网络里可见、可被访问，但 {@link #getStorageBytes()} /
     *       {@link #getAcceleratorThreads()} 都返回 0 → **CPU 功能禁用**（不贡献存储、没有线程）；</li>
     *   <li>已成型：正常贡献存储与线程。</li>
     * </ul>
     * 另一个必须联网的理由：挖掉结构方块时要把任务材料退回网络，而退回靠的是簇的网格节点
     * （{@code QianJiBE.returnPushedFor} / {@code InfiniteInterfaceBE.returnPushedFor}）——
     * 节点不在就等于退料失败。
     */
    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return super.getGridNode(dir);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return super.getCableConnectionType(dir);
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("formed", formed);
        tag.putBoolean("hasCo", hasCoProcessing);
        // 成型来源（2026-09-24）：结构判出来的 vs 创造模式变体给的，必须持久化，
        // 否则重登后创造变体会被结构复检打回未成型。
        tag.putBoolean("structureFormed", structureFormed);
        tag.putBoolean("creativeFormed", creativeFormed);
        saveBuild(tag);
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        formed = tag.getBoolean("formed");
        hasCoProcessing = tag.getBoolean("hasCo");
        boolean hasSource = tag.contains("structureFormed") || tag.contains("creativeFormed");
        structureFormed = tag.getBoolean("structureFormed");
        creativeFormed = tag.getBoolean("creativeFormed");
        // 旧存档兼容：只有 formed、没有来源标志 → 按"结构成型"处理（保持旧行为）
        if (!hasSource && formed) {
            structureFormed = true;
        }
        loadBuild(tag);
    }

    /**
     * 网络工具 / ME 控制器里机器的**图标与身份**（2026-09-15 sensei 陈年问题）。
     * <p>
     * AE2 的 `AENetworkBlockEntity`/`CraftingBlockEntity` 用本方法给网格节点设
     * `visualRepresentation`；默认实现取「方块对应的物品」——我们有几个方块是
     * **两个物品共用一个方块 id**（本体 + 已成型变体）→ `asItem()` 会拿到已成型变体甚至空气；
     * 继承 CraftingUnitBlock 的（集成CPU/装配处理器）还会被 type 名带成「256k 合成存储器」。
     * 这里显式返回**本方块的物品本体**。
     */
    @Override
    public net.minecraft.world.item.Item getItemFromBlockEntity() {
        return com.ae2addon.init.ModItems.INTEGRATED_CPU_ITEM.get();
    }

    /**
     * ⚠ 2026-09-24 sensei 实测：「线缆无法连接未成型的集成CPU」。
     * <p>
     * 根因：AE2 的 {@code CraftingBlockEntity} **自己**在 {@code getGridConnectableSides} 里按
     * 「是否已成型 / 簇形态」决定暴露哪几面，未成型时返回空集 —— 于是电缆接不上去。
     * 光改 {@link #getGridNode} 不够（那只管"节点存不存在"，管不了"允许从哪一面连"）。
     * <p>
     * 覆写成**永远暴露 6 面**：未成型也要能接电缆（一键成型要靠网络取料）。
     * AE2 会把本集合与邻居的可连接面求交集，所以全开不会造成异常连接。
     */
    @Override
    public java.util.Set<Direction> getGridConnectableSides(
            appeng.api.orientation.BlockOrientation orientation) {
        return java.util.EnumSet.allOf(Direction.class);
    }
}
