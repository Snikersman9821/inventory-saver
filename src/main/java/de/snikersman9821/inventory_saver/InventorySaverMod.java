package de.snikersman9821.inventory_saver;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InventorySaverMod implements ClientModInitializer {
    public static final String MOD_ID = "inventory_saver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping saveKey;
    private static KeyMapping restoreKey;
    private static KeyMapping configKey;
    private static final KeyMapping[] saveProfileKeys = new KeyMapping[ModConfig.MAX_SAVE_SLOTS];
    private static final KeyMapping[] restoreProfileKeys = new KeyMapping[ModConfig.MAX_SAVE_SLOTS];

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        saveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory_saver.save", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        restoreKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory_saver.restore", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, category));
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.inventory_saver.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

        // One save key and one restore key per profile, unbound by default.
        for (int i = 0; i < ModConfig.MAX_SAVE_SLOTS; i++) {
            saveProfileKeys[i] = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.inventory_saver.save_profile_" + (i + 1), InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN, category));
            restoreProfileKeys[i] = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.inventory_saver.restore_profile_" + (i + 1), InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN, category));
        }

        ClientTickEvents.END_CLIENT_TICK.register(InventorySaverMod::onTick);
    }

    private static void onTick(Minecraft mc) {
        // Run an active restore first (it cancels itself when a screen is open).
        RestoreTask.tick(mc);

        // Always drain the key queues, even if nothing happens.
        while (saveKey.consumeClick()) {
            InventoryActions.save(mc);
        }
        while (restoreKey.consumeClick()) {
            InventoryActions.restore(mc);
        }
        while (configKey.consumeClick()) {
            mc.setScreenAndShow(new ConfigScreen(mc.gui.screen()));
        }
        for (int i = 0; i < ModConfig.MAX_SAVE_SLOTS; i++) {
            while (saveProfileKeys[i].consumeClick()) {
                InventoryActions.save(mc, i + 1);
            }
            while (restoreProfileKeys[i].consumeClick()) {
                InventoryActions.restore(mc, i + 1);
            }
        }
    }
}
