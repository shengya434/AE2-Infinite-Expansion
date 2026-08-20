package com.ae2addon.crafting;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;

/**
 * 拆批后重新模拟时的仿真请求者：透传原请求的来源与网格节点，
 * 让 beginCraftingCalculation 能正确读取网格库存做模拟。
 */
public final class SimulationRequester implements ICraftingSimulationRequester {

    private final IActionSource source;
    private final IGridNode node;

    public SimulationRequester(IActionSource source, IGridNode node) {
        this.source = source;
        this.node = node;
    }

    @Override
    public IActionSource getActionSource() {
        return source;
    }

    @Override
    public IGridNode getGridNode() {
        return node;
    }
}
