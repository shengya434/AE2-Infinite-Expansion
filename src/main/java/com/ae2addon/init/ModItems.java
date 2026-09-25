package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.item.CatalystItem;
import com.ae2addon.item.EternalHeartItem;
import com.ae2addon.item.FormedBlockItem;
import com.ae2addon.item.MatterBallItem;
import com.ae2addon.item.UniversalStorageCell;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AE2Addon.MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AE2Addon.MODID);

    // ── 已有物品 ──

    public static final RegistryObject<Item> ETERNAL_HEART = ITEMS.register(
            "eternal_heart",
            EternalHeartItem::new
    );

    // ── 已有方块物品 ──

    public static final RegistryObject<Item> INFINITE_CRAFTING_STORAGE_ITEM = ITEMS.register(
            "infinite_crafting_storage",
            () -> new BlockItem(ModBlocks.INFINITE_CRAFTING_STORAGE.get(), new Item.Properties())
    );
    public static final RegistryObject<Item> INFINITE_CO_PROCESSING_ITEM = ITEMS.register(
            "infinite_co_processing",
            () -> new BlockItem(ModBlocks.INFINITE_CO_PROCESSING.get(), new Item.Properties())
    );

    // ── 自建合成方块（2026-09-24）：与控制器同单元类型，用于和控制器成簇 ──

    /** 巨型存储合成单元（替换 256k 合成存储器） */
    public static final RegistryObject<Item> DENSE_STORAGE_UNIT_ITEM = ITEMS.register(
            "dense_storage_unit",
            () -> new BlockItem(ModBlocks.DENSE_STORAGE_UNIT.get(), new Item.Properties())
    );

    /** 空白存储合成单元（替换合成单元） */
    public static final RegistryObject<Item> BLANK_STORAGE_UNIT_ITEM = ITEMS.register(
            "blank_storage_unit",
            () -> new BlockItem(ModBlocks.BLANK_STORAGE_UNIT.get(), new Item.Properties())
    );

    // ── 已有元件 ──

    public static final RegistryObject<Item> UNIVERSAL_STORAGE_CELL = ITEMS.register(
            "universal_storage_cell",
            UniversalStorageCell::new
    );

    // ── 物质球（取消无限时大量物品临时存放） ──

    public static final RegistryObject<Item> MATTER_BALL = ITEMS.register(
            "matter_ball",
            MatterBallItem::new
    );

    // ── 新增方块物品 ──

    public static final RegistryObject<Item> INTEGRATED_CPU_ITEM = ITEMS.register(
            "integrated_cpu",
            () -> new BlockItem(ModBlocks.INTEGRATED_CPU.get(), new Item.Properties())
    );

    /** ME接口（无限级）方块物品 */
    public static final RegistryObject<Item> INFINITE_INTERFACE_ITEM = ITEMS.register(
            "infinite_interface",
            () -> new BlockItem(ModBlocks.INFINITE_INTERFACE.get(), new Item.Properties())
    );

    /** 装配处理器核心方块物品（v0.3 M3） */
    public static final RegistryObject<Item> ASSEMBLER_CORE_ITEM = ITEMS.register(
            "assembler_core",
            () -> new BlockItem(ModBlocks.ASSEMBLER_CORE.get(), new Item.Properties())
    );

    // ── 2.0 WIP（2026-08-11 备份恢复，2026-09-14 拾起）──

    /** 驱动器（无限级）方块物品 */
    public static final RegistryObject<Item> INFINITE_DRIVE_ITEM = ITEMS.register(
            "infinite_drive",
            () -> new BlockItem(ModBlocks.INFINITE_DRIVE.get(), new Item.Properties())
    );

    /** 千机·阿比舒（无限级）方块物品 */
    public static final RegistryObject<Item> QIAN_JI_ITEM = ITEMS.register(
            "qianji",
            () -> new BlockItem(ModBlocks.QIAN_JI.get(), new Item.Properties().fireResistant())
    );

    /**
     * 已成型变体（创造模式随取随用）：与本体共用方块 + BE 类型，
     * 只在放置瞬间把 BE 置为成型，免搭 3×3×3 / 5×5×5 结构。
     */
    public static final RegistryObject<Item> QIAN_JI_FORMED_ITEM = ITEMS.register(
            "qianji_formed",
            () -> new FormedBlockItem(ModBlocks.QIAN_JI.get(),
                    new Item.Properties().fireResistant(), "qianji_formed")
    );

    public static final RegistryObject<Item> INFINITE_DRIVE_FORMED_ITEM = ITEMS.register(
            "infinite_drive_formed",
            () -> new FormedBlockItem(ModBlocks.INFINITE_DRIVE.get(),
                    new Item.Properties(), "infinite_drive_formed")
    );

    /** 集成型CPU（无限级）· 已成型变体（子会话当时漏做了 CPU，2026-09-14 补） */
    public static final RegistryObject<Item> INTEGRATED_CPU_FORMED_ITEM = ITEMS.register(
            "integrated_cpu_formed",
            () -> new FormedBlockItem(ModBlocks.INTEGRATED_CPU.get(),
                    new Item.Properties(), "integrated_cpu_formed")
    );

    /** 催化剂三档（千机产出数量倍数 1.5/2/3.5，主产物+副产物 / 耗电倍率） */
    public static final RegistryObject<Item> CATALYST_BASIC = ITEMS.register(
            "catalyst_basic",
            () -> new CatalystItem(1)
    );
    public static final RegistryObject<Item> CATALYST_ADVANCED = ITEMS.register(
            "catalyst_advanced",
            () -> new CatalystItem(2)
    );
    public static final RegistryObject<Item> CATALYST_ULTIMATE = ITEMS.register(
            "catalyst_ultimate",
            () -> new CatalystItem(3)
    );

    /** ME接口（无限级）· 线缆面板（part，2026-09-02 sensei：装线缆上喂机器，不占格） */
    public static final RegistryObject<Item> INFINITE_INTERFACE_PANEL_ITEM = ITEMS.register(
            "infinite_interface_panel",
            () -> new appeng.items.parts.PartItem<>(
                    new Item.Properties(),
                    com.ae2addon.part.InfiniteInterfacePart.class,
                    partItem -> new com.ae2addon.part.InfiniteInterfacePart(partItem))
    );

    /** 配置存储卡：复制/粘贴接口配置（自研，绕开 AE2 内存卡限制） */
    public static final RegistryObject<Item> CONFIG_CARD = ITEMS.register(
            "config_card",
            com.ae2addon.item.ConfigCardItem::new
    );

    /**
     * 千机·样板终端（线缆面板 part，2026-09-21 v249）。
     * <p>
     * 把"样板管理终端（全网样板长列表 + 搜索）"与"编码终端（空白样板槽 → 编码样板槽）"缝在一起，
     * 只产出**千机样板**。注册方式与 {@link #INFINITE_INTERFACE_PANEL_ITEM} 完全一致
     * （AE2 的 {@code PartItem} + part 工厂）。
     */
    public static final RegistryObject<Item> QIAN_JI_TERMINAL_ITEM = ITEMS.register(
            "qianji_terminal",
            () -> new appeng.items.parts.PartItem<>(
                    new Item.Properties(),
                    com.ae2addon.part.QianJiTerminalPart.class,
                    partItem -> new com.ae2addon.part.QianJiTerminalPart(partItem))
    );

    /**
     * 千机·样板终端（**无线形态**，2026-09-21 v272）。
     * <p>
     * 与线缆面板共用同一个菜单/界面；区别只是：网络来自绑定的无线访问点、
     * 两个样板槽存在**物品 NBT** 里、按距离耗电。绑定方式与 AE2 自带无线终端一致。
     */
    public static final RegistryObject<Item> QIAN_JI_WIRELESS_TERMINAL_ITEM = ITEMS.register(
            "qianji_wireless_terminal",
            () -> com.ae2addon.compat.ae2wtlib.AE2WTLibCompat.isLoaded()
                    // 装了 AE2WTLib → 用"通用终端感知"的子类（只多实现一个接口，其余一模一样）；
                    // 没装 → 基础物品，一点 AE2WTLib 的类都不会碰到
                    ? com.ae2addon.compat.ae2wtlib.AE2WTLibItemFactory.create()
                    : new com.ae2addon.item.QianJiWirelessTerminalItem()
    );

    /** 千机配方样板（自有样板体系，2026-09-15） */
    public static final RegistryObject<Item> QIAN_JI_PATTERN = ITEMS.register(
            "qianji_pattern",
            com.ae2addon.item.QianJiPatternItem::new
    );

    /**
     * 千机样板的**底图渲染模板**（2026-09-20）：纯渲染用，玩家拿不到、**不进创造标签页**。
     * <p>
     * 为什么需要一个真实物品：千机样板的渲染器靠"按住 Shift 时显示底图"，
     * 而底图以前是一个**没被任何物品引用的模型文件**（{@code qianji_pattern_base}）→
     * 不被烘焙 → {@code ModelManager#getModel} 返回"缺失模型" → 贴图变黑紫方块（sensei 实测）。
     * 现在底图 = 这个物品的模型（{@code assets/ae2addon/models/item/qianji_pattern_template.json}），
     * 被真实物品引用 → 一定被烘焙；渲染时把它交给原版 {@code ItemRenderer#renderStatic}，
     * 位置/缩放/光照与普通物品完全一致。
     * <p>
     * ⚠ **不要**把它加进 {@code TAB_AE2ADDON} 的 `displayItems`（那不是它的用途）。
     */
    public static final RegistryObject<Item> QIANJI_PATTERN_TEMPLATE = ITEMS.register(
            "qianji_pattern_template",
            () -> new Item(new Item.Properties())
    );

    // ── 无限 xxx 元件体系（2026-09-19 sensei 定稿，见 docs/infinite-essence-cell.md）──
    // 精华与元件都用「一个物品 + NBT 绑定目标 key」，不注册上万种物品。

    /** 无限 xxx 精华：由千机「输入全是催化剂」的配方额外产出 */
    public static final RegistryObject<Item> INFINITE_ESSENCE = ITEMS.register(
            "infinite_essence",
            com.ae2addon.item.InfiniteEssenceItem::new
    );

    /** 无限 xxx 元件（物品变体）：精华 + ME物品元件外壳 → 此物 */
    public static final RegistryObject<Item> INFINITE_ITEM_CELL = ITEMS.register(
            "infinite_item_cell",
            () -> new com.ae2addon.item.BoundInfiniteCellItem(
                    com.ae2addon.item.BoundInfiniteCellItem.Kind.ITEM)
    );

    /** 无限 xxx 元件（流体变体）：精华 + ME流体元件外壳 → 此物 */
    public static final RegistryObject<Item> INFINITE_FLUID_CELL = ITEMS.register(
            "infinite_fluid_cell",
            () -> new com.ae2addon.item.BoundInfiniteCellItem(
                    com.ae2addon.item.BoundInfiniteCellItem.Kind.FLUID)
    );

    /** 无限 xxx 元件（化学品变体）：精华 + ME化学品元件外壳（Applied-Mekanistics）→ 此物 */
    public static final RegistryObject<Item> INFINITE_CHEMICAL_CELL = ITEMS.register(
            "infinite_chemical_cell",
            () -> new com.ae2addon.item.BoundInfiniteCellItem(
                    com.ae2addon.item.BoundInfiniteCellItem.Kind.CHEMICAL)
    );

    // ── 创造模式标签页 ──

    public static final RegistryObject<CreativeModeTab> TAB_AE2ADDON = CREATIVE_TABS.register(
            "ae2addon_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.ae2addon"))
                    .icon(() -> new ItemStack(ETERNAL_HEART.get()))
                    .displayItems((params, output) -> {
                        // 防御性填充（2026-08-28 12:30 崩）：任何物品计数异常都钳回 1，
                        // 单个物品失败不影响其余（ForgeHooks 对 count≠1 会硬抛）
                        acceptTabItem(output, ETERNAL_HEART.get());
                        acceptTabItem(output, UNIVERSAL_STORAGE_CELL.get());
                        acceptTabItem(output, INFINITE_CRAFTING_STORAGE_ITEM.get());
                        acceptTabItem(output, INFINITE_CO_PROCESSING_ITEM.get());
                        // 集成型CPU：**两个物品共用同一方块 id**，Block.asItem() 解析到的是
                        // **后注册的已成型变体** → 旧写法（传 ModBlocks.INTEGRATED_CPU）
                        // 实际放的是已成型，本体（未成型）漏了（2026-09-15 sensei 发现）
                        acceptTabItem(output, INTEGRATED_CPU_ITEM.get());
                        acceptTabItem(output, INTEGRATED_CPU_FORMED_ITEM.get());
                        acceptTabItem(output, ASSEMBLER_CORE_ITEM.get());
                        acceptTabItem(output, INFINITE_INTERFACE_ITEM.get());
                        acceptTabItem(output, INFINITE_INTERFACE_PANEL_ITEM.get());
                        // 方块物品显式传 Item：两个物品共用同一方块 id，
                        // Block.asItem() 的结果由注册顺序决定，不能依赖
                        acceptTabItem(output, INFINITE_DRIVE_ITEM.get());
                        acceptTabItem(output, INFINITE_DRIVE_FORMED_ITEM.get());
                        acceptTabItem(output, QIAN_JI_ITEM.get());
                        acceptTabItem(output, QIAN_JI_FORMED_ITEM.get());
                        acceptTabItem(output, CATALYST_BASIC.get());
                        acceptTabItem(output, CATALYST_ADVANCED.get());
                        acceptTabItem(output, CATALYST_ULTIMATE.get());
                        acceptTabItem(output, QIAN_JI_PATTERN.get());
                        // 千机·样板终端（2026-09-21：sensei 报"没写进创造标签页"）
                        acceptTabItem(output, QIAN_JI_TERMINAL_ITEM.get());
                        // 千机·样板无线终端（2026-09-21 v272）
                        acceptTabItem(output, QIAN_JI_WIRELESS_TERMINAL_ITEM.get());
                        acceptTabItem(output, CONFIG_CARD.get());
                        acceptTabItem(output, MATTER_BALL.get());
                        acceptTabItem(output, INFINITE_ESSENCE.get());
                        // 自建合成方块（2026-09-24）：巨型存储合成单元 / 空白存储合成单元
                        acceptTabItem(output, DENSE_STORAGE_UNIT_ITEM.get());
                        acceptTabItem(output, BLANK_STORAGE_UNIT_ITEM.get());
                        acceptTabItem(output, INFINITE_ITEM_CELL.get());
                        acceptTabItem(output, INFINITE_FLUID_CELL.get());
                        acceptTabItem(output, INFINITE_CHEMICAL_CELL.get());
                    })
                    .build()
    );

    /**
     * 创造标签页安全添加：显式构造 ItemStack 并保证 count=1
     * （ForgeHooks 对 count≠1 抛 IllegalArgumentException）。
     * 单物品异常只跳过该物品并记日志，绝不崩游戏。
     */
    private static void acceptTabItem(net.minecraft.world.item.CreativeModeTab.Output output,
            net.minecraft.world.level.ItemLike item) {
        try {
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
            if (stack.isEmpty() || stack.getCount() != 1) {
                // isEmpty（Forge：delegate==AIR 即空，物品未注册）或计数异常：跳过并记日志
                com.ae2addon.AE2Addon.LOGGER.warn(
                        "[ae2addon] 创造标签页跳过 {}: isEmpty={} count={}（物品可能未注册）",
                        item, stack.isEmpty(), stack.getCount());
                return;
            }
            output.accept(stack);
        } catch (RuntimeException e) {
            com.ae2addon.AE2Addon.LOGGER.warn(
                    "[ae2addon] 创造标签页添加失败，已跳过: {}", item, e);
        }
    }
}
