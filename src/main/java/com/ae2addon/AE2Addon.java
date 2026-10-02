package com.ae2addon;

import org.apache.logging.log4j.Logger;

import com.ae2addon.cell.UnlimitedCellHandler;
import com.ae2addon.command.CommandCell;
import com.ae2addon.command.CommandCellSelfTest;

import net.minecraft.item.Item;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

@Mod(modid = AE2Addon.MODID, name = AE2Addon.NAME, version = AE2Addon.VERSION,
        acceptedMinecraftVersions = "[1.12.2]",
        dependencies = "required-after:appliedenergistics2")
public class AE2Addon {

    public static final String MODID = "ae2addon";
    public static final String NAME = "AE2 Addon";
    public static final String VERSION = "0.1.0-1.12.2";
    public static final String CELL_NAME = "unlimited_cell";

    public static Logger LOG;
    public static Item UNLIMITED_CELL;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOG = event.getModLog();
    }

    /**
     * The cell handler MUST be registered here (init), not in preInit.
     *
     * <p>AE2's {@code CellRegistry.addCellHandler} ends with
     * {@code Verify.verify(handlers.get(0) instanceof BasicCellHandler)} -- it requires AE2's own
     * BasicCellHandler to be element 0. AE2 adds it during its own preInit, so registering from our
     * preInit can (and did, 2026-10-02) put our handler at index 0 and crash the game with a bare
     * VerifyException before the window even opened. Every mod's preInit completes before any mod's
     * init runs, so registering from init is always safe.
     *
     * <p>(Same reason Mekanism Energistics does its cell-registry work in init and only its storage
     * channel in preInit -- that was the evidence, and it was right.)
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // AEApi.instance() is safe this early: AEApi's static initialiser reflectively reads
        // appeng.core.Api.INSTANCE.
        UnlimitedCellHandler.register();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandCellSelfTest());
        event.registerServerCommand(new CommandCell());
    }

    // NOTE: item registration deliberately does NOT live here. In 1.12.2 the registry events are fired
    // on MinecraftForge.EVENT_BUS (see GameData.fireRegistryEvents), while FML registers the @Mod
    // instance on this mod's own bus only (FMLModContainer.registerBus). A @SubscribeEvent method here
    // would therefore never fire -- that mistake cost a crash on 2026-10-02.
    // The handler is in RegistryHandler (@Mod.EventBusSubscriber).
}
