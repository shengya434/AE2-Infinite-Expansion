package com.ae2addon.network;

import com.ae2addon.gui.QianJiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 千机 GUI 搜索结果（服务端 → 客户端，2026-09-21 v244）。
 * <p>
 * 只带**命中项**：槽位号 + 一行短标签。不带样板完整数据（客户端不需要，也不该拿到
 * 1280 槽的内容 —— 同"大槽位不同步"铁律）。客户端拿到的这批命中用来：
 * ① 在结果面板里列出；② 在当前页上给命中的槽画绿框；③ 点击后跳到它所在的页。
 */
public final class QianJiSearchResultPacket {

    /**
     * 单条命中：槽位号（0..1279，页 = slot / 54）+ 列表里的一行短标签 + **完整 tooltip 文本**。
     * <p>
     * 为什么要带 tooltip：2026-09-21 sensei 反馈"只显示机器名和产物，区分效果不太强"——
     * 搜索结果里好几条看着一样，得能看到**输入有哪些、概率产出是什么**才能分辨。
     * tooltip 文本在**服务端**用 {@code QianJiPatternData#describe()} 拼好（与物品 tooltip 同源），
     * 客户端仍然拿不到样板数据本身（1280 槽不同步的铁律不破）。
     *
     * @param slot    样板槽位号
     * @param label   列表行短标签（主产物 + 概率条数 + 来源机器）
     * @param tooltip 完整 tooltip，多行用 {@code \n} 分隔（服务端已按 {@link #TOOLTIP_MAX} 截断）
     */
    public record Hit(int slot, String label, String tooltip) {}

    /**
     * tooltip 文本上限（字符）。**必须是硬上限**：{@code FriendlyByteBuf#writeUtf(s, max)}
     * 在超长时会直接抛 {@code EncoderException}（不是截断），所以服务端必须先截好。
     */
    public static final int TOOLTIP_MAX = 460;
    /** 列表行标签上限 */
    public static final int LABEL_MAX = 120;

    /** 本次搜索用的关键词（客户端显示"搜索 X → 命中 N 条"） */
    public final String term;
    /** 命中列表（按槽位升序 = 服务端扫描顺序） */
    public final List<Hit> hits;

    public QianJiSearchResultPacket(String term, List<Hit> hits) {
        this.term = term == null ? "" : term;
        this.hits = hits == null ? List.of() : hits;
    }

    public static void encode(QianJiSearchResultPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.term, 64);
        buf.writeVarInt(msg.hits.size());
        for (Hit hit : msg.hits) {
            buf.writeVarInt(hit.slot());
            buf.writeUtf(cap(hit.label(), LABEL_MAX), LABEL_MAX);
            buf.writeUtf(cap(hit.tooltip(), TOOLTIP_MAX), TOOLTIP_MAX);
        }
    }

    public static QianJiSearchResultPacket decode(FriendlyByteBuf buf) {
        String term = buf.readUtf(64);
        int size = buf.readVarInt();
        List<Hit> hits = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            hits.add(new Hit(buf.readVarInt(), buf.readUtf(LABEL_MAX), buf.readUtf(TOOLTIP_MAX)));
        }
        return new QianJiSearchResultPacket(term, hits);
    }

    /** 兜底截断：writeUtf 超长会抛异常而不是静默截断，所以这里先夹住 */
    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void handle(QianJiSearchResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof QianJiScreen screen) {
                screen.acceptSearchResult(msg.term, msg.hits);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
