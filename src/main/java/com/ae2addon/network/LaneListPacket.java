package com.ae2addon.network;

import com.ae2addon.gui.IntegratedCPUMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 量子分裂线程完整列表同步包（服务端 → 客户端）。
 * <p>
 * 修复原 8 槽位限制（@GuiSync lane0~lane7 只同步前 8 个线程，多余的全显示成
 * "线程7"）：改为一次性同步完整 lane 列表，客户端滚动面板渲染。
 * 服务端在 broadcastChanges 里做变化检测（状态类型变化才发），避免高频刷包。
 */
public final class LaneListPacket {

    /** 完整 lane 描述列表（服务端 JSON 序列化的 translatable Component） */
    public final List<String> lanes;
    public final int laneCount;
    public final int activeJobs;
    public final boolean formed;
    public final int selectedLaneIndex;

    public LaneListPacket(List<String> lanes, int laneCount, int activeJobs,
                          boolean formed, int selectedLaneIndex) {
        this.lanes = lanes;
        this.laneCount = laneCount;
        this.activeJobs = activeJobs;
        this.formed = formed;
        this.selectedLaneIndex = selectedLaneIndex;
    }

    public static void encode(LaneListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.lanes.size());
        for (String s : msg.lanes) {
            buf.writeUtf(s);
        }
        buf.writeVarInt(msg.laneCount);
        buf.writeVarInt(msg.activeJobs);
        buf.writeBoolean(msg.formed);
        buf.writeVarInt(msg.selectedLaneIndex);
    }

    public static LaneListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> lanes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            lanes.add(buf.readUtf());
        }
        return new LaneListPacket(lanes, buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(LaneListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu instanceof IntegratedCPUMenu menu) {
                menu.fullLanes = msg.lanes;
                menu.laneCount = msg.laneCount;
                menu.activeJobs = msg.activeJobs;
                menu.formed = msg.formed;
                menu.selectedLaneIndex = msg.selectedLaneIndex;
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
