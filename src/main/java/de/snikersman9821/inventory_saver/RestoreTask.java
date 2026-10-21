package de.snikersman9821.inventory_saver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plans and executes the restore.
 *
 * Planning: the current menu state (slots 5 to 45) is copied and swaps are simulated on the copy.
 * Every target slot gets the item that should be there. Items that are not part of the save are
 * moved out of the way by the swaps and never block a target slot.
 *
 * Execution: the swaps are queued and run over several ticks (clicks per tick from the settings,
 * 0 = all in one tick). A swap is never split across ticks, so the cursor is empty between ticks.
 *
 * Merging: a plain pick-up/put-down swap of two stacks of the same item merges them. With
 * "keep stacks separate" such swaps are done through a hotbar slot with SWAP clicks instead.
 */
public final class RestoreTask {
    public record Click(int slot, int button, ContainerInput input) {
    }

    private static RestoreTask active;

    private final ArrayDeque<List<Click>> queue;
    private final int missing;
    private final int unplaceable;
    private final int wrongAmount;
    private final int totalSwaps;
    private final String label;

    private RestoreTask(ArrayDeque<List<Click>> queue, int missing, int unplaceable, int wrongAmount, String label) {
        this.queue = queue;
        this.missing = missing;
        this.unplaceable = unplaceable;
        this.wrongAmount = wrongAmount;
        this.totalSwaps = queue.size();
        this.label = label;
    }

    public static boolean isRunning() {
        return active != null;
    }

