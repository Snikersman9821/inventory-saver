/*
 * Copyright (C) 2026 Snikersman9821
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package de.snikersman9821.inventory_saver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes the save files: one JSON file per server/world in config/inventory_saver/.
 */
public final class InventoryStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private InventoryStorage() {
    }

    /** One saved inventory: menu slot index -> serialized ItemStack (JSON). */
    public static class SavedInventory {
        public long savedAt;
        public Map<Integer, JsonElement> stacks = new LinkedHashMap<>();
    }

    /** Content of one JSON file: save slot number (1-based) -> saved inventory. */
    public static class SaveFile {
        public Map<Integer, SavedInventory> slots = new LinkedHashMap<>();
    }

    /** Builds a file-name-safe key for the current server or singleplayer world. */
    public static String contextKey(Minecraft mc) {
        String raw = "unknown";
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            raw = "server_" + server.ip;
        } else if (mc.getSingleplayerServer() != null) {
            raw = "world_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path fileFor(String key) {
        return ModConfig.dir().resolve(key + ".json");
    }

    private static SaveFile read(String key) {
        Path file = fileFor(key);
        if (!Files.exists(file)) {
            return new SaveFile();
        }
        try {
            SaveFile loaded = GSON.fromJson(Files.readString(file), SaveFile.class);
            if (loaded != null && loaded.slots != null) {
                return loaded;
            }
        } catch (IOException | JsonParseException e) {
            InventorySaverMod.LOGGER.error("Could not read save file {}", file, e);
        }
        return new SaveFile();
    }

    /** Returns the saved inventory for the given slot, or null if there is none. */
    public static SavedInventory load(String key, int slot) {
        return read(key).slots.get(slot);
    }

    public static void store(String key, int slot, SavedInventory inventory) throws IOException {
        SaveFile file = read(key);
        file.slots.put(slot, inventory);
        Files.createDirectories(ModConfig.dir());
        Files.writeString(fileFor(key), GSON.toJson(file));
    }
}
