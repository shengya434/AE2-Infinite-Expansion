package com.ae2addon.block;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.grid.AENetworkBlockEntity;
import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Debug 销毁方块 BE（测试用）：无限输入 + 瞬间销毁。
 * <p>
 * - 实现 ICraftingProvider：pushPattern 无脑接受（输入量可以是 long 级），
 *   接受的输入直接丢弃 = 销毁。
 * - 不主动注册任何 pattern；由 CraftingCpuLogicMixin 把本方块追加到
 *   executeCrafting 的 getProviders 结果里，因此能接收任意 pattern。
 * - 定期日志输出销毁数量，方便测试观察吞吐。
 */
public class DebugTrashBE extends AENetworkBlockEntity implements ICraftingProvider {

    private static final long LOG_INTERVAL_TICKS = 100;

    private long destroyedTotal;
    private long destroyedSinceLog;
    private long lastLogTick = Long.MIN_VALUE;

    public DebugTrashBE(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DEBUG_TRASH.get(), pos, state);
        getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(ICraftingProvider.class, this);
    }

    @Override
    public void onReady() {
        super.onReady();
        DebugTrashRegistry.register(this);
    }

    @Override
    public void onChunkUnloaded() {
        DebugTrashRegistry.unregister(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        DebugTrashRegistry.unregister(this);
        super.setRemoved();
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return List.of();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        // 无限输入 + 瞬间销毁：接受即销毁，无需存储
        long count = 0;
        if (inputHolder != null) {
            for (var counter : inputHolder) {
                if (counter != null) {
                    for (var entry : counter) {
                        count += entry.getLongValue();
                    }
                }
            }
        }
        destroyedTotal += count;
        destroyedSinceLog += count;

        if (level != null && !level.isClientSide()) {
            long tick = level.getGameTime();
            if (lastLogTick == Long.MIN_VALUE) {
                lastLogTick = tick;
            } else if (tick - lastLogTick >= LOG_INTERVAL_TICKS) {
                AE2Addon.LOGGER.info(
                        "[ae2addon] DebugTrash @{} 最近{}tick销毁 {} 个物品，累计 {}",
                        worldPosition.toShortString(),
                        tick - lastLogTick,
                        destroyedSinceLog,
                        destroyedTotal);
                lastLogTick = tick;
                destroyedSinceLog = 0;
            }
        }
        return true;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    public long getDestroyedTotal() {
        return destroyedTotal;
    }
}
