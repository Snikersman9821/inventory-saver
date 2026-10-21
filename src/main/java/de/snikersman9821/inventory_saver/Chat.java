package de.snikersman9821.inventory_saver;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Local-only chat messages. Only this class touches the chat API,
 * so a rename in a newer Minecraft version needs a change in one place.
 */
public final class Chat {
    private Chat() {
    }

    public static void say(String message) {
        if (!ModConfig.get().chatFeedback) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Component text = Component.literal("[Inventory Saver] " + message).withStyle(ChatFormatting.AQUA);
        mc.player.sendSystemMessage(text);
    }
}
