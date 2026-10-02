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

    private static final String USAGE = "/ae2addoncell <mode|wm|thr|wl|info|cancel|reject|ore> ...";

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
            return getListOfStringsMatchingLastWord(args, "mode", "wm", "thr", "wl", "info", "cancel", "reject", "ore");
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
            case "info": {
                reply(sender, inv.describeSettings());
                break;
            }
            default:
                throw new CommandException(USAGE);
        }
    }

    // ------------------------------------------------------------------

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
        reply(sender, "物品: " + held.getDisplayName());
        reply(sender, "注册名: " + (id == null ? "?" : id.toString()));
        reply(sender, "模组 id: " + (id == null ? "?" : id.getNamespace()));
        int[] oreIds = net.minecraftforge.oredict.OreDictionary.getOreIDs(held);
        if (oreIds == null || oreIds.length == 0) {
            reply(sender, "矿词: 无（该物品没有注册任何矿词）");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (int oreId : oreIds) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(net.minecraftforge.oredict.OreDictionary.getOreName(oreId));
        }
        reply(sender, "矿词: " + names);
    }

    private static void reply(ICommandSender sender, String message) {
        sender.sendMessage(new TextComponentString("[ae2addon] " + message));
        AE2Addon.LOG.info("[ae2addon] /ae2addoncell {}", message);
    }
}
