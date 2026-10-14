package me.itemstacker.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import me.itemstacker.utils.API;

class ConfigManagerTest {

	private final List<String> warnings = new ArrayList<>();

	private Plugin plugin(YamlConfiguration yaml) {
		Logger logger = Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		logger.addHandler(new Handler() {
			@Override
			public void publish(LogRecord record) {
				warnings.add(record.getMessage());
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		});
		return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] { Plugin.class }, (proxy, method, args) -> {
			if (method.getName().equals("getConfig")) return yaml;
			if (method.getName().equals("getLogger")) return logger;
			return null;
		});
	}

	private YamlConfiguration defaults() {
		return YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
	}

	private ConfigManager with(String overrides) {
		YamlConfiguration yaml = defaults();
		YamlConfiguration extra = YamlConfiguration.loadConfiguration(new StringReader("Config:\n" + overrides));
		for (String key : extra.getConfigurationSection("Config").getKeys(true)) {
			if (!extra.isConfigurationSection("Config." + key)) yaml.set("Config." + key, extra.get("Config." + key));
		}
		return new ConfigManager(plugin(yaml));
	}

	@Test
	void shippedDefaults() {
		ConfigManager config = new ConfigManager(plugin(defaults()));
		assertEquals(100000, config.defaultLimit);
		assertEquals(100000, config.limit(Material.STONE));
		assertEquals(8, config.distance);
		assertEquals(2, config.minShown);
		assertEquals(3000, config.mergeDelay);
		assertEquals(5, config.visualTicks);
		assertEquals(5000000, config.slice);
		assertTrue(config.nameActive && config.nameAlways && config.mergeOnSpawn && config.custom && config.unstackable);
		assertEquals("§b{displayname} §a{amount}x", config.format);
		assertTrue(config.blocks(new ItemStack(Material.DIAMOND_SWORD)));
		assertTrue(config.blocks(new ItemStack(Material.DIAMOND_SWORD, 1, (short) 100)));
		assertFalse(config.blocks(new ItemStack(Material.STONE)));
		assertTrue(warnings.isEmpty());
	}

	@Test
	void perMaterialLimitsOverrideTheDefault() {
		ConfigManager config = with("  stack_limits:\n    DIAMOND: 5000\n    cobblestone: 10000000\n    NOT_A_MATERIAL: 5\n    STONE: 0\n");
		assertEquals(5000, config.limit(Material.DIAMOND));
		assertEquals(10000000, config.limit(Material.COBBLESTONE));
		assertEquals(100000, config.limit(Material.STONE));
		assertEquals(2, warnings.size());
	}

	@Test
	void limitsAreClampedToASafeRange() {
		assertEquals(API.MAX_AMOUNT, with("  stack_size: 9223372036854775807\n").defaultLimit);
		assertEquals(1, with("  stack_size: 0\n").defaultLimit);
		assertEquals(1, with("  stack_size: -20\n").defaultLimit);
		assertEquals(32, with("  stack_distance: 100\n").distance);
		assertEquals(1, with("  stack_distance: 0\n").distance);
		assertEquals(1, with("  visual_interval: 0\n").visualTicks);
		assertEquals(1000000, with("  slice_ms: 0\n").slice);
	}

	@Test
	void blacklistSupportsMaterialsAndDataValues() {
		ConfigManager config = with("  blacklist:\n    - \"wool>14\"\n    - \"stick\"\n    - \"276\"\n    - \"nonexistent_thing\"\n    - \"wool>abc\"\n    - \"wool>\"\n");
		assertTrue(config.blocks(new ItemStack(Material.WOOL, 1, (short) 14)));
		assertFalse(config.blocks(new ItemStack(Material.WOOL, 1, (short) 0)));
		assertTrue(config.blocks(new ItemStack(Material.STICK)));
		assertTrue(config.blocks(new ItemStack(Material.DIAMOND_SWORD)));
		assertFalse(config.blocks(new ItemStack(Material.DIAMOND_PICKAXE)));
		assertEquals(3, warnings.size());
	}

	@Test
	void whitelistLimitsStackingToTheListedMaterials() {
		ConfigManager config = with("  whitelist:\n    - \"cobblestone\"\n    - \"diamond_sword\"\n    - \"nonexistent_thing\"\n");
		assertFalse(config.blocks(new ItemStack(Material.COBBLESTONE)));
		assertTrue(config.blocks(new ItemStack(Material.DIAMOND_SWORD)));
		assertTrue(config.blocks(new ItemStack(Material.STONE)));
		assertEquals(1, warnings.size());
	}

	@Test
	void ignoredWorldsAreCaseInsensitive() {
		ConfigManager config = with("  ignored_worlds:\n    - \"Creative\"\n");
		assertTrue(config.ignores(world("creative")));
		assertTrue(config.ignores(world("CREATIVE")));
		assertFalse(config.ignores(world("world")));
	}

	private World world(String name) {
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, args) -> method.getName().equals("getName") ? name : null);
	}
}
