package com.ae2addon.command;

import java.nio.charset.StandardCharsets;
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
