package com.ae2addon.init;

import com.ae2addon.AE2Addon;
import com.ae2addon.block.InfiniteCraftingStorageBlock;
import com.ae2addon.block.InfiniteCoProcessingBlock;
import com.ae2addon.block.InfiniteInterfaceBlock;
import com.ae2addon.block.InfiniteInterfacePanelBlock;
import com.ae2addon.block.IntegratedCPUBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * AE2 Addon 方块注册
 */
public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AE2Addon.MODID);

    // ── 已有方块 ──

    /** 无限合成存储器 — 合成 CPU 扩容，永不爆仓 */
    public static final RegistryObject<Block> INFINITE_CRAFTING_STORAGE = BLOCKS.register(
            "infinite_crafting_storage",
            InfiniteCraftingStorageBlock::new
    );

    /** 无限并行处理单元 — 合成队列无限并发 */
    public static final RegistryObject<Block> INFINITE_CO_PROCESSING = BLOCKS.register(
            "infinite_co_processing",
            InfiniteCoProcessingBlock::new
    );

    // ── 新增三方块 ──

    /** 集成型CPU（无限级）— 3×5×3 多方块，提供无限合成能力 */
    public static final RegistryObject<Block> INTEGRATED_CPU = BLOCKS.register(
            "integrated_cpu",
            IntegratedCPUBlock::new
    );

    // ── ME接口（无限级） ──

    /** ME接口（无限级）— 机器供料站：被动拉取无上限 + 接收 CPU N× 直灌 + 按机器容量喂出 */
    public static final RegistryObject<Block> INFINITE_INTERFACE = BLOCKS.register(
            "infinite_interface",
            InfiniteInterfaceBlock::new
    );

    /** ME接口（无限级）· 面板 — 薄型贴面变种（2026-09-02），功能与整格版一致，复用同一 BE */
    public static final RegistryObject<Block> INFINITE_INTERFACE_PANEL = BLOCKS.register(
            "infinite_interface_panel",
            InfiniteInterfacePanelBlock::new
    );
}
