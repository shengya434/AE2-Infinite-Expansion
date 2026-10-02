package com.ae2addon.network;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.gui.Mode2ConfigScreen;
import com.ae2addon.gui.ModeSelectScreen;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Mode2 wire envelope. Only operations backed by P2 public methods are enabled. */
public final class Mode2ConfigPacket implements IMessage {
    public static final int MAX_ITEMS_PER_CHUNK = 50;
    public int type;
    public long value;
    public int workMode;
    public boolean flag;
    public ItemStack item = ItemStack.EMPTY;
    public int chunkIndex;
    public int totalChunks;
    public List<PanelEntry> entries = new ArrayList<>();
    public NBTTagCompound state;

    public Mode2ConfigPacket() {}
    public Mode2ConfigPacket(int type) { this.type = type; }
    public Mode2ConfigPacket(int type, long value) { this.type = type; this.value = value; }
    public Mode2ConfigPacket(int type, boolean flag) { this.type = type; this.flag = flag; }
    public Mode2ConfigPacket(int type, ItemStack item) { this.type = type; this.item = item; }
    public static Mode2ConfigPacket workMode(int mode) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(6); p.workMode = mode; return p;
    }
    public static Mode2ConfigPacket itemThreshold(ItemStack item, long value) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(16, item); p.value = value; return p;
    }
    public static Mode2ConfigPacket panel(int index, int total, List<PanelEntry> entries) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(4);
        p.chunkIndex = index; p.totalChunks = total; p.entries = entries;
        return p;
    }
    public static Mode2ConfigPacket rejectState(UnlimitedCellInventory inv) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(18);
        p.state = new NBTTagCompound();
        p.state.setBoolean("default", inv.rejectDefault());
        p.state.setInteger("onCount", inv.rejectOnCount());
        p.state.setInteger("offCount", inv.rejectOffCount());
        return p;
    }

    @Override public void toBytes(ByteBuf raw) {
        PacketBuffer buf = new PacketBuffer(raw);
        buf.writeByte(type);
        switch (type) {
            case 0: buf.writeLong(value); buf.writeString(""); break;
            case 1: case 5: case 7: case 22: buf.writeItemStack(item); break;
            case 6: buf.writeVarInt(workMode); break;
            case 8: buf.writeBoolean(flag); break;
            case 16: buf.writeItemStack(item); buf.writeLong(value); break;
            case 18: buf.writeCompoundTag(state); break;
            case 4:
                buf.writeVarInt(chunkIndex); buf.writeVarInt(totalChunks); buf.writeVarInt(entries.size());
                for (PanelEntry entry : entries) {
                    buf.writeItemStack(entry.item);
                    buf.writeByteArray(entry.amount.toByteArray());
                    buf.writeBoolean(entry.infinite);
                    buf.writeBoolean(entry.whitelisted);
                    buf.writeBoolean(entry.rejecting);
                    buf.writeLong(entry.threshold);
                }
                break;
            case 3: case 23: break;
            default: throw new IllegalArgumentException("Unsupported mode2 packet type " + type);
        }
    }

    @Override public void fromBytes(ByteBuf raw) {
        PacketBuffer buf = new PacketBuffer(raw);
        type = buf.readUnsignedByte();
        try {
            switch (type) {
                case 0: value = buf.readLong(); buf.readString(64); break;
                case 1: case 5: case 7: case 22: item = buf.readItemStack(); break;
                case 6: workMode = buf.readVarInt(); break;
                case 8: flag = buf.readBoolean(); break;
                case 16: item = buf.readItemStack(); value = buf.readLong(); break;
                case 18: state = buf.readCompoundTag(); break;
                case 4:
                    chunkIndex = buf.readVarInt(); totalChunks = buf.readVarInt();
                    int size = buf.readVarInt();
                    if (totalChunks < 1 || totalChunks > 100000 || chunkIndex < 0 || chunkIndex >= totalChunks
                            || size < 0 || size > MAX_ITEMS_PER_CHUNK) throw new IOException("Invalid panel chunk");
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = buf.readItemStack();
                        byte[] amount = buf.readByteArray(8192);
                        boolean infinite = buf.readBoolean();
                        boolean whitelisted = buf.readBoolean();
                        boolean rejecting = buf.readBoolean();
                        long threshold = buf.readLong();
                        if (!stack.isEmpty()) entries.add(new PanelEntry(stack, new BigInteger(amount),
                                infinite, whitelisted, rejecting, threshold));
                    }
                    break;
                case 3: case 23: break;
                default: throw new IOException("Unsupported mode2 packet type " + type);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Bad mode2 packet", ex);
        }
    }

    public static final class PanelEntry {
        public final ItemStack item;
        public final BigInteger amount;
        public final boolean infinite;
        public final boolean whitelisted;
        public final boolean rejecting;
        public final long threshold;

        public PanelEntry(ItemStack item, BigInteger amount, boolean infinite,
                boolean whitelisted, boolean rejecting, long threshold) {
            this.item = item; this.amount = amount; this.infinite = infinite;
            this.whitelisted = whitelisted; this.rejecting = rejecting; this.threshold = threshold;
        }
    }

    public static final class ServerHandler implements IMessageHandler<Mode2ConfigPacket, IMessage> {
        @Override public IMessage onMessage(Mode2ConfigPacket msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServer().addScheduledTask(() -> handleServer(msg, player));
            return null;
        }
    }

    private static void handleServer(Mode2ConfigPacket msg, EntityPlayerMP player) {
        CellScreenTarget target = CellScreenTarget.resolve(player);
        if (target == null) return;
        UnlimitedCellInventory inv = new UnlimitedCellInventory(target.stack, null);
        if (msg.type == 0) inv.setThreshold(msg.value);
        else if (msg.type == 1) {
            IAEItemStack key = key(msg.item); if (key == null) return; inv.addWhitelist(key);
        } else if (msg.type == 5) {
            IAEItemStack key = key(msg.item); if (key == null) return; inv.cancelInfinite(key);
        } else if (msg.type == 6) {
            if (msg.workMode < 1 || msg.workMode > 3) return;
            inv.setWorkMode(msg.workMode);
        } else if (msg.type == 7) {
            IAEItemStack key = key(msg.item); if (key == null) return; inv.toggleReject(key);
        } else if (msg.type == 8) inv.setRejectDefault(msg.flag);
        else if (msg.type == 16) {
            IAEItemStack key = key(msg.item); if (key == null) return;
            inv.setItemThreshold(key, msg.value);
        } else if (msg.type == 22) {
            IAEItemStack key = key(msg.item); if (key == null) return; inv.clearReject(key);
        } else if (msg.type != 3 && msg.type != 23) return;
        sendState(player, inv);
    }

    private static IAEItemStack key(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        IAEItemStack key = AEItemStack.fromItemStack(stack);
        if (key != null) key.setStackSize(1);
        return key;
    }

    public static void sendState(EntityPlayerMP player, UnlimitedCellInventory inv) {
        CellNetwork.sendTo(rejectState(inv), player);
        List<PanelEntry> all = new ArrayList<>();
        for (IAEItemStack key : inv.getAvailableItems(UnlimitedCellInventory.itemChannel().createList())) {
            all.add(new PanelEntry(key.createItemStack(), inv.storedAmount(key), inv.isInfinite(key),
                    inv.isWhitelisted(key), inv.isRejecting(key), inv.thresholdFor(key)));
        }
        int total = Math.max(1, (all.size() + MAX_ITEMS_PER_CHUNK - 1) / MAX_ITEMS_PER_CHUNK);
        for (int index = 0; index < total; index++) {
            int from = index * MAX_ITEMS_PER_CHUNK;
            int to = Math.min(all.size(), from + MAX_ITEMS_PER_CHUNK);
            CellNetwork.sendTo(panel(index, total, new ArrayList<>(all.subList(from, to))), player);
        }
    }

    public static final class ClientHandler implements IMessageHandler<Mode2ConfigPacket, IMessage> {
        @Override public IMessage onMessage(Mode2ConfigPacket msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof Mode2ConfigScreen) ((Mode2ConfigScreen) screen).accept(msg);
                if (screen instanceof ModeSelectScreen) ((ModeSelectScreen) screen).accept(msg);
            });
            return null;
        }
    }
}
