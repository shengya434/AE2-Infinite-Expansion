package com.ae2addon.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.ae2addon.AE2Addon;
import com.ae2addon.cell.CellStorageData;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * P2 persistence: one {@link CellStorageData} per cell UUID, stored in the overworld's map storage.
 *
 * <p>Why world saved data instead of the cell's ItemStack NBT: the maps can reach hundreds of
 * thousands of keys, and item NBT gets copied (and synced) constantly.
 *
 * <p>Known consequences, documented rather than hidden:
 * <ul>
 * <li>Because the UUID lives in the item's NBT, <b>copying a cell makes both copies share one data
 * entry</b> (creative middle-click, NBT duplication). The 1.20 line behaves the same way.</li>
 * <li>The data is <b>per world</b>: carrying a cell into another save shows an empty cell there.
 * Nothing is lost -- going back to the original save brings the contents back.</li>
 * </ul>
 */
public class CellWorldData extends WorldSavedData {

    public static final String DATA_NAME = "ae2addon_cells";
    private static final String K_CELLS = "cells";

    private final Map<UUID, CellStorageData> cells = new HashMap<>();

    /** MapStorage instantiates saved data reflectively through the {@code (String)} constructor. */
    public CellWorldData() {
        super(DATA_NAME);
    }

    public CellWorldData(String name) {
        super(name);
    }

    /**
     * @return the singleton for this world, or {@code null} when there is no server world
     *         (client side, or before a world exists). Callers must treat null as "storage
     *         unavailable" and must never mutate state in that case.
     */
    public static CellWorldData get() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return null;
        }
        WorldServer overworld = server.getWorld(0);
        if (overworld == null) {
            return null;
        }
        MapStorage storage = overworld.getMapStorage();
        if (storage == null) {
            return null;
        }
        WorldSavedData loaded = storage.getOrLoadData(CellWorldData.class, DATA_NAME);
        if (loaded == null) {
            CellWorldData created = new CellWorldData(DATA_NAME);
            storage.setData(DATA_NAME, created);
            return created;
        }
        return (CellWorldData) loaded;
    }

    public CellStorageData get(UUID id) {
        return cells.get(id);
    }

    public CellStorageData getOrCreate(UUID id) {
        CellStorageData data = cells.get(id);
        if (data == null) {
            data = new CellStorageData();
            cells.put(id, data);
            markDirty();
        }
        return data;
    }

    public void put(UUID id, CellStorageData data) {
        if (cells.get(id) != data) {
            cells.put(id, data);
        }
        markDirty();
    }

    public int size() {
        return cells.size();
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        cells.clear();
        NBTTagCompound root = nbt.getCompoundTag(K_CELLS);
        for (String key : root.getKeySet()) {
            try {
                UUID id = UUID.fromString(key);
                CellStorageData data = new CellStorageData();
                data.readFromNbt(root.getCompoundTag(key));
                cells.put(id, data);
            } catch (RuntimeException ex) {
                // One unreadable cell must not cost the whole save.
                if (AE2Addon.LOG != null) {
                    AE2Addon.LOG.warn("[ae2addon] 存档里有一个元件数据读不出来，已跳过: {}", ex.toString());
                }
            }
        }
        if (AE2Addon.LOG != null) {
            AE2Addon.LOG.info("[ae2addon] 已载入 {} 份元件数据", cells.size());
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        NBTTagCompound root = new NBTTagCompound();
        for (Map.Entry<UUID, CellStorageData> entry : cells.entrySet()) {
            NBTTagCompound one = new NBTTagCompound();
            entry.getValue().writeToNbt(one);
            root.setTag(entry.getKey().toString(), one);
        }
        compound.setTag(K_CELLS, root);
        return compound;
    }
}
