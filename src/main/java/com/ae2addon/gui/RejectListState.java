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
    private boolean complete;
    private final ChunkRetry retry = new ChunkRetry();

    public void handleChunk(List<ItemStack> chunkOn, List<ItemStack> chunkOff, int index, int total) {
        if (total < 1 || total > 100000 || index < 0 || index >= total) return;
        if (expected == 0 || expected != total) {
            pendingOn.clear(); pendingOff.clear(); expected = total;
            complete = false;
            retry.start();
            for (int i = 0; i < total; i++) { pendingOn.add(null); pendingOff.add(null); }
        }
        if (expected != total || pendingOn.size() != total) return;
        if (!complete && pendingOn.get(index) != null) return;
        pendingOn.set(index, new ArrayList<>(chunkOn));
        pendingOff.set(index, new ArrayList<>(chunkOff));
        if (!complete) retry.progress();
        if (pendingOn.contains(null) || pendingOff.contains(null)) return;
        List<ItemStack> allOn = new ArrayList<>();
        List<ItemStack> allOff = new ArrayList<>();
        for (int i = 0; i < total; i++) { allOn.addAll(pendingOn.get(i)); allOff.addAll(pendingOff.get(i)); }
        on = allOn; off = allOff;
        // Retain the complete chunks so late retry packets update the list without reopening it.
        complete = true;
    }

    public List<ItemStack> on() { return on; }
    public List<ItemStack> off() { return off; }
    public boolean pending() { return expected != 0 && !complete; }
    public int expected() { return expected; }
    public int received() {
        int count = 0;
        for (List<ItemStack> part : pendingOn) if (part != null) count++;
        return count;
    }
    public boolean retryDue() { return retry.retryDue(pending()); }
    public boolean exhausted() { return retry.exhausted(pending()); }
    public void manualRefresh() {
        if (complete) {
            pendingOn.clear(); pendingOff.clear(); expected = 0; complete = false;
        }
        retry.manualRefresh();
    }
}
