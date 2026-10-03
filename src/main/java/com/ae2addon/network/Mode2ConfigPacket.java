package com.ae2addon.network;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.fml.common.Loader;

/** 24-type mode2 wire envelope. Type 13 is reserved for JEI direct add. */
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
    public String text = "", quantityText = "";
    public boolean isOre, lock;
    public int ruleMode;
    public List<String> rules = new ArrayList<>();
    public List<Integer> modes = new ArrayList<>();
    public List<ItemStack> keys = new ArrayList<>(), on = new ArrayList<>(), off = new ArrayList<>();

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
    public static Mode2ConfigPacket rule(int type, boolean ore, String name, int mode) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(type);
        p.isOre = ore; p.text = name; p.ruleMode = mode; return p;
    }
    public static Mode2ConfigPacket quantity(ItemStack item, String amount, boolean locked) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(17, item);
        p.quantityText = amount; p.lock = locked; return p;
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
        p.state.setBoolean("ruleInstant", inv.isRuleInstant());
        NBTTagList rejectOn = new NBTTagList(), rejectOff = new NBTTagList();
        for (IAEItemStack key : inv.getRejectOnKeys())
            rejectOn.appendTag(key.createItemStack().writeToNBT(new NBTTagCompound()));
        for (IAEItemStack key : inv.getRejectOffKeys())
            rejectOff.appendTag(key.createItemStack().writeToNBT(new NBTTagCompound()));
        p.state.setTag("on", rejectOn);
        p.state.setTag("off", rejectOff);
        NBTTagList committed = new NBTTagList();
        for (UnlimitedCellInventory.PanelItem row : inv.getPanelItems()) {
            if (!row.isInfinite) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("key", row.key.createItemStack().writeToNBT(new NBTTagCompound()));
            entry.setString("amount", inv.getCommitedAmount(row.key).toString());
            entry.setBoolean("has", inv.hasCommitedAmount(row.key));
            committed.appendTag(entry);
        }
        p.state.setTag("committed", committed);
        return p;
    }

    private static void writeKeys(PacketBuffer buf, List<ItemStack> list) {
        buf.writeVarInt(list.size());
        for (ItemStack stack : list) buf.writeItemStack(stack);
    }
    private static List<ItemStack> readKeys(PacketBuffer buf) throws IOException {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ITEMS_PER_CHUNK) throw new IOException("Invalid key chunk");
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = buf.readItemStack();
            if (!stack.isEmpty()) list.add(stack);
        }
        return list;
    }
    private static void checkChunk(Mode2ConfigPacket p) throws IOException {
        if (p.totalChunks < 1 || p.totalChunks > 100000 || p.chunkIndex < 0
                || p.chunkIndex >= p.totalChunks) throw new IOException("Invalid chunk index");
    }

    @Override public void toBytes(ByteBuf raw) {
        PacketBuffer buf = new PacketBuffer(raw);
        buf.writeByte(type);
        switch (type) {
            case 0: case 2: case 14: buf.writeLong(value); buf.writeString(text); break;
            case 1: case 5: case 7: case 11: case 13: case 22: buf.writeItemStack(item); break;
            case 6: buf.writeVarInt(workMode); break;
            case 8: case 10: buf.writeBoolean(flag); break;
            case 16: buf.writeItemStack(item); buf.writeLong(value); break;
            case 17: buf.writeItemStack(item); buf.writeString(quantityText); buf.writeBoolean(lock); break;
            case 18: buf.writeCompoundTag(state); break;
            case 9:
                buf.writeBoolean(isOre); buf.writeVarInt(rules.size());
                for (int i = 0; i < rules.size(); i++) {
                    buf.writeString(rules.get(i)); buf.writeByte(modes.get(i));
                }
                break;
            case 12: buf.writeVarInt(chunkIndex); buf.writeVarInt(totalChunks); writeKeys(buf, keys); break;
            case 15: buf.writeBoolean(isOre); buf.writeString(text); buf.writeVarInt(ruleMode); break;
            case 19: case 20: buf.writeBoolean(isOre); buf.writeString(text); break;
            case 21:
                buf.writeVarInt(chunkIndex); buf.writeVarInt(totalChunks); writeKeys(buf, on); writeKeys(buf, off);
                break;
            case 4:
                buf.writeVarInt(chunkIndex); buf.writeVarInt(totalChunks); buf.writeVarInt(entries.size());
                for (PanelEntry entry : entries) {
                    buf.writeItemStack(entry.item);
                    buf.writeByteArray(entry.amount.toByteArray());
                    buf.writeBoolean(entry.infinite);
                    buf.writeVarLong(entry.bytes);
                    buf.writeLong(entry.threshold);
                    buf.writeBoolean(entry.quantityLocked);
                    buf.writeBoolean(entry.whitelisted);
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
                case 0: case 2: case 14: value = buf.readLong(); text = buf.readString(256); break;
                case 1: case 5: case 7: case 11: case 13: case 22: item = buf.readItemStack(); break;
                case 6: workMode = buf.readVarInt(); break;
                case 8: case 10: flag = buf.readBoolean(); break;
                case 16: item = buf.readItemStack(); value = buf.readLong(); break;
                case 17: item = buf.readItemStack(); quantityText = buf.readString(128); lock = buf.readBoolean(); break;
                case 18: state = buf.readCompoundTag(); break;
                case 9:
                    isOre = buf.readBoolean(); int count = buf.readVarInt();
                    if (count < 0 || count > 10000) throw new IOException("Invalid rule count");
                    for (int i = 0; i < count; i++) { rules.add(buf.readString(256)); modes.add((int) buf.readByte()); }
                    break;
                case 12:
                    chunkIndex = buf.readVarInt(); totalChunks = buf.readVarInt(); checkChunk(this);
                    keys = readKeys(buf); break;
                case 15: isOre = buf.readBoolean(); text = buf.readString(256); ruleMode = buf.readVarInt(); break;
                case 19: case 20: isOre = buf.readBoolean(); text = buf.readString(256); break;
                case 21:
                    chunkIndex = buf.readVarInt(); totalChunks = buf.readVarInt(); checkChunk(this);
                    on = readKeys(buf); off = readKeys(buf); break;
                case 4:
                    chunkIndex = buf.readVarInt(); totalChunks = buf.readVarInt();
                    int size = buf.readVarInt();
                    checkChunk(this);
                    if (size < 0 || size > MAX_ITEMS_PER_CHUNK) throw new IOException("Invalid panel chunk");
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = buf.readItemStack();
                        byte[] amount = buf.readByteArray(8192);
                        boolean infinite = buf.readBoolean();
                        long bytes = buf.readVarLong();
                        long threshold = buf.readLong();
                        boolean locked = buf.readBoolean();
                        boolean whitelisted = buf.readBoolean();
                        if (!stack.isEmpty() && amount.length > 0) entries.add(new PanelEntry(stack, new BigInteger(amount),
                                infinite, bytes, threshold, locked, whitelisted));
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
        public final long bytes;
        public final boolean quantityLocked;
        public final boolean whitelisted;
        public final long threshold;

        public PanelEntry(ItemStack item, BigInteger amount, boolean infinite, long bytes,
                long threshold, boolean quantityLocked, boolean whitelisted) {
            this.item = item; this.amount = amount; this.infinite = infinite;
            this.bytes = bytes; this.threshold = threshold; this.quantityLocked = quantityLocked;
            this.whitelisted = whitelisted;
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
        else if (msg.type == 1 || msg.type == 13) {
            IAEItemStack key = key(msg.item); if (key == null) return; inv.addWhitelist(key);
        } else if (msg.type == 2) {
            for (IAEItemStack key : inv.getWl()) {
                ItemStack stack = key.createItemStack();
                if (stack.getItem().getRegistryName() != null
                        && stack.getItem().getRegistryName().toString().equals(msg.text)) inv.removeWhitelist(key);
            }
        } else if (msg.type == 5) {
            IAEItemStack key = key(msg.item); if (key == null) return; inv.togglePanelInfinite(key);
        } else if (msg.type == 6) {
            if (msg.workMode < 1 || msg.workMode > 3) return;
            inv.setWorkMode(msg.workMode);
        } else if (msg.type == 7) {
            IAEItemStack key = key(msg.item); if (key == null) return; inv.toggleReject(key);
        } else if (msg.type == 8) inv.setRejectDefault(msg.flag);
        else if (msg.type == 10) inv.setRuleInstant(msg.flag);
        else if (msg.type == 11) {
            IAEItemStack key = key(msg.item);
            if (key == null) {
                sendKeys(player, 12, stackList(inv.getBlacklist()), new ArrayList<>());
                return;
            }
            inv.toggleBlacklist(key);
        } else if (msg.type == 14) inv.wipeAllData();
        else if (msg.type == 15) inv.setRuleMode(UnlimitedCellInventory.ruleKey(msg.isOre, msg.text), msg.ruleMode);
        else if (msg.type == 16) {
            IAEItemStack key = key(msg.item); if (key == null) return;
            inv.setItemThreshold(key, msg.value);
        } else if (msg.type == 17) {
            IAEItemStack key = key(msg.item); if (key == null) return;
            try {
                // 2026-10-03: no clamp to Long.MAX here any more. The cell stores the quantity as a
                // BigInteger, so "元件内数量 = 2^128" is a legitimate thing to ask for -- clamping it made the
                // box silently mean 9.22e18 instead. Only the parser's digit bound applies, and a refusal is
                // reported instead of substituted.
                BigInteger amount = QuantityExpression.parse(msg.quantityText);
                inv.setStoredQuantity(key, amount, msg.lock);
            }
            catch (NumberFormatException badQuantity) {
                player.sendMessage(new TextComponentString(
                        "§c[ae2addon] 数量算式无效：" + badQuantity.getMessage()));
            }
        } else if (msg.type == 19) {
            if (msg.isOre) { if (OreDictionary.doesOreNameExist(msg.text)) inv.addOreRule(msg.text); }
            else if (Loader.isModLoaded(msg.text)) inv.addModRule(msg.text);
        } else if (msg.type == 20) {
            if (msg.isOre) inv.removeOreRule(msg.text); else inv.removeModRule(msg.text);
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
        sendKeys(player, 21, stackList(inv.getRejectOnKeys()), stackList(inv.getRejectOffKeys()));
        sendKeys(player, 12, stackList(inv.getBlacklist()), new ArrayList<>());
        sendRules(player, inv, true);
        sendRules(player, inv, false);
        List<PanelEntry> all = new ArrayList<>();
        for (UnlimitedCellInventory.PanelItem row : inv.getPanelItems()) {
            all.add(new PanelEntry(row.key.createItemStack(), row.amount, row.isInfinite,
                    row.bytes, row.itemThreshold, row.quantityLocked, inv.isWhitelisted(row.key)));
        }
        int total = Math.max(1, (all.size() + MAX_ITEMS_PER_CHUNK - 1) / MAX_ITEMS_PER_CHUNK);
        for (int index = 0; index < total; index++) {
            int from = index * MAX_ITEMS_PER_CHUNK;
            int to = Math.min(all.size(), from + MAX_ITEMS_PER_CHUNK);
            CellNetwork.sendTo(panel(index, total, new ArrayList<>(all.subList(from, to))), player);
        }
    }

    private static List<ItemStack> stackList(Set<IAEItemStack> source) {
        List<ItemStack> result = new ArrayList<>();
        for (IAEItemStack key : source) result.add(key.createItemStack());
        return result;
    }

    private static void sendKeys(EntityPlayerMP player, int type, List<ItemStack> first, List<ItemStack> second) {
        int length = Math.max(first.size(), second.size());
        int total = Math.max(1, (length + MAX_ITEMS_PER_CHUNK - 1) / MAX_ITEMS_PER_CHUNK);
        for (int index = 0; index < total; index++) {
            int from = index * MAX_ITEMS_PER_CHUNK;
            Mode2ConfigPacket packet = new Mode2ConfigPacket(type);
            packet.chunkIndex = index; packet.totalChunks = total;
            packet.keys = new ArrayList<>(first.subList(Math.min(from, first.size()),
                    Math.min(from + MAX_ITEMS_PER_CHUNK, first.size())));
            packet.on = packet.keys;
            packet.off = new ArrayList<>(second.subList(Math.min(from, second.size()),
                    Math.min(from + MAX_ITEMS_PER_CHUNK, second.size())));
            CellNetwork.sendTo(packet, player);
        }
    }

    private static void sendRules(EntityPlayerMP player, UnlimitedCellInventory inv, boolean ore) {
        Mode2ConfigPacket packet = new Mode2ConfigPacket(9);
        packet.isOre = ore;
        for (String name : ore ? inv.getOreRules() : inv.getModRules()) {
            packet.rules.add(name);
            packet.modes.add(inv.getRuleMode(UnlimitedCellInventory.ruleKey(ore, name)));
        }
        CellNetwork.sendTo(packet, player);
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
