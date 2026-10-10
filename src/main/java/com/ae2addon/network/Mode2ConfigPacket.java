package com.ae2addon.network;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Set;

import com.ae2addon.cell.CellChannel;
import com.ae2addon.cell.CellChannelRegistry;
import com.ae2addon.cell.CellInventories;
import com.ae2addon.cell.ChannelCellCore;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.gui.Mode2ConfigScreen;
import com.ae2addon.gui.ModeSelectScreen;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
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

/**
 * Mode2 wire envelope.
 *
 * <p>Types 0-24 are the original set (13 is reserved for JEI direct add). 25/26/27 belong to the batch-infinite
 * tab (2026-10-10): the client asks the server to search the whole REGISTRY (25), the server answers in chunks
 * that reuse the panel row layout (26), and the client asks for a bulk mark/unmark (27). Purely additive --
 * no existing type changed shape or meaning.
 */
public final class Mode2ConfigPacket implements IMessage {
    public static final int MAX_ITEMS_PER_CHUNK = 50;
    /**
     * Ceiling on one registry search, and therefore on one batch apply.
     *
     * <p>Bounded on purpose: an unbounded answer would mean a query like {@code ""} pushing every registered
     * key through the wire. 200 is four chunks and covers "every ingot of that mod" style work, and the
     * client's confirmation names the count it is about to touch, so the ceiling is never invisible.
     */
    public static final int MAX_SEARCH_ROWS = 200;
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
    /** Which storage channel this packet is about (panel rows and key-based actions). Empty = item channel. */
    public String channelId = "";
    /** A panel row's key, serialised by the owning channel -- the only way to name a fluid/gas/essentia key. */
    public NBTTagCompound keyNbt;
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
    /** Client asks to switch the config panel to another channel (type 24). */
    public static Mode2ConfigPacket panelChannel(String channelId) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(24);
        p.channelId = channelId == null ? "" : channelId;
        return p;
    }

    public static Mode2ConfigPacket quantity(ItemStack item, String amount, boolean locked) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(17, item);
        p.quantityText = amount; p.lock = locked; return p;
    }
    public static Mode2ConfigPacket panel(int index, int total, String channelId, List<PanelEntry> entries) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(4);
        p.chunkIndex = index; p.totalChunks = total; p.channelId = channelId; p.entries = entries;
        return p;
    }
    public static Mode2ConfigPacket rejectState(UnlimitedCellInventory inv) {
        Mode2ConfigPacket p = new Mode2ConfigPacket(18);
        p.state = new NBTTagCompound();
        p.state.setBoolean("default", inv.rejectDefault());
        p.state.setBoolean("ruleInstant", inv.isRuleInstant());
        NBTTagList rejectOn = new NBTTagList(), rejectOff = new NBTTagList();
        for (IAEStack<?> key : inv.getRejectOnKeys())
            rejectOn.appendTag(key.asItemStackRepresentation().writeToNBT(new NBTTagCompound()));
        for (IAEStack<?> key : inv.getRejectOffKeys())
            rejectOff.appendTag(key.asItemStackRepresentation().writeToNBT(new NBTTagCompound()));
        p.state.setTag("on", rejectOn);
        p.state.setTag("off", rejectOff);
        NBTTagList committed = new NBTTagList();
        for (UnlimitedCellInventory.PanelItem row : inv.getPanelItems()) {
            if (!row.isInfinite) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("key", row.key.asItemStackRepresentation().writeToNBT(new NBTTagCompound()));
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
            // Key actions also carry the key itself -- as its OWN channel's NBT plus the channel id. The client
            // has always filled both in (Mode2ConfigScreen#withChannel), but they were never written to the wire,
            // so the server had to rebuild the key from the item stack. On a fluid / gas / essentia / mana / flux
            // row that stack is not the channel's key type at all and the channel's normalise() threw
            // ClassCastException (found 2026-10-10 while adding the batch tab; javap showed every one of these
            // cases wrote itemStack alone). Empty string + empty compound mean "no key": the bare blacklist-row
            // request (type 11) and item-channel actions use that and fall back to the stack.
            case 1: case 5: case 7: case 11: case 13: case 22:
                buf.writeItemStack(item);
                writeKeyIdentity(buf);
                break;
            case 6: buf.writeVarInt(workMode); break;
            case 8: case 10: buf.writeBoolean(flag); break;
            case 16: buf.writeItemStack(item); buf.writeLong(value); writeKeyIdentity(buf); break;
            case 17:
                buf.writeItemStack(item); buf.writeString(quantityText); buf.writeBoolean(lock);
                writeKeyIdentity(buf);
                break;
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
            // 26 shares 4's row layout on purpose: the client draws a registry search result with exactly the
            // code it draws panel rows with (see PanelEntry).
            case 4: case 26:
                buf.writeVarInt(chunkIndex); buf.writeVarInt(totalChunks); buf.writeString(channelId);
                buf.writeVarInt(entries.size());
                for (PanelEntry entry : entries) {
                    buf.writeCompoundTag(entry.keyNbt == null ? new NBTTagCompound() : entry.keyNbt);
                    buf.writeString(entry.label == null ? "" : entry.label);
                    buf.writeItemStack(entry.item);
                    buf.writeByteArray(entry.amount.toByteArray());
                    buf.writeBoolean(entry.infinite);
                    buf.writeVarLong(entry.bytes);
                    buf.writeLong(entry.threshold);
                    buf.writeBoolean(entry.quantityLocked);
                    buf.writeBoolean(entry.whitelisted);
                    // Per ROW, not just per packet: with the "all channels" view one chunk set carries rows of
                    // several channels, and an action on a row has to name the row's own channel (sensei's
                    // "add an All option", 2026-10-04).
                    buf.writeString(entry.channelId == null ? "" : entry.channelId);
                }
                break;
            case 24: buf.writeString(channelId); break;
            // 25: "search the registry for this text"; 27: "mark/unmark everything that search finds". Both
            // name their channel, because a registry scan is always per channel.
            case 25: buf.writeString(text); buf.writeString(channelId); break;
            case 27: buf.writeString(text); buf.writeBoolean(flag); buf.writeString(channelId); break;
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
                case 1: case 5: case 7: case 11: case 13: case 22:
                    item = buf.readItemStack();
                    readKeyIdentity(buf);
                    break;
                case 6: workMode = buf.readVarInt(); break;
                case 8: case 10: flag = buf.readBoolean(); break;
                case 16: item = buf.readItemStack(); value = buf.readLong(); readKeyIdentity(buf); break;
                case 17:
                    item = buf.readItemStack(); quantityText = buf.readString(128); lock = buf.readBoolean();
                    readKeyIdentity(buf);
                    break;
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
                case 4: case 26:
                    chunkIndex = buf.readVarInt(); totalChunks = buf.readVarInt();
                    channelId = buf.readString(64);
                    int size = buf.readVarInt();
                    checkChunk(this);
                    if (size < 0 || size > MAX_ITEMS_PER_CHUNK) throw new IOException("Invalid panel chunk");
                    for (int i = 0; i < size; i++) {
                        NBTTagCompound keyTag = buf.readCompoundTag();
                        String label = buf.readString(256);
                        ItemStack stack = buf.readItemStack();
                        byte[] amount = buf.readByteArray(8192);
                        boolean infinite = buf.readBoolean();
                        long bytes = buf.readVarLong();
                        long threshold = buf.readLong();
                        boolean locked = buf.readBoolean();
                        boolean whitelisted = buf.readBoolean();
                        // The row's own channel (see the encoder): an action on a row names the row's channel,
                        // which is what makes the "all channels" view usable.
                        String rowChannel = buf.readString(64);
                        if (rowChannel.isEmpty()) rowChannel = channelId;
                        // A row is valid when it carries a key or at least an icon: non-item channels have no icon.
                        if (amount.length > 0 && (!stack.isEmpty() || keyTag != null)) {
                            entries.add(new PanelEntry(rowChannel, keyTag, label, stack,
                                    new BigInteger(amount), infinite, bytes, threshold, locked, whitelisted));
                        }
                    }
                    break;
                case 24: channelId = buf.readString(64); break;
                case 25: text = buf.readString(256); channelId = buf.readString(64); break;
                case 27: text = buf.readString(256); flag = buf.readBoolean(); channelId = buf.readString(64); break;
                case 3: case 23: break;
                default: throw new IOException("Unsupported mode2 packet type " + type);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Bad mode2 packet", ex);
        }
    }

    /**
     * One panel row.
     *
     * <p>{@code item} is the icon (only the item channel has a real one; other channels render {@code label}),
     * {@code keyNbt} is the key serialised BY ITS OWN CHANNEL so the client can send it back for an action, and
     * {@code channelId} says which channel the row belongs to -- the panel serves every channel the cell speaks.
     */
    public static final class PanelEntry {
        public final String channelId;
        public final NBTTagCompound keyNbt;
        public final String label;
        public final ItemStack item;
        public final BigInteger amount;
        public final boolean infinite;
        public final long bytes;
        public final boolean quantityLocked;
        public final boolean whitelisted;
        public final long threshold;

        public PanelEntry(String channelId, NBTTagCompound keyNbt, String label, ItemStack item,
                BigInteger amount, boolean infinite, long bytes, long threshold, boolean quantityLocked,
                boolean whitelisted) {
            this.channelId = channelId; this.keyNbt = keyNbt; this.label = label; this.item = item;
            this.amount = amount; this.infinite = infinite;
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
        // Which channel the player is looking at. The panel serves every channel the cell speaks; the settings
        // (mode / work mode / threshold / rules / reject) are shared, so only the storage rows change.
        String channelId = msg.channelId == null || msg.channelId.isEmpty()
                ? panelChannelOf(player) : msg.channelId;
        if (!ALL_CHANNELS.equals(channelId) && CellChannelRegistry.byId(channelId) == null) {
            channelId = CellChannelRegistry.ITEM;
        }
        if (msg.channelId != null && !msg.channelId.isEmpty()) {
            PANEL_CHANNEL.put(player.getUniqueID(), channelId);
        }
        ChannelCellCore<?> core = CellInventories.open(target.stack, null, channelId);
        if (core == null) core = inv;
        IAEStack<?> actionKey = resolveKey(msg, channelId);

        if (msg.type == 25) {
            // Batch-infinite tab, search half (2026-10-10): nothing on the cell changes, so this answers with
            // the search result and returns instead of falling through to the state broadcast below. In the "all"
            // view this now scans every channel the cell speaks (see searchChannelIds).
            sendSearchResult(player, target.stack, channelId, msg.text);
            return;
        } else if (msg.type == 27) {
            // Batch-infinite tab, action half: one bulk whitelist change per channel, one save each, one chat
            // line -- and then the refreshed result. It deliberately falls through to sendState, so the panel rows
            // agree with what just happened without the player having to refresh by hand.
            int changed = 0;
            StringBuilder perChannel = new StringBuilder();
            for (String id : searchChannelIds(channelId)) {
                ChannelCellCore<?> searchCore = CellInventories.open(target.stack, null, id);
                if (searchCore == null) {
                    continue;
                }
                int n = searchCore.applyInfiniteToSearch(msg.text, MAX_SEARCH_ROWS, msg.flag);
                changed += n;
                if (n > 0) {
                    if (perChannel.length() > 0) perChannel.append('、');
                    perChannel.append(id).append(' ').append(n);
                }
            }
            player.sendMessage(new TextComponentString("§b[ae2addon] "
                    + (msg.flag ? "批量标无限：" : "批量取消无限：") + changed + " 条已改动（按通道 "
                    + (perChannel.length() == 0 ? "无" : perChannel.toString()) + "；查询「"
                    + msg.text + "」，每通道单次上限 " + MAX_SEARCH_ROWS + " 条）"));
            sendSearchResult(player, target.stack, channelId, msg.text);
        } else if (msg.type == 0) inv.setThreshold(msg.value);
        else if (msg.type == 1 || msg.type == 13) {
            if (actionKey == null) return; core.addWhitelistOf(actionKey);
        } else if (msg.type == 2) {
            for (IAEStack<?> key : inv.getWl()) {
                if (!(key instanceof IAEItemStack)) continue;
                ItemStack stack = key.asItemStackRepresentation();
                if (stack.getItem().getRegistryName() != null
                        && stack.getItem().getRegistryName().toString().equals(msg.text)) inv.removeWhitelist((IAEItemStack) key);
            }
        } else if (msg.type == 5) {
            if (actionKey == null) return; core.togglePanelInfiniteOf(actionKey);
        } else if (msg.type == 6) {
            if (msg.workMode < 1 || msg.workMode > 3) return;
            inv.setWorkMode(msg.workMode);
        } else if (msg.type == 7) {
            if (actionKey == null) return; core.toggleRejectOf(actionKey);
        } else if (msg.type == 8) inv.setRejectDefault(msg.flag);
        else if (msg.type == 10) inv.setRuleInstant(msg.flag);
        else if (msg.type == 11) {
            if (actionKey == null) {
                sendKeys(player, 12, stackList(inv.getBlacklist()), new ArrayList<>());
                return;
            }
            core.toggleBlacklistOf(actionKey);
        } else if (msg.type == 14) inv.wipeAllData();
        else if (msg.type == 15) inv.setRuleMode(UnlimitedCellInventory.ruleKey(msg.isOre, msg.text), msg.ruleMode);
        else if (msg.type == 16) {
            // TEMPORARY DIAGNOSTIC (2026-10-10): sensei reports the single-item threshold "does not change".
            // This line tells us whether the packet arrives with a resolvable key, or is dropped right here.
            com.ae2addon.AE2Addon.LOG.info("[ae2addon][dbg] 单物品阈值收到 value={} channel={} key={}",
                    msg.value, channelId, actionKey == null ? "null（会被丢弃）" : String.valueOf(actionKey));
            if (actionKey == null) return;
            core.setItemThresholdOf(actionKey, msg.value);
        } else if (msg.type == 17) {
            if (actionKey == null) return;
            try {
                // 2026-10-03: no clamp to Long.MAX here any more. The cell stores the quantity as a
                // BigInteger, so "元件内数量 = 2^128" is a legitimate thing to ask for -- clamping it made the
                // box silently mean 9.22e18 instead. Only the parser's digit bound applies, and a refusal is
                // reported instead of substituted.
                BigInteger amount = QuantityExpression.parse(msg.quantityText);
                // 2026-10-10 (sensei): the box has two jobs and only ONE of them is bounded.
                //  - LOCKED = "从 1 至无限之间取一值" -- above the channel's infinite value it is meaningless
                //    (and since 2026-10-05 it would draw as "∞" anyway), so it is REFUSED with a message,
                //    never silently clamped (2026-10-03's rule).
                //  - UNLOCKED = 拓展 the cell's capacity -- deliberately unbounded, 2^128 stays legal; only
                //    what the cell REPORTS is topped off at infinite-1 (see ChannelCellCore.reportMap).
                long cap = core.reportedInfinite();
                if (msg.lock && amount.compareTo(BigInteger.valueOf(cap)) > 0) {
                    player.sendMessage(new TextComponentString("§c[ae2addon] 锁定量上限 = " + cap
                            + "（本通道的无限值），已拒绝：" + amount));
                } else if (!core.setStoredQuantityOf(actionKey, amount, msg.lock)) {
                    player.sendMessage(new TextComponentString("§c[ae2addon] 元件内数量写入失败：" + amount));
                }
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
            if (actionKey == null) return; core.clearRejectOf(actionKey);
        } else if (msg.type == 24) {
            // Channel switch. Nothing on the cell changes (the selection is per player), but the client needs
            // the NEW channel's rows -- which sendState below does. Leaving this type out of the chain made the
            // panel keep showing item rows no matter what the player switched to (sensei, 2026-10-04): the
            // fall-through at the bottom returned without answering at all.
        } else if (msg.type != 3 && msg.type != 23) return;
        sendState(player, inv);
    }

    /**
     * The key an action refers to.
     *
     * <p>The panel sends the key's own NBT plus its channel id, which is the only way to name a fluid / gas /
     * essentia / mana / FE key; an older client (or the item channel) sends an item stack instead, and that path
     * stays supported. Whatever comes back is normalised to size 1.
     */
    /** Writes the key's channel plus its own-channel NBT, for the packets that act on one key (2026-10-10). */
    private void writeKeyIdentity(PacketBuffer buf) {
        buf.writeString(channelId == null ? "" : channelId);
        buf.writeCompoundTag(keyNbt == null ? new NBTTagCompound() : keyNbt);
    }

    /** Reads what {@link #writeKeyIdentity} wrote. {@code readCompoundTag} throws, like the type-18 read. */
    private void readKeyIdentity(PacketBuffer buf) throws IOException {
        channelId = buf.readString(64);
        keyNbt = buf.readCompoundTag();
    }

    private static IAEStack<?> resolveKey(Mode2ConfigPacket msg, String channelId) {
        if (msg.keyNbt != null) {
            CellChannel<?> channel = CellChannelRegistry.byId(channelId);
            if (channel != null) {
                IAEStack<?> key = channel.readKeyUntyped(msg.keyNbt);
                if (key != null) {
                    key.setStackSize(1);
                    return key;
                }
            }
        }
        // Only the item channel may fall back to the item stack. For any other channel that stack is not a key
        // of that channel at all, and the channel's normalise() throws ClassCastException -- which is exactly
        // what happened when a fluid row was clicked before 2026-10-10. Returning null makes the handler drop
        // the action instead of blowing up mid-packet.
        return CellChannelRegistry.ITEM.equals(channelId) ? key(msg.item) : null;
    }

    /** Which channel each player has selected in the config panel (one short string per online player). */
    private static final Map<UUID, String> PANEL_CHANNEL = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The panel's "every channel at once" view (sensei, 2026-10-04). A sentinel rather than a real channel id:
     * it is only ever a SELECTION, never a key's channel -- each row still carries its own channel, which is
     * what actions name.
     */
    public static final String ALL_CHANNELS = "all";

    private static String panelChannelOf(EntityPlayerMP player) {
        String id = PANEL_CHANNEL.get(player.getUniqueID());
        if (ALL_CHANNELS.equals(id)) {
            return ALL_CHANNELS;
        }
        return id == null || CellChannelRegistry.byId(id) == null ? CellChannelRegistry.ITEM : id;
    }

    private static IAEItemStack key(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        IAEItemStack key = AEItemStack.fromItemStack(stack);
        if (key != null) key.setStackSize(1);
        return key;
    }

    public static void sendState(EntityPlayerMP player, UnlimitedCellInventory inv) {
        String channelId = panelChannelOf(player);
        Mode2ConfigPacket statePacket = rejectState(inv);
        if (statePacket.state != null) {
            statePacket.state.setString("ch", channelId);
        }
        CellNetwork.sendTo(statePacket, player);
        sendKeys(player, 21, stackList(inv.getRejectOnKeys()), stackList(inv.getRejectOffKeys()));
        sendKeys(player, 12, stackList(inv.getBlacklist()), new ArrayList<>());
        sendRules(player, inv, true);
        sendRules(player, inv, false);
        // The rows come from the SELECTED channel's view of the cell; every row carries its key (as that
        // channel's own NBT), a display label (only the item channel has an icon to show) and its own channel id.
        List<PanelEntry> all = panelRows(inv.getItemStack(), channelId);
        int total = Math.max(1, (all.size() + MAX_ITEMS_PER_CHUNK - 1) / MAX_ITEMS_PER_CHUNK);
        for (int index = 0; index < total; index++) {
            int from = index * MAX_ITEMS_PER_CHUNK;
            int to = Math.min(all.size(), from + MAX_ITEMS_PER_CHUNK);
            CellNetwork.sendTo(panel(index, total, channelId, new ArrayList<>(all.subList(from, to))), player);
        }
    }

    /**
     * The rows one panel view lists: the selected channel's, or -- for {@link #ALL_CHANNELS} -- every channel
     * the cell speaks, one group per channel.
     *
     * <p>Public so the in-game self-test can pin the assembly without a player: sensei asked for an "All" entry
     * in the channel selector (2026-10-04), and the thing that can silently go wrong is a row naming the wrong
     * channel (an action would then hit the cell's item view instead of the row's own channel).
     */
    public static List<PanelEntry> panelRows(ItemStack cell, String channelId) {
        List<PanelEntry> rows = new ArrayList<>();
        if (ALL_CHANNELS.equals(channelId)) {
            for (CellChannel<?> channel : CellChannelRegistry.all()) {
                appendRows(rows, cell, channel.id());
            }
        } else {
            appendRows(rows, cell, channelId);
        }
        return rows;
    }

    private static void appendRows(List<PanelEntry> out, ItemStack cell, String channelId) {
        ChannelCellCore<?> core = CellInventories.open(cell, null, channelId);
        if (core == null) {
            return;
        }
        for (ChannelCellCore.PanelItem row : core.getPanelItems()) {
            NBTTagCompound keyTag = new NBTTagCompound();
            row.key.writeToNBT(keyTag);
            out.add(new PanelEntry(channelId, keyTag, core.displayNameOf(row.key),
                    row.key.asItemStackRepresentation(), row.amount, row.isInfinite,
                    row.bytes, row.itemThreshold, row.quantityLocked, core.isWhitelisted(row.key)));
        }
    }

    /**
     * The channels a registry search runs on.
     *
     * <p><b>Why this is a list</b> (sensei, 2026-10-10): in the "all" view the batch page searched the ITEM
     * channel only, so nothing but items ever came back -- a fluid could not be marked infinite as a fluid (the
     * thing that turned up instead was AE2's item-side placeholder for it), and every row claimed to be the item
     * channel, because that is the id the rows were built with. "All" is a view over every channel the cell
     * speaks, so the search walks them all, and each row keeps its own channel id and its own channel's key NBT.
     */
    private static List<String> searchChannelIds(String channelId) {
        if (!ALL_CHANNELS.equals(channelId)) {
            return java.util.Collections.singletonList(channelId);
        }
        List<String> ids = new ArrayList<>();
        for (CellChannel<?> channel : CellChannelRegistry.all()) {
            ids.add(channel.id());
        }
        if (ids.isEmpty()) {
            ids.add(CellChannelRegistry.ITEM);
        }
        return ids;
    }

    /**
     * Answers one registry search (type 25) as chunks of type 26.
     *
     * <p>Rows carry the SAME fields a panel row carries (see {@link #appendRows} and
     * {@code ChannelCellCore#panelItem}), so the client draws, scrolls and hovers a search result with exactly
     * the code it already uses -- plus the key's own NBT, which is what an action on the row names.
     *
     * <p>Every channel's hits go into ONE chunk set (the packet's own {@code channelId} is just the request's view;
     * each ROW names its channel): the client tracks chunks by index, so two interleaved sets would collide.
     *
     * <p>At least one chunk is sent even for an empty result: the client treats "chunks pending" as an
     * incomplete transfer and would otherwise sit there until its retry budget ran out.
     */
    private static void sendSearchResult(EntityPlayerMP player, ItemStack cell, String channelId, String query) {
        List<PanelEntry> rows = new ArrayList<>();
        for (String id : searchChannelIds(channelId)) {
            if (rows.size() >= MAX_SEARCH_ROWS) {
                break;
            }
            ChannelCellCore<?> core = CellInventories.open(cell, null, id);
            if (core == null) {
                continue;
            }
            rows.addAll(searchRows(core, id, query, MAX_SEARCH_ROWS - rows.size()));
        }
        int total = Math.max(1, (rows.size() + MAX_ITEMS_PER_CHUNK - 1) / MAX_ITEMS_PER_CHUNK);
        for (int index = 0; index < total; index++) {
            int from = index * MAX_ITEMS_PER_CHUNK;
            int to = Math.min(rows.size(), from + MAX_ITEMS_PER_CHUNK);
            Mode2ConfigPacket packet = new Mode2ConfigPacket(26);
            packet.chunkIndex = index;
            packet.totalChunks = total;
            packet.channelId = channelId;
            packet.entries = new ArrayList<>(rows.subList(from, to));
            CellNetwork.sendTo(packet, player);
        }
    }

    /** The rows of ONE channel's registry search, capped at {@code limit}. */
    private static List<PanelEntry> searchRows(ChannelCellCore<?> core, String channelId, String query, int limit) {
        List<IAEStack<?>> keys = core.searchRegistry(query, limit);
        // These three accessors COPY their collection, so they are read once for the whole batch rather than
        // once per row (200 rows x a copy of a 200-entry whitelist is pure waste).
        Set<IAEStack<?>> infinite = core.getUl();
        Set<IAEStack<?>> locked = core.quantityLockedKeys();
        Map<IAEStack<?>, Long> thresholds = core.itemThresholds();
        List<PanelEntry> rows = new ArrayList<>();
        for (IAEStack<?> key : keys) {
            NBTTagCompound keyTag = new NBTTagCompound();
            key.writeToNBT(keyTag);
            boolean whitelisted = core.isWhitelisted(key);
            // The spec's definition, like the panel's: infinite means "in the whitelist or in the infinite set".
            boolean isInfinite = whitelisted || infinite.contains(key);
            // Same derivations the panel uses: an infinite row reports the infinite value and the infinite byte
            // figure, a finite row its stored amount and zero bytes; threshold stays the per-key override.
            Long override = thresholds.get(key);
            rows.add(new PanelEntry(channelId, keyTag, core.displayNameOf(key), key.asItemStackRepresentation(),
                    isInfinite ? BigInteger.valueOf(ChannelCellCore.INFINITE) : core.storedAmountOf(key),
                    isInfinite, isInfinite ? ChannelCellCore.INFINITE_BYTES : 0L,
                    override == null ? 0L : override, locked.contains(key), whitelisted));
        }
        return rows;
    }

    private static List<ItemStack> stackList(Set<IAEStack<?>> source) {
        List<ItemStack> result = new ArrayList<>();
        for (IAEStack<?> key : source) result.add(key.asItemStackRepresentation());
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
