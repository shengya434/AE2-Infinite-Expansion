package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.InfiniteInterfaceScreen;
import com.ae2addon.gui.IntegratedCPUScreen;
import com.ae2addon.gui.AssemblerScreen;
import com.ae2addon.gui.Mode2ConfigScreen;
import com.ae2addon.gui.ModeSelectScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端设置：注册GUI界面
 */
@Mod.EventBusSubscriber(modid = AE2Addon.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 线缆面板 part 模型注册到 AE2（2026-09-02；需在 AE2 冻结前）
            try {
                var models = appeng.items.parts.PartModelsHelper
                        .createModels(com.ae2addon.part.InfiniteInterfacePart.class);
                appeng.api.parts.PartModels.registerModels(models);
            } catch (RuntimeException e) {
                AE2Addon.LOGGER.warn("[ae2addon] part 模型注册失败: ", e);
            }
            MenuScreens.register(ModMenuTypes.MODE_SELECT.get(), ModeSelectScreen::new);
            MenuScreens.register(ModMenuTypes.MODE2_CONFIG.get(), Mode2ConfigScreen::new);
            MenuScreens.register(ModMenuTypes.INTEGRATED_CPU.get(), IntegratedCPUScreen::new);
            MenuScreens.register(ModMenuTypes.INFINITE_INTERFACE.get(), InfiniteInterfaceScreen::new);
            MenuScreens.register(ModMenuTypes.ASSEMBLER.get(), AssemblerScreen::new);

        });
    }
}
