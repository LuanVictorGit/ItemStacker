package me.itemstacker.managers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import me.itemstacker.Core;
import me.itemstacker.utils.API;
import me.itemstacker.utils.Platform;
import me.itemstacker.utils.Store;
import me.itemstacker.utils.Visuals;

public final class Manager {

	static final class Bucket {

		final World world;
		final int cx, cz;
		final ArrayList<Group> list = new ArrayList<>();
		final AtomicBoolean visualQueued = new AtomicBoolean(), mergeQueued = new AtomicBoolean(), dispatched = new AtomicBoolean();
		volatile boolean wantVisual, wantMerge, refresh;
		volatile int count;
		int cursor;

		Bucket(World world, int cx, int cz) {
			this.world = world;
			this.cx = cx;
			this.cz = cz;
		}
	}

	private static final class Ticket {

		final Bucket bucket;
		final long due;

		Ticket(Bucket bucket, long due) {
			this.bucket = bucket;
			this.due = due;
		}
	}

	public volatile ConfigManager cfg;
	public volatile Visuals visuals;
	public final Platform platform;
	public final Store store;

	private final Plugin plugin;
	private final ConcurrentHashMap<Integer, Group> groups = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Bucket>> worlds = new ConcurrentHashMap<>();
	private final PriorityBlockingQueue<Ticket> visualQueue = new PriorityBlockingQueue<>(64, (a, b) -> Long.compare(a.due, b.due)), mergeQueue = new PriorityBlockingQueue<>(64, (a, b) -> Long.compare(a.due, b.due));
	private final AtomicInteger queued = new AtomicInteger();
	private final LongAdder merged = new LongAdder(), absorbed = new LongAdder(), picked = new LongAdder(), sucked = new LongAdder(), pushes = new LongAdder(), runs = new LongAdder();
	private final AtomicLong nanos = new AtomicLong(), worst = new AtomicLong(), lastError = new AtomicLong();
	private final long origin = System.nanoTime();
	private volatile long nextRescan, nextSave, nextTimer;
	private volatile boolean overflow;

	public Manager(Core plugin, ConfigManager cfg, Platform platform, Store store) {
		this.plugin = plugin;
		this.cfg = cfg;
		this.platform = platform;
		this.store = store;
	}

	public static Manager get() {
		return Core.getInstance().getManager();
	}

	private long now() {
		return (System.nanoTime() - origin) / 1000000L;
	}

	public Group group(int id) {
		return groups.get(id);
	}

