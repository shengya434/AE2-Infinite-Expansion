package com.ae2addon.network;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.QianJiMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * 千机 GUI 搜索请求（客户端 → 服务端，2026-09-21 v244）。
 * <p>
 * **为什么必须走服务端**：千机有 1280 个样板槽，把整包同步给客户端会直接把包撑爆 ——
 * 这是本项目"大槽位 GUI 绝不全量同步"的铁律（同类教训见千机分页与集成型 CPU 列表）。
 * 所以客户端只发一个关键词，服务端扫完只回**命中的那几条**。
 * <p>
 * 搜索规则与 {@code /qianji find} **完全一致**（都走 {@code QianJiBE#searchPatterns} +
 * {@code QianJiPatternData#searchText}）：命令能搜到的，GUI 一定能搜到；反之亦然。
 * 这样"命令版先验证搜索文本"那一步的结论可以直接继承，不用两套逻辑各调一遍。
 */
public final class QianJiSearchRequestPacket {

    /**
     * 单次搜索最多回多少条：200 足够用（GUI 一次只列十几条），
     * 又不至于让回包大到影响网络（200 × 短标签 ≈ 20KB 量级）。
     */
    public static final int MAX_HITS = 200;

    /** 关键词（原样传，trim/小写在服务端统一做） */
    public final String term;

    public QianJiSearchRequestPacket(String term) {
        this.term = term == null ? "" : term;
    }

    public static void encode(QianJiSearchRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.term, 64);
    }

    public static QianJiSearchRequestPacket decode(FriendlyByteBuf buf) {
        return new QianJiSearchRequestPacket(buf.readUtf(64));
    }

    public static void handle(QianJiSearchRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player == null) return;
            // 只认"玩家当前打开的千机菜单"——这本身就是一道天然门槛：
            // 没开着这台千机的界面就发不出搜索（将来做无线/远程终端时，门槛同理挂在这里）。
            if (!(player.containerMenu instanceof QianJiMenu menu)) return;

            var hits = menu.searchPatterns(msg.term, MAX_HITS);
            // 2026-09-22 v285：原来每次搜索打一行 [search]（GUI 里本来就看得见命中数）→ 清掉

            var out = new ArrayList<QianJiSearchResultPacket.Hit>(hits.size());
            for (var hit : hits) {
                out.add(new QianJiSearchResultPacket.Hit(
                        hit.slot(), label(hit.data()), tooltip(hit.data())));
            }
            AE2Addon.NETWORK.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new QianJiSearchResultPacket(msg.term, out));
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 结果行标签：主产物（+概率产出条数）+ 来源机器。
     * **服务端算好再发**，客户端只管显示 —— 客户端没有这批样板数据（也不该有）。
     */
    private static String label(com.ae2addon.recipe.QianJiPatternData data) {
        var sb = new StringBuilder();
        for (var out : data.primary()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(out.stack().what().getDisplayName().getString());
        }
        if (!data.chanced().isEmpty()) sb.append(" (+").append(data.chanced().size()).append("概率)");
        if (!data.machine().isEmpty()) sb.append("  ← ").append(data.machine());
        return sb.toString();
    }

    /**
     * 完整 tooltip（多行，{@code \n} 分隔）：**与物品 tooltip 同源** —— 直接复用
     * {@code QianJiPatternData#describe()}，于是"列表里悬停看到的"与"把这张样板拿在手里看到的"
     * 内容完全一致（含输入、主产物、概率产出与概率值、来源机器）。
     * <p>
     * 长度截断由 {@link QianJiSearchResultPacket#encode} 统一做（writeUtf 超长会抛异常）。
     */
    private static String tooltip(com.ae2addon.recipe.QianJiPatternData data) {
        var lines = data.describe();
        var sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }
}
