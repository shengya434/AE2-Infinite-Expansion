package com.ae2addon.block;

import appeng.block.crafting.CraftingUnitBlock;
import appeng.block.crafting.CraftingUnitType;
import appeng.block.AEBaseEntityBlock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;

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
        if (!core.isFormed()) {
            // ⚠ 2026-09-24 sensei：「没在结构里的也能用」—— 门禁已经补在
            //   ICraftingProvider 与终端库存上（都按 isFormed 过滤），这里只加一句人话提示，
            //   免得玩家点了半天不知道为什么没反应。
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c装配处理器未装入集成 CPU 多方块结构（需放在结构指定的那一格）"), true);
            return InteractionResult.SUCCESS;
        }
        // 已装入结构 → 打开样板槽界面（声明虚拟结算白名单）
        com.ae2addon.gui.AssemblerMenu.open(player, pos);
        return InteractionResult.SUCCESS;
    }

    /**
     * 直接 new BE：基类 newBlockEntity 依赖 blockEntityType 字段（AE2 只给自己的
     * 方块注入），第三方方块不 override 会 NPE（2026-09-04 放置崩溃实锤；
     * 同 InfiniteInterfaceBlock 做法）。
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AssemblerCoreBE(pos, state);
    }

    /** 归属维护间隔（tick）：2 秒。认领逻辑要能跟上"集成 CPU 后成型"的情况 */
    private static final int OWNER_CHECK_INTERVAL = 40;

    /**
     * 定期重新认领归属（2026-09-24）：装配处理器是否"已装入结构"完全取决于
     * "我在哪个已成型的集成 CPU 结构里"，而这个关系会随时间变化
     * （CPU 后成型 / 结构被拆），所以定期重算一次最稳妥。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof AssemblerCoreBE core && lvl.getGameTime() % OWNER_CHECK_INTERVAL == 0) {
                core.tickOwnerMaintenance();
            }
        };
    }
}
