package com.ae2addon;

import com.ae2addon.init.ModBlockEntities;
import com.ae2addon.init.ModBlocks;
import com.ae2addon.init.ModItems;
import com.ae2addon.init.ModMenuTypes;
import com.ae2addon.network.Mode2ConfigPacket;
import com.ae2addon.network.SetCellModePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(AE2Addon.MODID)
public class AE2Addon {
    public static final String MODID = "ae2addon";
    public static final Logger LOGGER = LogManager.getLogger();

    private static final String PROTOCOL_VERSION = "1";

    // 网络通道
    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        // 2026-09-20：把**千机样板**注册成 AE2 认可的样板（解码器）。
        // 不做这一步，PatternDetailsHelper.isEncodedPattern(千机样板) 恒为假 →
        // EAEP 的「上传样板」、样板管理终端、其它 mod 的样板工具全都把我们当普通物品
        //（逐个 mixin 放宽过滤只是治标，这一步才治本）。
        try {
            appeng.api.crafting.PatternDetailsHelper.registerDecoder(
                    new com.ae2addon.crafting.QianJiPatternDecoder());
            LOGGER.info("[ae2addon] 已注册千机样板解码器（AE2 的 isEncodedPattern/decodePattern 现在认得千机样板）");
        } catch (Throwable t) {
            LOGGER.warn("[ae2addon] 注册千机样板解码器失败：{}", t.toString());
        }
        event.enqueueWork(() -> {
            // 注册集成 CPU 菜单 opener（AE2 locator 协议）
            appeng.menu.MenuOpener.addOpener(
                    ModMenuTypes.INTEGRATED_CPU.get(),
                    com.ae2addon.gui.IntegratedCPUMenu::openMenu);
            // 千机·样板终端 opener（2026-09-21 v272：无线终端要靠它才打得开）
            registerTerminalOpener();
            // 无线终端要能放进无线访问点的链接槽（2026-09-21 v273）
            registerGridLinkable();
            // 通用终端（AE2WTLib）集成：把千机终端登记成通用终端的一种状态（2026-09-21 v273）
            if (com.ae2addon.compat.ae2wtlib.AE2WTLibCompat.isLoaded()) {
                com.ae2addon.compat.ae2wtlib.QianJiWUTRegistrar.register();
            } else {
                LOGGER.info("[ae2addon][wut] 没装 AE2WTLib，跳过通用终端集成（千机终端照旧能用）");
            }
            dumpRegistrations();
            dumpKeyTypes();
        });
    }

    /**
     * 把无线千机·样板终端登记成 AE2 认可的**可链接物品**（2026-09-21 v273）。
     * <p>
     * 为什么必须显式登记（javap 核对 15.4.10 的字节码）：无线访问点的链接槽是
     * {@code RestrictedInputSlot} 的 {@code GRID_LINKABLE_ITEM} 分支，它按
     * **物品对象**查处理器 —— {@code GridLinkables.get(stack.getItem())} ——
     * 而 AE2 只在 {@code InitGridLinkables} 里给 {@code ae2:wireless_terminal} 与
     * {@code ae2:wireless_crafting_terminal} 各注册了一份。
     * 我们的物品虽然**继承**了 {@code WirelessTerminalItem}，但**不会自动继承这份登记** →
     * 表现就是 sensei 报的"放不进无线访问点的链接槽位"（顺带也永远绑不上访问点）。
     * 这里补上登记，处理器直接用 AE2 自己那份（{@code canLink} 判 instanceof，
     * {@code link} 写 {@code accessPoint} NBT —— 与 AE2 无线终端完全一致）。
     */
    private static void registerGridLinkable() {
        try {
            appeng.api.features.GridLinkables.register(
                    ModItems.QIAN_JI_WIRELESS_TERMINAL_ITEM.get(),
                    appeng.items.tools.powered.WirelessTerminalItem.LINKABLE_HANDLER);
            LOGGER.info("[ae2addon] 无线终端已登记为可链接物品（可以放进无线访问点的链接槽了）");
        } catch (Throwable t) {
            LOGGER.warn("[ae2addon] 登记可链接物品失败：{}", t.toString());
        }
    }

    /**
     * 给千机·样板终端注册 AE2 的菜单 opener（2026-09-21 v272）。
     * <p>
     * 无线终端右键走的是 AE2 自己的链路：{@code WirelessTerminalItem#use} →
     * {@code MenuOpener.open(getMenuType(), player, locator)}。没注册 opener 的话，
     * AE2 只会在日志留一句 "Trying to open menu for unknown menu type" 然后什么都不发生。
     * <p>
     * ⚠ 2026-09-21 v273：终端菜单改成继承 AE2 的 {@code AEBaseMenu}（通用终端的切换包要求），
     * 所以这里不再需要 v272 那个"原生类型转换"绕开泛型上界的写法了。
     */
    private static void registerTerminalOpener() {
        try {
            appeng.menu.MenuOpener.addOpener(
                    ModMenuTypes.QIAN_JI_TERMINAL.get(),
                    com.ae2addon.gui.QianJiTerminalMenu::openTerminal);
            LOGGER.info("[ae2addon] 千机·样板终端 opener 已注册（线缆面板 + 无线终端 + 通用终端共用）");
        } catch (Throwable t) {
            LOGGER.warn("[ae2addon] 注册千机·样板终端 opener 失败：{}", t.toString());
        }
    }

    /**
     * 键类型自检（2026-09-19）：把已注册的 AE 键类型摊出来。
     * <p>
     * 为什么需要：化学品（Applied-Mekanistics）兼容是**按注册名**探测的
     * （{@code appmek:chemical}），而这个 id 是我从对方 jar 的 lang/模型文件名推出来的。
     * 万一真实 id 不同，这行日志会直接给出答案 —— 不用进游戏猜、也不用截图。
     */
    private static void dumpKeyTypes() {
        try {
            var sb = new StringBuilder();
            for (var type : appeng.api.stacks.AEKeyTypes.getAll()) {
                sb.append(type.getId()).append(' ');
            }
            LOGGER.info("[ae2addon] 已注册的 AE 键类型: {}", sb.toString().trim());
            LOGGER.info("[ae2addon] {}", com.ae2addon.compat.ChemicalCompat.describe());
        } catch (Throwable t) {
            LOGGER.warn("[ae2addon] 键类型自检失败: {}", t.toString());
        }
    }

    /** 注册表自检（2026-08-28：创造标签页物品缺失排查）。 */
    private static void dumpRegistrations() {
        try {
            var block = ModBlocks.INFINITE_INTERFACE.get();
            var item = ModItems.INFINITE_INTERFACE_ITEM.get();
            var registered = net.minecraftforge.registries.ForgeRegistries.ITEMS
                    .containsValue(item);
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
            var byBlock = net.minecraft.world.item.Item.byBlock(block);
            var asItem = block.asItem();
            LOGGER.info("[ae2addon] 注册自检: block={} item={} itemInRegistry={} key={} byBlock={} asItem={}",
                    block, item, registered, key, byBlock, asItem);
            LOGGER.info("[ae2addon] 注册自检: ITEMS.containsKey(ae2addon:infinite_interface)={}",
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(
                            new ResourceLocation(MODID, "infinite_interface")));
        } catch (RuntimeException e) {
            LOGGER.warn("[ae2addon] 注册自检失败", e);
        }
    }

    public AE2Addon() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册配置文件（config/ae2addon-common.toml，2026-08-27；热加载）
        com.ae2addon.config.AE2AddonConfig.register();
        modBus.addListener(com.ae2addon.config.AE2AddonConfig::onConfigEvent);

        // 游戏内配置界面（mod 列表 → 配置按钮，2026-08-27 21:46）：
        // 自研轻量界面（Forge 1.20.1 无内置通用配置 GUI），保存即写盘 + 热加载。
        // 用全限定名 + dist 检查：dedicated server 不加载 client 类。
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            screen -> new com.ae2addon.client.gui.AE2AddonConfigScreen(screen)));
            // 线缆面板 part 模型注册（2026-09-02）：必须早于 AE2 的
            // PartModels.freeze()（ModelEvent.RegisterAdditional）；此处为最早时机。
            try {
                var partModels = appeng.items.parts.PartModelsHelper
                        .createModels(com.ae2addon.part.InfiniteInterfacePart.class);
                appeng.api.parts.PartModels.registerModels(partModels);
                LOGGER.info("[ae2addon] part 模型已注册: {}", partModels.size());
            } catch (Throwable t) {
                LOGGER.warn("[ae2addon] part 模型注册失败(构造期): ", t);
            }
            // 千机·样板终端（线缆面板 part，2026-09-21 v249）：同样必须早于 PartModels.freeze()
            try {
                var terminalModels = appeng.items.parts.PartModelsHelper
                        .createModels(com.ae2addon.part.QianJiTerminalPart.class);
                appeng.api.parts.PartModels.registerModels(terminalModels);
                LOGGER.info("[ae2addon] 终端 part 模型已注册: {}", terminalModels.size());
            } catch (Throwable t) {
                LOGGER.warn("[ae2addon] 终端 part 模型注册失败(构造期): ", t);
            }
        }

        // 注册物品
        ModItems.ITEMS.register(modBus);
        ModItems.CREATIVE_TABS.register(modBus);

        // 注册方块
        ModBlocks.BLOCKS.register(modBus);

        // 注册方块实体
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);

        // 注册菜单类型
        ModMenuTypes.MENUS.register(modBus);

        // 注册配方序列化器（2026-09-19：无限精华 + 元件外壳 → 无限 xxx 元件）
        com.ae2addon.init.ModRecipes.SERIALIZERS.register(modBus);
        // 配方类型也必须走 DeferredRegister：直接用 RecipeType.register(...) 会在（已冻结的）
        // 内置注册表上写 → 启动即崩（2026-09-19 实锤，见 ModRecipes.RECIPE_TYPES 的注释）
        com.ae2addon.init.ModRecipes.RECIPE_TYPES.register(modBus);

        // 集成 CPU 菜单 opener 延迟到注册表就绪后注册（FMLCommonSetupEvent）
        modBus.addListener(this::onCommonSetup);

        // 注册网络数据包
        NETWORK.registerMessage(0, SetCellModePacket.class,
                SetCellModePacket::encode,
                SetCellModePacket::decode,
                SetCellModePacket::handle
        );
        NETWORK.registerMessage(1, Mode2ConfigPacket.class,
                Mode2ConfigPacket::encode,
                Mode2ConfigPacket::decode,
                Mode2ConfigPacket::handle
        );
        NETWORK.registerMessage(2, com.ae2addon.network.LaneListPacket.class,
                com.ae2addon.network.LaneListPacket::encode,
                com.ae2addon.network.LaneListPacket::decode,
                com.ae2addon.network.LaneListPacket::handle
        );
        NETWORK.registerMessage(3, com.ae2addon.network.OrderListPacket.class,
                com.ae2addon.network.OrderListPacket::encode,
                com.ae2addon.network.OrderListPacket::decode,
                com.ae2addon.network.OrderListPacket::handle
        );
        NETWORK.registerMessage(4, com.ae2addon.network.FeederStatusPacket.class,
                com.ae2addon.network.FeederStatusPacket::encode,
                com.ae2addon.network.FeederStatusPacket::decode,
                com.ae2addon.network.FeederStatusPacket::handle
        );
        NETWORK.registerMessage(5, com.ae2addon.network.FeederSettingPacket.class,
                com.ae2addon.network.FeederSettingPacket::encode,
                com.ae2addon.network.FeederSettingPacket::decode,
                com.ae2addon.network.FeederSettingPacket::handle
        );
        NETWORK.registerMessage(6, com.ae2addon.network.FeederMarkPacket.class,
                com.ae2addon.network.FeederMarkPacket::encode,
                com.ae2addon.network.FeederMarkPacket::decode,
                com.ae2addon.network.FeederMarkPacket::handle
        );
        NETWORK.registerMessage(7, com.ae2addon.network.FeederTargetPacket.class,
                com.ae2addon.network.FeederTargetPacket::encode,
                com.ae2addon.network.FeederTargetPacket::decode,
                com.ae2addon.network.FeederTargetPacket::handle
        );
        NETWORK.registerMessage(8, com.ae2addon.network.FeederTogglePacket.class,
                com.ae2addon.network.FeederTogglePacket::encode,
                com.ae2addon.network.FeederTogglePacket::decode,
                com.ae2addon.network.FeederTogglePacket::handle
        );
        NETWORK.registerMessage(9, com.ae2addon.network.AssemblerPagePacket.class,
                com.ae2addon.network.AssemblerPagePacket::encode,
                com.ae2addon.network.AssemblerPagePacket::decode,
                com.ae2addon.network.AssemblerPagePacket::handle
        );
        NETWORK.registerMessage(10, com.ae2addon.network.FeederReturnPacket.class,
                com.ae2addon.network.FeederReturnPacket::encode,
                com.ae2addon.network.FeederReturnPacket::decode,
                com.ae2addon.network.FeederReturnPacket::handle
        );

        NETWORK.registerMessage(11, com.ae2addon.network.QianJiPatternPacket.class,
                com.ae2addon.network.QianJiPatternPacket::encode,
                com.ae2addon.network.QianJiPatternPacket::decode,
                com.ae2addon.network.QianJiPatternPacket::handle
        );

        // 千机 GUI 搜索（2026-09-21 v244）：12 = 请求（C→S，只发关键词），13 = 结果（S→C，只回命中）
        NETWORK.registerMessage(12, com.ae2addon.network.QianJiSearchRequestPacket.class,
                com.ae2addon.network.QianJiSearchRequestPacket::encode,
                com.ae2addon.network.QianJiSearchRequestPacket::decode,
                com.ae2addon.network.QianJiSearchRequestPacket::handle
        );
        NETWORK.registerMessage(13, com.ae2addon.network.QianJiSearchResultPacket.class,
                com.ae2addon.network.QianJiSearchResultPacket::encode,
                com.ae2addon.network.QianJiSearchResultPacket::decode,
                com.ae2addon.network.QianJiSearchResultPacket::handle
        );

        // 千机·样板终端（2026-09-21 v254）：14 = 查询（C→S，关键词 + 窗口），15 = 回包（S→C，一段列表）
        NETWORK.registerMessage(14, com.ae2addon.network.QianJiTerminalQueryPacket.class,
                com.ae2addon.network.QianJiTerminalQueryPacket::encode,
                com.ae2addon.network.QianJiTerminalQueryPacket::decode,
                com.ae2addon.network.QianJiTerminalQueryPacket::handle
        );
        NETWORK.registerMessage(15, com.ae2addon.network.QianJiTerminalPagePacket.class,
                com.ae2addon.network.QianJiTerminalPagePacket::encode,
                com.ae2addon.network.QianJiTerminalPagePacket::decode,
                com.ae2addon.network.QianJiTerminalPagePacket::handle
        );
        // 16 = 终端的"取 / 放"动作（C→S，2026-09-21 v256）
        NETWORK.registerMessage(16, com.ae2addon.network.QianJiTerminalActionPacket.class,
                com.ae2addon.network.QianJiTerminalActionPacket::encode,
                com.ae2addon.network.QianJiTerminalActionPacket::decode,
                com.ae2addon.network.QianJiTerminalActionPacket::handle
        );
        // 17 = 「样板插进千机了 → 列表跳过去并高亮 3 秒」（S→C，2026-09-22 v277）
        NETWORK.registerMessage(17, com.ae2addon.network.QianJiTerminalFocusPacket.class,
                com.ae2addon.network.QianJiTerminalFocusPacket::encode,
                com.ae2addon.network.QianJiTerminalFocusPacket::decode,
                com.ae2addon.network.QianJiTerminalFocusPacket::handle
        );
        // 18 = 「一键成型」请求（C→S，2026-09-24：集成 CPU 从网络取料自动搭建结构）
        NETWORK.registerMessage(18, com.ae2addon.network.IntegratedCpuBuildPacket.class,
                com.ae2addon.network.IntegratedCpuBuildPacket::encode,
                com.ae2addon.network.IntegratedCpuBuildPacket::decode,
                com.ae2addon.network.IntegratedCpuBuildPacket::handle
        );
        // 19 = 「放置被阻挡」红框位置（S→C，2026-09-24：客户端渲染描边，关深度测试才穿墙）
        NETWORK.registerMessage(19, com.ae2addon.network.IntegratedCpuConflictPacket.class,
                com.ae2addon.network.IntegratedCpuConflictPacket::encode,
                com.ae2addon.network.IntegratedCpuConflictPacket::decode,
                com.ae2addon.network.IntegratedCpuConflictPacket::handle
        );
        // 20 = 集成 CPU 网络侧展示信息（S→C，2026-09-24：AE2 自己的 CPU 列表拿不到我们的线程/存储）
        NETWORK.registerMessage(20, com.ae2addon.network.IntegratedCpuStatusPacket.class,
                com.ae2addon.network.IntegratedCpuStatusPacket::encode,
                com.ae2addon.network.IntegratedCpuStatusPacket::decode,
                com.ae2addon.network.IntegratedCpuStatusPacket::handle
        );
        NETWORK.registerMessage(21, com.ae2addon.network.IntegratedCpuRingPacket.class,
                com.ae2addon.network.IntegratedCpuRingPacket::encode,
                com.ae2addon.network.IntegratedCpuRingPacket::decode,
                com.ae2addon.network.IntegratedCpuRingPacket::handle
        );
        NETWORK.registerMessage(22, com.ae2addon.network.IntegratedCpuOutlinePacket.class,
                com.ae2addon.network.IntegratedCpuOutlinePacket::encode,
                com.ae2addon.network.IntegratedCpuOutlinePacket::decode,
                com.ae2addon.network.IntegratedCpuOutlinePacket::handle
        );

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(com.ae2addon.command.AE2InfoCommand.class);
        MinecraftForge.EVENT_BUS.register(com.ae2addon.command.QianJiPatternCommand.class);
        // 测试指令：/ae2essence 造「无限 xxx 精华 / 元件」（2026-09-19 无限元件体系）
        MinecraftForge.EVENT_BUS.register(com.ae2addon.command.EssenceCommand.class);
        MinecraftForge.EVENT_BUS.register(com.ae2addon.crafting.BatchedCraftingQueue.class);
        // 爆炸配方：东西扔地上炸一下 → 产物（2026-09-19 sensei 方案 A；
        // AE2 原生 ae2:transform 不支持数量，见 ExplosionRecipe 类注释）
        MinecraftForge.EVENT_BUS.register(com.ae2addon.crafting.ExplosionRecipeHandler.class);
        // 任务值取证：不在这里注册 —— TaskValueProbe 由 BatchedCraftingQueue.onServerTick 直接调用
        // （2026-09-18 实测：本类自己注册 EVENT_BUS 没被调用，挂到已验证的钩子上才可靠）

        // 升级卡注册统一在 ensureCompatUpgrades（BE 构造时触发）：此时所有注册表
        // 就绪，block.asItem() 能正确解析——注册阶段执行会命中 asItem 毒缓存
        // （物品未注册 → AIR）导致 Upgrades key 错位、卡片计数全 0（16:24 实锤）

        LOGGER.info("✅ AE2 Addon loaded! Universal Storage Cells ready!");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }

    /** 升级卡统一懒注册（BE 构造时触发；所有注册表已就绪，asItem() 正确）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean COMPAT_UPGRADES =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 世界加载时（BE 构造）调用；此时所有 mod 的注册表已就绪。 */
    public static void ensureCompatUpgrades() {
        if (COMPAT_UPGRADES.get()) {
            return; // 已成功注册
        }
        try {
            var block = ModBlocks.INFINITE_INTERFACE.get();
            // 升级卡注册到两个宿主：方块版 + 线缆面板 part（2026-09-02 part 卡无效根因）
            // ⚠️ Upgrades.add(upgrade, supportedBy, max)：卡片在前，机器在后！
            registerInterfaceUpgrades(block);
            var partItem = com.ae2addon.init.ModItems.INFINITE_INTERFACE_PANEL_ITEM.get();
            registerInterfaceUpgrades(partItem);
            // 速度卡：喂出预算 ×2/张（part 版已实现；方块版沿用注释意图——注册上供网络工具卡槽可用）
            appeng.api.upgrades.Upgrades.add(
                    appeng.core.definitions.AEItems.SPEED_CARD.asItem(), block, 2);
            appeng.api.upgrades.Upgrades.add(
                    appeng.core.definitions.AEItems.SPEED_CARD.asItem(), partItem, 2);
            COMPAT_UPGRADES.set(true); // 全部成功才置位
            LOGGER.info("[ae2addon] 升级卡注册完成（{} 张卡类型）",
                    appeng.api.upgrades.Upgrades.getMaxInstallable(
                            appeng.core.definitions.AEItems.CAPACITY_CARD.asItem(), block.asItem()));
        } catch (Throwable t) {
            // 失败不置位：下次 BE 构造重试，并打堆栈定位
            LOGGER.warn("[ae2addon] 升级卡注册失败（将重试）", t);
        }
    }

    /** 给某机器 item 注册 AE2 自带卡 + 跨 mod 卡（容量/红石/反向/合成/感应/频道/虚拟）。 */
    private static void registerInterfaceUpgrades(net.minecraft.world.level.ItemLike machine) {
        try {
            appeng.api.upgrades.Upgrades.add(
                    appeng.core.definitions.AEItems.CAPACITY_CARD.asItem(), machine, 4);
            appeng.api.upgrades.Upgrades.add(
                    appeng.core.definitions.AEItems.REDSTONE_CARD.asItem(), machine, 1);
            appeng.api.upgrades.Upgrades.add(
                    appeng.core.definitions.AEItems.INVERTER_CARD.asItem(), machine, 1);
            appeng.api.upgrades.Upgrades.add(
                    appeng.core.definitions.AEItems.CRAFTING_CARD.asItem(), machine, 1);
            var inductionCard = com.ae2addon.compat.AppFluxPowerCompat.inductionCard();
            if (inductionCard != null) {
                appeng.api.upgrades.Upgrades.add(inductionCard, machine, 1);
            }
            var channelCard = com.ae2addon.compat.ExtendedAEPlusCompat.channelCard();
            if (channelCard != null) {
                appeng.api.upgrades.Upgrades.add(channelCard, machine, 1);
            }
            var virtualCard = com.ae2addon.compat.ExtendedAEPlusCompat.virtualCraftingCard();
            if (virtualCard != null) {
                appeng.api.upgrades.Upgrades.add(virtualCard, machine, 1);
            }
        } catch (Throwable ignored) {
            // 单宿主失败不阻塞另一个
        }
    }
}
