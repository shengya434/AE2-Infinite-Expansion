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

    private final String key;
    private final long value;

    public FeederSettingPacket(String key, long value) {
        this.key = key;
        this.value = value;
    }

    public static void encode(FeederSettingPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.key);
        buf.writeLong(msg.value);
    }

    public static FeederSettingPacket decode(FriendlyByteBuf buf) {
        return new FeederSettingPacket(buf.readUtf(), buf.readLong());
    }

    public static void handle(FeederSettingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isServer()) {
            ctx.get().enqueueWork(() -> {
                boolean applied = switch (msg.key) {
                    case "stockTarget" -> {
                        var v = AE2AddonConfig.FEEDER_STOCK_TARGET;
                        v.set(Math.max(0L, Math.min(Long.MAX_VALUE, msg.value)));
                        yield true;
                    }
                    case "restockInterval" -> {
                        var v = AE2AddonConfig.FEEDER_RESTOCK_INTERVAL;
                        v.set((int) Math.max(1, Math.min(200, msg.value)));
                        yield true;
                    }
                    case "feedBudget" -> {
                        var v = AE2AddonConfig.FEEDER_FEED_BUDGET;
                        v.set((int) Math.max(1, Math.min(1_000_000, msg.value)));
                        yield true;
                    }
                    default -> false;
                };
                if (applied) {
                    AE2AddonConfig.SPEC.save();
                    AE2AddonConfig.apply();
                    com.ae2addon.AE2Addon.LOGGER.info(
                            "[ae2addon][feeder] GUI设置更新 {} = {}", msg.key, msg.value);
                }
            });
        }
        ctx.get().setPacketHandled(true);
    }
}
