package com.ae2addon.block.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only JEI description. Multiblock validation remains in the block classes. */
public final class MultiblockPreviewDef {
    @FunctionalInterface
    public interface ExpectFn {
        @Nullable Block at(int x, int y, int z);
    }

    public record Cell(int x, int y, int z, Block block) {}

    private final String id;
    private final Component title;
    private final int width, height, depth;
    private final BlockPos coreOffset;
    private final ExpectFn expectAt;
    private final ItemStack icon;
    private final List<Cell> cells;
    private final Map<Block, Integer> materialCounts;

    public MultiblockPreviewDef(String id, Component title, int width, int height, int depth,
                                BlockPos coreOffset, ExpectFn expectAt, ItemStack icon) {
        this.id = id;
        this.title = title;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.coreOffset = coreOffset;
        this.expectAt = expectAt;
        this.icon = icon.copy();

        var occupied = new ArrayList<Cell>();
        var counts = new HashMap<Block, Integer>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    if (coreOffset.getX() == x && coreOffset.getY() == y && coreOffset.getZ() == z) {
                        continue;
                    }
                    Block block = expectAt(x, y, z);
                    if (block != null) {
                        occupied.add(new Cell(x, y, z, block));
                        counts.merge(block, 1, Integer::sum);
                    }
                }
            }
        }
        cells = List.copyOf(occupied);
        materialCounts = Map.copyOf(counts);
    }

    public String id() { return id; }
    public Component title() { return title; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public BlockPos coreOffset() { return coreOffset; }
    @Nullable
    public Block expectAt(int x, int y, int z) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth
                || coreOffset.getX() == x && coreOffset.getY() == y && coreOffset.getZ() == z) {
            return null;
        }
        return expectAt.at(x, y, z);
    }
    public ItemStack icon() { return icon.copy(); }
    public List<Cell> cells() { return cells; }
    public Map<Block, Integer> materialCounts() { return materialCounts; }
    public int placementCount() { return cells.size(); }
}
