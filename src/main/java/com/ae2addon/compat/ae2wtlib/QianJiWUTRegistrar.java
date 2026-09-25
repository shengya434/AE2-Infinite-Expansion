package com.ae2addon.compat.ae2wtlib;

import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import com.ae2addon.init.ModMenuTypes;
import de.mari_023.ae2wtlib.wut.WTDefinition;
import de.mari_023.ae2wtlib.wut.WUTHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 把千机·样板终端登记成**无线通用终端（WUT）的一种状态**（2026-09-21 v273，v274 改写入方式）。
 * <p>
 * 前置事实（javap 核过 AE2WTLib 15.3.3 的字节码，不是猜的）：
 * <ul>
 *   <li>通用终端的状态表 = {@code WUTHandler.wirelessTerminals}（public），顺序表 = {@code terminalNames}（public）；</li>
 *   <li>切换按钮（通用终端界面左下角那个）按下后发 {@code CycleTerminalPacket}，
 *       服务端按 {@code terminalNames} 的顺序切到**下一个已装**状态；</li>
 *   <li>"已装"= 通用终端物品 NBT 里有以状态名为键的标记；写进去靠
 *       {@code ae2wtlib:upgrade}（通用终端 + 该终端）或 {@code ae2wtlib:combine}（两个终端）
 *       这两种数据包配方 —— 我们两份都随包发了；</li>
 *   <li>⚠ 配方序列化器会**校验** {@code terminalName} 必须在 {@code terminalNames} 里，
 *       否则整条配方判为非法（v273 就是这个连带故障：登记失败 → 配方也报
 *       "A required attribute is missing or invalid!"）。</li>
 * </ul>
 * <p>
 * **v274 的关键修正：不走 {@code WUTHandler.addTerminal}。** 它内部除了写表，还会
 * {@code HotkeyActions.register(...)}，而 AE2 的热键表在我们能跑到的时机（commonSetup）
 * 已经冻结 —— 实测日志：{@code java.lang.IllegalStateException: Hotkey registration already finalized!}，
 * 异常一抛，**整条登记（地图 put + 顺序表 add）全都没执行**。
 * 这里直接写那两个 public 表，语义与 addTerminal 的后半段一致，只是不注册那个热键
 * （热键只是"按键直接开这个终端"，与 sensei 要的循环切换无关）。
 * <p>
 * ⚠ 这个类只会被 {@link AE2WTLibCompat#isLoaded()} 为真时调用，所以没装 AE2WTLib 不会缺类。
 */
public final class QianJiWUTRegistrar {

    /** 千机终端在通用终端里的状态名（也是配方 JSON 里的 terminalName，改一处要改两处） */
    public static final String TERMINAL_NAME = "qianji";

    private QianJiWUTRegistrar() {
    }

    public static void register() {
        try {
            final Item item = ModItems.QIAN_JI_WIRELESS_TERMINAL_ITEM.get();
            if (!(item instanceof QianJiWUTItem wutItem)) {
                AE2Addon.LOGGER.warn("[ae2addon][wut] 无线终端物品不是通用终端感知子类，跳过登记（{}）", item);
                return;
            }
            if (WUTHandler.wirelessTerminals.containsKey(TERMINAL_NAME)) {
                AE2Addon.LOGGER.info("[ae2addon][wut] 通用终端状态「{}」已存在，跳过", TERMINAL_NAME);
                return;
            }

            // 图标栈照 addTerminal 的写法：通用终端物品 + "这个名字已装"的标记。
            // ⚠ 不能直接写 AE2wtlib.UNIVERSAL_TERMINAL：那个字段的类型是 ItemWUT，而它实现了
            //   Curios 的 ICurioItem → 编译期就得把 Curios 也拉进 classpath。按名字从注册表取，
            //   既不用依赖 Curios，也不引 AE2WTLib 的类。
            final Item universalTerminalItem = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .getValue(new net.minecraft.resources.ResourceLocation("ae2wtlib", "wireless_universal_terminal"));
            final ItemStack icon = universalTerminalItem == null
                    ? ItemStack.EMPTY : new ItemStack(universalTerminalItem);
            if (!icon.isEmpty()) {
                icon.getOrCreateTag().putBoolean(TERMINAL_NAME, true);
            }

            WUTHandler.wirelessTerminals.put(TERMINAL_NAME, new WTDefinition(
                    // ContainerOpener：接口默认实现 = 前置检查（绑定/耗电）→ MenuOpener.open(getMenuType(stack), …)
                    wutItem::tryOpen,
                    // 通用终端上的宿主：槽位存在通用终端物品的 NBT 里，并带量子桥那套
                    QianJiUniversalHost::new,
                    ModMenuTypes.QIAN_JI_TERMINAL.get(),
                    wutItem,
                    icon,
                    Component.translatable("item.ae2addon.qianji_wireless_terminal")));
            WUTHandler.terminalNames.add(TERMINAL_NAME);

            // ── 升级卡登记（2026-09-22 v276）──────────────────────────────
            // sensei 报"千机终端卡槽放不进升级卡"的真因：某张卡能不能装由
            // `Upgrades.getMaxInstallable(卡, 终端)` 决定（`UpgradeInventory.getMaxInstalled` 就是查它），
            // 而 AE2WTLib 是在**它自己初始化时**给"当时已知的终端"逐个登记的（UpgradeHelper.addUpgrades()）。
            // 我们的终端是之后才登记进 WUTHandler 的 → 我们这边的上限是默认 0 → 库存直接拒收。
            // 这里照它自己的数字补登记（它对通用终端本身用的就是 1）：
            //   量子桥卡 = 1（就是"远处也能连网"那张卡）
            //   能量卡   = 传 0 → 走它自己的"按各终端上限"分支（与它自家终端行为一致）
            //   磁卡     = 它只给无线合成终端和通用终端本身登记，子终端不给 → 我们也不登记
            de.mari_023.ae2wtlib.UpgradeHelper.addUpgradeToAllTerminals(
                    de.mari_023.ae2wtlib.AE2wtlib.QUANTUM_BRIDGE_CARD, 1);
            de.mari_023.ae2wtlib.UpgradeHelper.addUpgradeToAllTerminals(
                    appeng.core.definitions.AEItems.ENERGY_CARD, 0);

            AE2Addon.LOGGER.info(
                    "[ae2addon][wut] 千机·样板终端已登记为通用终端状态「{}」（共 {} 种状态；用合成台升级/合并通用终端即可装上）"
                            + " 升级卡上限：量子桥卡={} 能量卡={}",
                    TERMINAL_NAME, WUTHandler.terminalNames.size(),
                    appeng.api.upgrades.Upgrades.getMaxInstallable(
                            de.mari_023.ae2wtlib.AE2wtlib.QUANTUM_BRIDGE_CARD, item),
                    appeng.api.upgrades.Upgrades.getMaxInstallable(
                            appeng.core.definitions.AEItems.ENERGY_CARD, item));
        } catch (Throwable t) {
            AE2Addon.LOGGER.warn("[ae2addon][wut] 登记通用终端状态失败：{}", t.toString());
        }
    }
}
