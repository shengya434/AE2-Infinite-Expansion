package com.ae2addon.block;

import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.OrientationStrategies;
import appeng.block.AEBaseEntityBlock;
import appeng.menu.locator.MenuLocators;
import com.ae2addon.gui.InfiniteInterfaceMenu;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * ME 接口（无限级）— 机器供料站方块。
 * <p>
 * 方向性方块（水平 4 朝向）：FACING = 正面 = 相邻机器所在侧。
 * - 正面：喂出物品给机器（insertItem）+ 机器/漏斗可抽（extractItem）
 * - 其余面：接 AE 网络（智能线缆）
 * <p>
 * 放样板（3×3 槽，GUI）→ 声明可处理配方，CPU 会把任务推过来；
 * 样板输入自动成为补货清单，机器消耗后从网络拉回（无单 tick 上限）。
 */
public class InfiniteInterfaceBlock extends AEBaseEntityBlock<InfiniteInterfaceBE> {

    public InfiniteInterfaceBlock() {
        super(stoneProps().strength(3.0f).requiresCorrectToolForDrops());
        // ⚠️ 必须设置 blockEntityClass：AEBaseEntityBlock.hasAnalogOutputSignal() 直接
        // blockEntityClass.isAssignableFrom(...) 无判空，方块放置时 markAndNotifyBlock
        // 查比较器信号 → null.isAssignableFrom → NPE（2026-08-28 崩溃实锤）。
        // type/ticker 传 null：newBlockEntity/getTicker 均已 override，不依赖它们。
        setBlockEntity(InfiniteInterfaceBE.class, null, null, null);
    }

    @Override
    public IOrientationStrategy getOrientationStrategy() {
        // 完整 6 向 facing：扳手点任意面都安全（horizontalFacing 点侧面会旋转到
        // UP/DOWN 而 HORIZONTAL_FACING 存不了 → 崩）；支持正面朝上/朝下喂机器。
        return OrientationStrategies.facing();
    }

    /**
     * 2026-08-28 15:40 注册自检实锤：Forge 补丁版 Block.asItem() 带缓存（f_49788_），
     * 首次调用时若物品尚未注册会永久缓存 Items.AIR → new ItemStack(方块)=EMPTY
     * （创造标签页消失/JEI 找不到）。重写直接返回注册物品，绕开毒缓存。
     * 未注册时（注册早期）回退 super（不会崩，注册完成后即返回正确物品）。
     */
    @Override
    public net.minecraft.world.item.Item asItem() {
        var ro = com.ae2addon.init.ModItems.INFINITE_INTERFACE_ITEM;
        if (ro != null && ro.isPresent()) {
            return ro.get();
        }
        return super.asItem();
    }

    @Override
    public InteractionResult onActivated(Level level, BlockPos pos, Player player,
            InteractionHand hand, ItemStack heldStack, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // AE2 内存卡 doesSneakBypassUse=true：shift+右键绕过物品交互走到这里。
        // 检测内存卡 → 复制/粘贴配置（与正常右键 useOn 路径同一套逻辑）
        if (heldStack.getItem() instanceof appeng.items.tools.MemoryCardItem) {
            var be = getBlockEntity(level, pos);
            if (be != null) {
                // shift+右键（bypass useOn）= 粘贴；正常右键走 useOn 复制
                if (com.ae2addon.util.MemoryCardHelper.handlePaste(be, player, heldStack)) {
                    return InteractionResult.SUCCESS;
                }
            }
        }
        var be = getBlockEntity(level, pos);
        if (be == null) {
            return InteractionResult.FAIL;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer,
                    new SimpleMenuProvider((containerId, inventory, ignored) ->
                            new InfiniteInterfaceMenu(containerId, inventory, be),
                            Component.translatable("gui.ae2addon.infinite_interface.title")),
                    buffer -> MenuLocators.writeToPacket(buffer, MenuLocators.forBlockEntity(be)));
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InfiniteInterfaceBE(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,
            BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof InfiniteInterfaceBE feeder) {
                feeder.serverTick();
            }
        };
    }
}
