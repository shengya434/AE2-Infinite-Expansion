package com.ae2addon.cell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.storage.IStorageChannel;
import appeng.api.storage.data.IAEStack;

/**
 * Which storage channels this cell speaks, and how to get from a saved id back to the adapter.
 *
 * <p>Two lookups are needed and they are NOT the same direction:
 * <ul>
 * <li><b>by storage</b>: AE2 hands us an {@code IStorageChannel} when it asks the cell handler for a
 *     channel's inventory ({@code ICellHandler.getCellInventory(stack, provider, channel)}).</li>
 * <li><b>by id</b>: world saved data stores keys per channel as text, so reading them back means turning a
 *     saved {@code "item"} / {@code "fluid"} back into the adapter that can deserialize those keys. Without
 *     this the save format would have to embed AE2 types, which is exactly what it must not do.</li>
 * </ul>
 *
 * <p>The item channel registers itself in this class's static block: an item-only cell must keep working even
 * if the mod's own setup order changes, and the item channel is always present. Optional channels (fluid, gas,
 * essentia) register from the mod init once they exist -- and an unknown id in a save is skipped with a log
 * line instead of failing the whole cell (a removed bridge mod must not cost the player their data).
 */
public final class CellChannelRegistry {

    /** Id of the always-present item channel. */
    public static final String ITEM = "item";
    /** Id of the fluid channel (AE2-UEL own second channel, always present). */
    public static final String FLUID = "fluid";

    private static final Map<String, CellChannel<?>> BY_ID = new LinkedHashMap<>();
    private static final Map<IStorageChannel<?>, CellChannel<?>> BY_STORAGE = new LinkedHashMap<>();

    static {
        register(ItemCellChannel.INSTANCE);
        // Fluid is AE2-UEL's own second channel, so it is always available -- no bridge mod involved.
        register(FluidCellChannel.INSTANCE);
    }

    private CellChannelRegistry() {
    }

    public static synchronized void register(CellChannel<?> channel) {
        BY_ID.put(channel.id(), channel);
        BY_STORAGE.put(channel.storage(), channel);
    }

    /** @return the adapter for a saved id, or {@code null} when that channel is not available here. */
    public static synchronized CellChannel<?> byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /**
     * @return the adapter AE2 is asking about, or {@code null} when this cell does not serve that channel.
     *
     * <p>Matches by INSTANCE first, then by CLASS, and that fallback is load-bearing. AE2's {@code ApiStorage} is
     * not guaranteed to keep handing out the same {@code IStorageChannel} objects it had when we registered: it
     * is rebuilt on side changes. A stale identity match answers "I do not serve that channel" for every channel
     * at once, which makes the cell invisible to the ME network -- the terminal lists nothing and accepts
     * nothing, and the cell in a drive shows 0 bytes/types (sensei's BUG1/BUG2, 2026-10-03).
     *
     * <p>That is exactly what began when this map lookup replaced the live {@code channel != itemChannel()}
     * comparison in the fluid commit {@code a8d7baf}: a live comparison can never go stale, a cached key can.
     */
    public static synchronized CellChannel<?> byStorage(IStorageChannel<?> storage) {
        if (storage == null) {
            return null;
        }
        CellChannel<?> direct = BY_STORAGE.get(storage);
        if (direct != null) {
            return direct;
        }
        for (CellChannel<?> adapter : BY_STORAGE.values()) {
            IStorageChannel<?> registered = adapter.storage();
            if (registered != null && registered.getClass() == storage.getClass()) {
                return adapter;
            }
        }
        return null;
    }

    public static synchronized List<CellChannel<?>> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ID.values()));
    }

    /**
     * Typed view of {@link #byId}.
     *
     * <p>Unchecked on purpose: a save only ever stores keys of the channel whose entry they came from, so the
     * cast is guaranteed by the data itself -- but the caller has to pass the channel's stack type through,
     * which is why this is a separate method rather than a field on the data class.
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T extends IAEStack<T>> CellChannel<T> byIdTyped(String id) {
        return (CellChannel<T>) BY_ID.get(id);
    }
}
