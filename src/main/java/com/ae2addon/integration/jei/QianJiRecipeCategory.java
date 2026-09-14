package com.ae2addon.integration.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.ae2addon.AE2Addon;
import com.ae2addon.init.ModItems;
import com.ae2addon.recipe.QianJiPatternData;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 页「千机·自用配方」（2026-09-15 sensei 定稿）。
 * <p>
 * 版面（2026-09-15 二次优化）：
 * <ul>
 *   <li>**固定槽位网格**：物品输入 9 格 / 物品输出 9 格 / 流体输入 2 格 / 流体输出 2 格 —— 空着也画出来</li>
 *   <li>**不够自动扩槽**：物品每 9 格一行、流体每 2 格一行，按需加行（上限见 MAX_* 常量）</li>
 *   <li>**非物品一律进流体槽**：槽位按第一个选项的类型分类（物品 → 物品槽，其余 → 流体槽），
 *       以后接别 mod 的非物品输入（气体/化学物等）也自动落到流体槽</li>
 *   <li>右下「编码」按钮 → 生成千机配方样板（成本与 AE2 编码一致）</li>
 * </ul>
 */
public class QianJiRecipeCategory implements IRecipeCategory<QianJiRecipeCategory.Entry> {

    public static final RecipeType<Entry> TYPE =
            new RecipeType<>(new ResourceLocation(AE2Addon.MODID, "qianji_recipes"), Entry.class);

    // ── 版面常量 ──
    private static final int WIDTH = 176;
    private static final int EDGE = 6;
    private static final int STEP = 18;
    /** 物品槽每行几个 */
    private static final int ITEMS_PER_ROW = 9;
    /** 流体槽每行几个 */
    private static final int FLUIDS_PER_ROW = 2;
    /** 上限（超出只显示前 N 个） */
    private static final int MAX_ITEM_SLOTS = 18;
    private static final int MAX_FLUID_SLOTS = 4;

    private static final int Y_ITEM_IN = 22;
    private static final int Y_FLUID_IN = 62;
    private static final int Y_ITEM_OUT = 118;
    private static final int Y_FLUID_OUT = 158;
    private static final int HEIGHT = 214;

    /** 「编码」按钮区域（页面内坐标） */
    private static final int BTN_X = 146;
    private static final int BTN_Y = 190;
    private static final int BTN_W = 24;
    private static final int BTN_H = 16;

    private final IDrawable icon;
    private final IDrawable arrow;

    /** 一条配方：来源 id + 我们提取出的数据 */
    public record Entry(String recipeId, QianJiPatternData data) {}

