package com.ae2addon.command;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.CellChannelRegistry;
import com.ae2addon.cell.CellInventories;
import com.ae2addon.cell.ChannelCellCore;
import com.ae2addon.cell.FluidCellChannel;
import com.ae2addon.cell.ItemCellChannel;
import com.ae2addon.cell.FluidCellChannel;
import com.ae2addon.cell.FluidCellInventory;
import com.ae2addon.cell.CellStorageData;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.data.CellWorldData;
import com.ae2addon.network.QuantityExpression;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.fluids.util.AEFluidStack;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEItemStack;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

/**
 * In-game acceptance test: {@code /ae2addoncelltest}
 *
 * <p>A group = P1 semantics (rv6 return values, identity, persistence, BigInteger above long).
 * B group = P2 semantics (three modes, thresholds, whitelist, commit/cancel, world saved data).
 *
 * <p>Every group works on a <b>fixed</b> UUID and wipes that entry before running, so repeated runs are
 * deterministic and do not pile up entries in the world save.
 */
public class CommandCellSelfTest extends CommandBase {

    private static final IActionSource NO_SOURCE = new IActionSource() {
        @Override
        public Optional<EntityPlayer> player() {
            return Optional.empty();
        }

        @Override
        public Optional<IActionHost> machine() {
            return Optional.empty();
        }

        @Override
        public <T> Optional<T> context(Class<T> clazz) {
            return Optional.empty();
        }
    };

    private int passed;
    private int failed;

