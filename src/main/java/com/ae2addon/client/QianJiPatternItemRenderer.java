package com.ae2addon.client;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.compat.ChemicalCompat;
import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * 千机样板的客户端渲染（2026-09-20 sensei 需求第三版）：
 * <ul>
 *   <li><b>默认</b>（不按 Shift）→ 画该样板**主产物**的图标；</li>
 *   <li><b>按住 Shift</b> → 画样板底图（原样板贴图）。</li>
 * </ul>
 * 也就是把第一版的两种形态**反过来**：默认看产物、Shift 看底图。
 * <p>
 * {@link ItemDisplayContext} 分不出"背包槽 / 终端槽 / 千机样板槽"（三者都是 GUI），
 * 所以**不按上下文区分**，全局统一按上面这条走。
 *
 * <h3>为什么不再自己画 baked model 的 quads（2026-09-20 实测教训，别改回去）</h3>
 * 第一版是"取底图模型 → applyTransform → 手工 translate + 逐面 renderQuadList"。
 * sensei 实测：① 贴图端点落在 (-1,1)(1,1)(1,-1)(-1,-1)，**位置/缩放全歪**（手工变换错了）；
 * ② 底图模型没被任何已注册物品引用 → **不会被烘焙** → {@code ModelManager#getModel} 拿到
 * "缺失模型" → 原贴图变成黑紫方块。
 * <p>
 * 现在改成：**物品产物仍然委托给原版 {@code ItemRenderer#renderStatic}**，画的是一个**真实注册的物品**
 * （{@link ModItems#QIANJI_PATTERN_TEMPLATE}，贴图就是原样板贴图）。
 * 位置 / 缩放 / 光照 / 变换全部由原版那套 `display` 逻辑负责 → 与普通物品**完全一致**，
 * 而且它被真实物品引用 → 一定会被烘焙 → 不可能再出现缺失模型。
 *
 * <h3>流体 / 化学品的画法（2026-09-21 第二版；第一版是"自己画四边形"）</h3>
 * 第一版把流体/化学品交给 AE2 的 {@code appeng.items.misc.WrappedGenericStack.wrap(key, amount)}。
 * **sensei 实测：显示成透明材质。** 原因已确认：那个物品的模型是 AE2 的**透明占位贴图**
 * ——它只在 JEI 配方格里用，图标由 AE2 的 JEI 插件自己画（不走物品模型），
 * 所以在背包/终端槽里就是一块透明的东西。
 * <p>
 * 于是改成"自己画"。但自绘踩了两个坑（都有实机日志证据，别再踩）：
 * ① {@code RenderType.translucent()} 用 {@code DefaultVertexFormat.BLOCK}，漏了 {@code normal}
 *    → {@code IllegalStateException: Not filled all elements of the vertex}（异常被吞 → 看着"透明"）；
 * ② 补了 normal 之后仍然"只有一面有材质、背包那面透明"（translucent 带**正面剔除** + 两面共面被深度测试挡掉）。
 * <p>
 * <b>本版的三条修法</b>：
 * <ol>
 *   <li><b>流体优先用真实物品</b>：{@code FluidUtil.getFilledBucket(new FluidStack(key.getFluid(), 1000))}
 *       → 拿到桶就**走和物品产物完全相同的 {@code ItemRenderer#renderStatic} 那条路**（不画四边形）。
 *       水/岩浆/绝大多数模组流体都有桶 → 这一条覆盖绝大部分，也最稳；</li>
 *   <li>**没有桶物品**的流体 + **化学品** → 自绘四边形，渲染类型换成
 *       {@code RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS)}（**不剔除** → 绕序/朝向不再重要，
 *       一面就够）；顶点元素顺序改成与之匹配的 {@code NEW_ENTITY}（见 {@link #drawQuad}）；</li>
 *   <li>顶点里的光照/overlay 用调用方传进来的，法线 {@code (0,0,1)}，四边形仍在 ±0.5、z 用现有 {@code QUAD_Z}。</li>
 * </ol>
 * 化学品（Applied-Mekanistics 的 key，{@link ChemicalCompat#isChemical} 判定）：有现成的图标辅助
 * （Mekanism 自己的做法 = {@code Chemical#getIcon()} 取**方块图集**里的 sprite +
 * {@code ChemicalStack#getChemicalTint()} 染色，证据：appmek 的
 * {@code AMChemicalStackRenderer#drawOnBlockFace} 与 {@code MekanismRenderer#getChemicalTexture(Chemical)}
 * 字节码）→ 有就用；图标取不到才退回**纯色四边形**（tint 或中性灰）。
 *
 * <h3>为什么实例要懒加载缓存</h3>
 * {@link BlockEntityWithoutLevelRenderer} 在 1.20.1 **没有无参构造**（javap 实证：只有
 * {@code (BlockEntityRenderDispatcher, EntityModelSet)}），必须从 {@code Minecraft.getInstance()} 取；
 * 而某个物品的客户端扩展可能在类加载/注册早期就被问一次 → 在类加载期 new 会 NPE。
 *
 * <h3>签名都是 javap 核过的（1.20.1-47.4.22_mapped_official / AE2 15.4.10 / Mekanism 10.4.16 / appmek 1.4.3）</h3>
 * <pre>
 * BlockEntityWithoutLevelRenderer#renderByItem(ItemStack, ItemDisplayContext, PoseStack, MultiBufferSource, int, int)
 * ItemRenderer#renderStatic(ItemStack, ItemDisplayContext, int light, int overlay, PoseStack, MultiBufferSource, Level, int seed)
 * Screen#hasShiftDown()
 * Minecraft#getItemRenderer() / #getInstance() / #level / #getTextureAtlas(ResourceLocation)   ← 返回 Function&lt;ResourceLocation, TextureAtlasSprite&gt;
 * TextureAtlasSprite#getU0()/getU1()/getV0()/getV1()
 * VertexConsumer#vertex(Matrix4f,float,float,float) / #color(int)（int 按 ARGB 解包，见字节码）/ #uv(float,float)
 *               / #overlayCoords(int)（拆成 (u,v) 两个 short 后调重载）/ #uv2(int) / #normal(float,float,float) / #endVertex()
 * MultiBufferSource#getBuffer(RenderType) / RenderType#entityCutoutNoCull(ResourceLocation)
 *   ↑ 该 RenderType 的 VertexFormat = DefaultVertexFormat.NEW_ENTITY，元素顺序
 *     Position → Color → UV0 → UV1(overlay) → UV2(light) → Normal → Padding（1.20.1 源码实证）
 * IClientFluidTypeExtensions.of(Fluid) / #getStillTexture(FluidStack) / #getTintColor(FluidStack)
 * AEFluidKey#getFluid() / #toStack(int)                    ← Forge FluidStack，不是 MCP 名，无需反射
 * FluidUtil#getFilledBucket(FluidStack) / FluidStack(Fluid,int)   ← 流体有没有"真实桶物品"
 * （appmek，反射）MekanismKey#getStack() → ChemicalStack#getChemicalTint() / Chemical#getIcon() / #getTint()
 * </pre>
 */
public class QianJiPatternItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** 渲染出问题时只吼这一次，之后静默（渲染路径不能刷日志，更不能抛异常） */
    private static boolean loggedFailure = false;
    /**
     * 2026-09-20 sensei 实测"完全没变化"时的取证开关（一次性日志）：
     * 第一条判"渲染器到底有没有被调用"，第二条判"默认形态有没有真的走产物图标"。
     */
    private static boolean loggedFirstRender = false;
    private static boolean loggedProductRender = false;
    /** 2026-09-21 新增：流体 / 化学品自绘路径各一次性取证（判"有没有走到新分支 + sprite 是不是缺失贴图"） */
    private static boolean loggedFluidRender = false;
    private static boolean loggedChemicalRender = false;
    /** 流体走"真实桶物品"那条路的一次性取证（区分 A 桶 / B 自绘两条路） */
    private static boolean loggedFluidBucketRender = false;
    /**
     * 2026-09-21：**按类别**记一次填充失败（原来全局只有一个 {@link #loggedFailure} 标志位，
     * 流体的失败会把化学品的盖住 —— 上一轮排查日志时就是这么被误导的）。
     */
    private static boolean loggedFluidFillFailure = false;
    private static boolean loggedChemicalFillFailure = false;

    private static QianJiPatternItemRenderer instance;

    /** 全亮的 light 兜底：主菜单这类光照里 renderStatic 会传 0，物品会画成黑块 */
    private static final int FALLBACK_LIGHT = LightTexture.FULL_BRIGHT;

    /**
     * 自绘四边形用的渲染类型（2026-09-21 换掉 {@code RenderType.translucent()}）。
     * <p>
     * <b>为什么换</b>：{@code translucent()} 带**正面剔除**（cull），quad 的绕序画反了就是整块看不见；
     * 而"哪一面朝镜头"在离线环境里没法判断（前几轮为此来回翻绕序、两种绕序各画一遍都没解决）。
     * {@code entityCutoutNoCull} 的状态里是 {@code setCullState(RenderStateShard.NO_CULL)}
     * （证据：1.20.1 的 {@code RenderType.java} 第 48 行，{@code ENTITY_CUTOUT_NO_CULL} 的 CompositeState）
     * → **不剔除**，绕序/朝向彻底不重要，一面就够。
     * <p>
     * <b>代价（照实记）</b>：它的状态是 {@code setTransparencyState(NO_TRANSPARENCY)}（同上第 48 行）
     * → 走**剔除透明度的着色器**（有 alpha 测试、没有混合）：
     * <ul>
     *   <li>半透明像素（alpha 低于阈值，原版是 0.1）会被丢掉 → 贴图边缘带透明的部分会变脆，
     *       但方块图集里流体的 still 贴图主体是不透明的，所以能看见；</li>
     *   <li>顶点颜色里的 alpha 不再参与混合（水那种 {@code #3f76e4} 半透明 tint 会"实心"显示）。</li>
     * </ul>
     * 想要真正半透明且同样不剔除的，是 {@code RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS)}
     * （第 64 行：{@code TRANSLUCENT_TRANSPARENCY} + {@code NO_CULL}）—— 本版按 sensei 的要求用 cutoutNoCull，
     * 若实机觉得流体"太实/边缘发脆"，换那一个即可（顶点元素顺序完全相同，改一行）。
     */
    private static final RenderType QUAD_RENDER_TYPE =
            RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);

    /**
     * 四边形在"物品坐标系"里的半边长（中心在原点）。
     * <p>
     * 为什么是 -0.5..0.5 而不是 0..1：见 {@link #renderByItem} 里那段"坐标系"注释 ——
     * 我们抵掉调用方的 -0.5 之后，所处的坐标系正是**物品模型自己的 quad 所在的那个**，
     * 而原版物品模型的 quad 就画在 -0.5..0.5（模型的 0..1 减去那次 -0.5 平移）。
     */
    private static final float QUAD_HALF = 0.5f;

    /** 往 +z 方向抬一点点，避开与"物品模型自己的面"共面（appmek 用的就是 0.01）
     *  <p>
     *  ⚠ 参考数字（1.20.1 {@code ItemModelGenerator} 源码）：{@code item/generated} 的模型是一块
     *  厚 1 像素的"板"，厚度方向 {@code MIN_Z = 7.5F} → {@code MAX_Z = 8.5F}（像素），
     *  减掉调用方那次 {@code -0.5} 后落在 **z = -0.03125 .. +0.03125**。
     *  所以 {@code +0.01} 其实落在**这块板内部**（比正面 +0.03125 靠后）——
     *  本渲染器画流体/化学品时**不画**底图模板，格子里没有别的面写深度，quad 照常可见；
     *  万一实机发现"quad 被模型正面挡住看不见"，把这里改成 {@code 0.04f} 即可（越过 +0.03125，其余逻辑不变）。 */
    private static final float QUAD_Z = 0.01f;

    /** 化学品图标/tint 取不到时的中性灰（不透明） */
    private static final int CHEMICAL_FALLBACK_TINT = 0xFF808080;

    /** 不能在类加载期 new（见类注释），统一从这里拿 */
    public static synchronized QianJiPatternItemRenderer instance() {
        if (instance == null) {
            var mc = Minecraft.getInstance();
            instance = new QianJiPatternItemRenderer(
                    mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
        }
        return instance;
    }

    /** 这个构造签名是 1.20.1 唯一的一个（javap 实证） */
    private QianJiPatternItemRenderer(net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher,
                                      net.minecraft.client.model.geom.EntityModelSet modelSet) {
        super(dispatcher, modelSet);
    }

    private static final Logger LOGGER = LogManager.getLogger("ae2addon");

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource bufferSource, int light, int overlay) {
        // 本版本**不再自己 push/pop poseStack**：整套变换交给原版 renderStatic（它自己管配平），
        // 所以我们这边没有"异常时要弹栈"的负担，异常直接吞掉 + 记一次日志即可。
        try {
            if (stack.isEmpty()) return;
            if (!loggedFirstRender) {
                loggedFirstRender = true;
                LOGGER.info("[ae2addon] 千机样板渲染器**已生效**（renderByItem 首次调用，context={}）" +
                        "—— 若日志里没有这一行，说明自定义渲染器压根没被调用", context);
            }
            // 光照兜底：有些上下文（例如主菜单/创建世界界面的物品预览）传进来的是全 0 →
            // 不兜底的话图标会画成"全黑"而不是看不见，比不画更难看。
            final int packedLight = light == 0 ? FALLBACK_LIGHT : light;

            final boolean shift = Screen.hasShiftDown();
            // 主产物（读不出数据 / 没有主产物 → null）。Shift = 要看底图，所以不解析产物。
            final GenericStack product = shift ? null : primaryOf(stack);

            // 2026-09-21 实机定位（反汇编 `ItemRenderer.render` 得到，**别删**）：
            // 调用方在进入「自定义渲染器」分支**之前**已经做过一次 translate(-0.5f,-0.5f,-0.5f)
            //（字节码 109~117：三条 ldc -0.5f + invokevirtual translate，位置在 isCustomRenderer
            //  判断之前，**两个分支共用**），而下面的 renderStatic 内部会**再做一次**同样的平移
            // → 叠加成 -1.0 → 物品朝左下方偏半个单位（sensei 实测「歪到左下方」正是这个）。
            // 所以先把调用方那一层抵掉，再交给原版渲染。
            poseStack.translate(0.5f, 0.5f, 0.5f);
            // ── 坐标系（这段同时管着自绘四边形，2026-09-21 由 1.20.1 源码确认）──────────────
            // `ItemRenderer.render` 的实际顺序是：
            //     handleCameraTransforms(poseStack, model, ctx, false)   ← 把 display 变换写进 poseStack
            //     poseStack.translate(-0.5f, -0.5f, -0.5f)
            //     →（自定义渲染器分支）renderByItem(...)
            // 而**普通分支**那两组 quad 就是在这个 -0.5 之后画的（模型 0..1 平移成 -0.5..0.5）。
            // 也就是说：抵掉 -0.5 之后的我们 = 普通分支画 quad 时**再往原点方向挪半格**的坐标系；
            // 若按 -0.5..0.5 画，最终落点与物品模型 quad 完全重合。
            // ⚠ 一开始想"抄 renderStatic 之前的 pose 状态、按 0..1 画"，但那样会**整体错位半格**
            //   （renderStatic 自己还会再 -0.5 一次，两次平移的缩放基数相同、可以抵消）。
            if (product != null && product.what() != null) {
                final AEKey productKey = product.what();
                if (productKey instanceof AEFluidKey fluidKey) {
                    if (drawFluid(fluidKey, product.amount(), poseStack, bufferSource, packedLight, overlay,
                            context)) {
                        return;     // 自绘成功 → 绝不能再画底图（否则底图会盖在图标上面）
                    }
                } else if (ChemicalCompat.isChemical(productKey)) {
                    if (drawChemical(productKey, poseStack, bufferSource, packedLight, overlay)) {
                        return;
                    }
                }
            }

            final ItemStack toDraw = shift ? baseTemplate() : baseOrItemProduct(stack, product);
            if (toDraw == null || toDraw.isEmpty()) return;   // 模板物品没注册（理论上不会）：宁可不画也不抛

            // 委托原版：位置/缩放/光照/display 变换全部由它负责，与普通物品完全一致
            Minecraft.getInstance().getItemRenderer().renderStatic(
                    toDraw, context, packedLight, overlay, poseStack, bufferSource,
                    Minecraft.getInstance().level, 0);
        } catch (Throwable t) {
            logFailureOnce(t);
        }
    }

    /**
     * 回退路径要画的物品：物品产物 → 它自己；其余（流体/化学品没画成 / 产物是未知 key）→ 底图模板。
     * <p>
     * 只有真走了物品产物才打那条一次性取证日志（以前只要不是 Shift 就会打，纯概率产出的样板会被误报成"走了产物"）。
     */
    private static ItemStack baseOrItemProduct(ItemStack pattern, GenericStack product) {
        ItemStack itemProduct = product == null ? null : itemProduct(product, pattern);
        if (itemProduct == null) {
            return baseTemplate();
        }
        if (!loggedProductRender) {
            loggedProductRender = true;
            LOGGER.info("[ae2addon] 千机样板默认（无 Shift）走产物图标：产物={}（此后不再记录）",
                    itemProduct.getHoverName().getString());
        }
        return itemProduct;
    }

    /**
     * 底图模板 = 一个**真实注册**的隐藏物品（贴图就是原样板贴图）。
     * <p>
     * 为什么必须是真实物品：模型只被"已注册物品"引用时才会被烘焙；不被烘焙 →
     * {@code getModel} 返回缺失模型 → 黑紫方块（这正是第一版的 bug②）。
     * <p>
     * **每次 new 一个**（不要缓存成可变栈）：renderStatic 可能改动传入的 ItemStack 的 count/数据，
     * 共用一个静态栈迟早出鬼。
     */
    private static ItemStack baseTemplate() {
        return new ItemStack(ModItems.QIANJI_PATTERN_TEMPLATE.get());
    }

    /**
     * 物品产物（旧版遗留的入口，现在只被"物品"这一条路用到；流体/化学品不再走它）。
     * <p>
     * 2026-09-21 起**只负责"是物品的产物"**：流体 / 化学品改走 {@link #drawFluid} / {@link #drawChemical} 自绘，
     * 不再使用 AE2 的 {@code WrappedGenericStack}（它的模型是透明占位贴图，sensei 实测显示成透明块）。
     */
    private static ItemStack productOf(ItemStack pattern) {
        GenericStack first = primaryOf(pattern);
        return first == null ? null : itemProduct(first, pattern);
    }

    /** 主产物的原件（流体/化学品也要用），读不出来返回 null */
    private static GenericStack primaryOf(ItemStack pattern) {
        QianJiPatternData data = QianJiPatternData.of(pattern);
        if (data == null) return null;
        if (data.primary().isEmpty()) return null;
        GenericStack first = data.primary().get(0).stack();
        if (first == null || first.what() == null) return null;
        return first;
    }

    /** 物品产物 → 物品栈；其余（流体/化学品/未知）→ null（由调用方决定怎么画） */
    private static ItemStack itemProduct(GenericStack first, ItemStack pattern) {
        if (!(first.what() instanceof AEItemKey itemKey)) return null;
        ItemStack product = itemKey.toStack();   // toStack() 带 NBT，量恒为 1
        if (product.isEmpty() || product.getItem() == pattern.getItem()) return null;
        return product;
    }

    // ─────────────────────────── 流体 ───────────────────────────

    /**
     * 流体产物。**两级**（2026-09-21 sensei 定的修法）：
     * <ol>
     *   <li><b>优先用真实桶物品</b>（{@link #bucketOfFluid}）：有桶 → 走和"物品产物"**完全相同**的
     *       {@code ItemRenderer#renderStatic} 那条路（连那次 {@code translate(0.5,0.5,0.5)} 都一样）。
     *       这是最稳的一条：位置/缩放/光照/display 变换全交给原版，不需要自己碰顶点。
     *       水、岩浆、绝大多数模组流体都有桶 → 这一条能覆盖绝大部分。</li>
     *   <li>没有桶物品（{@code FluidUtil.getFilledBucket} 返回空）→ 才退到 {@link #drawQuad}
     *       画 still 贴图四边形。</li>
     * </ol>
     *
     * @return true = 已经画好（调用方不要再画底图）
     */
    private static boolean drawFluid(AEFluidKey key, long amount, PoseStack poseStack,
                                     MultiBufferSource bufferSource, int light, int overlay,
                                     ItemDisplayContext context) {
        // ── A. 优先真实桶物品（最稳）──────────────────────────────────────────────
        ItemStack bucket = bucketOfFluid(key);
        if (bucket != null && !bucket.isEmpty()) {
            if (!loggedFluidBucketRender) {
                loggedFluidBucketRender = true;
                LOGGER.info("[ae2addon] 千机样板画流体产物（**真实桶物品**，走 renderStatic）：流体={} 桶={}（此后不再记录）",
                        key.getFluid(), bucket.getItem());
            }
            Minecraft.getInstance().getItemRenderer().renderStatic(
                    bucket, context, light, overlay, poseStack, bufferSource,
                    Minecraft.getInstance().level, 0);
            return true;
        }

        // ── B. 没有桶物品 → 自绘 still 贴图四边形 ─────────────────────────────────
        // toStack(int) 是 Forge 补丁进 AE2 的：FluidStack(AEFluidKey#getFluid(), amount)
        //（javap 实证：public net.minecraftforge.fluids.FluidStack toStack(int)）
        FluidStack fs = key.toStack((int) Math.max(1L, Math.min(Integer.MAX_VALUE, amount)));
        if (fs == null || fs.isEmpty()) return false;   // 流体没注册（理论上不会）→ 回退底图

        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fs.getFluid());
        ResourceLocation still = ext.getStillTexture(fs);
        if (still == null) return false;
        int tint = opaque(ext.getTintColor(fs));
        TextureAtlasSprite sprite = sprite(still);
        if (sprite == null) return false;

        if (!loggedFluidRender) {
            loggedFluidRender = true;
            LOGGER.info("[ae2addon] 千机样板画流体产物（自绘四边形，该流体没有桶物品）：流体={} 贴图={} tint=#{}（此后不再记录）",
                    fs.getFluid(), still, Integer.toHexString(tint));
        }
        return drawQuad(poseStack, bufferSource, sprite, tint, light, overlay, "流体");
    }

    /**
     * 该流体有没有"真实物品"（桶）可画？没有返回 null。
     * <p>
     * <b>为什么用这条 API</b>（javap 实证 {@code net.minecraftforge.fluids.FluidUtil}）：
     * <pre>
     * FluidUtil.getFilledBucket(FluidStack): ItemStack
     * FluidStack(Fluid, int)                             ← 构造重载，第二个参数是 mB（这里给 1000，与桶的容量一致）
     * </pre>
     * 字节码路径：水/岩浆直接给 {@code Items.WATER_BUCKET / LAVA_BUCKET}，
     * 其余走 {@code FluidType#getBucket(FluidStack)} → 默认实现是 {@code new ItemStack(fluid.getBucket())}
     * → 没有桶的流体（例如 Mekanism 的化学品流体、其它模组的"无桶"流体）拿到的是 **air** →
     * {@code isEmpty()} 为真 → 自动落到自绘那条路 ✓
     * <p>
     * ⚠ 两条已知限制（都写在日志里了，不是猜的）：
     * <ol>
     *   <li>{@code FluidStack(Fluid,int)} 的构造函数遇到 **null 或未注册的 Fluid** 会
     *       {@code throw new IllegalArgumentException}（javap 字节码实测，不是"可能"）
     *       → 这里必须 try/catch，否则渲染路径会崩（本项目铁律：渲染绝不能崩游戏）；</li>
     *   <li>桶物品**不带流体的 NBT**（{@code getFilledBucket} 里只有"无 tag 或 tag 为空"才走水/岩浆快捷路径，
     *       带 tag 的会走 {@code FluidType.getBucket}，那个默认实现也只用 {@code Fluid}）→
     *       同一流体不同 NBT（例如带药水效果的）图标会一样。这是"用桶当图标"的固有取舍，照实接受。</li>
     * </ol>
     */
    private static ItemStack bucketOfFluid(AEFluidKey key) {
        try {
            return FluidUtil.getFilledBucket(new FluidStack(key.getFluid(), 1000));
        } catch (Throwable t) {
            // 流体未注册 / API 哪天变了：静默退到自绘四边形（不抛、不刷日志）
            return null;
        }
    }

    // ────────────────────────── 化学品 ──────────────────────────

    /**
     * 化学品产物（Applied-Mekanistics 的 key）。
     * <p>
     * **有现成辅助就用**：Mekanism 自己画化学品图标的做法就是
     * {@code Chemical#getIcon()}（方块图集里的 sprite）+ {@code ChemicalStack#getChemicalTint()}，
     * 证据是 appmek 的 {@code AMChemicalStackRenderer#drawOnBlockFace}（那段画 BlockFace 的字节码）
     * 和 {@code MekanismRenderer#getChemicalTexture(Chemical)}（内部就是
     * {@code Minecraft#getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(chemical.getIcon())}）。
     * 所以这里照抄同一套，不自己造图标。
     * <p>
     * 取不到图标（没装 Mekanism / 反射断了）→ 退回**纯色四边形**（tint 或中性灰）。
     * <p>
     * 反射纪律：{@code MekanismKey} / {@code ChemicalStack} / {@code Chemical} 都是**别的 mod 的类**，
     * 我们构建期没有它们（appmek 是 compileOnly，Mekanism 万一缺席也要能跑），所以只能反射；
     * 但拿回来的对象（{@code ResourceLocation}）是原版的，**直接调用、不反射**。
     *
     * @return true = 已经画好（调用方不要再画底图）
     */
    private static boolean drawChemical(AEKey key, PoseStack poseStack, MultiBufferSource bufferSource,
                                        int light, int overlay) {
        ResourceLocation icon = null;
        int tint = CHEMICAL_FALLBACK_TINT;
        try {
            // MekanismKey#getStack() : ChemicalStack<?>
            Object chemicalStack = invokeNoArg(key, "getStack");
            if (chemicalStack != null) {
                // ChemicalStack#getChemicalTint() : int
                Object tintRaw = invokeNoArg(chemicalStack, "getChemicalTint");
                if (tintRaw instanceof Integer i) {
                    tint = opaque(i);
                } else {
                    LoggerHolder.warnOnceChemicalTint();
                }
                // ChemicalStack#getType() : Chemical<?>  →  Chemical#getIcon() : ResourceLocation
                Object chemical = invokeNoArg(chemicalStack, "getType");
                if (chemical != null) {
                    Object iconRaw = invokeNoArg(chemical, "getIcon");
                    if (iconRaw instanceof ResourceLocation rl) {
                        icon = rl;
                    }
                }
            }
        } catch (Throwable t) {
            // 反射断了也绝不往外抛：下面的纯色四边形就是兜底
            LoggerHolder.warnOnceChemical(t);
        }

        TextureAtlasSprite sprite = icon == null ? null : sprite(icon);
        if (sprite == null) {
            // 没有图标（或图标不在方块图集里）→ 纯色：用一张纯白 sprite 乘 tint，等价于纯色块
            sprite = sprite(new ResourceLocation("minecraft", "block/white_concrete"));
            if (sprite == null) return false;   // 连兜底 sprite 都没有 → 回退底图
        }

        if (!loggedChemicalRender) {
            loggedChemicalRender = true;
            LOGGER.info("[ae2addon] 千机样板画化学品产物（{}）：key={} icon={} tint=#{}（此后不再记录）",
                    icon != null ? "图标 sprite" : "纯色兜底", key.getId(),
                    icon == null ? "无（已退化为纯色四边形）" : icon, Integer.toHexString(tint));
        }
        return drawQuad(poseStack, bufferSource, sprite, tint, light, overlay, "化学品");
    }

    // ─────────────────────── 四边形（流体/化学品共用） ───────────────────────

    /**
     * 按 sprite 的 UV 画一个贴图四边形，铺在物品坐标系里（见 {@link #renderByItem} 的坐标系注释）。
     * <p>
     * <b>顶点元素顺序必须与 {@link #QUAD_RENDER_TYPE} 的 {@code VertexFormat} 一致</b>
     * （{@code VertexConsumer} 是按调用顺序往 buffer 里塞属性的；顺序不对就会
     * {@code IllegalStateException: Not filled all elements of the vertex} → 异常被吞 → 表现为"什么都没画"）。javap 实证：
     * <pre>
     * RenderType.entityCutoutNoCull(ResourceLocation): RenderType
     *   → 1.20.1 的 RenderType.java 第 49 行：create("entity_cutout_no_cull", DefaultVertexFormat.NEW_ENTITY, …)
     * com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY（字段顺序，见该类源码）：
     *   Position → Color → UV0 → **UV1(overlay)** → UV2(light) → Normal → Padding
     * 对比旧的 DefaultVertexFormat.BLOCK（translucent() 用的）：
     *   Position → Color → UV0 → UV2(light) → Normal → Padding          ← **没有 UV1(overlay)**
     * 两者的差别就在这里：NEW_ENTITY 一定要在 uv 之后、uv2 之前插一次 overlayCoords。
     * </pre>
     * 所以本方法是：{@code vertex → color → uv → overlayCoords → uv2 → normal → endVertex}
     * （{@code VertexConsumer#overlayCoords(int)} = 把打包值拆成 (u,v) 两个 short 后调两参重载，
     *  javap 实证过它的 default 实现）。
     * <p>
     * 光照/overlay 都用调用方传进来的；不剔除（NO_CULL）→ **一面就够**，不再需要"两种绕序各画一遍"。
     *
     * @param category 只用于一次性失败日志的分类（"流体" / "化学品"），不影响绘制
     * @return true = 顶点都填好了；false = 填顶点时抛了异常（本次没画出来，调用方应回退底图）
     */
    private static boolean drawQuad(PoseStack poseStack, MultiBufferSource bufferSource,
                                    TextureAtlasSprite sprite, int argb, int light, int overlay,
                                    String category) {
        VertexConsumer vc = bufferSource.getBuffer(QUAD_RENDER_TYPE);
        var pose = poseStack.last().pose();

        final float u0 = sprite.getU0();
        final float u1 = sprite.getU1();
        final float v0 = sprite.getV0();
        final float v1 = sprite.getV1();
        final float a = QUAD_HALF;
        final float b = -QUAD_HALF;

        try {
            // 左下 → 右下 → 右上 → 左上（x/y 从 -0.5 到 0.5，z 抬一点点避开模型自身）
            vertex(vc, pose, b, b, u0, v1, argb, light, overlay);
            vertex(vc, pose, a, b, u1, v1, argb, light, overlay);
            vertex(vc, pose, a, a, u1, v0, argb, light, overlay);
            vertex(vc, pose, b, a, u0, v0, argb, light, overlay);
            return true;
        } catch (Throwable t) {
            // 顶点填错（格式元素没填满 / buffer 状态不对）→ 只回退"这一块"，别让异常冒到 renderByItem
            // 把后面本该画的底图也一起废掉。按类别各记一次（原来全局一个标志位，流体的失败会盖住化学品的）。
            boolean alreadyLogged;
            if ("流体".equals(category)) {
                alreadyLogged = loggedFluidFillFailure;
                loggedFluidFillFailure = true;
            } else {
                alreadyLogged = loggedChemicalFillFailure;
                loggedChemicalFillFailure = true;
            }
            if (!alreadyLogged) {
                LOGGER.warn("[ae2addon] 千机样板自绘四边形失败（{}）→ 该图标本次回退为底图：{}",
                        category, t.toString());
            }
            return false;
        }
    }

    private static void vertex(VertexConsumer vc, org.joml.Matrix4f pose, float x, float y,
                               float u, float v, int argb, int light, int overlay) {
        vc.vertex(pose, x, y, QUAD_Z)
                .color(argb)            // int 版按 ARGB 解包（javap 实证：FastColor.ARGB32.red/green/blue/alpha）
                .uv(u, v)
                // ⚠ NEW_ENTITY 的 overlay 元素在这里（旧 BLOCK 格式**没有**这一步）——
                // 漏了它同样会 "Not filled all elements of the vertex"。
                .overlayCoords(overlay)
                .uv2(light)
                // 2026-09-20 sensei 实测「还是透明」时日志给出的真因：
                // 原来漏了 **normal** → 顶点不完整 → 抛异常被吞 → 什么都画不出来。
                // 法线补在 uv2 之后（与 NEW_ENTITY / BLOCK 的元素顺序一致），朝 +Z。
                .normal(0f, 0f, 1f)
                .endVertex();
    }

    // ───────────────────────────── 小工具 ─────────────────────────────

    /**
     * 从方块图集取 sprite。取不到时**返回 null**（而不是让 {@code Minecraft} 抛异常：
     * 1.20.1 的 {@code TextureAtlas#getSprite} 会把 {@code ResourceLocation} 直接丢进
     * {@code Preconditions.checkNotNull}，传 null 会抛 NPE）。
     */
    private static TextureAtlasSprite sprite(ResourceLocation location) {
        if (location == null) return null;
        try {
            return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(location);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 让颜色一定可见：alpha 为 0（有些流体/化学品没填 alpha）时补成不透明 */
    private static int opaque(int argb) {
        return (argb & 0xFF000000) == 0 ? (argb | 0xFF000000) : argb;
    }

    /** 反射调一个**无参**方法（别的 mod 的类才需要；拿回来的原版对象一律直接调用） */
    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    private static void logFailureOnce(Throwable t) {
        if (loggedFailure) return;
        loggedFailure = true;
        LOGGER.warn("[ae2addon] 千机样板渲染失败（已回退为不渲染，后续同类异常不再记录）: {}", t.toString());
    }

    /** 化学品这段的告警各自只打一次（渲染路径不能刷日志） */
    private static final class LoggerHolder {
        private static boolean warnedTint = false;
        private static boolean warnedReflect = false;

        private static void warnOnceChemicalTint() {
            if (warnedTint) return;
            warnedTint = true;
            LOGGER.warn("[ae2addon] 化学品 tint 取不到（ChemicalStack#getChemicalTint 反射断了）→ 用中性灰 {}",
                    Integer.toHexString(CHEMICAL_FALLBACK_TINT));
        }

        private static void warnOnceChemical(Throwable t) {
            if (warnedReflect) return;
            warnedReflect = true;
            LOGGER.warn("[ae2addon] 化学品图标反射失败（该产物退化为纯色四边形，后续不再记录）: {}", t.toString());
        }
    }
}
