package appeng.client.gui;

import appeng.api.storage.data.IAEItemStack;
import appeng.client.gui.implementations.GuiCraftAmount;
import appeng.client.gui.implementations.GuiCraftConfirm;
import appeng.client.gui.implementations.GuiCraftingCPU;
import appeng.client.gui.implementations.GuiExpandedProcessingPatternTerm;
import appeng.client.gui.implementations.GuiPatternTerm;
import appeng.client.gui.implementations.GuiUpgradeable;
import appeng.client.gui.widgets.GuiCustomSlot;
import appeng.container.interfaces.IJEIGhostIngredients;
import appeng.container.interfaces.ISpecialSlotIngredient;
import appeng.container.slot.IJEITargetSlot;
import mezz.jei.api.gui.IAdvancedGuiHandler;
import mezz.jei.api.gui.IGhostIngredientHandler;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import org.lwjgl.input.Mouse;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;


public class AEGuiHandler implements IAdvancedGuiHandler<AEBaseGui>, IGhostIngredientHandler<AEBaseGui> {
    @Override
    @Nonnull
    public Class<AEBaseGui> getGuiContainerClass() {
        return AEBaseGui.class;
    }

    @Nullable
    @Override
    public List<Rectangle> getGuiExtraAreas(@Nonnull AEBaseGui guiContainer) {
        return guiContainer.getJEIExclusionArea();
    }

    @Nullable
    @Override
    public Object getIngredientUnderMouse(@Nonnull AEBaseGui guiContainer, int mouseX, int mouseY) {
        List<IAEItemStack> visual;
        int guiSlotIdx;
        Object result = null;
        if (guiContainer instanceof GuiCraftConfirm) {
            guiSlotIdx = getSlotidx(guiContainer, mouseX, mouseY, ((GuiCraftConfirm) guiContainer).getDisplayedRows());
            visual = ((GuiCraftConfirm) guiContainer).getVisual();
            if (guiSlotIdx < visual.size() && guiSlotIdx != -1) {
                result = visual.get(guiSlotIdx).getDefinition();
            } else {
                return null;
            }
        }

        if (guiContainer instanceof GuiCraftingCPU) {
            guiSlotIdx = getSlotidx(guiContainer, mouseX, mouseY, ((GuiCraftingCPU) guiContainer).getDisplayedRows());
            visual = ((GuiCraftingCPU) guiContainer).getVisual();
            if (guiSlotIdx < visual.size() && guiSlotIdx != -1) {
                result = visual.get(guiSlotIdx).getDefinition();
            } else {
                return null;
            }
        }

        if (guiContainer instanceof GuiCraftAmount) {
            if (guiContainer.getSlotUnderMouse() != null) {
                result = guiContainer.getSlotUnderMouse().getStack();
            } else {
                return null;
            }
        }

        if (result != null) {
            return result;
        }

        Slot slot = guiContainer.getSlotUnderMouse();
        if (slot instanceof ISpecialSlotIngredient ss) {
            return ss.getIngredient();
        }
        for (GuiCustomSlot customSlot : guiContainer.guiSlots) {
            if (this.checkSlotArea(guiContainer, customSlot, mouseX, mouseY)) {
                return customSlot.getIngredient();
            }
        }

        return result;
    }

    private boolean checkSlotArea(GuiContainer gui, GuiCustomSlot slot, int mouseX, int mouseY) {
        int i = gui.guiLeft;
        int j = gui.guiTop;
        mouseX = mouseX - i;
        mouseY = mouseY - j;
        return mouseX >= slot.xPos() - 1 &&
                mouseX < slot.xPos() + slot.getWidth() + 1 &&
                mouseY >= slot.yPos() - 1 &&
                mouseY < slot.yPos() + slot.getHeight() + 1;
    }

    private int getSlotidx(AEBaseGui guiContainer, int mouseX, int mouseY, int rows) {
        int guileft = guiContainer.getGuiLeft();
        int guitop = guiContainer.getGuiTop();
        int currentScroll = guiContainer.getScrollBar().getCurrentScroll();
        final int xo = 9;
        final int yo = 19;

        int guiSlotx = (mouseX - guileft - xo) / 67;
        if (guiSlotx > 2 || mouseX < guileft + xo) return -1;
        int guiSloty = (mouseY - guitop - yo) / 23;
        if (guiSloty > (rows - 1) || mouseY < guitop + yo) return -1;
        return (guiSloty * 3) + guiSlotx + (currentScroll * 3);
    }

    @SuppressWarnings("unchecked")
    @Override
    @Nonnull
    public <I> List<Target<I>> getTargets(@Nonnull AEBaseGui gui, @Nonnull I ingredient, boolean doStart) {
        ArrayList<Target<I>> targets = new ArrayList<>();
        if (gui instanceof IJEIGhostIngredients g) {
            List<Target<?>> phantomTargets = g.getPhantomTargets(ingredient);
            targets.addAll((List<Target<I>>) (Object) phantomTargets);
        }
        if (doStart && GuiScreen.isShiftKeyDown() && Mouse.isButtonDown(0)) {
            if (gui instanceof GuiUpgradeable || gui instanceof GuiPatternTerm || gui instanceof GuiExpandedProcessingPatternTerm) {
                IJEIGhostIngredients ghostGui = ((IJEIGhostIngredients) gui);
                for (Target<I> target : targets) {
                    if (ghostGui.getFakeSlotTargetMap().get(target) instanceof IJEITargetSlot jeiSlot) {
                        if (jeiSlot.needAccept()) {
                            target.accept(ingredient);
                            break;
                        }
                    }
                }
            }
        }
        return targets;
    }

    @Override
    public void onComplete() {
    }

    @Override
    public boolean shouldHighlightTargets() {
        return true;
    }

}
