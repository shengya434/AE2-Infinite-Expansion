package com.ae2addon.cell.gas;

import java.util.Collections;
import java.util.List;

import com.ae2addon.cell.CellChannel;

import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

/**
 * The DOOR of the optional-dependency pattern.
 *
 * <p>This class deliberately references NO type from Mekanism Energistics or Mekanism: the mod list lookup is
 * the only thing it does by itself, and everything else is a call into {@link GasChannelFactory}, which the JVM
 * loads the first time such a call actually executes. That is what keeps "mekeng is not installed" from
 * turning into a NoClassDefFoundError at class-verification time.
 */
public final class GasChannelDoor {

    public static final String MOD_ID = "mekeng";

    /** Resolved once: the mod list cannot change after loading. */
    private static final boolean LOADED = Loader.isModLoaded(MOD_ID);

    private GasChannelDoor() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    /** Registers the gas channel with the cell's channel registry, if the bridge mod is here. */
    public static void register() {
        if (LOADED) {
            GasChannelFactory.register();
        }
    }

    /** @return a gas cell inventory handler when this channel is the gas one, else {@code null}. */
    public static <T extends IAEStack<T>> ICellInventoryHandler<T> tryBuild(ItemStack stack,
            ISaveProvider saveProvider, IStorageChannel<T> channel) {
        return LOADED ? GasChannelFactory.tryBuild(stack, saveProvider, channel) : null;
    }

    /** All channels this optional bridge contributes (empty when it is absent). */
    public static List<CellChannel<?>> channels() {
        return LOADED ? Collections.<CellChannel<?>>singletonList(GasCellChannel.INSTANCE)
                : Collections.<CellChannel<?>>emptyList();
    }
}