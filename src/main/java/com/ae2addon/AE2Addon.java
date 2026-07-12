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

    public AE2Addon() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册物品
        ModItems.ITEMS.register(modBus);
        ModItems.CREATIVE_TABS.register(modBus);

        // 注册方块
        ModBlocks.BLOCKS.register(modBus);

        // 注册方块实体
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);

        // 注册菜单类型
        ModMenuTypes.MENUS.register(modBus);

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

        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("✅ AE2 Addon loaded! Universal Storage Cells ready!");
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
