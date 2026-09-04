package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 装配处理器样板槽翻页包（客户端 → 服务端）：
 * 目标页（0..199），服务端 AssemblerCoreBE.setPage + 菜单槽自动刷新。
 */
public class AssemblerPagePacket {

    private final BlockPos pos;
    private final int page;

    public AssemblerPagePacket(BlockPos pos, int page) {
        this.pos = pos;
        this.page = page;
    }

    public static void encode(AssemblerPagePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.page);
    }

    public static AssemblerPagePacket decode(FriendlyByteBuf buf) {
        return new AssemblerPagePacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(AssemblerPagePacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var player = ctx.get().getSender();
                if (player == null || player.level() == null
                        || !player.level().hasChunkAt(msg.pos)) {
                    return;
                }
                if (player.level().getBlockEntity(msg.pos)
                        instanceof com.ae2addon.block.AssemblerCoreBE core) {
                    core.setPage(msg.page);
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }
}
