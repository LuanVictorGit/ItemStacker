package it;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class Harness extends JavaPlugin implements org.bukkit.event.Listener {

	interface Scenario {
		void run(Area area) throws Exception;
	}

	final class Area {

		final String name;
		final int cx, cz;

		Area(String name, int cx, int cz) {
			this.name = name;
			this.cx = cx;
			this.cz = cz;
		}

		Location at(double dx, double dz) {
			double x = cx * 16 + 8 + dx, z = cz * 16 + 8 + dz;
			return new Location(world, x, ground((int) Math.floor(x), (int) Math.floor(z)), z);
		}

		void later(long ticks, Runnable task) {
			Harness.this.later(cx, cz, ticks, task);
		}

		List<Item> find(Material... types) {
			List<Material> wanted = Arrays.asList(types);
			List<Item> found = new ArrayList<>();
			for (int x = cx - 1; x <= cx + 1; x++) {
				for (int z = cz - 1; z <= cz + 1; z++) {
					if (!world.isChunkLoaded(x, z)) continue;
					for (Entity entity : world.getChunkAt(x, z).getEntities()) {
						if (entity instanceof Item && !entity.isDead() && wanted.contains(((Item) entity).getItemStack().getType())) found.add((Item) entity);
					}
				}
			}
			return found;
		}

		void finish(boolean ok, String detail) {
			report(name, ok, detail);
		}
	}

	private final boolean folia = exists("io.papermc.paper.threadedregions.RegionizedServer");
	private final Map<String, Scenario> scenarios = new LinkedHashMap<>();
	private final Map<String, int[]> places = new LinkedHashMap<>();
	private final AtomicInteger pending = new AtomicInteger();
	private final AtomicInteger failures = new AtomicInteger();
	private final Deque<Runnable> queue = new ConcurrentLinkedDeque<>();
	private final java.util.Set<Long> held = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private String phase = "";
	private Plugin stacker;
	private Object manager, regions;
	private Method groupOf, statsOf, regionDelayed, regionExecute;
	private Field extraField;
	private World world;

	private static boolean exists(String name) {
		try {
			Class.forName(name);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	private static Object call(Method method, Object target, Object... args) {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(e.getCause());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	void refresh() {
		try {
			manager = stacker.getClass().getMethod("getManager").invoke(stacker);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void onEnable() {
		stacker = Bukkit.getPluginManager().getPlugin("ItemStacker");
		try {
			manager = stacker.getClass().getMethod("getManager").invoke(stacker);
			groupOf = manager.getClass().getMethod("group", int.class);
			statsOf = manager.getClass().getMethod("stats");
			extraField = Class.forName("me.itemstacker.managers.Group", true, stacker.getClass().getClassLoader()).getField("extra");
			if (folia) {
				regions = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
				Class<?> type = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
				regionDelayed = type.getMethod("runDelayed", Plugin.class, World.class, int.class, int.class, Consumer.class, long.class);
				regionExecute = type.getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		register();
		Bukkit.getPluginManager().registerEvents(this, this);
	}

	private final Map<Material, AtomicLong> mobDrops = new java.util.concurrent.ConcurrentHashMap<>();
	private volatile int delivered = -1;

	@org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
	public void onSpawn(org.bukkit.event.entity.ItemSpawnEvent event) {
		if (event.getEntity().getItemStack().getType() == Material.LAPIS_BLOCK) delivered = event.getEntity().getItemStack().getAmount();
	}

	@org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
	public void onDeath(org.bukkit.event.entity.EntityDeathEvent event) {
		if (!"ist-pig".equals(event.getEntity().getCustomName())) return;
		for (ItemStack drop : event.getDrops()) mobDrops.computeIfAbsent(drop.getType(), k -> new AtomicLong()).addAndGet(drop.getAmount());
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		world = Bukkit.getWorlds().get(0);
		start(args.length == 0 ? "main" : args[0]);
		return true;
	}

	void log(String line) {
		getLogger().info("[IT] " + line);
	}

	void report(String name, boolean ok, String detail) {
		if (!ok) failures.incrementAndGet();
		log((ok ? "PASS " : "FAIL ") + name + " " + detail);
		if (pending.decrementAndGet() == 0) log("DONE " + phase + " failures=" + failures.get());
		Runnable next = queue.poll();
		if (next != null) next.run();
	}

	void add(String phaseName, String name, int cx, int cz, Scenario scenario) {
		scenarios.put(phaseName + ":" + name, scenario);
		places.put(phaseName + ":" + name, new int[] { cx, cz });
	}

	void start(String phaseName) {
		phase = phaseName;
		failures.set(0);
		List<String> keys = new ArrayList<>();
		for (String key : scenarios.keySet()) if (key.startsWith(phaseName + ":")) keys.add(key);
		if (keys.isEmpty()) {
			log("DONE " + phaseName + " failures=0 empty");
			return;
		}
		pending.set(keys.size());
		int baseX = world.getSpawnLocation().getBlockX() >> 4, baseZ = world.getSpawnLocation().getBlockZ() >> 4;
		boolean sequential = phaseName.equals("player") || phaseName.equals("visual") || phaseName.equals("vanilla");
		for (String key : keys) {
			int[] at = places.get(key);
			int ax = baseX + at[0], az = baseZ + at[1];
			if (!phaseName.equals("unload")) for (int x = ax - 2; x <= ax + 2; x++) for (int z = az - 2; z <= az + 2; z++) hold(x, z);
			Area area = new Area(key.substring(phaseName.length() + 1), ax, az);
			Runnable launch = () -> later(ax, az, 1, () -> {
				try {
					scenarios.get(key).run(area);
				} catch (Throwable t) {
					t.printStackTrace();
					area.finish(false, "exception " + t);
				}
			});
			if (sequential) queue.add(launch);
			else launch.run();
		}
		Runnable first = queue.poll();
		if (first != null) first.run();
	}

	void hold(int x, int z) {
		hold(x, z, true);
	}

	void hold(int x, int z, boolean keep) {
		try {
			World.class.getMethod("setChunkForceLoaded", int.class, int.class, boolean.class).invoke(world, x, z, keep);
		} catch (NoSuchMethodException e) {
			if (keep) {
				held.add(chunkKey(x, z));
				world.loadChunk(x, z, true);
			} else {
				held.remove(chunkKey(x, z));
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static long chunkKey(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	@org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
	public void onUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
		if (!held.isEmpty() && held.contains(chunkKey(event.getChunk().getX(), event.getChunk().getZ()))) event.setCancelled(true);
	}

	void later(int cx, int cz, long ticks, Runnable task) {
		Runnable guarded = () -> {
			try {
				task.run();
			} catch (Throwable t) {
				t.printStackTrace();
				log("FAIL exception " + t);
				failures.incrementAndGet();
			}
		};
		if (folia) call(regionDelayed, regions, this, world, cx, cz, (Consumer<Object>) t -> guarded.run(), Math.max(1, ticks));
		else Bukkit.getScheduler().runTaskLater(this, guarded, Math.max(1, ticks));
	}

	void now(int cx, int cz, Runnable task) {
		if (folia) call(regionExecute, regions, this, world, cx, cz, task);
		else task.run();
	}

	double ground(int x, int z) {
		int y = world.getHighestBlockYAt(x, z);
		return world.getBlockAt(x, y, z).getType() == Material.AIR ? y : y + 1;
	}

	long extra(Item item) {
		try {
			Object group = groupOf.invoke(manager, item.getEntityId());
			return group == null ? 0 : extraField.getLong(group);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	boolean tracked(Item item) {
		try {
			return groupOf.invoke(manager, item.getEntityId()) != null;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	long total(Item item) {
		return item.getItemStack().getAmount() + extra(item);
	}

	long sum(List<Item> items) {
		long sum = 0;
		for (Item item : items) sum += total(item);
		return sum;
	}

	int maxPhysical(List<Item> items) {
		int max = 0;
		for (Item item : items) max = Math.max(max, item.getItemStack().getAmount());
		return max;
	}

	List<Long> totals(List<Item> items) {
		List<Long> list = new ArrayList<>();
		for (Item item : items) list.add(total(item));
		Collections.sort(list);
		return list;
	}

	String[] stats() {
		return (String[]) call(statsOf, manager);
	}

	int groups() {
		String line = stats()[1];
		return Integer.parseInt(line.substring(line.indexOf(':') + 2, line.indexOf(' ', line.indexOf(':') + 2)));
	}

	void drop(Location at, Material type, int amount) {
		for (int left = amount; left > 0; left -= 64) world.dropItem(at, new ItemStack(type, Math.min(64, left))).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
	}

	void config(String key, Object value) {
		stacker.getConfig().set("Config." + key, value);
		stacker.saveConfig();
		try {
			stacker.getClass().getMethod("reloadPlugin").invoke(stacker);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	Enchantment enchantment() {
		Enchantment found = Enchantment.getByName("DURABILITY");
		return found != null ? found : Enchantment.getByName("UNBREAKING");
	}

	Inventory hopper(Block block) {
		return ((InventoryHolder) block.getState()).getInventory();
	}

	int count(Inventory inventory, Material type) {
		int sum = 0;
		for (ItemStack stack : inventory.getContents()) if (stack != null && stack.getType() == type) sum += stack.getAmount();
		return sum;
	}

	void pickup(Area a, Material type, int amount, Consumer<Player> setup, int expectedHeld, int expectedPile) {
		Player player = anyPlayer();
		player.getInventory().clear();
		if (setup != null) setup.accept(player);
		Location at = player.getLocation();
		Area here = new Area(a.name, at.getBlockX() >> 4, at.getBlockZ() >> 4);
		int before = count(player.getInventory(), type);
		drop(at, type, amount);
		here.later(100, () -> {
			int held = count(player.getInventory(), type) - before;
			List<Item> pile = here.find(type);
			long left = sum(pile);
			for (Item item : pile) item.remove();
			player.getInventory().clear();
			a.finish(held == expectedHeld && left == expectedPile && held + left == amount, "inventory=" + held + "/" + expectedHeld + " pile=" + left + "/" + expectedPile);
		});
	}

	void teleport(Player player, Location to) {
		try {
			Player.class.getMethod("teleportAsync", Location.class).invoke(player, to);
		} catch (NoSuchMethodException e) {
			player.teleport(to);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	Player anyPlayer() {
		for (Player player : Bukkit.getOnlinePlayers()) return player;
		return null;
	}

	void register() {
		add("main", "spawn-merge", 1, 0, a -> {
			Location at = a.at(0, 0);
			drop(at, Material.COBBLESTONE, 640);
			a.later(40, () -> {
				List<Item> l = a.find(Material.COBBLESTONE);
				long t = sum(l);
				a.finish(l.size() == 1 && t == 640 && maxPhysical(l) == 64, "entities=" + l.size() + " total=" + t + " physical=" + maxPhysical(l));
			});
		});
		add("main", "limit-split", 2, 0, a -> {
			drop(a.at(0, 0), Material.DIRT, 1600);
			a.later(40, () -> {
				List<Item> l = a.find(Material.DIRT);
				a.finish(l.size() == 2 && sum(l) == 1600 && totals(l).equals(Arrays.asList(600L, 1000L)), "totals=" + totals(l));
			});
		});
		add("main", "big-quantity", 3, 0, a -> {
			drop(a.at(0, 0), Material.GRAVEL, 128000);
			a.later(100, () -> {
				List<Item> l = a.find(Material.GRAVEL);
				a.finish(l.size() == 2 && sum(l) == 128000 && maxPhysical(l) <= 64 && totals(l).equals(Arrays.asList(28000L, 100000L)), "totals=" + totals(l) + " physical=" + maxPhysical(l));
			});
		});
		add("main", "thresholds", 4, 0, a -> {
			Material[] types = { Material.NETHERRACK, Material.GLOWSTONE, Material.BOOKSHELF, Material.PUMPKIN, Material.SPONGE };
			int[] amounts = { 65, 127, 128, 32767, 32768 };
			for (int i = 0; i < types.length; i++) drop(a.at(0, 0), types[i], amounts[i]);
			a.later(100, () -> {
				StringBuilder detail = new StringBuilder();
				boolean ok = true;
				for (int i = 0; i < types.length; i++) {
					List<Item> l = a.find(types[i]);
					boolean good = l.size() == 1 && sum(l) == amounts[i] && l.get(0).getItemStack().getAmount() == Math.min(64, amounts[i]);
					ok &= good;
					detail.append(amounts[i]).append('=').append(sum(l)).append(good ? " " : "! ");
				}
				a.finish(ok, detail.toString().trim());
			});
		});
		add("main", "incompatible", 1, 1, a -> {
			Location at = a.at(0, 0);
			world.dropItem(at, new ItemStack(Material.STONE, 10));
			world.dropItem(at, new ItemStack(Material.STONE, 10));
			ItemStack named = new ItemStack(Material.STONE, 10);
			ItemMeta meta = named.getItemMeta();
			meta.setDisplayName("Named");
			named.setItemMeta(meta);
			world.dropItem(at, named);
			ItemStack lore = new ItemStack(Material.STONE, 10);
			meta = lore.getItemMeta();
			meta.setLore(Arrays.asList("lore"));
			lore.setItemMeta(meta);
			world.dropItem(at, lore);
			int expected = 3;
			Enchantment enchantment = enchantment();
			if (enchantment != null) {
				ItemStack enchanted = new ItemStack(Material.STONE, 10);
				enchanted.addUnsafeEnchantment(enchantment, 1);
				world.dropItem(at, enchanted);
				expected++;
			}
			ItemStack first = new ItemStack(Material.IRON_PICKAXE, 1);
			first.setDurability((short) 10);
			world.dropItem(at, first);
			ItemStack second = new ItemStack(Material.IRON_PICKAXE, 1);
			second.setDurability((short) 20);
			world.dropItem(at, second);
			world.dropItem(at, second.clone());
			int stones = expected;
			a.later(60, () -> {
				List<Item> s = a.find(Material.STONE), p = a.find(Material.IRON_PICKAXE);
				boolean ok = s.size() == stones && sum(s) == 20 + 10 * (stones - 1) && p.size() == 2 && sum(p) == 3;
				a.finish(ok, "stone entities=" + s.size() + "/" + stones + " total=" + sum(s) + " pickaxe entities=" + p.size() + " total=" + sum(p));
			});
		});
		add("main", "cross-chunk", 6, 0, a -> {
			World w = world;
			double z = a.cz * 16 + 8;
			int x = a.cx * 16 + 16;
			Location left = new Location(w, x - 0.4, ground(x - 1, (int) z), z), right = new Location(w, x + 0.4, ground(x, (int) z), z);
			world.dropItem(left, new ItemStack(Material.REDSTONE_BLOCK, 64));
			world.dropItem(right, new ItemStack(Material.REDSTONE_BLOCK, 64));
			a.later(60, () -> {
				List<Item> l = a.find(Material.REDSTONE_BLOCK);
				a.finish(l.size() == 1 && sum(l) == 128, "entities=" + l.size() + " total=" + sum(l));
			});
		});
		add("main", "blacklist-world", 5, 0, a -> {
			Location at = a.at(0, 0);
			world.dropItem(at, new ItemStack(Material.DIAMOND_SWORD, 1));
			world.dropItem(at, new ItemStack(Material.DIAMOND_SWORD, 1));
			a.later(60, () -> {
				List<Item> l = a.find(Material.DIAMOND_SWORD);
				boolean untouched = true;
				for (Item item : l) untouched &= !tracked(item);
				a.finish(l.size() == 2 && untouched, "entities=" + l.size() + " tracked=" + !untouched);
			});
		});
		add("main", "hopper-empty", 2, 1, a -> {
			Location at = a.at(-4, 0);
			Block block = world.getBlockAt(at.getBlockX(), at.getBlockY() - 1, at.getBlockZ());
			block.setType(Material.HOPPER);
			Inventory inventory = hopper(block);
			drop(new Location(world, at.getBlockX() + 0.5, at.getBlockY() + 0.3, at.getBlockZ() + 0.5), Material.IRON_INGOT, 640);
			StringBuilder trace = new StringBuilder();
			Runnable[] poll = new Runnable[1];
			int[] ticks = { 0 };
			poll[0] = () -> {
				ticks[0] += 20;
				List<Item> l = a.find(Material.IRON_INGOT);
				int inside = count(inventory, Material.IRON_INGOT);
				trace.append(inside).append(',');
				if (inside < 320 && ticks[0] < 400) {
					a.later(20, poll[0]);
					return;
				}
				StringBuilder where = new StringBuilder();
				for (Item item : l) where.append(item.getEntityId()).append(':').append(item.getItemStack().getAmount()).append('+').append(extra(item)).append('@').append(item.getLocation().getBlockX()).append(',').append(item.getLocation().getY()).append(',').append(item.getLocation().getBlockZ()).append(' ');
				a.finish(inside == 320 && sum(l) == 320 && inside + sum(l) == 640, "hopper=" + inside + " pile=" + sum(l) + " ticks=" + ticks[0] + " trace=" + trace + " items=" + where + "hopperBlock=" + block.getX() + "," + block.getY() + "," + block.getZ());
			};
			a.later(20, poll[0]);
		});
		add("main", "hopper-partial", 2, 1, a -> {
			Location at = a.at(4, 0);
			Block block = world.getBlockAt(at.getBlockX(), at.getBlockY() - 1, at.getBlockZ());
			block.setType(Material.HOPPER);
			Inventory inventory = hopper(block);
			for (int i = 0; i < 4; i++) inventory.setItem(i, new ItemStack(Material.DIAMOND, 64));
			inventory.setItem(4, new ItemStack(Material.GOLD_INGOT, 50));
			drop(new Location(world, at.getBlockX() + 0.5, at.getBlockY() + 0.3, at.getBlockZ() + 0.5), Material.GOLD_INGOT, 320);
			a.later(160, () -> {
				List<Item> l = a.find(Material.GOLD_INGOT);
				int inside = count(inventory, Material.GOLD_INGOT);
				boolean intact = count(inventory, Material.DIAMOND) == 256;
				a.finish(intact && inside == 64 && sum(l) == 306 && inside + sum(l) == 370, "hopper=" + inside + " pile=" + sum(l) + " diamonds=" + count(inventory, Material.DIAMOND));
			});
		});
		add("main", "oversized-drop", 7, 0, a -> {
			delivered = -1;
			world.dropItem(a.at(0, 0), new ItemStack(Material.LAPIS_BLOCK, 200));
			int seen = delivered;
			a.later(40, () -> {
				List<Item> l = a.find(Material.LAPIS_BLOCK);
				if (seen != 200) {
					a.finish(sum(l) <= Math.max(0, seen), "server itself keeps only " + seen + " of 200, entities=" + l.size() + " total=" + sum(l));
					return;
				}
				a.finish(l.size() == 1 && sum(l) == 200 && maxPhysical(l) <= 64, "entities=" + l.size() + " total=" + sum(l) + " physical=" + maxPhysical(l));
			});
		});
		add("main", "dropper", 4, 2, a -> {
			Location at = a.at(0, 0);
			drop(at, Material.PRISMARINE, 640);
			Block block = world.getBlockAt(at.getBlockX() + 3, at.getBlockY() + 2, at.getBlockZ());
			block.setType(Material.DROPPER);
			Inventory inventory = ((InventoryHolder) block.getState()).getInventory();
			inventory.setItem(0, new ItemStack(Material.PRISMARINE, 10));
			for (int i = 0; i < 10; i++) a.later(5 + i * 4, () -> ((org.bukkit.block.Dropper) block.getState()).drop());
			a.later(120, () -> {
				List<Item> l = a.find(Material.PRISMARINE);
				int left = count(inventory, Material.PRISMARINE);
				a.finish(sum(l) + left == 650 && left == 0, "pile=" + sum(l) + " entities=" + l.size() + " left=" + left);
			});
		});
		add("main", "dispenser", 5, 2, a -> {
			Location at = a.at(0, 0);
			drop(at, Material.SEA_LANTERN, 640);
			Block block = world.getBlockAt(at.getBlockX() + 3, at.getBlockY() + 2, at.getBlockZ());
			block.setType(Material.DISPENSER);
			Inventory inventory = ((InventoryHolder) block.getState()).getInventory();
			inventory.setItem(0, new ItemStack(Material.SEA_LANTERN, 10));
			for (int i = 0; i < 10; i++) a.later(5 + i * 4, () -> ((org.bukkit.block.Dispenser) block.getState()).dispense());
			a.later(120, () -> {
				List<Item> l = a.find(Material.SEA_LANTERN);
				int left = count(inventory, Material.SEA_LANTERN);
				a.finish(sum(l) + left == 650 && left == 0, "pile=" + sum(l) + " entities=" + l.size() + " left=" + left);
			});
		});
		add("main", "block-drops", 6, 2, a -> {
			Location at = a.at(0, 0);
			List<Block> shelves = new ArrayList<>();
			for (int i = 0; i < 50; i++) {
				Block block = world.getBlockAt(at.getBlockX() + i % 5 - 2, at.getBlockY() + 3 + i / 5, at.getBlockZ() + 2);
				block.setType(Material.BOOKSHELF);
				shelves.add(block);
			}
			a.later(10, () -> {
				for (Block block : shelves) block.breakNaturally();
				a.later(80, () -> {
					List<Item> l = a.find(Material.BOOK);
					a.finish(sum(l) == 150 && l.size() <= 3, "books=" + sum(l) + " entities=" + l.size());
				});
			});
		});
		add("main", "mob-drops", 7, 2, a -> {
			mobDrops.clear();
			Location at = a.at(0, 0);
			for (int i = 0; i < 30; i++) {
				org.bukkit.entity.LivingEntity pig = (org.bukkit.entity.LivingEntity) world.spawnEntity(at, org.bukkit.entity.EntityType.PIG);
				pig.setCustomName("ist-pig");
				pig.damage(1000.0);
			}
			a.later(100, () -> {
				boolean ok = !mobDrops.isEmpty();
				StringBuilder detail = new StringBuilder();
				for (Map.Entry<Material, AtomicLong> entry : mobDrops.entrySet()) {
					List<Item> l = a.find(entry.getKey());
					ok &= sum(l) == entry.getValue().get() && l.size() <= 2;
					detail.append(entry.getKey()).append(" dropped=").append(entry.getValue().get()).append(" pile=").append(sum(l)).append(" entities=").append(l.size()).append(' ');
				}
				a.finish(ok, detail.toString().trim());
			});
		});

		add("vanilla", "vanilla-merge", 1, 3, a -> {
			config("merge_on_spawn", false);
			config("delayCheckItems", 600000);
			config("rescan_seconds", 0);
			Location at = a.at(0, 0);
			world.dropItem(at, new ItemStack(Material.BRICK, 30)).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
			world.dropItem(at.clone().add(0.1, 0, 0), new ItemStack(Material.BRICK, 30)).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
			a.later(100, () -> {
				List<Item> l = a.find(Material.BRICK);
				boolean tracking = true;
				for (Item item : l) tracking &= tracked(item);
				config("merge_on_spawn", true);
				config("delayCheckItems", 500);
				config("rescan_seconds", 5);
				a.finish(sum(l) == 60 && tracking && l.size() <= 2, "entities=" + l.size() + " total=" + sum(l) + " vanillaMerged=" + (l.size() == 1));
			});
		});
		add("vanilla", "vanilla-control", 2, 3, a -> {
			config("blacklist", Arrays.asList("diamond_sword", "flint"));
			Location at = a.at(0, 0);
			world.dropItem(at, new ItemStack(Material.FLINT, 30)).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
			world.dropItem(at.clone().add(0.1, 0, 0), new ItemStack(Material.FLINT, 30)).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
			a.later(100, () -> {
				List<Item> l = a.find(Material.FLINT);
				boolean untouched = true;
				for (Item item : l) untouched &= !tracked(item);
				config("blacklist", Arrays.asList("diamond_sword"));
				a.finish(sum(l) == 60 && untouched, "entities=" + l.size() + " total=" + sum(l) + " vanillaMerged=" + (l.size() == 1) + " (platform behavior without the plugin)");
			});
		});
		add("main", "merge-event-guard", 3, 3, a -> {
			Location at = a.at(0, 0);
			drop(at, Material.CLAY_BALL, 192);
			ItemStack other = new ItemStack(Material.CLAY_BALL, 1);
			ItemMeta meta = other.getItemMeta();
			meta.setDisplayName("other");
			other.setItemMeta(meta);
			Item target = world.dropItem(at.clone().add(3, 0, 3), other);
			a.later(40, () -> {
				List<Item> l = a.find(Material.CLAY_BALL);
				Item pile = null;
				for (Item item : l) if (extra(item) > 0) pile = item;
				if (pile == null) {
					a.finish(false, "pile missing");
					return;
				}
				ItemMergeEvent event = new ItemMergeEvent(pile, target);
				Bukkit.getPluginManager().callEvent(event);
				a.finish(event.isCancelled() && total(pile) == 192, "cancelled=" + event.isCancelled() + " total=" + total(pile));
			});
		});

		add("cleanup", "group-cleanup", 3, 3, a -> {
			Material[] types = { Material.ANVIL, Material.BEACON, Material.JUKEBOX, Material.NOTE_BLOCK, Material.DISPENSER };
			List<Item> spawned = new ArrayList<>();
			for (Material type : types) spawned.add(world.dropItem(a.at(0, 0), new ItemStack(type, 1)));
			a.later(30, () -> {
				int counted = 0;
				for (Item item : spawned) if (tracked(item)) counted++;
				int tracking = counted;
				for (Item item : spawned) item.remove();
				StringBuilder trace = new StringBuilder();
				Runnable[] poll = new Runnable[1];
				int[] waited = { 0 };
				poll[0] = () -> {
					waited[0] += 20;
					int leaked = 0;
					for (Item item : spawned) if (tracked(item)) leaked++;
					trace.append(leaked).append(',');
					if (leaked > 0 && waited[0] < 400) {
						a.later(20, poll[0]);
						return;
					}
					a.finish(tracking == types.length && leaked == 0, "tracked=" + tracking + "/" + types.length + " leaked=" + leaked + " ticks=" + waited[0] + " trace=" + trace);
				};
				a.later(20, poll[0]);
			});
		});

		add("persist-create", "persist-create", 3, 1, a -> {
			Location at = a.at(0, 0);
			drop(at, Material.HAY_BLOCK, 7777);
			ItemStack named = new ItemStack(Material.STONE, 64);
			ItemMeta meta = named.getItemMeta();
			meta.setDisplayName("Persist");
			named.setItemMeta(meta);
			for (int i = 0; i < 46; i++) world.dropItem(at.clone().add(2, 0, 2), named.clone());
			ItemStack rest = named.clone();
			rest.setAmount(56);
			world.dropItem(at.clone().add(2, 0, 2), rest);
			a.later(80, () -> {
				List<Item> hay = a.find(Material.HAY_BLOCK), stone = a.find(Material.STONE);
				Properties properties = new Properties();
				properties.setProperty("hay", String.valueOf(sum(hay)));
				properties.setProperty("stone", String.valueOf(sum(stone)));
				getDataFolder().mkdirs();
				try (FileOutputStream out = new FileOutputStream(new File(getDataFolder(), "persist.properties"))) {
					properties.store(out, null);
				} catch (Exception e) {
					a.finish(false, e.toString());
					return;
				}
				a.finish(hay.size() == 1 && sum(hay) == 7777 && stone.size() == 1 && sum(stone) == 3000, "hay=" + sum(hay) + " stone=" + sum(stone));
			});
		});
		add("persist-verify", "persist-verify", 3, 1, a -> {
			Properties properties = new Properties();
			try (FileInputStream in = new FileInputStream(new File(getDataFolder(), "persist.properties"))) {
				properties.load(in);
			}
			a.later(60, () -> {
				List<Item> hay = a.find(Material.HAY_BLOCK), stone = a.find(Material.STONE);
				boolean ok = hay.size() == 1 && sum(hay) == Long.parseLong(properties.getProperty("hay")) && stone.size() == 1 && sum(stone) == Long.parseLong(properties.getProperty("stone"));
				a.finish(ok, "hay=" + sum(hay) + "/" + properties.getProperty("hay") + " stone=" + sum(stone) + "/" + properties.getProperty("stone") + " tracked=" + (!hay.isEmpty() && tracked(hay.get(0))));
			});
		});

		add("unload", "chunk-unload-reload", 24, 24, a -> {
			world.getChunkAt(a.cx, a.cz).load(true);
			hold(a.cx, a.cz, true);
			Location at = a.at(0, 0);
			drop(at, Material.SLIME_BLOCK, 512);
			world.dropItem(at.clone().add(2, 0, 0), new ItemStack(Material.DIAMOND_SWORD, 1)).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
			a.later(40, () -> {
				long before = sum(a.find(Material.SLIME_BLOCK));
				hold(a.cx, a.cz, false);
				boolean unloaded = world.unloadChunk(a.cx, a.cz, true);
				a.later(80, () -> {
					boolean gone = !world.isChunkLoaded(a.cx, a.cz);
					world.getChunkAt(a.cx, a.cz).load(true);
					hold(a.cx, a.cz, true);
					a.later(100, () -> {
						List<Item> l = a.find(Material.SLIME_BLOCK);
						int swords = a.find(Material.DIAMOND_SWORD).size();
						int all = 0;
						for (Entity entity : world.getChunkAt(a.cx, a.cz).getEntities()) all++;
						hold(a.cx, a.cz, false);
						a.finish(before == 512 && sum(l) == 512 && l.size() == 1 && tracked(l.get(0)), "before=" + before + " unloaded=" + unloaded + "/" + gone + " after=" + sum(l) + " tracked=" + (!l.isEmpty() && tracked(l.get(0))) + " controlSwords=" + swords + " entitiesInChunk=" + all);
					});
				});
			});
		});

		add("control", "pickup-control", 0, 0, a -> {
			Player player = anyPlayer();
			player.getInventory().clear();
			Location at = player.getLocation();
			Area here = new Area(a.name, at.getBlockX() >> 4, at.getBlockZ() >> 4);
			world.dropItem(at, new ItemStack(Material.DIAMOND_SWORD, 1));
			world.dropItem(at, new ItemStack(Material.APPLE, 3));
			here.later(80, () -> {
				int sword = count(player.getInventory(), Material.DIAMOND_SWORD), apple = count(player.getInventory(), Material.APPLE);
				a.finish(sword == 1 && apple == 3, "sword=" + sword + " apple=" + apple);
			});
		});
		add("player", "pickup-bulk", 0, 0, a -> pickup(a, Material.QUARTZ, 5000, null, 2304, 2696));
		add("player", "pickup-partial", 0, 0, a -> pickup(a, Material.EMERALD, 1000, player -> {
			for (int i = 0; i < 30; i++) player.getInventory().setItem(i, new ItemStack(Material.DIAMOND_BLOCK, 64));
			player.getInventory().setItem(30, new ItemStack(Material.EMERALD, 10));
			player.getInventory().setItem(31, new ItemStack(Material.EMERALD, 60));
		}, 314, 686));
		add("player", "pickup-unstackable", 0, 0, a -> pickup(a, Material.IRON_AXE, 100, null, 36, 64));
		add("player", "pickup-full-inventory", 0, 0, a -> pickup(a, Material.COAL, 300, player -> {
			for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.DIAMOND_BLOCK, 64));
		}, 0, 300));
		add("player", "pickup-truncated-10", 0, 0, a -> pickup(a, Material.EMERALD, 200, player -> {
			for (int i = 0; i < 35; i++) player.getInventory().setItem(i, new ItemStack(Material.DIAMOND_BLOCK, 64));
			player.getInventory().setItem(35, new ItemStack(Material.EMERALD, 54));
		}, 10, 190));
		add("player", "pickup-truncated-40", 0, 0, a -> pickup(a, Material.EMERALD, 500, player -> {
			for (int i = 0; i < 35; i++) player.getInventory().setItem(i, new ItemStack(Material.DIAMOND_BLOCK, 64));
			player.getInventory().setItem(35, new ItemStack(Material.EMERALD, 24));
		}, 40, 460));
		add("player", "pickup-vanilla-partial", 0, 0, a -> pickup(a, Material.EMERALD, 50, player -> {
			for (int i = 0; i < 35; i++) player.getInventory().setItem(i, new ItemStack(Material.DIAMOND_BLOCK, 64));
			player.getInventory().setItem(35, new ItemStack(Material.EMERALD, 54));
		}, 10, 40));

		add("visual", "visual-create", 0, 0, a -> {
			Player player = anyPlayer();
			Location at = player.getLocation().add(4, 0, 0);
			Area here = new Area(a.name, at.getBlockX() >> 4, at.getBlockZ() >> 4);
			drop(at, Material.EMERALD_BLOCK, 640);
			here.later(40, () -> {
				List<Item> first = here.find(Material.EMERALD_BLOCK);
				log("PILE id=" + first.get(0).getEntityId() + " total=" + total(first.get(0)) + " step=1 label=Emerald Block");
				drop(at, Material.EMERALD_BLOCK, 360);
				here.later(40, () -> {
					List<Item> second = here.find(Material.EMERALD_BLOCK);
					log("PILE id=" + second.get(0).getEntityId() + " total=" + total(second.get(0)) + " step=2 label=Emerald Block");
					a.finish(second.size() == 1 && total(second.get(0)) == 1000, "total=" + sum(second));
				});
			});
		});

		add("visual", "visual-clear", 0, 0, a -> {
			Player player = anyPlayer();
			Location at = player.getLocation().add(0, 0, 4);
			Area here = new Area(a.name, at.getBlockX() >> 4, at.getBlockZ() >> 4);
			drop(at, Material.GOLD_NUGGET, 3);
			here.later(40, () -> {
				List<Item> l = here.find(Material.GOLD_NUGGET);
				Item pile = l.get(0);
				log("CLEAR id=" + pile.getEntityId() + " total=" + total(pile) + " step=1");
				ItemStack stack = pile.getItemStack();
				stack.setAmount(1);
				pile.setItemStack(stack);
				here.later(60, () -> {
					log("CLEAR id=" + pile.getEntityId() + " total=" + total(pile) + " step=2");
					a.finish(total(pile) == 1, "total=" + total(pile));
				});
			});
		});
		add("visual", "visual-retrack", 0, 0, a -> {
			Player player = anyPlayer();
			Location home = player.getLocation();
			Area here = new Area(a.name, home.getBlockX() >> 4, home.getBlockZ() >> 4);
			List<Item> piles = here.find(Material.EMERALD_BLOCK);
			Item pile = piles.get(0);
			Location far = home.clone().add(400, 0, 400);
			world.getChunkAt(far).load(true);
			teleport(player, far);
			here.later(80, () -> {
				log("RETRACK id=" + pile.getEntityId() + " total=" + total(pile) + " phase=back");
				teleport(player, home);
				here.later(80, () -> a.finish(true, "id=" + pile.getEntityId()));
			});
		});

		add("millions", "millions", 12, 12, a -> {
			long begin = System.nanoTime();
			drop(a.at(0, 0), Material.REDSTONE, 5000000);
			long ms = (System.nanoTime() - begin) / 1000000;
			a.later(100, () -> {
				List<Item> l = a.find(Material.REDSTONE);
				boolean ok = sum(l) == 5000000 && l.size() == 50 && maxPhysical(l) <= 64;
				a.finish(ok, "spawnMs=" + ms + " entities=" + l.size() + " total=" + sum(l) + " physical=" + maxPhysical(l) + " " + stats()[1]);
			});
		});
		int[][] spots = { { 0, 100 }, { 100, 0 }, { 100, 100 }, { -100, 0 }, { 0, -100 }, { -100, -100 } };
		for (int i = 0; folia && i < spots.length; i++) {
			int index = i;
			add("regions", "region-" + i, spots[i][0], spots[i][1], a -> {
				Location at = a.at(0, 0);
				drop(at, Material.SNOW_BLOCK, 3000 + index);
				double border = a.cx * 16 + 16;
				for (int n = 0; n < 40; n++) world.dropItem(new Location(world, border - 0.3 + (n % 2) * 0.6, ground((int) border - 1, (int) at.getZ()), at.getZ()), new ItemStack(Material.SNOW_BLOCK, 64)).setVelocity(new org.bukkit.util.Vector(0, 0, 0));
				a.later(100, () -> {
					List<Item> l = a.find(Material.SNOW_BLOCK);
					a.finish(sum(l) == 3000 + index + 2560 && l.size() <= 2 && maxPhysical(l) <= 64, "entities=" + l.size() + " total=" + sum(l) + " expected=" + (3000 + index + 2560));
				});
			});
		}

		add("lifecycle", "plugin-reenable", 8, 8, a -> {
			if (folia) {
				a.finish(true, "skipped on folia");
				return;
			}
			drop(a.at(0, 0), Material.COAL_BLOCK, 640);
			a.later(40, () -> {
				long before = sum(a.find(Material.COAL_BLOCK));
				Bukkit.getPluginManager().disablePlugin(stacker);
				Bukkit.getPluginManager().enablePlugin(stacker);
				if (!stacker.isEnabled()) {
					a.finish(true, "skipped: this server cannot re-enable a disabled plugin");
					return;
				}
				refresh();
				log("LIFECYCLE enabled=" + stacker.isEnabled() + " " + stats()[1]);
				a.later(60, () -> {
					List<Item> restored = a.find(Material.COAL_BLOCK);
					log("LIFECYCLE restored entities=" + restored.size() + " " + stats()[1]);
					boolean tracking = restored.size() == 1 && tracked(restored.get(0));
					drop(a.at(0, 0), Material.COAL_BLOCK, 64);
					a.later(40, () -> {
						List<Item> after = a.find(Material.COAL_BLOCK);
						a.finish(before == 640 && tracking && after.size() == 1 && sum(after) == 704, "before=" + before + " restored=" + sum(restored) + " tracked=" + tracking + " afterDrop=" + sum(after) + " " + stats()[0]);
					});
				});
			});
		});

		add("stress", "stress", 10, 10, a -> {
			if (folia) {
				a.finish(true, "skipped on folia");
				return;
			}
			Material[] types = { Material.COBBLESTONE, Material.DIRT, Material.GRAVEL, Material.SAND, Material.STONE, Material.CLAY_BALL };
			java.util.Random random = new java.util.Random(7);
			int count = 6000;
			long begin = System.nanoTime();
			for (int i = 0; i < count; i++) world.dropItem(a.at(random.nextInt(7) - 3, random.nextInt(7) - 3), new ItemStack(types[i % types.length], 1 + random.nextInt(8)));
			long spawnMs = (System.nanoTime() - begin) / 1000000;
			List<Item> merged = a.find(types);
			int mergedEntities = merged.size();
			long mergedTotal = sum(merged);
			for (Item item : merged) item.remove();
			config("merge_on_spawn", false);
			config("delayCheckItems", 1000);
			Random again = new Random(7);
			for (int i = 0; i < count; i++) world.dropItem(a.at(again.nextInt(7) - 3, again.nextInt(7) - 3), new ItemStack(types[i % types.length], 1 + again.nextInt(8)));
			List<Item> before = a.find(types);
			int startEntities = before.size();
			long startTotal = sum(before);
			AtomicLong worst = new AtomicLong();
			long[] last = { System.nanoTime() };
			int sampler = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
				long now = System.nanoTime();
				worst.set(Math.max(worst.get(), now - last[0]));
				last[0] = now;
			}, 1, 1);
			int[] waited = { 0 };
			Runnable[] poll = new Runnable[1];
			poll[0] = () -> {
				List<Item> now = a.find(types);
				waited[0] += 20;
				if (now.size() > types.length * 3 && waited[0] < 1200) {
					a.later(20, poll[0]);
					return;
				}
				Bukkit.getScheduler().cancelTask(sampler);
				config("merge_on_spawn", true);
				config("delayCheckItems", 500);
				boolean ok = now.size() <= types.length * 3 && sum(now) == startTotal;
				a.finish(ok, "spawnMergeMs=" + spawnMs + " spawnMergeEntities=" + mergedEntities + " spawnMergeTotal=" + mergedTotal + " entitiesBefore=" + startEntities + " entitiesAfter=" + now.size() + " totalBefore=" + startTotal + " totalAfter=" + sum(now) + " ticksToConverge=" + waited[0] + " worstTickMs=" + worst.get() / 1000000 + " " + stats()[3]);
			};
			a.later(20, poll[0]);
		});
	}
}
