/*
 * Copyright (C) 2026 Snikersman9821
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package de.snikersman9821.inventory_saver;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Inventory preview of the active profile plus item/count input, embedded in the config screen.
 * The screen owns scrolling: it passes the current scroll offset to render and click.
 */
public final class ProfilePreview {
    /** Lets the screen position, register and scroll the widgets created here. */
    public interface Placer {
        void place(AbstractWidget widget, int x, int y);
    }

    private static final int PANEL_W = 176;
    private static final int PANEL_H = 114;
    private static final int HEIGHT = PANEL_H + 4 + 20 + 3 + 9;

    private final Font font;
    private final String contextKey;
    private final ItemStack[] shown = new ItemStack[InventoryActions.MENU_SIZE];
    private RegistryOps<JsonElement> ops;
    private InventoryStorage.SavedInventory saved;
    private int profile;

    private int selected = -1;
    private int held = -1;
    private int ox;
    private int baseY;
    private int centerX;
    private EditBox itemBox;
    private EditBox countBox;
    private StringWidget statusWidget;
    private Component status = Component.empty();

    public ProfilePreview(Font font) {
        this.font = font;
        Minecraft mc = Minecraft.getInstance();
        this.contextKey = InventoryStorage.contextKey(mc);
        if (mc.level != null) {
            this.ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        }
        reload();
    }

    public int height() {
        return HEIGHT;
    }

    /** Reads the active profile from disk again (after switching profiles). */
    public void reload() {
        profile = ModConfig.effectiveSlot();
        InventoryStorage.SavedInventory loaded = InventoryStorage.load(contextKey, profile);
        if (loaded == null || loaded.stacks == null) {
            loaded = new InventoryStorage.SavedInventory();
            loaded.savedAt = System.currentTimeMillis();
        }
        saved = loaded;
        Arrays.fill(shown, ItemStack.EMPTY);
        if (ops != null) {
            for (Map.Entry<Integer, JsonElement> e : saved.stacks.entrySet()) {
                int slot = e.getKey();
                if (slot < InventoryActions.FIRST_SLOT || slot > InventoryActions.LAST_SLOT) {
                    continue;
                }
                shown[slot] = ItemStack.CODEC.parse(ops, e.getValue())
                        .resultOrPartial(err -> InventorySaverMod.LOGGER.error("Could not decode stack: {}", err))
                        .orElse(ItemStack.EMPTY);
            }
        }
        selected = -1;
        held = -1;
        if (itemBox != null) {
            itemBox.setValue("");
            countBox.setValue("");
        }
        setStatus("");
    }

    /** Creates the input widgets. (centerX, y) is the top center of the preview, y without scroll. */
    public void build(int centerX, int y, Placer placer) {
        this.centerX = centerX;
        this.ox = centerX - PANEL_W / 2;
        this.baseY = y;
        int left = centerX - 152;
        int rowY = y + PANEL_H + 4;

        itemBox = new EditBox(font, left, rowY, 150, 20, Component.translatable("inventory_saver.preview.item"));
        itemBox.setMaxLength(100);
        itemBox.setHint(Component.translatable("inventory_saver.preview.item"));
        placer.place(itemBox, left, rowY);

        countBox = new EditBox(font, left + 154, rowY, 40, 20, Component.translatable("inventory_saver.preview.count"));
        countBox.setMaxLength(2);
        countBox.setHint(Component.translatable("inventory_saver.preview.count"));
        placer.place(countBox, left + 154, rowY);

        placer.place(Button.builder(Component.translatable("inventory_saver.preview.apply"), b -> apply())
                .bounds(0, 0, 54, 20).build(), left + 198, rowY);
        placer.place(Button.builder(Component.translatable("inventory_saver.preview.clear"), b -> clear())
                .bounds(0, 0, 48, 20).build(), left + 256, rowY);

        statusWidget = new StringWidget(left, rowY + 23, 1, 9, status, font);
        placer.place(statusWidget, left, rowY + 23);
        centerStatus();

        if (selected >= 0) {
            fillBoxes(selected);
        }
    }

    // ---- geometry (item position, the 18x18 frame starts 1 px up/left) ----

    private int slotX(int s) {
        if (s >= 5 && s <= 8) {
            return ox + 8 + (s - 5) * 18;
        }
        if (s == 45) {
            return ox + 8 + 5 * 18;
        }
        return ox + 8 + ((s - 9) % 9) * 18;
    }

    private static int slotY(int s, int oy) {
        if (s <= 8 || s == 45) {
            return oy + 8;
        }
        if (s <= 35) {
            return oy + 32 + ((s - 9) / 9) * 18;
        }
        return oy + 90;
    }

