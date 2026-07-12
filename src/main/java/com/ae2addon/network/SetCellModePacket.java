package com.ae2addon.network;

import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 设置存储元件模式的数据包
 * <p>
 * 客户端发送模式编号 → 服务端更新玩家手中物品的模式
 * 通过 UnlimitedCellInventory.setMode() 同步更新摘要标签 _b/_t
 */
public class SetCellModePacket {

    private final int mode;

    public SetCellModePacket(int mode) {
        this.mode = mode;
    }

    public static void encode(SetCellModePacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.mode);
    }

    public static SetCellModePacket decode(FriendlyByteBuf buf) {
        return new SetCellModePacket(buf.readInt());
    }

    public static void handle(SetCellModePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof UniversalStorageCell)) {
                stack = player.getOffhandItem();
                if (!(stack.getItem() instanceof UniversalStorageCell)) return;
            }

            int newMode = packet.mode;
            if (newMode < 1 || newMode > 3) {
                // mode 0 来自「⇄ 模式」按钮：循环到下一个模式
                int cur = stack.getOrCreateTag().getInt("umode");
                if (cur < 1 || cur > 3) cur = 1;
                newMode = (cur % 3) + 1;
            }

            // 通过 inventory 设置模式，同步更新 _b/_t 摘要标签
            UnlimitedCellInventory inv = new UnlimitedCellInventory(stack, null);
            inv.setMode(newMode);
        });
        ctx.get().setPacketHandled(true);
    }
}
