package com.ae2addon.block;

import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.CraftingUnitBlock;
import appeng.block.crafting.CraftingUnitType;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import com.ae2addon.init.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;

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

        // 2026-09-24 sensei：木棍成型**废弃**，改成放下后自动判定结构。
        // 界面**始终可打开**（未成型也能开 —— 界面里有"成型/未成型"字条，
        // 一键成型按钮以后也挂在这个界面里）。旧实现未成型只发提示、不开界面，是错的。
        MenuOpener.open(ModMenuTypes.INTEGRATED_CPU.get(), player,
                MenuLocators.forBlockEntity(cpuBE));
        return InteractionResult.SUCCESS;
    }

    /**
     * 放下控制器后立即判定一次结构（sensei：控制器放下即开始判定）。
     */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || state == oldState) return;
        if (level.getBlockEntity(pos) instanceof IntegratedCPUBE cpu) {
            cpu.reevaluateStructure();
        }
    }

    /** 结构复检间隔（tick）：3 秒。sensei 2026-09-24：「放下控制器后每 3 秒检测一次，不依赖周围方块更新」 */
    private static final int CHECK_INTERVAL_TICKS = 60;

    private static final java.util.concurrent.atomic.AtomicLong TICKER_ENTRY_CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 周期性结构复检（2026-09-24 sensei 指定）。
     * <p>
     * **不再依赖方块更新事件** —— 之前靠 {@code neighborChanged} 那套有两个毛病：
     * ① Minecraft 只给相邻方块发更新，结构有 2000+ 格、控制器只在侧面，
     * 玩家在很远的地方搭/挖时控制器根本收不到信号；
     * ② 为了补救我还把"变更点 ±2 邻域"当候选，范围反而过大。
     * 现在改成**定时轮询**：逻辑简单、与位置无关、行为可预期。
     * <p>
     * 判定只遍历模板里真实存在的 2248 格（见 {@code IntegratedCpuStructure.Pattern#plan()}），
     * 所以每 3 秒一次的开销很小。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            long calls = TICKER_ENTRY_CALLS.incrementAndGet();
            if (calls % 100 == 0) {
                com.ae2addon.AE2Addon.LOGGER.info(
                        "[ae2addon][cpu-diag] ticker入口 calls={} levelNull={} clientSide={} gameTime={} cpuBE={}",
                        calls, lvl == null, lvl != null && lvl.isClientSide,
                        lvl == null ? -1 : lvl.getGameTime(), be instanceof IntegratedCPUBE);
            }
            if (!(be instanceof IntegratedCPUBE cpu)) {
                return;
            }
            if (lvl.getGameTime() % 20 == 0) {
                cpu.pushOutlineStateToClients();
            }
            // 一键成型：每 tick 推一批放置（构建在跑时必须每 tick 走）
            if (cpu.isBuilding()) {
                cpu.tickBuild();
            }
            // 一键回收（2026-09-25）：每 tick 拆一批，避免上千次 setBlock 卡服务端
            if (cpu.isRecycling()) {
                cpu.tickRecycle();
            }
            // 冲突位置红色方框（有时限，自己会结束）
            cpu.tickConflictHighlight();
            // 网络侧展示信息：**每 tick 走一次入口、内部按 1 秒限频**
            // （2026-09-24 sensei：「网络内集成型CPU线程信息会在重新成型后重新无法显示」——
            //   原先把推送挂在 refreshLanes 里，而 lane 数不变时那条链可能不跑 → 数值停更。
            //   这里挂在**一定会被调用的 ticker** 上，并在结构复检时强制推一次。）
            cpu.pushStatusToClients(false);
            // 结构复检：每 3 秒一次（sensei 指定）
            if (lvl.getGameTime() % CHECK_INTERVAL_TICKS == 0) {
                cpu.reevaluateStructure();
            }
        };
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new IntegratedCPUBE(pos, state);
    }
}
