package app.afkbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The open container (chest, barrel, shulker...) and the player's inventory, as last sent by the server. */
final class Inv {
    private Inv() {
    }

    static final class Item {
        final int id, count;
        final String name;
        final String detail; // e.g. "Shulker contents: 12 items" or enchantments count
        // for mods: the internal id ("carrot"), how many fit in a slot, wear, enchantment levels by name, and what's
        // inside (shulker boxes; null when it holds nothing / isn't a container)
        String key = "";
        int maxCount = 64, damage, maxDamage;
        java.util.Map<String, Integer> enchants;
        List<Item> contents;

        Item(int id, int count, String name, String detail) {
            this.id = id;
            this.count = count;
            this.name = name;
            this.detail = detail;
        }
    }

    static volatile int openId = -1; // -1 = no container open
    static volatile String title = "";
    static volatile int stateId;
    static volatile List<Item> slots = new ArrayList<Item>(); // container slots + player inventory (36)
    static volatile List<Item> inventory = new ArrayList<Item>(); // window 0
    static volatile int version;
    /** Selected hotbar slot 0-8. */
    static volatile int held;

    /** The item in your hand (hotbar slot), or null. */
    static Item heldItem() {
        List<Item> inv = inventory;
        int i = 36 + held;
        return i < inv.size() ? inv.get(i) : null;
    }
    static volatile String status = "";

    static synchronized void reset() {
        openId = -1;
        title = "";
        slots = new ArrayList<Item>();
        inventory = new ArrayList<Item>();
        status = "";
        version++;
    }

    /** Number of slots that belong to the container (the rest are your inventory). */
    static int containerSize() {
        return Math.max(0, slots.size() - 36);
    }

    /** Converts a parsed Slot (from ProtoDef) into an Item, or null for an empty slot. */
    @SuppressWarnings("unchecked")
    static Item fromSlot(Object o, ProtoDef.VersionData vd) {
        if (!(o instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        // 1.20.2-1.20.4: {present, itemId, itemCount, nbtData}; 1.20.5+: {itemCount, itemId, components...}
        Object present = m.get("present");
        if (Boolean.FALSE.equals(present)) return null;
        int count = num(m.get("itemCount"));
        if (count <= 0) return null;
        int id = num(m.get("itemId"));
        String name = vd.itemName(id);
        String detail = "";
        Object comps = m.get("components");
        int damage = 0, maxDamage = vd.maxDurability(id), maxCount = vd.maxStack(id);
        java.util.Map<String, Integer> enchants = null;
        List<Item> contents = null;
        if (comps instanceof List) {
            int ench = 0;
            for (Object c : (List<Object>) comps) {
                if (!(c instanceof Map)) continue;
                Map<String, Object> cm = (Map<String, Object>) c;
                Object type = cm.get("type");
                Object data = cm.get("data");
                if ("custom_name".equals(type) || "item_name".equals(type) && name.startsWith("Item #")) {
                    String n = Text.strip(Text.flatten(data)).trim();
                    if (!n.isEmpty()) name = n;
                } else if ("container".equals(type)) {
                    int inside = 0;
                    contents = new ArrayList<Item>();
                    if (data instanceof List) {
                        for (Object x : (List<Object>) data) {
                            Item it = fromSlot(x instanceof Map && ((Map<String, Object>) x).containsKey("item") ? ((Map<String, Object>) x).get("item") : x, vd);
                            if (it != null) {
                                inside += it.count;
                                contents.add(it);
                            }
                        }
                    } else if (data instanceof Map) {
                        Object list = ((Map<String, Object>) data).values().iterator().hasNext() ? ((Map<String, Object>) data).values().iterator().next() : null;
                        if (list instanceof List) for (Object x : (List<Object>) list) {
                            Item it = fromSlot(x, vd);
                            if (it != null) {
                                inside += it.count;
                                contents.add(it);
                            }
                        }
                    }
                    detail = inside > 0 ? "contains " + inside + " items" : "empty";
                } else if ("enchantments".equals(type) && data instanceof Map) {
                    Object e = ((Map<String, Object>) data).get("enchantments");
                    if (e instanceof List) {
                        ench = ((List<Object>) e).size();
                        enchants = new java.util.HashMap<String, Integer>();
                        String[] names = enchantNames;
                        for (Object x : (List<Object>) e) {
                            if (!(x instanceof Map)) continue;
                            int eid = num(((Map<String, Object>) x).get("id")), lvl = num(((Map<String, Object>) x).get("level"));
                            enchants.put(names != null && eid >= 0 && eid < names.length ? names[eid] : "#" + eid, lvl);
                        }
                    }
                } else if ("damage".equals(type)) {
                    int dmg = num(data);
                    damage = dmg;
                    if (dmg > 0) detail = detail.isEmpty() ? "damage " + dmg : detail;
                } else if ("max_damage".equals(type)) {
                    maxDamage = num(data);
                } else if ("max_stack_size".equals(type)) {
                    maxCount = num(data);
                }
            }
            if (ench > 0) detail = (detail.isEmpty() ? "" : detail + ", ") + ench + " enchantment" + (ench > 1 ? "s" : "");
        } else {
            Object nbt = m.get("nbtData");
            if (nbt instanceof Map) {
                Object disp = ((Map<String, Object>) nbt).get("display");
                if (disp instanceof Map) {
                    Object n = ((Map<String, Object>) disp).get("Name");
                    if (n instanceof String) {
                        String t = Text.strip(Text.fromJson((String) n)).trim();
                        if (!t.isEmpty()) name = t;
                    }
                }
            }
        }
        Item it = new Item(id, count, name, detail);
        it.key = vd.itemId(id);
        it.maxCount = maxCount;
        it.damage = damage;
        it.maxDamage = maxDamage;
        it.enchants = enchants;
        it.contents = contents;
        return it;
    }

    /** A copy with another count (for predicting what a click does before the server says). */
    static Item withCount(Item o, int count) {
        if (count <= 0) return null;
        Item it = new Item(o.id, count, o.name, o.detail);
        it.key = o.key;
        it.maxCount = o.maxCount;
        it.damage = o.damage;
        it.maxDamage = o.maxDamage;
        it.enchants = o.enchants;
        it.contents = o.contents;
        return it;
    }

    /** Enchantment names in the server's registry order (enchantment ids in items are positions in it). */
    static volatile String[] enchantNames;
    /** The open container's menu type (the game's menu registry: 2 generic 9x3, 5 generic 9x6, 20 shulker box...), -1 if unknown. */
    static volatile int type = -1;
    /** The open container was opened by a mod (so it's not you using it). */
    static volatile boolean openedByMod;
    /** When a mod last right-clicked a container. */
    static volatile long modOpenAt;

    /** Rejects nonsense that means the item data was misread. */
    static boolean plausible(Item it, ProtoDef.VersionData vd) {
        return it.count > 0 && it.count <= 127 && it.id > 0 && it.id < vd.itemNames.length;
    }

    static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }
}
