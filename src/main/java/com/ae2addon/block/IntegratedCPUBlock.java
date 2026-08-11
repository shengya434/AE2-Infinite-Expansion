package com.ae2addon.block;

import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.CraftingUnitBlock;
import appeng.block.crafting.CraftingUnitType;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import com.ae2addon.init.ModBlocks;
import com.ae2addon.init.ModMenuTypes;
import com.ae2addon.util.ChatLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 集成型 CPU 核心（多方块 3×5×3）
 * <p>
 * 注意：必须继承 CraftingUnitBlock（不能改成 Block），因为
 * - CraftingBlockEntity.onReady() 会强转 block -> AbstractCraftingUnitBlock
 * - AEBaseEntityBlock.getBlockEntityBlockState() 依赖 blockEntityClass 字段
 * <p>
 * 成型条件：内部 2 格空间必须有无限合成存储器（至少1个）+ 可选无限并行/工作台
 * 成型后：提供无限合成存储空间（或同时拥有无限并行）
 * 不摆放在结构中时无法接入 AE 网络。
 */
public class IntegratedCPUBlock extends CraftingUnitBlock {

    private static boolean BLOCK_ENTITY_CLASS_INIT = false;

    public IntegratedCPUBlock() {
        super(CraftingUnitType.STORAGE_256K);
        // AEBaseEntityBlock 的 blockEntityClass 字段在 BlockEntityType 构造器中未被设置
        // 必须通过反射设为 IntegratedCPUBE.class 以防 NPE
        if (!BLOCK_ENTITY_CLASS_INIT) {
            BLOCK_ENTITY_CLASS_INIT = true;
            try {
                var f = ObfuscationReflectionHelper.findField(
                        AEBaseEntityBlock.class, "blockEntityClass");
                f.setAccessible(true);
                f.set(this, IntegratedCPUBE.class);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof IntegratedCPUBE cpuBE)) return InteractionResult.FAIL;

        // 木棍右键 && 未成型 → 尝试成型
        if (player.getItemInHand(hand).getItem() == Items.STICK && !cpuBE.isFormed()) {
            if (tryForm((ServerLevel) level, pos, cpuBE, player)) {
                ChatLog.ok(level, pos, "CPU 成型成功！");
                return InteractionResult.SUCCESS;
            }
            ChatLog.err(level, pos, "CPU 结构不匹配，无法成型");
            return InteractionResult.FAIL;
        }

        // 已成型 → 打开量子分裂线程状态界面
        if (cpuBE.isFormed()) {
            MenuOpener.open(ModMenuTypes.INTEGRATED_CPU.get(), player,
                    MenuLocators.forBlockEntity(cpuBE));
            return InteractionResult.SUCCESS;
        }

        // 未成型（木棍已处理过成型）→ 交回父类
        return super.use(state, level, pos, player, hand, hit);
    }

    /**
     * 检测并成型 3×5×3 多方块
     */
    private boolean tryForm(ServerLevel level, BlockPos corePos, IntegratedCPUBE be, Player player) {
        // 检测外部结构 + 内部元件
        List<BlockPos> structureBlocks = checkStructure(level, corePos, player);
        if (structureBlocks == null) return false;

        // 检测内部元件
        InternalComponents components = checkInternalComponents(level, corePos);
        if (!components.hasStorage()) return false;

        // 生存模式消耗：外部结构 + 内部元件，防止刷材料
        if (!player.isCreative()) {
            for (BlockPos p : structureBlocks) level.destroyBlock(p, false);
            for (BlockPos p : components.slotPositions()) level.destroyBlock(p, false);
        }
        be.setHasCoProcessing(components.hasCoProcessing());
        be.setFormed(true);
        return true;
    }

