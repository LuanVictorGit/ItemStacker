package me.itemstacker.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class Platform {

	private static final ClassValue<Method> ITEM = getter("getItem"), ENTITIES = getter("getEntities"), CAUSE = getter("getCause"), REMAINING = getter("getRemaining");

	public final boolean folia, pdc, entityPickup, entityStorage, legacySound;
	public final String name;

	private final Plugin plugin;
	private final Object regions, globals, asyncs, key, longType;
	private final Method regionRun, entityScheduler, entityRun, globalRepeat, globalCancel, asyncRun, ownsEntity, ownsChunk;
	private final Method container, containerGet, containerSet, containerRemove, ownerOf;
	private Object timer;

	public Platform(Plugin plugin) {
		this.plugin = plugin;
		try {
			folia = find("io.papermc.paper.threadedregions.RegionizedServer") != null;
			Class<?> holder = find("org.bukkit.persistence.PersistentDataHolder");
			pdc = holder != null;
			entityPickup = find("org.bukkit.event.entity.EntityPickupItemEvent") != null;
			entityStorage = find("org.bukkit.event.world.EntitiesLoadEvent") != null;
			String version = Bukkit.getBukkitVersion().split("-")[0];
			String[] numbers = version.split("\\.");
			legacySound = numbers[0].equals("1") && Integer.parseInt(numbers[1]) < 9;
			name = Bukkit.getName() + " " + version;
			Method owner = null;
			try {
				owner = Item.class.getMethod("getOwner");
			} catch (NoSuchMethodException ignored) {
			}
			ownerOf = owner;
			if (holder != null) {
				Class<?> data = find("org.bukkit.persistence.PersistentDataContainer"), type = find("org.bukkit.persistence.PersistentDataType"), namespaced = find("org.bukkit.NamespacedKey");
				key = namespaced.getConstructor(Plugin.class, String.class).newInstance(plugin, "extra");
				longType = type.getField("LONG").get(null);
				container = holder.getMethod("getPersistentDataContainer");
				containerGet = data.getMethod("get", namespaced, type);
				containerSet = data.getMethod("set", namespaced, type, Object.class);
				containerRemove = data.getMethod("remove", namespaced);
			} else {
				key = longType = null;
				container = containerGet = containerSet = containerRemove = null;
			}
			if (folia) {
				String scheduler = "io.papermc.paper.threadedregions.scheduler.";
				regions = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
				globals = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
				asyncs = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
				regionRun = find(scheduler + "RegionScheduler").getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
				entityScheduler = Entity.class.getMethod("getScheduler");
				entityRun = find(scheduler + "EntityScheduler").getMethod("run", Plugin.class, Consumer.class, Runnable.class);
				globalRepeat = find(scheduler + "GlobalRegionScheduler").getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
				globalCancel = find(scheduler + "GlobalRegionScheduler").getMethod("cancelTasks", Plugin.class);
				asyncRun = find(scheduler + "AsyncScheduler").getMethod("runNow", Plugin.class, Consumer.class);
				ownsEntity = Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class);
				ownsChunk = Bukkit.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class);
			} else {
				regions = globals = asyncs = null;
				regionRun = entityScheduler = entityRun = globalRepeat = globalCancel = asyncRun = ownsEntity = ownsChunk = null;
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ClassValue<Method> getter(String name) {
		return new ClassValue<Method>() {
			@Override
			protected Method computeValue(Class<?> type) {
				try {
					return type.getMethod(name);
				} catch (NoSuchMethodException e) {
					throw new IllegalStateException(e);
				}
			}
		};
	}

	private static Class<?> find(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	private static Object call(Method method, Object target, Object... args) {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			throw cause instanceof RuntimeException ? (RuntimeException) cause : new IllegalStateException(cause);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	public void entity(Entity entity, Runnable task, Runnable retired) {
		if (!folia) {
			sync(task);
			return;
		}
		Object scheduled = call(entityRun, call(entityScheduler, entity), plugin, (Consumer<Object>) t -> task.run(), retired);
		if (scheduled == null && retired != null) retired.run();
	}

	public void region(World world, int cx, int cz, Runnable task) {
		if (folia) call(regionRun, regions, plugin, world, cx, cz, task);
		else sync(task);
	}

	public void async(Runnable task) {
		if (folia) call(asyncRun, asyncs, plugin, (Consumer<Object>) t -> task.run());
		else Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
	}

	public void repeat(Runnable runnable, long ticks) {
		stop();
		if (folia) timer = call(globalRepeat, globals, plugin, (Consumer<Object>) t -> runnable.run(), ticks, ticks);
		else timer = Bukkit.getScheduler().runTaskTimer(plugin, runnable, ticks, ticks);
	}

	public void stop() {
		if (folia) call(globalCancel, globals, plugin);
		else if (timer != null) ((BukkitTask) timer).cancel();
		timer = null;
	}

	public boolean owns(Entity entity) {
		return !folia || (Boolean) call(ownsEntity, null, entity);
	}

	public boolean owns(World world, int cx, int cz) {
		return !folia || (Boolean) call(ownsChunk, null, world, cx, cz);
	}

	private void sync(Runnable runnable) {
		if (Bukkit.isPrimaryThread()) runnable.run();
		else Bukkit.getScheduler().runTask(plugin, runnable);
	}

	public long extra(Item item) {
		Object value = call(containerGet, call(container, item), key, longType);
		return value == null ? 0 : (Long) value;
	}

	public void extra(Item item, long value) {
		Object data = call(container, item);
		if (value > 0) call(containerSet, data, key, longType, value);
		else call(containerRemove, data, key);
	}

	public UUID owner(Item item) {
		return ownerOf == null ? null : (UUID) call(ownerOf, item);
	}

	public Item pickedItem(Event event) {
		return (Item) call(ITEM.get(event.getClass()), event);
	}

	public String causeOf(Event event) {
		return String.valueOf(call(CAUSE.get(event.getClass()), event));
	}

	public int remaining(Event event) {
		return (Integer) call(REMAINING.get(event.getClass()), event);
	}

	@SuppressWarnings("unchecked")
	public List<Entity> entitiesOf(Event event) {
		return (List<Entity>) call(ENTITIES.get(event.getClass()), event);
	}

	@SuppressWarnings("unchecked")
	public void listen(Listener listener, String event, EventPriority priority, Consumer<Event> handler) {
		Class<?> type = find(event);
		if (type == null) return;
		Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, listener, priority, (l, e) -> {
			if (type.isInstance(e)) handler.accept(e);
		}, plugin, true);
	}

	private final ConcurrentHashMap<UUID, Integer> despawnRates = new ConcurrentHashMap<>();

	private int queryDespawnRate(World world) {
		try {
			Method getHandle = world.getClass().getMethod("getHandle");
			Object handle = getHandle.invoke(world);
			Field spigotConfig = handle.getClass().getField("spigotConfig");
			Object cfg = spigotConfig.get(handle);
			Field rate = cfg.getClass().getField("itemDespawnRate");
			int val = ((Number) rate.get(cfg)).intValue();
			if (val > 0) return val;
		} catch (Throwable ignored) {
		}
		try {
			Method spigotMethod = Bukkit.class.getMethod("spigot");
			Object spigot = spigotMethod.invoke(null);
			Method getConfig = spigot.getClass().getMethod("getConfig");
			Object config = getConfig.invoke(spigot);
			if (config instanceof org.bukkit.configuration.ConfigurationSection) {
				org.bukkit.configuration.ConfigurationSection section = (org.bukkit.configuration.ConfigurationSection) config;
				int val = section.getInt("world-settings." + world.getName() + ".item-despawn-rate", -1);
				if (val <= 0) val = section.getInt("world-settings.default.item-despawn-rate", -1);
				if (val > 0) return val;
			}
		} catch (Throwable ignored) {
		}
		return 6000;
	}

	public int despawnRate(World world) {
		Integer cached = despawnRates.get(world.getUID());
		if (cached != null) return cached;
		int rate = queryDespawnRate(world);
		despawnRates.put(world.getUID(), rate);
		return rate;
	}

	public void clearRates() {
		despawnRates.clear();
	}
}
