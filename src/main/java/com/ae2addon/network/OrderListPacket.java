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
 * <p>
 * 2026-09-15 修复：除了描述文本，同时下发每行的**订单 id** ——
 * 客户端取消时回传 id，服务端按 id 精确定位。此前客户端回传的是**行索引**，
 * 而面板列表是按**网格过滤**的、服务端取的是**全局**列表索引 → 多网络/多玩家时
 * 索引错位，会取消到别人的订单。
 */
public final class OrderListPacket {

    /** 订单 id 列表（与 {@link #orders} 同序同长） */
    public final List<Integer> orderIds;

    /** 订单描述列表（服务端 JSON 序列化的 translatable Component） */
    public final List<String> orders;

    public OrderListPacket(List<Integer> orderIds, List<String> orders) {
        this.orderIds = orderIds;
        this.orders = orders;
    }

    public static void encode(OrderListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.orderIds.size());
        for (int id : msg.orderIds) {
            buf.writeVarInt(id);
        }
        buf.writeVarInt(msg.orders.size());
        for (String s : msg.orders) {
            buf.writeUtf(s);
        }
    }

    public static OrderListPacket decode(FriendlyByteBuf buf) {
        int idCount = buf.readVarInt();
        List<Integer> orderIds = new ArrayList<>(idCount);
        for (int i = 0; i < idCount; i++) {
            orderIds.add(buf.readVarInt());
        }
        int size = buf.readVarInt();
        List<String> orders = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            orders.add(buf.readUtf());
        }
        return new OrderListPacket(orderIds, orders);
    }

    public static void handle(OrderListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu instanceof IntegratedCPUMenu menu) {
                menu.fullOrderIds = msg.orderIds;
                menu.fullOrders = msg.orders;
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
