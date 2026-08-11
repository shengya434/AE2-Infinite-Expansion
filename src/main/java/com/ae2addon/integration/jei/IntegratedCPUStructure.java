package com.ae2addon.integration.jei;

import com.ae2addon.init.ModBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/**
 * 集成 CPU 3×5×3 多方块结构定义（供 JEI 预览与成型检测共用）。
 * <p>
 * 需求文件（结构权威）字符画（q=紫 w=品红 e=核心 u=元件 t=书架）：
 * <pre>
 *   层1(y=0): qqq / qwq / qeq    核心 e 在 (1,0,2)
 *   层2(y=1): qwq / wuw / qwq    u 槽中心 (1,1,1)
 *   层3(y=2): qwq / wuw / qwq    u 槽 (1,2,1)
 *   层4(y=3): qqq / qwq / qqq    w 中心
 *   层5(y=4): t t /  t / t t     书架四角+中心（5 个）
 * </pre>
 * 结构坐标 (x, y, z)：x,z ∈ [0,2]，y ∈ [0,4]。
 */
public final class IntegratedCPUStructure {

    /** 结构尺寸 */
    public static final int WIDTH = 3;
    public static final int HEIGHT = 5;
    public static final int DEPTH = 3;

    /** 方块角色 */
    public enum Role {
        PURPLE,   // 紫混凝土
        MAGENTA,  // 品红混凝土
        BOOKSHELF, // 书架
        CORE,     // 集成 CPU（机器方块，e）
        SLOT,     // 内部 u 槽（无限存储/并行/工作台）
        AIR       // 空气
    }

    private static final Role[][][] LAYOUT = buildLayout();

    private IntegratedCPUStructure() {
    }

    private static Role[][][] buildLayout() {
        Role[][][] layout = new Role[HEIGHT][DEPTH][WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < DEPTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    layout[y][z][x] = roleAt(x, y, z);
                }
            }
        }
        return layout;
    }

    /** 结构坐标 (x,y,z) 的角色 */
    private static Role roleAt(int x, int y, int z) {
        // 层5(y=4)：书架四角 + 中心，边中点空气
        if (y == 4) {
            if (x == 1 && z == 1) return Role.BOOKSHELF;
            if (x != 1 && z != 1) return Role.BOOKSHELF;
            return Role.AIR;
        }
        // 层1(y=0)：qqq / qwq / qeq → 核心 (1,0,2)，品红 (1,0,1)
        if (y == 0) {
            if (x == 1 && z == 2) return Role.CORE;
            if (x == 1 && z == 1) return Role.MAGENTA;
            return Role.PURPLE;
        }
        // 层2/3(y=1/2)：qwq / wuw / qwq → u 中心，w 四边中点，四角紫
        if (y == 1 || y == 2) {
            if (x == 1 && z == 1) return Role.SLOT;
            if (x == 1 || z == 1) return Role.MAGENTA;
            return Role.PURPLE;
        }
        // 层4(y=3)：qqq / qwq / qqq → w 中心
        if (y == 3) {
            if (x == 1 && z == 1) return Role.MAGENTA;
            return Role.PURPLE;
        }
        return Role.AIR;
    }

    /** 获取某层（y）的角色矩阵 [z][x] */
    public static Role[][] layer(int y) {
        if (y < 0 || y >= HEIGHT) return null;
        return LAYOUT[y];
    }

    /** 全结构角色 */
    public static Role[][][] layout() {
        return LAYOUT;
    }

    /** 角色 → 实际方块 */
    public static Block blockFor(Role role) {
        return switch (role) {
            case PURPLE -> Blocks.PURPLE_CONCRETE;
            case MAGENTA -> Blocks.MAGENTA_CONCRETE;
            case BOOKSHELF -> Blocks.BOOKSHELF;
            case CORE -> ModBlocks.INTEGRATED_CPU.get();
            case SLOT -> null; // 元件槽：不固定方块
            case AIR -> null;
        };
    }

    /** 材料统计：方块 → 数量（核心与元件槽不计入结构材料） */
    public static Map<Block, Integer> materialCounts() {
        Map<Block, Integer> counts = new HashMap<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < DEPTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    Role role = LAYOUT[y][z][x];
                    if (role == Role.AIR || role == Role.CORE || role == Role.SLOT) continue;
                    Block block = blockFor(role);
                    if (block != null) {
                        counts.merge(block, 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    /** 总结构方块数（不含核心与元件槽） */
    public static int totalStructureBlocks() {
        int total = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < DEPTH; z++) {
                for (int x = 0; x < WIDTH; x++) {
                    Role role = LAYOUT[y][z][x];
                    if (role != Role.AIR && role != Role.CORE && role != Role.SLOT) {
                        total++;
                    }
                }
            }
        }
        return total;
    }
}
