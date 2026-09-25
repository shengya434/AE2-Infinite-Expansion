package com.ae2addon.client;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.multiblock.IntegratedCpuStructure;
import com.ae2addon.block.multiblock.StructureMatcher;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Persistent, through-wall structure guide for every unformed integrated CPU. */
@Mod.EventBusSubscriber(modid = AE2Addon.MODID, value = Dist.CLIENT)
public final class IntegratedCpuOutlineRenderer {
    private static final double MAX_DISTANCE_SQR = 256.0 * 256.0;
    private static final Map<BlockPos, OutlineState> STATES = new HashMap<>();
    private static Object currentLevel;
    private static IntegratedCpuStructure.Pattern noExpandPattern;

    private record Marker(float x, float y, float z, float size,
                          float red, float green, float blue) {}

    private record Geometry(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ, List<Marker> markers) {}

    private record OutlineState(boolean noExpand, Direction body, Geometry geometry, long lastTick) {}

    private IntegratedCpuOutlineRenderer() {}

    /** Called on the client thread by IntegratedCpuOutlinePacket. */
    public static void update(BlockPos pos, boolean formed, boolean noExpand, Direction body) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (currentLevel != level) {
            STATES.clear();
            currentLevel = level;
        }
        if (formed || !body.getAxis().isHorizontal()) {
            STATES.remove(pos);
            return;
        }
        var old = STATES.get(pos);
        if (old != null && old.noExpand() == noExpand && old.body() == body) {
            STATES.put(pos.immutable(), new OutlineState(noExpand, body, old.geometry(), level.getGameTime()));
            return;
        }
        try {
            var pattern = noExpand ? noExpandPattern() : IntegratedCpuStructure.get();
            STATES.put(pos.immutable(), new OutlineState(noExpand, body,
                    geometryFor(pos, body, pattern), level.getGameTime()));
        } catch (RuntimeException e) {
            AE2Addon.LOGGER.warn("[ae2addon][cpu-outline] 无法计算结构轮廓: {}", e.toString());
        }
    }

    private static IntegratedCpuStructure.Pattern noExpandPattern() {
        if (noExpandPattern == null) {
            noExpandPattern = IntegratedCpuStructure.loadVariant(
                    "/data/ae2addon/integrated_cpu_structure_noexpand.txt");
        }
        return noExpandPattern;
    }

    /** Geometry is relative to the controller, so camera translation stays precise. */
    private static Geometry geometryFor(BlockPos controller, Direction body,
                                        IntegratedCpuStructure.Pattern pattern) {
        // ⚠ 2026-09-25 修「框比建筑大一圈」：包围盒必须按**实际方块占用范围**算，
        //   不能假设 [0, width) —— 模板周围有空白边距（含拓展真实只占 x[3..14] y[9..52] z[5..37]，
        //   而 width/height/depth 报 31×53×41）。用错的尺寸会让框各方向多出 3~16 格。
        var extent = pattern.occupiedExtent();
        Direction right = body.getCounterClockWise();
        float minX = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        if (extent != null) {
            // 局部坐标 → 相对控制器的偏移：right*(x-coreX) + body*(z-coreZ)
            var core = pattern.coreOffset();
            int[] xs = {extent.minX(), extent.maxX()};
            int[] zs = {extent.minZ(), extent.maxZ()};
            for (int x : xs) {
                for (int z : zs) {
                    float wx = right.getStepX() * (x - core.getX())
                            + body.getStepX() * (z - core.getZ());
                    float wz = right.getStepZ() * (x - core.getX())
                            + body.getStepZ() * (z - core.getZ());
                    minX = Math.min(minX, wx);
                    maxX = Math.max(maxX, wx + 1);
                    minZ = Math.min(minZ, wz);
                    maxZ = Math.max(maxZ, wz + 1);
                }
            }
        } else {
            minX = minZ = 0;
            maxX = maxZ = 1;
        }

        var markers = new ArrayList<Marker>();
        markers.add(new Marker(0.5f, 0.5f, 0.5f, 0.28f, 0.45f, 1.0f, 1.0f));
        var core = pattern.coreOffset();
        var co = com.ae2addon.init.ModBlocks.INFINITE_CO_PROCESSING.get();
        var assembler = com.ae2addon.init.ModBlocks.ASSEMBLER_CORE.get();
        for (var cell : pattern.plan()) {
            boolean isCo = cell.accepted().contains(co);
            boolean isAssembler = cell.accepted().contains(assembler);
            if (!isCo && !isAssembler) continue;
            float dx = right.getStepX() * (cell.x() - core.getX())
                    + body.getStepX() * (cell.z() - core.getZ()) + 0.5f;
            float dy = cell.y() - core.getY() + 0.5f;
            float dz = right.getStepZ() * (cell.x() - core.getX())
                    + body.getStepZ() * (cell.z() - core.getZ()) + 0.5f;
            markers.add(isCo
                    ? new Marker(dx, dy, dz, 0.19f, 1.0f, 0.75f, 0.18f)
                    : new Marker(dx, dy, dz, 0.19f, 0.95f, 0.36f, 1.0f));
        }
        // Y 也用实际范围（含拓展 real y 从 9 开始，原来固定从 origin.y 起会多出 9 格）
        int minY = extent != null ? extent.minY() : 0;
        int maxY = extent != null ? extent.maxY() : 0;
        return new Geometry(minX, minY - core.getY(), minZ,
                maxX, maxY - core.getY() + 1, maxZ,
                List.copyOf(markers));
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
        Iterator<Map.Entry<BlockPos, OutlineState>> iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var state = entry.getValue();
            if (level.getGameTime() - state.lastTick() > 60) {
                iterator.remove();
                continue;
            }
            BlockPos pos = entry.getKey();
            if (mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    > MAX_DISTANCE_SQR) continue;

            pose.pushPose();
            pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            // ⚠ 顺序很关键（照 IntegratedCpuConflictRenderer 的写法）：
            //   第一遍**带深度测试**画（会被地形/方块正常遮挡），先 endBatch 提交；
            //   然后才关深度测试再画一遍 → 第二遍穿墙可见。
            //   2026-09-25 修：原来 disableDepthTest() 写在两遍之前，导致**第一遍也不做深度测试**，
            //   整个框永远画在最前面（漂在地形上，看着就是"歪"的）。
            draw(buffers, pose, state.geometry(), 0.50f);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            draw(buffers, pose, state.geometry(), 0.60f);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            pose.popPose();
        }
    }

    /** Flush before changing depth state, as with the existing conflict renderer. */
    private static void draw(MultiBufferSource.BufferSource buffers, PoseStack pose,
                             Geometry geometry, float alpha) {
        Matrix4f matrix = pose.last().pose();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        box(lines, matrix, geometry.minX(), geometry.minY(), geometry.minZ(),
                geometry.maxX(), geometry.maxY(), geometry.maxZ(),
                0.35f, 0.85f, 0.95f, alpha);
        for (Marker marker : geometry.markers()) {
            float size = marker.size();
            box(lines, matrix, marker.x() - size, marker.y() - size, marker.z() - size,
                    marker.x() + size, marker.y() + size, marker.z() + size,
                    marker.red(), marker.green(), marker.blue(), Math.min(1.0f, alpha * 1.5f));
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void box(VertexConsumer vc, Matrix4f m,
                            float x0, float y0, float z0, float x1, float y1, float z1,
                            float red, float green, float blue, float alpha) {
        line(vc, m, x0,y0,z0, x1,y0,z0, red,green,blue,alpha);
        line(vc, m, x1,y0,z0, x1,y0,z1, red,green,blue,alpha);
        line(vc, m, x1,y0,z1, x0,y0,z1, red,green,blue,alpha);
        line(vc, m, x0,y0,z1, x0,y0,z0, red,green,blue,alpha);
        line(vc, m, x0,y1,z0, x1,y1,z0, red,green,blue,alpha);
        line(vc, m, x1,y1,z0, x1,y1,z1, red,green,blue,alpha);
        line(vc, m, x1,y1,z1, x0,y1,z1, red,green,blue,alpha);
        line(vc, m, x0,y1,z1, x0,y1,z0, red,green,blue,alpha);
        line(vc, m, x0,y0,z0, x0,y1,z0, red,green,blue,alpha);
        line(vc, m, x1,y0,z0, x1,y1,z0, red,green,blue,alpha);
        line(vc, m, x1,y0,z1, x1,y1,z1, red,green,blue,alpha);
        line(vc, m, x0,y0,z1, x0,y1,z1, red,green,blue,alpha);
    }

    private static void line(VertexConsumer vc, Matrix4f matrix,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float red, float green, float blue, float alpha) {
        vc.vertex(matrix, x0, y0, z0).color(red, green, blue, alpha)
                .normal(0f, 1f, 0f).endVertex();
        vc.vertex(matrix, x1, y1, z1).color(red, green, blue, alpha)
                .normal(0f, 1f, 0f).endVertex();
    }
}