    /**
     * 检测 3×5×3 外部结构。
     * 不包含内部两个 u 位置（它们保留用于无限CPU元件）。
     */
    @Nullable
    private List<BlockPos> checkStructure(ServerLevel level, BlockPos pos, Player player) {
        Block purple = Blocks.PURPLE_CONCRETE;
        Block magenta = Blocks.MAGENTA_CONCRETE;
        Block bookshelf = Blocks.BOOKSHELF;

        // 核心在 (x=1, y=0, z=1)：结构在核心周围居中
        BlockPos origin = pos.offset(-1, 0, -2);
        spawnCornerParticles(level, origin, 3, 5, 3);
        List<BlockPos> toConsume = new ArrayList<>();

        for (int y = 0; y < 5; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    BlockPos checkPos = origin.offset(x, y, z);
                    if (checkPos.equals(pos)) continue; // 核心自身
                    if (isInternalSlot(x, y, z)) continue; // u 位保留

                    BlockState stateAt = level.getBlockState(checkPos);
                    Block expected = getExpectCpuBlock(x, y, z, purple, magenta, bookshelf);
                    if (expected == null) {
                        if (!stateAt.isAir()) {
                            spawnParticles(level, checkPos);
                            player.sendSystemMessage(Component.literal("§b✗ " + formatPos(checkPos) + " 应为空气，但找到了 " + blockName(stateAt)));
                            return null;
                        }
                        continue;
                    }
                    if (stateAt.getBlock() != expected) {
                        spawnParticles(level, checkPos);
                        ChatLog.err(level, checkPos, "应为 " + expected.getName().getString() + "，但找到了 " + blockName(stateAt));
                        return null;
                    }
                    spawnParticles(level, checkPos);
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

    /**
     * 判断是否为内部两个 u 槽（保留给无限CPU元件）
     */
    private boolean isInternalSlot(int x, int y, int z) {
        // u 位置: (y=1,x=1,z=1) 和 (y=2,x=1,z=1)
        return (x == 1 && z == 1) && (y == 1 || y == 2);
    }

    /**
     * 判断 (x,y,z) 在 3×5×3 CPU 结构中的期望方块
     */
    @Nullable
    private Block getExpectCpuBlock(int x, int y, int z,
                                    Block purple, Block magenta, Block bookshelf) {
        // 层5(y=4): 书架摆放
        if (y == 4) {
            if (x == 1 && z == 1) return bookshelf;   // 中心书架
            if (x != 1 && z != 1) return bookshelf;   // 四角书架
            // (x=1,z=0),(x=0,z=1),(x=2,z=1),(x=1,z=2) 为空气
            return null;
        }

        // 层1(y=0): 底部
        if (y == 0) {
            if (z == 0 || z == 2 || x == 0 || x == 2) return purple;
            if (z == 1 && x == 1) return magenta;    // 核心旁边的品红
            return purple; // 核心所在位置理论上还会走到这里
        }

        // 层2/3(y=1/2): 中间
        if (y == 1 || y == 2) {
            if (z == 0 && x == 1) return magenta;     // w
            if (z == 2 && x == 1) return magenta;     // w
            if (z == 1 && x == 0) return magenta;     // w
            if (z == 1 && x == 2) return magenta;     // w
            if (z == 1 && x == 1) return null;         // u (保留)
            return purple; // 四角或边缘
        }

        // 层4(y=3): qqq,qwq,qqq — 品红只在中心 (x=1,z=1)
        if (y == 3) {
            if (z == 1 && x == 1) return magenta;   // 中心品红
            return purple;                            // 其他全紫
        }

        return null;
    }

    /**
     * 检查内部两个 u 槽是否包含正确的元件。
     * 必须至少有一个无限合成存储器。
     */
    private InternalComponents checkInternalComponents(ServerLevel level, BlockPos corePos) {
        BlockPos origin = corePos.offset(-1, 0, -2);
        Block infiniteStorage = ModBlocks.INFINITE_CRAFTING_STORAGE.get();
        Block infiniteCo = ModBlocks.INFINITE_CO_PROCESSING.get();
        Block workbench = Blocks.CRAFTING_TABLE;

        boolean hasStorage = false;
        boolean hasCo = false;

        // u1: (y=1, x=1, z=1)
        BlockPos u1 = origin.offset(1, 1, 1);
        BlockState u1State = level.getBlockState(u1);
        if (u1State.getBlock() == infiniteStorage) hasStorage = true;
        if (u1State.getBlock() == infiniteCo) hasCo = true;
        boolean u1Valid = hasStorage || hasCo || u1State.getBlock() == workbench;

        // u2: (y=2, x=1, z=1)
        BlockPos u2 = origin.offset(1, 2, 1);
        BlockState u2State = level.getBlockState(u2);
        if (u2State.getBlock() == infiniteStorage) hasStorage = true;
        if (u2State.getBlock() == infiniteCo) hasCo = true;
        boolean u2Valid = hasStorage || hasCo || u2State.getBlock() == workbench;

        if (!u1Valid || !u2Valid || !hasStorage) {
            return new InternalComponents(false, false);
        }
        return new InternalComponents(true, hasCo, List.of(u1, u2));
    }

    /**
     * 内部元件检测结果
     */
    private record InternalComponents(boolean hasStorage, boolean hasCoProcessing, List<BlockPos> slotPositions) {
        public InternalComponents(boolean hasStorage, boolean hasCoProcessing) {
            this(hasStorage, hasCoProcessing, List.of());
        }
    }


    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new IntegratedCPUBE(pos, state);
    }
}
