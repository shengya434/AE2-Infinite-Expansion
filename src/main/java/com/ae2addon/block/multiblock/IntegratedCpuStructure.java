package com.ae2addon.block.multiblock;

import com.ae2addon.AE2Addon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 集成型 CPU 多方块**结构定义**（2026-09-24）。
 * <p>
 * 结构不再写死在 Java 里，而是放在资源文件
 * {@code data/ae2addon/integrated_cpu_structure.txt}（由 {@code tools/gen_structure_def.py}
 * 从 Building Gadgets 2 模板生成）——以后 sensei 改结构，只要重新导一份模板、重跑生成器，
 * 不用碰 Java 代码。
 * <p>
 * 文件格式：
 * <pre>
 *   size 16 44 34          # width height depth（局部坐标 x/y/z 的格数）
 *   core 11 20 18          # 控制器在结构里的局部坐标
 *   block h ae2:crafting_unit[,ae2:xxx]   # 符号 → 方块（逗号分隔 = 可互换，任一命中即算匹配）
 *   layers
 *   layer 0
 *   ....gggg....           # 每层 depth 行、每行 width 字符；'.' = 必须是空气
 * </pre>
 * 局部坐标 → 世界坐标由 {@link StructureMatcher} 负责（4 个水平朝向都认）。
 * <p>
 * 判定只比较**方块**、不比较方块状态：`ae2:dense_energy_cell` 的 fullness、
 * `quartz_fixture` 的 facing 之类全都不参与（2026-09-24 sensei：状态是填充时 NBT 没弄好）。
 */
public final class IntegratedCpuStructure {

    /** 资源路径 */
    private static final String RESOURCE = "/data/ae2addon/integrated_cpu_structure.txt";

    /** 模式内容（懒加载一次，客户端/服务端共用） */
    private static volatile Pattern pattern;

    private IntegratedCpuStructure() {
    }

    /**
     * 模式：尺寸 + 核心局部坐标 + 符号方块表 + 字符画。
     *
     * @param width      局部 x 方向格数
     * @param height     局部 y 方向格数
     * @param depth      局部 z 方向格数
     * @param coreOffset 控制器自身的局部坐标（该处在字符画里是 '.'，判定时跳过）
     * @param groups     符号 → 可接受的方块（可为多个，任一命中即算匹配）
     * @param rows       每层 depth 行；整体按 y 升序，一层占 depth 行
     */
    public record Pattern(int width, int height, int depth, BlockPos coreOffset,
                          Map<Character, List<Block>> groups, List<String> rows) {

        /** 各个 Pattern 独立缓存；新增预览变体不能覆盖判定所用的主结构计划。 */
        private static final Map<Pattern, List<PlanCell>> PLAN_CACHES =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

        /** 同上：实际占用范围的缓存（2026-09-25 加，供世界轮廓用） */
        private static final Map<Pattern, Extent> EXTENT_CACHES =
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

        /** 某层某行（局部 y、局部 z）的字符画 */
        public String row(int y, int z) {
            return rows.get(y * depth + z);
        }

        /** 该格的字符 */
        public char symbolAt(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
                return '.';
            }
            String r = row(y, z);
            return x < r.length() ? r.charAt(x) : '.';
        }

        /**
         * 忽略格（字符 {@code ~}）：**不参与判定、也不放置**。
         * <p>
         * 2026-09-24：v4 模板里带了 {@code ae2:cable_bus}（线缆）。线缆的 blockstate 是**动态**的
         * （按连接情况变），照原样判永远判不过；它也不是玩家该"搭"出来的方块。
         * 这种格子就当不存在：既不算必须空气，也不算必须某方块 —— 有也行、没有也行。
         */
        public boolean isIgnored(int x, int y, int z) {
            return symbolAt(x, y, z) == '~';
        }

