package com.ae2addon.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

/** Client-only inbox for explicit reject override chunks (type 21). */
public final class RejectListState {
    private final List<List<ItemStack>> pendingOn = new ArrayList<>();
    private final List<List<ItemStack>> pendingOff = new ArrayList<>();
    private List<ItemStack> on = Collections.emptyList();
    private List<ItemStack> off = Collections.emptyList();
    private int expected;
    private long started;

    public void handleChunk(List<ItemStack> chunkOn, List<ItemStack> chunkOff, int index, int total) {
        if (total < 1 || total > 100000 || index < 0 || index >= total) return;
        if (index == 0) {
            pendingOn.clear(); pendingOff.clear(); expected = total;
            started = System.currentTimeMillis();
            for (int i = 0; i < total; i++) { pendingOn.add(null); pendingOff.add(null); }
        }
        if (expected != total || pendingOn.size() != total) return;
        pendingOn.set(index, new ArrayList<>(chunkOn));
        pendingOff.set(index, new ArrayList<>(chunkOff));
        if (pendingOn.contains(null) || pendingOff.contains(null)) return;
        List<ItemStack> allOn = new ArrayList<>();
        List<ItemStack> allOff = new ArrayList<>();
        for (int i = 0; i < total; i++) { allOn.addAll(pendingOn.get(i)); allOff.addAll(pendingOff.get(i)); }
        on = allOn; off = allOff;
        pendingOn.clear(); pendingOff.clear(); expected = 0;
    }

    public List<ItemStack> on() { return on; }
    public List<ItemStack> off() { return off; }
    public boolean pending() { return expected != 0; }
    public boolean timedOut() { return pending() && System.currentTimeMillis() - started > 5000; }
}