    /** Two stacks count as the same kind if item, custom name and enchantments match. */
    private static boolean sameKind(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty() || a.getItem() != b.getItem()) {
            return false;
        }
        return Objects.equals(a.get(DataComponents.CUSTOM_NAME), b.get(DataComponents.CUSTOM_NAME))
                && Objects.equals(a.get(DataComponents.ENCHANTMENTS), b.get(DataComponents.ENCHANTMENTS))
                && Objects.equals(a.get(DataComponents.STORED_ENCHANTMENTS), b.get(DataComponents.STORED_ENCHANTMENTS));
    }

    /**
     * Finds the source slot for a target. The home slot is checked first.
     * With rememberCount, a stack with the same size is preferred, otherwise the first match wins.
     */
    private static int findSource(ItemStack[] w, boolean[] used, ItemStack target, int home, boolean rememberCount) {
        int fallback = -1;
        for (int n = -1; n <= InventoryActions.LAST_SLOT; n++) {
            int j;
            if (n < 0) {
                j = home;
            } else {
                if (n < InventoryActions.FIRST_SLOT || n == home) {
                    continue;
                }
                j = n;
            }
            if (used[j] || w[j].isEmpty() || !sameKind(w[j], target)) {
                continue;
            }
            if (!rememberCount) {
                return j;
            }
            if (w[j].getCount() == target.getCount()) {
                return j;
            }
            if (fallback < 0) {
                fallback = j;
            }
        }
        return fallback;
    }

    private static boolean isHotbar(int s) {
        return s >= 36 && s <= 44;
    }

    private static boolean isMain(int s) {
        return s >= 9 && s <= 35;
    }

    /** Builds the clicks for exchanging the contents of two slots. */
    private static List<Click> swapClicks(int a, int b, ItemStack sa, ItemStack sb, boolean keepSeparate) {
        // One SWAP click when a hotbar slot is involved (button = hotbar index). Never merges.
        if (isHotbar(b) && (isHotbar(a) || isMain(a))) {
            return List.of(new Click(a, b - 36, ContainerInput.SWAP));
        }
        if (isHotbar(a) && isMain(b)) {
            return List.of(new Click(b, a - 36, ContainerInput.SWAP));
        }
        // One SWAP click with button 40 = offhand. Never merges.
        if (a == 45 && (isMain(b) || isHotbar(b))) {
            return List.of(new Click(b, 40, ContainerInput.SWAP));
        }
        if (b == 45 && (isMain(a) || isHotbar(a))) {
            return List.of(new Click(a, 40, ContainerInput.SWAP));
        }
        // Main <-> main with the same item in both: go through hotbar slot 0 so nothing merges.
        if (keepSeparate && isMain(a) && isMain(b) && !sa.isEmpty() && !sb.isEmpty()
                && ItemStack.isSameItemSameComponents(sa, sb)) {
            return List.of(
                    new Click(a, 0, ContainerInput.SWAP),
                    new Click(b, 0, ContainerInput.SWAP),
                    new Click(a, 0, ContainerInput.SWAP));
        }
        // Generic: pick up, put down, put the rest back. Start with a non-empty slot.
        if (sa.isEmpty()) {
            int t = a;
            a = b;
            b = t;
            ItemStack ts = sa;
            sa = sb;
            sb = ts;
        }
        List<Click> clicks = new ArrayList<>();
        clicks.add(new Click(a, 0, ContainerInput.PICKUP));
        clicks.add(new Click(b, 0, ContainerInput.PICKUP));
        if (!sb.isEmpty()) {
            clicks.add(new Click(a, 0, ContainerInput.PICKUP));
        }
        return clicks;
    }

    public static void start(Minecraft mc, Map<Integer, ItemStack> targets, int extraMissing, String label) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            return;
        }
        if (active != null) {
            Chat.say("A restore is already running.");
            return;
        }
        InventoryMenu menu = player.inventoryMenu;
        if (menu.slots.size() != InventoryActions.MENU_SIZE) {
            Chat.say("Unexpected inventory layout (" + menu.slots.size() + " slots), aborting.");
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            Chat.say("Your cursor is holding an item. Put it down first.");
            return;
        }

        ModConfig cfg = ModConfig.get();
        boolean rememberCount = cfg.rememberStackSizes;
        boolean keepSeparate = cfg.keepStacksSeparate;

        int first = InventoryActions.FIRST_SLOT;
        int last = InventoryActions.LAST_SLOT;

        ItemStack[] w = new ItemStack[InventoryActions.MENU_SIZE];
        for (int i = first; i <= last; i++) {
            w[i] = menu.slots.get(i).getItem().copy();
        }

        ItemStack[] want = new ItemStack[InventoryActions.MENU_SIZE];
        boolean[] used = new boolean[InventoryActions.MENU_SIZE];
        List<Integer> pending = new ArrayList<>();

        // Pass 0: split oversized stacks into empty target slots (needs rememberStackSizes).
        ArrayDeque<List<Click>> queue = new ArrayDeque<>();
        if (rememberCount) {
            for (int i = 9; i <= 44; i++) {
                ItemStack t = targets.get(i);
                if (t == null || t.isEmpty()) {
                    continue;
                }
                if (!w[i].isEmpty()) {
                    if (sameKind(w[i], t)) {
                        continue;
                    }
                    boolean donor = false;
                    for (int d = 9; d <= 44; d++) {
                        if (d != i && sameKind(w[d], t) && w[d].getCount() > t.getCount()) {
                            donor = true;
                            break;
                        }
                    }
                    int free = -1;
                    for (int e = 9; e <= 44 && donor; e++) {
                        ItemStack et = targets.get(e);
                        if (e != i && w[e].isEmpty() && (et == null || et.isEmpty())
                                && menu.slots.get(e).mayPlace(w[i])) {
                            free = e;
                            break;
                        }
                    }
                    if (free < 0 || !menu.slots.get(i).mayPlace(ItemStack.EMPTY)) {
                        continue;
                    }
                    queue.add(List.of(new Click(i, 0, ContainerInput.PICKUP),
                            new Click(free, 0, ContainerInput.PICKUP)));
                    w[free] = w[i];
                    w[i] = ItemStack.EMPTY;
                }
                for (int j = 9; j <= 44; j++) {
                    if (j == i || w[j].isEmpty() || !sameKind(w[j], t)) {
                        continue;
                    }
                    ItemStack own = targets.get(j);
                    int keep = (own != null && sameKind(w[j], own)) ? own.getCount() : 0;
                    int take = t.getCount();
                    int remaining = w[j].getCount() - take;
                    if (remaining <= 0 || remaining < keep) {
                        continue;
                    }
                    // Another stack of the right size exists elsewhere: a normal swap handles it.
                    boolean alt = false;
                    for (int k = 9; k <= 44; k++) {
                        if (k == i || k == j || w[k].isEmpty() || !sameKind(w[k], t)
                                || w[k].getCount() != t.getCount()) {
                            continue;
                        }
                        ItemStack kt = targets.get(k);
                        boolean inPlace = kt != null && sameKind(w[k], kt) && w[k].getCount() == kt.getCount();
                        if (!inPlace) {
                            alt = true;
                        }
                    }
                    if (alt || !menu.slots.get(i).mayPlace(w[j])) {
                        continue;
                    }
                    List<Click> clicks = new ArrayList<>();
                    clicks.add(new Click(j, 0, ContainerInput.PICKUP));
                    if (take <= remaining) {
                        for (int n = 0; n < take; n++) {
                            clicks.add(new Click(i, 1, ContainerInput.PICKUP));
                        }
                        clicks.add(new Click(j, 0, ContainerInput.PICKUP));
                    } else {
                        for (int n = 0; n < remaining; n++) {
                            clicks.add(new Click(j, 1, ContainerInput.PICKUP));
                        }
                        clicks.add(new Click(i, 0, ContainerInput.PICKUP));
                    }
                    queue.add(clicks);
                    ItemStack part = w[j].copy();
                    part.setCount(take);
                    w[j].shrink(take);
                    w[i] = part;
                    break;
                }
            }
        }

        // Pass 1: items that already sit in the right slot stay (with the right count if remembered).
        for (int i = first; i <= last; i++) {
            ItemStack t = targets.get(i);
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (sameKind(w[i], t) && (!rememberCount || w[i].getCount() == t.getCount())) {
                want[i] = w[i];
                used[i] = true;
            } else {
                pending.add(i);
            }
        }

        // Pass 2: find a source for every remaining target slot.
        int missing = extraMissing;
        for (int i : pending) {
            int src = findSource(w, used, targets.get(i), i, rememberCount);
            if (src < 0) {
                missing++;
            } else {
                want[i] = w[src];
                used[src] = true;
            }
        }

        // Pass 3: simulate swaps in slot order and queue the clicks.
        int unplaceable = 0;
        for (int i = first; i <= last; i++) {
            if (want[i] == null) {
                continue;
            }
            int k = -1;
            for (int m = first; m <= last; m++) {
                if (w[m] == want[i]) {
                    k = m;
                    break;
                }
            }
            if (k < 0 || k == i) {
                continue;
            }
            Slot slotI = menu.slots.get(i);
            Slot slotK = menu.slots.get(k);
            boolean okI = slotI.mayPlace(w[k]);
            boolean okK = w[i].isEmpty() || slotK.mayPlace(w[i]);
            if (!okI || !okK) {
                unplaceable++;
                continue;
            }
            List<Click> clicks = swapClicks(i, k, w[i], w[k], keepSeparate);
            if (!clicks.isEmpty()) {
                queue.add(clicks);
            }
            ItemStack tmp = w[i];
            w[i] = w[k];
            w[k] = tmp;
        }

        // Pass 4: top up stacks that hold too few items, using spare stacks of the same kind.
        int wrongAmount = 0;
        if (rememberCount) {
            java.util.Set<ItemStack> wanted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int i = first; i <= last; i++) {
                if (want[i] != null) {
                    wanted.add(want[i]);
                }
            }
            for (int i = first; i <= last; i++) {
                ItemStack t = targets.get(i);
                if (t == null || t.isEmpty() || w[i].isEmpty() || !sameKind(w[i], t)) {
                    continue;
                }
                for (int j = 9; !keepSeparate && j <= last && w[i].getCount() < t.getCount(); j++) {
                    if (j == i || w[j].isEmpty() || wanted.contains(w[j]) || !sameKind(w[j], t)) {
                        continue;
                    }
                    int room = w[i].getMaxStackSize() - w[i].getCount();
                    int take = Math.min(Math.min(t.getCount() - w[i].getCount(), room), w[j].getCount());
                    if (take <= 0) {
                        continue;
                    }
                    boolean wholeDonor = take == w[j].getCount();
                    List<Click> clicks = new ArrayList<>();
                    clicks.add(new Click(j, 0, ContainerInput.PICKUP));
                    if (wholeDonor || take == room) {
                        // Exact merge: the whole donor fits, or the target is filled up to its maximum.
                        clicks.add(new Click(i, 0, ContainerInput.PICKUP));
                        if (!wholeDonor) {
                            clicks.add(new Click(j, 0, ContainerInput.PICKUP));
                        }
                    } else {
                        // Right click places one item at a time, then the rest goes back.
                        for (int n = 0; n < take; n++) {
                            clicks.add(new Click(i, 1, ContainerInput.PICKUP));
                        }
                        clicks.add(new Click(j, 0, ContainerInput.PICKUP));
                    }
                    queue.add(clicks);
                    w[i].grow(take);
                    w[j].shrink(take);
                    if (w[j].isEmpty()) {
                        w[j] = ItemStack.EMPTY;
                    }
                }
                if (w[i].getCount() != t.getCount()) {
                    wrongAmount++;
                }
            }
        }

        RestoreTask task = new RestoreTask(queue, missing, unplaceable, wrongAmount, label);
        if (queue.isEmpty()) {
            task.finish(mc);
            return;
        }
        active = task;
        Chat.say("Restoring" + label + " (" + task.totalSwaps + " swaps)...");
    }

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        RestoreTask task = active;
        if (task == null) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            active = null;
            return;
        }
        // Any open screen (escape menu, inventory, chat, ...) or another open container cancels.
        if (mc.gui.screen() != null || player.containerMenu != player.inventoryMenu) {
            active = null;
            Chat.say("Restore cancelled (" + task.queue.size() + " swaps left).");
            return;
        }

        int perTick = ModConfig.get().clicksPerTick;
        int budget = perTick <= 0 ? Integer.MAX_VALUE : perTick;
        while (budget > 0 && !task.queue.isEmpty()) {
            List<Click> swap = task.queue.pollFirst();
            for (Click c : swap) {
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, c.slot(), c.button(), c.input(), player);
            }
            budget -= swap.size();
        }

        if (task.queue.isEmpty()) {
            active = null;
            task.finish(mc);
        }
    }

    private void finish(Minecraft mc) {
        StringBuilder sb = new StringBuilder("Restore" + label + " finished.");
        if (missing > 0) {
            sb.append(' ').append(missing).append(missing == 1 ? " item" : " items").append(" not found.");
        }
        if (unplaceable > 0) {
            sb.append(' ').append(unplaceable).append(unplaceable == 1 ? " item" : " items")
                    .append(" could not be placed.");
        }
        if (wrongAmount > 0) {
            sb.append(' ').append(wrongAmount).append(wrongAmount == 1 ? " stack has" : " stacks have")
                    .append(" a different amount than saved.");
        }
        LocalPlayer player = mc.player;
        if (player != null && !player.inventoryMenu.getCarried().isEmpty()) {
            sb.append(" Warning: the cursor is not empty.");
        }
        Chat.say(sb.toString());
    }
}
