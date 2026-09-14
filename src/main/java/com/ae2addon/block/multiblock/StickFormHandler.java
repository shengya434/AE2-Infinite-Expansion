package com.ae2addon.block.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.BiConsumer;

/**
 * 木棍右键成型处理器。
 * <p>
 * 通用处理逻辑：玩家持木棍右键核心方块 → 检查多方块结构 → 消耗结构材料 → 成型。
 */
public class StickFormHandler {

    /**
     * 检查多方块结构，如果匹配则消耗材料并调用成型回调。
     *
     * @param level    世界
     * @param pos      核心方块位置
     * @param player   玩家
     * @param detector 结构检测器，返回 null=不匹配, 非null=要消耗的方块列表
     * @param onForm   成型回调: (level, pos) → void
     * @return InteractionResult
     */
    public static InteractionResult tryForm(Level level, BlockPos pos,
                                            Player player, InteractionHand hand,
                                            StructureDetector detector,
                                            BiConsumer<ServerLevel, BlockPos> onForm) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player.getItemInHand(hand).getItem() != Items.STICK) {
            return InteractionResult.PASS;
        }

        var toConsume = detector.detect((ServerLevel) level, pos);
        if (toConsume == null) {
            return InteractionResult.FAIL;
        }

        // 消耗结构材料
        for (BlockPos p : toConsume) {
            level.destroyBlock(p, false); // 破坏但不掉落
        }

        // 调用成型回调
        onForm.accept((ServerLevel) level, pos);

        return InteractionResult.SUCCESS;
    }

    @FunctionalInterface
    public interface StructureDetector {
        /**
         * @return 需要消耗的方块位置列表，如果不匹配返回 null
         */
        java.util.List<BlockPos> detect(ServerLevel level, BlockPos corePos);
    }

    /**
     * 简单的方块位置断言：在指定偏移量处的方块是否是指定方块
     */
    public static class CheckBuilder {
        private final java.util.List<BlockPos> toConsume = new java.util.ArrayList<>();
        private ServerLevel level;
        private BlockPos corePos;

        public CheckBuilder at(ServerLevel level, BlockPos corePos) {
            this.level = level;
            this.corePos = corePos;
            return this;
        }

        /**
         * 检查相对偏移位置是否是指定方块。
         * 如果是，添加到消耗列表。
         */
        public CheckBuilder check(int dx, int dy, int dz, Block expected) {
            BlockPos p = corePos.offset(dx, dy, dz);
            BlockState state = level.getBlockState(p);
            if (state.getBlock() != expected) {
                // 如果是空气也算不匹配
                toConsume.clear();
                return this;
            }
            toConsume.add(p);
            return this;
        }

        /**
         * 检查相对偏移位置是否为指定方块之一。
         */
        public CheckBuilder checkAny(int dx, int dy, int dz, Block... expected) {
            BlockPos p = corePos.offset(dx, dy, dz);
            BlockState state = level.getBlockState(p);
            boolean match = false;
            for (Block b : expected) {
                if (state.getBlock() == b) { match = true; break; }
            }
            if (!match) {
                toConsume.clear();
                return this;
            }
            toConsume.add(p);
            return this;
        }

        /**
         * 跳过某个位置（通常是核心方块自身位置）
         */
        public CheckBuilder skip(int dx, int dy, int dz) {
            // 什么也不做
            return this;
        }

        public java.util.List<BlockPos> build() {
            return toConsume.isEmpty() ? null : new java.util.ArrayList<>(toConsume);
        }
    }
}
