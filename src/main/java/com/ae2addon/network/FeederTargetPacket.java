package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 每标记缓存目标设置包（客户端 → 服务端，中键弹框输入）：
 * 设置指定接口方块上某个标记槽的独立补货目标（-1 = 清除独立值回退全局）。
 */
public class FeederTargetPacket {

    private final BlockPos pos;
    private final int markerIndex;
    private final long target;

    public FeederTargetPacket(BlockPos pos, int markerIndex, long target) {
        this.pos = pos;
        this.markerIndex = markerIndex;
        this.target = target;
    }

    public static void encode(FeederTargetPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeInt(msg.markerIndex);
        buf.writeLong(msg.target);
    }

    public static FeederTargetPacket decode(FriendlyByteBuf buf) {
        return new FeederTargetPacket(buf.readBlockPos(), buf.readInt(), buf.readLong());
    }

    public static void handle(FeederTargetPacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var level = ctx.get().getSender() == null ? null : ctx.get().getSender().level();
                if (level == null || !level.hasChunkAt(msg.pos)) {
                    return;
                }
                var fh = com.ae2addon.network.FeederHostResolver.resolve(level, msg.pos);
                    if (fh != null) {
                        fh.setMarkerTarget(msg.markerIndex, msg.target);
                    }
                            });
        }
        ctx.get().setPacketHandled(true);
    }
}
