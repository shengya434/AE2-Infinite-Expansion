package com.ae2addon.client;

import com.ae2addon.AE2Addon;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client-side item model registration.
 *
 * <p>Two 1.12.2 wiring facts, both verified from the Forge sources jar rather than recalled:
 * <ul>
 * <li>{@code ModelRegistryEvent} is posted on {@code MinecraftForge.EVENT_BUS} (FMLClientHandler),
 * not on the per-mod bus.</li>
 * <li>In 1.12.2, {@code @Mod.EventBusSubscriber} classes are injected into that same
 * {@code MinecraftForge.EVENT_BUS} (FML's AutomaticEventSubscriber). So a
 * {@code @Mod.EventBusSubscriber} class can handle ModelRegistryEvent -- but it can NOT handle
 * {@code RegistryEvent.Register<Item>}, which is a mod-bus event handled by the {@code @Mod} instance.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AE2Addon.MODID, value = Side.CLIENT)
public final class ClientRegistration {

    private ClientRegistration() {
    }

    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(AE2Addon.UNLIMITED_CELL, 0,
                new ModelResourceLocation(new ResourceLocation(AE2Addon.MODID, AE2Addon.CELL_NAME), "inventory"));
    }
}
