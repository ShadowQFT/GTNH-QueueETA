package com.shadowqft.gtnhqueueeta.client;

import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;

final class GuiTooltipRenderer extends GuiScreen {

    private GuiTooltipRenderer() {}

    static void draw(List<String> lines, int mouseX, int mouseY, FontRenderer fontRenderer) {
        new GuiTooltipRenderer().drawHoveringText(lines, mouseX, mouseY, fontRenderer);
    }
}
