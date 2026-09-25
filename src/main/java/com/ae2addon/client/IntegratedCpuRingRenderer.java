package com.ae2addon.client;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.multiblock.IntegratedCpuStructure;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Six luminous bands and an energy orb around each formed integrated CPU. */
@Mod.EventBusSubscriber(modid = AE2Addon.MODID, value = Dist.CLIENT)
public final class IntegratedCpuRingRenderer {
    private static final int SEGMENTS = 192;
    private static final double MAX_DISTANCE_SQR = 256.0 * 256.0;
    private static final float[][] RAINBOW = makeRainbow();
    private static final double[] COS = makeTrig(true);
    private static final double[] SIN = makeTrig(false);
    private static final RenderType GLOW = RingRenderType.createGlow();
    private static final RenderType ORB_SHELL = RingRenderType.createOrbShell();
    private static final RenderType ORB_SPARKS = RingRenderType.createOrbSparks();
    /**
     * 五组充能石英火把在结构模板里的局部 X 偏移均值（单位：格，相对 core）。
     * <p>
     * ⚠ 2026-09-25 定案（此前是 -3.0，整体偏 2 格）。真值由
     * {@code tools/dump_fixture_offsets.py} 直接从
     * {@code integrated_cpu_structure.txt} 算出，肉眼不可信：
     * 五组的 X 格偏移都是 {-6, -5}，均值 {@code -5.5}。
     */
    private static final double FIXTURE_AXIS_X = -5.5;
    /** 火把组再向外半格；红色环与能量球落在这条线上。 */
    private static final double UPPER_AXIS_X = FIXTURE_AXIS_X - 0.5;
    private static final double ORB_DX = UPPER_AXIS_X;
    private static final double ORB_DY = 42.0;
    private static final double ORB_DZ = -0.5;
    private static final double ORB_RADIUS = 5.0;
    private static final int SURFACE_DOT_COUNT = 180;
    private static final int INNER_DOT_COUNT = 48;
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    private static final OrbDot[] SURFACE_DOTS = makeSurfaceDots();
    private static final OrbDot[] INNER_DOTS = makeInnerDots();

    // SOUTH is the template orientation. dx/dz are LOCAL CELL offsets relative to the core
    // (exactly what integrated_cpu_structure.txt says), dy is the cell offset plus 0.5 for the
    // block center. rotatedX/rotatedZ add the world-axis 0.5, so these numbers can be checked
    // mechanically against the resource — see tools/dump_fixture_offsets.py.
    private static final Ring[] RINGS = {
            new Ring(FIXTURE_AXIS_X, 19.5, 0.0, Math.sqrt(0.5), Math.sqrt(2.0), Axis.X, Axis.Z, true),
            new Ring(FIXTURE_AXIS_X, 3.5, 0.0, 2.5 * Math.sqrt(2.0), 16.0 * Math.sqrt(2.0), Axis.X, Axis.Z, true),
            new Ring(FIXTURE_AXIS_X, 7.5, 0.0, 2.5 * Math.sqrt(2.0), 16.0 * Math.sqrt(2.0), Axis.X, Axis.Z, true),
            // These fixture rectangles span local X and a tilted Y/Z direction.
            new Ring(FIXTURE_AXIS_X, 4.5, 1.0, 1.5 * Math.sqrt(2.0), Math.sqrt(754.0),
                    Axis.X, Axis.D_TILT, true),
            new Ring(FIXTURE_AXIS_X, 4.5, -1.0, 1.5 * Math.sqrt(2.0), Math.sqrt(754.0),
                    Axis.X, Axis.E_TILT, true),
            new Ring(UPPER_AXIS_X, 32, -0.5, 7.0, 7.0, Axis.X, Axis.Z, false)
    };

