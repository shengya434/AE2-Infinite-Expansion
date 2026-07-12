package com.ae2addon.init;

import com.ae2addon.AE2Addon;
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
}
