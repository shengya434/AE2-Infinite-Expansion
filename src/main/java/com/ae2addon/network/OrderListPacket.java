package com.ae2addon.network;

import com.ae2addon.gui.IntegratedCPUMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 巨型订单列表同步包（服务端 → 客户端）。
 * <p>
 * 集成 CPU 界面「巨型订单」管理面板的数据源：每个订单一行
 * （物品 + 进度 + 状态），客户端渲染后可一键取消整个订单。
 */
public final class OrderListPacket {

    /** 订单描述列表（服务端 JSON 序列化的 translatable Component） */
    public final List<String> orders;

    public OrderListPacket(List<String> orders) {
        this.orders = orders;
    }

    public static void encode(OrderListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.orders.size());
        for (String s : msg.orders) {
            buf.writeUtf(s);
        }
    }

    public static OrderListPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> orders = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            orders.add(buf.readUtf());
        }
        return new OrderListPacket(orders);
    }

    public static void handle(OrderListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu instanceof IntegratedCPUMenu menu) {
                menu.fullOrders = msg.orders;
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
