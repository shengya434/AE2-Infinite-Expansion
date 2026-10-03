package com.ae2addon.cell.mana;

import com.ae2addon.cell.SingleKeyCellChannel;
import nyonio.ae2.ManaStack;

import appeng.api.AEApi;
import appeng.api.storage.IStorageChannel;

/**
 * The 法力 channel, provided by botania_applie.
 *
 * <p>A SINGLE-KEY resource: Mana (Botania) has one key and only the amount varies, so this adapter is a handful of
 * names on top of {@link SingleKeyCellChannel}. Optional-dependency code: only reachable through
 * {@link ManaChannelDoor}, which names no type from here.
 */
public final class ManaCellChannel extends SingleKeyCellChannel<nyonio.ae2.ManaStack> {

    public static final ManaCellChannel INSTANCE = new ManaCellChannel();

    private ManaCellChannel() {
    }

    @Override
    public String id() {
        return "mana";
    }

    @Override
    public IStorageChannel<nyonio.ae2.ManaStack> storage() {
        // Prefer whatever AE2's storage helper knows (that is the instance AE2 hands the cell handler), and
        // fall back to the bridge mod's own singleton if it never registered one.
        IStorageChannel<nyonio.ae2.ManaStack> viaApi = AEApi.instance().storage().getStorageChannel(nyonio.ae2.ManaStorageChannel.class);
        return viaApi != null ? viaApi : nyonio.ae2.ManaStorageChannel.INSTANCE;
    }

    @Override
    protected nyonio.ae2.ManaStack singleKey() {
        return ManaStack.STATIC_STACK;
    }

    @Override
    protected String namespaceId() {
        // Mod rules mean "everything from this mod"; the bridge mod owns this channel, so it is its namespace.
        return "botania_applie";
    }

    @Override
    protected String displayNameText() {
        return "Mana (Botania)";
    }
}