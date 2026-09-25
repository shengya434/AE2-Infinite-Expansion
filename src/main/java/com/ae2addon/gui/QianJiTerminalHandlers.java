package com.ae2addon.gui;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternContainer;
import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 千机·样板终端的**槽位规则 + 自动推入**（2026-09-21 v272 从 part 里抽出来的）。
 * <p>
 * 抽出来的理由只有一条：同一个终端现在有两种载体（线缆面板 / 无线终端），
 * 规则必须**只有一份**——否则迟早出现"面板拦得住、无线拦不住"这种半吊子。
 * <p>
 * 规则（sensei 2026-09-21 定调，别自己放宽）：
 * <ul>
 *   <li>空白样板槽：只收 AE2 的空白样板；</li>
 *   <li>编码样板槽：**只收我们自己的 {@code ae2addon:qianji_pattern}**，
 *       AE2 样板外壳即使带着千机数据也拒收（代价：ME 编码终端那条路对千机样板退休）。</li>
 * </ul>
 * 两层闸：库存的 {@code isItemValid}（AE2/管道路径）+ {@code setStackInSlot}
 * （Forge 的 setStackInSlot **不校验**，是历史漏洞点）。
 */
public final class QianJiTerminalHandlers {

    private QianJiTerminalHandlers() {
    }