    // The 20 fixture cells themselves, in the same local basis as RINGS: integer cell offsets
    // on X/Z, plus 0.5 on Y for the block center. F has no fixture markers.
    // Verified: every marker lands exactly on its ellipse (ellipse=1.0000, planeOffset=0.0000).
    private static final double[][][] MARKERS = {
            {{-6, 19.5, -1}, {-5, 19.5, -1}, {-6, 19.5, 1}, {-5, 19.5, 1}},
            {{-8, 3.5, -16}, {-3, 3.5, -16}, {-8, 3.5, 16}, {-3, 3.5, 16}},
            {{-8, 7.5, -16}, {-3, 7.5, -16}, {-8, 7.5, 16}, {-3, 7.5, 16}},
            {{-7, 23.5, 5}, {-4, 23.5, 5}, {-7, -14.5, -3}, {-4, -14.5, -3}},
            {{-7, 23.5, -5}, {-4, 23.5, -5}, {-7, -14.5, 3}, {-4, -14.5, 3}}
    };

    static {
        logMarkerChecks();
        AE2Addon.LOGGER.info("[ae2addon][cpu-rings] orb center=({}, {}, {}) r=5.0 surfaceDots=180 innerDots=48",
                ORB_DX, ORB_DY, ORB_DZ);
    }

    private static final Map<BlockPos, RingState> STATES = new HashMap<>();
    private static Object currentLevel;
    /** 模板真值只 dump 一次（它不随朝向变化）。 */
    private static boolean templateDumped;

    /** Unit vector in the structure's local coordinate system. */
    private record Axis(double x, double y, double z) {
        private static final Axis X = new Axis(1, 0, 0);
        private static final Axis Y = new Axis(0, 1, 0);
        private static final Axis Z = new Axis(0, 0, 1);
        private static final Axis D_TILT = new Axis(0, 19 / Math.sqrt(377.0), 4 / Math.sqrt(377.0));
        private static final Axis E_TILT = new Axis(0, 19 / Math.sqrt(377.0), -4 / Math.sqrt(377.0));
    }

    private record Ring(double dx, double dy, double dz, double ru, double rv,
                        Axis uAxis, Axis vAxis, boolean rainbow) {}

    private record RingState(Direction body, long lastTick, long lastDebugTick) {}

    private record OrbDot(double x, double y, double z, double phase, double period,
                          float red, float green, float blue) {}

    private IntegratedCpuRingRenderer() {}

