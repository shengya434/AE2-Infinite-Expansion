package com.ae2addon.network;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;

/** One Forge channel; discriminator ranges are reserved by direction. */
public final class CellNetwork {
    private static SimpleNetworkWrapper channel;

    private CellNetwork() {}

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("ae2addon");
        channel.registerMessage(SetCellModePacket.Handler.class, SetCellModePacket.class, 0, Side.SERVER);
        channel.registerMessage(Mode2ConfigPacket.ServerHandler.class, Mode2ConfigPacket.class, 1, Side.SERVER);
        channel.registerMessage(RequestCellScreenPacket.Handler.class, RequestCellScreenPacket.class, 2, Side.SERVER);
        channel.registerMessage(Mode2ConfigPacket.ClientHandler.class, Mode2ConfigPacket.class, 50, Side.CLIENT);
    }

    public static void send(IMessage message) { channel.sendToServer(message); }
    public static void sendTo(IMessage message, EntityPlayerMP player) { channel.sendTo(message, player); }
}
