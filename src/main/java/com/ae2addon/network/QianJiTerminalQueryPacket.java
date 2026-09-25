package com.ae2addon.network;

import com.ae2addon.AE2Addon;
import com.ae2addon.crafting.QianJiNetworkPatterns;
import com.ae2addon.gui.QianJiTerminalMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * 千机·样板终端的数据请求（客户端 → 服务端，2026-09-21 v254）。
 * <p>
 * 终端要展示的是"**整张网络上所有千机的样板**"——不分页、只滑条。这种长列表**不可能整包发**，
 * 所以客户端按窗口来取（滚到哪取到哪），就像 AE2 的样板管理终端那样按需拉。
 * <p>
 * 一个包管两种模式：
 * <ul>
 *   <li>{@code term} 为空 = **列表模式**：取 {@code collect()} 结果的第 offset 段；</li>
 *   <li>{@code term} 非空 = **搜索模式**：先在全网搜（服务端同一份 searchText 规则），再取第 offset 段。</li>
 * </ul>
 */
public final class QianJiTerminalQueryPacket {

    /** 一次最多回多少条（客户端一次只显示十来行，留点余量给滚动） */
    public static final int MAX_PAGE = 120;
    /** 搜索模式下的命中上限（与其它搜索入口一致） */
    public static final int MAX_SEARCH_HITS = 200;

    /** 关键词（空 = 列表模式） */
    public final String term;
    /** 起始下标 */
    public final int offset;
    /** 取多少条 */
    public final int limit;

    public QianJiTerminalQueryPacket(String term, int offset, int limit) {
        this.term = term == null ? "" : term;
        this.offset = Math.max(0, offset);
        this.limit = Math.max(1, Math.min(MAX_PAGE, limit));
    }

    public static void encode(QianJiTerminalQueryPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.term, 64);
        buf.writeVarInt(msg.offset);
        buf.writeVarInt(msg.limit);
    }

    public static QianJiTerminalQueryPacket decode(FriendlyByteBuf buf) {
        return new QianJiTerminalQueryPacket(buf.readUtf(64), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(QianJiTerminalQueryPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            // 门槛还是"菜单归属"：没开着这台终端就发不出查询
            if (!(player.containerMenu instanceof QianJiTerminalMenu menu)) return;
            var grid = menu.grid();
            if (grid == null) return;

            var all = QianJiNetworkPatterns.collect(grid);
            var source = msg.term.isEmpty()
                    ? all
                    : QianJiNetworkPatterns.search(grid, msg.term, MAX_SEARCH_HITS);

            int from = Math.min(msg.offset, source.size());
            int to = (int) Math.min(source.size(), (long) from + msg.limit);
            var slice = source.subList(from, to);

            var entries = new ArrayList<QianJiTerminalPagePacket.Entry>(slice.size());
            for (var e : slice) {
                entries.add(new QianJiTerminalPagePacket.Entry(
                        e.pos(), e.slot(), where(e.pos(), e.slot()) + " " + label(e.data()), tooltip(e.data())));
            }

            // 顺带把"这条网络上最近一次推入"带给客户端（v280）→ 列表跳过去并高亮 3 秒。
            // 搭在列表数据上而不是单独发包：不依赖玩家对象/菜单状态/终端形态。
            var recent = com.ae2addon.gui.QianJiRecentPush.get(grid, System.nanoTime());

            AE2Addon.NETWORK.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new QianJiTerminalPagePacket(msg.term, source.size(), msg.offset, entries,
                            menu.networkBlankPatterns(),
                            recent == null ? null : recent.pos(),
                            recent == null ? 0 : recent.slot(),
                            recent == null ? 0L : recent.seq()));
            // 2026-09-22 v285：原来这里每次查询都打一行 [search] + 命中定位时再打一行 [focus]，
            // 界面开着时每 2 秒一条，纯刷屏 → 都清掉（要排查就临时加回来）。
        });
        ctx.get().setPacketHandled(true);
    }

    /** 定位串：{@code [x, y, z] 第 N 页 槽 i}（页 = slot/54，与千机 GUI 分页一致） */
    private static String where(net.minecraft.core.BlockPos pos, int slot) {
        return "§8[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]"
                + " §8" + (slot / 54 + 1) + "页·槽" + slot + " §f";
    }

    /** 列表行短标签：主产物（+概率条数）+ 来源机器 */
    private static String label(com.ae2addon.recipe.QianJiPatternData data) {
        var sb = new StringBuilder();
        for (var out : data.primary()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(out.stack().what().getDisplayName().getString());
        }
        if (!data.chanced().isEmpty()) sb.append(" (+").append(data.chanced().size()).append("概率)");
        if (!data.machine().isEmpty()) sb.append(" §8← ").append(data.machine());
        return sb.toString();
    }

    /** 完整 tooltip = 与样板物品同源的 describe()（悬停时显示） */
    private static String tooltip(com.ae2addon.recipe.QianJiPatternData data) {
        var sb = new StringBuilder();
        for (String line : data.describe()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }
}
