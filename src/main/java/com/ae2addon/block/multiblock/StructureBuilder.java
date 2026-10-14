package com.ae2addon.block.multiblock;

import com.ae2addon.AE2Addon;
import com.ae2addon.util.ChatLog;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集成 CPU 多方块**一键成型**构建器（2026-09-24 sensei 第 3 阶段）。
 * <p>
 * 规则（全部由 sensei 指定）：
 * <ul>
 *   <li><b>快速放置、从下往上</b>：按局部 y 升序排队，每 tick 放一批（{@link #BLOCKS_PER_TICK}）；</li>
 *   <li><b>取料</b>：从控制器所在 ME 网络取（{@code IStorageService.extract}）。
 *       先全程 SIMULATE 统计缺口 —— 缺料就**中止并在聊天栏报出缺什么、缺多少**，一块都不放；</li>
 *   <li><b>预检冲突</b>：目标位置必须能放 —— 空气、或本来就是该格期望的方块（已完成的部分跳过）。
 *       一旦有非空气占位，**取消整个放置**，记录位置（交给方块类做红色方框高亮）并聊天栏提示"放置被阻挡"；</li>
 *   <li><b>续建</b>：已摆好的格子自动跳过，再次点击从断点继续；</li>
 *   <li><b>已成型</b>：直接提示"已成型，无需摆放"。</li>
 * </ul>
 * 忽略格（线缆那种）不放置也不判定 —— 见 {@code IntegratedCpuStructure.Pattern#isIgnored}。
 */
public final class StructureBuilder {

    /** 每 tick 放置多少块（快速放置） */
    private static final int BLOCKS_PER_TICK = 256;

    private StructureBuilder() {
    }

    /** 队列里的一项：局部坐标 + **已选定的**要放哪个方块（从该格可接受的方块里挑第一个能在网络里找到的） */
    public record Place(int x, int y, int z, Block block) {}

    /** 构建任务：不可变计划 + 游标。存进 BE 的 NBT，重登可续。 */
    public static final class Job {
        /** 从下往上、同层按 z 再按 x 排好序 */
        private final List<Place> queue;
        /** 已处理到的下标 */
        private int cursor;
        /** 开始时总数（进度条用） */
        private final int total;

        public Job(List<Place> queue) {
            this.queue = List.copyOf(queue);
            this.cursor = 0;
            this.total = queue.size();
        }

        /** 反序列化用（带游标） */
        public Job(List<Place> queue, int cursor, int total) {
            this.queue = List.copyOf(queue);
            this.cursor = Math.max(0, Math.min(cursor, this.queue.size()));
            this.total = total;
        }

        public int total() {
            return total;
        }

        public int placed() {
            return cursor;
        }

        public boolean done() {
            return cursor >= queue.size();
        }

        public int remaining() {
            return queue.size() - cursor;
        }

        public List<Place> queue() {
            return queue;
        }

        public int cursor() {
            return cursor;
        }
    }

    /** 给界面/聊天栏用的一句话状态 */
    public enum State {
        IDLE("空闲"),
        BUILDING("正在放置"),
        BLOCKED("放置被阻挡"),
        MISSING("缺材料"),
        DONE("已完成");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 干跑结果：缺口清单（方块 → 缺多少） */
    public record Shortage(Block block, long missing) {}

    /**
     * 编制**放置计划**：模板里真实存在、排除控制器自身与忽略格；
     * 每格从"可接受的方块"里挑**第一个能在该网络里找到**的（先按库存、可选替换项顺序）。
     * <p>
     * 挑不出来的格子（网络里一个可接受的方块都没有）会进 {@code unresolved}，由调用方报缺料。
     *
     * @param body 结构朝向（局部 +z 轴的朝向）
     */
    public static List<Place> plan(ServerLevel level, BlockPos corePos, Direction body,
                                   appeng.api.storage.MEStorage storage,
                                   appeng.api.networking.security.IActionSource src,
                                   Map<Block, Long> missingOut) {
        return plan(level, corePos, body, IntegratedCpuStructure.get(), storage, src, missingOut);
    }

    /**
     * 同 {@link #plan}，但用**指定的结构定义**（2026-09-25：无拓展单元结构也要能一键成型）。
     * <p>
     * ⚠ 必须传"判定命中的那一份"：含拓展版 31×53×41、无拓展版 27×44×42 —— 拿错会往结构范围外放方块。
     */
    public static List<Place> plan(ServerLevel level, BlockPos corePos, Direction body,
                                   IntegratedCpuStructure.Pattern p,
                                   appeng.api.storage.MEStorage storage,
                                   appeng.api.networking.security.IActionSource src,
                                   Map<Block, Long> missingOut) {
        Direction right = body.getCounterClockWise();
        BlockPos core = p.coreOffset();
        List<Place> out = new ArrayList<>();

        // 先按 y 升序把格子排好（从下往上），再决定每格放哪个方块
        List<IntegratedCpuStructure.PlanCell> cells = new ArrayList<>(p.plan());
        cells.sort(Comparator.comparingInt(IntegratedCpuStructure.PlanCell::y)
                .thenComparingInt(IntegratedCpuStructure.PlanCell::z)
                .thenComparingInt(IntegratedCpuStructure.PlanCell::x));

        Map<Block, Integer> picked = new HashMap<>();
        // 同物品多形态候选表的缓存（见 candidateKeys 注释）：只对"精确 key 取不到"的方块才真去枚举
        Map<Block, List<AEItemKey>> keyCache = new HashMap<>();

        for (var cell : cells) {
            // 该格可接受的方块里，挑第一个"网络里有货"的；都要看替换项
            Block chosen = null;
            for (Block candidate : candidatesFor(cell)) {
                Item item = candidate.asItem();
                if (item == net.minecraft.world.item.Items.AIR) {
                    continue;   // 没有物品形态的方块不能放置
                }
                if (availableTotal(storage, candidate, 1, src, keyCache) > 0) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) {
                // 网络里一个都没有 → 记缺口（按该格的首选方块报）
                Block first = candidatesFor(cell).isEmpty() ? null : candidatesFor(cell).get(0);
                if (first != null) {
                    missingOut.merge(first, 1L, Long::sum);
                }
                continue;
            }
            picked.merge(chosen, 1, Integer::sum);
            out.add(new Place(cell.x(), cell.y(), cell.z(), chosen));
        }

        // 库存够不够（按 SIMULATE 精确核对每种方块的总需求）
        for (var e : picked.entrySet()) {
            long have = availableTotal(storage, e.getKey(), e.getValue(), src, keyCache);
            if (have < e.getValue()) {
                missingOut.merge(e.getKey(), e.getValue() - have, Long::sum);
                continue;
            }
            // ★ 取证日志（2026-10-02）：这份材料是不是**部分要靠非精确形态**才够？
            // 是 → 玩家会看到"明明有一堆却要挑形状"的现象，这行日志就是他实机时的证据。
            long plainOnly = storage.extract(AEItemKey.of(e.getKey()), e.getValue(),
                    Actionable.SIMULATE, src);
            if (plainOnly < e.getValue()) {
                AE2Addon.LOGGER.info(
                        "[ae2addon][结构] 材料 {} 需要 {} 个：无 NBT 形态只有 {} 个 → 其余取自同物品的其它 NBT 形态",
                        e.getKey().getName().getString(), e.getValue(), plainOnly);
            }
        }
        return out;
    }

    // ── 结构材料的「同物品多形态」容忍（2026-10-02 sensei 报的 bug）────────────
    //
    // 症状：一网络满满的致密能源元件，一键成型却报**缺料**；只有"放在世界上过"的那种才认。
    //
    // 根因（已用 javap 逐条核对 AE2 15.4.11 字节码，不是猜）：
    //   · AE2 的物品 key `AEItemKey` 是 **item + tag + caps 全程精确匹配**，
    //     且 `equals` 对 tag/caps 用的是 **InternedTag 实例引用比较**（`if_acmpne`）；
    //   · `AEItemKey.of(ItemLike)` → `InternedTag.of(null)` → 共用 **EMPTY 单例**（tag = null）；
    //   · 而一个**非 null 但空**的 CompoundTag（NBT 里的 `tag:{}`）会走另一条分支
    //     `new InternedTag(空tag)` → **另一个实例**，`equals` 比 `tag` 字段：null vs {} ⇒ **false**。
    //   ⇒ 网络里存的是"带空 tag"的那种时，`AEItemKey.of(block)`（本类原先唯一的取料 key）**永远取不到**，
    //     而方块挖出/新合成的干净栈是 tag=null ⇒ 认得。
    //
    // 修法：取料/核对时把**同一物品的所有 key 形态**都算候选，使用优先级
    //       ① tag=null（精确，老行为）→ ② 空 tag（等价于没数据）→ ③ 带内容的 NBT（最后才动，打警告）。
    // 性能：常见情况（①有货）走原路径、**零额外开销**；只有①取不到时才枚举一次网络物品表，
    //       且每次 plan()/placeBatch() 内按方块缓存，不会每格枚举。

    /** NBT 是否"没有数据"（null 或空复合都算） */
    private static boolean blankTag(@Nullable CompoundTag tag) {
        return tag == null || tag.isEmpty();
    }

    /** 该方块在网络里的候选 key 形态（[0] 恒为精确的 `AEItemKey.of(block)`） */
    private static List<AEItemKey> candidateKeys(MEStorage storage, Block block) {
        AEItemKey plain = AEItemKey.of(block);
        List<AEItemKey> blank = new ArrayList<>();
        List<AEItemKey> risky = new ArrayList<>();
        if (plain == null) {
            return List.of();
        }
        Item item = plain.getItem();
        try {
            for (var entry : storage.getAvailableStacks()) {
                if (!(entry.getKey() instanceof AEItemKey key)) {
                    continue;
                }
                if (key.getItem() != item || key.equals(plain)) {
                    continue;
                }
                if (blankTag(key.getTag())) {
                    blank.add(key);      // 空 tag：与 tag=null 实质等价
                } else {
                    risky.add(key);      // 真的带数据（例如充过电）→ 排最后，动用时打警告
                }
            }
        } catch (RuntimeException e) {
            // 列不全就退回"只认精确 key"（老行为），绝不让结构构建因此崩掉
            AE2Addon.LOGGER.warn("[ae2addon][结构] 枚举网络物品表失败，退回精确 key：{}", e.toString());
            return List.of(plain);
        }
        List<AEItemKey> out = new ArrayList<>(1 + blank.size() + risky.size());
        out.add(plain);
        out.addAll(blank);
        out.addAll(risky);
        return out;
    }

    /** 取候选表（每次 plan/placeBatch 内按方块缓存，避免逐格枚举） */
    private static List<AEItemKey> keysFor(MEStorage storage, Block block,
                                           Map<Block, List<AEItemKey>> cache) {
        return cache.computeIfAbsent(block, b -> candidateKeys(storage, b));
    }

    /** 网络里该方块（含其它 NBT 形态）**合计**能满足 need 的量 —— SIMULATE，不扣料 */
    private static long availableTotal(MEStorage storage, Block block, long need,
                                       IActionSource src, Map<Block, List<AEItemKey>> cache) {
        long total = 0;
        for (AEItemKey key : keysFor(storage, block, cache)) {
            total += storage.extract(key, need, Actionable.SIMULATE, src);
            if (total >= need) {
                break;
            }
        }
        return total;
    }

    /** 已就"动用了带 NBT 形态"警告过的方块（会话内每方块只警告一次，防刷屏） */
    private static final Set<Block> WARNED_NBT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 真取 1 个：按候选优先级试，返回**实际取到料的那把 key**（取不到 = null）。
     * <p>
     * 返回 key 而不是 boolean —— 回退时要"取哪种还哪种"，否则会把带 NBT 的换成无 NBT 的（凭空改物品形态）。
     */
    @Nullable
    private static AEItemKey extractOne(MEStorage storage, Block block, IActionSource src,
                                        Map<Block, List<AEItemKey>> cache) {
        for (AEItemKey key : keysFor(storage, block, cache)) {
            if (storage.extract(key, 1, Actionable.MODULATE, src) < 1) {
                continue;
            }
            if (!blankTag(key.getTag()) && WARNED_NBT.add(block)) {
                AE2Addon.LOGGER.warn(
                        "[ae2addon][结构] 取料动用了带 NBT 的形态：方块={}（同物品已无干净形态，"
                                + "该 NBT 数据在放置时会丢失）",
                        block.getName().getString());
            }
            return key;
        }
        return null;
    }

    /** 某格可接受的方块（含可替换项，已展开） */
    private static List<Block> candidatesFor(IntegratedCpuStructure.PlanCell cell) {
        List<Block> list = new ArrayList<>(cell.accepted());
        // 展开可替换项（与判定逻辑一致：模板里的"键"可以换成替代方块）
        for (Block b : cell.accepted()) {
            list.addAll(IntegratedCpuStructure.replacementsOf(b));
        }
        return list;
    }

    /**
     * 预检：计划里每一格现在能不能放。
     * <p>
     * 能放 = 空气，或**已经是该格可接受的方块**（续建时跳过已完成的部分）。
     * 否则返回第一处冲突的位置（调用方负责红色高亮 + 聊天栏提示）。
     */
    @Nullable
    public static BlockPos findConflict(ServerLevel level, BlockPos corePos, Direction body, List<Place> plan) {
        return findConflict(level, corePos, body, plan, IntegratedCpuStructure.get());
    }

    /** 同 {@link #findConflict}，但用指定的结构定义（无拓展版尺寸不同，判"已是该格可接受方块"必须用对的 pattern）。 */
    @Nullable
    public static BlockPos findConflict(ServerLevel level, BlockPos corePos, Direction body, List<Place> plan,
                                        IntegratedCpuStructure.Pattern p) {
        Direction right = body.getCounterClockWise();
        BlockPos core = p.coreOffset();
        for (Place place : plan) {
            BlockPos pos = worldPos(corePos, right, body, core, place);
            if (pos.equals(corePos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() == place.block()) {
                continue;
            }
            // 本来就是该格可接受的别的方块（比如替换项）→ 也算已完成，不算冲突
            var cell = p.expectedAt(place.x(), place.y(), place.z());
            if (cell != null && IntegratedCpuStructure.matches(state, cell)) {
                continue;
            }
            return pos;
        }
        return null;
    }

    /**
     * 执行一小批放置：从游标开始最多放 {@link #BLOCKS_PER_TICK} 块。
     * <p>
     * 每块都是"先从网络真取 1 个 → 放下去"；放不下去就把材料塞回网络（不吞料）。
     *
     * @return 本次实际放置的块数
     */
    public static int placeBatch(ServerLevel level, BlockPos corePos, Direction body, Job job,
                                 appeng.api.storage.MEStorage storage,
                                 appeng.api.networking.security.IActionSource src) {
        return placeBatch(level, corePos, body, job, IntegratedCpuStructure.get(), storage, src);
    }

    /** 同 {@link #placeBatch}，但用指定的结构定义（世界坐标换算必须与 plan() 用同一份 pattern）。 */
    public static int placeBatch(ServerLevel level, BlockPos corePos, Direction body, Job job,
                                 IntegratedCpuStructure.Pattern p,
                                 appeng.api.storage.MEStorage storage,
                                 appeng.api.networking.security.IActionSource src) {
        Direction right = body.getCounterClockWise();
        BlockPos core = p.coreOffset();
        int placed = 0;
        Map<Block, List<AEItemKey>> keyCache = new HashMap<>();

        while (placed < BLOCKS_PER_TICK && !job.done()) {
            Place place = job.queue().get(job.cursor());
            BlockPos pos = worldPos(corePos, right, body, core, place);

            // 已经对了 → 直接跳过（续建）
            if (!pos.equals(corePos)) {
                BlockState current = level.getBlockState(pos);
                var cell = p.expectedAt(place.x(), place.y(), place.z());
                if (current.getBlock() == place.block()
                        || (cell != null && IntegratedCpuStructure.matches(current, cell))) {
                    job.cursor++;
                    continue;
                }
                if (!current.isAir() && !current.canBeReplaced()) {
                    // 位置被别的东西占了（预检之后被人放了方块）→ 停下，交给复检报冲突
                    return placed;
                }
            }

            // 取料：容忍同物品的其它 NBT 形态（见 candidateKeys 注释）
            AEItemKey key = extractOne(storage, place.block(), src, keyCache);
            if (key == null) {
                return placed;   // 取不到料（网络被抽干/断网）→ 停下，下次再试
            }
            BlockState state = place.block().defaultBlockState();
            boolean ok = level.setBlock(pos, state, 3);
            if (!ok) {
                storage.insert(key, 1, Actionable.MODULATE, src);   // 放不进去回料（按原形态还）
                return placed;
            }
            job.cursor++;
            placed++;
        }
        return placed;
    }

    /** 局部坐标 → 世界坐标（与 {@link StructureMatcher} 同一套公式） */
    private static BlockPos worldPos(BlockPos corePos, Direction right, Direction body,
                                     BlockPos core, Place place) {
        return corePos.offset(
                right.getStepX() * (place.x() - core.getX()) + body.getStepX() * (place.z() - core.getZ()),
                place.y() - core.getY(),
                right.getStepZ() * (place.x() - core.getX()) + body.getStepZ() * (place.z() - core.getZ()));
    }

    /**
     * 把缺口清单写成一句人话（聊天栏提示用，最多列 6 种）。
     * <p>
     * 2026-10-11（sensei）：文案里**点明是"网络里"缺** —— 料源只有控制器的 ME 网络、
     * **不看玩家背包**（见 {@link #plan} 的 storage 参数与 {@link #extractOne}），
     * 不写清的话，背包里明明有货的玩家会以为算错了。
     */
    public static Component describeShortage(Map<Block, Long> missing) {
        var sb = new StringBuilder("§c缺少结构材料（网络里）：");
        int n = 0;
        for (var e : missing.entrySet()) {
            if (n++ >= 6) {
                sb.append("§c…等 ").append(missing.size()).append(" 种");
                break;
            }
            if (n > 1) {
                sb.append("§7, ");
            }
            sb.append("§f").append(e.getKey().getName().getString())
                    .append("§7×").append(e.getValue());
        }
        return Component.literal(sb.toString());
    }
}