        /**
         * 局部坐标期望的方块组；{@code null} = 必须是空气（或忽略格）。
         */
        @Nullable
        public List<Block> expectedAt(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
                return null;
            }
            char ch = symbolAt(x, y, z);
            if (ch == '.' || ch == '~') {
                return null;
            }
            return groups.get(ch);
        }

        /**
         * **判定计划**：模板里真实存在的方块格（不含空气格、不含忽略格、不含控制器自身那格）。
         * <p>
         * 每次判定都遍历它 —— 只花 2248 次查询，而不是整个包围盒的 6.8 万格。
         * 懒加载 + 缓存（一份结构只算一次）。
         */
        public List<PlanCell> plan() {
            List<PlanCell> cached = PLAN_CACHES.get(this);
            if (cached != null) {
                return cached;
            }
            List<PlanCell> list = new ArrayList<>();
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    String r = row(y, z);
                    for (int x = 0; x < width && x < r.length(); x++) {
                        char ch = r.charAt(x);
                        if (ch == '.' || ch == '~') {
                            continue;
                        }
                        List<Block> accepted = groups.get(ch);
                        if (accepted == null || accepted.isEmpty()) {
                            continue;
                        }
                        if (coreOffset.getX() == x && coreOffset.getY() == y && coreOffset.getZ() == z) {
                            continue;   // 控制器自身
                        }
                        list.add(new PlanCell(x, y, z, accepted));
                    }
                }
            }
            cached = List.copyOf(list);
            PLAN_CACHES.put(this, cached);
            return cached;
        }

        /**
         * **结构实际占用的局部坐标范围**（含控制器那一格；不含空气格与忽略格）。
         * <p>
         * ⚠ 2026-09-25 新增，修"世界轮廓框比建筑大一圈"：
         * 模板周围有一圈**空白边距** —— 例如含拓展那份真实方块只占
         * `x[3..14] y[9..52] z[5..37]`，而 `width/height/depth` 报的是 31×53×41
         * （`x[0..30] y[0..52] z[0..40]`）。所以画包围盒**不能用 `[0, width)`**，
         * 否则框会往一边多出一大截（实测各朝向偏差 3~16 格不等）。
         * <p>
         * 与 {@link #plan()} 的区别：这里**包含控制器自身那格**（它就是结构的一部分，
         * 包围盒不能把它漏掉），并且直接返回极值而非格子列表。
         */
        @Nullable
        public Extent occupiedExtent() {
            Extent cached = EXTENT_CACHES.get(this);
            if (cached != null) {
                return cached;
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    String r = row(y, z);
                    for (int x = 0; x < width && x < r.length(); x++) {
                        char ch = r.charAt(x);
                        if (ch == '.' || ch == '~') {
                            continue;
                        }
                        if (groups.get(ch) == null || groups.get(ch).isEmpty()) {
                            continue;
                        }
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                        if (z < minZ) minZ = z;
                        if (z > maxZ) maxZ = z;
                    }
                }
            }
            if (maxX < minX) {
                return null;   // 空结构（理论上不会发生）
            }
            Extent ext = new Extent(minX, minY, minZ, maxX, maxY, maxZ);
            EXTENT_CACHES.put(this, ext);
            return ext;
        }
    }

    /** 判定计划里的一格：局部坐标 + 该格可接受的方块（含可替换项已展开的原始组） */
    public record PlanCell(int x, int y, int z, List<Block> accepted) {}

    /** 结构实际占用的局部坐标范围（闭区间，含控制器格） */
    public record Extent(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}

    /** 取模式（首次调用时读资源文件） */
    public static Pattern get() {
        Pattern p = pattern;
        if (p == null) {
            synchronized (IntegratedCpuStructure.class) {
                p = pattern;
                if (p == null) {
                    p = load(RESOURCE);
                    pattern = p;
                }
            }
        }
        return p;
    }

    /** 只供预览读取第二份资源；{@link #get()} 仍始终返回默认结构。 */
    public static Pattern loadVariant(String resourcePath) {
        return load(resourcePath);
    }

    private static Pattern load(String resourcePath) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = IntegratedCpuStructure.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("找不到结构定义资源: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取结构定义失败: " + resourcePath, e);
        }

        int width = -1;
        int height = -1;
        int depth = -1;
        BlockPos core = null;
        Map<Character, List<Block>> groups = new HashMap<>();
        List<String> rows = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        boolean inLayers = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("size ")) {
                String[] p = line.split("\\s+");
                width = Integer.parseInt(p[1]);
                height = Integer.parseInt(p[2]);
                depth = Integer.parseInt(p[3]);
            } else if (line.startsWith("core ")) {
                String[] p = line.split("\\s+");
                core = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } else if (line.startsWith("block ")) {
                String[] p = line.split("\\s+");
                char symbol = p[1].charAt(0);
                List<Block> blocks = new ArrayList<>();
                for (String id : p[2].split(",")) {
                    Block block = byName(id.trim());
                    if (block == null) {
                        AE2Addon.LOGGER.warn("[ae2addon] 结构定义里的方块不存在，已跳过: {}", id);
                        continue;
                    }
                    blocks.add(block);
                }
                if (!blocks.isEmpty()) {
                    groups.put(symbol, List.copyOf(blocks));
                }
            } else if (line.equals("layers")) {
                inLayers = true;
            } else if (line.startsWith("ignore ")) {
                // 忽略格：记录方块名只为日志可读；判定侧由字符 '~' 处理
                ignored.add(line.substring("ignore ".length()).trim());
            } else if (line.startsWith("layer ")) {
                // 层号只用于人看，真正顺序按出现次序（生成器保证 y 升序）
            } else if (inLayers) {
                rows.add(line.replace(" ", "."));
            }
        }

        if (width <= 0 || height <= 0 || depth <= 0 || core == null) {
            throw new IllegalStateException("结构定义缺少 size/core: " + resourcePath);
        }
        if (rows.size() != height * depth) {
            throw new IllegalStateException("结构定义层数不对: 期望 " + (height * depth)
                    + " 行，实际 " + rows.size() + " 行 (" + resourcePath + ")");
        }
        if (!ignored.isEmpty()) {
            AE2Addon.LOGGER.info("[ae2addon] 结构定义里的忽略格（不判定也不放置）: {}", ignored);
        }
        return new Pattern(width, height, depth, core, Map.copyOf(groups), List.copyOf(rows));
    }

    /** 注册名 → 方块；不存在返回 null（不抛异常，缺方块只跳过该符号） */
    @Nullable
    public static Block byName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(rl);
    }

    // ── 判定辅助 ──────────────────────────────────────────────────────────────

    /**
     * **可替换组**（2026-09-24 sensei，Java 侧声明 —— 资源文件保持"忠实还原模板"）：
     * <pre>
     *   模板里该位置是「键」这个方块时，实际放「值」里任意一个都算通过。
     *   assembler_core ← 可以换成 smooth_sky_stone_block（功能扩展位）
     * </pre>
     * <p>
     * ⚠ 2026-09-24 sensei：「先取消原本的空白单元与 256k 作为多方块结构方块」——
     * 原来这里还有一条 {@code 256k_crafting_storage ← crafting_unit}（两个**原版**方块互相顶替），
     * 现在**取消**：结构里该用哪个方块就用哪个，原版合成单元不再能顶替 256k 存储。
     * 自建方块（{@code ae2addon:dense_storage_unit} / {@code ae2addon:blank_storage_unit}）
     * 才是用来替换它们的。
     */
    private static final Map<String, List<String>> REPLACEMENTS = Map.of(
            "ae2addon:assembler_core", List.of("ae2:smooth_sky_stone_block"));

    /** 该方块作为"模板里的期望方块"时，允许用哪些方块替代（可能为空） */
    public static List<Block> replacementsOf(Block base) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(base);
        List<String> alts = key == null ? null : REPLACEMENTS.get(key.toString());
        if (alts == null) {
            return List.of();
        }
        List<Block> out = new ArrayList<>(alts.size());
        for (String id : alts) {
            Block b = byName(id);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    /** 该位置的期望方块组在「实际放了 actual」时是否通过（含可替换规则） */
    private static boolean acceptsAny(List<Block> expected, Block actual) {
        for (Block b : expected) {
            if (b == actual) {
                return true;
            }
            // 期望方块是可替换组的键 → 看实际方块在不在允许的替代里
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(b);
            List<String> alts = key == null ? null : REPLACEMENTS.get(key.toString());
            if (alts != null) {
                for (String altId : alts) {
                    Block alt = byName(altId);
                    if (alt != null && alt == actual) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 该局部坐标期望的方块组是否被给定状态命中（null = 必须是空气） */
    public static boolean matches(BlockState state, @Nullable List<Block> expected) {
        if (expected == null) {
            return state.isAir();
        }
        return acceptsAny(expected, state.getBlock());
    }

    /**
     * 诊断用：给出**四种朝向下**这个世界坐标对应的局部坐标与该格的期望方块。
     * <p>
     * 为什么需要它：判定失败时只报"世界坐标 X 应为 Y"是不够的 —— 玩家可能按另一个朝向搭的，
     * 光看世界坐标没法判断到底是"搭错了"还是"朝向没认对"（2026-09-24 就栽在这上面）。
     */
    public static String describeAllOrientations(Pattern p, BlockPos corePos, BlockPos world) {
        StringBuilder sb = new StringBuilder();
        for (int turn = 0; turn < 4; turn++) {
            Direction body = StructureMatcher.rotateAroundY(Direction.SOUTH, turn);
            Direction right = body.getCounterClockWise();
            BlockPos core = p.coreOffset();
            int dx = world.getX() - corePos.getX();
            int dy = world.getY() - corePos.getY();
            int dz = world.getZ() - corePos.getZ();
            int alongRight = dx * right.getStepX() + dz * right.getStepZ();
            int alongBody = dx * body.getStepX() + dz * body.getStepZ();
            int lx = alongRight + core.getX();
            int lz = alongBody + core.getZ();
            int ly = dy + core.getY();
            if (turn > 0) {
                sb.append(" | ");
            }
            sb.append("朝").append(StructureMatcher.describe(body)).append("→局部(")
                    .append(lx).append(',').append(ly).append(',').append(lz).append(")=");
            if (lx < 0 || lx >= p.width() || ly < 0 || ly >= p.height()
                    || lz < 0 || lz >= p.depth()) {
                sb.append("越界");
            } else if (p.isIgnored(lx, ly, lz)) {
                sb.append("忽略格");
            } else {
                sb.append(describe(p.expectedAt(lx, ly, lz)));
            }
        }
        return sb.toString();
    }

    /** 期望方块的显示名（提示用；多个用 / 连接，并附上可替换项） */
    public static String describe(@Nullable List<Block> expected) {
        if (expected == null) {
            return "空气";
        }
        StringBuilder sb = new StringBuilder();
        for (Block b : expected) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(b.getName().getString());
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(b);
            List<String> alts = key == null ? null : REPLACEMENTS.get(key.toString());
            if (alts != null) {
                for (String altId : alts) {
                    Block alt = byName(altId);
                    if (alt != null) {
                        sb.append("（或 ").append(alt.getName().getString()).append("）");
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 4 个水平朝向都试一遍，返回第一个完全匹配的结果；全不中返回 null。
     * <p>
     * ⚠ 2026-09-24 sensei：「结构模版就不要记入空气了，不然把结构范围外的东西也会检测进去」。
     * <p>
     * 所以这里**只遍历模板里真实存在的方块格**（{@code rows} 里的符号格），
     * **不检查空气格**。理由：结构 31×53×41 = 68,863 格里只有 2248 格是实体，
     * 其余 6.6 万格空气如果都要求"必须是空气"，那么结构范围内任何别的方块
     * （别人的建筑、装饰、树、雪……）都会把成型判失败。
     * 忽略格（`~`，线缆那种动态状态）同样跳过。
     *
     * @param problems 非 null 时收集不匹配的位置（只为给出人话提示，不参与判定）
     */
    @Nullable
    public static StructureMatcher.Match match(ServerLevel level, BlockPos corePos,
                                               Pattern p,
                                               @Nullable List<StructureMatcher.Problem> problems) {
        List<PlanCell> plan = p.plan();

        outer:
        for (int turn = 0; turn < 4; turn++) {
            Direction body = StructureMatcher.rotateAroundY(Direction.SOUTH, turn);
            Direction right = body.getCounterClockWise();
            BlockPos core = p.coreOffset();
            List<BlockPos> toConsume = new ArrayList<>();
            for (PlanCell cell : plan) {
                BlockPos pos = corePos.offset(
                        right.getStepX() * (cell.x() - core.getX()) + body.getStepX() * (cell.z() - core.getZ()),
                        cell.y() - core.getY(),
                        right.getStepZ() * (cell.x() - core.getX()) + body.getStepZ() * (cell.z() - core.getZ()));
                if (pos.equals(corePos)) {
                    continue;   // 控制器自身那格跳过
                }
                BlockState state = level.getBlockState(pos);
                if (!matches(state, cell.accepted())) {
                    if (turn == 3) {
                        // 4 个朝向全不中 —— 报问题时要**同时给出四种朝向下这一格的局部坐标 + 判定结果**，
                        // 否则光看世界坐标根本判断不出玩家是按哪个朝向搭的（2026-09-24 踩过：
                        // 报出来的"应为紫水晶块"跟实际搭的位置对不上，就是朝向没对上）。
                        if (problems != null) {
                            problems.add(new StructureMatcher.Problem(pos,
                                    describeAllOrientations(p, corePos, pos), state));
                        }
                        return null;
                    }
                    continue outer;        // 这个朝向不中，换下一个
                }
                toConsume.add(pos);
            }
            return new StructureMatcher.Match(body,
                    StructureMatcher.frontFor(body, p.depth(), core.getZ()), toConsume);
        }
        return null;
    }

    /**
     * 命中结果：**匹配到的结构定义 + 匹配结果**。
     * <p>
     * 为什么要带上 {@code pattern}：两份结构的世界坐标布局不同（尺寸/核心坐标都不一样），
     * 判定之后凡是要判断"某格算不算结构的一部分"（一键回收的覆盖格、装配处理器位置、
     * 并行处理器扫描）都必须知道**到底是哪一份**，光凭 {@code Match} 里的朝向分不出来。
     */
    public record Matched(Pattern pattern, StructureMatcher.Match match) {}

    /**
     * **依次尝试多份结构定义**，第一份匹配上就返回（2026-09-25 sensei 选 2）。
     * <p>
     * 背景：集成 CPU 有「含拓展单元」（31×53×41）和「无拓展单元」（27×44×42）两种搭法。
     * 原来判定只认含拓展那份 —— 按无拓展搭的结构**永远不会成型**。
     * <p>
     * 顺序有意义：**先试含拓展**（现有世界里已建的建筑都是它，不能因为先试无拓展而误判）。
     * 只有全部都不匹配时才返回 {@code null}；此时 {@code problems} 里留的是**最后一份**结构的
     * 逐格诊断（够玩家定位问题，且日志不会因为两份都报而翻倍）。
     */
    @Nullable
    public static Matched matchAny(ServerLevel level, BlockPos corePos,
                                   List<Pattern> patterns,
                                   @Nullable List<StructureMatcher.Problem> problems) {
        for (int i = 0; i < patterns.size(); i++) {
            Pattern p = patterns.get(i);
            List<StructureMatcher.Problem> sink = (i == patterns.size() - 1) ? problems : null;
            StructureMatcher.Match m = match(level, corePos, p, sink);
            if (m != null) {
                return new Matched(p, m);
            }
        }
        return null;
    }

    /**
     * 默认结构的匹配（等价于 {@code match(level, corePos, get(), problems)}）。
     * 保留这个重载，免得现有调用点全部改签名。
     */
    @Nullable
    public static StructureMatcher.Match match(ServerLevel level, BlockPos corePos,
                                               @Nullable List<StructureMatcher.Problem> problems) {
        return match(level, corePos, get(), problems);
    }

    /**
     * **结构覆盖到的世界坐标集合**（四种朝向都试，取并集）。
     * <p>
     * 用途：一键回收（2026-09-25 sensei）——把所有属于本结构的位置找出来，逐个拆掉。
     * 不要求"完全匹配"（结构可能已经缺块/被别的东西顶了），所以这里是**并集**：
     * 只要某个朝向下这一格落在模板的非空格上，就算结构的一格。
     * <p>
     * ⚠ 只返回**世界坐标非空气**的格：空气格本来就没东西可拆。
     * 控制器自己那一格永远不会出现（见 {@link Pattern#plan()} 里对 coreOffset 的排除）。
     *
     * @param level   服务端世界
     * @param corePos 控制器世界坐标
     * @param body    结构朝向；{@code null} = 四种朝向全试、取并集
     */
    public static List<BlockPos> coveredPositions(ServerLevel level, BlockPos corePos,
                                                  @Nullable Direction body) {
        return coveredPositions(level, corePos, get(), body);
    }

    /**
     * 同 {@link #coveredPositions(ServerLevel, BlockPos, Direction)}，但**用指定的结构定义**。
     * <p>
     * 2026-09-25：无拓展单元结构也要能成型后，一键回收必须按**实际匹配到的那份**来拆 ——
     * 否则按无拓展搭的建筑会被当成含拓展那份去拆，拆错格子。
     */
    public static List<BlockPos> coveredPositions(ServerLevel level, BlockPos corePos,
                                                  Pattern p,
                                                  @Nullable Direction body) {
        List<PlanCell> plan = p.plan();
        BlockPos core = p.coreOffset();
        List<BlockPos> out = new ArrayList<>();
        java.util.HashSet<BlockPos> seen = new java.util.HashSet<>();

        int first = 0;
        int last = body == null ? 3 : 0;
        for (int turn = first; turn <= last; turn++) {
            Direction dir = body != null ? body
                    : StructureMatcher.rotateAroundY(Direction.SOUTH, turn);
            Direction right = dir.getCounterClockWise();
            for (PlanCell cell : plan) {
                BlockPos pos = corePos.offset(
                        right.getStepX() * (cell.x() - core.getX()) + dir.getStepX() * (cell.z() - core.getZ()),
                        cell.y() - core.getY(),
                        right.getStepZ() * (cell.x() - core.getX()) + dir.getStepZ() * (cell.z() - core.getZ()));
                if (pos.equals(corePos) || !seen.add(pos)) {
                    continue;
                }
                if (level.getBlockState(pos).isAir()) {
                    continue;   // 空气没什么可拆的
                }
                out.add(pos);
            }
        }
        return out;
    }

    /** 结构需要放置的方块格数（排除控制器自身、空气格与忽略格） */
    public static int placementCount() {
        Pattern p = get();
        int n = 0;
        for (int y = 0; y < p.height(); y++) {
            for (int z = 0; z < p.depth(); z++) {
                for (int x = 0; x < p.width(); x++) {
                    if (p.coreOffset().getX() == x && p.coreOffset().getY() == y
                            && p.coreOffset().getZ() == z) {
                        continue;
                    }
                    if (p.isIgnored(x, y, z)) {
                        continue;
                    }
                    if (p.expectedAt(x, y, z) != null) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * 结构材料统计（符号 → 首选方块 × 数量），给 JEI / 提示用。
     * 可互换的符号只统计第一个方块；忽略格不计。
     */
    public static Map<Block, Integer> materialCounts() {
        Pattern p = get();
        Map<Block, Integer> counts = new HashMap<>();
        for (int y = 0; y < p.height(); y++) {
            for (int z = 0; z < p.depth(); z++) {
                for (int x = 0; x < p.width(); x++) {
                    if (p.coreOffset().getX() == x && p.coreOffset().getY() == y
                            && p.coreOffset().getZ() == z) {
                        continue;
                    }
                    if (p.isIgnored(x, y, z)) {
                        continue;
                    }
                    List<Block> exp = p.expectedAt(x, y, z);
                    if (exp != null && !exp.isEmpty()) {
                        counts.merge(exp.get(0), 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }
}
