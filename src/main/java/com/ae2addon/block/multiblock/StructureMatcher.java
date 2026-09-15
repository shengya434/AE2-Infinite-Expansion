package com.ae2addon.block.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 多方块**方向检测**通用工具（2026-09-15 sensei：先把多方块方向检测完善）。
 * <p>
 * 结构用**局部坐标**描述（原点 = 结构最小角，(0,0,0)，xyz 递增），检测时依次尝试
 * **4 个水平朝向** —— 玩家从任何一边搭都能成型（旧实现只认硬编码的那一种朝向）。
 * <p>
 * 局部 → 世界 的映射（{@code body} = 局部 +z 轴在世界的方向，即结构体从核心“伸出去”的方向）：
 * <pre>
 *   局部 +y（向上）  →  UP
 *   局部 +z（进深）  →  body
 *   局部 +x（横排）  →  body.getCounterClockWise()
 * </pre>
 * 某块在世界里的位置 = {@code corePos + right*(x - coreOffset.x) + UP*(y - coreOffset.y) + body*(z - coreOffset.z)}。
 * <p>
 * 「机器正面」（{@code front}）自动推出：核心落在进深轴的**前半**（{@code coreOffset.z*2 < depth}）时，
 * 正面就是局部 -z（{@code body.getOpposite()}），否则是局部 +z（{@code body}）——
 * 这样两个结构各自的“正面”定义一致（都是**核心外露的那一面**），不用每个结构自己记约定。
 */
public final class StructureMatcher {

    /** 结构定义：返回局部坐标处**期望**的方块；{@code null} = 该处必须是空气 */
    public interface Expector {
        @Nullable
        Block expected(int x, int y, int z);
    }

    /** 匹配失败的一条问题（诊断/提示用） */
    public record Problem(BlockPos pos, @Nullable Block expected, BlockState found) {}

    /**
     * 匹配结果。
     *
     * @param body      检测到的「局部 +z 轴」世界方向（结构从核心伸出去的方向）
     * @param front     检测到的**机器正面**（核心外露面朝向，即成型时玩家站的那一侧）
     * @param toConsume 需要消耗的结构方块
     */
    public record Match(Direction body, Direction front, List<BlockPos> toConsume) {}

    private StructureMatcher() {}

    /**
     * 依次尝试 4 个水平朝向，返回**第一个**完全匹配的结果；都不匹配返回 null。
     *
     * @param defaultBody 旧硬编码布局对应的「局部 +z 轴」朝向（旋转从这里开始试）
     */
    @Nullable
    public static Match match(ServerLevel level, BlockPos corePos, Direction defaultBody,
                              int width, int height, int depth, BlockPos coreOffset,
                              Expector expector) {
        for (int turn = 0; turn < 4; turn++) {
            Direction body = rotateAroundY(defaultBody, turn);
            var toConsume = check(level, corePos, body, width, height, depth, coreOffset, expector, null);
            if (toConsume != null) {
                return new Match(body, frontFor(body, depth, coreOffset.getZ()), toConsume);
            }
        }
        return null;
    }

    /** 机器正面：核心在进深轴前半 → 正面是局部 -z，否则是局部 +z */
    public static Direction frontFor(Direction body, int depth, int coreLocalZ) {
        return coreLocalZ * 2 < depth ? body.getOpposite() : body;
    }

    /**
     * 单个朝向的检测。
     *
     * @param problems 非 null 时把不匹配的位置写进去（只为给出人话提示，不参与判定）
     * @return 匹配则返回要消耗的方块位置；不匹配返回 null
     */
    @Nullable
    public static List<BlockPos> check(ServerLevel level, BlockPos corePos, Direction body,
                                       int width, int height, int depth, BlockPos coreOffset,
                                       Expector expector, @Nullable List<Problem> problems) {
        BlockPos origin = originFor(corePos, body, coreOffset);
        Direction right = body.getCounterClockWise();
        List<BlockPos> toConsume = new ArrayList<>();

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    BlockPos checkPos = origin.offset(
                            right.getStepX() * x + body.getStepX() * z,
                            y,
                            right.getStepZ() * x + body.getStepZ() * z);
                    if (checkPos.equals(corePos)) continue;   // 核心自身不检查、不消耗

                    Block expected = expector.expected(x, y, z);
                    BlockState state = level.getBlockState(checkPos);
                    if (expected == null) {
                        if (state.isAir()) continue;
                        if (problems != null) problems.add(new Problem(checkPos, null, state));
                        return null;
                    }
                    if (state.getBlock() != expected) {
                        if (problems != null) problems.add(new Problem(checkPos, expected, state));
                        return null;
                    }
                    toConsume.add(checkPos);
                }
            }
        }
        return toConsume;
    }

    /** 该朝向下结构最小角的世界坐标 */
    public static BlockPos originFor(BlockPos corePos, Direction body, BlockPos coreOffset) {
        Direction right = body.getCounterClockWise();
        return corePos.offset(
                -(right.getStepX() * coreOffset.getX() + body.getStepX() * coreOffset.getZ()),
                -coreOffset.getY(),
                -(right.getStepZ() * coreOffset.getX() + body.getStepZ() * coreOffset.getZ()));
    }

    /** 水平绕 Y 旋转若干次（只接受水平朝向；传入非水平时按 SOUTH 处理） */
    public static Direction rotateAroundY(Direction base, int quarterTurns) {
        Direction d = base != null && base.getAxis().isHorizontal() ? base : Direction.SOUTH;
        for (int i = 0; i < (quarterTurns & 3); i++) {
            d = d.getClockWise();
        }
        return d;
    }

    /** 朝向的中文名（聊天栏提示用） */
    public static String describe(Direction facing) {
        return switch (facing) {
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
            default -> facing.getName();
        };
    }
}
