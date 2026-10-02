package com.ae2addon.network;

import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.gui.CellScreens;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketBuffer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** C to S: umode, independent of Mode2 work mode. */
public final class SetCellModePacket implements IMessage {
    private int mode;
    public SetCellModePacket() {}
    public SetCellModePacket(int mode) { this.mode = mode; }
    @Override public void fromBytes(ByteBuf buf) { mode = new PacketBuffer(buf).readInt(); }
    @Override public void toBytes(ByteBuf buf) { new PacketBuffer(buf).writeInt(mode); }

    public static final class Handler implements IMessageHandler<SetCellModePacket, IMessage> {
        @Override public IMessage onMessage(SetCellModePacket msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServer().addScheduledTask(() -> {
                CellScreenTarget target = CellScreenTarget.resolve(player);
                if (target == null || msg.mode < 1 || msg.mode > 3) return;
                new UnlimitedCellInventory(target.stack, null).setMode(msg.mode);
                CellScreens.open(player, msg.mode == 2 ? CellScreens.CONFIG : CellScreens.SELECT, target.hand);
            });
            return null;
        }
    }
}
