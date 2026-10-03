package com.ae2addon.command;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.item.ItemUnlimitedCell;

import appeng.api.AEApi;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

/**
 * P2's minimal settings entry: {@code /ae2addoncell ...} acting on the cell in the player's main hand.
 *
 * <p>P3 replaces this with the real GUI; it exists now because modes / thresholds / whitelists have to
 * be settable in game or P2 cannot be accepted at all.
 */
public class CommandCell extends CommandBase {

    private static final String USAGE = "/ae2addoncell <mode|wm|thr|wl|info|cancel|reject|rule|ore> ...";

    @Override
    public String getName() {
        return "ae2addoncell";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
            @Nullable net.minecraft.util.math.BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "mode", "wm", "thr", "wl", "info", "diag", "cancel", "reject", "rule", "ore");
        }
        if (args.length == 2 && "rule".equals(args[0])) {
            return getListOfStringsMatchingLastWord(args, "add", "del");
        }
        if (args.length == 3 && "rule".equals(args[0])) {
            return getListOfStringsMatchingLastWord(args, "ore", "mod");
        }
        if (args.length == 2 && "mode".equals(args[0])) {
            return getListOfStringsMatchingLastWord(args, "1", "2", "3");
        }
        if (args.length == 2 && "wm".equals(args[0])) {
            return getListOfStringsMatchingLastWord(args, "1", "2", "3");
        }
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (args.length < 1) {
            throw new CommandException(USAGE);
        }

        // Handled before heldCell(): this one asks about whatever item you are holding, not about the cell.
        if ("ore".equalsIgnoreCase(args[0])) {
            reportHeldItemOreNames(sender, player);
            return;
        }

        UnlimitedCellInventory inv = heldCell(player);

        switch (args[0].toLowerCase()) {
            case "mode": {
                int m = argInt(args, 1);
                inv.setMode(m);
                reply(sender, "mode = " + m + "（" + UnlimitedCellInventory.modeName(inv.getMode()) + "）");
                break;
            }
            case "wm": {
                int wm = argInt(args, 1);
                inv.setWorkMode(wm);
                reply(sender, "workMode = " + wm + "（" + UnlimitedCellInventory.workModeName(inv.getWorkMode()) + "）");
                break;
            }
            case "thr": {
                if (args.length == 2) {
                    long t = parseLong(args[1]);
                    inv.setThreshold(t);
                    reply(sender, "全局阈值 = " + inv.getThreshold());
                } else if (args.length >= 3) {
                    IAEItemStack key = itemKey(args[1]);
                    long t = parseLong(args[2]);
                    inv.setItemThreshold(key, t);
                    reply(sender, args[1] + " 的单物品阈值 = " + (t <= 0 ? "已清除（回落全局）" : t));
                } else {
                    throw new CommandException("用法: /ae2addoncell thr <阈值>  或  /ae2addoncell thr <物品> <阈值>");
                }
                break;
            }
            case "wl": {
                if (args.length < 3) {
                    throw new CommandException("用法: /ae2addoncell wl <add|remove> <物品>");
                }
                IAEItemStack key = itemKey(args[2]);
                boolean changed = "add".equalsIgnoreCase(args[1]) ? inv.addWhitelist(key) : inv.removeWhitelist(key);
                reply(sender, "白名单 " + args[1] + " " + args[2] + (changed ? " 已生效" : "（原本就是该状态）"));
                break;
            }
            case "cancel": {
                if (args.length < 2) {
                    throw new CommandException("用法: /ae2addoncell cancel <物品>");
                }
                IAEItemStack key = itemKey(args[1]);
                boolean ok = inv.cancelInfinite(key);
                if (!ok) {
                    throw new CommandException(args[1] + " 当前不是无限，无需取消");
                }
                reply(sender, "已取消 " + args[1] + " 的无限，承诺额度已回到普通存储");
                break;
            }
            case "reject": {
                if (args.length < 2) {
                    throw new CommandException(
                            "用法: /ae2addoncell reject <toggle|clear> <物品>  或  reject default");
                }
                if ("default".equalsIgnoreCase(args[1])) {
                    boolean now = inv.toggleRejectDefault();
                    reply(sender, "全局默认拒收 = " + now + "（未显式列出的物品跟随它）");
                    break;
                }
                if (args.length < 3) {
                    throw new CommandException("用法: /ae2addoncell reject <toggle|clear> <物品>");
                }
                IAEItemStack key = itemKey(args[2]);
                if ("clear".equalsIgnoreCase(args[1])) {
                    boolean changed = inv.clearReject(key);
                    reply(sender, args[2] + (changed ? " 的拒收设置已清除（跟随默认）" : " 本来就没有显式设置"));
                } else {
                    boolean nowRejecting = inv.toggleReject(key);
                    reply(sender, args[2] + (nowRejecting ? " 现在被拒收" : " 现在显式不拒收"));
                }
                break;
            }
            case "rule": {
                // Command-side counterpart of the GUI's rule bar, and the target of the clickable names that
                // /ae2addoncell ore prints. Without it those names would have to be retyped by hand.
                if (args.length < 4) {
                    throw new CommandException("/ae2addoncell rule <add|del> <ore|mod> <名字>");
                }
                String op = args[1].toLowerCase();
                String kind = args[2].toLowerCase();
                String name = args[3];
                boolean ore = "ore".equals(kind);
                if (!ore && !"mod".equals(kind)) {
                    throw new CommandException("第二项只能是 ore 或 mod");
                }
                if ("add".equals(op)) {
                    if (ore && !net.minecraftforge.oredict.OreDictionary.doesOreNameExist(name)) {
                        reply(sender, "§e警告: 矿辞 " + name + " 当前没有注册过，这条规则不会命中任何物品");
                    }
                    boolean added = ore ? inv.addOreRule(name) : inv.addModRule(name);
                    reply(sender, (added ? "已添加" : "已存在，未重复添加") + (ore ? "矿辞" : "模组")
                            + "规则: " + name);
                } else if ("del".equals(op)) {
                    boolean removed = ore ? inv.removeOreRule(name) : inv.removeModRule(name);
                    reply(sender, (removed ? "已删除" : "没有这条规则") + (ore ? "矿辞" : "模组")
                            + "规则: " + name);
                } else {
                    throw new CommandException("第一项只能是 add 或 del");
                }
                break;
            }
            case "info": {
                reply(sender, inv.describeSettings());
                break;
            }
            case "diag": {
                reportDiagnostics(sender, player);
                break;
            }
            default:
                throw new CommandException(USAGE);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Prints what the cell REALLY holds, from both sides of the fence, because "the cell looks empty" can mean
     * three different things (sensei BUG1/BUG2, 2026-10-03):
     * <ul>
     * <li>the cell item NBT (what the tooltip and the drive slot show -- summary bytes/types, mode, uuid),</li>
     * <li>the world-saved record keyed by that uuid (the authoritative counts, per channel),</li>
     * <li>and, per registered channel, what a channel view would list and whether AE2's registry answers at all.</li>
     * </ul>
     * A mismatch between the first two is exactly the "0 bytes until you open the panel" symptom, and a uuid in
     * the NBT that no longer matches the record is how data gets orphaned.
     */
    private static void reportDiagnostics(ICommandSender sender, EntityPlayerMP player) throws CommandException {
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !(held.getItem() instanceof ItemUnlimitedCell)) {
            throw new CommandException("请手持无限存储元件再执行 diag");
        }
        reply(sender, "§e===== 元件诊断 =====");
        reply(sender, "§7物品 NBT（tooltip/驱动器的数字来源）:");
        if (held.getTagCompound() == null) {
            reply(sender, "  §c整个物品没有 NBT（= 从没被写过的元件）");
        } else {
            net.minecraft.nbt.NBTTagCompound itemTag = held.getTagCompound();
            if (itemTag.hasKey("ae2addonCell", 10)) {
                reply(sender, "  " + itemTag.getCompoundTag("ae2addonCell").toString());
            } else {
                reply(sender, "  §c没有 ae2addonCell 标签，实际标签: " + itemTag.getKeySet());
            }
        }
        UnlimitedCellInventory.Summary summary = UnlimitedCellInventory.summaryOf(held);
        reply(sender, String.format("§7NBT 摘要: mode=%d wm=%d thr=%d 类型=%d 字节=%s",
                summary.mode, summary.workMode, summary.threshold, summary.types, summary.bytes));
        reply(sender, "§7共享存档记录（服务端权威）: " + heldCell(player).describeSettings());
        reply(sender, "§7逐通道（handler / 存量 / 类型 / mode3 应列条数 / 该通道键数）:");
        for (com.ae2addon.cell.CellChannel<?> channel : com.ae2addon.cell.CellChannelRegistry.all()) {
            com.ae2addon.cell.ChannelCellCore<?> core = com.ae2addon.cell.CellInventories.open(held, null, channel.id());
            boolean handled = false;
            try {
                @SuppressWarnings("rawtypes")
                appeng.api.storage.ICellInventoryHandler h = AEApi.instance().registries().cell()
                        .getCellInventory(held, null, (appeng.api.storage.IStorageChannel) channel.storage());
                handled = h != null && h.getCellInv() != null;
            } catch (RuntimeException | LinkageError failed) {
                handled = false;
            }
            if (core == null) {
                reply(sender, "  §c" + channel.id() + ": 打不开这个通道视图");
                continue;
            }
            reply(sender, String.format("  %s: handler=%s 存量=%d 类型=%d mode3应列=%d 键数=%d",
                    channel.id(), handled ? "ok" : "§cNULL", core.getStoredItemCount(), core.getStoredItemTypes(),
                    core.listedRowCount(), channel.allKeys().size()));
        }
        reply(sender, "§7提示：NBT 摘要与共享记录不一致 → 就是 BUG2（打开面板会把 NBT 重写一遍）");
    }

    private static UnlimitedCellInventory heldCell(EntityPlayerMP player) throws CommandException {
        ItemStack held = player.getHeldItemMainhand();
        if (held.isEmpty() || !(held.getItem() instanceof ItemUnlimitedCell)) {
            throw new CommandException("请手持无限存储元件");
        }
        ICellInventoryHandler<IAEItemStack> handler = AEApi.instance().registries().cell()
                .getCellInventory(held, null, UnlimitedCellInventory.itemChannel());
        if (handler == null || !(handler.getCellInv() instanceof UnlimitedCellInventory)) {
            throw new CommandException("AE2 没认出这个元件（注册链路异常）");
        }
        return (UnlimitedCellInventory) handler.getCellInv();
    }

    private static IAEItemStack itemKey(String name) throws CommandException {
        Item item = Item.getByNameOrId(name);
        if (item == null) {
            throw new CommandException("找不到物品: " + name + "（用 modid:name 形式，如 minecraft:stone）");
        }
        IAEItemStack key = AEItemStack.fromItemStack(new ItemStack(item));
        if (key == null) {
            throw new CommandException("无法构造该物品的 AE 表示: " + name);
        }
        key.setStackSize(1);
        return key;
    }

    private static int argInt(String[] args, int index) throws CommandException {
        if (args.length <= index) {
            throw new CommandException("缺少参数: " + USAGE);
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ex) {
            throw new CommandException("不是整数: " + args[index]);
        }
    }

    // NOTE: parseLong(String) is inherited from CommandBase (public static, throws
    // NumberInvalidException which is a CommandException) -- do not shadow it with a private method,
    // javac rejects the weaker access.

    /**
     * {@code /ae2addoncell ore} -- prints the registry name, mod id and every OreDictionary name the held
     * item is actually registered under.
     *
     * <p>Exists because an ore rule has to be typed as an exact ore name, and there is no way to see those
     * from outside the running game: scanning jars only proves a *string* exists somewhere, not that the
     * OreDictionary ever got it. This asks the game itself.
     */
    private void reportHeldItemOreNames(ICommandSender sender, EntityPlayerMP player) throws CommandException {
        ItemStack held = player.getHeldItemMainhand();
        if (held == null || held.isEmpty()) {
            throw new CommandException("手上没有物品：拿一个想查的物品再敲一次");
        }
        net.minecraft.util.ResourceLocation id = held.getItem().getRegistryName();
        String namespace = id == null ? null : id.getNamespace();
        reply(sender, "物品: " + held.getDisplayName());
        reply(sender, "注册名: " + (id == null ? "?" : id.toString()));

        // The point of this command is to get a working rule into the cell. 1.12.2 chat has no
        // "copy to clipboard" click action, so instead of making the name selectable we make it CLICKABLE:
        // one click runs the command that adds that very rule to the cell in your hand. No retyping.
        sender.sendMessage(label("模组 id: "));
        sender.sendMessage(clickable(namespace == null ? "?" : namespace,
                "/ae2addoncell rule add mod " + namespace,
                "点击把模组规则加到手上的元件: " + namespace));

        int[] oreIds = net.minecraftforge.oredict.OreDictionary.getOreIDs(held);
        if (oreIds == null || oreIds.length == 0) {
            reply(sender, "矿词: 无（该物品没有注册任何矿词）");
            return;
        }
        sender.sendMessage(label("矿词（点一个直接加规则）:"));
        for (int oreId : oreIds) {
            String ore = net.minecraftforge.oredict.OreDictionary.getOreName(oreId);
            if (ore == null) {
                continue;
            }
            sender.sendMessage(clickable(ore, "/ae2addoncell rule add ore " + ore,
                    "点击把矿辞规则加到手上的元件: " + ore));
        }
    }

    private static TextComponentString label(String text) {
        return new TextComponentString("§7[ae2addon] " + text);
    }

    /** A chat line that looks like a button: running {@code command} on click, {@code hint} on hover. */
    private static TextComponentString clickable(String text, String command, String hint) {
        TextComponentString line = new TextComponentString("  ");
        line.appendSibling(new TextComponentString("§b§n" + text + "§r")
                .setStyle(new net.minecraft.util.text.Style()
                        .setClickEvent(new net.minecraft.util.text.event.ClickEvent(
                                net.minecraft.util.text.event.ClickEvent.Action.RUN_COMMAND, command))
                        .setHoverEvent(new net.minecraft.util.text.event.HoverEvent(
                                net.minecraft.util.text.event.HoverEvent.Action.SHOW_TEXT,
                                new TextComponentString("§7" + hint)))));
        return line;
    }

    private static void reply(ICommandSender sender, String message) {
        sender.sendMessage(new TextComponentString("[ae2addon] " + message));
        AE2Addon.LOG.info("[ae2addon] /ae2addoncell {}", message);
    }
}
