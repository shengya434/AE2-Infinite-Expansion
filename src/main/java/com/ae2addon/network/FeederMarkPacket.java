package com.ae2addon.network;

import appeng.api.stacks.AEKey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 标记槽标记设置包（客户端 → 服务端）：JEI 拖取等客户端场景设置标记。
 * 携带标记槽索引 + AEKey（流体/气体/物品均可，WrappedGenericStack 存储）。
 */
public class FeederMarkPacket {

    private final int markerIndex;
    private final AEKey key;

    public FeederMarkPacket(int markerIndex, AEKey key) {
        this.markerIndex = markerIndex;
        this.key = key;
    }

    public static void encode(FeederMarkPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.markerIndex);
        buf.writeNbt(msg.key == null ? null : msg.key.toTagGeneric());
    }

    public static FeederMarkPacket decode(FriendlyByteBuf buf) {
        int index = buf.readByte();
        var tag = buf.readNbt();
        AEKey key = tag == null ? null : AEKey.fromTagGeneric(tag);
        return new FeederMarkPacket(index, key);
    }

    public static void handle(FeederMarkPacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var player = ctx.get().getSender();
                if (player == null || !(player.containerMenu instanceof com.ae2addon.gui.InfiniteInterfaceMenu menu)) {
                    return;
                }
                var feeder = menu.getFeeder();
                if (feeder == null) {
                    return;
                }
                if (msg.key == null) {
                    feeder.clearMarker(msg.markerIndex);
                } else {
                    feeder.markByKey(msg.markerIndex, msg.key);
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }
}
