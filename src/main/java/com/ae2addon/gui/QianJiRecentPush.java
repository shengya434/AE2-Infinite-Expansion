package com.ae2addon.gui;

import appeng.api.networking.IGrid;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "这条网络上最近一次把样板推进千机"的小记录（2026-09-22 v280）。
 * <p>
 * 为什么要它：v277 的列表跳转/高亮是靠**服务端单独发一个定位包**给"正在看终端的那个玩家"，
 * 而那条路要经过"宿主持有的是哪个玩家 / 那一刻他的菜单是不是终端"这些**脆弱的前提** ——
 * sensei 实测 JEI 编码那条路没触发。
 * <p>
 * 现在改成：自动推入成功时**在这里记一笔**，终端查询回包（{@code QianJiTerminalPagePacket}）顺带把它带给客户端。
 * 列表数据本来就能可靠到达客户端（列表一直在刷新就是证据），于是跳转/高亮**不再依赖玩家对象、菜单状态、形态**
 * （线缆面板 / 自家无线终端 / 通用终端状态一律有效）。
 * <p>
 * 只记"最近一次"，并且限时有效（{@link #FRESH_TICKS}）：界面是后来才打开的话不该无端乱跳。
 * 客户端靠递增的 {@code seq} 判断"这是新的一次推入"，同一次只跳一次。
 */
public final class QianJiRecentPush {

    /** 记录的有效期（tick）：超过这个时间就不再带给客户端（≈5 秒） */
    public static final long FRESH_TICKS = 100;

    /**
     * 一次推入。
     *
     * @param pos  落在哪台千机
     * @param slot 哪个槽
     * @param time 记录时的世界时间（tick）
     * @param seq  递增序号（客户端据此判断"是不是新的一次"）
     */
    public record Entry(BlockPos pos, int slot, long time, long seq) {}

    /** 按网络（IGrid）记 —— 弱引用键，网络没了记录自然消失，不会越攒越多 */
    private static final Map<IGrid, Entry> BY_GRID = new WeakHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private QianJiRecentPush() {
    }

    /** 记一笔"刚刚把样板推进了这台的这个槽" */
    public static synchronized void record(IGrid grid, BlockPos pos, int slot) {
        if (grid == null || pos == null) return;
        BY_GRID.put(grid, new Entry(pos, slot, System.nanoTime(), SEQ.incrementAndGet()));
    }

    /**
     * 取这条网络上"最近一次推入"（还没过期的）。
     *
     * @param nowNanos 当前时间（{@code System.nanoTime()}）；用来判断有没有过期
     */
    @Nullable
    public static synchronized Entry get(IGrid grid, long nowNanos) {
        if (grid == null) return null;
        final Entry e = BY_GRID.get(grid);
        if (e == null) return null;
        // nanoTime → tick 的换算没必要精确：1 tick ≈ 50ms，用纳秒直接比
        final long ageTicks = (nowNanos - e.time()) / 50_000_000L;
        return ageTicks <= FRESH_TICKS ? e : null;
    }
}
