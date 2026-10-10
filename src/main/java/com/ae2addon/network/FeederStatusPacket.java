package com.ae2addon.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * ME 接口（无限级）蓄水池状态同步包（服务端 → 客户端，变化检测节流）。
 * <p>
 * 2026-10-10（i18n 收尾）：除状态行文本外，**另行携带三个参数的真实数值**
 * （补货目标 / 补货间隔 / 喂出预算）。原先客户端是靠把 {@code gui.ae2addon.feeder.params}
 * 的**本地化文案**反解析回数值来填输入框（{@code contains("目标=")} 之类）——
 * 一旦改文案或换语言就静默填不上。文本只用于显示，数值走这里。
 */
public class FeederStatusPacket {

    private final List<String> lines;
    /** 补货目标（每接口实际值，已含全局回退）。 */
    private final long stockTarget;
    /** 补货间隔 tick。 */
    private final int restockInterval;
    /** 喂出预算。 */
    private final int feedBudget;

    public FeederStatusPacket(List<String> lines, long stockTarget, int restockInterval,
            int feedBudget) {
        this.lines = lines;
        this.stockTarget = stockTarget;
        this.restockInterval = restockInterval;
        this.feedBudget = feedBudget;
    }

    public static void encode(FeederStatusPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.lines.size());
        for (String line : msg.lines) {
            buf.writeUtf(line);
        }
        buf.writeLong(msg.stockTarget);
        buf.writeVarInt(msg.restockInterval);
        buf.writeVarInt(msg.feedBudget);
    }

    public static FeederStatusPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buf.readUtf());
        }
        return new FeederStatusPacket(lines, buf.readLong(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(FeederStatusPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            com.ae2addon.gui.InfiniteInterfaceScreen.handleStatus(
                    msg.lines, msg.stockTarget, msg.restockInterval, msg.feedBudget);
        });
        ctx.get().setPacketHandled(true);
    }
}
