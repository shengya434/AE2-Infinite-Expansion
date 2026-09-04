package com.ae2addon.block;

import appeng.block.crafting.CraftingUnitBlock;
import appeng.block.crafting.CraftingUnitType;
import appeng.block.AEBaseEntityBlock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/**
 * 无限级装配处理器·核心方块（v0.3 M3）。
 * <p>
 * 必须继承 CraftingUnitBlock（不能改 Block）：CraftingBlockEntity.onReady 强转
 * block -> AbstractCraftingUnitBlock；AEBaseEntityBlock 依赖 blockEntityClass。
 * 与普通 crafting unit / 集成 CPU 同簇（3×3×3 框架+核心），簇成型由 AE2 原版
 * CraftingCPUCluster 机制自动完成（相邻 unit 检测），无需手搓成型。
 */
public class AssemblerCoreBlock extends CraftingUnitBlock {

    private static boolean BLOCK_ENTITY_CLASS_INIT = false;

    public AssemblerCoreBlock() {
        super(CraftingUnitType.STORAGE_256K);
        // AEBaseEntityBlock 的 blockEntityClass 在 BlockEntityType 构造器中未被设置，
        // 必须反射设为 AssemblerCoreBE.class 防 NPE（同 IntegratedCPUBlock 做法）。
        if (!BLOCK_ENTITY_CLASS_INIT) {
            BLOCK_ENTITY_CLASS_INIT = true;
            try {
                var f = ObfuscationReflectionHelper.findField(
                        AEBaseEntityBlock.class, "blockEntityClass");
                f.setAccessible(true);
                f.set(this, AssemblerCoreBE.class);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AssemblerCoreBE core)) {
            return InteractionResult.FAIL;
        }
        if (core.isFormed()) {
            // 已成型 → 打开样板槽界面（声明虚拟结算白名单）
            com.ae2addon.gui.AssemblerMenu.open(player, pos);
            return InteractionResult.SUCCESS;
        }
        return super.use(state, level, pos, player, hand, hit);
    }
}
