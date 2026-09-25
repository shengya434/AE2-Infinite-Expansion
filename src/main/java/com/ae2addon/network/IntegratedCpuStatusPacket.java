package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 集成 CPU 的**网络侧展示信息**（服务端 → 客户端，2026-09-24）。
 * <p>
 * sensei 实测：AE2 自己的 CPU 列表里，集成 CPU 只显示一个物品图标，
 * **线程数/存储都不显示**（因为它没有 AE2 主簇，AE2 的列表拿不到这些数字）。
 * 所以这里由控制器主动把数值推给客户端，客户端在合成状态界面上叠一层显示。
 */
public class IntegratedCpuStatusPacket {

    private final BlockPos pos;
    private final int lanes;
    private final int activeJobs;
    private final long storageBytes;
    private final int threads;
    private final boolean formed;

    public IntegratedCpuStatusPacket(BlockPos pos, int lanes, int activeJobs,
                                     long storageBytes, int threads, boolean formed) {
        this.pos = pos;
        this.lanes = lanes;
        this.activeJobs = activeJobs;
        this.storageBytes = storageBytes;
        this.threads = threads;
        this.formed = formed;
    }

    public static void encode(IntegratedCpuStatusPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.lanes);
        buf.writeVarInt(msg.activeJobs);
        buf.writeLong(msg.storageBytes);
        buf.writeVarInt(msg.threads);
        buf.writeBoolean(msg.formed);
    }

    public static IntegratedCpuStatusPacket decode(FriendlyByteBuf buf) {
        return new IntegratedCpuStatusPacket(buf.readBlockPos(), buf.readVarInt(),
                buf.readVarInt(), buf.readLong(), buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(IntegratedCpuStatusPacket msg, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.ae2addon.client.IntegratedCpuStatusHud.update(msg.pos,
                    msg.lanes, msg.activeJobs, msg.storageBytes, msg.threads, msg.formed));
        }
        context.setPacketHandled(true);
    }
}
