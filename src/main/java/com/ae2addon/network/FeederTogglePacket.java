package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 主动输入/输出开关包（客户端 → 服务端）：
 * which = "extract"（主动抽取）/ "feed"（主动喂出），切换对应开关。
 */
public class FeederTogglePacket {

    private final BlockPos pos;
    private final String which;

    public FeederTogglePacket(BlockPos pos, String which) {
        this.pos = pos;
        this.which = which;
    }

    public static void encode(FeederTogglePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.which);
    }

    public static FeederTogglePacket decode(FriendlyByteBuf buf) {
        return new FeederTogglePacket(buf.readBlockPos(), buf.readUtf());
    }

    public static void handle(FeederTogglePacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var level = ctx.get().getSender() == null ? null : ctx.get().getSender().level();
                if (level == null || !level.hasChunkAt(msg.pos)) {
                    return;
                }
                var fh = com.ae2addon.network.FeederHostResolver.resolve(level, msg.pos);
                    if (fh != null) {
                        fh.toggleActive(msg.which);
                    }
                            });
        }
        ctx.get().setPacketHandled(true);
    }
}
