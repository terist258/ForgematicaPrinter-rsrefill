package me.aleksilassila.litematica.printer.rs;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

public class RSRefiller {

    private static final Logger LOGGER = LogManager.getLogger("RSRefiller");

    private static final int EXTRACT_SHIFT = 4;

    private static final Object lock = new Object();
    private static volatile boolean busy = false;
    private static volatile boolean dumpedThisCycle = false;
    private static volatile boolean noMatch = false;
    private static volatile ItemStack neededItem = ItemStack.EMPTY;

    private static final Map<Item, Long> recentlyPulled = new HashMap<>();
    private static final long KEEP_RECENT_MS = 30000;

    /** How many free slots to keep before pulling. */
    private static final int TARGET_FREE_SLOTS = 2;

    /** Items absent from network -> cooldown deadline, avoids repeated screen open/close. */
    private static final Map<Item, Long> failedUntil = new HashMap<>();
    private static final long ABSENT_COOLDOWN_MS = 5000;
    private static final long NOT_ARRIVED_COOLDOWN_MS = 2000;
    private static long absentMaxCooldownMs() {
        int v = Configs.RS_ABSENT_COOLDOWN.getIntegerValue();
        if (v < 5) v = 5;
        return v * 1000L;
    }
    private static final Map<Item, Integer> absentCount = new HashMap<>();

    public static boolean isCoolingDown(Item item) {
        if (item == null) return false;
        Long until = failedUntil.get(item);
        return until != null && System.currentTimeMillis() < until;
    }

    private static void markAbsent(Item item) {
        if (item == null) return;
        int n = absentCount.getOrDefault(item, 0) + 1;
        absentCount.put(item, n);
        long cd = Math.min(ABSENT_COOLDOWN_MS * (1L << Math.min(n - 1, 4)), absentMaxCooldownMs());
        failedUntil.put(item, System.currentTimeMillis() + cd);
    }

    public static void markNotArrived() {
        ItemStack n = neededItem;
        if (n != null && !n.isEmpty()) {
            failedUntil.put(n.getItem(), System.currentTimeMillis() + NOT_ARRIVED_COOLDOWN_MS);
        }
    }

    public static boolean isBusy() { return busy; }
    public static boolean isNoMatch() { return noMatch; }

    public static boolean tryRefill(ItemStack needed) {
        if (!Configs.RS_REFILL.getBooleanValue()) return false;
        if (needed == null || needed.isEmpty()) return false;
        if (isCoolingDown(needed.getItem())) { return false; }
        synchronized (lock) {
            if (busy) { LOGGER.info("[RSRefiller] FAIL: busy, skip"); return false; }
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;

        if (hasItemInInventory(client.player, needed)) {
            LOGGER.info("[RSRefiller] already have {} in inventory, skip refill",
                    needed.getItem().getTranslationKey());
            return false;
        }

        FoundWireless fw = findWirelessItem(client.player);
        if (fw == null) {
            LOGGER.info("[RSRefiller] FAIL: no wireless item anywhere (hands/inventory/Curios).");
            return false;
        }
        LOGGER.info("[RSRefiller] OK: found wireless location={} slot={} curio={} item={}",
                fw.location, fw.slot, fw.curioSlot, fw.stack.getItem().getTranslationKey());

        return openAndExtract(client, fw, needed);
    }

    private static boolean openAndExtract(MinecraftClient client, FoundWireless fw, ItemStack needed) {
        synchronized (lock) { busy = true; }
        neededItem = needed.copy();
        dumpedThisCycle = false;
        noMatch = false;
        ScreenInterceptor.arm();

        if ("mainhand".equals(fw.location) || "offhand".equals(fw.location)) {
            Hand hand = "mainhand".equals(fw.location) ? Hand.MAIN_HAND : Hand.OFF_HAND;
            client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(hand, 0));
            LOGGER.info("[RSRefiller] sent use packet for {}-held wireless", fw.location);
            return true;
        }

        if (sendOpenNetworkItem(fw)) return true;

        LOGGER.info("[RSRefiller] FAIL: cannot open wireless from {}", fw.location);
        synchronized (lock) { busy = false; }
        ScreenInterceptor.disarm();
        return false;
    }

