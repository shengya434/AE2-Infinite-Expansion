package com.ae2addon.network;

import com.ae2addon.gui.QianJiTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「样板刚被放进千机 → 让终端列表跳过去并高亮」的通知（服务端 → 客户端，2026-09-22 v277）。
 * <p>
 * sensei 要的：插入千机那一刻，前端列表自动滚到那个栏位并高亮 3 秒，省得自己找。
 * <p>
 * 为什么由服务端发：两个插入路径服务端才知道**最终落在哪个槽**
 * （界面上的"右键放进去"会在那台千机里找第一个空槽；编码槽的自动推入同理），
 * 客户端只有"某一行"的信息、甚至完全没有（自动推入根本没有客户端动作）。
 * 包里只带坐标 + 槽位 —— 客户端拿它在**已有列表**里找那一行，不额外同步任何样板数据。
 */
public final class QianJiTerminalFocusPacket {

    /** 目标千机坐标 */
    public final BlockPos pos;
    /** 目标槽位（千机内的绝对槽号） */
    public final int slot;

    public QianJiTerminalFocusPacket(BlockPos pos, int slot) {
        this.pos = pos;
        this.slot = slot;
    }

    public static void encode(QianJiTerminalFocusPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.slot);
    }

    public static QianJiTerminalFocusPacket decode(FriendlyByteBuf buf) {
        return new QianJiTerminalFocusPacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(QianJiTerminalFocusPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof QianJiTerminalScreen screen) {
                screen.acceptFocus(msg.pos, msg.slot);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
