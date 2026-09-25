package com.ae2addon.network;

import com.ae2addon.gui.QianJiTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 千机·样板终端的数据回包（服务端 → 客户端，2026-09-21 v254）。
 * <p>
 * 只带**当前窗口**那一段：{@code 定位串 + 短标签 + 完整 tooltip}。
 * 客户端拿它渲染长列表（滑条滚动）与悬停提示；**样板数据本体仍不下发**（铁律不变）。
 */
public final class QianJiTerminalPagePacket {

    /**
     * 一条列表项。
     *
     * @param pos     样板所在千机坐标
     * @param slot    该千机内的槽位号
     * @param label   列表行文本（已含定位串 + 主产物 + 来源机器）
     * @param tooltip 完整 tooltip（多行，{@code \n} 分隔；与样板物品同源）
     */
    public record Entry(BlockPos pos, int slot, String label, String tooltip) {}

    /** 单条 label / tooltip 的硬上限（writeUtf 超长会抛异常，必须先夹） */
    public static final int LABEL_MAX = 200;
    public static final int TOOLTIP_MAX = 460;

    /** 本次查询的关键词（空 = 列表模式） */
    public final String term;
    /** 结果总数（列表模式 = 全网样板总数；搜索模式 = 命中总数） */
    public final int total;
    /** 本段起始下标 */
    public final int offset;
    /** 本段条目 */
    public final List<Entry> entries;
    /** 网络里现有空白样板数量（"访问并使用"时要能看到家底） */
    public final long blankPatterns;

    /**
     * 这条网络上**最近一次把样板推进千机**的位置（v280）。
     * <p>
     * 顺带回包带给客户端 → 列表跳过去 + 高亮 3 秒。之所以搭在列表数据上而不是单独发定位包：
     * 列表数据本来就能可靠到达（列表一直在刷新就是证据），于是不再依赖"玩家对象 / 那一刻的菜单类型 / 终端形态"。
     * null = 没有（或已过期）。
     */
    public final net.minecraft.core.BlockPos focusPos;
    /** 最近一次推入落在哪个槽（{@code focusPos == null} 时无意义） */
    public final int focusSlot;
    /** 递增序号：客户端据此判断"这是新的一次推入"，同一次只跳一次 */
    public final long focusSeq;

    public QianJiTerminalPagePacket(String term, int total, int offset, List<Entry> entries,
                                    long blankPatterns) {
        this(term, total, offset, entries, blankPatterns, null, 0, 0L);
    }

    public QianJiTerminalPagePacket(String term, int total, int offset, List<Entry> entries,
                                    long blankPatterns, net.minecraft.core.BlockPos focusPos,
                                    int focusSlot, long focusSeq) {
        this.term = term == null ? "" : term;
        this.total = total;
        this.offset = offset;
        this.entries = entries == null ? List.of() : entries;
        this.blankPatterns = blankPatterns;
        this.focusPos = focusPos;
        this.focusSlot = focusSlot;
        this.focusSeq = focusSeq;
    }

    public static void encode(QianJiTerminalPagePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.term, 64);
        buf.writeVarInt(msg.total);
        buf.writeVarInt(msg.offset);
        buf.writeVarLong(msg.blankPatterns);
        buf.writeVarInt(msg.entries.size());
        for (Entry e : msg.entries) {
            buf.writeBlockPos(e.pos());
            buf.writeVarInt(e.slot());
            buf.writeUtf(cap(e.label(), LABEL_MAX), LABEL_MAX);
            buf.writeUtf(cap(e.tooltip(), TOOLTIP_MAX), TOOLTIP_MAX);
        }
        buf.writeBoolean(msg.focusPos != null);
        if (msg.focusPos != null) {
            buf.writeBlockPos(msg.focusPos);
            buf.writeVarInt(msg.focusSlot);
            buf.writeVarLong(msg.focusSeq);
        }
    }

    public static QianJiTerminalPagePacket decode(FriendlyByteBuf buf) {
        String term = buf.readUtf(64);
        int total = buf.readVarInt();
        int offset = buf.readVarInt();
        long blanks = buf.readVarLong();
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readBlockPos(), buf.readVarInt(),
                    buf.readUtf(LABEL_MAX), buf.readUtf(TOOLTIP_MAX)));
        }
        net.minecraft.core.BlockPos focusPos = null;
        int focusSlot = 0;
        long focusSeq = 0L;
        if (buf.readBoolean()) {
            focusPos = buf.readBlockPos();
            focusSlot = buf.readVarInt();
            focusSeq = buf.readVarLong();
        }
        return new QianJiTerminalPagePacket(term, total, offset, entries, blanks,
                focusPos, focusSlot, focusSeq);
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static void handle(QianJiTerminalPagePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof QianJiTerminalScreen screen) {
                screen.acceptPage(msg);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
