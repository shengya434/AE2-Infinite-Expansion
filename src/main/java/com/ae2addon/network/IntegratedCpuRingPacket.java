package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Current controller formation and structure orientation for nearby ring renderers. */
public final class IntegratedCpuRingPacket {
    private final BlockPos pos;
    private final boolean formed;
    private final Direction body;

    public IntegratedCpuRingPacket(BlockPos pos, boolean formed, Direction body) {
        this.pos = pos;
        this.formed = formed;
        this.body = body;
    }

    public static void encode(IntegratedCpuRingPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeBoolean(msg.formed);
        buf.writeEnum(msg.body);
    }

    public static IntegratedCpuRingPacket decode(FriendlyByteBuf buf) {
        return new IntegratedCpuRingPacket(buf.readBlockPos(), buf.readBoolean(), buf.readEnum(Direction.class));
    }

    public static void handle(IntegratedCpuRingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.ae2addon.client.IntegratedCpuRingRenderer.update(
                    msg.pos, msg.formed, msg.body));
        }
        context.setPacketHandled(true);
    }
}
