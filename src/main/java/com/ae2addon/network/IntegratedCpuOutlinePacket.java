package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Controller outline state; the client reads both patterns from its own resources. */
public final class IntegratedCpuOutlinePacket {
    private final BlockPos pos;
    private final boolean formed;
    private final boolean noExpand;
    private final Direction body;

    public IntegratedCpuOutlinePacket(BlockPos pos, boolean formed, boolean noExpand, Direction body) {
        this.pos = pos;
        this.formed = formed;
        this.noExpand = noExpand;
        this.body = body;
    }

    public static void encode(IntegratedCpuOutlinePacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeBoolean(packet.formed);
        buf.writeBoolean(packet.noExpand);
        buf.writeEnum(packet.body);
    }

    public static IntegratedCpuOutlinePacket decode(FriendlyByteBuf buf) {
        return new IntegratedCpuOutlinePacket(buf.readBlockPos(), buf.readBoolean(), buf.readBoolean(),
                buf.readEnum(Direction.class));
    }

    public static void handle(IntegratedCpuOutlinePacket packet, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.ae2addon.client.IntegratedCpuOutlineRenderer.update(
                    packet.pos, packet.formed, packet.noExpand, packet.body));
        }
        context.setPacketHandled(true);
    }
}
