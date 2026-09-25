package com.ae2addon.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * 「放置被阻挡」**红色方框**的世界渲染（2026-09-24）。
 * <p>
 * sensei 实测：用红色 dust 粒子画的方框**会被方块挡住**，看不出是哪一格。
 * 所以改成本渲染器：
 * <ul>
 *   <li>用 {@link RenderType#lines()} 画 12 条棱（细线框）；</li>
 *   <li>渲染前后 {@code depthMask} 关/开 + {@code depthTest} 关闭再恢复 ——
 *       **关掉深度测试就穿墙可见**（这正是"穿透显示"的关键）；</li>
 *   <li>位置由 {@code IntegratedCpuConflictPacket} 从服务端下发，客户端只存一个坐标 + 倒计时。</li>
 * </ul>
 * 渲染时机：{@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}（当帧其余内容都画完了再叠上去）。
 */
@Mod.EventBusSubscriber(modid = com.ae2addon.AE2Addon.MODID, value = Dist.CLIENT)
public final class IntegratedCpuConflictRenderer {

    /** 当前高亮的方块位置（null = 不画） */
    @Nullable
    private static BlockPos highlightPos;

    /** 客户端剩余显示 tick */
    private static int remainingTicks;

    /** 线框颜色：红（带一点透明度，避免刺眼） */
    private static final float R = 1.0f;
    private static final float G = 0.15f;
    private static final float B = 0.15f;
    private static final float A = 1.0f;

    private IntegratedCpuConflictRenderer() {
    }

    /**
     * 设置/清除高亮（由网络包调用；客户端线程）。
     *
     * @param ticks 0 = 立即清除
     */
    public static void setHighlight(BlockPos pos, int ticks) {
        if (ticks <= 0) {
            highlightPos = null;
            remainingTicks = 0;
            return;
        }
        highlightPos = pos.immutable();
        remainingTicks = ticks;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        BlockPos pos = highlightPos;
        if (pos == null) {
            return;
        }
        if (--remainingTicks <= 0) {
            highlightPos = null;
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // 距离太远就不画（200 格以外看不清也没意义）
        var cam = event.getCamera().getPosition();
        if (cam.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 200 * 200) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);

        // 微放大一点点，避免与方块表面共面闪烁（z-fighting）
        float lo = -0.002f;
        float hi = 1.002f;

        // ⚠ 顺序很重要：先把线写进缓冲区、**endBatch 画出来**（此时才真正提交），
        //   再关深度测试重画一遍 → 第二遍就会**穿墙可见**（sensei 要的"穿透显示"）。
        //   反过来写（先关深度再 endBatch）会因为 RenderType 自己重设状态而失效。
        drawBox(buffers, pose, lo, hi);

        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
        drawBox(buffers, pose, lo, hi);          // 穿墙那一遍
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();

        pose.popPose();
    }

    /** 把 12 条棱写进 lines 缓冲区并立刻提交 */
    private static void drawBox(MultiBufferSource.BufferSource buffers, PoseStack pose,
                                float lo, float hi) {
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());

        // 底面 4 条
        addLine(vc, m, lo, lo, lo, hi, lo, lo);
        addLine(vc, m, hi, lo, lo, hi, lo, hi);
        addLine(vc, m, hi, lo, hi, lo, lo, hi);
        addLine(vc, m, lo, lo, hi, lo, lo, lo);
        // 顶面 4 条
        addLine(vc, m, lo, hi, lo, hi, hi, lo);
        addLine(vc, m, hi, hi, lo, hi, hi, hi);
        addLine(vc, m, hi, hi, hi, lo, hi, hi);
        addLine(vc, m, lo, hi, hi, lo, hi, lo);
        // 4 条竖棱
        addLine(vc, m, lo, lo, lo, lo, hi, lo);
        addLine(vc, m, hi, lo, lo, hi, hi, lo);
        addLine(vc, m, hi, lo, hi, hi, hi, hi);
        addLine(vc, m, lo, lo, hi, lo, hi, hi);

        buffers.endBatch(RenderType.lines());
    }

    /** 画一条线（两端同色：红） */
    private static void addLine(VertexConsumer vc, Matrix4f m,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2) {
        vc.vertex(m, x1, y1, z1).color(R, G, B, A).normal(0f, 1f, 0f).endVertex();
        vc.vertex(m, x2, y2, z2).color(R, G, B, A).normal(0f, 1f, 0f).endVertex();
    }
}
