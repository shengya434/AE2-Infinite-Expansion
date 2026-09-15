package com.ae2addon.block;

import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.util.ChatLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.NotNull;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 千机·阿比舒（无限级）核心方块。
 * <p>
 * 3×3×3 多方块，无视一切配方条件，只考虑输入输出。
 * 结构材料：下界合金块、龙蛋。
 * 额定耗电基于催化剂等级：×1 / ×4 / ×20 / ×400（基值 20000 AE/t）
 */
public class QianJiBlock extends BaseEntityBlock {

    public QianJiBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(50.0f, 1200.0f)
                .requiresCorrectToolForDrops()
                .lightLevel(s -> 8));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof QianJiBE qianji)) return InteractionResult.FAIL;

        // 木棍右键 && 未成型 → 尝试成型
        if (player.getItemInHand(hand).getItem() == Items.STICK && !qianji.isFormed()) {
            if (tryForm((ServerLevel) level, pos, qianji, player)) {
                ChatLog.ok(level, pos, "千机成型成功！（朝向："
                        + com.ae2addon.block.multiblock.StructureMatcher.describe(qianji.getFacing()) + "）");
                return InteractionResult.SUCCESS;
            }
            ChatLog.err(level, pos, "千机结构不匹配，无法成型");
            return InteractionResult.FAIL;
        }

        // 已成型 → 打开 GUI
        if (qianji.isFormed()) {
            if (!player.isCrouching()) {
                NetworkHooks.openScreen((net.minecraft.server.level.ServerPlayer) player, qianji, pos);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.FAIL;
    }

    /**
     * 检测并成型 3×3×3 多方块（**四个水平朝向都认** —— 2026-09-15 sensei：完善方向检测）
     */
    private boolean tryForm(ServerLevel level, BlockPos corePos, QianJiBE be, Player player) {
        var match = checkStructure(level, corePos, player);
        if (match == null) return false;

        // 生存模式消耗结构材料，防止刷材料
        if (!player.isCreative()) {
            for (BlockPos p : match.toConsume()) level.destroyBlock(p, false);
        }
        be.setFacing(match.front());
        be.setFormed(true);
        return true;
    }

    /** 结构检测：先试 4 个水平朝向；全不中再按默认朝向给出逐条诊断 */
    @Nullable
    private com.ae2addon.block.multiblock.StructureMatcher.Match checkStructure(ServerLevel level, BlockPos pos, Player player) {
        Block netherite = Blocks.NETHERITE_BLOCK;
        Block dragonEgg = Blocks.DRAGON_EGG;
        BlockPos coreOffset = new BlockPos(1, 1, 0);
        com.ae2addon.block.multiblock.StructureMatcher.Expector expector =
                (x, y, z) -> getExpectQianJiBlock(x, y, z, netherite, dragonEgg);

        // 四个水平朝向先扫一遍（顺带用检测到的朝向刷边界粒子）
        var match = com.ae2addon.block.multiblock.StructureMatcher.match(
                level, pos, Direction.SOUTH, 3, 3, 3, coreOffset, expector);
        spawnCornerParticles(level, com.ae2addon.block.multiblock.StructureMatcher.originFor(
                pos, match != null ? match.body() : Direction.SOUTH, coreOffset), 3, 3, 3);
        if (match != null) return match;

        // 全不中 → 按默认朝向跑一遍，把具体错位告诉玩家（部署/排查体验不变）
        var problems = new ArrayList<com.ae2addon.block.multiblock.StructureMatcher.Problem>();
        com.ae2addon.block.multiblock.StructureMatcher.check(
                level, pos, Direction.SOUTH, 3, 3, 3, coreOffset, expector, problems);
        for (var problem : problems) {
            spawnParticles(level, problem.pos());
            if (problem.expected() == null) {
                player.sendSystemMessage(Component.literal("§b✗ " + formatPos(problem.pos())
                        + " 应为空气，但找到了 " + blockName(problem.found())));
            } else {
                ChatLog.err(level, problem.pos(), "应为 " + problem.expected().getName().getString()
                        + "，但找到了 " + blockName(problem.found()));
            }
        }
        return null;
    }

    /**
     * 判断 (x,y,z) 在 3×3×3 千机结构中的期望方块。
     * 
     * 层1(y=0): 全下界合金块
     * 层2(y=1): (1,1,1)=龙蛋 (x=1,z=1,y=1), (2,1,2)=核心
     * 层3(y=2): 全下界合金块
     */
    @Nullable
    private Block getExpectQianJiBlock(int x, int y, int z,
                                       Block netherite, Block dragonEgg) {
        if (y == 0 || y == 2) return netherite; // 上下两层全下界合金块
        if (y == 1) {
            if (x == 1 && z == 1) return dragonEgg; // 龙蛋在中心
            return netherite;                        // 其他全下界合金块（不含核心自身位置）
        }
        return null;
    }

    // ── 调试辅助方法 ──

    private static String formatPos(BlockPos p) {
        return "§e" + p.getX() + " " + p.getY() + " " + p.getZ() + "§r";
    }

    private static String blockName(BlockState state) {
        return "§7" + state.getBlock().getName().getString() + "§r";
    }

    private static void spawnParticles(ServerLevel level, BlockPos pos) {
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                5, 0.3, 0.3, 0.3, 0.02);
    }

    private static void spawnCornerParticles(ServerLevel level, BlockPos origin, int w, int h, int d) {
        int[] xs = {0, w - 1};
        int[] ys = {0, h - 1};
        int[] zs = {0, d - 1};
        for (int ix : xs) {
            for (int iy : ys) {
                for (int iz : zs) {
                    BlockPos corner = origin.offset(ix, iy, iz);
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.DRAGON_BREATH,
                            corner.getX() + 0.5, corner.getY() + 0.5, corner.getZ() + 0.5,
                            2, 0, 0, 0, 0);
                }
            }
        }
    }

    @Override
    public net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QianJiBE(pos, state);
    }
}
