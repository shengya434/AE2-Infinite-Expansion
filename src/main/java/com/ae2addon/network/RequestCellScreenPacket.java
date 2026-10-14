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
    /**
     * 目标界面。**只剩 SELECT**：
     * 原先还有 {@code CONFIG}（「从模式选择页不改模式、直接进配置页」那条路），
     * 2026-10-14 清掉 —— 点「自定义无限」当场就会进配置页
     * （见 {@link SetCellModePacket#handle} 里的 {@code openConfigFor(..., newMode == 2)}），
     * 那条路既没接完也没用了。载荷形状保持不变，将来要加回来只需补枚举与分支。
     */
    public enum Target { SELECT }

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
            AE2Addon.LOGGER.info("[cellscreen] menu={} src={} newMode={} open=ModeSelectMenu",
                    player.containerMenu.getClass().getSimpleName(), target.source(),
                    target.stack().getOrCreateTag().getInt("umode"));
            UniversalStorageCell.openSelectMenuFor(player, target.stack(), target.drive(), target.driveSlot());
        });
        ctx.get().setPacketHandled(true);
    }
}
