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

        // 升级卡注册移到方块注册完成后（onBlocksRegistered）；AppFlux/ExtendedAE+
        // 的卡在运行时懒注册（ensureCompatUpgrades，BE 构造时触发）
        modBus.addListener(this::onBlocksRegistered);

        LOGGER.info("✅ AE2 Addon loaded! Universal Storage Cells ready!");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }

    /**
     * 方块注册完成后注册升级卡（2026-08-28 崩溃修复）：
     * 构造期 ModBlocks.XXX.get() 会抛 "Registry Object not present" NPE
     * （方块要等 REGISTER 事件才进注册表），必须延迟到这里。
     */
    private void onBlocksRegistered(net.minecraftforge.registries.RegisterEvent event) {
        if (event.getRegistryKey() != net.minecraft.core.registries.Registries.BLOCK) {
            return;
        }
        // AE2 自带卡片（AE2 物品始终可用）
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.CAPACITY_CARD.asItem(), 4);
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.REDSTONE_CARD.asItem(), 1);
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.INVERTER_CARD.asItem(), 1);
        appeng.api.upgrades.Upgrades.add(
                ModBlocks.INFINITE_INTERFACE.get(),
                appeng.core.definitions.AEItems.CRAFTING_CARD.asItem(), 1);
    }

    /** 跨 mod 升级卡懒注册（AppFlux 感应卡 / ExtendedAE+ 频道卡、虚拟合成卡）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean COMPAT_UPGRADES =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 世界加载时（BE 构造）调用；此时所有 mod 的注册表已就绪。 */
    public static void ensureCompatUpgrades() {
        if (COMPAT_UPGRADES.getAndSet(true)) {
            return; // 只注册一次
        }
        // AppFlux 感应卡（供电卡；未装 AppFlux 时为 null 跳过）
        var inductionCard = com.ae2addon.compat.AppFluxPowerCompat.inductionCard();
        if (inductionCard != null) {
            appeng.api.upgrades.Upgrades.add(
                    ModBlocks.INFINITE_INTERFACE.get(), inductionCard, 1);
        }
        // ExtendedAE+ 频道卡（无线连网） + 虚拟合成卡（末批即完成）
        var channelCard = com.ae2addon.compat.ExtendedAEPlusCompat.channelCard();
        if (channelCard != null) {
            appeng.api.upgrades.Upgrades.add(
                    ModBlocks.INFINITE_INTERFACE.get(), channelCard, 1);
        }
        var virtualCard = com.ae2addon.compat.ExtendedAEPlusCompat.virtualCraftingCard();
        if (virtualCard != null) {
            appeng.api.upgrades.Upgrades.add(
                    ModBlocks.INFINITE_INTERFACE.get(), virtualCard, 1);
        }
    }
}
