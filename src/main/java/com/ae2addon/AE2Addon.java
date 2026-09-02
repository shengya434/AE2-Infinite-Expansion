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
        event.enqueueWork(() -> {
            // 注册集成 CPU 菜单 opener（AE2 locator 协议）
            appeng.menu.MenuOpener.addOpener(
                    ModMenuTypes.INTEGRATED_CPU.get(),
                    com.ae2addon.gui.IntegratedCPUMenu::openMenu);
            dumpRegistrations();
        });
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

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(com.ae2addon.command.AE2InfoCommand.class);
        MinecraftForge.EVENT_BUS.register(com.ae2addon.crafting.BatchedCraftingQueue.class);

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
