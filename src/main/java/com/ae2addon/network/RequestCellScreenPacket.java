package com.ae2addon.network;

import com.ae2addon.gui.CellScreens;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketBuffer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** C to S: navigate while retaining the same live hand held cell. */
public final class RequestCellScreenPacket implements IMessage {
    private int target;
    public RequestCellScreenPacket() {}
    public RequestCellScreenPacket(int target) { this.target = target; }
    @Override public void fromBytes(ByteBuf buf) { target = new PacketBuffer(buf).readByte(); }
    @Override public void toBytes(ByteBuf buf) { new PacketBuffer(buf).writeByte(target); }

    public static final class Handler implements IMessageHandler<RequestCellScreenPacket, IMessage> {
        @Override public IMessage onMessage(RequestCellScreenPacket msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServer().addScheduledTask(() -> {
                CellScreenTarget cell = CellScreenTarget.resolve(player);
                if (cell != null && (msg.target == CellScreens.SELECT || msg.target == CellScreens.CONFIG)) {
                    CellScreens.open(player, msg.target, cell.hand);
                }
            });
            return null;
        }
    }
}
