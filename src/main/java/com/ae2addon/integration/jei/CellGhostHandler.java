package com.ae2addon.integration.jei;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;

import com.ae2addon.gui.Mode2ConfigScreen;

import mezz.jei.api.gui.IGhostIngredientHandler;
import net.minecraft.item.ItemStack;

/**
 * Accepts an item dragged out of JEI and drops it onto the cell config list.
 *
 * <p>Verified API shape (javap on jei_1.12.2-4.16.5.1030.jar, 2026-10-02):
 * <pre>
 * interface IGhostIngredientHandler&lt;T extends GuiScreen&gt; {
 *     &lt;I&gt; List&lt;Target&lt;I&gt;&gt; getTargets(T gui, I ingredient, boolean doStart);
 *     void onComplete();
 *     boolean shouldHighlightTargets();          // default
 *     &lt;I&gt; boolean quickMove(T gui, I ingredient); // default
 * }
 * interface Target&lt;I&gt; extends Consumer&lt;I&gt; { Rectangle getArea(); void accept(I); }
 * </pre>
 *
 * <p>What a drop means is decided by the screen's active tab (the screen itself owns that state):
 * the item page adds the item to the whitelist, the reject page toggles rejection for it.
 */
public final class CellGhostHandler implements IGhostIngredientHandler<Mode2ConfigScreen> {

    @Override
    public <I> List<Target<I>> getTargets(Mode2ConfigScreen gui, I ingredient, boolean doStart) {
        // Only items: the cell stores items, and that is all the panels speak.
        if (!(ingredient instanceof ItemStack)) {
            return Collections.emptyList();
        }
        final ItemStack stack = (ItemStack) ingredient;
        if (stack.isEmpty()) {
            return Collections.emptyList();
        }
        final Rectangle area = gui.ghostArea();
        if (area == null) {
            return Collections.emptyList(); // page takes no drops right now
        }
        return Collections.singletonList(new Target<I>() {
            @Override
            public Rectangle getArea() {
                return area;
            }

            @Override
            public void accept(I value) {
                gui.applyGhostIngredient(stack);
            }
        });
    }

    @Override
    public void onComplete() {
        // Nothing to clean up; the screen keeps no drag state between drops.
    }
}
