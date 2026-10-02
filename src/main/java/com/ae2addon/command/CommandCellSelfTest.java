package com.ae2addon.command;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.CellStorageData;
import com.ae2addon.cell.UnlimitedCellInventory;
import com.ae2addon.data.CellWorldData;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
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
        boolean b3s2clear = b2.rawData().s2.isEmpty();
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

        // ---- B8: switching modes must not lose counts ----
        UnlimitedCellInventory b8 = newInventory("b8");
        b8.setMode(1);
        b8.injectItems(stack(Items.STICK, 12345L), Actionable.MODULATE, NO_SOURCE);
        long b8m1 = b8.storedAmount(stick).longValue();
        b8.setMode(2);
        b8.setThreshold(1000000L);
        b8.setWorkMode(1);
        long b8m2 = b8.storedAmount(stick).longValue();
        b8.setMode(1);
        long b8back = b8.storedAmount(stick).longValue();
        check(sender, "B8", "★ 模式切换不丢数：mode1→mode2→mode1 三次读数都是 12345",
                b8m1 == 12345L && b8m2 == 12345L && b8back == 12345L, "12345/12345/12345",
                b8m1 + "/" + b8m2 + "/" + b8back);

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
        check(sender, "B11", "★ v1(P1) 数据迁移：12345 进 mode1 存储，且版本升到 2",
                b11stored == 12345L && b11version == 2, "12345/2", b11stored + "/" + b11version);

        runRejectGroup(sender);
        runRuleGroup(sender);
        runTerminalGroup(sender);

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
        data.s1.clear();
        data.s2.clear();
        data.ca.clear();
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
        boolean d6s2clear = d6.rawData().s2.isEmpty();
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
            if (row.key.isSameType(stick) && row.amount.signum() == 0 && row.itemThreshold == 123L) {
                d7zero = true;
            }
            if (row.key.isSameType(key(Items.PAPER)) && row.isInfinite) {
                d7paper = true;
            }
            if (row.key.isSameType(diamond) && row.amount.longValue() == 5L && !row.isInfinite) {
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

        // E3: rule promotion writes ca only, never ul -- so ca must be reported too
        UnlimitedCellInventory e3 = newInventory("e3");
        e3.setMode(2);
        e3.setWorkMode(1);
        e3.addModRule("minecraft");
        e3.setRuleMode(UnlimitedCellInventory.ruleKey(false, "minecraft"), 2);
        e3.injectItems(stack(Items.STICK, 3L), Actionable.MODULATE, NO_SOURCE); // Touch rule -> ca
        long e3seen = listedSize(e3, key(Items.STICK));
        check(sender, "E3", "规则推进（只写 ca、不进 ul）的物品在终端列表里可见", e3seen == infinite,
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
        check(sender, "E6", "mode3 的 getStoredItemTypes 与终端实际列出的条目数一致",
                e6.getStoredItemTypes() == e6list.size() && e6list.size() == 2,
                "list=2", "types=" + e6.getStoredItemTypes() + " list=" + e6list.size());
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