	public void start() {
		platform.repeat(this::cycle, cfg.visualTicks);
		if (platform.folia) return;
		for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) load(Arrays.asList(chunk.getEntities()), true);
	}

	public void reload(ConfigManager next) {
		cfg = next;
		platform.clearRates();
		for (ConcurrentHashMap<Long, Bucket> map : worlds.values()) {
			for (Bucket bucket : map.values()) {
				bucket.refresh = true;
				enqueueVisual(bucket);
			}
		}
		platform.repeat(this::cycle, next.visualTicks);
	}

	public void stop() {
		platform.stop();
		store.flush(true);
		groups.clear();
		worlds.clear();
		visualQueue.clear();
		mergeQueue.clear();
		queued.set(0);
	}

	public void save() {
		store.flush(false);
	}

	private void enqueue(PriorityBlockingQueue<Ticket> queue, AtomicBoolean queuedFlag, Bucket bucket, long delay) {
		if (!queuedFlag.compareAndSet(false, true)) return;
		if (queued.get() >= cfg.queueLimit) {
			queuedFlag.set(false);
			overflow = true;
			return;
		}
		queued.incrementAndGet();
		queue.add(new Ticket(bucket, now() + delay));
	}

	private void enqueueVisual(Bucket bucket) {
		bucket.wantVisual = true;
		enqueue(visualQueue, bucket.visualQueued, bucket, cfg.visualTicks * 50L);
	}

	private void enqueueMerge(Bucket bucket, long delay) {
		bucket.wantMerge = true;
		enqueue(mergeQueue, bucket.mergeQueued, bucket, delay);
	}

	private void poke(Group group) {
		group.dirty = true;
		if (group.bucket != null) enqueueVisual(group.bucket);
	}

	private void cycle() {
		try {
			long now = now();
			int budget = drain(visualQueue, true, now, cfg.budget);
			drain(mergeQueue, false, now, Math.max(budget, cfg.budget / 4));
			if (overflow && queued.get() < cfg.queueLimit) retry();
			if (cfg.timerActive && now >= nextTimer) countdown(now);
			if (cfg.rescan > 0 && now >= nextRescan) rescan(now);
			if (now >= nextSave) {
				nextSave = now + cfg.save * 1000L;
				store.flush(false);
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	private int drain(PriorityBlockingQueue<Ticket> queue, boolean visual, long now, int budget) {
		Ticket ticket;
		while (budget > 0 && (ticket = queue.poll()) != null) {
			if (ticket.due > now) {
				queue.add(ticket);
				break;
			}
			Bucket bucket = ticket.bucket;
			(visual ? bucket.visualQueued : bucket.mergeQueued).set(false);
			queued.decrementAndGet();
			if (bucket.count <= 0 || !bucket.dispatched.compareAndSet(false, true)) continue;
			budget -= bucket.count;
			try {
				platform.region(bucket.world, bucket.cx, bucket.cz, () -> process(bucket));
			} catch (RuntimeException e) {
				bucket.dispatched.set(false);
				throw e;
			}
		}
		return budget;
	}

	private void retry() {
		overflow = false;
		for (ConcurrentHashMap<Long, Bucket> map : worlds.values()) {
			for (Bucket bucket : map.values()) {
				if (bucket.wantMerge) enqueueMerge(bucket, 0);
				if (bucket.wantVisual) enqueueVisual(bucket);
			}
		}
	}

	private void countdown(long now) {
		nextTimer = now + 1000L;
		for (ConcurrentHashMap<Long, Bucket> map : worlds.values()) for (Bucket bucket : map.values()) if (bucket.count > 0) enqueueVisual(bucket);
	}

	private void rescan(long now) {
		nextRescan = now + cfg.rescan * 1000L;
		for (ConcurrentHashMap<Long, Bucket> map : worlds.values()) {
			for (Bucket bucket : map.values()) {
				if (bucket.count <= 0) continue;
				enqueueMerge(bucket, 0);
				if (bucket.wantVisual) enqueueVisual(bucket);
			}
		}
	}

	private void fail(Throwable t) {
		long now = now();
		if (now - lastError.get() > 10000) {
			lastError.set(now);
			plugin.getLogger().log(Level.WARNING, "ItemStacker task failed", t);
		}
	}

	private Bucket bucket(World world, int cx, int cz) {
		return worlds.computeIfAbsent(world.getUID(), k -> new ConcurrentHashMap<>()).computeIfAbsent(API.key(cx, cz), k -> new Bucket(world, cx, cz));
	}

	private void place(Group group, World world, int cx, int cz) {
		Bucket bucket = bucket(world, cx, cz);
		group.bucket = bucket;
		bucket.list.add(group);
		bucket.count = bucket.list.size();
		enqueueMerge(bucket, cfg.mergeDelay);
	}

	private boolean eligible(Item item, ItemStack stack) {
		ConfigManager c = cfg;
		return stack != null && stack.getAmount() > 0 && stack.getType() != Material.AIR && !c.ignores(item.getWorld()) && !c.blocks(stack)
				&& (c.custom || !stack.hasItemMeta()) && (c.unstackable || stack.getMaxStackSize() > 1)
				&& item.getPickupDelay() < Short.MAX_VALUE && item.getCustomName() == null && platform.owner(item) == null;
	}

	public void load(Iterable<? extends Entity> entities, boolean announce) {
		for (Entity entity : entities) {
			if (!(entity instanceof Item) || groups.containsKey(entity.getEntityId())) continue;
			Item item = (Item) entity;
			ItemStack stack = item.getItemStack();
			long extra = store.read(item);
			boolean mergeable = eligible(item, stack);
			if (!mergeable && extra <= 0) continue;
			Location at = item.getLocation();
			Group group = new Group(item, stack, mergeable);
			group.extra = extra;
			group.total = stack.getAmount() + extra;
			if (cfg.timerActive) {
				int lived = item.getTicksLived();
				group.lastSeconds = lived < 0 ? 0 : Math.max(0, platform.despawnRate(at.getWorld()) - lived) / 20;
			}
			group.dirty = announce;
			groups.put(group.id, group);
			place(group, at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
			if (stack.getAmount() > cap(stack)) set(group, group.total);
			if (announce) enqueueVisual(group.bucket);
		}
	}

	private static int cap(ItemStack stack) {
		return Math.max(1, Math.min(99, stack.getMaxStackSize()));
	}

	public boolean absorb(Item item) {
		if (store.read(item) > 0) {
			load(Collections.singletonList(item), false);
			return false;
		}
		ConfigManager c = cfg;
		ItemStack stack = item.getItemStack();
		if (!eligible(item, stack)) return false;
		Location at = item.getLocation();
		World world = at.getWorld();
		int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
		Group group = new Group(item, stack, true);
		group.x = at.getX();
		group.y = at.getY();
		group.z = at.getZ();
		group.total = stack.getAmount();
		if (c.timerActive) {
			int lived = item.getTicksLived();
			group.lastSeconds = lived < 0 ? 0 : Math.max(0, platform.despawnRate(world) - lived) / 20;
		}
		if (c.mergeOnSpawn) {
			long limit = c.limit(group.type);
			double radius = (double) c.distance * c.distance;
			Location scratch = new Location(world, 0, 0, 0);
			Group[] hit = new Group[1];
			around(new Bucket(world, cx, cz), 0, other -> usable(group, other, limit, radius, scratch, stack) && (hit[0] = other) != null);
			Group target = hit[0];
			long move = target == null ? 0 : Math.min(stack.getAmount(), API.room(target.total, limit));
			if (move > 0) {
				set(target, API.sum(target.total, move));
				try {
					absorbed.increment();
					age(target.item, item);
					if (move == stack.getAmount()) return true;
					stack.setAmount(stack.getAmount() - (int) move);
					item.setItemStack(stack);
					group.total = stack.getAmount();
				} catch (RuntimeException e) {
					set(target, target.total - move);
					throw e;
				}
			}
		}
		groups.put(group.id, group);
		place(group, world, cx, cz);
		if (stack.getAmount() > cap(stack)) set(group, group.total);
		return false;
	}

	public void unload(World world, int cx, int cz) {
		ConcurrentHashMap<Long, Bucket> map = worlds.get(world.getUID());
		Bucket bucket = map == null ? null : map.remove(API.key(cx, cz));
		if (bucket != null) detach(bucket);
	}

	public void unload(World world) {
		ConcurrentHashMap<Long, Bucket> map = worlds.remove(world.getUID());
		if (map != null) for (Bucket bucket : map.values()) detach(bucket);
	}

	public void unload(Iterable<? extends Entity> entities) {
		for (Entity entity : entities) {
			Group group = entity instanceof Item ? groups.remove(entity.getEntityId()) : null;
			if (group != null) group.dead = true;
		}
	}

	private void detach(Bucket bucket) {
		for (Group group : bucket.list) {
			groups.remove(group.id, group);
			group.bucket = null;
		}
		bucket.list.clear();
		bucket.count = 0;
	}

	public void despawn(Item item) {
		Group group = groups.get(item.getEntityId());
		if (group != null) kill(group);
	}

	private void kill(Group group) {
		retire(group);
		if (group.bucket != null) enqueueVisual(group.bucket);
	}

	private void retire(Group group) {
		group.dead = true;
		groups.remove(group.id, group);
		store.drop(group.uuid);
	}

	private void process(Bucket bucket) {
		bucket.dispatched.set(false);
		long start = System.nanoTime();
		int from = bucket.cursor;
		boolean merge = bucket.wantMerge || from > 0, refresh = bucket.refresh;
		bucket.cursor = 0;
		bucket.wantMerge = false;
		bucket.wantVisual = false;
		bucket.refresh = false;
		try {
			if (!bucket.world.isChunkLoaded(bucket.cx, bucket.cz)) {
				ConcurrentHashMap<Long, Bucket> map = worlds.get(bucket.world.getUID());
				if (map != null) map.remove(API.key(bucket.cx, bucket.cz), bucket);
				detach(bucket);
				return;
			}
			Location loc = new Location(bucket.world, 0, 0, 0);
			ArrayList<Group> list = bucket.list;
			if (from == 0) {
				for (int i = list.size() - 1; i >= 0; i--) {
					if (sync(list.get(i), bucket, loc)) continue;
					list.set(i, list.get(list.size() - 1));
					list.remove(list.size() - 1);
				}
			}
			if (refresh) {
				for (Group group : list) {
					group.view = null;
					group.shown = -1;
					group.shownSeconds = -1;
					group.dirty = true;
				}
			}
			if (merge) {
				long deadline = start + cfg.slice;
				for (int i = from; i < list.size(); i++) {
					if (i > from && (i & 7) == 7 && System.nanoTime() > deadline) {
						bucket.cursor = i;
						enqueueMerge(bucket, 0);
						break;
					}
					Group group = list.get(i);
					if (!group.dead && group.mergeable && group.total < cfg.limit(group.type)) scan(bucket, i, loc);
				}
			}
			flush(bucket, loc);
			bucket.count = list.size();
			if (list.isEmpty()) {
				ConcurrentHashMap<Long, Bucket> map = worlds.get(bucket.world.getUID());
				if (map != null) map.remove(API.key(bucket.cx, bucket.cz), bucket);
			}
		} catch (Throwable t) {
			fail(t);
		} finally {
			long elapsed = System.nanoTime() - start;
			if (cfg.debug && elapsed > 5000000L) plugin.getLogger().info("Slow bucket " + bucket.cx + "," + bucket.cz + " (" + bucket.list.size() + " groups): " + elapsed / 1000 + "us");
			runs.increment();
			nanos.addAndGet(elapsed);
			long peak;
			while (elapsed > (peak = worst.get()) && !worst.compareAndSet(peak, elapsed)) {
			}
		}
	}

	private boolean sync(Group group, Bucket bucket, Location loc) {
		if (group.dead) return false;
		Item item = group.item;
		if (!platform.owns(item)) {
			platform.entity(item, () -> relocate(group), () -> groups.remove(group.id, group));
			return false;
		}
		if (!item.isValid()) {
			group.dead = true;
			groups.remove(group.id, group);
			if (item.isDead()) store.drop(group.uuid);
			return false;
		}
		item.getLocation(loc);
		int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
		if (cx != bucket.cx || cz != bucket.cz) {
			place(group, bucket.world, cx, cz);
			return false;
		}
		group.x = loc.getX();
		group.y = loc.getY();
		group.z = loc.getZ();
		group.total = item.getItemStack().getAmount() + group.extra;
		if (group.shown >= 0 && group.shown != group.total) group.dirty = true;
		if (cfg.timerActive) {
			int lived = item.getTicksLived();
			long seconds = lived < 0 ? 0 : Math.max(0, platform.despawnRate(bucket.world) - lived) / 20;
			if (group.lastSeconds != seconds) {
				group.lastSeconds = seconds;
				if (group.shown >= 0) group.dirty = true;
			}
		}
		return true;
	}

	private void relocate(Group group) {
		if (group.dead) return;
		if (!group.item.isValid()) {
			group.dead = true;
			groups.remove(group.id, group);
			return;
		}
		Location at = group.item.getLocation();
		place(group, at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
	}

	private boolean live(Group group, Location loc) {
		if (group.dead) return false;
		Item item = group.item;
		if (!item.isValid() || !platform.owns(item)) return false;
		item.getLocation(loc);
		group.x = loc.getX();
		group.y = loc.getY();
		group.z = loc.getZ();
		group.total = item.getItemStack().getAmount() + group.extra;
		return true;
	}

	private void around(Bucket home, int from, Predicate<Group> visitor) {
		ConcurrentHashMap<Long, Bucket> map = worlds.get(home.world.getUID());
		if (map == null) return;
		int reach = (cfg.distance + 15) >> 4;
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				Bucket other = map.get(API.key(home.cx + dx, home.cz + dz));
				if (other == null || other != home && !platform.owns(other.world, other.cx, other.cz)) continue;
				ArrayList<Group> list = other.list;
				for (int i = other == home ? from : 0; i < list.size(); i++) if (visitor.test(list.get(i))) return;
			}
		}
	}

	private boolean match(Group a, Group b, long limit, double radius) {
		if (a == b || b.dead || !b.mergeable || b.hash != a.hash || b.type != a.type || b.data != a.data || b.total >= limit) return false;
		double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
		return dx * dx + dy * dy + dz * dz <= radius;
	}

	private boolean usable(Group a, Group b, long limit, double radius, Location loc, ItemStack sample) {
		return match(a, b, limit, radius) && live(b, loc) && match(a, b, limit, radius) && b.item.getItemStack().isSimilar(sample);
	}

	private void scan(Bucket home, int index, Location loc) {
		Group a = home.list.get(index);
		if (!live(a, loc)) return;
		long limit = cfg.limit(a.type);
		double radius = (double) cfg.distance * cfg.distance;
		ItemStack sample = a.item.getItemStack();
		around(home, index + 1, b -> {
			if (usable(a, b, limit, radius, loc, sample)) {
				boolean keep = a.total > b.total || a.total == b.total && a.id < b.id;
				move(keep ? a : b, keep ? b : a, limit);
			}
			return a.dead || a.total >= limit;
		});
	}

	private void move(Group to, Group from, long limit) {
		long amount = Math.min(from.total, API.room(to.total, limit));
		if (amount <= 0) return;
		set(to, API.sum(to.total, amount));
		try {
			if (amount == from.total) {
				from.item.remove();
				kill(from);
			} else {
				set(from, from.total - amount);
			}
		} catch (RuntimeException e) {
			set(to, to.total - amount);
			throw e;
		}
		age(to.item, from.item);
		merged.increment();
	}

	private void age(Item keep, Item other) {
		keep.setTicksLived(Math.max(1, Math.min(keep.getTicksLived(), other.getTicksLived())));
	}

	private void set(Group group, long total) {
		Item item = group.item;
		ItemStack stack = item.getItemStack();
		int physical = API.physical(total, cap(stack));
		long extra = total - physical;
		if (stack.getAmount() != physical) {
			stack.setAmount(physical);
			item.setItemStack(stack);
		}
		if (extra != group.extra) {
			group.extra = extra;
			store.write(item, extra);
		}
		group.total = total;
		poke(group);
	}

	private void flush(Bucket bucket, Location loc) {
		Visuals v = visuals;
		ConfigManager c = cfg;
		List<Player> all = null, near = null;
		double range = (double) c.viewRange * c.viewRange;
		for (Group group : bucket.list) {
			if (!group.dirty || group.dead) continue;
			boolean timerChanged = c.timerActive && group.shownSeconds != group.lastSeconds;
			if (v == null || (group.shown == group.total && !timerChanged) || !c.nameActive && !group.named) {
				group.dirty = false;
				continue;
			}
			if (all == null) {
				all = new ArrayList<>();
				near = new ArrayList<>();
				for (Player player : Bukkit.getOnlinePlayers()) if (player.getWorld() == bucket.world) all.add(player);
			}
			near.clear();
			long total = group.total;
			double x = group.x, y = group.y, z = group.z;
			for (Player player : all) {
				if (platform.owns(player)) {
					player.getLocation(loc);
					double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
					if (dx * dx + dy * dy + dz * dz <= range) near.add(player);
				} else {
					platform.entity(player, () -> {
						Location at = player.getLocation();
						double dx = at.getX() - x, dy = at.getY() - y, dz = at.getZ() - z;
						if (dx * dx + dy * dy + dz * dz <= range) v.push(group, total, Collections.singletonList(player));
					}, null);
				}
			}
			if (!near.isEmpty()) {
				v.push(group, total, near);
				pushes.increment();
			}
			group.shown = total;
			group.shownSeconds = group.lastSeconds;
			group.dirty = false;
		}
	}

	public void vanillaMerge(ItemMergeEvent event) {
		Group from = groups.get(event.getEntity().getEntityId()), to = groups.get(event.getTarget().getEntityId());
		if (from != null && from.extra > 0) {
			event.setCancelled(true);
			return;
		}
		if (from != null) kill(from);
		if (to != null) poke(to);
	}

	public void pickup(Cancellable event, Player player, Item item, int remaining) {
		Group group = groups.get(item.getEntityId());
		if (group == null) return;
		if (group.extra <= 0) {
			poke(group);
			return;
		}
		event.setCancelled(true);
		ItemStack stack = item.getItemStack();
		stack.setAmount(stack.getAmount() + remaining);
		if (player == null || !platform.owns(item)) return;
		UUID owner = platform.owner(item);
		if (owner != null && !owner.equals(player.getUniqueId())) return;
		ItemStack unit = stack.clone();
		long total = unit.getAmount() + group.extra;
		int moved = API.insert(player.getInventory(), 36, unit, (int) Math.min(total, Integer.MAX_VALUE));
		if (moved <= 0) return;
		picked.increment();
		boolean empty = take(group, total, moved);
		if (cfg.sound) player.playSound(player.getLocation(), platform.legacySound ? "random.pop" : "entity.item.pickup", 0.2F, 1.5F + ThreadLocalRandom.current().nextFloat() * 0.5F);
		Visuals v = visuals;
		if (empty && v != null) v.collect(group, player, moved, nearby(player.getLocation(), cfg.viewRange));
	}

	public void suck(InventoryPickupItemEvent event) {
		Item item = event.getItem();
		Group group = groups.get(item.getEntityId());
		if (group == null) return;
		if (group.extra <= 0) {
			poke(group);
			return;
		}
		event.setCancelled(true);
		long now = now();
		if (now < group.cool || !platform.owns(item)) return;
		ItemStack unit = item.getItemStack().clone();
		long total = unit.getAmount() + group.extra;
		Inventory inventory = event.getInventory();
		int moved = API.insert(inventory, inventory.getSize(), unit, (int) Math.min(total, Math.max(1, unit.getMaxStackSize())));
		if (moved <= 0) return;
		group.cool = now + 400;
		sucked.increment();
		take(group, total, moved);
	}

	private boolean take(Group group, long total, long amount) {
		long left = total - amount;
		if (left > 0) {
			set(group, left);
			return false;
		}
		group.item.remove();
		kill(group);
		return true;
	}

	private List<Player> nearby(Location center, int range) {
		List<Player> players = new ArrayList<>();
		double limit = (double) range * range;
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (!platform.owns(player)) continue;
			Location at = player.getLocation();
			if (at.getWorld() == center.getWorld() && at.distanceSquared(center) <= limit) players.add(player);
		}
		return players;
	}

	public String[] stats() {
		long hidden = 0;
		for (Group group : groups.values()) hidden += group.extra;
		long count = runs.sum();
		return new String[] {
				"Platform: " + platform.name + (platform.pdc ? " (entity data)" : " (groups.dat: " + store.size() + ")"),
				"Groups: " + groups.size() + " | Hidden items: " + hidden + " | Queue: " + queued.get(),
				"Merged: " + merged.sum() + " | Spawn merged: " + absorbed.sum() + " | Pickups: " + picked.sum() + " | Hoppers: " + sucked.sum(),
				"Name updates: " + pushes.sum() + " | Runs: " + count + " | Avg: " + (count == 0 ? 0 : nanos.get() / count / 1000) + "us | Worst: " + worst.get() / 1000 + "us"
		};
	}
}
