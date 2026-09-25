package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「放置被阻挡」红色方框位置（服务端 → 客户端，2026-09-24）。
 * <p>
 * 粒子画的框会被方块挡住（sensei 实测：「红色方框无法穿墙显示」），
 * 所以改成客户端渲染描边（{@code RenderType.lines()} + 关闭深度测试 = 穿墙可见）。
 * 本包只送冲突坐标，客户端在 {@code IntegratedCpuConflictRenderer} 里画。
 */
public class IntegratedCpuConflictPacket {

    private final BlockPos pos;
    /** 显示时长（tick），0 = 立即清除 */
    private final int ticks;

    public IntegratedCpuConflictPacket(BlockPos pos, int ticks) {
        this.pos = pos;
        this.ticks = ticks;
    }

    public static void encode(IntegratedCpuConflictPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.ticks);
    }

    public static IntegratedCpuConflictPacket decode(FriendlyByteBuf buf) {
        return new IntegratedCpuConflictPacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(IntegratedCpuConflictPacket msg, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.ae2addon.client.IntegratedCpuConflictRenderer
                    .setHighlight(msg.pos, msg.ticks));
        }
        context.setPacketHandled(true);
    }
}
