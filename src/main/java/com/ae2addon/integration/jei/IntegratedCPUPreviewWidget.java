package com.ae2addon.integration.jei;

import com.ae2addon.AE2Addon;
import com.ae2addon.integration.jei.IntegratedCPUStructure.Role;
import com.mojang.blaze3d.systems.RenderSystem;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/**
 * 集成 CPU 结构 JEI 预览组件。
 * <p>
 * 功能：
 * - 全览模式：3×5×3 结构自动旋转（12s/圈，时间驱动）+ 手动 &lt; &gt; 90° 步进
 * - 分层模式：0-4 层俯视（70° 视角 + 6.5x 放大），清晰看每层 3×3
 * - 图例：方块颜色说明
 * <p>
 * 渲染：方块色块轴测投影（JEI 预览惯例，无 3D 模型依赖）。
 */
public class IntegratedCPUPreviewWidget implements IRecipeWidget, IJeiInputHandler {

    /** 全览旋转速度：12s/圈 */
    private static final double ROTATE_SPEED_FULL = 360.0 / 12.0; // deg/s
    /** 分层旋转速度：20s/圈 */
    private static final double ROTATE_SPEED_LAYER = 360.0 / 20.0;
    /** 全览缩放 */
    private static final double SCALE_FULL = 9.0;
    /** 单层俯视缩放 */
    private static final double SCALE_LAYER = 6.5;
    /** 单层俯视仰角（70° 俯视） */
    private static final double LAYER_ELEVATION = 70.0;

    private final ScreenPosition position;
    private int currentLayer = -1; // -1 = 全览
    private double rotationDeg = 0;
    private boolean autoRotate = true;
    private long lastTickMillis = -1;

    public IntegratedCPUPreviewWidget(ScreenPosition position) {
        this.position = position;
    }

    @Override
    public ScreenPosition getPosition() {
        return position;
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (lastTickMillis < 0) {
            lastTickMillis = now;
            return;
        }
        long dt = now - lastTickMillis;
        lastTickMillis = now;
        if (autoRotate) {
            double speed = currentLayer >= 0 ? ROTATE_SPEED_LAYER : ROTATE_SPEED_FULL;
            rotationDeg = (rotationDeg + speed * dt / 1000.0) % 360.0;
        }
    }

    @Override
    public void drawWidget(GuiGraphics guiGraphics, double mouseX, double mouseY) {
        int cx = position.x() + 70;
        int cy = position.y() + 60;

        if (currentLayer >= 0) {
            drawLayer(guiGraphics, cx, cy, currentLayer);
        } else {
            drawFullStructure(guiGraphics, cx, cy);
        }
        drawControls(guiGraphics);
        drawLegend(guiGraphics);
        drawMaterials(guiGraphics);
    }

    /** 材料统计：显示所需方块数量 */
    private void drawMaterials(GuiGraphics guiGraphics) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        var counts = IntegratedCPUStructure.materialCounts();
        int purple = counts.getOrDefault(net.minecraft.world.level.block.Blocks.PURPLE_CONCRETE, 0);
        int magenta = counts.getOrDefault(net.minecraft.world.level.block.Blocks.MAGENTA_CONCRETE, 0);
        int bookshelf = counts.getOrDefault(net.minecraft.world.level.block.Blocks.BOOKSHELF, 0);

