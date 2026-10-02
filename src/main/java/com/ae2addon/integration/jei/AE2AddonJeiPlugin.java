package com.ae2addon.integration.jei;

import com.ae2addon.gui.Mode2ConfigScreen;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;

/**
 * JEI integration: lets the player drag an item straight out of JEI's ingredient list onto the cell
 * config screen.
 *
 * <p>Why we register this ourselves instead of reusing AE2-UEL's hook: AE2 registers its own ghost
 * handler for {@code AEGuiHandler.getGuiContainerClass()} (see {@code appeng.integration.modules.jei.JEIPlugin}),
 * which does not cover our screen -- {@link Mode2ConfigScreen} extends the vanilla {@code GuiContainer},
 * not AE2's {@code AEBaseGui}. AE2-UEL's {@code appeng.container.interfaces.IJEIGhostIngredients} remains
 * the right hook for addons whose GUI *is* an AE2 GUI; ours is not.
 *
 * <p>This class is annotated {@code @JEIPlugin} and only ever loaded by JEI's own plugin scanner, so with
 * JEI absent nothing loads it and no defensive check is needed. It is compiled against a
 * {@code compileOnly} JEI dependency, so JEI is never bundled into our jar.
 */
@JEIPlugin
public final class AE2AddonJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        registry.addGhostIngredientHandler(Mode2ConfigScreen.class, new CellGhostHandler());
    }
}