    /** Called on the client thread by the S->C state packet. */
    public static void update(BlockPos pos, boolean formed, Direction body) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (currentLevel != level) {
            STATES.clear();
            currentLevel = level;
        }
        if (!formed || !body.getAxis().isHorizontal()) {
            STATES.remove(pos);
        } else {
            long now = level.getGameTime();
            RingState previous = STATES.get(pos);
            // 2026-09-25 定案后降噪：只在首次出现或朝向变化时打印一次核对结果，
            // 不再每 100 tick 重复刷屏（12 行/次/栋）。
            boolean log = previous == null || previous.body() != body;
            if (log) logRingCenters(pos, body);
            STATES.put(pos.immutable(), new RingState(body, now,
                    log ? now : previous.lastDebugTick()));
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null || mc.player == null) {
            STATES.clear();
            currentLevel = null;
            return;
        }
        if (currentLevel != level) {
            STATES.clear();
            currentLevel = level;
            return;
        }
        if (STATES.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        var camera = event.getCamera().getPosition();
        Vector3f cameraRight = new Vector3f(1, 0, 0).rotate(event.getCamera().rotation());
        Vector3f cameraUp = new Vector3f(0, 1, 0).rotate(event.getCamera().rotation());
        double seconds = (level.getGameTime() + event.getPartialTick()) / 20.0;
        Iterator<Map.Entry<BlockPos, RingState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            RingState state = entry.getValue();
            // The server sends a heartbeat once a second. Expire removed or out-of-range controllers.
            if (level.getGameTime() - state.lastTick() > 60) {
                it.remove();
                continue;
            }
            BlockPos pos = entry.getKey();
            if (mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    > MAX_DISTANCE_SQR) continue;

            pose.pushPose();
            pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            Matrix4f matrix = pose.last().pose();
            Direction body = state.body();
            Direction right = body.getCounterClockWise();
            VertexConsumer vertices = buffers.getBuffer(GLOW);
            for (Ring ring : RINGS) {
                // Wide faint halo plus a narrow bright center, both using unlit additive color.
                addBand(vertices, matrix, ring, right, body, 0.48, 0.12f);
                addBand(vertices, matrix, ring, right, body, 0.12, 0.65f);
            }
            buffers.endBatch(GLOW);
            drawOrb(buffers, matrix, right, body, cameraRight, cameraUp, seconds);
            pose.popPose();
        }
    }

    private static void addBand(VertexConsumer vertices, Matrix4f matrix, Ring ring,
                                Direction right, Direction body, double width, float alpha) {
        double cx = rotatedX(ring.dx(), ring.dz(), right, body);
        double cz = rotatedZ(ring.dx(), ring.dz(), right, body);
        double ux = axisX(ring.uAxis(), right, body);
        double uy = ring.uAxis().y();
        double uz = axisZ(ring.uAxis(), right, body);
        double vx = axisX(ring.vAxis(), right, body);
        double vy = ring.vAxis().y();
        double vz = axisZ(ring.vAxis(), right, body);
        double half = width / 2.0;
        for (int i = 0; i < SEGMENTS; i++) {
            // Offset along the ellipse's unit normal to keep the band equally wide at both axes.
            vertex(vertices, matrix, ring, cx, cz, ux, uy, uz, vx, vy, vz, i, -half, alpha);
            vertex(vertices, matrix, ring, cx, cz, ux, uy, uz, vx, vy, vz, i, half, alpha);
            vertex(vertices, matrix, ring, cx, cz, ux, uy, uz, vx, vy, vz, i + 1, half, alpha);
            vertex(vertices, matrix, ring, cx, cz, ux, uy, uz, vx, vy, vz, i + 1, -half, alpha);
        }
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, Ring ring,
                               double cx, double cz,
                               double ux, double uy, double uz,
                               double vx, double vy, double vz,
                               int index, double offset, float alpha) {
        double cos = COS[index];
        double sin = SIN[index];
        double normalU = ring.rv() * cos;
        double normalV = ring.ru() * sin;
        double normalLength = Math.hypot(normalU, normalV);
        double u = ring.ru() * cos + offset * normalU / normalLength;
        double v = ring.rv() * sin + offset * normalV / normalLength;
        double x = cx + ux * u + vx * v;
        double y = ring.dy() + uy * u + vy * v;
        double z = cz + uz * u + vz * v;
        float[] rgb = ring.rainbow() ? RAINBOW[index] : null;
        vertices.vertex(matrix, (float) x, (float) y, (float) z)
                .color(rgb == null ? 1.0f : rgb[0], rgb == null ? 0.05f : rgb[1],
                        rgb == null ? 0.05f : rgb[2], alpha)
                .uv2(0xF000F0).endVertex();
    }

    private static void drawOrb(MultiBufferSource.BufferSource buffers, Matrix4f matrix,
                                Direction right, Direction body, Vector3f cameraRight,
                                Vector3f cameraUp, double seconds) {
        double cx = rotatedX(ORB_DX, ORB_DZ, right, body);
        double cz = rotatedZ(ORB_DX, ORB_DZ, right, body);

        // Flush each layer before starting the next one so the dark depth-writing shell is drawn first.
        VertexConsumer shell = buffers.getBuffer(ORB_SHELL);
        for (int latitude = 0; latitude < 12; latitude++) {
            double lat0 = -Math.PI / 2 + Math.PI * latitude / 12;
            double lat1 = -Math.PI / 2 + Math.PI * (latitude + 1) / 12;
            for (int longitude = 0; longitude < 24; longitude++) {
                double lon0 = 2 * Math.PI * longitude / 24;
                double lon1 = 2 * Math.PI * (longitude + 1) / 24;
                shellVertex(shell, matrix, cx, cz, lat0, lon0);
                shellVertex(shell, matrix, cx, cz, lat1, lon0);
                shellVertex(shell, matrix, cx, cz, lat1, lon1);
                shellVertex(shell, matrix, cx, cz, lat0, lon1);
            }
        }
        buffers.endBatch(ORB_SHELL);

        VertexConsumer sparks = buffers.getBuffer(ORB_SPARKS);
        for (OrbDot dot : INNER_DOTS) {
            double wave = 0.5 + 0.5 * Math.sin(2 * Math.PI * seconds / dot.period() + dot.phase());
            // 2026-09-25 sensei：「内部彩光暗一点，就是要进入球体才能看到彩光的效果」。
            // 球面已经 0.97 近乎全黑，外面看不到彩光；这里刻意压低亮度，
            // 让它只在飞进球体内部（背面被 CULL 剔除）时才显出来。
            float bright = (float) (0.04 + 0.62 * wave);
            addSpark(sparks, matrix, cx + dot.x(), ORB_DY + dot.y(), cz + dot.z(),
                    0.16f + 0.22f * bright, dot.red(), dot.green(), dot.blue(),
                    bright * 0.95f, cameraRight, cameraUp);
        }
        for (OrbDot dot : SURFACE_DOTS) {
            double wave = 0.5 + 0.5 * Math.sin(2 * Math.PI * seconds / dot.period() + dot.phase());
            // 白点也略提一点，让它们仍能从更黑的球面上"浮现"出来
            float bright = (float) (0.20 + 0.90 * wave * wave);
            addSpark(sparks, matrix, cx + dot.x(), ORB_DY + dot.y(), cz + dot.z(),
                    0.11f + 0.18f * bright, 1.0f, 1.0f, 1.0f,
                    Math.min(1.0f, bright), cameraRight, cameraUp);
        }
        buffers.endBatch(ORB_SPARKS);
    }

    private static void shellVertex(VertexConsumer vertices, Matrix4f matrix,
                                    double cx, double cz, double latitude, double longitude) {
        double horizontal = ORB_RADIUS * Math.cos(latitude);
        vertices.vertex(matrix,
                        (float) (cx + horizontal * Math.cos(longitude)),
                        (float) (ORB_DY + ORB_RADIUS * Math.sin(latitude)),
                        (float) (cz + horizontal * Math.sin(longitude)))
                          // 2026-09-25 sensei：「直接变成全黑吧，内部彩光暗一点，
                          // 就是要进入球体才能看到彩光的效果」→ 0.86 → 0.97（近乎完全不透）。
                          // 外面只看到黑球 + 表面白点；彩光要飞进球体内部才看得见。
                          .color(0.02f, 0.02f, 0.035f, 0.97f)
                .uv2(0xF000F0).endVertex();
    }

    private static void addSpark(VertexConsumer vertices, Matrix4f matrix,
                                 double x, double y, double z, float size,
                                 float red, float green, float blue, float alpha,
                                 Vector3f right, Vector3f up) {
        float half = size / 2;
        sparkVertex(vertices, matrix, x, y, z, right, up, -half, -half, red, green, blue, alpha);
        sparkVertex(vertices, matrix, x, y, z, right, up, half, -half, red, green, blue, alpha);
        sparkVertex(vertices, matrix, x, y, z, right, up, half, half, red, green, blue, alpha);
        sparkVertex(vertices, matrix, x, y, z, right, up, -half, half, red, green, blue, alpha);
    }

    private static void sparkVertex(VertexConsumer vertices, Matrix4f matrix,
                                    double x, double y, double z, Vector3f right, Vector3f up,
                                    float rx, float uy, float red, float green, float blue, float alpha) {
        vertices.vertex(matrix,
                        (float) (x + right.x * rx + up.x * uy),
                        (float) (y + right.y * rx + up.y * uy),
                        (float) (z + right.z * rx + up.z * uy))
                .color(red, green, blue, alpha)
                .uv2(0xF000F0).endVertex();
    }

    private static OrbDot[] makeSurfaceDots() {
        Random random = new Random(0x50A8FACE);
        OrbDot[] dots = new OrbDot[SURFACE_DOT_COUNT];
        for (int i = 0; i < dots.length; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / dots.length;
            double horizontal = Math.sqrt(1.0 - y * y);
            double angle = i * GOLDEN_ANGLE;
            dots[i] = new OrbDot(ORB_RADIUS * horizontal * Math.cos(angle),
                    ORB_RADIUS * y, ORB_RADIUS * horizontal * Math.sin(angle),
                    random.nextDouble() * 2 * Math.PI, 1.6 + random.nextDouble() * 2.6,
                    1.0f, 1.0f, 1.0f);
        }
        return dots;
    }

    private static OrbDot[] makeInnerDots() {
        Random random = new Random(0xC010A11);
        OrbDot[] dots = new OrbDot[INNER_DOT_COUNT];
        for (int i = 0; i < dots.length; i++) {
            double y = 2 * random.nextDouble() - 1;
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = ORB_RADIUS * 0.8 * Math.cbrt(random.nextDouble());
            double horizontal = Math.sqrt(1.0 - y * y);
            float[] color = hsvRgb(random.nextDouble(), 0.9f);
            dots[i] = new OrbDot(radius * horizontal * Math.cos(angle), radius * y,
                    radius * horizontal * Math.sin(angle), random.nextDouble() * 2 * Math.PI,
                    2.5 + random.nextDouble() * 3.5, color[0], color[1], color[2]);
        }
        return dots;
    }

    private static float[] hsvRgb(double hue, float saturation) {
        double h = hue * 6.0;
        int sector = (int) h;
        float fraction = (float) (h - sector);
        float low = 1.0f - saturation;
        float falling = 1.0f - saturation * fraction;
        float rising = 1.0f - saturation * (1.0f - fraction);
        return switch (sector) {
            case 0 -> new float[]{1, rising, low};
            case 1 -> new float[]{falling, 1, low};
            case 2 -> new float[]{low, 1, rising};
            case 3 -> new float[]{low, falling, 1};
            case 4 -> new float[]{rising, low, 1};
            default -> new float[]{1, low, falling};
        };
    }

    /**
     * 局部格偏移 (dx, dz) → 相对控制器方块**角点**的世界水平偏移。
     * <p>
     * ⚠ 2026-09-25 定案。这里曾经写成方块中心基准
     * （{@code 0.5 + right*(x-0.5) + body*(z-0.5)}），而 {@code RINGS} 里的
     * {@code dx/dz} 是**格偏移**（模板真值），两套基准混用导致整体偏 2 格。
     * <p>
     * 现在的形式与 {@code StructureMatcher} 的映射、以及
     * {@code StructureMatcher} 使用的
     * {@code world = corePos + right*(x-coreX) + UP*(y-coreY) + body*(z-coreZ)}
     * 完全一致：格偏移做旋转，方块中心的 0.5 直接加在**世界轴**上
     * （Y 轴的 0.5 已经含在 {@code Ring.dy} 里）。
     */
    private static double rotatedX(double x, double z, Direction right, Direction body) {
        return 0.5 + right.getStepX() * x + body.getStepX() * z;
    }

    private static double rotatedZ(double x, double z, Direction right, Direction body) {
        return 0.5 + right.getStepZ() * x + body.getStepZ() * z;
    }

    /**
     * 独立核对：模板里 fixture 的格偏移（真值） vs {@code RINGS} 常量（渲染用的值）。
     * <p>
     * 两条路径完全独立 —— 真值从 {@code integrated_cpu_structure.txt} 的 plan 读出，
     * 常量是代码里手写的。所以 {@code delta} 必须为 0，否则就是常量写错了。
     */
    private static void logRingCenters(BlockPos pos, Direction body) {
        Direction right = body.getCounterClockWise();
        try {
            var pattern = IntegratedCpuStructure.get();
            var fixture = IntegratedCpuStructure.byName("ae2:quartz_fixture");
            var core = pattern.coreOffset();

            // 真值：模板里 fixture 的格偏移，按局部 Y 分组。
            java.util.Map<Integer, java.util.List<int[]>> byY = new java.util.TreeMap<>();
            for (var cell : pattern.plan()) {
                if (!cell.accepted().contains(fixture)) continue;
                byY.computeIfAbsent(cell.y() - core.getY(), k -> new java.util.ArrayList<>())
                        .add(new int[]{cell.x() - core.getX(), cell.z() - core.getZ()});
            }
            for (var group : byY.entrySet()) {
                double sumX = 0;
                double sumZ = 0;
                int n = 0;
                for (int[] offset : group.getValue()) {
                    sumX += offset[0];
                    sumZ += offset[1];
                    n++;
                }
                if (!templateDumped) {
                    AE2Addon.LOGGER.info(
                            "[ae2addon][ring-debug] 模板 dy={} 数量={} x均值={} z均值={}",
                            group.getKey(), n, sumX / n, sumZ / n);
                }
            }
            templateDumped = true;

            // 常量：六个环渲染时实际用的值。
            for (int i = 0; i < RINGS.length; i++) {
                Ring ring = RINGS[i];
                AE2Addon.LOGGER.info(
                        "[ae2addon][ring-debug] ring[{}] dx={} dy={} dz={} 世界圆心=({}, {}, {})",
                        i, ring.dx(), ring.dy(), ring.dz(),
                        pos.getX() + rotatedX(ring.dx(), ring.dz(), right, body),
                        pos.getY() + ring.dy(),
                        pos.getZ() + rotatedZ(ring.dx(), ring.dz(), right, body));
            }

            // A 环：每格 fixture 的真实世界中心 vs 渲染圆心。
            Ring a = RINGS[0];
            double renderedX = pos.getX() + rotatedX(a.dx(), a.dz(), right, body);
            double renderedY = pos.getY() + a.dy();
            double renderedZ = pos.getZ() + rotatedZ(a.dx(), a.dz(), right, body);
            double expectedX = 0;
            double expectedY = 0;
            double expectedZ = 0;
            int count = 0;
            for (var cell : pattern.plan()) {
                if (cell.y() - core.getY() != (int) Math.floor(a.dy())
                        || !cell.accepted().contains(fixture)) continue;
                expectedX += pos.getX() + right.getStepX() * (cell.x() - core.getX())
                        + body.getStepX() * (cell.z() - core.getZ()) + 0.5;
                expectedY += pos.getY() + cell.y() - core.getY() + 0.5;
                expectedZ += pos.getZ() + right.getStepZ() * (cell.x() - core.getX())
                        + body.getStepZ() * (cell.z() - core.getZ()) + 0.5;
                count++;
            }
            if (count != 4) {
                AE2Addon.LOGGER.warn("[ae2addon][ring-debug] pos={} body={} A火把数量={}，无法核对圆心",
                        pos, body, count);
                return;
            }
            expectedX /= count;
            expectedY /= count;
            expectedZ /= count;
            AE2Addon.LOGGER.info(
                    "[ae2addon][ring-debug] 客户端 pos={} RingState.body={} right={} A ring center=({}, {}, {}) expected=({}, {}, {}) delta=({}, {}, {})",
                    pos, body, right, renderedX, renderedY, renderedZ, expectedX, expectedY, expectedZ,
                    renderedX - expectedX, renderedY - expectedY, renderedZ - expectedZ);
        } catch (RuntimeException e) {
            AE2Addon.LOGGER.warn("[ae2addon][ring-debug] pos={} body={} 模板核对失败: {}",
                    pos, body, e.toString());
        }
    }

    private static double axisX(Axis axis, Direction right, Direction body) {
        return axis.x() * right.getStepX() + axis.z() * body.getStepX();
    }

    private static double axisZ(Axis axis, Direction right, Direction body) {
        return axis.x() * right.getStepZ() + axis.z() * body.getStepZ();
    }

    private static double[] makeTrig(boolean cosine) {
        double[] values = new double[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / SEGMENTS;
            values[i] = cosine ? Math.cos(angle) : Math.sin(angle);
        }
        return values;
    }

    /** One-time check against the fixture block centers in integrated_cpu_structure.txt. */
    private static void logMarkerChecks() {
        int markers = 0;
        double worstEllipse = 0;
        double worstPlane = 0;
        String worstName = "-";
        for (int i = 0; i < MARKERS.length; i++) {
            Ring ring = RINGS[i];
            for (int j = 0; j < MARKERS[i].length; j++) {
                double[] marker = MARKERS[i][j];
                double dx = marker[0] - ring.dx();
                double dy = marker[1] - ring.dy();
                double dz = marker[2] - ring.dz();
                double du = axisDelta(ring.uAxis(), dx, dy, dz);
                double dv = axisDelta(ring.vAxis(), dx, dy, dz);
                double equation = du * du / (ring.ru() * ring.ru())
                        + dv * dv / (ring.rv() * ring.rv());
                double planeError = Math.sqrt(Math.max(0, dx * dx + dy * dy + dz * dz
                        - du * du - dv * dv));
                markers++;
                if (Math.abs(equation - 1) > worstEllipse) {
                    worstEllipse = Math.abs(equation - 1);
                    worstName = Character.toString((char) ('A' + i)) + (j + 1);
                }
                worstPlane = Math.max(worstPlane, planeError);
            }
        }
        String line = String.format(Locale.ROOT,
                "[ae2addon][cpu-rings] 火把自检: %d 个标记, 椭圆最大偏差=%.6f(%s), 平面外最大偏差=%.6f",
                markers, worstEllipse, worstName, worstPlane);
        if (worstEllipse > 0.0001 || worstPlane > 0.0001) {
            AE2Addon.LOGGER.warn(line);
        } else {
            AE2Addon.LOGGER.info(line);
        }
    }

    private static double axisDelta(Axis axis, double dx, double dy, double dz) {
        return axis.x() * dx + axis.y() * dy + axis.z() * dz;
    }

    private static float[][] makeRainbow() {
        float[][] colors = new float[SEGMENTS + 1][3];
        for (int i = 0; i <= SEGMENTS; i++) {
            float h = (i % SEGMENTS) / (float) SEGMENTS * 6.0f;
            float x = 1.0f - Math.abs(h % 2.0f - 1.0f);
            float m = 0.15f;
            float[] c = colors[i];
            if (h < 1) { c[0] = 1; c[1] = x; c[2] = 0; }
            else if (h < 2) { c[0] = x; c[1] = 1; c[2] = 0; }
            else if (h < 3) { c[0] = 0; c[1] = 1; c[2] = x; }
            else if (h < 4) { c[0] = 0; c[1] = x; c[2] = 1; }
            else if (h < 5) { c[0] = x; c[1] = 0; c[2] = 1; }
            else { c[0] = 1; c[1] = 0; c[2] = x; }
            c[0] = m + 0.85f * c[0];
            c[1] = m + 0.85f * c[1];
            c[2] = m + 0.85f * c[2];
        }
        return colors;
    }

    /** Full-bright lightmap samples plus additive blending give the bands an emissive appearance. */
    private static final class RingRenderType extends RenderType {
        private RingRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                               boolean affectsCrumbling, boolean sortOnUpload,
                               Runnable setup, Runnable clear) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        }

        private static RenderType createGlow() {
            return RenderType.create("ae2addon_cpu_rings", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 4096, false, false,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_LIGHTMAP_SHADER)
                            .setTransparencyState(ADDITIVE_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLightmapState(LIGHTMAP)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }

        private static RenderType createOrbShell() {
            return RenderType.create("ae2addon_cpu_orb_shell", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 4096, false, false,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_LIGHTMAP_SHADER)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(CULL)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setLightmapState(LIGHTMAP)
                            .setWriteMaskState(COLOR_DEPTH_WRITE)
                            .createCompositeState(false));
        }

        private static RenderType createOrbSparks() {
            return RenderType.create("ae2addon_cpu_orb_sparks", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 4096, false, false,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_LIGHTMAP_SHADER)
                            .setTransparencyState(ADDITIVE_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .setLightmapState(LIGHTMAP)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));
        }
    }
}
