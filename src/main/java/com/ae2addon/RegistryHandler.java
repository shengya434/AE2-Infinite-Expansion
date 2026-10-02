package com.ae2addon;

import com.ae2addon.item.ItemUnlimitedCell;

import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Common-side registry events.
 *
 * <p>Wiring fact that cost a crash on 2026-10-02: in 1.12.2 the registry events are posted on the
 * <b>Forge bus</b>, not on the per-mod bus. Source evidence:
 * {@code GameData.fireRegistryEvents()} calls
 * {@code MinecraftForge.EVENT_BUS.post(...getRegisterEvent(ITEMS))} -- it posts to
 * {@code MinecraftForge.EVENT_BUS} and nowhere else, for blocks, items and every other registry.
 *
 * <p>Consequences:
 * <ul>
 * <li>A {@code @SubscribeEvent} method on the {@code @Mod} class instance does NOT receive
 * {@code RegistryEvent}: FMLModContainer registers the instance on the mod's own bus
 * ({@code eventBus.register(this)}), while {@code @Mod.EventBusSubscriber} classes are registered on
 * {@code MinecraftForge.EVENT_BUS} (FML's AutomaticEventSubscriber). Putting the item registration on
 * the mod instance silently registers nothing -- the item stays null and the model event then throws
 * an NPE inside ModelLoader.setCustomModelResourceLocation.</li>
 * <li>Order is safe for us: {@code Loader.preinitializeMods()} fires the registry events at line 630
 * and {@code fireSidedRegistryEvents()} (ModelRegistryEvent) at line 631, both after every mod's
 * preInit -- so {@code AE2Addon.LOG} is already available here.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AE2Addon.MODID)
public final class RegistryHandler {

    private RegistryHandler() {
    }

    @SubscribeEvent
    public static void onRegisterItems(RegistryEvent.Register<Item> event) {
        AE2Addon.UNLIMITED_CELL = new ItemUnlimitedCell();
        event.getRegistry().register(AE2Addon.UNLIMITED_CELL);
        AE2Addon.LOG.info("[ae2addon] 已注册物品 {}", AE2Addon.UNLIMITED_CELL.getRegistryName());
    }
}
