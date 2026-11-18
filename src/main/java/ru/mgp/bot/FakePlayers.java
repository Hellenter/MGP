package ru.mgp.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Настоящие игроки без клиента (как «фейковые игроки» Carpet): ServerPlayer сервера с подключением-заглушкой.
 * Для плагина и других плагинов это обычный Player — заходит в очередь, получает наборы, умирает и возрождается.
 *
 * Код сервера достаётся через рефлексию по именам Mojang (Paper 26.x не обфусцирован), чтобы плагин
 * по-прежнему собирался только с paper-api. Если имена поменяются в новой версии — {@link #error()} скажет, что сломалось.
 */
final class FakePlayers {
    private static boolean ready;
    private static String error;

    private static Method craftServerGetServer, getPlayerList, craftWorldGetHandle, craftPlayerGetHandle;
    private static Constructor<?> gameProfileCtor, serverPlayerCtor, connectionCtor, embeddedChannelCtor;
    private static Method clientInfoDefault, cookieInitial, placeNewPlayer, removePlayer, doTick, markClientLoaded;
    private static Method isChangingDimension, hasChangedDimension, hasClientLoaded, releaseOutbound, setJumping, setYRot, setXRot, setYHeadRot, setYBodyRot;
    private static Field awaitingPosition, connChannel, connAddress, spConnection, zza, xxa, horizontalCollision;
    private static Object serverbound;

    private FakePlayers() {}

    static synchronized boolean init() {
        if (ready) return true;
        if (error != null) return false;
        try {
            Object craftServer = Bukkit.getServer();
            craftServerGetServer = craftServer.getClass().getMethod("getServer");
            Object mc = craftServerGetServer.invoke(craftServer);
            getPlayerList = find(mc.getClass(), "getPlayerList");
            Class<?> craftWorld = Class.forName(pkg(craftServer) + ".CraftWorld");
            craftWorldGetHandle = craftWorld.getMethod("getHandle");
            Class<?> craftPlayer = Class.forName(pkg(craftServer) + ".entity.CraftPlayer");
            craftPlayerGetHandle = craftPlayer.getMethod("getHandle");

            ClassLoader cl = mc.getClass().getClassLoader();
            Class<?> profile = Class.forName("com.mojang.authlib.GameProfile", true, cl);
            gameProfileCtor = profile.getConstructor(UUID.class, String.class);
            Class<?> mcServer = Class.forName("net.minecraft.server.MinecraftServer", true, cl);
            Class<?> level = Class.forName("net.minecraft.server.level.ServerLevel", true, cl);
            Class<?> clientInfo = Class.forName("net.minecraft.server.level.ClientInformation", true, cl);
            clientInfoDefault = clientInfo.getMethod("createDefault");
            Class<?> sp = Class.forName("net.minecraft.server.level.ServerPlayer", true, cl);
            serverPlayerCtor = sp.getConstructor(mcServer, level, profile, clientInfo);
            doTick = sp.getMethod("doTick");
            spConnection = sp.getField("connection");

            Class<?> flow = Class.forName("net.minecraft.network.protocol.PacketFlow", true, cl);
            serverbound = flow.getField("SERVERBOUND").get(null);
            Class<?> conn = Class.forName("net.minecraft.network.Connection", true, cl);
            connectionCtor = conn.getConstructor(flow);
            connChannel = conn.getField("channel");
            connAddress = conn.getField("address");
            Class<?> embedded = Class.forName("io.netty.channel.embedded.EmbeddedChannel", true, cl);
            embeddedChannelCtor = embedded.getConstructor(Class.forName("io.netty.channel.ChannelHandler", true, cl).arrayType());
            releaseOutbound = embedded.getMethod("releaseOutbound");

            Class<?> cookie = Class.forName("net.minecraft.server.network.CommonListenerCookie", true, cl);
            cookieInitial = cookie.getMethod("createInitial", profile, boolean.class);
            Class<?> playerList = Class.forName("net.minecraft.server.players.PlayerList", true, cl);
            placeNewPlayer = playerList.getMethod("placeNewPlayer", conn, sp, cookie);
            removePlayer = playerList.getMethod("remove", sp);

            Class<?> listener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl", true, cl);
            markClientLoaded = listener.getDeclaredMethod("markClientLoaded");
            markClientLoaded.setAccessible(true);
            hasClientLoaded = listener.getMethod("hasClientLoaded");
            awaitingPosition = listener.getDeclaredField("awaitingPositionFromClient");
            awaitingPosition.setAccessible(true);
            isChangingDimension = sp.getMethod("isChangingDimension");
            hasChangedDimension = sp.getMethod("hasChangedDimension");

            Class<?> living = Class.forName("net.minecraft.world.entity.LivingEntity", true, cl);
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity", true, cl);
            zza = living.getField("zza");
            xxa = living.getField("xxa");
            setJumping = living.getMethod("setJumping", boolean.class);
            setYHeadRot = living.getMethod("setYHeadRot", float.class);
            setYBodyRot = living.getMethod("setYBodyRot", float.class);
            setYRot = entity.getMethod("setYRot", float.class);
            setXRot = entity.getMethod("setXRot", float.class);
            horizontalCollision = entity.getField("horizontalCollision");
            ready = true;
            return true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Bukkit.getLogger().warning("[MGP] Боты недоступны на этой версии сервера: " + error);
            return false;
        }
    }

    static String error() { return error; }

    private static String pkg(Object craftServer) { return craftServer.getClass().getPackageName(); }

    private static Method find(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == 0) { m.setAccessible(true); return m; }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Заводит бота как вошедшего игрока (PlayerJoinEvent и всё остальное — как у настоящего). */
    static Player spawn(String name, Location at) throws Exception {
        Object mc = craftServerGetServer.invoke(Bukkit.getServer());
        World w = at.getWorld();
        Object level = craftWorldGetHandle.invoke(w);
        Object profile = gameProfileCtor.newInstance(offlineUuid(name), name);
        Object handle = serverPlayerCtor.newInstance(mc, level, profile, clientInfoDefault.invoke(null));
        Object conn = connectionCtor.newInstance(serverbound);
        Object channel = embeddedChannelCtor.newInstance((Object) java.lang.reflect.Array.newInstance(
                Class.forName("io.netty.channel.ChannelHandler", true, mc.getClass().getClassLoader()), 0));
        connChannel.set(conn, channel);
        connAddress.set(conn, new InetSocketAddress("127.0.0.1", 0));
        Object list = getPlayerList.invoke(mc);
        placeNewPlayer.invoke(list, conn, handle, cookieInitial.invoke(null, profile, false));
        Player p = Bukkit.getPlayer(offlineUuid(name));
        if (p == null) throw new IllegalStateException("сервер не принял бота");
        p.teleport(at);
        settle(p);
        return p;
    }

    /** Убрать бота с сервера (PlayerQuitEvent как при обычном выходе). */
    static void remove(Player p) {
        try {
            Object mc = craftServerGetServer.invoke(Bukkit.getServer());
            removePlayer.invoke(getPlayerList.invoke(mc), handle(p));
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[MGP] Не удалось убрать бота " + p.getName() + ": " + t);
        }
    }

    static Object handle(Player p) throws Exception { return craftPlayerGetHandle.invoke(p); }

    private static Object connectionOf(Player p) throws Exception {
        Object listener = spConnection.get(handle(p));
        if (listener == null) return null;
        for (Class<?> k = listener.getClass(); k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().getName().equals("net.minecraft.network.Connection")) {
                    f.setAccessible(true);
                    return f.get(listener);
                }
            }
        }
        return null;
    }

    /**
     * То, что настоящий клиент подтверждает пакетами: «мир загружен» и «телепорт принят».
     * Пока сервер ждёт этого, по игроку не проходит урон (после входа, смерти и перехода в другой мир).
     */
    static void settle(Player p) {
        try {
            Object h = handle(p);
            Object listener = spConnection.get(h);
            if (listener == null) return;
            if (awaitingPosition.get(listener) != null) awaitingPosition.set(listener, null);
            if ((boolean) isChangingDimension.invoke(h)) hasChangedDimension.invoke(h);
            if (!(boolean) hasClientLoaded.invoke(listener)) markClientLoaded.invoke(listener);
        } catch (Throwable ignored) {}
    }

    /** Один тик физики бота (у настоящего игрока его вызывает обработка пакетов клиента). */
    static void tick(Player p) throws Exception {
        Object h = handle(p);
        doTick.invoke(h);
        Object conn = connectionOf(p);
        if (conn != null) {
            Object ch = connChannel.get(conn);
            if (ch != null) releaseOutbound.invoke(ch); // пакеты «клиенту» никто не читает — выбрасываем
        }
    }

    /** Управление: вперёд/вбок (-1..1), прыжок, поворот головы и тела. */
    static void steer(Player p, float forward, float strafe, boolean jump, float yaw, float pitch) throws Exception {
        Object h = handle(p);
        zza.setFloat(h, forward);
        xxa.setFloat(h, strafe);
        setJumping.invoke(h, jump);
        setYRot.invoke(h, yaw);
        setXRot.invoke(h, pitch);
        setYHeadRot.invoke(h, yaw);
        setYBodyRot.invoke(h, yaw);
    }

    static boolean blocked(Player p) {
        try { return horizontalCollision.getBoolean(handle(p)); } catch (Throwable t) { return false; }
    }
}