        int x = position.x() + 8;
        int y = position.y() + 46;
        guiGraphics.drawString(font, I18n.get("gui.ae2addon.jei.materials"), x, y, 0xFFDDDDDD);
        // 色块 + 数量
        materialEntry(guiGraphics, x, y + 10, 0xFF7B2FBE, I18n.get("gui.ae2addon.jei.mat.purple_concrete") + " ×" + purple);
        materialEntry(guiGraphics, x + 88, y + 10, 0xFFC74EBD, I18n.get("gui.ae2addon.jei.mat.magenta") + " ×" + magenta);
        materialEntry(guiGraphics, x, y + 20, 0xFF96714C, I18n.get("gui.ae2addon.jei.mat.bookshelf") + " ×" + bookshelf);
        materialEntry(guiGraphics, x + 88, y + 20, 0xFF38BDF8, I18n.get("gui.ae2addon.jei.mat.core") + " ×1");
    }

    private static void materialEntry(GuiGraphics guiGraphics, int x, int y, int color, String label) {
        guiGraphics.fill(x, y + 2, x + 7, y + 9, color);
        guiGraphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                label, x + 11, y, 0xFFAAAAAA);
    }

    /** 控制按钮：旋转 < >（第一排）+ 层切换 < >（第二排） */
    private void drawControls(GuiGraphics guiGraphics) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        int bx = position.x();
        int by = position.y();

        // 第一排：旋转（< > 90° 步进，关闭自动旋转）
        guiGraphics.fill(bx + 8, by + 8, bx + 32, by + 24, 0xAA333333);
        guiGraphics.fill(bx + 32, by + 8, bx + 56, by + 24, 0xAA333333);
        guiGraphics.drawString(font, "<", bx + 17, by + 10, 0xFFFFFFFF);
        guiGraphics.drawString(font, ">", bx + 41, by + 10, 0xFFFFFFFF);
        guiGraphics.drawString(font, I18n.get("gui.ae2addon.jei.rotate"), bx + 60, by + 10, 0xFFAAAAAA);

        // 第二排：层切换（-1 全览 / 0-4 单层）
        guiGraphics.fill(bx + 8, by + 26, bx + 32, by + 42, 0xAA333333);
        guiGraphics.fill(bx + 32, by + 26, bx + 56, by + 42, 0xAA333333);
        guiGraphics.drawString(font, "<", bx + 17, by + 28, 0xFFFFFFFF);
        guiGraphics.drawString(font, ">", bx + 41, by + 28, 0xFFFFFFFF);
        guiGraphics.drawString(font, currentLayer >= 0
                ? I18n.get("gui.ae2addon.jei.layer", currentLayer + 1, 5)
                : I18n.get("gui.ae2addon.jei.overview"), bx + 60, by + 28, 0xFFFFD700);
    }

    // ── 全览模式 ──

    private void drawFullStructure(GuiGraphics guiGraphics, int cx, int cy) {
        double rad = Math.toRadians(rotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double scale = SCALE_FULL;

        for (int y = 0; y < IntegratedCPUStructure.HEIGHT; y++) {
            for (int z = 0; z < IntegratedCPUStructure.DEPTH; z++) {
                for (int x = 0; x < IntegratedCPUStructure.WIDTH; x++) {
                    Role role = IntegratedCPUStructure.layout()[y][z][x];
                    if (role == Role.AIR) continue;

                    // 结构坐标 → 绕 Y 轴旋转（x, z 平面）
                    double sx = (x - 1) * scale;
                    double sz = (z - 1) * scale;
                    double rx = sx * cos - sz * sin;
                    double rz = sx * sin + sz * cos;
                    // y=0 是层1（核心地基）在底部，y=4 是层5（书架屋顶）在顶部
                    double ry = (2 - y) * scale * 0.9;

                    // 轴测投影：等距视角（isometric）
                    int px = (int) (cx + rx - rz * 0.55);
                    int py = (int) (cy + ry + rz * 0.55);

                    int color = colorFor(role);
                    drawBlock(guiGraphics, px, py, (int) scale, (int) scale, color);
                }
            }
        }
    }

    // ── 分层模式 ──

    private void drawLayer(GuiGraphics guiGraphics, int cx, int cy, int layer) {
        Role[][] roles = IntegratedCPUStructure.layer(layer);
        if (roles == null) return;

        double rad = Math.toRadians(rotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double scale = SCALE_LAYER;

        // 俯视视角：z 轴压缩（70° 仰角 → cos70°≈0.34）
        double zCompress = Math.cos(Math.toRadians(LAYER_ELEVATION));

        for (int z = 0; z < IntegratedCPUStructure.DEPTH; z++) {
            for (int x = 0; x < IntegratedCPUStructure.WIDTH; x++) {
                Role role = roles[z][x];
                if (role == Role.AIR) continue;

                double sx = (x - 1) * scale;
                double sz = (z - 1) * scale;
                double rx = sx * cos - sz * sin;
                double rz = sx * sin + sz * cos;

                int px = (int) (cx + rx);
                int py = (int) (cy + rz * zCompress);

                int color = colorFor(role);
                drawBlock(guiGraphics, px, py, (int) scale, (int) (scale * 0.55), color);
            }
        }
    }

    // ── 渲染辅助 ──

    /**
     * 绘制一个 2.5D 等距立方体：顶面 + 左面 + 右面（三面不同明暗）。
     * 方块顶部向上凸起 depth，形成立体感。
     */
    private static void drawBlock(GuiGraphics guiGraphics, int x, int y, int w, int h, int color) {
        int depth = Math.max(2, h / 3);
        int faceW = w / 2;

        // 底面阴影（让方块"浮"起来）
        guiGraphics.fill(x - 1, y + h, x + w + 1, y + h + 2, 0x66000000);

        // 左面（中间色）
        int left = shade(color, 0.72f);
        guiGraphics.fill(x, y + depth, x + faceW, y + h, left);
        // 右面（暗色）
        int right = shade(color, 0.50f);
        guiGraphics.fill(x + faceW, y + depth, x + w, y + h, right);
        // 顶面（亮色）
        int top = shade(color, 1.10f);
        guiGraphics.fill(x, y, x + w, y + depth, top);

        // 轮廓线（增强边界）
        guiGraphics.fill(x, y, x + w, y + 1, 0x33000000);
        guiGraphics.fill(x, y + depth, x + w, y + depth + 1, 0x44000000);
        guiGraphics.fill(x, y, x + 1, y + h, 0x44000000);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, 0x44000000);
        // 顶面中线（左右面分界）
        guiGraphics.fill(x + faceW, y, x + faceW + 1, y + depth, 0x33000000);
    }

    /** 颜色明暗调整：factor > 1 变亮，< 1 变暗 */
    private static int shade(int color, float factor) {
        int r = (int) Math.min(255, ((color >> 16) & 0xFF) * factor);
        int g = (int) Math.min(255, ((color >> 8) & 0xFF) * factor);
        int b = (int) Math.min(255, (color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int colorFor(Role role) {
        return switch (role) {
            case PURPLE -> 0xFF7B2FBE;    // 紫混凝土（真实色）
            case MAGENTA -> 0xFFC74EBD;   // 品红混凝土（真实色）
            case BOOKSHELF -> 0xFF96714C; // 书架（棕，木板色）
            case CORE -> 0xFF38BDF8;      // 集成 CPU 核心（亮蓝，醒目）
            case SLOT -> 0xFF22C55E;      // 元件槽（绿）
            case AIR -> 0x00000000;
        };
    }

    private void drawLegend(GuiGraphics guiGraphics) {
        int x = position.x() + 8;
        int y = position.y() + 118;
        legendEntry(guiGraphics, x, y, 0xFF7B2FBE, I18n.get("gui.ae2addon.jei.mat.purple_concrete"));
        legendEntry(guiGraphics, x + 65, y, 0xFFC74EBD, I18n.get("gui.ae2addon.jei.mat.magenta"));
        legendEntry(guiGraphics, x + 125, y, 0xFF96714C, I18n.get("gui.ae2addon.jei.mat.bookshelf"));
        legendEntry(guiGraphics, x + 175, y, 0xFF38BDF8, I18n.get("gui.ae2addon.jei.mat.core"));
        legendEntry(guiGraphics, x + 225, y, 0xFF22C55E, I18n.get("gui.ae2addon.jei.legend.slot"));
    }

    private static void legendEntry(GuiGraphics guiGraphics, int x, int y, int color, String label) {
        guiGraphics.fill(x, y, x + 8, y + 8, color);
        guiGraphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                label, x + 12, y, 0xFFAAAAAA);
    }

    // ── 输入处理 ──

    @Override
    public ScreenRectangle getArea() {
        // 绝对屏幕坐标（JEI 的 isMouseOver 用绝对坐标判断）
        return new ScreenRectangle(position.x(), position.y(), 150, 116);
    }

    @Override
    public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
        // JEI 传入的是屏幕绝对坐标，转换为 widget 相对坐标（按钮位置是相对 position 的）
        int x = (int) (mouseX - position.x());
        int y = (int) (mouseY - position.y());

        // < 按钮（左旋转 90°）
        if (isInside(x, y, 8, 8, 24, 16)) {
            rotationDeg = (rotationDeg + 90) % 360;
            autoRotate = false;
            return true;
        }
        // > 按钮（右旋转 90°）
        if (isInside(x, y, 32, 8, 24, 16)) {
            rotationDeg = (rotationDeg - 90 + 360) % 360;
            autoRotate = false;
            return true;
        }
        // 层切换 < >（第二排）
        if (isInside(x, y, 8, 26, 24, 16)) {
            currentLayer = currentLayer <= -1 ? 4 : currentLayer - 1;
            if (currentLayer < -1) currentLayer = 4;
            return true;
        }
        if (isInside(x, y, 32, 26, 24, 16)) {
            currentLayer = currentLayer >= 4 ? -1 : currentLayer + 1;
            return true;
        }
        // 点击空白恢复自动旋转
        if (isInside(x, y, 0, 0, 140, 110)) {
            autoRotate = true;
            return true;
        }
        return false;
    }

    private static boolean isInside(int x, int y, int rx, int ry, int w, int h) {
        return x >= rx && x < rx + w && y >= ry && y < ry + h;
    }

    // 工具：方块颜色预览（调试用，也方便后续扩展真实模型渲染）
    static Map<Block, Integer> debugColors() {
        Map<Block, Integer> map = new HashMap<>();
        map.put(Blocks.PURPLE_CONCRETE, colorFor(Role.PURPLE));
        map.put(Blocks.MAGENTA_CONCRETE, colorFor(Role.MAGENTA));
        map.put(Blocks.BOOKSHELF, colorFor(Role.BOOKSHELF));
        return map;
    }
}
