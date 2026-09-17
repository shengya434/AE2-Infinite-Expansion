package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.InfiniteInterfaceScreen;
import com.ae2addon.gui.IntegratedCPUScreen;
import com.ae2addon.gui.AssemblerScreen;
import com.ae2addon.gui.InfiniteDriveScreen;
import com.ae2addon.gui.QianJiScreen;
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
        // ⚠ 2026-09-17：这里**原来重复注册了一次 part 模型**，每次启动必然失败并刷一条警告 ——
        // 真正的注册在 AE2Addon 的构造期（日志里的「part 模型已注册: 1」），早于 AE2 的
        // PartModels.freeze()（ModelEvent.RegisterAdditional）；而 enqueueWork 的活儿要等所有 mod
        // 的 client setup 都跑完才执行，那时模型表早冻结了 → 必然抛「Cannot register models after
        // the pre-initialization phase!」。实测（sensei 2026-09-17）：面板形态游戏内一切正常，
        // 说明这条重复注册纯属噪声，删掉即可。
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.MODE_SELECT.get(), ModeSelectScreen::new);
            MenuScreens.register(ModMenuTypes.MODE2_CONFIG.get(), Mode2ConfigScreen::new);
            MenuScreens.register(ModMenuTypes.INTEGRATED_CPU.get(), IntegratedCPUScreen::new);
            MenuScreens.register(ModMenuTypes.INFINITE_INTERFACE.get(), InfiniteInterfaceScreen::new);
            MenuScreens.register(ModMenuTypes.ASSEMBLER.get(), AssemblerScreen::new);
            MenuScreens.register(ModMenuTypes.QIAN_JI.get(), QianJiScreen::new);
            MenuScreens.register(ModMenuTypes.INFINITE_DRIVE.get(), InfiniteDriveScreen::new);

        });
    }
}
