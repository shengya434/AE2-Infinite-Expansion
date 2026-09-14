package com.ae2addon.block.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 多方块结构模式定义与匹配工具。
 * <p>
 * 每个多方块由核心方块 + 若干层组成。
 * 玩家手持木棍右键核心方块 → 消耗结构物品 → 自动成型。
 */
public class MultiblockPattern {

    private final int width;
    private final int height;
    private final int depth;
    private final Predicate<BlockState>[][][] pattern; // [y][z][x]
    private final BlockPos coreOffset;                  // 核心方块在模式中的相对位置

    @SuppressWarnings("unchecked")
    public MultiblockPattern(int width, int height, int depth,
                             Layer[] layers, BlockPos coreOffset) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.coreOffset = coreOffset;
        this.pattern = new Predicate[height][depth][width];

        if (layers.length != height) {
            throw new IllegalArgumentException("Layer count " + layers.length
                    + " doesn't match height " + height);
        }

        // layers[0] = bottom, layers[height-1] = top
        for (int y = 0; y < height; y++) {
            String[] rows = layers[y].rows;
            if (rows.length != depth) {
                throw new IllegalArgumentException("Layer " + y + " has " + rows.length
                        + " rows, expected " + depth);
            }
            for (int z = 0; z < depth; z++) {
                String row = rows[z];
                if (row.length() != width) {
                    throw new IllegalArgumentException("Layer " + y + " row " + z
                            + " has length " + row.length() + ", expected " + width);
                }
                for (int x = 0; x < width; x++) {
                    char ch = row.charAt(x);
                    pattern[y][z][x] = ch == ' ' ? ALWAYS_TRUE : charPredicate(ch);
                }
            }
        }
    }

    /**
     * 在指定世界位置匹配多方块结构。
     *
     * @param level    世界
     * @param corePos  核心方块的世界坐标
     * @return 如果匹配成功返回需要消耗的方块位置列表，否则返回 null
     */
    public MatchResult match(Level level, BlockPos corePos) {
        BlockPos origin = corePos.subtract(coreOffset);
        List<BlockPos> toConsume = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    BlockPos checkPos = origin.offset(x, y, z);

                    if (checkPos.equals(corePos)) {
                        // 核心方块位置特殊处理：不消耗自身
                        continue;
                    }

                    Predicate<BlockState> predicate = pattern[y][z][x];
                    if (predicate == ALWAYS_TRUE) continue;

                    BlockState state = level.getBlockState(checkPos);
                    if (!predicate.test(state)) {
                        return null; // 匹配失败
                    }
                    toConsume.add(checkPos);
                }
            }
        }

        return new MatchResult(toConsume);
    }

    public static final Predicate<BlockState> ALWAYS_TRUE = s -> true;

    private static final int CHAR_DEFINITIONS = 26;
    private static final Predicate<BlockState>[] EMPTY_PREDICATES = new Predicate[CHAR_DEFINITIONS];

    static {
        // 所有字符默认为 ALWAYS_TRUE（空格已在构造函数中处理）
        for (int i = 0; i < CHAR_DEFINITIONS; i++) {
            EMPTY_PREDICATES[i] = ALWAYS_TRUE;
        }
    }

    private final Predicate<BlockState>[] charPredicates = new Predicate[CHAR_DEFINITIONS];

    /**
     * 设置某个字符对应的方块匹配条件。
     *
     * @param ch  字符 (a-z)
     * @param block 需要匹配的方块
     */
    public void setPredicate(char ch, Block block) {
        int idx = ch - 'a';
        if (idx >= 0 && idx < CHAR_DEFINITIONS) {
            charPredicates[idx] = state -> state.getBlock() == block;
        }
    }

    /**
     * 设置某个字符对应的方块匹配条件（支持多个方块）。
     */
    public void setPredicate(char ch, Block... blocks) {
        int idx = ch - 'a';
        if (idx >= 0 && idx < CHAR_DEFINITIONS) {
            charPredicates[idx] = state -> {
                for (Block b : blocks) {
                    if (state.getBlock() == b) return true;
                }
                return false;
            };
        }
    }

    private Predicate<BlockState> charPredicate(char ch) {
        int idx = ch - 'a';
        if (idx >= 0 && idx < CHAR_DEFINITIONS) {
            Predicate<BlockState> p = charPredicates[idx];
            return p != null ? p : ALWAYS_TRUE;
        }
        return ALWAYS_TRUE;
    }

    /**
     * 默认字符对应关系 (a-z) — 子类可覆盖
     */
    protected Predicate<BlockState> getCharPredicate(char ch) {
        return charPredicate(ch);
    }

    /**
     * 一层定义
     */
    public record Layer(String... rows) {}

    /**
     * 匹配结果
     */
    public record MatchResult(List<BlockPos> toConsume) {
        public boolean matched() { return toConsume != null; }
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public BlockPos getCoreOffset() { return coreOffset; }
}
