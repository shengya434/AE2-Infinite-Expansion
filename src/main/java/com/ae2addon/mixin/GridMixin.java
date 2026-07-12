package com.ae2addon.mixin;

import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

/**
 * Patches AE2's {@link Grid#getMachines(Class)} and {@link Grid#getMachineNodes(Class)}
 * to also return nodes stored under subclass keys.
 * <p>
 * AE2's Grid stores nodes in a {@code SetMultimap<Class<?>, IGridNode>} keyed by
 * {@code node.getOwner().getClass()} (the exact runtime class). When
 * {@link appeng.me.service.CraftingService#updateCPUClusters()} calls
 * {@code grid.getMachines(CraftingBlockEntity.class)}, the multimap only returns
 * entries for the exact key {@code CraftingBlockEntity.class} — subclasses like
 * {@link com.ae2addon.block.InfiniteCraftingStorageBE} are stored under their own
 * class key and are never found.
 * <p>
 * This mixin patches {@code getMachines} and {@code getMachineNodes} to iterate
 * over all keys and include any key from which the requested class is assignable
 * (i.e., subclass keys).
 */
@Mixin(Grid.class)
public class GridMixin {

    @Shadow(remap = false)
    private SetMultimap<Class<?>, IGridNode> machines;

    /**
     * Intercepts {@link Grid#getMachines(Class)} to also include owners from
     * subclass keys in the multimap.
     * <p>
     * For each key {@code k} in {@code this.machines}, if
     * {@code clazz.isAssignableFrom(k)} is true, the owners of all nodes under
     * key {@code k} are included in the returned set.
     */
    @Inject(
            method = "getMachines(Ljava/lang/Class;)Ljava/util/Set;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private <T> void ae2addon$getMachines(Class<T> clazz, CallbackInfoReturnable<Set<T>> cir) {
        Set<T> result = new HashSet<>();
        for (Class<?> key : this.machines.keySet()) {
            if (clazz.isAssignableFrom(key)) {
                for (IGridNode node : this.machines.get(key)) {
                    result.add((T) node.getOwner());
                }
            }
        }
        cir.setReturnValue(ImmutableSet.copyOf(result));
    }

    /**
     * Intercepts {@link Grid#getMachineNodes(Class)} to also include nodes from
     * subclass keys in the multimap.
     * <p>
     * For each key {@code k} in {@code this.machines}, if
     * {@code clazz.isAssignableFrom(k)} is true, all nodes under key {@code k}
     * are included in the returned iterable.
     */
    @Inject(
            method = "getMachineNodes(Ljava/lang/Class;)Ljava/lang/Iterable;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void ae2addon$getMachineNodes(Class<?> clazz, CallbackInfoReturnable<Iterable<IGridNode>> cir) {
        Set<IGridNode> result = new HashSet<>();
        for (Class<?> key : this.machines.keySet()) {
            if (clazz.isAssignableFrom(key)) {
                result.addAll(this.machines.get(key));
            }
        }
        cir.setReturnValue(ImmutableSet.copyOf(result));
    }
}
