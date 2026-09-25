package com.ae2addon.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 自建合成方块：**巨型存储合成单元** / **空白存储合成单元**。
 * <p>
 * ⚠ 2026-09-25 sensei：「我不是说不要特殊属性嘛？很容易出问题的吧」——
 * 之前的实现继承 {@code CraftingUnitBlock}（带 STORAGE_256K 存储、formed/powered 属性、
 * 自己的 CraftingBlockEntity），确实会牵扯 AE2 的分簇/存储累加逻辑。
 * 现已**改成彻底普通的方块**：没有方块实体、没有 blockstate 属性、不进任何 AE2 系统，
 * 纯粹当多方块结构的占位/装饰块用（结构判定只比对方块 id）。
 * <p>
 * 用法：集成 CPU 多方块模板里，原本 `ae2:256k_crafting_storage` 与 `ae2:crafting_unit`
 * 的位置由这两块替代（见 {@code data/ae2addon/integrated_cpu_structure.txt}）。
 */
public class CraftingUnitStorageBlock extends Block {

    /** @param dense true = 巨型存储合成单元，false = 空白存储合成单元（仅贴图/命名不同） */
    public CraftingUnitStorageBlock(boolean dense) {
        // 普通方块属性：挖掘手感对齐 sky stone 一族（结构本身是大型多方块）
        super(BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.STONE)
                .strength(2.0F, 6.0F)
                .requiresCorrectToolForDrops());
        this.dense = dense;
    }

    /** true = 巨型存储合成单元（与空白版的主要区别在贴图/命名） */
    private final boolean dense;

    public boolean isDense() {
        return dense;
    }
}
