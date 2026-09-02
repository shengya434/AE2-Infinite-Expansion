package com.ae2addon.block;

import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.OrientationStrategies;
import appeng.block.AEBaseEntityBlock;
import appeng.menu.locator.MenuLocators;
import com.ae2addon.gui.InfiniteInterfaceMenu;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * ME 接口（无限级）· 面板变种 — 薄型贴面形态（2026-09-02 sensei 需求）。
 * <p>
 * 与整格版功能完全一致（复用 {@link InfiniteInterfaceBE}，同一 BlockEntityType），
 * 仅形态不同：2/16 厚的薄片，右键点击方块表面即贴上，不占整格、贴墙美观。
 * <p>
 * 朝向语义与整格版相同：FACING = 正面 = 喂出方向 = 贴着的机器/方块所在侧。
 * 放置规则：FACING = 被点击面的反方向（面板贴在被点击方块表面，面向该方块供料）。
 * 例：点击机器东侧面 → 面板落在机器东侧格内、贴其西面，FACING=WEST，向机器喂出。
 */
public class InfiniteInterfacePanelBlock extends AEBaseEntityBlock<InfiniteInterfaceBE> {

    /** 薄片厚度（1/16 单位）。 */
    private static final int THICKNESS = 2;

    // 薄片贴 FACING 方向的墙（FACING = 机器所在侧 = 薄片要贴紧的面）。
    // 例：facing=north（机器在 -Z）→ 薄片在格内 z=0..2 贴北墙，紧贴机器南面。
    private static final VoxelShape SHAPE_NORTH = box(0, 0, 0, 16, 16, THICKNESS);
    private static final VoxelShape SHAPE_SOUTH = box(0, 0, 16 - THICKNESS, 16, 16, 16);
    private static final VoxelShape SHAPE_WEST = box(0, 0, 0, THICKNESS, 16, 16);
    private static final VoxelShape SHAPE_EAST = box(16 - THICKNESS, 0, 0, 16, 16, 16);
    private static final VoxelShape SHAPE_DOWN = box(0, 0, 0, 16, THICKNESS, 16);
    private static final VoxelShape SHAPE_UP = box(0, 16 - THICKNESS, 0, 16, 16, 16);

    public InfiniteInterfacePanelBlock() {
        super(stoneProps().strength(3.0f).requiresCorrectToolForDrops());
        // ⚠️ 同 InfiniteInterfaceBlock：AEBaseEntityBlock.hasAnalogOutputSignal() 直接
        // blockEntityClass.isAssignableFrom(...) 无判空，必须 setBlockEntity（2026-08-28 崩溃实锤）。
        setBlockEntity(InfiniteInterfaceBE.class, null, null, null);
        // 默认 FACING=north 朝下兼容（放置时 getStateForPlacement 会按点击面重设）
        registerDefaultState(defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH));
    }

    @Override
    public IOrientationStrategy getOrientationStrategy() {
        // 与整格版一致：完整 6 向 facing（BE 的 BlockOrientation.get(state) 解析依赖它）。
        return OrientationStrategies.facing();
    }

    /**
     * 放置：FACING = 被点击面的反方向 → 面板贴在被点击方块表面，面向它（机器）供料。
     */
    @Override
    @Nullable
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        Direction facing = clicked.getOpposite(); // 喂出方向 = 指向被点击的方块
        return defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return shapeFor(state.getValue(BlockStateProperties.FACING));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return shapeFor(state.getValue(BlockStateProperties.FACING));
    }

    private static VoxelShape shapeFor(Direction facing) {
        return switch (facing) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            case UP -> SHAPE_UP;
            case DOWN -> SHAPE_DOWN;
        };
    }

    /**
     * 同 InfiniteInterfaceBlock：Forge 补丁版 Block.asItem() 带毒缓存（首次调用时若物品未注册
     * 会永久缓存 Items.AIR），重写直接返回注册物品绕开。
     */
    @Override
    public net.minecraft.world.item.Item asItem() {
        var ro = com.ae2addon.init.ModItems.INFINITE_INTERFACE_PANEL_ITEM;
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
        // 检测内存卡/配置卡 → 复制/粘贴配置（与整格版同一套逻辑）
        if (heldStack.getItem() instanceof com.ae2addon.item.ConfigCardItem
                || heldStack.getItem() instanceof appeng.items.tools.MemoryCardItem) {
            var be = getBlockEntity(level, pos);
            if (be != null) {
                boolean paste = player.isShiftKeyDown();
                boolean handled = paste
                        ? com.ae2addon.util.MemoryCardHelper.handlePaste(be, player, heldStack)
                        : com.ae2addon.util.MemoryCardHelper.handleCopy(be, player, heldStack);
                if (handled) {
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
