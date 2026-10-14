package com.ae2addon.gui;

import appeng.api.stacks.AEKey;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Client snapshot of explicit reject settings, independent of stored items. */
public final class RejectListState {
    private static final Map<Integer, List<AEKey>> pendingOn = new HashMap<>();
    private static final Map<Integer, List<AEKey>> pendingOff = new HashMap<>();
    private static int expected;
    private RejectListState() {}

    public static void handleChunk(List<AEKey> on, List<AEKey> off, int index, int total) {
        if (total < 1 || index < 0 || index >= total) return;
        if (index == 0) {
            pendingOn.clear(); pendingOff.clear(); expected = total;
        }
        if (expected != total) return;
        pendingOn.put(index, on);
        pendingOff.put(index, off);
        if (pendingOn.size() != total || pendingOff.size() != total) return;
        Set<AEKey> allOn = new LinkedHashSet<>(), allOff = new LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            allOn.addAll(pendingOn.get(i));
            allOff.addAll(pendingOff.get(i));
        }
        pendingOn.clear(); pendingOff.clear(); expected = 0;
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof Mode2ConfigScreen mode2) mode2.setRejectKeys(allOn, allOff);
        if (screen instanceof ModeSelectScreen select) select.setRejectKeys(allOn, allOff);
    }
}
