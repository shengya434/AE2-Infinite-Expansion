package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.gui.InfiniteInterfaceMenu;
import com.ae2addon.gui.IntegratedCPUMenu;
import com.ae2addon.gui.Mode2ConfigMenu;
import com.ae2addon.gui.ModeSelectMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 菜单类型注册
 */
public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, AE2Addon.MODID);

    // 模式选择界面
    public static final RegistryObject<MenuType<ModeSelectMenu>> MODE_SELECT =
            MENUS.register("mode_select",
                    () -> IForgeMenuType.create(ModeSelectMenu::fromNetwork));

    // 模式2配置界面
    public static final RegistryObject<MenuType<Mode2ConfigMenu>> MODE2_CONFIG =
            MENUS.register("mode2_config",
                    () -> IForgeMenuType.create(Mode2ConfigMenu::fromNetwork));

    // 集成 CPU 状态界面（量子分裂线程列表）
    public static final RegistryObject<MenuType<IntegratedCPUMenu>> INTEGRATED_CPU =
            MENUS.register("integrated_cpu",
                    () -> IForgeMenuType.create(IntegratedCPUMenu::fromNetwork));

    // ME 接口（无限级）配置界面
    public static final RegistryObject<MenuType<InfiniteInterfaceMenu>> INFINITE_INTERFACE =
            MENUS.register("infinite_interface",
                    () -> IForgeMenuType.create(InfiniteInterfaceMenu::fromNetwork));

}
