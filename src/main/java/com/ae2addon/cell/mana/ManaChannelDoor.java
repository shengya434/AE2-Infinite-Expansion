package com.ae2addon.cell.mana;

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
 * The DOOR of the optional-dependency pattern for 法力.
 *
 * <p>Names no botania_applie type: the mod list lookup is all this class does by itself, and every other method is
 * a call into {@link ManaChannelFactory}, which the JVM loads only when such a call actually executes.
 */
public final class ManaChannelDoor {

    public static final String MOD_ID = "botania_applie";

    private static final boolean LOADED = Loader.isModLoaded(MOD_ID);

    private ManaChannelDoor() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static void register() {
        if (LOADED) {
            ManaChannelFactory.register();
        }
    }

    public static <T extends IAEStack<T>> ICellInventoryHandler<T> tryBuild(ItemStack stack,
            ISaveProvider saveProvider, IStorageChannel<T> channel) {
        return LOADED ? ManaChannelFactory.tryBuild(stack, saveProvider, channel) : null;
    }

    public static List<CellChannel<?>> channels() {
        return LOADED ? Collections.<CellChannel<?>>singletonList(ManaCellChannel.INSTANCE)
                : Collections.<CellChannel<?>>emptyList();
    }
}