package com.ae2addon.network;

import com.ae2addon.block.InfiniteDriveBE;
import com.ae2addon.gui.InfiniteDriveMenu;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 编辑器从驱动器当前页选取元件。槽号是驱动器内的绝对槽号。 */
public class CellEditorPickPacket {
    private final BlockPos pos;
    private final int slot;

    public CellEditorPickPacket(BlockPos pos, int slot) {
        this.pos = pos;
        this.slot = slot;
    }

    public static void encode(CellEditorPickPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeVarInt(packet.slot);
    }

    public static CellEditorPickPacket decode(FriendlyByteBuf buf) {
        return new CellEditorPickPacket(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(CellEditorPickPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !(player.containerMenu instanceof InfiniteDriveMenu menu)
                    || !menu.isEditorMode() || !menu.getDrivePos().equals(packet.pos)
                    || packet.slot < 0 || packet.slot >= InfiniteDriveBE.CELL_SLOTS
                    || packet.slot / 54 != menu.getCurrentPage()
                    || !player.level().hasChunkAt(packet.pos)
                    || !(player.level().getBlockEntity(packet.pos) instanceof InfiniteDriveBE drive)
                    || menu.getDrive() != drive
                    || !drive.isFormed()) return;

            ItemStack stack = drive.getInternalInventory().getStackInSlot(packet.slot);
            if (!(stack.getItem() instanceof UniversalStorageCell)) {
                player.sendSystemMessage(Component.translatable("gui.ae2addon.cell_editor.not_a_cell"));
                return;
            }
            UniversalStorageCell.openConfigFor(player, stack);
        });
        ctx.get().setPacketHandled(true);
    }
}
