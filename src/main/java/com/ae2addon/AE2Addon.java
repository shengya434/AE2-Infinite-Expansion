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
        });
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

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(com.ae2addon.command.AE2InfoCommand.class);
        MinecraftForge.EVENT_BUS.register(com.ae2addon.crafting.BatchedCraftingQueue.class);

        // ME接口（无限级）升级卡注册：容量卡（每卡+1页=9格/槽，最多2）+ 速度卡（喂出预算×2/张，最多2）
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.CAPACITY_CARD.asItem(), 2);
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.SPEED_CARD.asItem(), 2);
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.REDSTONE_CARD.asItem(), 1);
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.INVERTER_CARD.asItem(), 1);
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.CRAFTING_CARD.asItem(), 1);

        LOGGER.info("✅ AE2 Addon loaded! Universal Storage Cells ready!");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