    private static boolean sendOpenNetworkItem(FoundWireless fw) {
        try {
            Class<?> slotCls = Class.forName("com.refinedmods.refinedstorage.inventory.player.PlayerSlot");
            Object playerSlot;
            if (fw.curioSlot != null) {
                Constructor<?> c = findCtor(slotCls, int.class, String.class);
                if (c == null) {
                    LOGGER.info("[RSRefiller] PlayerSlot(int,String) NOT found. available: {}", dumpCtors(slotCls));
                    return false;
                }
                playerSlot = c.newInstance(fw.slot, fw.curioSlot);
            } else {
                Constructor<?> c = findCtor(slotCls, int.class);
                if (c == null) {
                    LOGGER.info("[RSRefiller] PlayerSlot(int) NOT found. available: {}", dumpCtors(slotCls));
                    return false;
                }
                playerSlot = c.newInstance(fw.slot);
            }

            Class<?> msgCls = Class.forName("com.refinedmods.refinedstorage.network.OpenNetworkItemMessage");
            Constructor<?> ctor = null;
            for (Constructor<?> c : msgCls.getConstructors()) {
                if (c.getParameterCount() == 1 && c.getParameterTypes()[0].isAssignableFrom(slotCls)) { ctor = c; break; }
            }
            if (ctor == null) {
                LOGGER.info("[RSRefiller] OpenNetworkItemMessage(PlayerSlot) NOT found. available: {}", dumpCtors(msgCls));
                return false;
            }
            Object msg = ctor.newInstance(playerSlot);

            Class<?> rsCls = Class.forName("com.refinedmods.refinedstorage.RS");
            Object handler = rsCls.getField("NETWORK_HANDLER").get(null);
            Method send = null;
            for (Method m : handler.getClass().getMethods()) {
                if (m.getName().equals("sendToServer") && m.getParameterCount() == 1) { send = m; break; }
            }
            if (send == null) { LOGGER.info("[RSRefiller] sendToServer(1 param) not found"); return false; }
            send.setAccessible(true);
            send.invoke(handler, msg);
            LOGGER.info("[RSRefiller] sent OpenNetworkItemMessage location={} slot={} curio={}",
                    fw.location, fw.slot, fw.curioSlot);
            return true;
        } catch (Throwable t) {
            LOGGER.info("[RSRefiller] sendOpenNetworkItem failed: {}", String.valueOf(t));
            return false;
        }
    }

