/*
 * Copyright (C) 2026 Snikersman9821
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package de.snikersman9821.inventory_saver;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Only this class touches the draw API (fill, items, tooltips),
 * so a rename in a newer Minecraft version needs a change in one place.
 */
public final class Gfx {
    private Gfx() {
    }

    public static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int argb) {
        g.fill(x1, y1, x2, y2, argb);
    }

    public static void item(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
        g.item(stack, x, y);
        g.itemDecorations(font, stack, x, y);
    }

    public static void tooltip(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
        g.setTooltipForNextFrame(font, stack, x, y);
    }

    /** Vanilla-like grey panel with a 3D border. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        fill(g, x, y, x + w, y + h, 0xFF000000);
        fill(g, x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
        fill(g, x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        fill(g, x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
    }

    /** 18x18 slot frame, (x, y) is the top-left corner of the frame. */
    public static void slot(GuiGraphicsExtractor g, int x, int y) {
        fill(g, x, y, x + 18, y + 18, 0xFF373737);
        fill(g, x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        fill(g, x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    public static void scissorOn(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.enableScissor(x1, y1, x2, y2);
    }

    public static void scissorOff(GuiGraphicsExtractor g) {
        g.disableScissor();
    }

    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        fill(g, x, y, x + w, y + 1, argb);
        fill(g, x, y + h - 1, x + w, y + h, argb);
        fill(g, x, y + 1, x + 1, y + h - 1, argb);
        fill(g, x + w - 1, y + 1, x + w, y + h - 1, argb);
    }
}
