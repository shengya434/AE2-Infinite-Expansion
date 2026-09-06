package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「退回网络」包（客户端 → 服务端，2026-09-06 sensei）：
 * 把该接口蓄水池全部材料（未喂出的推送料 + 待入网缓存产物）插回网络存储。
 * 适用样板发错/任务放弃后材料收不回的清理。
 */
public class FeederReturnPacket {

    private final BlockPos pos;

    public FeederReturnPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(FeederReturnPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
    }

    public static FeederReturnPacket decode(FriendlyByteBuf buf) {
        return new FeederReturnPacket(buf.readBlockPos());
    }

    public static void handle(FeederReturnPacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var level = ctx.get().getSender() == null ? null : ctx.get().getSender().level();
                if (level == null || !level.hasChunkAt(msg.pos)) {
                    return;
                }
                var fh = FeederHostResolver.resolve(level, msg.pos);
                if (fh != null) {
                    fh.returnAllToNetwork();
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }
}