    @Override
    public String getName() {
        return "ae2addoncelltest";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ae2addoncelltest";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        this.passed = 0;
        this.failed = 0;

        IStorageChannel<IAEItemStack> channel = UnlimitedCellInventory.itemChannel();
        sender.sendMessage(new TextComponentString("=== ae2addon 自检 (执行者 " + player.getName() + ") ==="));
        sender.sendMessage(new TextComponentString("[P1] 物品通道实现类 = " + channel.getClass().getName()));

        // ---- A0: registration chain ----
        ItemStack cell0 = cellWithUuid(testUuid("a0"));
        ICellInventoryHandler<IAEItemStack> handler = AEApi.instance().registries().cell()
                .getCellInventory(cell0, null, channel);
        if (handler == null) {
            throw new CommandException("注册失败：ICellRegistry 没认出我们的元件（handler == null）");
        }
        ICellInventory<IAEItemStack> inv = handler.getCellInv();
        if (inv == null) {
            throw new CommandException(
                    "ICellInventory 没接上：handler.getCellInv() == null —— 驱动器那一格 LED 会恒为异常");
        }
        check(sender, "A0", "注册链路通 + getCellInv() 可用 + status==1",
                inv.getStatusForCell() == 1, "1", String.valueOf(inv.getStatusForCell()));

        UnlimitedCellInventory a = newInventory("a");

        // ---- A1/A2: inject; SIMULATE must not mutate, MODULATE accepts all and returns null ----
        IAEItemStack a1 = a.injectItems(stack(Items.STICK, 1000L), Actionable.SIMULATE, NO_SOURCE);
        long a1stored = a.getStoredItemCount();
        check(sender, "A1", "SIMULATE 注入 1000 木棍 → null 且存量仍 0",
                a1 == null && a1stored == 0, "null/0", describe(a1) + "/" + a1stored);

        IAEItemStack a2 = a.injectItems(stack(Items.STICK, 1000L), Actionable.MODULATE, NO_SOURCE);
        long a2stored = a.getStoredItemCount();
        check(sender, "A2", "MODULATE 注入 1000 木棍 → null 且存量 1000",
                a2 == null && a2stored == 1000L, "null/1000", describe(a2) + "/" + a2stored);

        // ---- A3/A4: extract semantics ----
        IAEItemStack a3 = a.extractItems(stack(Items.STICK, 400L), Actionable.SIMULATE, NO_SOURCE);
        long a3stored = a.getStoredItemCount();
        check(sender, "A3", "SIMULATE 取 400 → 返回 400 且存量仍 1000",
                size(a3) == 400L && a3stored == 1000L, "400/1000", size(a3) + "/" + a3stored);

        IAEItemStack a4 = a.extractItems(stack(Items.STICK, 400L), Actionable.MODULATE, NO_SOURCE);
        long a4stored = a.getStoredItemCount();
        check(sender, "A4", "MODULATE 取 400 → 返回 400 且存量 600",
                size(a4) == 400L && a4stored == 600L, "400/600", size(a4) + "/" + a4stored);

        IAEItemStack a5 = a.extractItems(stack(Items.STICK, 10000000L), Actionable.MODULATE, NO_SOURCE);
        long a5stored = a.getStoredItemCount();
        long a5types = a.getStoredItemTypes();
        check(sender, "A5", "取 10^7（超过存量）→ 只给 600，存量归零且种类归零",
                size(a5) == 600L && a5stored == 0L && a5types == 0L, "600/0/0",
                size(a5) + "/" + a5stored + "/" + a5types);

        IAEItemStack a6 = a.extractItems(stack(Items.STICK, 1L), Actionable.MODULATE, NO_SOURCE);
        check(sender, "A6", "★ 空盘取 1 → null（不是 0）", a6 == null, "null", describe(a6));

        // ---- A7: getAvailableItems ----
        a.injectItems(stack(Items.DIAMOND, 5L), Actionable.MODULATE, NO_SOURCE);
        IItemList<IAEItemStack> available = a.getAvailableItems(channel.createList());
        boolean a7ok = available.size() == 1 && available.getFirstItem() != null
                && available.getFirstItem().getStackSize() == 5L
                && available.getFirstItem().getItem() == Items.DIAMOND;
        check(sender, "A7", "getAvailableItems → 恰好 1 条、数量 5、物品是钻石", a7ok,
                "size=1,count=5,diamond",
                "size=" + available.size() + ",count=" + (available.getFirstItem() == null ? "null"
                        : String.valueOf(available.getFirstItem().getStackSize())));

        // ---- A8: identity must include NBT ----
        UnlimitedCellInventory a8 = newInventory("a8");
        a8.injectItems(aeStack(paperWith("A"), 1L), Actionable.MODULATE, NO_SOURCE);
        a8.injectItems(aeStack(paperWith("B"), 1L), Actionable.MODULATE, NO_SOURCE);
        long a8types = a8.getStoredItemTypes();
        check(sender, "A8", "同物品不同 NBT 各存 1 个 → 种类 2（身份含 NBT）", a8types == 2L, "2",
                String.valueOf(a8types));

        // ---- A9: persistence round trip ----
        ItemStack cell9 = cellWithUuid(testUuid("a9"));
        UnlimitedCellInventory inv9 = new UnlimitedCellInventory(cell9, null);
        wipe(inv9);
        inv9.injectItems(stack(Items.STICK, 12345L), Actionable.MODULATE, NO_SOURCE);
        inv9.persist();
        UnlimitedCellInventory reloaded = new UnlimitedCellInventory(cell9, null);
        long a9stored = reloaded.getStoredItemCount();
        boolean a9nbt = cell9.getTagCompound() != null && cell9.getTagCompound().hasKey("ae2addon:cell", 10);
        check(sender, "A9", "存档往返：persist 后重新装载 → 12345 还在", a9stored == 12345L && a9nbt,
                "12345 + NBT 存在", a9stored + " + NBT=" + a9nbt);

        // ---- A10: beyond Long.MAX_VALUE ----
        UnlimitedCellInventory a10 = newInventory("a10");
        a10.injectItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE);
        a10.injectItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE);
        long a10stored = a10.getStoredItemCount();
        long a10e1 = size(a10.extractItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE));
        long a10e2 = size(a10.extractItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE));
        long a10left = a10.getStoredItemCount();
        check(sender, "A10", "★ 连存 2×Long.MAX → 对外夹到 Long.MAX（不为负），两次各取出 Long.MAX",
                a10stored == Long.MAX_VALUE && a10e1 == Long.MAX_VALUE && a10e2 == Long.MAX_VALUE
                        && a10left == 0L,
                Long.MAX_VALUE + "/" + Long.MAX_VALUE + "/" + Long.MAX_VALUE + "/0",
                a10stored + "/" + a10e1 + "/" + a10e2 + "/" + a10left);

        // ================= B group: P2 =================
        CellWorldData worldData = CellWorldData.get();
        check(sender, "B0", "世界存档可用（WorldSavedData）", worldData != null, "非 null",
                String.valueOf(worldData));
        if (worldData == null) {
            finish(sender);
            return;
        }

        // ---- B1: mode 1 keeps BigInteger behaviour ----
        UnlimitedCellInventory b1 = newInventory("b1");
        b1.setMode(1);
        b1.injectItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE);
        b1.injectItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE);
        long b1stored = b1.getStoredItemCount();
        long b1e1 = size(b1.extractItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE));
        long b1e2 = size(b1.extractItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE));
        check(sender, "B1", "mode1：连存 2×Long.MAX → 夹到 Long.MAX，两次各取出 Long.MAX",
                b1stored == Long.MAX_VALUE && b1e1 == Long.MAX_VALUE && b1e2 == Long.MAX_VALUE
                        && b1.getStoredItemCount() == 0L,
                "MAX/MAX/MAX/0", b1stored + "/" + b1e1 + "/" + b1e2 + "/" + b1.getStoredItemCount());

        // ---- B2/B3: mode 2 + wm 1 (threshold) ----
        UnlimitedCellInventory b2 = newInventory("b2");
        b2.setMode(2);
        b2.setThreshold(1000L);
        b2.setWorkMode(1);
        IAEItemStack stick = key(Items.STICK);
        b2.injectItems(stack(Items.STICK, 999L), Actionable.MODULATE, NO_SOURCE);
        boolean b2inf = b2.isInfinite(stick);
        // SIMULATE on purpose: a MODULATE extract here would drain the 999 and make B3 meaningless.
        long b2take = size(b2.extractItems(stack(Items.STICK, 1000L), Actionable.SIMULATE, NO_SOURCE));
        long b2kept = b2.storedAmount(stick).longValue();
        check(sender, "B2", "mode2/wm1 阈值 1000：存 999 → 未升级，取 1000 只能拿到 999（且没被取走）",
                !b2inf && b2take == 999L && b2kept == 999L, "false/999/999",
                b2inf + "/" + b2take + "/" + b2kept);

        b2.injectItems(stack(Items.STICK, 1L), Actionable.MODULATE, NO_SOURCE);
        boolean b3inf = b2.isInfinite(stick);
        long b3committed = b2.storedAmount(stick).longValue();
        boolean b3s2clear = b2.rawData().stocks(CellChannelRegistry.ITEM).s2.isEmpty();
        long b3take = size(b2.extractItems(stack(Items.STICK, Long.MAX_VALUE), Actionable.MODULATE, NO_SOURCE));
        check(sender, "B3", "累计到 1000 → 升级：ul 命中、s2 清空、承诺额度 1000，此后取 Long.MAX 返回 Long.MAX",
                b3inf && b3s2clear && b3committed == 1000L && b3take == Long.MAX_VALUE,
                "true/true/1000/" + Long.MAX_VALUE,
                b3inf + "/" + b3s2clear + "/" + b3committed + "/" + b3take);

        // ---- B4: mode 2 + wm 2 (store = infinite) ----
        UnlimitedCellInventory b4 = newInventory("b4");
        b4.setMode(2);
        b4.setWorkMode(2);
        b4.injectItems(stack(Items.DIAMOND, 1L), Actionable.MODULATE, NO_SOURCE);
        boolean b4inf = b4.isInfinite(key(Items.DIAMOND));
        long b4take = size(b4.extractItems(stack(Items.DIAMOND, 1000L), Actionable.MODULATE, NO_SOURCE));
        check(sender, "B4", "mode2/wm2 存入无限：存 1 个 → 立刻无限（取 1000 成功）",
                b4inf && b4take == 1000L, "true/1000", b4inf + "/" + b4take);

        // ---- B5: mode 2 + wm 3 (臻藏) ----
        UnlimitedCellInventory b5 = newInventory("b5");
        b5.setMode(2);
        b5.setWorkMode(3);
        b5.injectItems(stack(Items.DIAMOND, 5L), Actionable.MODULATE, NO_SOURCE);
        long b5take5 = size(b5.extractItems(stack(Items.DIAMOND, 5L), Actionable.MODULATE, NO_SOURCE));
        IAEItemStack b5extra = b5.extractItems(stack(Items.DIAMOND, 1L), Actionable.MODULATE, NO_SOURCE);
        b5.addWhitelist(key(Items.DIAMOND));
        long b5takeBig = size(b5.extractItems(stack(Items.DIAMOND, 1000L), Actionable.MODULATE, NO_SOURCE));
        check(sender, "B5", "mode2/wm3 臻藏：白名单外只能取 5，第 6 个取不到；加白名单后 → 无限",
                b5take5 == 5L && b5extra == null && b5takeBig == 1000L,
                "5/null/1000", b5take5 + "/" + describe(b5extra) + "/" + b5takeBig);

        // ---- B6: per-item threshold wins ----
        UnlimitedCellInventory b6 = newInventory("b6");
        b6.setMode(2);
        b6.setThreshold(1000L);
        b6.setWorkMode(1);
        b6.setItemThreshold(key(Items.DIAMOND), 5L);
        b6.injectItems(stack(Items.DIAMOND, 6L), Actionable.MODULATE, NO_SOURCE);
        boolean b6inf = b6.isInfinite(key(Items.DIAMOND));
        check(sender, "B6", "单物品阈值优先：全局 1000、该物品 5，存 6 → 升级", b6inf, "true",
                String.valueOf(b6inf));

        // ---- B7: mode 3 answers for items never stored ----
        UnlimitedCellInventory b7 = newInventory("b7");
        b7.setMode(3);
        long b7take = size(b7.extractItems(stack(Items.DIAMOND, 1000L), Actionable.MODULATE, NO_SOURCE));
        long b7before = b7.storedAmount(key(Items.DIAMOND)).longValue();
        check(sender, "B7", "mode3：从未存过的物品取 1000 → 成功", b7take == 1000L && b7before == 0L,
                "1000/0", b7take + "/" + b7before);

        // ---- B8: the three main modes are COMPLETELY ISOLATED stores (sensei's original spec, 2026-10-03) ----
        // Before this date setMode() merged s1 into s2 (and back), so mode 1 showed mode 2's pile -- the modes
        // behaved like one shared store. The merge is gone; this assertion pins both directions of isolation.
        UnlimitedCellInventory b8 = newInventory("b8");
        b8.setMode(1);
        b8.injectItems(stack(Items.STICK, 12345L), Actionable.MODULATE, NO_SOURCE);
        long b8m1 = b8.getStoredItemCount();
        b8.setMode(2);
        b8.setThreshold(1000000L);
        b8.setWorkMode(1);
        b8.injectItems(stack(Items.DIAMOND, 7L), Actionable.MODULATE, NO_SOURCE);
        long b8m2 = b8.getStoredItemCount();
        long b8m2stick = size(b8.extractItems(stack(Items.STICK, 1L), Actionable.MODULATE, NO_SOURCE));
        b8.setMode(1);
        long b8back = b8.getStoredItemCount();
        long b8backDiamond = size(b8.extractItems(stack(Items.DIAMOND, 1L), Actionable.MODULATE, NO_SOURCE));
        check(sender, "B8", "★ 三种主模式存储完全隔离：mode1 的 12345 在 mode2 看不到也取不出，mode2 的 7 个钻石也不回流 mode1，各自切回原样",
                b8m1 == 12345L && b8m2 == 7L && b8m2stick == -1L && b8back == 12345L && b8backDiamond == -1L,
                "12345/7/null/12345/null",
                b8m1 + "/" + b8m2 + "/" + b8m2stick + "/" + b8back + "/" + b8backDiamond);

        // ---- B9: cancel infinite gives the committed amount back ----
        UnlimitedCellInventory b9 = newInventory("b9");
        b9.setMode(2);
        b9.setThreshold(1000L);
        b9.setWorkMode(1);
        b9.injectItems(stack(Items.DIAMOND, 1000L), Actionable.MODULATE, NO_SOURCE);
        boolean b9wasInf = b9.isInfinite(key(Items.DIAMOND));
        boolean b9cancelled = b9.cancelInfinite(key(Items.DIAMOND));
        long b9after = b9.storedAmount(key(Items.DIAMOND)).longValue();
        check(sender, "B9", "★ 取消无限：承诺额度 1000 回到普通存储（不是凭空消失）",
                b9wasInf && b9cancelled && b9after == 1000L, "true/true/1000",
                b9wasInf + "/" + b9cancelled + "/" + b9after);

        // ---- B10: persistence of settings + data through world saved data ----
        ItemStack cell10 = cellWithUuid(testUuid("b10"));
        UnlimitedCellInventory inv10 = new UnlimitedCellInventory(cell10, null);
        wipe(inv10);
        inv10.setMode(2);
        inv10.setThreshold(4321L);
        inv10.setWorkMode(1);
        inv10.addWhitelist(key(Items.PAPER));
        inv10.injectItems(stack(Items.STICK, 777L), Actionable.MODULATE, NO_SOURCE);
        inv10.persist();

        UnlimitedCellInventory reload10 = new UnlimitedCellInventory(cell10, null);
        boolean b10ok = reload10.getMode() == 2 && reload10.getWorkMode() == 1
                && reload10.getThreshold() == 4321L
                && reload10.storedAmount(key(Items.STICK)).longValue() == 777L
                && reload10.isWhitelisted(key(Items.PAPER))
                && worldData.get(testUuid("b10")) != null;
        check(sender, "B10", "持久化往返：mode/wm/thr/白名单/数量全在，且世界存档里有该 uuid",
                b10ok, "2/1/4321/777/true/true",
                reload10.getMode() + "/" + reload10.getWorkMode() + "/" + reload10.getThreshold() + "/"
                        + reload10.storedAmount(key(Items.STICK)) + "/" + reload10.isWhitelisted(key(Items.PAPER))
                        + "/" + (worldData.get(testUuid("b10")) != null));

        // ---- B11: v1 (P1) data migrates instead of vanishing ----
        // Fixed uuid + wipe first, so reruns are deterministic and do not pile up world-save entries.
        UUID id11 = testUuid("b11");
        CellStorageData existing11 = worldData.get(id11);
        if (existing11 != null) {
            wipe(existing11);
        }
        ItemStack cell11 = new ItemStack(AE2Addon.UNLIMITED_CELL);
        NBTTagCompound root11 = new NBTTagCompound();
        root11.setInteger("v", 1);
        root11.setString("uuid", id11.toString());
        NBTTagList items11 = new NBTTagList();
        NBTTagCompound entry11 = new NBTTagCompound();
        NBTTagCompound keyTag11 = new NBTTagCompound();
        AEItemStack.fromItemStack(new ItemStack(Items.STICK)).writeToNBT(keyTag11);
        entry11.setTag("key", keyTag11);
        entry11.setString("count", "12345");
        items11.appendTag(entry11);
        root11.setTag("items", items11);
        NBTTagCompound stackTag11 = new NBTTagCompound();
        stackTag11.setTag("ae2addon:cell", root11);
        cell11.setTagCompound(stackTag11);

        UnlimitedCellInventory inv11 = new UnlimitedCellInventory(cell11, null);
        long b11stored = inv11.storedAmount(stick).longValue();
        int b11version = cell11.getTagCompound().getCompoundTag("ae2addon:cell").getInteger("v");
        check(sender, "B11", "★ v1(P1) 数据迁移：12345 进 mode1 存储，且版本升到当前格式版本",
                b11stored == 12345L && b11version == UnlimitedCellInventory.FORMAT_VERSION,
                "12345/" + UnlimitedCellInventory.FORMAT_VERSION, b11stored + "/" + b11version);

        // ---- B12: mode 2's committed amount must never cross into mode 1's isolated room ----
        // ca only exists because of mode 2 features (rules / promotion / 存入无限 / panel toggle), and the
        // command works in every mode: deleting a rule while the cell stands in mode 1 used to write the
        // refund into "the current mode's store", i.e. straight into mode 1's room. The refund belongs to s2.
        UnlimitedCellInventory b12 = newInventory("b12");
        b12.setMode(2);
        b12.setWorkMode(1);
        b12.addModRule("minecraft");
        b12.injectItems(stack(Items.STICK, 7L), Actionable.MODULATE, NO_SOURCE); // rule hit -> ca = 7
        b12.setMode(1);
        b12.removeModRule("minecraft");                                          // deleted from mode 1
        long b12m1 = b12.getStoredItemCount();
        b12.setMode(2);
        long b12m2 = b12.getStoredItemCount();
        check(sender, "B12", "★ 站在 mode1 里删规则：mode2 的 7 个退回 mode2 自己的房间，不越界倒进 mode1",
                b12m1 == 0L && b12m2 == 7L, "0/7", b12m1 + "/" + b12m2);

        runRejectGroup(sender);
        runRuleGroup(sender);
        runTerminalGroup(sender);
        runSummaryGroup(sender);
        runRuleVisibilityGroup(sender);
        runAllTypesCostGroup(sender);
        runListingIntegrityGroup(sender);
        runBigQuantityGroup(sender);
        runSaveFormatGroup(sender);
        runFluidChannelGroup(sender);
        runPanelChannelGroup(sender);
        runChannelCompletenessGroup(sender);
        runSharedSettingsGroup(sender);
        runDrivePathGroup(sender);
        runDriveBindingGroup(sender);
        runChannelSummaryGroup(sender);
        runPanelAllChannelsGroup(sender);
        runOptionalChannelGroups(sender);

        finish(sender);
    }

    /**
     * C group: the reject list, needed by P3's GUI. Three-state model ported from 1.20 (L762-799):
     * explicit reject &gt; explicit "not rejected" &gt; the cell's default.
     */
    private void runRejectGroup(ICommandSender sender) {
        ItemStack cellC = cellWithUuid(testUuid("reject"));
        UnlimitedCellInventory c = new UnlimitedCellInventory(cellC, null);
        wipe(c);
        IAEItemStack coal = key(Items.COAL);
        IAEItemStack stickK = key(Items.STICK);

        // C1: baseline -- nothing rejected by default
        IAEItemStack r1 = c.injectItems(stack(Items.COAL, 10L), Actionable.MODULATE, NO_SOURCE);
        long s1 = c.storedAmount(coal).longValue();
        check(sender, "C1", "拒收基线：默认不拒收，正常入库", r1 == null && s1 == 10L, "null/10",
                describe(r1) + "/" + s1);

        // C2: explicit reject -> input handed back untouched (NOT null)
        c.toggleReject(coal);
        IAEItemStack r2 = c.injectItems(stack(Items.COAL, 10L), Actionable.MODULATE, NO_SOURCE);
        long s2 = c.storedAmount(coal).longValue();
        check(sender, "C2", "★ 拒收：inject 原样返回 input（不是 null），且存量不变",
                r2 != null && r2.getStackSize() == 10L && s2 == 10L, "input(10)/10",
                describe(r2) + "/" + s2);

        // C3: the default applies to unlisted items, and SIMULATE must reject too
        c.setRejectDefault(true);
        IAEItemStack r3sim = c.injectItems(stack(Items.STICK, 5L), Actionable.SIMULATE, NO_SOURCE);
        IAEItemStack r3mod = c.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE);
        long s3 = c.storedAmount(stickK).longValue();
        check(sender, "C3", "全局默认拒收：未列出的物品 SIMULATE 与 MODULATE 都原样退回",
                r3sim != null && r3mod != null && s3 == 0L, "input/input/0",
                describe(r3sim) + "/" + describe(r3mod) + "/" + s3);

        // C4: an explicit "not rejected" entry overrides the default
        c.toggleReject(coal); // now in rejectOff
        IAEItemStack r4 = c.injectItems(stack(Items.COAL, 4L), Actionable.MODULATE, NO_SOURCE);
        long s4 = c.storedAmount(coal).longValue();
        check(sender, "C4", "显式不拒收覆盖全局默认：该物品仍能存入（10→14）", r4 == null && s4 == 14L,
                "null/14", describe(r4) + "/" + s4);

        // C5: reject state survives a persist/reload round trip
        c.persist();
        UnlimitedCellInventory reload = new UnlimitedCellInventory(cellC, null);
        boolean c5 = reload.rejectDefault() && !reload.isRejecting(coal) && reload.isRejecting(stickK);
        check(sender, "C5", "拒收状态持久化：默认拒收 + 煤显式不拒收 + 木棍跟随默认", c5,
                "true/false/true",
                reload.rejectDefault() + "/" + reload.isRejecting(coal) + "/" + reload.isRejecting(stickK));
    }

    private void finish(ICommandSender sender) {
        sender.sendMessage(new TextComponentString(
                String.format("=== 自检结果：PASS %d / FAIL %d（共 %d 项）===", passed, failed, passed + failed)));
        if (failed > 0) {
            sender.sendMessage(new TextComponentString("有 FAIL，请把上面整段复制给爱丽丝。"));
        }
        AE2Addon.LOG.info("[ae2addon] 自检结束: PASS {} FAIL {}", passed, failed);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static UUID testUuid(String tag) {
        return UUID.nameUUIDFromBytes(("ae2addon-selftest:" + tag).getBytes(StandardCharsets.UTF_8));
    }

    private static ItemStack cellWithUuid(UUID id) {
        ItemStack cell = new ItemStack(AE2Addon.UNLIMITED_CELL);
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("v", UnlimitedCellInventory.FORMAT_VERSION);
        root.setInteger("mode", 1);
        root.setInteger("wm", 1);
        root.setLong("thr", UnlimitedCellInventory.DEFAULT_THRESHOLD);
        root.setString("uuid", id.toString());
        NBTTagCompound stackTag = new NBTTagCompound();
        stackTag.setTag("ae2addon:cell", root);
        cell.setTagCompound(stackTag);
        return cell;
    }

    /** Fresh inventory on a fixed uuid with a wiped data entry, so reruns are deterministic. */
    private static UnlimitedCellInventory newInventory(String tag) {
        UnlimitedCellInventory inv = new UnlimitedCellInventory(cellWithUuid(testUuid(tag)), null);
        wipe(inv);
        return inv;
    }

    private static void wipe(UnlimitedCellInventory inv) {
        wipe(inv.rawData());
    }

    private static void wipe(CellStorageData data) {
        // Every channel's counts: "storage separate, settings shared" means a wipe has to walk them all.
        for (java.util.Map.Entry<String, CellStorageData.Stocks> entry : data.allStocks().entrySet()) {
            entry.getValue().s1.clear();
            entry.getValue().s2.clear();
            entry.getValue().ca.clear();
        }
        data.itemThr.clear();
        data.wl.clear();
        data.ul.clear();
        data.m3.clear();
        data.rejectOn.clear();
        data.rejectOff.clear();
        data.rejectDefault = false;
        data.oreRules.clear();
        data.modRules.clear();
        data.ruleModes.clear();
        data.touched.clear();
        data.blacklist.clear();
        data.qtyLocked.clear();
        data.ruleInstant = true;
    }

    /**
     * D group: batch rules (OreDictionary in 1.12.2), blacklist, quantity lock, panel enumeration,
     * infinite toggle, wipe, and their persistence.
     */
    private void runRuleGroup(ICommandSender sender) {
        IAEItemStack stick = key(Items.STICK);
        IAEItemStack diamond = key(Items.DIAMOND);

        // D1: mod rule in Instant mode -> infinite without ever storing it
        UnlimitedCellInventory d1 = newInventory("d1");
        d1.setMode(2);
        d1.setWorkMode(1);
        d1.setThreshold(1000000L);
        d1.addModRule("minecraft");
        boolean d1inf = d1.isInfiniteByRule(stick);
        long d1take = size(d1.extractItems(stack(Items.STICK, 10000000L), Actionable.MODULATE, NO_SOURCE));
        check(sender, "D1", "mod 规则 + Instant：没存过也能无限取 10^7",
                d1inf && d1take == 10000000L, "true/10000000", d1inf + "/" + d1take);

        // D2: same rule switched to Touch -> infinite only after it has been stored once
        d1.setRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft"), 2);
        boolean d2before = d1.isInfiniteByRule(stick);
        d1.injectItems(stack(Items.STICK, 1L), Actionable.MODULATE, NO_SOURCE);
        boolean d2after = d1.isInfiniteByRule(stick);
        check(sender, "D2", "同一规则改 Touch：没存过不无限，存过 1 个后才无限",
                !d2before && d2after, "false/true", d2before + "/" + d2after);

        // D3: NBT variants must never take the rule path (the 1.20 vanishing-item bug)
        UnlimitedCellInventory d3 = newInventory("d3");
        d3.setMode(2);
        d3.setWorkMode(1);
        d3.addModRule("minecraft");
        ItemStack taggedStack = new ItemStack(Items.STICK);
        NBTTagCompound taggedTag = new NBTTagCompound();
        taggedTag.setString("ae2addon_selftest", "tagged");
        taggedStack.setTagCompound(taggedTag);
        IAEItemStack taggedKey = aeStack(taggedStack, 1L);
        boolean d3rule = d3.isInfiniteByRule(taggedKey);
        d3.injectItems(aeStack(taggedStack, 7L), Actionable.MODULATE, NO_SOURCE);
        long d3stored = d3.storedAmount(taggedKey).longValue();
        check(sender, "D3", "带 NBT 的变体不进规则路径，7 个落在正常存储里",
                !d3rule && d3stored == 7L, "false/7", d3rule + "/" + d3stored);

        // D4: blacklist blocks the "store = infinite" promotion but not storage itself
        UnlimitedCellInventory d4 = newInventory("d4");
        d4.setMode(2);
        d4.setWorkMode(2);
        boolean d4bl = d4.toggleBlacklist(diamond);
        d4.injectItems(stack(Items.DIAMOND, 5L), Actionable.MODULATE, NO_SOURCE);
        long d4stored = d4.storedAmount(diamond).longValue();
        check(sender, "D4", "黑名单：拦住「存入即无限」，5 个落普通存储不升级",
                d4bl && d4.isBlacklisted(diamond) && !d4.isInfinite(diamond) && d4stored == 5L,
                "true/true/false/5",
                d4bl + "/" + d4.isBlacklisted(diamond) + "/" + d4.isInfinite(diamond) + "/" + d4stored);

        // D5: quantity lock swallows inserts and caps extracts without changing the record
        UnlimitedCellInventory d5 = newInventory("d5");
        d5.setMode(2);
        d5.setWorkMode(1);
        d5.setStoredQuantity(diamond, BigInteger.valueOf(500), true);
        d5.injectItems(stack(Items.DIAMOND, 100L), Actionable.MODULATE, NO_SOURCE);
        long d5stored = d5.storedAmount(diamond).longValue();
        long d5take = size(d5.extractItems(stack(Items.DIAMOND, 600L), Actionable.MODULATE, NO_SOURCE));
        long d5after = d5.storedAmount(diamond).longValue();
        check(sender, "D5", "数量锁定：存入被吞（仍 500）、取出限到 500 且记录不变",
                d5.isQuantityLocked(diamond) && d5stored == 500L && d5take == 500L && d5after == 500L,
                "true/500/500/500",
                d5.isQuantityLocked(diamond) + "/" + d5stored + "/" + d5take + "/" + d5after);

        // D6: panel infinite toggle, and cancelling must give the committed amount back
        UnlimitedCellInventory d6 = newInventory("d6");
        d6.setMode(2);
        d6.setWorkMode(1);
        d6.setThreshold(1000000L);
        d6.injectItems(stack(Items.DIAMOND, 300L), Actionable.MODULATE, NO_SOURCE);
        boolean d6on = d6.togglePanelInfinite(diamond);
        long d6ca = d6.getCommitedAmount(diamond).longValue();
        boolean d6s2clear = d6.rawData().stocks(CellChannelRegistry.ITEM).s2.isEmpty();
        boolean d6off = d6.togglePanelInfinite(diamond);
        long d6back = d6.storedAmount(diamond).longValue();
        check(sender, "D6", "面板切换无限：300 搬进承诺额度；再切换 300 回到普通存储（不丢数）",
                d6on && d6ca == 300L && d6s2clear && !d6off && d6back == 300L,
                "true/300/true/false/300",
                d6on + "/" + d6ca + "/" + d6s2clear + "/" + d6off + "/" + d6back);

        // D7: panel enumeration -- zero-count rows carrying an override must still appear
        UnlimitedCellInventory d7 = newInventory("d7");
        d7.setMode(2);
        d7.setWorkMode(1);
        d7.injectItems(stack(Items.DIAMOND, 5L), Actionable.MODULATE, NO_SOURCE);
        d7.setItemThreshold(stick, 123L);
        d7.addWhitelist(key(Items.PAPER));
        boolean d7zero = false;
        boolean d7paper = false;
        boolean d7diamond = false;
        List<UnlimitedCellInventory.PanelItem> rows = d7.getPanelItems();
        for (UnlimitedCellInventory.PanelItem row : rows) {
            if (((IAEItemStack) row.key).isSameType(stick) && row.amount.signum() == 0 && row.itemThreshold == 123L) {
                d7zero = true;
            }
            if (((IAEItemStack) row.key).isSameType(key(Items.PAPER)) && row.isInfinite) {
                d7paper = true;
            }
            if (((IAEItemStack) row.key).isSameType(diamond) && row.amount.longValue() == 5L && !row.isInfinite) {
                d7diamond = true;
            }
        }
        check(sender, "D7", "面板枚举：零数量但有阈值的条目在（阈值 123）、白名单条目算无限、钻石 5 不算无限",
                d7zero && d7paper && d7diamond, "true/true/true",
                d7zero + "/" + d7paper + "/" + d7diamond + " rows=" + rows.size());

        // D8: wipe clears everything but keeps mode 3's seen-set
        UnlimitedCellInventory d8 = newInventory("d8");
        d8.setMode(3);
        d8.injectItems(stack(Items.DIAMOND, 1L), Actionable.MODULATE, NO_SOURCE);
        d8.setMode(2);
        d8.setWorkMode(1);
        d8.setThreshold(4242L);
        d8.addModRule("minecraft");
        d8.addWhitelist(stick);
        d8.toggleBlacklist(key(Items.COAL));
        d8.wipeAllData();
        boolean d8ok = d8.getThreshold() == UnlimitedCellInventory.DEFAULT_THRESHOLD
                && d8.getWorkMode() == 1
                && d8.getModRules().isEmpty()
                && d8.getWl().isEmpty()
                && d8.getBlacklist().isEmpty()
                && d8.rawData().m3.size() == 1;
        check(sender, "D8", "一键格式化：阈值回 65536、wm 回 1、规则/白名单/黑名单全清、mode3 数据保留",
                d8ok, "true",
                d8.getThreshold() + "/" + d8.getWorkMode() + "/" + d8.getModRules().size() + "/"
                        + d8.getWl().size() + "/" + d8.getBlacklist().size() + "/" + d8.rawData().m3.size());

        // D9: rules / blacklist / lock survive a persist-reload round trip
        ItemStack cell9 = cellWithUuid(testUuid("d9"));
        UnlimitedCellInventory inv9 = new UnlimitedCellInventory(cell9, null);
        wipe(inv9);
        inv9.setMode(2);
        inv9.addModRule("minecraft");
        inv9.setRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft"), 2);
        inv9.toggleBlacklist(diamond);
        inv9.setStoredQuantity(stick, BigInteger.TEN, true);
        inv9.persist();
        UnlimitedCellInventory reload9 = new UnlimitedCellInventory(cell9, null);
        boolean d9ok = reload9.getModRules().contains("minecraft")
                && reload9.getRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft")) == 2
                && reload9.isBlacklisted(diamond)
                && reload9.isQuantityLocked(stick);
        check(sender, "D9", "持久化：规则（含 Touch 模式）/黑名单/数量锁定 重载后一致",
                d9ok, "true/true/true/true",
                reload9.getModRules().contains("minecraft") + "/"
                        + reload9.getRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft")) + "/"
                        + reload9.isBlacklisted(diamond) + "/" + reload9.isQuantityLocked(stick));

        // D10: out-of-range and unknown inputs must not throw
        UnlimitedCellInventory d10 = newInventory("d10");
        d10.setMode(2);
        d10.addModRule("minecraft");
        d10.setRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft"), 3);
        int mode10 = d10.getRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft"));
        d10.setRuleMode("bogus:x", 2);
        long fallback = d10.getCommitedAmount(key(Items.COAL)).longValue();
        check(sender, "D10", "越界规则模式按 1；未知规则不动作；没有承诺额度时回落该物品阈值",
                mode10 == 1 && fallback == UnlimitedCellInventory.DEFAULT_THRESHOLD,
                "1/" + UnlimitedCellInventory.DEFAULT_THRESHOLD, mode10 + "/" + fallback);
    }

    private static IAEItemStack stack(Item item, long count) {
        IAEItemStack stack = AEItemStack.fromItemStack(new ItemStack(item));
        stack.setStackSize(count);
        return stack;
    }

    private static IAEItemStack key(Item item) {
        return stack(item, 1L);
    }

    private static IAEItemStack aeStack(ItemStack definition, long count) {
        IAEItemStack stack = AEItemStack.fromItemStack(definition);
        stack.setStackSize(count);
        return stack;
    }

    private static ItemStack paperWith(String marker) {
        ItemStack stack = new ItemStack(Items.PAPER);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("ae2addon_selftest", marker);
        stack.setTagCompound(tag);
        return stack;
    }

    /**
     * E group: what the ME terminal is allowed to see. A cell that can hand an item out but does not list it
     * is invisible in the terminal even though extraction works -- exactly the bug sensei hit on 2026-10-02
     * with manually marked and JEI-dragged items, which the A-D groups never covered.
     */
    private void runTerminalGroup(ICommandSender sender) {
        final long infinite = UnlimitedCellInventory.INFINITE;

        // E1: whitelist -- what the panel's "mark infinite" and a JEI drag both produce
        UnlimitedCellInventory e1 = newInventory("e1");
        e1.setMode(2);
        e1.setWorkMode(1);
        e1.addWhitelist(key(Items.DIAMOND));
        long e1seen = listedSize(e1, key(Items.DIAMOND));
        check(sender, "E1", "白名单物品在终端列表里可见（无限）", e1seen == infinite,
                String.valueOf(infinite), String.valueOf(e1seen));

        // E2: the ul path (panel's infinite toggle)
        UnlimitedCellInventory e2 = newInventory("e2");
        e2.setMode(2);
        e2.setWorkMode(1);
        e2.injectItems(stack(Items.DIAMOND, 10L), Actionable.MODULATE, NO_SOURCE);
        e2.togglePanelInfinite(key(Items.DIAMOND));
        long e2seen = listedSize(e2, key(Items.DIAMOND));
        check(sender, "E2", "面板标记无限（ul）的物品在终端列表里可见", e2seen == infinite,
                String.valueOf(infinite), String.valueOf(e2seen));

        // E3: a rule hit writes touched + ca and never ul -- it must be listed through the RULE path (ca is
        // not an infinite claim on its own; see I5, where deleting the rule has to hand the amount back)
        UnlimitedCellInventory e3 = newInventory("e3");
        e3.setMode(2);
        e3.setWorkMode(1);
        e3.addModRule("minecraft");
        e3.setRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft"), 2);
        e3.injectItems(stack(Items.STICK, 3L), Actionable.MODULATE, NO_SOURCE); // Touch rule -> touched + ca
        long e3seen = listedSize(e3, key(Items.STICK));
        check(sender, "E3", "规则推进（只写 ca、不进 ul）的物品靠规则命中路径在终端列表里可见", e3seen == infinite,
                String.valueOf(infinite), String.valueOf(e3seen));

        // E4: two unlimited cells aggregating into one grid list must not overflow to a negative size
        IItemList<IAEItemStack> list = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        UnlimitedCellInventory e4a = newInventory("e4a");
        e4a.setMode(2);
        e4a.addWhitelist(key(Items.DIAMOND));
        e4a.getAvailableItems(list);
        UnlimitedCellInventory e4b = newInventory("e4b");
        e4b.setMode(2);
        e4b.addWhitelist(key(Items.DIAMOND));
        e4b.getAvailableItems(list);
        IAEItemStack found = list.findPrecise(key(Items.DIAMOND));
        long e4seen = found == null ? -1L : found.getStackSize();
        check(sender, "E4", "两片无限盘聚合到同一列表不溢出（仍是 INFINITE，不是负数）", e4seen == infinite,
                String.valueOf(infinite), String.valueOf(e4seen));

        // E5: the reported type count must agree with the reported list, or the tooltip contradicts the
        // terminal. Mixed cell: whitelist (wl) + plain storage (s2) + rule promotion (ca only).
        UnlimitedCellInventory e5 = newInventory("e5");
        e5.setMode(2);
        e5.setWorkMode(1);
        e5.addWhitelist(key(Items.DIAMOND));                                    // wl
        e5.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE); // s2
        e5.addModRule("minecraft");
        e5.setRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft"), 2);
        e5.injectItems(stack(Items.PAPER, 2L), Actionable.MODULATE, NO_SOURCE); // Touch rule -> ca
        IItemList<IAEItemStack> e5list = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        e5.getAvailableItems(e5list);
        check(sender, "E5", "mode2 的 getStoredItemTypes 与终端实际列出的条目数一致",
                e5.getStoredItemTypes() == e5list.size(),
                "list=" + e5list.size(), "types=" + e5.getStoredItemTypes() + " list=" + e5list.size());

        // E6: same consistency for mode 3 (which lists m3)
        UnlimitedCellInventory e6 = newInventory("e6");
        e6.setMode(3);
        e6.injectItems(stack(Items.DIAMOND, 1L), Actionable.MODULATE, NO_SOURCE);
        e6.injectItems(stack(Items.STICK, 1L), Actionable.MODULATE, NO_SOURCE);
        IItemList<IAEItemStack> e6list = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        e6.getAvailableItems(e6list);
        // NOTE: mode 3 now lists every registered item (1.20 parity, asked for by sensei), so the expected
        // count is "whatever the registry yields" rather than 2 -- what must hold is that the reported type
        // count agrees with the listing.
        check(sender, "E6", "mode3 的 getStoredItemTypes 与终端实际列出的条目数一致",
                e6.getStoredItemTypes() == e6list.size() && e6list.size() > 100,
                "types=list, list>100", "types=" + e6.getStoredItemTypes() + " list=" + e6list.size());
    }

    /**
     * F group: the item tooltip's summary counters. A tooltip that disagrees with what the cell actually
     * reports is worse than no tooltip, so F1 pins the counters to the terminal listing.
     */
    private void runSummaryGroup(ICommandSender sender) {
        ItemStack cellF1 = cellWithUuid(testUuid("f1"));
        UnlimitedCellInventory f1 = new UnlimitedCellInventory(cellF1, null);
        wipe(f1);
        f1.setMode(2);
        f1.setWorkMode(1);
        f1.addWhitelist(key(Items.DIAMOND));                                      // 1 infinite type
        f1.injectItems(stack(Items.STICK, 40L), Actionable.MODULATE, NO_SOURCE);  // 40 finite
        IItemList<IAEItemStack> f1list = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        f1.getAvailableItems(f1list);
        UnlimitedCellInventory.Summary f1sum = UnlimitedCellInventory.summaryOf(cellF1);
        long f1expectBytes = 40L + UnlimitedCellInventory.INFINITE_BYTES;
        check(sender, "F1", "tooltip 摘要（类型/字节）与终端实际列出的一致",
                f1sum.types == f1list.size() && f1sum.types == 2 && f1sum.bytes.longValue() == f1expectBytes,
                "types=2 bytes=" + f1expectBytes,
                "types=" + f1sum.types + " list=" + f1list.size() + " bytes=" + f1sum.bytes);

        ItemStack cellF2 = cellWithUuid(testUuid("f2"));
        UnlimitedCellInventory f2 = new UnlimitedCellInventory(cellF2, null);
        wipe(f2);
        f2.setMode(3);
        f2.setWorkMode(2);
        f2.setThreshold(1234L);
        UnlimitedCellInventory.Summary f2sum = UnlimitedCellInventory.summaryOf(cellF2);
        check(sender, "F2", "摘要能读出模式/工作模式/阈值（tooltip 那几行据此生成）",
                f2sum.mode == 3 && f2sum.workMode == 2 && f2sum.threshold == 1234L,
                "3/2/1234", f2sum.mode + "/" + f2sum.workMode + "/" + f2sum.threshold);
    }

    /**
     * G group: rule visibility. An Instant rule means "this item is infinite without ever being stored", so
     * the cell has to be able to name it up front -- otherwise Instant and Touch look identical in game,
     * which is exactly what sensei reported on 2026-10-02.
     */
    private void runRuleVisibilityGroup(ICommandSender sender) {
        final long infinite = UnlimitedCellInventory.INFINITE;
        String modRule = UnlimitedCellInventory.ruleKey(false, "minecraft");

        // G1: Instant rule -- an item that was NEVER stored is already listed as infinite
        UnlimitedCellInventory g1 = newInventory("g1");
        g1.setMode(2);
        g1.setWorkMode(1);
        g1.addModRule("minecraft");
        long g1listed = listedSize(g1, key(Items.DIAMOND));
        boolean g1rule = g1.isInfiniteByRule(key(Items.DIAMOND));
        check(sender, "G1", "「立即」规则：没存过的物品也已经在终端列表里（无限）",
                g1listed == infinite && g1rule, "infinite/true",
                "listed=" + g1listed + " rule=" + g1rule);

        // G2: same rule as Touch -- not listed before storing, listed after storing one
        g1.setRuleMode(modRule, 2);
        long g2before = listedSize(g1, key(Items.STICK));
        g1.injectItems(stack(Items.STICK, 1L), Actionable.MODULATE, NO_SOURCE);
        long g2after = listedSize(g1, key(Items.STICK));
        check(sender, "G2", "「触碰」规则：没存过不列出，存过 1 个后才列出",
                g2before == -1L && g2after == infinite, "-1/infinite",
                g2before + "/" + g2after);

        // G3: the panel (what the GUI shows) has to include Instant-rule matches too
        UnlimitedCellInventory g3 = newInventory("g3");
        g3.setMode(2);
        g3.setWorkMode(1);
        g3.addModRule("minecraft");
        boolean g3panel = false;
        for (UnlimitedCellInventory.PanelItem row : g3.getPanelItems()) {
            if (((IAEItemStack) row.key).isSameType(key(Items.PAPER)) && row.isInfinite) {
                g3panel = true;
            }
        }
        check(sender, "G3", "面板也列出「立即」规则命中的物品（否则界面里看不出立即/触碰的区别）",
                g3panel, "true", String.valueOf(g3panel));

        // G4: type count stays consistent with the list while Instant rules are in play
        IItemList<IAEItemStack> g4list = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        g3.getAvailableItems(g4list);
        check(sender, "G4", "有「立即」规则时 getStoredItemTypes 仍与终端列出条数一致",
                g3.getStoredItemTypes() == g4list.size(),
                "types=list", "types=" + g3.getStoredItemTypes() + " list=" + g4list.size());
    }

    /**
     * H group: what mode 3's "list every registered item" actually costs. sensei asked for the 1.20 behaviour
     * *if it is not laggy*, so the numbers are measured here rather than guessed: how many keys the registry
     * yields, that each is reported as infinite, and how long a warm listing takes -- a warm listing is what
     * every storage-grid rebuild pays.
     */
    private void runAllTypesCostGroup(ICommandSender sender) {
        UnlimitedCellInventory inv = newInventory("h");
        inv.setMode(3);

        IItemList<IAEItemStack> coldList = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        long coldStart = System.nanoTime();
        inv.getAvailableItems(coldList); // cold: includes the one-off registry scan
        long coldNanos = System.nanoTime() - coldStart;

        IItemList<IAEItemStack> warmList = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        long warmStart = System.nanoTime();
        inv.getAvailableItems(warmList);
        long warmNanos = System.nanoTime() - warmStart;

        boolean allInfinite = true;
        for (IAEItemStack stack : warmList) {
            if (stack.getStackSize() != UnlimitedCellInventory.INFINITE) {
                allInfinite = false;
                break;
            }
        }
        check(sender, "H1", "mode3 列出全部注册物品且都算无限",
                warmList.size() == inv.registryKeyCount() && warmList.size() > 100 && allInfinite,
                "=" + inv.registryKeyCount() + "（>100）且全为 INFINITE",
                "listed=" + warmList.size() + " registry=" + inv.registryKeyCount()
                        + " allInfinite=" + allInfinite);

        // H3: the registry scan must not emit an empty/air entry. minecraft:air is in the item registry but
        // new ItemStack(air) is EMPTY and AEItemStack.fromItemStack(empty) is null -- that null crashed the
        // server tick loop on 2026-10-02, so the guard is pinned by an assertion instead of a comment.
        boolean noEmpty = true;
        for (IAEItemStack stack : warmList) {
            if (stack == null || stack.getDefinition() == null || stack.getDefinition().isEmpty()) {
                noEmpty = false;
                break;
            }
        }
        check(sender, "H3", "注册表扫描不产出空物品（air 必须被跳过，否则 NPE 崩 tick 循环）",
                noEmpty, "true", String.valueOf(noEmpty));

        // The number itself is the answer to "会不会卡"; the assertion is just a headroom gate.
        sender.sendMessage(new TextComponentString(String.format(
                "[ae2addon] [H2] mode3 列出 %d 个键：冷 %.1f ms（含一次注册表扫描，只发生一次）/ "
                        + "热 %.2f ms（每次网格重建的代价）",
                warmList.size(), coldNanos / 1e6, warmNanos / 1e6)));
        check(sender, "H2", "mode3 热重建耗时 < 50 ms（超了就该退回按需列出）",
                warmNanos < 50_000_000L, "<50ms", String.format("%.2f ms", warmNanos / 1e6));
    }

    /**
     * I group: the three listing bugs sensei reported on 2026-10-03. They are one theme -- what the cell
     * CLAIMS to hold and what it can really hand out drifted apart:
     * <ul>
     * <li>I1/I2 multi-meta items: an Instant rule only ever covered meta 0 of a sequence, so a mod rule made
     *     the first entry of Mekanism CE's ingot sequence infinite and left the rest Touch-only.</li>
     * <li>I3/I4 one key reported twice into one list: {@code IItemList.add} SUMS, so a finite report landing
     *     on an {@code INFINITE} row wraps to a negative size and the row disappears from the terminal.</li>
     * <li>I5/I6 a deleted (or blacklisted) rule left an orphaned committed amount that was listed as infinite
     *     but could not be extracted.</li>
     * <li>I7 the invariant behind all of them: everything listed as infinite must actually come out.</li>
     * </ul>
     */
    private void runListingIntegrityGroup(ICommandSender sender) {
        final long infinite = UnlimitedCellInventory.INFINITE;
        IStorageChannel<IAEItemStack> channel = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class);
        String modRule = UnlimitedCellInventory.ruleKey(false, "minecraft");

        // I1: a mod rule has to cover every meta of one item, not just meta 0 -- the shape Mekanism CE's
        // ItemIngot has (one item, many metas). Vanilla dye is the same shape with 16 metas.
        UnlimitedCellInventory i1 = newInventory("i1");
        i1.setMode(2);
        i1.setWorkMode(1);
        i1.addModRule("minecraft");
        IItemList<IAEItemStack> i1list = channel.createList();
        i1.getAvailableItems(i1list);
        int dyeMetas = 0;
        for (IAEItemStack listed : i1list) {
            if (listed.getItem() == Items.DYE) {
                dyeMetas++;
            }
        }
        long i1meta15 = listedSize(i1, aeStack(new ItemStack(Items.DYE, 1, 15), 1L));
        check(sender, "I1", "★ 多 meta 物品：「立即」规则要覆盖全部 meta（染料 16 色都无限），不是只有 meta 0",
                dyeMetas >= 8 && i1meta15 == infinite, ">=8/infinite",
                "dyeMetas=" + dyeMetas + " meta15=" + i1meta15);

        // I2: coal (meta 0) and charcoal (meta 1) are one item; the rule must cover both
        IAEItemStack charcoal = aeStack(new ItemStack(Items.COAL, 1, 1), 1L);
        boolean i2rule = i1.isInfiniteByRule(charcoal);
        long i2listed = listedSize(i1, charcoal);
        check(sender, "I2", "煤/木炭（同 id 不同 meta）都在「立即」规则下无限且已列出",
                i2rule && i2listed == infinite, "true/infinite",
                "rule=" + i2rule + " listed=" + i2listed);

        // I3: one item matched by BOTH an ore rule and a mod rule (sensei's bug 1), plus the whole-list
        // invariant that no entry comes out non-positive -- that is what "the item vanished" looks like.
        Item probeItem = null;
        String probeOre = null;
        for (Item candidate : new Item[] { Items.STICK, Items.IRON_INGOT, Items.COAL, Items.DIAMOND }) {
            String ore = firstOreName(new ItemStack(candidate));
            if (ore != null) {
                probeItem = candidate;
                probeOre = ore;
                break;
            }
        }
        UnlimitedCellInventory i3 = newInventory("i3");
        i3.setMode(2);
        i3.setWorkMode(1);
        i3.addModRule("minecraft");
        if (probeOre != null) {
            i3.addOreRule(probeOre);
        }
        IItemList<IAEItemStack> i3list = channel.createList();
        i3.getAvailableItems(i3list);
        IAEItemStack probeKey = probeItem == null ? key(Items.STICK) : key(probeItem);
        int i3hits = 0;
        long i3size = -1L;
        int i3bad = 0;
        for (IAEItemStack listed : i3list) {
            if (listed.isSameType(probeKey)) {
                i3hits++;
                i3size = listed.getStackSize();
            }
            if (listed.getStackSize() <= 0) {
                i3bad++;
            }
        }
        check(sender, "I3", "★ 矿词+模组双重命中：该物品只出现一次、仍是无限，且列表里没有非正数条目",
                probeOre != null && i3hits == 1 && i3size == infinite && i3bad == 0,
                "ore!=null/1/infinite/0",
                "ore=" + probeOre + " hits=" + i3hits + " size=" + i3size + " bad=" + i3bad);

        // I4: the mechanism behind bug 1 -- two cells feeding ONE list, one of them claiming infinity by rule
        // while the other still holds the item as ordinary storage. A naive finite report SUMS onto the
        // INFINITE row, wraps negative, and the terminal drops the item.
        IItemList<IAEItemStack> i4list = channel.createList();
        UnlimitedCellInventory i4rule = newInventory("i4rule");
        i4rule.setMode(2);
        i4rule.setWorkMode(1);
        i4rule.addModRule("minecraft");
        i4rule.getAvailableItems(i4list);
        UnlimitedCellInventory i4plain = newInventory("i4plain");
        i4plain.setMode(2);
        i4plain.setWorkMode(1);
        i4plain.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE);
        i4plain.getAvailableItems(i4list);
        IAEItemStack i4found = i4list.findPrecise(key(Items.STICK));
        long i4seen = i4found == null ? -1L : i4found.getStackSize();
        check(sender, "I4", "★ 一片盘按规则无限 + 另一片盘里它仍是普通存储 → 合并列表还是无限（不能负成 0 以下）",
                i4seen == infinite, String.valueOf(infinite), String.valueOf(i4seen));

        // I5: bug 2 -- delete the rule and what it had absorbed must come back as ordinary storage
        UnlimitedCellInventory i5 = newInventory("i5");
        i5.setMode(2);
        i5.setWorkMode(1);
        i5.addModRule("minecraft");
        i5.injectItems(stack(Items.STICK, 7L), Actionable.MODULATE, NO_SOURCE); // rule hit -> touched + ca
        i5.removeModRule("minecraft");
        long i5listed = listedSize(i5, key(Items.STICK));
        long i5take = size(i5.extractItems(stack(Items.STICK, 100L), Actionable.MODULATE, NO_SOURCE));
        check(sender, "I5", "★ 删掉规则：原来被规则吸进去的 7 个回到普通存储（列表显示 7、能取出 7）",
                i5listed == 7L && i5take == 7L, "7/7", i5listed + "/" + i5take);

        // I6: same class -- blacklisting a rule-matched item must not strand its amount either
        UnlimitedCellInventory i6 = newInventory("i6");
        i6.setMode(2);
        i6.setWorkMode(1);
        i6.addModRule("minecraft");
        i6.injectItems(stack(Items.PAPER, 7L), Actionable.MODULATE, NO_SOURCE);
        i6.toggleBlacklist(key(Items.PAPER));
        long i6listed = listedSize(i6, key(Items.PAPER));
        long i6take = size(i6.extractItems(stack(Items.PAPER, 100L), Actionable.MODULATE, NO_SOURCE));
        check(sender, "I6", "★ 把规则命中的物品拉黑：7 个也不能被黑洞吞掉（列表 7、能取出 7）",
                i6listed == 7L && i6take == 7L, "7/7", i6listed + "/" + i6take);

        // I7: the invariant that covers all of the above -- every listed key must really come out, and the
        // reported type count must still match the listing (wl + ul + Touch rule + plain storage in one cell).
        UnlimitedCellInventory i7 = newInventory("i7");
        i7.setMode(2);
        i7.setWorkMode(1);
        i7.setThreshold(1000000L);
        i7.addWhitelist(key(Items.DIAMOND));                                    // wl
        i7.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE); // s2, finite
        i7.injectItems(stack(Items.EMERALD, 9L), Actionable.MODULATE, NO_SOURCE);
        i7.togglePanelInfinite(key(Items.EMERALD));                             // ul
        i7.addModRule("minecraft");
        i7.setRuleMode(modRule, 2);                                             // Touch
        i7.injectItems(stack(Items.PAPER, 3L), Actionable.MODULATE, NO_SOURCE); // rule hit
        IItemList<IAEItemStack> i7list = channel.createList();
        i7.getAvailableItems(i7list);
        int i7checked = 0;
        int i7broken = 0;
        for (IAEItemStack listed : i7list) {
            if (listed.getStackSize() <= 0) {
                i7broken++;
                i7checked++;
                continue;
            }
            IAEItemStack probe = listed.copy();
            probe.setStackSize(1L);
            long take = size(i7.extractItems(probe, Actionable.SIMULATE, NO_SOURCE));
            i7checked++;
            if (take <= 0) {
                i7broken++;
            }
        }
        check(sender, "I7", "★ 一致性总断言：列表里每一条都取得到（无限条目取得到、有限条目至少 1），且类型数与条数一致",
                i7checked >= 4 && i7broken == 0 && i7.getStoredItemTypes() == i7list.size(),
                "checked>=4/broken=0/types=list",
                "checked=" + i7checked + " broken=" + i7broken + " types=" + i7.getStoredItemTypes()
                        + " list=" + i7list.size());
    }

    /**
     * J group: the "元件内数量" box may hold numbers WIDER than a long.
     *
     * <p>The cell keeps quantities as {@code BigInteger}. On 2026-10-03 sensei typed {@code 2^128} into that
     * box and the parser silently rewrote it into 9.22e18, because the parser clamped anything wider than 63
     * bits. The clamp is gone -- the bound is now a digit count, and an over-limit input is refused with a
     * message instead of being substituted. These assertions pin both halves: the big value survives
     * verbatim, and the anti-DoS bound on {@code ^} still refuses absurd input.
     */
    private void runBigQuantityGroup(ICommandSender sender) {
        final BigInteger twoPow128 = BigInteger.ONE.shiftLeft(128);
        final long longMax = Long.MAX_VALUE;

        // J1: the parser returns 2^128 exactly, not Long.MAX
        BigInteger j1 = null;
        try {
            j1 = QuantityExpression.parse("2^128");
        } catch (NumberFormatException ignored) {
            // j1 stays null and the assertion reports it
        }
        check(sender, "J1", "★ 数量算式 2^128 精确解析（不再被夹成 Long.MAX）",
                twoPow128.equals(j1), twoPow128.toString(), String.valueOf(j1));

        // J2: the shortcuts still mean Long.MAX, and a plain big power is exact
        BigInteger j2max = null;
        BigInteger j2pow = null;
        try {
            j2max = QuantityExpression.parse("MAX");
            j2pow = QuantityExpression.parse("10^40");
        } catch (NumberFormatException ignored) {
            // reported below
        }
        check(sender, "J2", "快捷写法 MAX 仍是 Long.MAX；10^40 精确为 10^40",
                BigInteger.valueOf(longMax).equals(j2max) && BigInteger.TEN.pow(40).equals(j2pow),
                longMax + "/" + BigInteger.TEN.pow(40), j2max + "/" + j2pow);

        // J3: the anti-DoS bound must NOT be relaxed along with the clamp
        boolean j3refused = false;
        try {
            QuantityExpression.parse("2^99999");
        } catch (NumberFormatException expected) {
            j3refused = true;
        }
        check(sender, "J3", "防炸护栏仍在：2^99999（约 3 万位数字）被拒绝，而不是硬算",
                j3refused, "拒绝", String.valueOf(j3refused));

        // J4: a locked 2^128 record -- exact ledger, Long.MAX towards AE2, extraction capped but lossless
        UnlimitedCellInventory j4 = newInventory("j4");
        j4.setMode(2);
        j4.setWorkMode(1);
        j4.setStoredQuantity(key(Items.DIAMOND), twoPow128, true);
        BigInteger j4stored = j4.storedAmount(key(Items.DIAMOND));
        long j4listed = listedSize(j4, key(Items.DIAMOND));
        long j4take = size(j4.extractItems(stack(Items.DIAMOND, longMax), Actionable.MODULATE, NO_SOURCE));
        BigInteger j4after = j4.storedAmount(key(Items.DIAMOND));
        check(sender, "J4", "元件内数量 = 2^128（锁定）：账本精确、列表按 AE2 的 long 口径报 Long.MAX、取 Long.MAX 且账本不变",
                twoPow128.equals(j4stored) && j4listed == longMax && j4take == longMax
                        && twoPow128.equals(j4after),
                twoPow128 + "/" + longMax + "/" + longMax + "/" + twoPow128,
                j4stored + "/" + j4listed + "/" + j4take + "/" + j4after);

        // J5: unlocked -- extracting Long.MAX leaves the exact remainder, which is what proves the ledger
        // really runs past a long instead of saturating at it
        UnlimitedCellInventory j5 = newInventory("j5");
        j5.setMode(2);
        j5.setWorkMode(1);
        j5.setStoredQuantity(key(Items.EMERALD), twoPow128, false);
        long j5take = size(j5.extractItems(stack(Items.EMERALD, longMax), Actionable.MODULATE, NO_SOURCE));
        BigInteger j5left = j5.storedAmount(key(Items.EMERALD));
        BigInteger j5expect = twoPow128.subtract(BigInteger.valueOf(longMax));
        check(sender, "J5", "元件内数量 = 2^128（未锁定）：取走 Long.MAX 后余数精确（账本真的超过 long）",
                j5take == longMax && j5expect.equals(j5left),
                longMax + "/" + j5expect, j5take + "/" + j5left);
    }

    /**
     * K group: the save format across the P4 split.
     *
     * <p>sensei's layout (2026-10-03) is "storage separate, settings shared": the three count maps are per
     * channel, everything else is one shared set of settings. Saves made before that split have those maps at
     * the TOP LEVEL and keys with no channel marker, and reading them must land in the item channel -- if it
     * did not, every existing world would look empty. No in-game round trip can produce that layout any more,
     * so the self-test builds it by hand.
     */
    private void runSaveFormatGroup(ICommandSender sender) {
        IAEItemStack stickKey = key(Items.STICK);

        NBTTagCompound legacy = new NBTTagCompound();
        NBTTagList s1 = new NBTTagList();
        s1.appendTag(legacyCountEntry(new ItemStack(Items.STICK), "12345"));
        legacy.setTag("s1", s1);
        NBTTagList s2 = new NBTTagList();
        s2.appendTag(legacyCountEntry(new ItemStack(Items.DIAMOND), "7"));
        legacy.setTag("s2", s2);
        NBTTagList ca = new NBTTagList();
        ca.appendTag(legacyCountEntry(new ItemStack(Items.COAL), "9"));
        legacy.setTag("ca", ca);
        NBTTagList legacyWl = new NBTTagList();
        NBTTagCompound bareKey = new NBTTagCompound();
        AEItemStack.fromItemStack(new ItemStack(Items.PAPER)).writeToNBT(bareKey);
        legacyWl.appendTag(bareKey); // legacy sets stored the key tag itself, with no "k" wrapper
        legacy.setTag("w", legacyWl);
        NBTTagList legacyUl = new NBTTagList();
        legacyUl.appendTag(bareKey);
        legacy.setTag("u", legacyUl);
        for (String emptyKey : new String[] { "it", "m3", "ro", "rf", "rt", "bl", "ql", "or", "md", "rm" }) {
            legacy.setTag(emptyKey, new NBTTagList());
        }

        CellStorageData legacyData = new CellStorageData();
        legacyData.readFromNbt(legacy);
        BigInteger k1s1 = legacyData.stocks(CellChannelRegistry.ITEM).s1.get(stickKey);
        BigInteger k1s2 = legacyData.stocks(CellChannelRegistry.ITEM).s2.get(key(Items.DIAMOND));
        BigInteger k1ca = legacyData.stocks(CellChannelRegistry.ITEM).ca.get(key(Items.COAL));
        boolean k1wl = legacyData.wl.contains(key(Items.PAPER)) && legacyData.ul.contains(key(Items.PAPER));
        check(sender, "K1", "★ 旧存档（顶层 s1/s2/ca + 无通道标签的键）按物品通道读入，一条不丢",
                BigInteger.valueOf(12345).equals(k1s1) && BigInteger.valueOf(7).equals(k1s2)
                        && BigInteger.valueOf(9).equals(k1ca) && k1wl,
                "12345/7/9/true", k1s1 + "/" + k1s2 + "/" + k1ca + "/" + k1wl);

        // K2: new layout round trip -- including a per-key threshold and the blacklist (shared collections)
        legacyData.itemThr.put(stickKey, 4242L);
        legacyData.blacklist.add(stickKey);
        NBTTagCompound written = new NBTTagCompound();
        legacyData.writeToNbt(written);
        CellStorageData reloaded = new CellStorageData();
        reloaded.readFromNbt(written);
        BigInteger k2s1 = reloaded.stocks(CellChannelRegistry.ITEM).s1.get(stickKey);
        Long k2thr = reloaded.itemThr.get(stickKey);
        boolean k2ok = BigInteger.valueOf(12345).equals(k2s1) && k2thr != null && k2thr == 4242L
                && reloaded.blacklist.contains(stickKey) && reloaded.wl.contains(key(Items.PAPER))
                && reloaded.ul.contains(key(Items.PAPER))
                && BigInteger.valueOf(9).equals(reloaded.stocks(CellChannelRegistry.ITEM).ca.get(key(Items.COAL)));
        check(sender, "K2", "★ 新格式往返：按通道的存量 + 共享的单物品阈值/黑名单/白名单全部无损",
                k2ok, "12345/4242/true/true/true/9",
                k2s1 + "/" + k2thr + "/" + reloaded.blacklist.contains(stickKey) + "/"
                        + reloaded.wl.contains(key(Items.PAPER)) + "/" + reloaded.ul.contains(key(Items.PAPER))
                        + "/" + reloaded.stocks(CellChannelRegistry.ITEM).ca.get(key(Items.COAL)));

        // K3: another channel's bucket must not leak into the item channel's view
        UnlimitedCellInventory k3 = newInventory("k3");
        k3.setMode(1);
        k3.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE);
        k3.rawData().stocks("selftest-foreign").s1.put(stickKey, BigInteger.valueOf(999));
        long k3count = k3.getStoredItemCount();
        check(sender, "K3", "★ 别的通道的存量不会漏进物品通道的读数（每通道各自一间）",
                k3count == 5L, "5", String.valueOf(k3count));
    }

    /** One count entry in the PRE-P4 layout: {@code k} = key tag, {@code n} = decimal count. */
    private static NBTTagCompound legacyCountEntry(ItemStack definition, String count) {
        NBTTagCompound entry = new NBTTagCompound();
        NBTTagCompound keyTag = new NBTTagCompound();
        AEItemStack.fromItemStack(definition).writeToNBT(keyTag);
        entry.setTag("k", keyTag);
        entry.setString("n", count);
        return entry;
    }
    /**
     * L group: the FLUID channel -- the first channel added on top of the item channel (P4).
     *
     * <p>What has to hold for "one cell, every storage type": the fluid channel enumerates its own registry,
     * mod rules reach it, ore rules do NOT (1.12.2 has no fluid ore dictionary), the two channels keep
     * separate counts on the SAME cell while sharing the settings, and mode 3 stays affordable.
     */
    private void runFluidChannelGroup(ICommandSender sender) {
        IStorageChannel<IAEFluidStack> fluidChannel = FluidCellChannel.INSTANCE.storage();
        IAEFluidStack water = fluidKey(FluidRegistry.WATER);

        // L1: mode 3 lists every registered fluid as infinite, and the reported type count agrees with it
        FluidCellInventory l1 = newFluidInventory("l1");
        l1.setMode(3);
        IItemList<IAEFluidStack> l1list = fluidChannel.createList();
        l1.getAvailableItems(l1list);
        int registered = FluidRegistry.getRegisteredFluids().size();
        boolean l1allInfinite = true;
        for (IAEFluidStack listed : l1list) {
            if (listed.getStackSize() != ChannelCellCore.INFINITE) {
                l1allInfinite = false;
                break;
            }
        }
        check(sender, "L1", "流体通道 mode3：列出全部注册流体且都算无限，类型数与条数一致",
                l1list.size() == registered && registered > 5 && l1allInfinite
                        && l1.getStoredItemTypes() == l1list.size(),
                "=" + registered + "/全无限/types=list",
                "listed=" + l1list.size() + " types=" + l1.getStoredItemTypes() + " all=" + l1allInfinite);

        // L2: a mod rule reaches fluids too. Water is named "water" with no namespace, so this only works
        // because the adapter maps a missing colon to minecraft.
        FluidCellInventory l2 = newFluidInventory("l2");
        l2.setMode(2);
        l2.setWorkMode(1);
        l2.addModRule("minecraft");
        boolean l2rule = l2.isInfiniteByRule(water);
        long l2listed = fluidListedSize(l2, water);
        check(sender, "L2", "★ 模组规则 minecraft 命中水（无命名空间的流体按 minecraft 算）",
                l2rule && l2listed == ChannelCellCore.INFINITE, "true/infinite", l2rule + "/" + l2listed);

        // L3: ore rules must not reach fluids
        FluidCellInventory l3 = newFluidInventory("l3");
        l3.setMode(2);
        l3.setWorkMode(1);
        l3.addOreRule("ingotIron");
        long l3listed = fluidListedSize(l3, water);
        check(sender, "L3", "★ 矿词规则对流体无效（没有流体矿物词典，规则只对物品生效）",
                l3listed == -1L && !l3.isInfiniteByRule(water), "-1/false",
                l3listed + "/" + l3.isInfiniteByRule(water));

        // L4: the SAME cell holds both -- separate counts, shared settings
        ItemStack shared = cellWithUuid(testUuid("l4"));
        UnlimitedCellInventory l4items = new UnlimitedCellInventory(shared, null);
        wipe(l4items);
        FluidCellInventory l4fluids = new FluidCellInventory(shared, null);
        l4items.setMode(1);
        l4items.injectItems(stack(Items.STICK, 1000L), Actionable.MODULATE, NO_SOURCE);
        l4fluids.injectItems(fluidStack(FluidRegistry.WATER, 500), Actionable.MODULATE, NO_SOURCE);
        long l4itemCount = l4items.getStoredItemCount();
        long l4fluidCount = l4fluids.getStoredItemCount();
        boolean l4shared = l4items.getMode() == 1 && l4fluids.getMode() == 1;
        check(sender, "L4", "★ 同一只元件：物品与流体的存量互不干扰，但设置（模式）是共享的",
                l4itemCount == 1000L && l4fluidCount == 500L && l4shared,
                "1000/500/true", l4itemCount + "/" + l4fluidCount + "/" + l4shared);

        // L5: the threshold counts millibuckets, so 1000 mB (one bucket) can promote a fluid
        FluidCellInventory l5 = newFluidInventory("l5");
        l5.setMode(2);
        l5.setWorkMode(1);
        l5.setThreshold(1000L);
        l5.injectItems(fluidStack(FluidRegistry.WATER, 999), Actionable.MODULATE, NO_SOURCE);
        boolean l5before = l5.isInfinite(water);
        l5.injectItems(fluidStack(FluidRegistry.WATER, 1), Actionable.MODULATE, NO_SOURCE);
        boolean l5after = l5.isInfinite(water);
        check(sender, "L5", "流体阈值按 mB 计：存 999 mB 不升级、再存 1 mB（共 1000）升级为无限",
                !l5before && l5after, "false/true", l5before + "/" + l5after);

        // L6: what mode 3 costs for fluids -- measured, like the item channel's H2
        IItemList<IAEFluidStack> cold = fluidChannel.createList();
        long coldStart = System.nanoTime();
        l1.getAvailableItems(cold);
        long coldNanos = System.nanoTime() - coldStart;
        IItemList<IAEFluidStack> warm = fluidChannel.createList();
        long warmStart = System.nanoTime();
        l1.getAvailableItems(warm);
        long warmNanos = System.nanoTime() - warmStart;
        sender.sendMessage(new TextComponentString(String.format(
                "[ae2addon] [L6] 流体 mode3 列出 %d 个键：冷 %.1f ms（含一次性注册表扫描）/ 热 %.2f ms",
                warm.size(), coldNanos / 1e6, warmNanos / 1e6)));
        check(sender, "L6", "流体 mode3 热重建 < 50 ms（超了就该退回按需列出）",
                warmNanos < 50_000_000L, "<50ms", String.format("%.2f ms", warmNanos / 1e6));
    }

    private static FluidCellInventory newFluidInventory(String tag) {
        FluidCellInventory inv = new FluidCellInventory(cellWithUuid(testUuid(tag)), null);
        wipe(inv.rawData());
        return inv;
    }

    private static IAEFluidStack fluidKey(Fluid fluid) {
        return AEFluidStack.fromFluidStack(new FluidStack(fluid, 1));
    }

    private static IAEFluidStack fluidStack(Fluid fluid, int amount) {
        return AEFluidStack.fromFluidStack(new FluidStack(fluid, amount));
    }

    /** Size the fluid terminal would see, or -1 when the cell does not list that fluid at all. */
    private static long fluidListedSize(FluidCellInventory inv, IAEFluidStack key) {
        IItemList<IAEFluidStack> list = FluidCellChannel.INSTANCE.storage().createList();
        inv.getAvailableItems(list);
        IAEFluidStack found = list.findPrecise(key);
        return found == null ? -1L : found.getStackSize();
    }
    /**
     * Optional channels (gas, and later essentia): their assertions live in a class that only loads together
     * with the bridge mod. This method names nothing but the DOOR before deciding, and class loading is lazy,
     * so a missing bridge mod costs a skipped group instead of a NoClassDefFoundError in the self-test.
     */
    private void runOptionalChannelGroups(ICommandSender sender) {
        if (com.ae2addon.cell.gas.GasChannelDoor.isLoaded()) {
            com.ae2addon.cell.gas.GasChannelSelfTest.run(sender, this);
        } else {
            sender.sendMessage(new TextComponentString(
                    "[ae2addon] 跳过 M 组：没装气体通道（Mekanism Energistics）"));
        }
        if (com.ae2addon.cell.essentia.EssentiaChannelDoor.isLoaded()) {
            com.ae2addon.cell.essentia.EssentiaChannelSelfTest.run(sender, this);
        } else {
            sender.sendMessage(new TextComponentString(
                    "[ae2addon] 跳过 N 组：没装源质通道（Thaumic Energistics）"));
        }
        if (com.ae2addon.cell.mana.ManaChannelDoor.isLoaded()) {
            com.ae2addon.cell.mana.ManaChannelSelfTest.run(sender, this);
        } else {
            sender.sendMessage(new TextComponentString(
                    "[ae2addon] 跳过 O 组：没装法力通道（Botania Applie）"));
        }
        if (com.ae2addon.cell.flux.FluxChannelDoor.isLoaded()) {
            com.ae2addon.cell.flux.FluxChannelSelfTest.run(sender, this);
        } else {
            sender.sendMessage(new TextComponentString(
                    "[ae2addon] 跳过 P 组：没装能量通道（Flux Applied）"));
        }
    }

    /** Reporting bridge for the optional-channel groups, which live in their own (lazily loaded) class. */
    public void report(ICommandSender sender, String id, String what, boolean ok, String expected, String actual) {
        check(sender, id, what, ok, expected, actual);
    }

    // ---- scaffolding for the optional-channel groups ----------------------------------------------

    /** A fresh cell stack on a fixed uuid, so an optional group can build its own inventories. */
    public static ItemStack testCell(String tag) {
        return cellWithUuid(testUuid(tag));
    }

    /** Clears a cell record (all channel stocks + the shared settings), for rerunnable groups. */
    public static void wipeCell(CellStorageData data) {
        wipe(data);
    }

    /** The same key builder the item-group assertions use. */
    public static IAEItemStack itemStackOf(Item item, long count) {
        return stack(item, count);
    }
    /**
     * Q group: the SERVER half of the channel switch in the config panel (sensei 2026-10-03).
     *
     * <p>The panel serves whichever channel the player selected, so it reaches the cell through
     * {@link CellInventories} and talks in untyped keys ({@code IAEStack<?>}). The GUI itself cannot be
     * self-tested, but this half can: that opening a channel really reads that channel's rows, that the untyped
     * delegates act on the selected channel while touching the SHARED settings, and that a row carries a label
     * for channels that have no item icon.
     */
    private void runPanelChannelGroup(ICommandSender sender) {
        ItemStack cell = cellWithUuid(testUuid("q1"));
        UnlimitedCellInventory items = new UnlimitedCellInventory(cell, null);
        wipe(items);
        FluidCellInventory fluids = new FluidCellInventory(cell, null);
        IAEFluidStack water = fluidKey(FluidRegistry.WATER);
        items.setMode(2);
        items.setWorkMode(1);
        items.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE);
        fluids.injectItems(fluidStack(FluidRegistry.WATER, 3000), Actionable.MODULATE, NO_SOURCE);

        // Q1: the same cell, opened per channel, lists THAT channel's rows only
        ChannelCellCore<?> itemView = CellInventories.open(cell, null, CellChannelRegistry.ITEM);
        ChannelCellCore<?> fluidView = CellInventories.open(cell, null, CellChannelRegistry.FLUID);
        int itemRows = itemView == null ? -1 : itemView.getPanelItems().size();
        int fluidRows = fluidView == null ? -1 : fluidView.getPanelItems().size();
        boolean fluidRowIsWater = false;
        if (fluidView != null) {
            for (ChannelCellCore.PanelItem row : fluidView.getPanelItems()) {
                if (row.key.getChannel() == FluidCellChannel.INSTANCE.storage()) fluidRowIsWater = true;
            }
        }
        check(sender, "Q1", "★ 面板按通道取行：同一只元件，物品视图只列物品、流体视图只列流体",
                itemRows == 1 && fluidRows == 1 && fluidRowIsWater, "1/1/true",
                itemRows + "/" + fluidRows + "/" + fluidRowIsWater);

        // Q2: untyped delegates hit the selected channel, and what they write is the SHARED collection
        boolean toggled = fluidView != null && fluidView.toggleBlacklistOf(water);
        boolean sharedBlacklist = items.isBlacklistedOf(water);
        boolean stickUntouched = !items.isBlacklisted(key(Items.STICK));
        check(sender, "Q2", "★ 无类型委托作用在所选通道的键上，而黑名单是共享集合（物品视图也能看到）",
                toggled && sharedBlacklist && stickUntouched, "true/true/true",
                toggled + "/" + sharedBlacklist + "/" + stickUntouched);

        // Q3: pinning a fluid key's quantity writes the FLUID stock only
        if (fluidView != null) {
            fluidView.setStoredQuantityOf(water, BigInteger.valueOf(4096), true);
        }
        BigInteger fluidAmount = fluidView == null ? null : fluidView.storedAmountOf(water);
        long itemCount = items.getStoredItemCount();
        check(sender, "Q3", "★ 面板设「元件内数量」：只动所选通道的存量，另一通道读数不受影响",
                BigInteger.valueOf(4096).equals(fluidAmount) && itemCount == 5L,
                "4096/5", fluidAmount + "/" + itemCount);

        // Q4: a row carries a label -- the only thing a channel without an item icon can show
        String label = fluidView == null ? null : fluidView.displayNameOf(water);
        String rowLabel = "";
        if (fluidView != null) {
            for (ChannelCellCore.PanelItem row : fluidView.getPanelItems()) {
                rowLabel = fluidView.displayNameOf(row.key);
            }
        }
        check(sender, "Q4", "面板行带显示名（非物品通道没有图标，只能显示标签）",
                label != null && !label.isEmpty() && !rowLabel.isEmpty(), "非空/非空",
                label + "/" + rowLabel);
    }
    /**
     * R group: per-channel completeness of the mode 3 listing, which is what "the terminal only shows one kind
     * of storage type" (sensei 2026-10-03) has to be diagnosed against.
     *
     * <p>Mode 3 means "everything this channel can hold is infinite", and each channel's cell inventory answers
     * for its OWN registry. So this walks every REGISTERED channel and checks, without naming their stack types:
     * the cell registry really hands back a handler for that channel, that handler's listing is complete
     * (as many rows as the channel has keys), and the reported type count agrees with the listing.
     */
    private void runChannelCompletenessGroup(ICommandSender sender) {
        ItemStack cell = cellWithUuid(testUuid("r1"));
        UnlimitedCellInventory items = new UnlimitedCellInventory(cell, null);
        wipe(items);
        items.setMode(3);

        java.util.List<com.ae2addon.cell.CellChannel<?>> channels =
                new java.util.ArrayList<>(com.ae2addon.cell.CellChannelRegistry.all());
        StringBuilder detail = new StringBuilder();
        int complete = 0;
        int registered = 0;
        for (com.ae2addon.cell.CellChannel<?> channel : channels) {
            ChannelCellCore<?> core = CellInventories.open(cell, null, channel.id());
            registered++;
            if (core == null) {
                detail.append(channel.id()).append("=noCore ");
                continue;
            }
            core.setMode(3);
            int rows = core.listedRowCount();
            int keys = channel.allKeys().size();
            long types = core.getStoredItemTypes();
            boolean ok = rows == keys && types == rows && keys > 0;
            if (ok) {
                complete++;
            }
            detail.append(channel.id()).append("=").append(rows).append("/").append(keys)
                    .append(ok ? " " : "! ");
        }
        check(sender, "R1", "★ 每个通道被单独问到时，mode3 列表都是完整的（行数 = 该通道键数）",
                registered > 0 && complete == registered, "所有通道都 full",
                detail.toString().trim());

        // R2: the cell registry -- the exact call a drive makes -- answers for every channel
        int answers = 0;
        StringBuilder which = new StringBuilder();
        for (com.ae2addon.cell.CellChannel<?> channel : channels) {
            boolean has = cellHandlerFor(cell, channel) != null;
            if (has) {
                answers++;
            }
            which.append(channel.id()).append(has ? "=ok " : "=NULL ");
        }
        check(sender, "R2", "★ 元件注册表对每个通道都能给出 handler（驱动器就是这样逐通道问的）",
                answers == channels.size() && !channels.isEmpty(), "全部 ok", which.toString().trim());

        // R3: mode 3 must not depend on what other channels hold -- store a fluid and re-check the item view
        FluidCellInventory fluids = new FluidCellInventory(cell, null);
        fluids.injectItems(fluidStack(FluidRegistry.WATER, 1234), Actionable.MODULATE, NO_SOURCE);
        ChannelCellCore<?> itemView = CellInventories.open(cell, null, CellChannelRegistry.ITEM);
        ChannelCellCore<?> fluidView = CellInventories.open(cell, null, CellChannelRegistry.FLUID);
        int itemRows = itemView == null ? -1 : itemView.listedRowCount();
        int fluidRows = fluidView == null ? -1 : fluidView.listedRowCount();
        int itemKeys = ItemCellChannel.INSTANCE.allKeys().size();
        int fluidKeys = FluidCellChannel.INSTANCE.allKeys().size();
        check(sender, "R3", "★ 另一通道有数据不影响本通道：物品视图列全部物品、流体视图列全部流体",
                itemRows == itemKeys && fluidRows == fluidKeys, itemKeys + "/" + fluidKeys,
                itemRows + "/" + fluidRows);

        // R4: and the reported type count still equals the listing for both
        check(sender, "R4", "mode3 下每个通道「类型数 == 列出条数」（不漂移）",
                itemView != null && fluidView != null
                        && itemView.getStoredItemTypes() == itemRows
                        && fluidView.getStoredItemTypes() == fluidRows,
                "相等", (itemView == null ? "?" : itemView.getStoredItemTypes()) + "/" + itemRows + " "
                        + (fluidView == null ? "?" : fluidView.getStoredItemTypes()) + "/" + fluidRows);
    }

    /**
     * Asks the cell registry for one channel's handler, the way a drive does.
     *
     * <p>Raw on purpose: this has to ask about EVERY registered channel without naming their stack types, and
     * the registry method's own type parameter cannot be expressed for a wildcard channel. The cast is safe --
     * the channel instance comes from the adapter that owns it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ICellInventoryHandler<?> cellHandlerFor(ItemStack cell,
            com.ae2addon.cell.CellChannel<?> channel) {
        try {
            return AEApi.instance().registries().cell().getCellInventory(cell, null,
                    (IStorageChannel) channel.storage());
        } catch (RuntimeException | LinkageError failed) {
            return null;
        }
    }
    /**
     * S group: the settings are SHARED, so a change made through one channel's view is visible through every
     * other one immediately.
     *
     * <p>This is the pin for sensei's bug of 2026-10-03 ("in mode 3 the terminal only shows one storage type as
     * infinite"). The drive keeps one cell inventory per CHANNEL alive across grid rebuilds, so while the
     * settings were per-instance fields, a mode switch only reached the instances rebuilt afterwards.
     */
    private void runSharedSettingsGroup(ICommandSender sender) {
        ItemStack cell = cellWithUuid(testUuid("s1"));
        UnlimitedCellInventory itemView = new UnlimitedCellInventory(cell, null);
        wipe(itemView);
        FluidCellInventory fluidView = new FluidCellInventory(cell, null);

        // S1: a mode switch through the item view is what the fluid view reports at once
        itemView.setMode(3);
        int fluidSeesMode = fluidView.getMode();
        check(sender, "S1", "★ 设置是共享的：从物品视图切到 mode3，流体视图立刻也读到 3（不是各存一份）",
                fluidSeesMode == 3 && itemView.getMode() == 3, "3/3", fluidSeesMode + "/" + itemView.getMode());

        // S2: same for the work mode
        itemView.setMode(2);
        itemView.setWorkMode(3);
        check(sender, "S2", "★ 工作模式同样共享：物品视图设 3，流体视图读到 3",
                fluidView.getWorkMode() == 3, "3", String.valueOf(fluidView.getWorkMode()));

        // S3: and for the升级阈值
        itemView.setThreshold(4242L);
        check(sender, "S3", "★ 阈值同样共享：物品视图设 4242，流体视图读到 4242",
                fluidView.getThreshold() == 4242L, "4242", String.valueOf(fluidView.getThreshold()));

        // S4: the tooltip copy on the cell item still follows (the client cannot read the world save)
        ChannelCellCore.Summary summary = ChannelCellCore.summaryOf(cell);
        check(sender, "S4", "元件 NBT 里的设置副本跟着更新（tooltip 用，客户端读不到世界存档）",
                summary.mode == 2 && summary.workMode == 3 && summary.threshold == 4242L, "2/3/4242",
                summary.mode + "/" + summary.workMode + "/" + summary.threshold);

        // S5: and a NEW view opened later sees the same settings (the drive rebuilds views on grid changes)
        ChannelCellCore<?> freshView = CellInventories.open(cell, null, CellChannelRegistry.FLUID);
        check(sender, "S5", "新开的视图读到的也是共享设置（mode2/wm3/thr4242）",
                freshView != null && freshView.getMode() == 2 && freshView.getWorkMode() == 3
                        && freshView.getThreshold() == 4242L,
                "2/3/4242", freshView == null ? "null" : freshView.getMode() + "/" + freshView.getWorkMode()
                        + "/" + freshView.getThreshold());
    }
    /**
     * T group: the path the DRIVE actually uses.
     *
     * <p>Every earlier group built a cell inventory directly. A drive goes through the cell registry instead
     * ({@code AEApi...registries().cell().getCellInventory(stack, provider, channel)} -> handler -> getCellInv()),
     * which is where sensei's BUG1/BUG2 of 2026-10-03 showed up ("the terminal shows one storage type or nothing
     * and nothing can be inserted"). This walks that exact path for every registered channel.
     */
    private void runDrivePathGroup(ICommandSender sender) {
        ItemStack cell = cellWithUuid(testUuid("t1"));
        UnlimitedCellInventory direct = new UnlimitedCellInventory(cell, null);
        wipe(direct);
        direct.setMode(1);

        java.util.List<com.ae2addon.cell.CellChannel<?>> channels =
                new java.util.ArrayList<>(com.ae2addon.cell.CellChannelRegistry.all());
        StringBuilder detail = new StringBuilder();
        int listed = 0;
        for (com.ae2addon.cell.CellChannel<?> channel : channels) {
            com.ae2addon.cell.ChannelCellCore<?> core = CellInventories.open(cell, null, channel.id());
            String note;
            try {
                ICellInventoryHandler<?> handler = cellHandlerFor(cell, channel);
                if (handler == null || handler.getCellInv() == null) {
                    note = channel.id() + "=noHandler";
                } else {
                    // The handler's own inventory must answer with rows for its channel in mode 3.
                    core.setMode(3);
                    int rows = core.listedRowCount();
                    int keys = channel.allKeys().size();
                    if (rows == keys && rows > 0) {
                        listed++;
                    }
                    note = channel.id() + "=" + rows + "/" + keys + (rows == keys ? " " : "! ");
                }
            } catch (RuntimeException | LinkageError failed) {
                note = channel.id() + "=EX:" + failed.getClass().getSimpleName();
            }
            detail.append(note);
            core.setMode(1);
        }
        check(sender, "T1", "★ 走驱动器那条路（元件注册表→handler→getCellInv）逐通道 mode3 列表，无异常且完整",
                listed == channels.size() && !channels.isEmpty(), "所有通道都 full",
                detail.toString().trim());

        // T2: and the same path must accept an insert in mode 1 for every channel
        StringBuilder inserted = new StringBuilder();
        int accepted = 0;
        for (com.ae2addon.cell.CellChannel<?> channel : channels) {
            com.ae2addon.cell.ChannelCellCore<?> core = CellInventories.open(cell, null, channel.id());
            core.setMode(1);
            try {
                ICellInventoryHandler<?> handler = cellHandlerFor(cell, channel);
                if (handler != null && handler.getCellInv() != null) {
                    // Inject a plain "one key" of that channel through the handler's inventory.
                    appeng.api.storage.data.IAEStack<?> key = channel.allKeys().isEmpty() ? null : channel.allKeys().get(0);
                    if (key != null) {
                        appeng.api.storage.data.IAEStack<?> one = key.copy();
                        one.setStackSize(100);
                        appeng.api.storage.data.IAEStack left = injectUntyped(handler, one);
                        long stored = core.storedAmountOf(key) == null ? -1L : core.storedAmountOf(key).longValue();
                        if (left == null && stored == 100L) {
                            accepted++;
                        }
                        inserted.append(channel.id()).append("=").append(stored).append(left == null ? " " : "! ");
                    } else {
                        inserted.append(channel.id()).append("=noKey ");
                    }
                } else {
                    inserted.append(channel.id()).append("=noHandler ");
                }
            } catch (RuntimeException | LinkageError failed) {
                inserted.append(channel.id()).append("=EX:").append(failed.getClass().getSimpleName()).append(" ");
            }
        }
        check(sender, "T2", "★ 同一条路逐通道 mode1 存入 100 个键：都被接受且读数为 100",
                accepted == channels.size(), "所有通道都接受", inserted.toString().trim());
    }

    /** Injects through a handler whose key type we deliberately do not name (raw on purpose). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static appeng.api.storage.data.IAEStack injectUntyped(ICellInventoryHandler handler, appeng.api.storage.data.IAEStack key) {
        return handler.getCellInv().injectItems(key, Actionable.MODULATE, NO_SOURCE);
    }

    /**
     * U group: the drive patch -- AE2's drive binds ONE channel per slot, so we register the rest.
     *
     * <p>javap on {@code TileDrive.updateState()} (and on CrazyAE's improved drive) shows the drive walking
     * {@code storageChannels()} and jumping to the next slot as soon as one channel answers non-null. Since our
     * cell answers for six channels, the drive picked one at random per launch and the ME network never saw the
     * others -- sensei's BUG1/BUG2. {@link com.ae2addon.cell.DriveChannelBinder} appends the skipped channels;
     * the mixin calls it, and this group drives the same code with a fake slot source, so the patch logic is
     * pinned in-game without needing a world.
     *
     * <p>The three properties that matter: what AE2 already bound is not added twice, every channel we serve
     * ends up registered, and somebody else's cell is left exactly as AE2 left it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runDriveBindingGroup(ICommandSender sender) {
        ItemStack cell = cellWithUuid(testUuid("u1"));
        UnlimitedCellInventory direct = new UnlimitedCellInventory(cell, null);
        wipe(direct);
        direct.setMode(1);

        java.util.List<IStorageChannel> order =
                new java.util.ArrayList<IStorageChannel>(AEApi.instance().storage().storageChannels());
        java.util.List<IStorageChannel> served = new java.util.ArrayList<IStorageChannel>();
        for (IStorageChannel channel : order) {
            if (AEApi.instance().registries().cell().getCellInventory(cell, null, channel) != null) {
                served.add(channel);
            }
        }

        // The map AE2 builds: one (initially empty) list per registered channel, then its OWN single binding --
        // the first channel in this order that answered, exactly as the drive's break leaves it.
        java.util.Map<IStorageChannel, java.util.List<appeng.api.storage.IMEInventoryHandler>> map =
                new java.util.LinkedHashMap<IStorageChannel, java.util.List<appeng.api.storage.IMEInventoryHandler>>();
        for (IStorageChannel channel : order) {
            map.put(channel, new java.util.ArrayList<appeng.api.storage.IMEInventoryHandler>());
        }
        IStorageChannel boundByAe2 = served.get(0);
        map.get(boundByAe2).add(AEApi.instance().registries().cell().getCellInventory(cell, null, boundByAe2));

        int added = com.ae2addon.cell.DriveChannelBinder.bindMissing(map, fakeSlots(cell), DIRECT_WATCHERS);

        StringBuilder detail = new StringBuilder();
        int onServed = 0;
        int onUnserved = 0;
        for (IStorageChannel channel : order) {
            int rows = map.get(channel).size();
            boolean isServed = served.contains(channel);
            if (isServed) {
                onServed += rows;
            } else {
                onUnserved += rows;
            }
            detail.append(channel.getClass().getSimpleName()).append('=').append(rows).append(isServed ? " " : "! ");
        }

        // U1: AE2 bound one channel; the binder must add exactly the other ones it serves
        check(sender, "U1", "★ 驱动器漏掉的通道被补上（补绑数 = 我们服务的通道数 - 1）",
                served.size() >= 2 && added == served.size() - 1, (served.size() - 1) + " 条",
                added + " 条（共服务 " + served.size() + " 个通道）");

        // U2: every served channel ends up with exactly one registration, and we claim nothing else
        check(sender, "U2", "★ 每条我们服务的通道恰好一条、别人的通道一条都不占（不重复绑、不乱认）",
                onServed == served.size() && onUnserved == 0, "每条 1 条 / 其余 0 条", detail.toString().trim());

        // U3: a foreign cell (AE2's own 1k cell) must be left alone -- that is what keeps this patch safe
        ItemStack ae2Cell = AEApi.instance().definitions().items().cell1k().maybeStack(1).orElse(ItemStack.EMPTY);
        int foreignAdded = ae2Cell.isEmpty() ? -1 : com.ae2addon.cell.DriveChannelBinder.bindMissing(
                emptyChannelMap(order), fakeSlots(ae2Cell), DIRECT_WATCHERS);
        check(sender, "U3", "★ 别人的元件一条都不动（AE2 自带元件沿用「一格一通道」原语义）",
                !ae2Cell.isEmpty() && foreignAdded == 0, "0 条", foreignAdded + " 条");

        // U4: empty slots and unrelated items are skipped as well
        int emptyAdded = com.ae2addon.cell.DriveChannelBinder.bindMissing(
                emptyChannelMap(order), fakeSlots(ItemStack.EMPTY), DIRECT_WATCHERS);
        int stickAdded = com.ae2addon.cell.DriveChannelBinder.bindMissing(
                emptyChannelMap(order), fakeSlots(new ItemStack(Items.STICK)), DIRECT_WATCHERS);
        check(sender, "U4", "空槽与无关物品不进补绑流程（绑定数为 0）",
                emptyAdded == 0 && stickAdded == 0, "0/0", emptyAdded + "/" + stickAdded);
    }

    /**
     * W group: the panel's "全部" view -- one list holding every channel's rows (sensei 2026-10-04).
     *
     * <p>What can silently break here is a row naming the wrong channel: the panel sends an action as
     * (channelId, key's own NBT), so a row that claims to be the item channel while carrying a fluid key would
     * act on the wrong view. These assertions drive the same assembly the server uses ({@code panelRows}).
     */
    private void runPanelAllChannelsGroup(ICommandSender sender) {
        ItemStack cell = cellWithUuid(testUuid("w1"));
        UnlimitedCellInventory items = new UnlimitedCellInventory(cell, null);
        wipe(items);
        // mode 2 on purpose: the panel lists the mode-2 structures (s2 / whitelist / rules), so a mode-1 cell
        // has NO rows to show and the expected count would be 0 -- which is exactly how this group first went red.
        items.setMode(2);
        items.setWorkMode(1);
        FluidCellInventory fluids = new FluidCellInventory(cell, null);
        items.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE);
        fluids.injectItems(fluidStack(FluidRegistry.WATER, 3000), Actionable.MODULATE, NO_SOURCE);

        int expected = 0;
        for (com.ae2addon.cell.CellChannel<?> channel : com.ae2addon.cell.CellChannelRegistry.all()) {
            com.ae2addon.cell.ChannelCellCore<?> core = CellInventories.open(cell, null, channel.id());
            expected += core == null ? 0 : core.getPanelItems().size();
        }
        java.util.List<com.ae2addon.network.Mode2ConfigPacket.PanelEntry> allRows =
                com.ae2addon.network.Mode2ConfigPacket.panelRows(cell,
                        com.ae2addon.network.Mode2ConfigPacket.ALL_CHANNELS);

        // W1: the All view is exactly the sum of every channel's rows (item + fluid here), not just one channel
        check(sender, "W1", "★ 「全部」视图把各通道的行合成一份（行数 = 各通道行数之和）",
                expected >= 2 && allRows.size() == expected, String.valueOf(expected),
                allRows.size() + "（期望 " + expected + "）");

        // W2: every row carries a channel id whose own reader can parse that row's key
        boolean everyRowResolves = true;
        StringBuilder detail = new StringBuilder();
        for (com.ae2addon.network.Mode2ConfigPacket.PanelEntry row : allRows) {
            com.ae2addon.cell.CellChannel<?> channel = com.ae2addon.cell.CellChannelRegistry.byId(row.channelId);
            boolean ok = channel != null && row.keyNbt != null && channel.readKeyUntyped(row.keyNbt) != null;
            if (!ok) {
                everyRowResolves = false;
            }
            detail.append(row.channelId).append(ok ? "=ok " : "=BAD ");
        }
        check(sender, "W2", "★ 每行都带自己的通道身份，且该通道能读回自己的键（动作不会被算到物品视图）",
                everyRowResolves && !allRows.isEmpty(), "全部 ok", detail.toString().trim());

        // W3: a single-channel view is unchanged, and a non-item row is shown by label (it has no icon)
        java.util.List<com.ae2addon.network.Mode2ConfigPacket.PanelEntry> fluidRows =
                com.ae2addon.network.Mode2ConfigPacket.panelRows(cell, CellChannelRegistry.FLUID);
        boolean fluidRowOk = fluidRows.size() == 1
                && CellChannelRegistry.FLUID.equals(fluidRows.get(0).channelId)
                && fluidRows.get(0).item.isEmpty() && fluidRows.get(0).label != null
                && !fluidRows.get(0).label.isEmpty();
        check(sender, "W3", "★ 单通道视图不变：选流体只有 1 行、行归属流体、无图标但有显示名",
                fluidRowOk, "1 行 / fluid / 无图标 / 有名字",
                fluidRows.size() + " 行 / " + (fluidRows.isEmpty() ? "?" : fluidRows.get(0).channelId) + " / "
                        + (fluidRows.isEmpty() ? "?" : String.valueOf(fluidRows.get(0).item.isEmpty())) + " / "
                        + (fluidRows.isEmpty() ? "?" : fluidRows.get(0).label));
    }

    /** A channel map with one empty list per registered channel, the way AE2 lays it out. */
    @SuppressWarnings({"unchecked", "rawtypes"})    private static java.util.Map<IStorageChannel, java.util.List<appeng.api.storage.IMEInventoryHandler>> emptyChannelMap(
            java.util.List<IStorageChannel> order) {
        java.util.Map<IStorageChannel, java.util.List<appeng.api.storage.IMEInventoryHandler>> map =
                new java.util.LinkedHashMap<IStorageChannel, java.util.List<appeng.api.storage.IMEInventoryHandler>>();
        for (IStorageChannel channel : order) {
            map.put(channel, new java.util.ArrayList<appeng.api.storage.IMEInventoryHandler>());
        }
        return map;
    }

    /** One slot holding {@code stack}, with no save provider -- what a drive would hand the binder. */
    private static com.ae2addon.cell.DriveChannelBinder.Slots fakeSlots(final ItemStack stack) {
        return new com.ae2addon.cell.DriveChannelBinder.Slots() {

            @Override
            public int count() {
                return 1;
            }

            @Override
            public ItemStack stackAt(int slot) {
                return slot == 0 ? stack : ItemStack.EMPTY;
            }

            @Override
            public appeng.api.storage.ISaveProvider provider() {
                return null;
            }

            @Override
            public int priority() {
                return 7;
            }
        };
    }

    /** Plain registration, without the DriveWatcher wrapper a real drive builds. */
    private static final com.ae2addon.cell.DriveChannelBinder.Watchers DIRECT_WATCHERS =
            new com.ae2addon.cell.DriveChannelBinder.Watchers() {

                @Override
                public appeng.api.storage.IMEInventoryHandler<?> create(ICellInventoryHandler<?> handler,
                        ItemStack stack, appeng.api.storage.ICellHandler cellHandler,
                        appeng.api.storage.ISaveProvider provider, int priority) {
                    return handler;
                }
            };

    /**
     * V group: the per-channel tooltip summary (sensei 2026-10-04: "can the drive slot show every channel?").
     *
     * <p>A universal cell holds several channels at once, so "how full is it" has one answer per channel. The
     * summary used to be a single pair of NBT tags that every channel view wrote into the same place, and the
     * whole root compound was replaced on each write -- so the tooltip showed whichever channel persisted last
     * (random, and zero when that channel was empty). The layout is now {@code _ch/<channelId>} with one entry
     * per channel, and the flat tags stay the item channel's for older readers. These assertions pin both.
     */
    private void runChannelSummaryGroup(ICommandSender sender) {
        ItemStack cell = cellWithUuid(testUuid("v1"));
        UnlimitedCellInventory items = new UnlimitedCellInventory(cell, null);
        wipe(items);
        FluidCellInventory fluids = new FluidCellInventory(cell, null);
        items.setMode(1);
        items.setWorkMode(1);
        items.injectItems(stack(Items.STICK, 5L), Actionable.MODULATE, NO_SOURCE);
        fluids.injectItems(fluidStack(FluidRegistry.WATER, 3000), Actionable.MODULATE, NO_SOURCE);
        items.persist();
        fluids.persist();

        UnlimitedCellInventory.Summary itemSummary =
                UnlimitedCellInventory.summaryOf(cell, CellChannelRegistry.ITEM);
        UnlimitedCellInventory.Summary fluidSummary =
                UnlimitedCellInventory.summaryOf(cell, CellChannelRegistry.FLUID);

        // V1: both channels have their own entry, with their own真实 numbers (1 type / 5 bytes vs 1 / 3000)
        check(sender, "V1", "★ 每个通道各存一份摘要，互不覆盖（物品 1 类型/5 字节、流体 1/3000）",
                itemSummary.types == 1 && itemSummary.bytes.equals(BigInteger.valueOf(5L))
                        && fluidSummary.types == 1 && fluidSummary.bytes.equals(BigInteger.valueOf(3000L)),
                "1/5 与 1/3000", itemSummary.types + "/" + itemSummary.bytes + " 与 "
                        + fluidSummary.types + "/" + fluidSummary.bytes);

        // V2: writing a channel again (the drive persists on every grid rebuild) must not touch the others
        fluids.persist();
        fluids.persist();
        UnlimitedCellInventory.Summary itemAgain =
                UnlimitedCellInventory.summaryOf(cell, CellChannelRegistry.ITEM);
        check(sender, "V2", "★ 反复写盘不串台：流体视图再写两次，物品条目仍是 1 类型 / 5 字节",
                itemAgain.types == 1 && itemAgain.bytes.equals(BigInteger.valueOf(5L)), "1/5",
                itemAgain.types + "/" + itemAgain.bytes);

        // V3: a cell written before this layout has only the flat tags -- read them as the item channel
        ItemStack legacy = cellWithUuid(testUuid("v2"));
        NBTTagCompound legacyRoot = new NBTTagCompound();
        legacyRoot.setInteger("v", 2);
        legacyRoot.setInteger("mode", 2);
        legacyRoot.setInteger("wm", 1);
        legacyRoot.setLong("thr", 4096L);
        legacyRoot.setInteger("_t", 7);
        legacyRoot.setByteArray("_b2", BigInteger.valueOf(1234L).toByteArray());
        legacyRoot.setLong("_b", 1234L);
        NBTTagCompound legacyStackTag = new NBTTagCompound();
        legacyStackTag.setTag("ae2addon:cell", legacyRoot);
        legacy.setTagCompound(legacyStackTag);
        UnlimitedCellInventory.Summary legacyItem =
                UnlimitedCellInventory.summaryOf(legacy, CellChannelRegistry.ITEM);
        UnlimitedCellInventory.Summary legacyFluid =
                UnlimitedCellInventory.summaryOf(legacy, CellChannelRegistry.FLUID);
        check(sender, "V3", "★ 旧格式（只有扁平摘要）按物品通道读入，其它通道读 0（不用迁移）",
                legacyItem.types == 7 && legacyItem.bytes.equals(BigInteger.valueOf(1234L))
                        && legacyFluid.types == 0 && legacyFluid.bytes.signum() == 0,
                "物品 7/1234、流体 0/0", "物品 " + legacyItem.types + "/" + legacyItem.bytes + "、流体 "
                        + legacyFluid.types + "/" + legacyFluid.bytes);

        // V4: the flat tags stay the item channel's, even after another channel persisted -- that is what keeps
        // the pre-multi-channel readers (our diag command, older tooltips) showing item numbers
        NBTTagCompound flatRoot = cell.getTagCompound().getCompoundTag("ae2addon:cell");
        int flatTypes = flatRoot.getInteger("_t");
        long flatBytes = flatRoot.getLong("_b");
        check(sender, "V4", "★ 流体写盘后扁平字段仍是物品通道的数（旧读取端不串味）",
                flatTypes == 1 && flatBytes == 5L, "1/5", flatTypes + "/" + flatBytes);
    }
    /** The first ore-dictionary name this stack really carries, or null when it carries none. */
    private static String firstOreName(ItemStack definition) {
        int[] ids = net.minecraftforge.oredict.OreDictionary.getOreIDs(definition);
        if (ids == null || ids.length == 0) {
            return null;
        }
        for (int id : ids) {
            String name = net.minecraftforge.oredict.OreDictionary.getOreName(id);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    /** Size the terminal would see for {@code key}, or -1 when the cell does not list it at all. */
    private static long listedSize(UnlimitedCellInventory inv, IAEItemStack key) {
        IItemList<IAEItemStack> list = AEApi.instance().storage()
                .getStorageChannel(IItemStorageChannel.class).createList();
        inv.getAvailableItems(list);
        IAEItemStack found = list.findPrecise(key);
        return found == null ? -1L : found.getStackSize();
    }

    private static long size(IAEItemStack stack) {
        return stack == null ? -1L : stack.getStackSize();
    }

    private static String describe(IAEItemStack stack) {
        return stack == null ? "null" : ("size=" + stack.getStackSize());
    }

    private void check(ICommandSender sender, String id, String what, boolean ok, String expected, String actual) {
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        String line = String.format("[%s] %s  %s", id, ok ? "PASS" : "FAIL", what);
        if (!ok) {
            line = line + String.format("   ← 期望 %s，实际 %s", expected, actual);
        }
        sender.sendMessage(new TextComponentString(line));
    }
}