    /** AE2 空白样板（按注册名判断，避免硬依赖 AE2 的物品类） */
    public static boolean isBlankPattern(ItemStack stack) {
        return !stack.isEmpty() && "ae2:blank_pattern".equals(String.valueOf(
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())));
    }

    /** 千机样板：**外壳必须是我们的物品** + 数据非空 */
    public static boolean isQianJiPattern(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != ModItems.QIAN_JI_PATTERN.get()) {
            return false;
        }
        var data = QianJiPatternData.of(stack);
        return data != null && !data.isEmpty();
    }

    /** 空白样板槽库存（1 格）：内容变了调 {@code onChanged} */
    public static ItemStackHandler blankHandler(Runnable onChanged) {
        return new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return isBlankPattern(stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                onChanged.run();
            }
        };
    }

    /** 编码样板槽库存（1 格）：两道闸 + 内容变了调 {@code onChanged} */
    public static ItemStackHandler encodedHandler(Runnable onChanged) {
        return new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return isQianJiPattern(stack);
            }

            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                // Forge 的 setStackInSlot 不校验，谁直接调都能塞进来 → 最后一道闸
                if (!stack.isEmpty() && !isItemValid(slot, stack)) {
                    AE2Addon.LOGGER.info("[ae2addon][gate] 终端编码槽拒绝 {}", stack.getItem());
                    return;
                }
                super.setStackInSlot(slot, stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                onChanged.run();
            }
        };
    }

    /**
     * 把**空白样板槽**里的空白样板直接送回 ME 网络（2026-09-22 v282，sensei 要求）。
     * <p>
     * 语义：空白样板槽就是"网络里空白样板"的映射 —— 往里放 = 存进网络（槽里不留东西）。
     * 槽显示的"网络存量"由界面自己画（读 {@code blankPatterns} 那个字段），不靠槽里真放东西。
     * <p>
     * 为什么不做成"虚拟槽（getItem 直接返回网络库存）"：原版的**合并路径**
     * （{@code AbstractContainerMenu#moveItemStackTo} 里同物品堆叠那一段）只会动它拿到的那个
     * {@code ItemStack} 副本 + 调 {@code setChanged()}，**不会调用 {@code set()}** ——
     * 虚拟槽收不到这次写入，东西会凭空消失。真槽 + 即时入网没有这个坑。
     * <p>
     * 网络不在（没联网 / 客户端 / 超出无线范围）或网络塞不下：**什么都不动**，样板留在槽里（绝不吞物品）。
     *
     * @return 真的送进去的数量（0 = 没做事）
     */
    public static int flushBlankToNetwork(@Nullable IGrid grid, IItemHandler blankInv,
            @Nullable Runnable onChanged) {
        if (grid == null || blankInv == null) return 0;
        final ItemStack stack = blankInv.getStackInSlot(0);
        if (!isBlankPattern(stack)) return 0;
        int moved;
        try {
            final var storage = grid.getStorageService() == null
                    ? null : grid.getStorageService().getInventory();
            if (storage == null) return 0;
            final var key = appeng.api.stacks.AEItemKey.of(stack);
            if (key == null) return 0;
            final long inserted = storage.insert(key, stack.getCount(),
                    appeng.api.config.Actionable.MODULATE,
                    appeng.api.networking.security.IActionSource.empty());
            moved = (int) Math.min(stack.getCount(), inserted);
            if (moved <= 0) {
                AE2Addon.LOGGER.info("[ae2addon][terminal] 空白样板送不进网络（网络已满？），先留在槽里");
                return 0;
            }
            blankInv.extractItem(0, moved, false);
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][terminal] 空白样板送回网络失败：{}", t.toString());
            return 0;
        }
        if (onChanged != null) onChanged.run();
        return moved;
    }

    /**
     * 从网络里取空白样板（v282）：给"左键点空白样板槽 = 从网络拿一叠"用。
     *
     * @return 实际取到的栈（空 = 网络里没有 / 没联网）
     */
    public static ItemStack withdrawBlankFromNetwork(@Nullable IGrid grid, int amount) {
        if (grid == null || amount <= 0) return ItemStack.EMPTY;
        try {
            final var storage = grid.getStorageService() == null
                    ? null : grid.getStorageService().getInventory();
            if (storage == null) return ItemStack.EMPTY;
            final ItemStack probe = new ItemStack(appeng.core.definitions.AEItems.BLANK_PATTERN.asItem());
            final var key = appeng.api.stacks.AEItemKey.of(probe);
            if (key == null) return ItemStack.EMPTY;
            final long taken = storage.extract(key, amount,
                    appeng.api.config.Actionable.MODULATE,
                    appeng.api.networking.security.IActionSource.empty());
            if (taken <= 0) return ItemStack.EMPTY;
            probe.setCount((int) Math.min(Integer.MAX_VALUE, taken));
            return probe;
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][terminal] 从网络取空白样板失败：{}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    /**
     * 把编码槽里的千机样板**自动推入一台有空位的千机**（sensei 2026-09-21 要求）。
     * <p>
     * 只在服务端有意义：{@code grid} 为 null（客户端 / 未联网 / 超出无线范围）直接返回。
     * 遍历用 AE2 的 {@code IGrid#getMachines(PatternContainer.class)}（与全网搜索同一条链路），
     * 找到第一台有空格的就塞进去，然后清空编码槽；插入走千机自己的样板处理器 ——
     * "只收千机样板"由 handler 兜底，不靠调用方自觉。
     * <p>
     * 网络里的千机都满了：**什么都不动**，样板留在槽里，日志说明原因（绝不吞物品）。
     *
     * @param player 插入成功后要通知的玩家（让终端列表跳过去并高亮那个栏位，v277）；
     *               null = 不通知（客户端 / 拿不到玩家，例如线缆面板没人开着界面）
     */
    public static void autoPushEncoded(@Nullable IGrid grid, IItemHandler encodedInv,
            @Nullable Runnable onChanged, @Nullable net.minecraft.world.entity.player.Player player) {
        if (grid == null) return;
        var stack = encodedInv.getStackInSlot(0);
        if (!isQianJiPattern(stack)) return;
        for (var container : grid.getMachines(PatternContainer.class)) {
            if (!(container instanceof com.ae2addon.block.QianJiBE target)) continue;
            var handler = target.getPatternHandler();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (!handler.getStackInSlot(slot).isEmpty()) continue;
                var rest = handler.insertItem(slot, stack.copy(), false);
                if (rest.isEmpty()) {
                    // 先清槽再落盘：setStackInSlot 会再触发一次 onChanged（那次槽已空，会提前返回）
                    encodedInv.extractItem(0, stack.getCount(), false);
                    if (onChanged != null) onChanged.run();
                    AE2Addon.LOGGER.info("[ae2addon][terminal] 编码槽样板自动推入千机 @ {} 槽 {}",
                            target.getBlockPos(), slot);
                    // ① 记一笔（v280）：终端查询回包会把它带给客户端 → 跳转 + 高亮，
                    //    这条不依赖玩家对象/菜单状态，任何形态都有效
                    QianJiRecentPush.record(grid, target.getBlockPos(), slot);
                    // ② 顺手也试一次"直接发给正在看终端的玩家"（v277 的老路，成功的话反应更快）
                    notifyFocus(player, target.getBlockPos(), slot);
                    return;
                }
            }
        }
        AE2Addon.LOGGER.info("[ae2addon][terminal] 编码槽有样板但网络里的千机都没空位，先留在槽里");
    }

    /**
     * 通知"样板落在哪个栏位了"（列表跳转 + 高亮 3 秒，2026-09-22 v277）。
     * <p>
     * 只在**玩家正开着千机终端**时发 —— 没开界面就没人看那个列表。
     * <p>
     * ⚠ 2026-09-22 v279：这一路每一步都打日志 —— sensei 实测"JEI 编码自动推入后没跳转/没高亮"，
     * 靠日志把"没发出去"和"客户端没理"区分开（别猜）。
     */
    private static void notifyFocus(@Nullable net.minecraft.world.entity.player.Player player,
            net.minecraft.core.BlockPos pos, int slot) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!(sp.containerMenu instanceof QianJiTerminalMenu)) return;
        AE2Addon.NETWORK.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new com.ae2addon.network.QianJiTerminalFocusPacket(pos, slot));
    }
}
