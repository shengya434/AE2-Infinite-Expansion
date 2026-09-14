package com.ae2addon.block;

import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.item.UniversalStorageCell;
import com.ae2addon.util.ChatLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 无限驱动器核心方块（多方块 5×5×5）
 * <p>
 * 成型后：512 槽位，仅允许插入我们的无限元件。
 * 结构材料：灰色混凝土、淡灰色混凝土、ME 驱动器。
 */
public class InfiniteDriveBlock extends BaseEntityBlock {

    private static Block cachedMeDrive = null;

    public InfiniteDriveBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(3.0f)
                .requiresCorrectToolForDrops());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof InfiniteDriveBE driveBE)) return InteractionResult.FAIL;

        if (player.getItemInHand(hand).getItem() == Items.STICK && !driveBE.isFormed()) {
            if (tryForm((ServerLevel) level, pos, driveBE, player)) {
                ChatLog.ok(level, pos, "驱动器成型成功，已接入 AE 网络");
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.FAIL;
        }

        // 已成型 → 打开 AE2 原版驱动器面板（DriveMenu，10 格细胞槽）
        if (driveBE.isFormed()) {
            driveBE.openMenu(player);
            ChatLog.info(level, pos, "打开驱动器面板");
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.FAIL;
    }

    private boolean tryForm(ServerLevel level, BlockPos corePos, InfiniteDriveBE be, Player player) {
        List<BlockPos> toConsume = checkStructure(level, corePos, player);
        if (toConsume == null) return false;

        // 生存模式消耗结构材料，防止刷材料
        if (!player.isCreative()) {
            for (BlockPos p : toConsume) level.destroyBlock(p, false);
        }
        be.setFormed(true);
        return true;
    }

    @Nullable
    private List<BlockPos> checkStructure(ServerLevel level, BlockPos pos, Player player) {
        Block gray = Blocks.GRAY_CONCRETE;
        Block lightGray = Blocks.LIGHT_GRAY_CONCRETE;
        Block meDrive = getMeDriveBlock();
        if (meDrive == null) return null;

        BlockPos origin = pos.offset(-2, 0, -4);
        List<BlockPos> toConsume = new ArrayList<>();

        // 在结构八个角刷粒子帮助定位
        spawnCornerParticles(level, origin);

        for (int y = 0; y < 5; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 5; x++) {
                    BlockPos checkPos = origin.offset(x, y, z);
                    if (checkPos.equals(pos)) continue;

                    BlockState stateAt = level.getBlockState(checkPos);
                    Block expected = getExpectBlock(x, y, z, gray, lightGray, meDrive);
                    if (expected == null) {
                        if (!stateAt.isAir()) {
                            // 应为空气但有方块 → 粒子标记
                            spawnParticles(level, checkPos, 0.0, 1.0, 1.0); // 青色：错误
                            player.sendSystemMessage(Component.literal("§b✗ 位置 " + formatPos(checkPos) + " 应该是空气，但找到了 " + blockName(stateAt)));
                            return null;
                        }
                        continue;
                    }
                    if (stateAt.getBlock() != expected) {
                        // 方块不匹配 → 粒子标记
                        spawnParticles(level, checkPos, 1.0, 0.2, 0.2); // 红色：错误
                        player.sendSystemMessage(Component.literal("§c✗ 位置 " + formatPos(checkPos) + " 应该是 §f" + expected.getName().getString() + "§c，但找到了 " + blockName(stateAt)));
                        return null;
                    }
                    // 正确 → 绿色粒子闪烁
                    spawnParticles(level, checkPos, 0.2, 1.0, 0.2); // 绿色：正确
                    toConsume.add(checkPos);
                }
            }
        }
        return toConsume;
    }

    private static String formatPos(BlockPos p) {
        return "§e" + p.getX() + " " + p.getY() + " " + p.getZ() + "§r";
    }

    private static String blockName(BlockState state) {
        return "§7" + state.getBlock().getName().getString() + "§r";
    }

    /**
     * 在某个位置刷彩色粒子（焰火火箭星星的效果）
     */
    private static void spawnParticles(ServerLevel level, BlockPos pos, double r, double g, double b) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;
        // 用 ITEM_SNOWBALL 模拟彩色粒子（或用 FIREWORK 粒子）
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                x, y, z, 5, 0.3, 0.3, 0.3, 0.02);
    }

    private static void spawnCornerParticles(ServerLevel level, BlockPos origin) {
        // 在结构 8 个角刷龙息粒子标记边界
        int[] xs = {0, 4};
        int[] ys = {0, 4};
        int[] zs = {0, 4};
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

    /**
     * 需求结构布局 (5×5×5)：
     * <pre>
     * 层1(y=0): 全部灰色混凝土，z=4,x=2为机器核心
     *   eeeee,eeeee,eeeee,eeeee,eehee
     * 层2(y=1):
     *   eeeee, e _ m _ e, e _ m _ e, e _ m _ e, e _ m _ e
     * 层3(y=2): ME驱动器层
     *   eeeee, emmme, emnme, emmme, emmme
     * 层4(y=3): 同层2
     * 层5(y=4): 全部灰色混凝土
     * </pre>
     * e=灰色混凝土, m=淡灰色混凝土, n=ME驱动器, _=空气
     * 核心自身位置在循环中已跳过
     */
    @Nullable
    private Block getExpectBlock(int x, int y, int z,
                                 Block gray, Block lightGray, Block meDrive) {
        // 层1 & 5: 全部灰色混凝土
        if (y == 0 || y == 4) {
            return gray;
        }

        // 层2 & 4
        if (y == 1 || y == 3) {
            if (z == 0) return gray; // z=0 全排灰色
            // z=1..4: 灰色在 x=0/4, 淡灰在 x=2, 其余为空气
            if (x == 0 || x == 4) return gray;
            if (x == 2) return lightGray;
            return null; // x=1, x=3: 空气
        }

        // 层3: ME驱动器层
        if (y == 2) {
            if (z == 0) return gray; // z=0 全排灰色
            if (z == 1 || z == 3 || z == 4) {
                // emmme: 灰色在 x=0/4, 其余淡灰
                if (x == 0 || x == 4) return gray;
                return lightGray;
            }
            if (z == 2) {
                // emnme: x=0/4灰色, x=2 ME驱动器, 其余淡灰
                if (x == 0 || x == 4) return gray;
                if (x == 2) return meDrive;
                return lightGray; // x=1, x=3
            }
        }

        return null;
    }

    private static Block getMeDriveBlock() {
        if (cachedMeDrive == null) {
            cachedMeDrive = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getValue(new net.minecraft.resources.ResourceLocation("ae2:drive"));
        }
        return cachedMeDrive;
    }

    /**
     * 拆掉时把驱动器内容物（10 格元件）也吐出来。
     * <p>
     * 本方块直接 extends BaseEntityBlock，不走 AE2 的 AEBaseEntityBlock ——
     * AE2 把 {@code addAdditionalDrops} 挂在 AEBaseEntityBlock#onRemove 里，
     * 这里按同样时机手动调一次；否则元件会跟方块一起消失（loot table 只能掉方块本体）。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof InfiniteDriveBE be) {
            List<ItemStack> contents = new ArrayList<>();
            be.addAdditionalDrops(level, pos, contents);
            for (ItemStack stack : contents) {
                if (!stack.isEmpty()) popResource(level, pos, stack);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InfiniteDriveBE(pos, state);
    }
}
