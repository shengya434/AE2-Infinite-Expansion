package com.ae2addon.network;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.Mode2ConfigMenu;
import com.ae2addon.gui.ModeSelectMenu;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class RequestCellScreenPacket {
    public enum Target { SELECT, CONFIG }

    private final Target target;

    public RequestCellScreenPacket(Target target) { this.target = target; }

    public static void encode(RequestCellScreenPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.target);
    }

    public static RequestCellScreenPacket decode(FriendlyByteBuf buf) {
        return new RequestCellScreenPacket(buf.readEnum(Target.class));
    }

    public static void handle(RequestCellScreenPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!(player.containerMenu instanceof ModeSelectMenu || player.containerMenu instanceof Mode2ConfigMenu)) {
                AE2Addon.LOGGER.warn("[cellscreen] invalid navigation menu={}", player.containerMenu.getClass().getSimpleName());
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("gui.ae2addon.cell.target_missing"));
                return;
            }
            CellScreenTarget.CellTarget target = CellScreenTarget.resolveCellTarget(player);
            if (target == null) return;
            AE2Addon.LOGGER.info("[cellscreen] menu={} src={} newMode={} open={}",
                    player.containerMenu.getClass().getSimpleName(), target.source(),
                    target.stack().getOrCreateTag().getInt("umode"),
                    packet.target == Target.SELECT ? "ModeSelectMenu" : "Mode2ConfigMenu");
            if (packet.target == Target.SELECT) {
                UniversalStorageCell.openSelectMenuFor(player, target.stack(), target.drive(), target.driveSlot());
            } else {
                UniversalStorageCell.openConfigFor(player, target.stack(), target.drive(), target.driveSlot(), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
