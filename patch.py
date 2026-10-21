p = "src/main/java/de/snikersman9821/inventory_saver/RestoreTask.java"
s = open(p).read()
old = """                if (t == null || t.isEmpty() || !w[i].isEmpty()) {
                    continue;
                }
"""
new = """                if (t == null || t.isEmpty()) {
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
"""
assert s.count(old) == 1, "Stelle nicht gefunden"
open(p, "w").write(s.replace(old, new, 1))
print("gepatcht")
