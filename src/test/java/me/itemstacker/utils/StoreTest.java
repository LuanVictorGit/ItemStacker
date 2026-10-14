package me.itemstacker.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {

	private static final List<Runnable> TASKS = new ArrayList<>();

	@TempDir
	Path dir;

	@BeforeAll
	static void server() {
		if (Bukkit.getServer() != null) return;
		BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] { BukkitScheduler.class }, (proxy, method, args) -> {
			if (method.getName().equals("runTaskAsynchronously")) TASKS.add((Runnable) args[1]);
			return null;
		});
		Logger logger = Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		Bukkit.setServer((Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] { Server.class }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "getBukkitVersion": return "1.8.8-R0.1-SNAPSHOT";
				case "getName": case "getVersion": return "Test";
				case "getLogger": return logger;
				case "getScheduler": return scheduler;
				default: return null;
			}
		}));
	}

	@BeforeEach
	void reset() {
		TASKS.clear();
	}

	private Store store() {
		Logger logger = Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] { Plugin.class }, (proxy, method, args) -> {
			if (method.getName().equals("getDataFolder")) return dir.toFile();
			if (method.getName().equals("getLogger")) return logger;
			return null;
		});
		return new Store(plugin, new Platform(plugin));
	}

	private static Item item(UUID id) {
		return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[] { Item.class }, (proxy, method, args) -> method.getName().equals("getUniqueId") ? id : null);
	}

	@Test
	void recordsSurviveARestart() {
		Store first = store();
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		first.write(item(a), 5000);
		first.write(item(b), 7);
		first.flush(true);
		Store second = store();
		assertEquals(5000, second.read(item(a)));
		assertEquals(7, second.read(item(b)));
		assertEquals(2, second.size());
	}

	@Test
	void zeroAndDropRemoveRecords() {
		Store store = store();
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		store.write(item(a), 10);
		store.write(item(b), 20);
		store.write(item(a), 0);
		store.drop(b);
		store.flush(true);
		assertEquals(0, store().size());
		assertEquals(0, store.read(item(a)));
	}

	@Test
	void anOlderSnapshotNeverOverwritesANewerOne() throws Exception {
		Store store = store();
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		store.write(item(a), 1);
		store.flush(false);
		assertEquals(1, TASKS.size());
		store.write(item(b), 2);
		store.flush(true);
		TASKS.get(0).run();
		Store reread = store();
		assertEquals(1, reread.read(item(a)));
		assertEquals(2, reread.read(item(b)));
	}

	@Test
	void aBlockingFlushWritesEvenWhenNothingChanged() {
		Store store = store();
		store.write(item(UUID.randomUUID()), 3);
		store.flush(false);
		assertFalse(new File(dir.toFile(), "groups.dat").exists());
		store.flush(true);
		assertTrue(new File(dir.toFile(), "groups.dat").exists());
		assertEquals(1, store().size());
	}

	@Test
	void aCleanStoreQueuesNothing() {
		store().flush(false);
		assertTrue(TASKS.isEmpty());
	}

	@Test
	void aCorruptFileIsKeptAside() throws Exception {
		Files.write(dir.resolve("groups.dat"), new byte[] { 0, 0, 0, 5, 1, 2, 3 });
		Store store = store();
		assertFalse(new File(dir.toFile(), "groups.dat").exists());
		assertTrue(new File(dir.toFile(), "groups.dat.bad").exists());
		assertEquals(0, store.size());
	}
}
