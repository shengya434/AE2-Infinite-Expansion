package com.ae2addon.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「一键成型」请求（客户端 → 服务端，2026-09-24 sensei 第 3 阶段）。
 * <p>
 * 界面上的「一键成型」按钮按下时发本包；服务端按坐标找控制器 BE，
 * 让它跑 {@code IntegratedCPUBE#startBuild} —— 取料、预检冲突、从下往上快速放置都在那边。
 */
public class IntegratedCpuBuildPacket {

    private final BlockPos pos;

    public IntegratedCpuBuildPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(IntegratedCpuBuildPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
    }

    public static IntegratedCpuBuildPacket decode(FriendlyByteBuf buf) {
        return new IntegratedCpuBuildPacket(buf.readBlockPos());
    }

    public static void handle(IntegratedCpuBuildPacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var player = ctx.get().getSender();
                if (player == null || player.level() == null || !player.level().hasChunkAt(msg.pos)) {
                    return;
                }
                if (player.level().getBlockEntity(msg.pos)
                        instanceof com.ae2addon.block.IntegratedCPUBE cpu) {
                    cpu.startBuild(player);
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }
}