    public QianJiRecipeCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemStack(new ItemStack(ModItems.QIAN_JI_PATTERN.get()));
        this.arrow = guiHelper.getRecipeArrow();
    }

    public static Entry of(Recipe<?> recipe, QianJiPatternData data) {
        ResourceLocation id = recipe.getId();
        return new Entry(id == null ? "" : id.toString(), data);
    }

    @Override
    public RecipeType<Entry> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("gui.ae2addon.jei.qianji.title");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    // ── 版面计算（setRecipe 与 draw 共用，避免坐标漂移）──

    /** 槽位坐标：每行 perRow 个，步长 STEP */
    private static List<int[]> grid(int count, int perRow, int startX, int startY) {
        var points = new ArrayList<int[]>();
        for (int i = 0; i < count; i++) {
            points.add(new int[]{startX + (i % perRow) * STEP, startY + (i / perRow) * STEP});
        }
        return points;
    }

    /** 输入槽按「物品 / 非物品（流体等）」分类；非物品一律进流体槽 */
    private static List<List<GenericStack>>[] splitInputs(QianJiPatternData data) {
        var items = new ArrayList<List<GenericStack>>();
        var fluids = new ArrayList<List<GenericStack>>();
        for (QianJiPatternData.Slot slot : data.inputs()) {
            if (slot.options().isEmpty()) continue;
            boolean isItem = slot.options().get(0).what() instanceof AEItemKey;
            (isItem ? items : fluids).add(slot.options());
        }
        return new List[]{items, fluids};
    }

    /** 产出按物品/非物品分类（主产物 + 概率产出同列，概率产出在 draw 里标 % ） */
    private static List<GenericStack>[] splitOutputs(QianJiPatternData data) {
        var items = new ArrayList<GenericStack>();
        var fluids = new ArrayList<GenericStack>();
        for (QianJiPatternData.Out out : data.primary()) {
            (out.stack().what() instanceof AEItemKey ? items : fluids).add(out.stack());
        }
        for (QianJiPatternData.Chanced chanced : data.chanced()) {
            (chanced.stack().what() instanceof AEItemKey ? items : fluids).add(chanced.stack());
        }
        return new List[]{items, fluids};
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Entry entry, IFocusGroup focuses) {
        QianJiPatternData data = entry.data();

        var inSplit = splitInputs(data);
        var itemIn = inSplit[0];
        var fluidIn = inSplit[1];

        // 物品输入：固定 9 格起，不够扩行
        int itemInCount = Math.max(ITEMS_PER_ROW, Math.min(itemIn.size(), MAX_ITEM_SLOTS));
        for (int i = 0; i < itemInCount; i++) {
            var point = grid(itemInCount, ITEMS_PER_ROW, EDGE, Y_ITEM_IN).get(i);
            var slotBuilder = builder.addInputSlot(point[0], point[1]);
            if (i < itemIn.size()) addOptions(slotBuilder, itemIn.get(i));
        }

        // 流体（非物品）输入：固定 2 格起，不够扩行
        int fluidInCount = Math.max(FLUIDS_PER_ROW, Math.min(fluidIn.size(), MAX_FLUID_SLOTS));
        for (int i = 0; i < fluidInCount; i++) {
            var point = grid(fluidInCount, FLUIDS_PER_ROW, EDGE, Y_FLUID_IN).get(i);
            var slotBuilder = builder.addInputSlot(point[0], point[1]);
            if (i < fluidIn.size()) addOptions(slotBuilder, fluidIn.get(i));
        }

        var outSplit = splitOutputs(data);
        var itemOut = outSplit[0];
        var fluidOut = outSplit[1];

        int itemOutCount = Math.max(ITEMS_PER_ROW, Math.min(itemOut.size(), MAX_ITEM_SLOTS));
        for (int i = 0; i < itemOutCount; i++) {
            var point = grid(itemOutCount, ITEMS_PER_ROW, EDGE, Y_ITEM_OUT).get(i);
            var slotBuilder = builder.addOutputSlot(point[0], point[1]);
            if (i < itemOut.size()) addStack(slotBuilder, itemOut.get(i));
        }

        int fluidOutCount = Math.max(FLUIDS_PER_ROW, Math.min(fluidOut.size(), MAX_FLUID_SLOTS));
        for (int i = 0; i < fluidOutCount; i++) {
            var point = grid(fluidOutCount, FLUIDS_PER_ROW, EDGE, Y_FLUID_OUT).get(i);
            var slotBuilder = builder.addOutputSlot(point[0], point[1]);
            if (i < fluidOut.size()) addStack(slotBuilder, fluidOut.get(i));
        }
    }

    private static void addOptions(mezz.jei.api.gui.builder.IRecipeSlotBuilder slotBuilder,
                                   List<GenericStack> options) {
        for (GenericStack option : options) {
            addStack(slotBuilder, option);
        }
    }

    private static void addStack(mezz.jei.api.gui.builder.IRecipeSlotBuilder slotBuilder, GenericStack stack) {
        if (stack == null || stack.what() == null) return;
        if (stack.what() instanceof AEItemKey itemKey) {
            slotBuilder.addItemStack(itemKey.toStack((int) Math.max(1, stack.amount())));
        } else if (stack.what() instanceof AEFluidKey fluidKey) {
            slotBuilder.addFluidStack(fluidKey.getFluid(), stack.amount());
        }
    }

    @Override
    public void draw(Entry entry, IRecipeSlotsView slotsView, GuiGraphics graphics, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        QianJiPatternData data = entry.data();

        // 标题行：输入 / 输出分区标签
        graphics.drawString(font, "§7输入 §8(物品 9 · 流体 2，不足自动扩)", EDGE, 8, 0xFFFFFF, false);
        graphics.drawString(font, "§7产出 §8(▲ 绿=主产物 §d紫=概率产出)", EDGE, 104, 0xFFFFFF, false);

        // 概率产出：在对应槽位下方标 %（位置与 setRecipe 同算法）
        var outSplit = splitOutputs(data);
        var itemOut = outSplit[0];
        var fluidOut = outSplit[1];
        var chances = new ArrayList<Float>();
        for (var chanced : data.chanced()) chances.add(chanced.chance());

        int itemOutCount = Math.max(ITEMS_PER_ROW, Math.min(itemOut.size(), MAX_ITEM_SLOTS));
        var itemOutPoints = grid(itemOutCount, ITEMS_PER_ROW, EDGE, Y_ITEM_OUT);
        for (int i = 0; i < Math.min(itemOut.size(), itemOutPoints.size()); i++) {
            float chance = chanceOf(data, itemOut.get(i));
            if (chance < 0f) continue;
            String pct = chance > 0f ? Math.round(chance * 100) + "%" : "?";
            var point = itemOutPoints.get(i);
            graphics.drawString(font, "§d" + pct, point[0], point[1] + 17, 0xFFFFFF, false);
        }

        int fluidOutCount = Math.max(FLUIDS_PER_ROW, Math.min(fluidOut.size(), MAX_FLUID_SLOTS));
        var fluidOutPoints = grid(fluidOutCount, FLUIDS_PER_ROW, EDGE, Y_FLUID_OUT);
        for (int i = 0; i < Math.min(fluidOut.size(), fluidOutPoints.size()); i++) {
            float chance = chanceOf(data, fluidOut.get(i));
            if (chance < 0f) continue;
            String pct = chance > 0f ? Math.round(chance * 100) + "%" : "?";
            var point = fluidOutPoints.get(i);
            graphics.drawString(font, "§d" + pct, point[0], point[1] + 17, 0xFFFFFF, false);
        }

        // 来源配方 id
        if (entry.recipeId() != null && !entry.recipeId().isEmpty()) {
            graphics.drawString(font, "§8" + trim(entry.recipeId(), 40), EDGE, HEIGHT - 10, 0xFFFFFF, false);
        }

        // 编码按钮
        boolean hover = mouseX >= BTN_X && mouseX <= BTN_X + BTN_W
                && mouseY >= BTN_Y && mouseY <= BTN_Y + BTN_H;
        graphics.fill(BTN_X, BTN_Y, BTN_X + BTN_W, BTN_Y + BTN_H, hover ? 0x8040FF40 : 0x60207020);
        graphics.drawString(font, "§a编码", BTN_X + 2, BTN_Y + 4, 0xFFFFFF, false);
    }

    /** 该产出在数据里是概率产出吗（是则返回其几率，否则 -1） */
    private static float chanceOf(QianJiPatternData data, GenericStack stack) {
        for (QianJiPatternData.Chanced chanced : data.chanced()) {
            if (chanced.stack() == stack) return chanced.chance();
        }
        return -1f;
    }

    @Override
    public boolean handleInput(Entry entry, double mouseX, double mouseY,
                               com.mojang.blaze3d.platform.InputConstants.Key key) {
        if (mouseX < BTN_X || mouseX > BTN_X + BTN_W || mouseY < BTN_Y || mouseY > BTN_Y + BTN_H) {
            return false;
        }
        if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return false;
        }
        AE2Addon.NETWORK.sendToServer(new com.ae2addon.network.QianJiPatternPacket(entry.recipeId()));
        return true;
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