    private static Constructor<?> findCtor(Class<?> cls, Class<?>... types) {
        try { return cls.getConstructor(types); } catch (Throwable ignored) {}
        outer:
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length != types.length) continue;
            for (int i = 0; i < ps.length; i++) {
                if (ps[i] != types[i] && !ps[i].isAssignableFrom(types[i])) continue outer;
            }
            return c;
        }
        return null;
    }

    private static String dumpCtors(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            sb.append("[");
            for (Class<?> p : c.getParameterTypes()) sb.append(p.getSimpleName()).append(" ");
            sb.append("] ");
        }
        return sb.toString();
    }

    public static boolean tryPull(MinecraftClient client) {
        Screen current = client.currentScreen;
        if (!ScreenInterceptor.isRSGridScreen(current)) return false;
        ItemStack needed = neededItem;
        if (needed == null || needed.isEmpty()) return false;

        if (countEmptySlots(client.player) < TARGET_FREE_SLOTS) {
            if (!dumpedThisCycle) {
                dumpedThisCycle = true;
                LOGGER.info("[RSRefiller] need more free slots, dumping one per tick");
            }
            if (dumpOneBlock(client, needed)) {
                LOGGER.info("[RSRefiller] dumped one block to free slot, retry next tick");
                return false;
            }
            LOGGER.info("[RSRefiller] nothing dumpable, pulling anyway");
        }

        try {
            Object view = invoke(current, "getView");
            if (view == null) { LOGGER.info("[RSRefiller] pull: getView() null, retry later"); return false; }
            Object stacksObj = invoke(view, "getStacks");
            if (!(stacksObj instanceof List)) return false;
            List<?> stacks = (List<?>) stacksObj;
            if (stacks.isEmpty()) { LOGGER.info("[RSRefiller] pull: view empty, retry later"); return false; }

            LOGGER.info("[RSRefiller] pull: screen={}, stacks={}",
                    current.getClass().getSimpleName(), stacks.size());

            Item wanted = needed.getItem();
            boolean sent = false;
            for (Object gs : stacks) {
                Object idObj = invoke(gs, "getId");
                if (!(idObj instanceof UUID)) continue;
                UUID id = (UUID) idObj;
                Object ing = invoke(gs, "getIngredient");
                ItemStack is = toItemStack(ing);
                if (is == null || is.isEmpty()) continue;
                if (is.getItem() != wanted) continue;
                if (sendPullMessage(id)) {
                    LOGGER.info("[RSRefiller] pull: SENT GridItemPullMessage uuid={} item={} (EXTRACT_SHIFT)",
                            id, wanted.getTranslationKey());
                    markPulled(wanted);
                    sent = true;
                    break;
                }
            }
            if (!sent) {
                noMatch = true;
                markAbsent(wanted);
                LOGGER.info("[RSRefiller] pull: no match in grid view for {}", wanted.getTranslationKey());
                return clickSlotFallback(client, needed);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.info("[RSRefiller] pull exception: {}", String.valueOf(t));
            return false;
        }
    }

    private static boolean sendPullMessage(UUID id) {
        try {
            Class<?> msgCls = Class.forName("com.refinedmods.refinedstorage.network.grid.GridItemPullMessage");
            Constructor<?> ctor = msgCls.getConstructor(UUID.class, int.class);
            Object msg = ctor.newInstance(id, EXTRACT_SHIFT);
            Class<?> rsCls = Class.forName("com.refinedmods.refinedstorage.RS");
            Object handler = rsCls.getField("NETWORK_HANDLER").get(null);
            Method send = null;
            for (Method m : handler.getClass().getMethods()) {
                if (m.getName().equals("sendToServer") && m.getParameterCount() == 1) { send = m; break; }
            }
            if (send == null) return false;
            send.setAccessible(true);
            send.invoke(handler, msg);
            LOGGER.info("[RSRefiller] sendPullMessage: OK invoked {}", send.getName());
            return true;
        } catch (Throwable t) {
            LOGGER.info("[RSRefiller] sendPullMessage failed: {}", String.valueOf(t));
            return false;
        }
    }

    private static ItemStack toItemStack(Object ing) {
        if (ing == null) return ItemStack.EMPTY;
        if (ing instanceof ItemStack) return (ItemStack) ing;
        try {
            Method m = ing.getClass().getMethod("getItems");
            Object arr = m.invoke(ing);
            if (arr instanceof ItemStack[]) {
                ItemStack[] a = (ItemStack[]) arr;
                if (a.length > 0) return a[0];
            }
        } catch (Throwable ignored) {}
        return ItemStack.EMPTY;
    }

    private static boolean clickSlotFallback(MinecraftClient client, ItemStack needed) {
        try {
            var handler = client.player.currentScreenHandler;
            if (handler == null) return false;
            Item wanted = needed.getItem();
            for (int i = 0; i < handler.slots.size(); i++) {
                var slot = handler.slots.get(i);
                if (slot == null || !slot.hasStack()) continue;
                if (slot.getStack().getItem() == wanted) {
                    client.interactionManager.clickSlot(handler.syncId, i, 0,
                            SlotActionType.QUICK_MOVE, client.player);
                    LOGGER.info("[RSRefiller] fallback: clickSlot QUICK_MOVE at slot {}", i);
                    return true;
                }
            }
        } catch (Throwable t) {
            LOGGER.info("[RSRefiller] clickSlotFallback failed: {}", String.valueOf(t));
        }
        return false;
    }

    public static void closeContainer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        try {
            client.player.closeHandledScreen();
            LOGGER.info("[RSRefiller] closing container (closeHandledScreen)");
        } catch (Throwable t) {
            LOGGER.info("[RSRefiller] closeHandledScreen failed: {}", String.valueOf(t));
        }
        ScreenInterceptor.disarm();
    }

    public static boolean hasArrived(ClientPlayerEntity player) {
        ItemStack needed = neededItem;
        if (needed == null || needed.isEmpty()) return false;
        return hasItemInInventory(player, needed);
    }

    public static void resetBusy() {
        synchronized (lock) { busy = false; }
        neededItem = ItemStack.EMPTY;
        noMatch = false;
        dumpedThisCycle = false;
        ScreenInterceptor.disarm();
    }

    private static boolean hasItemInInventory(ClientPlayerEntity player, ItemStack needed) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == needed.getItem()) return true;
        }
        return false;
    }

    private static boolean hasEmptySlot(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isEmpty()) return true;
        return false;
    }

    private static int countEmptySlots(ClientPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isEmpty()) n++;
        return n;
    }

    private static boolean isPlaceableBlock(ItemStack s) {
        try {
            return net.minecraft.block.Block.getBlockFromItem(s.getItem()) != net.minecraft.block.Blocks.AIR;
        } catch (Throwable t) { return false; }
    }

    private static void markPulled(Item item) {
        if (item != null) {
            recentlyPulled.put(item, System.currentTimeMillis());
            absentCount.remove(item);
            failedUntil.remove(item);
        }
    }

    private static boolean isRecentlyPulled(Item item) {
        Long t = recentlyPulled.get(item);
        return t != null && (System.currentTimeMillis() - t) < KEEP_RECENT_MS;
    }

    private static boolean inMainInventory(PlayerInventory inv, ItemStack s) {
        for (int k = 9; k < 36; k++) if (inv.getStack(k) == s) return true;
        return false;
    }

    private static boolean clickQuickMove(MinecraftClient client, net.minecraft.screen.ScreenHandler handler,
                                          int menuSlot, ItemStack s) {
        try {
            client.interactionManager.clickSlot(handler.syncId, menuSlot, 0,
                    SlotActionType.QUICK_MOVE, client.player);
            LOGGER.info("[RSRefiller] dumped {} x{} (menu slot {}) into network",
                    s.getItem().getTranslationKey(), s.getCount(), menuSlot);
            return true;
        } catch (Throwable t) {
            LOGGER.info("[RSRefiller] dump failed: {}", String.valueOf(t));
        }
        return false;
    }

    /* Clear one block to free a slot. Prefers blocks NOT recently pulled,
       to avoid "dump then immediately re-pull" the same item. */
    private static boolean dumpOneBlock(MinecraftClient client, ItemStack needed) {
        net.minecraft.screen.ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null) return false;
        Item wanted = needed.getItem();
        PlayerInventory inv = client.player.getInventory();

        for (int i = 0; i < handler.slots.size(); i++) {
            var slot = handler.slots.get(i);
            if (slot == null || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            if (s.isEmpty()) continue;
            if (s.getItem() == wanted) continue;
            if (!isPlaceableBlock(s)) continue;
            if (!inMainInventory(inv, s)) continue;
            if (isRecentlyPulled(s.getItem())) continue;
            if (clickQuickMove(client, handler, i, s)) return true;
        }

        int oldestSlot = -1;
        long oldestTime = Long.MAX_VALUE;
        for (int i = 0; i < handler.slots.size(); i++) {
            var slot = handler.slots.get(i);
            if (slot == null || !slot.hasStack()) continue;
            ItemStack s = slot.getStack();
            if (s.isEmpty()) continue;
            if (s.getItem() == wanted) continue;
            if (!isPlaceableBlock(s)) continue;
            if (!inMainInventory(inv, s)) continue;
            Long t = recentlyPulled.get(s.getItem());
            long time = (t == null) ? 0L : t;
            if (time < oldestTime) { oldestTime = time; oldestSlot = i; }
        }
        if (oldestSlot >= 0) {
            var slot = handler.slots.get(oldestSlot);
            if (clickQuickMove(client, handler, oldestSlot, slot.getStack())) return true;
        }
        return false;
    }

    private static Object invoke(Object target, String name) { return call(target, name); }

    private static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        try {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != args.length) continue;
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object callStatic(Class<?> cls, String name, Object... args) {
        try {
            for (Method m : cls.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != args.length) continue;
                m.setAccessible(true);
                return m.invoke(null, args);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object unwrap(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof java.util.Optional) return ((java.util.Optional<?>) o).orElse(null);
            if (o.getClass().getName().contains("LazyOptional")) {
                Object v = call(o, "getValue");
                if (v != null) return v;
                Object r = call(o, "resolve");
                if (r instanceof java.util.Optional) return ((java.util.Optional<?>) r).orElse(null);
                return r;
            }
        } catch (Throwable ignored) {}
        return o;
    }

    private static int slotCountOf(Object dyn) {
        Object sz = call(dyn, "getSlots");
        if (sz instanceof Number) return ((Number) sz).intValue();
        Object inner = call(dyn, "getStacks");
        Object sz2 = call(inner, "getSlots");
        if (sz2 instanceof Number) return ((Number) sz2).intValue();
        return 0;
    }

    /* Scan Map<curio type, handler>. Type names and slot counts are read
       dynamically, so changing the modpack's curio layout needs no code change. */
    private static FoundWireless scanCurioMap(Map<?, ?> map) {
        for (Object e : map.entrySet()) {
            Object key = ((Map.Entry<?, ?>) e).getKey();
            Object val = ((Map.Entry<?, ?>) e).getValue();
            if (val == null) continue;

            Object dyn = unwrap(call(val, "getStacks"));
            if (dyn == null) dyn = val;

            int size = slotCountOf(dyn);

            Object stackSrc = dyn;
            if (call(stackSrc, "getStackInSlot", 0) == null) {
                Object inner = call(dyn, "getStacks");
                if (inner != null) stackSrc = inner;
            }

            for (int i = 0; i < size; i++) {
                Object ss = call(stackSrc, "getStackInSlot", i);
                if (!(ss instanceof ItemStack)) continue;
                ItemStack s = (ItemStack) ss;
                if (s.isEmpty()) continue;
                if (isWireless(s)) return new FoundWireless("curios", i, s, String.valueOf(key));
            }
        }
        return null;
    }

    /* Scan order: mainhand -> offhand -> inventory -> curios */
    private static FoundWireless findWirelessItem(ClientPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        ItemStack off = player.getOffHandStack();

        if (isWireless(main)) return new FoundWireless("mainhand", -1, main, null);
        if (isWireless(off)) return new FoundWireless("offhand", -1, off, null);

        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (isWireless(s)) return new FoundWireless("inventory", i, s, null);
        }

        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object helper = callStatic(api, "getCuriosHelper");
            if (helper == null) return null;

            Object target = unwrap(call(helper, "getCuriosHandler", player));
            if (target == null) {
                for (String mn : new String[]{"getCuriosInventory", "getCurioStacks"}) {
                    target = unwrap(call(helper, mn, player));
                    if (target != null) break;
                }
            }
            if (target == null) return null;

            for (String mn : new String[]{"getCurioStacks", "getStacksHandler", "getCurios", "getStacks"}) {
                Object m = call(target, mn);
                if (m instanceof Map) {
                    FoundWireless fw = scanCurioMap((Map<?, ?>) m);
                    if (fw != null) return fw;
                }
            }

            for (Method mm : target.getClass().getMethods()) {
                if (mm.getParameterCount() != 0) continue;
                Object r;
                try {
                    mm.setAccessible(true);
                    r = mm.invoke(target);
                } catch (Throwable t) { continue; }
                if (!(r instanceof Map)) continue;
                FoundWireless fw = scanCurioMap((Map<?, ?>) r);
                if (fw != null) return fw;
            }

            Object eq = unwrap(call(helper, "getEquippedCurios", player));
            if (eq instanceof Map) {
                for (Object e2 : ((Map<?, ?>) eq).entrySet()) {
                    Object key = ((Map.Entry<?, ?>) e2).getKey();
                    Object val = ((Map.Entry<?, ?>) e2).getValue();
                    if (val instanceof ItemStack) {
                        ItemStack s = (ItemStack) val;
                        if (isWireless(s)) return new FoundWireless("curios", 0, s, String.valueOf(key));
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static boolean isWireless(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String name = stack.getItem().getTranslationKey();
        if (name == null) return false;
        String n = name.toLowerCase().replace("_", "");
        return n.contains("refinedstorage") && n.contains("wireless");
    }

    static class FoundWireless {
        final String location;
        final int slot;
        final ItemStack stack;
        final String curioSlot;
        FoundWireless(String location, int slot, ItemStack stack, String curioSlot) {
            this.location = location;
            this.slot = slot;
            this.stack = stack;
            this.curioSlot = curioSlot;
        }
    }
}