    private int slotAt(double mx, double my, int oy) {
        for (int s = InventoryActions.FIRST_SLOT; s <= InventoryActions.LAST_SLOT; s++) {
            int x = slotX(s);
            int y = slotY(s, oy);
            if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                return s;
            }
        }
        return -1;
    }

    // ---- rendering and input ----

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, int scroll, boolean mouseActive) {
        int oy = baseY - scroll;
        Gfx.panel(g, ox, oy, PANEL_W, PANEL_H);

        int hovered = mouseActive ? slotAt(mouseX, mouseY, oy) : -1;
        for (int s = InventoryActions.FIRST_SLOT; s <= InventoryActions.LAST_SLOT; s++) {
            int x = slotX(s);
            int y = slotY(s, oy);
            Gfx.slot(g, x - 1, y - 1);
            if (!shown[s].isEmpty()) {
                Gfx.item(g, font, shown[s], x, y);
            }
            if (s == selected) {
                Gfx.outline(g, x - 1, y - 1, 18, 18, 0xFFFFFF55);
            } else if (s == hovered) {
                Gfx.fill(g, x, y, x + 16, y + 16, 0x80FFFFFF);
            }
        }
        if (hovered >= 0 && !shown[hovered].isEmpty()) {
            Gfx.tooltip(g, font, shown[hovered], mouseX, mouseY);
        }
    }

    /** Returns true if a slot was clicked. First click picks up, second click moves or swaps. */
    public boolean click(double mouseX, double mouseY, int scroll) {
        int s = slotAt(mouseX, mouseY, baseY - scroll);
        if (s < 0) {
            return false;
        }
        if (held < 0) {
            selected = s;
            fillBoxes(s);
            setStatus("");
            if (!shown[s].isEmpty()) {
                held = s;
            }
        } else if (held == s) {
            held = -1;
        } else {
            int from = held;
            held = -1;
            swap(from, s);
        }
        return true;
    }

    /** Puts a picked-up item back. */
    public void cancelHold() {
        held = -1;
    }

    private void fillBoxes(int s) {
        ItemStack st = shown[s];
        itemBox.setValue(st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        countBox.setValue(st.isEmpty() ? "1" : Integer.toString(st.getCount()));
    }

    private void setStatus(String text) {
        status = Component.literal(text);
        if (statusWidget != null) {
            statusWidget.setMessage(status);
            centerStatus();
        }
    }

    private void centerStatus() {
        int w = Math.max(1, font.width(status));
        statusWidget.setWidth(w);
        statusWidget.setX(centerX - w / 2);
    }

    // ---- editing ----

    private static Optional<Item> parseItem(String text) {
        Identifier id = Identifier.tryParse(text.toLowerCase(Locale.ROOT));
        if (id == null) {
            return Optional.empty();
        }
        return BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR);
    }

    private void apply() {
        if (selected < 0) {
            setStatus("Click a slot first.");
            return;
        }
        String text = itemBox.getValue().trim();
        if (text.isEmpty()) {
            clear();
            return;
        }
        Optional<Item> item = parseItem(text);
        if (item.isEmpty()) {
            setStatus("Unknown item: " + text);
            return;
        }
        int count;
        try {
            count = Integer.parseInt(countBox.getValue().trim());
        } catch (NumberFormatException e) {
            setStatus("Count must be a number.");
            return;
        }
        ItemStack old = shown[selected];
        // Same item: keep enchantments, name etc. and only change the count.
        ItemStack stack = (!old.isEmpty() && old.getItem() == item.get()) ? old.copy() : new ItemStack(item.get());
        stack.setCount(Math.max(1, Math.min(stack.getMaxStackSize(), count)));
        if (!canPlace(selected, stack)) {
            setStatus("That item can't go into this slot.");
            return;
        }
        place(selected, stack);
    }

    private void clear() {
        if (selected < 0) {
            setStatus("Click a slot first.");
            return;
        }
        place(selected, ItemStack.EMPTY);
    }

    private static boolean canPlace(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return true;
        }
        return slot < p.inventoryMenu.slots.size() && p.inventoryMenu.slots.get(slot).mayPlace(stack);
    }

    private static boolean sameStack(ItemStack x, ItemStack y) {
        return x == y || (x.isEmpty() && y.isEmpty());
    }

    private void swap(int a, int b) {
        ItemStack sa = shown[a];
        ItemStack sb = shown[b];
        if (!canPlace(b, sa) || !canPlace(a, sb)) {
            setStatus("That item can't go into this slot.");
            return;
        }
        place(a, sb);
        place(b, sa);
        if (!sameStack(shown[a], sb) || !sameStack(shown[b], sa)) {
            return;
        }
        selected = b;
        fillBoxes(b);
        setStatus(sb.isEmpty() ? "Item moved." : "Items swapped.");
    }

    private void place(int slot, ItemStack stack) {
        if (ops == null) {
            setStatus("Preview needs a loaded world.");
            return;
        }
        if (stack.isEmpty()) {
            saved.stacks.remove(slot);
        } else {
            Optional<JsonElement> json = ItemStack.CODEC.encodeStart(ops, stack)
                    .resultOrPartial(err -> InventorySaverMod.LOGGER.error("Could not encode stack: {}", err));
            if (json.isEmpty()) {
                setStatus("Could not encode the item.");
                return;
            }
            saved.stacks.put(slot, json.get());
        }
        saved.savedAt = System.currentTimeMillis();
        try {
            InventoryStorage.store(contextKey, profile, saved);
            shown[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack;
            fillBoxes(slot);
            setStatus(stack.isEmpty() ? "Slot cleared." : "Slot updated.");
        } catch (IOException e) {
            InventorySaverMod.LOGGER.error("Could not write save file", e);
            setStatus("Saving failed: " + e.getMessage());
        }
    }
}
