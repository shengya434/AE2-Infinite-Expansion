package com.ae2addon.integration.jei;

import com.ae2addon.block.multiblock.MultiblockPreviewDef;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.inputs.IJeiInputHandler;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Shared rotating voxel guide for all three multiblock structures. */
public final class IntegratedCPUStructurePreview implements IRecipeWidget, IJeiInputHandler {
    private static final int WIDTH = 240;
    private static final int HEIGHT = 320;
    private static final int VIEW_TOP = 28;
    private static final int VIEW_BOTTOM = 193;
    private static final int ACCENT = 0xFF63E8F2;

    private final ScreenPosition position;
    private final MultiblockPreviewDef definition;
    private final List<Map.Entry<Block, Integer>> materials;
    private final int total;
    private Projected[] projected = new Projected[0];
    private boolean slice;
    private int layer;
    private double angle;
    private long lastTickNanos;

    private static final class Projected {
        final double x, y, z;
        final int color;
        final boolean core;
        int sx, sy;
        double depth;

        Projected(double x, double y, double z, int color, boolean core) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.core = core;
        }
    }

    public IntegratedCPUStructurePreview(ScreenPosition position, MultiblockPreviewDef definition) {
        this.position = position;
        this.definition = definition;
        materials = definition.materialCounts().entrySet().stream()
                .sorted(Comparator.<Map.Entry<Block, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString()))
                .toList();
        total = definition.placementCount();
        layer = definition.coreOffset().getY();
        rebuild();
    }

    @Override
    public ScreenPosition getPosition() {
        return position;
    }

    @Override
    public ScreenRectangle getArea() {
        return new ScreenRectangle(position.x(), position.y(), WIDTH, HEIGHT);
    }

    @Override
    public void tick() {
        long now = System.nanoTime();
        if (lastTickNanos != 0) {
            double elapsed = Math.min((now - lastTickNanos) / 1_000_000_000.0, 0.1);
            angle = (angle + elapsed * 30.0) % 360.0;
        }
        lastTickNanos = now;
    }

    /** Cache only occupied cells; rotation changes their screen coordinates each frame. */
    private void rebuild() {
        var cells = new ArrayList<Projected>();
        double midX = (definition.width() - 1) / 2.0;
        double midY = (definition.height() - 1) / 2.0;
        double midZ = (definition.depth() - 1) / 2.0;
        for (var cell : definition.cells()) {
            if (!slice || cell.y() == layer) {
                cells.add(new Projected(cell.x() - midX, slice ? 0 : cell.y() - midY,
                        cell.z() - midZ, colorFor(cell.block()), false));
            }
        }
        var core = definition.coreOffset();
        if (!slice || layer == core.getY()) {
            cells.add(new Projected(core.getX() - midX, slice ? 0 : core.getY() - midY,
                    core.getZ() - midZ, ACCENT, true));
        }
        projected = cells.toArray(new Projected[0]);
    }

    @Override
    public void drawWidget(GuiGraphics gui, double mouseX, double mouseY) {
        int ox = position.x();
        int oy = position.y();
        Font font = Minecraft.getInstance().font;
        gui.fill(ox + 2, oy + 26, ox + WIDTH - 2, oy + 194, 0xD9182731);
        gui.fill(ox + 3, oy + 27, ox + WIDTH - 3, oy + 28, 0x8059B9C5);
        gui.fill(ox + 3, oy + 192, ox + WIDTH - 3, oy + 193, 0x8059B9C5);

        String mode = Component.translatable(slice ? "gui.ae2addon.jei.cpu.slice" : "gui.ae2addon.jei.cpu.full").getString();
        gui.fill(ox + 3, oy + 3, ox + 98, oy + 22, 0xFF263A43);
        gui.fill(ox + 3, oy + 3, ox + 5, oy + 22, ACCENT);
        gui.drawString(font, mode, ox + 11, oy + 8, 0xFFE1F9FA, false);
        gui.fill(ox + 103, oy + 3, ox + 125, oy + 22, 0xFF263A43);
        gui.fill(ox + 169, oy + 3, ox + 191, oy + 22, 0xFF263A43);
        gui.drawString(font, "<", ox + 111, oy + 8, 0xFFE1F9FA, false);
        gui.drawString(font, ">", ox + 177, oy + 8, 0xFFE1F9FA, false);
        String indicator = slice ? Component.translatable("gui.ae2addon.jei.cpu.layer", layer + 1, definition.height()).getString()
                : definition.width() + "×" + definition.height() + "×" + definition.depth();
        gui.drawCenteredString(font, indicator, ox + 147, oy + 8, 0xFFF6D894);

        drawVoxels(gui, ox, oy);
        gui.drawString(font, Component.translatable("gui.ae2addon.jei.cpu.wheel"),
                ox + 10, oy + 179, 0xFF90AAB3, false);

        gui.drawString(font, Component.translatable("gui.ae2addon.jei.cpu.materials"),
                ox + 8, oy + 201, 0xFF72E9EF, false);
        String count = Component.translatable("gui.ae2addon.jei.cpu.total", total).getString();
        gui.drawString(font, count, ox + WIDTH - 8 - font.width(count), oy + 201, 0xFFF6D894, false);
        for (int i = 0; i < materials.size(); i++) {
            String exact = Integer.toString(materials.get(i).getValue());
            int slotX = ox + 8 + i % 8 * 18;
            int slotY = oy + 218 + i / 8 * 32;
            gui.drawString(font, exact, slotX + 8 - font.width(exact) / 2,
                    slotY + 17, 0xFFB0B0B0, false);
        }
        int shown = Math.min(3, materials.size());
        for (int i = 0; i < shown; i++) {
            var material = materials.get(i);
            int y = oy + 280 + i * 11;
            gui.fill(ox + 9, y + 2, ox + 16, y + 9, colorFor(material.getKey()));
            String amount = "×" + material.getValue();
            int available = WIDTH - 43 - font.width(amount);
            String name = material.getKey().getName().getString();
            while (name.length() > 1 && font.width(name) > available) {
                name = name.substring(0, name.length() - 1);
            }
            gui.drawString(font, name, ox + 21, y, 0xFFD7E4E8, false);
            gui.drawString(font, amount, ox + WIDTH - 8 - font.width(amount), y, 0xFFAAC6CD, false);
        }
        if (materials.size() > shown) {
            String more = Component.translatable("gui.ae2addon.jei.cpu.more", materials.size() - shown).getString();
            gui.drawString(font, more, ox + 21, oy + 311, 0xFF8BA8B0, false);
        }
    }

    private void drawVoxels(GuiGraphics gui, int ox, int oy) {
        double radians = Math.toRadians(angle);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double scale = definition.id().startsWith("integrated_cpu")
                ? (slice ? 4.25 : 2.05)
                : (slice ? (definition.width() > 3 ? 23.0 : 28.0) : 22.0);
        int centerX = ox + WIDTH / 2;
        int centerY = oy + (slice ? 104 : 111);
        for (Projected cell : projected) {
            double rx = cell.x * cos - cell.z * sin;
            double rz = cell.x * sin + cell.z * cos;
            cell.sx = centerX + (int) Math.round((rx - rz) * scale * 0.72);
            cell.sy = centerY + (int) Math.round((rx + rz) * scale * (slice ? 0.30 : 0.34)
                    - cell.y * scale * 0.92);
            cell.depth = rx + rz + cell.y * 0.7;
        }
        Arrays.sort(projected, Comparator.comparingDouble(cell -> cell.depth));
        int halfWidth = Math.max(slice ? 3 : 2, (int) Math.round(scale * 0.43));
        int halfHeight = Math.max(slice ? 2 : 1, (int) Math.round(scale * 0.23));
        int sideHeight = Math.max(slice ? 3 : 2, (int) Math.round(scale * 0.40));
        Projected coreMarker = null;
        for (Projected cell : projected) {
            if (cell.sx < ox + 7 || cell.sx > ox + WIDTH - 7
                    || cell.sy < oy + VIEW_TOP + 5 || cell.sy > oy + VIEW_BOTTOM - 8) {
                continue;
            }
            if (cell.core) {
                coreMarker = cell;
                continue;
            }
            drawVoxel(gui, cell.sx, cell.sy, halfWidth, halfHeight, sideHeight, cell.color);
        }
        if (coreMarker != null) {
            int x = coreMarker.sx;
            int y = coreMarker.sy;
            drawVoxel(gui, x, y, halfWidth + 1, halfHeight + 1, sideHeight + 1, ACCENT);
            gui.fill(x - 5, y - 5, x + 6, y - 4, 0xFFFFFFFF);
            gui.fill(x - 5, y + 5, x + 6, y + 6, 0xFFFFFFFF);
            gui.fill(x - 5, y - 4, x - 4, y + 5, 0xFFFFFFFF);
            gui.fill(x + 5, y - 4, x + 6, y + 5, 0xFFFFFFFF);
        }
    }

    private static void drawVoxel(GuiGraphics gui, int x, int y, int w, int h, int side, int color) {
        for (int row = 0; row <= h; row++) {
            int inset = Math.round(w * row / (float) h);
            gui.fill(x - w + inset, y + row, x + 1, y + row + side,
                    shade(color, 0.63f));
            gui.fill(x, y + row, x + w - inset + 1, y + row + side,
                    shade(color, 0.43f));
        }
        for (int row = -h; row <= h; row++) {
            int radius = Math.round(w * (h - Math.abs(row)) / (float) h);
            gui.fill(x - radius, y + row, x + radius + 1, y + row + 1,
                    shade(color, 1.12f));
        }
    }

    private static int shade(int color, float factor) {
        int r = Math.min(255, (int) (((color >>> 16) & 255) * factor));
        int g = Math.min(255, (int) (((color >>> 8) & 255) * factor));
        int b = Math.min(255, (int) ((color & 255) * factor));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int colorFor(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (id.equals("ae2addon:integrated_cpu")) return ACCENT;
        if (id.equals("ae2addon:assembler_core")) return 0xFFFFB75E;
        if (id.equals("ae2addon:dense_storage_unit")) return 0xFF8C6CFF;
        if (id.equals("ae2addon:blank_storage_unit")) return 0xFF67DD9B;
        if (id.equals("ae2addon:infinite_crafting_storage")) return 0xFFE375DE;
        if (id.equals("ae2addon:infinite_co_processing")) return 0xFFFF805E;
        if (id.equals("minecraft:netherite_block")) return 0xFF585463;
        if (id.equals("minecraft:dragon_egg")) return 0xFFB98AFF;
        if (id.equals("minecraft:gray_concrete")) return 0xFF68737E;
        if (id.equals("minecraft:light_gray_concrete")) return 0xFFC2CDD2;
        if (id.equals("ae2:drive")) return 0xFF49D5E8;
        float hue = ((id.hashCode() & 0x7FFFFFFF) % 360) / 360.0f;
        return 0xFF000000 | Mth.hsvToRgb(hue, 0.42f, 0.85f);
    }

    @Override
    public boolean handleInput(double mouseX, double mouseY, IJeiUserInput input) {
        if (input.getKey().getType() != InputConstants.Type.MOUSE
                || input.getKey().getValue() != 0) {
            return false;
        }
        double x = mouseX;
        double y = mouseY;
        if (inside(x, y, 3, 3, 95, 19)) {
            if (!input.isSimulate()) {
                slice = !slice;
                rebuild();
            }
            return true;
        }
        if (inside(x, y, 103, 3, 22, 19) || inside(x, y, 169, 3, 22, 19)) {
            if (!input.isSimulate()) {
                slice = true;
                layer = Mth.clamp(layer + (x < 130 ? -1 : 1), 0, definition.height() - 1);
                rebuild();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean handleMouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        double x = mouseX;
        double y = mouseY;
        if (!inside(x, y, 3, VIEW_TOP, WIDTH - 6, VIEW_BOTTOM - VIEW_TOP) || scrollDelta == 0) {
            return false;
        }
        int next = Mth.clamp(layer + (scrollDelta > 0 ? 1 : -1), 0, definition.height() - 1);
        if (!slice || next != layer) {
            slice = true;
            layer = next;
            rebuild();
        }
        return true;
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }
}
