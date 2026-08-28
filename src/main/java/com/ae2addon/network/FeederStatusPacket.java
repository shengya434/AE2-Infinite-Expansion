package com.ae2addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * ME 接口（无限级）蓄水池状态同步包（服务端 → 客户端，变化检测节流）。
 */
public class FeederStatusPacket {

    private final List<String> lines;

    public FeederStatusPacket(List<String> lines) {
        this.lines = lines;
    }

    public static void encode(FeederStatusPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.lines.size());
        for (String line : msg.lines) {
            buf.writeUtf(line);
        }
    }

    public static FeederStatusPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buf.readUtf());
        }
        return new FeederStatusPacket(lines);
    }

    public static void handle(FeederStatusPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            com.ae2addon.gui.InfiniteInterfaceScreen.handleStatus(msg.lines);
        });
        ctx.get().setPacketHandled(true);
    }
}
