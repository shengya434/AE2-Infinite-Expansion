package com.ae2addon.network;

import com.ae2addon.config.AE2AddonConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 接口 GUI 内联设置包（客户端 → 服务端）：修改接口运行参数并热加载。
 * <p>
 * key: stockTarget / restockInterval / feedBudget；value 已由客户端解析为 long，
 * 服务端按各配置项范围钳制后写盘 + apply（与 mods 配置界面同一套机制）。
 */
public class FeederSettingPacket {

    private final net.minecraft.core.BlockPos pos;
    private final String key;
    private final long value;

    public FeederSettingPacket(net.minecraft.core.BlockPos pos, String key, long value) {
        this.pos = pos;
        this.key = key;
        this.value = value;
    }

    public static void encode(FeederSettingPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeUtf(msg.key);
        buf.writeLong(msg.value);
    }

    public static FeederSettingPacket decode(FriendlyByteBuf buf) {
        return new FeederSettingPacket(buf.readBlockPos(), buf.readUtf(), buf.readLong());
    }

    public static void handle(FeederSettingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                var level = ctx.get().getSender() == null ? null : ctx.get().getSender().level();
                if (level == null || !level.hasChunkAt(msg.pos)) {
                    return;
                }
                if (level.getBlockEntity(msg.pos)
                        instanceof com.ae2addon.block.InfiniteInterfaceBE be) {
                    be.setPerBlockParam(msg.key, msg.value);
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }
}
