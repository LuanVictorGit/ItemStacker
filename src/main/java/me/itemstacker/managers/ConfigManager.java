package me.itemstacker.managers;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import me.itemstacker.utils.API;

public final class ConfigManager {

	public final String tag, format, timeFormat, timerSuffix;
	public final boolean nameActive, nameAlways, timerActive, sound, debug, mergeOnSpawn, custom, unstackable;
	public final int distance, minShown, viewRange, visualTicks, budget, queueLimit, rescan, save;
	public final long mergeDelay, defaultLimit, slice;

	private final Map<Material, Long> limits = new EnumMap<>(Material.class);
	private final Set<Material> blocked = EnumSet.noneOf(Material.class);
	private final Set<Material> allowed = EnumSet.noneOf(Material.class);
	private final Map<Material, Set<Short>> blockedData = new EnumMap<>(Material.class);
	private final Set<String> ignoredWorlds = new HashSet<>();

	public ConfigManager(Plugin plugin) {
		ConfigurationSection section = plugin.getConfig().getConfigurationSection("Config");
		tag = section.getString("tag").replace("&", "§");
		format = section.getString("displayname_format").replace("&", "§");
		nameActive = section.getBoolean("displayname_active");
		nameAlways = section.getBoolean("displayname_always");
		timerActive = section.getBoolean("despawn_timer_active", false);
		timeFormat = section.getString("despawn_time_format", "{mm}:{ss}");
		timerSuffix = section.getString("despawn_timer_suffix", " &7({time})").replace("&", "§");
		minShown = Math.max(1, section.getInt("displayname_min_amount"));
		sound = section.getBoolean("playsound");
		debug = section.getBoolean("debug");
		mergeOnSpawn = section.getBoolean("merge_on_spawn");
		custom = section.getBoolean("stack_custom_items");
		unstackable = section.getBoolean("stack_unstackable_items");
		distance = Math.max(1, Math.min(32, section.getInt("stack_distance")));
		viewRange = Math.max(8, Math.min(128, section.getInt("view_range")));
		visualTicks = Math.max(1, section.getInt("visual_interval"));
		budget = Math.max(16, section.getInt("budget"));
		queueLimit = Math.max(64, section.getInt("queue_limit"));
		rescan = Math.max(0, section.getInt("rescan_seconds"));
		save = Math.max(1, section.getInt("save_seconds"));
		mergeDelay = Math.max(50, section.getLong("delayCheckItems"));
		slice = Math.max(1, section.getLong("slice_ms")) * 1000000L;
		defaultLimit = Math.max(1, API.clamp(section.getLong("stack_size")));

		for (String world : section.getStringList("ignored_worlds")) ignoredWorlds.add(world.trim().toLowerCase(Locale.ROOT));

		ConfigurationSection perMaterial = section.getConfigurationSection("stack_limits");
		if (perMaterial != null) {
			for (String name : perMaterial.getKeys(false)) {
				Material material = Material.matchMaterial(name);
				long value = perMaterial.getLong(name);
				if (material == null || value < 1) plugin.getLogger().warning("Invalid stack_limits entry: " + name);
				else limits.put(material, API.clamp(value));
			}
		}

		for (String entry : section.getStringList("whitelist")) {
			Material material = Material.matchMaterial(entry.trim());
			if (material == null) plugin.getLogger().warning("Invalid whitelist entry: " + entry);
			else allowed.add(material);
		}

		for (String entry : section.getStringList("blacklist")) {
			String[] parts = entry.trim().split(">", -1);
			Material material = parts[0].trim().isEmpty() ? null : Material.matchMaterial(parts[0].trim());
			if (material == null) {
				plugin.getLogger().warning("Could not convert the item to Material ID: " + entry + " blacklist!");
				continue;
			}
			if (parts.length == 1) {
				blocked.add(material);
				continue;
			}
			try {
				Set<Short> data = blockedData.get(material);
				if (data == null) blockedData.put(material, data = new HashSet<>());
				data.add(Short.parseShort(parts[1].trim()));
			} catch (NumberFormatException e) {
				plugin.getLogger().warning("Invalid data value in blacklist entry: " + entry);
			}
		}
	}

	public long limit(Material material) {
		Long value = limits.get(material);
		return value == null ? defaultLimit : value;
	}

	public boolean blocks(ItemStack stack) {
		Material type = stack.getType();
		if (blocked.contains(type) || !allowed.isEmpty() && !allowed.contains(type)) return true;
		Set<Short> data = blockedData.get(type);
		return data != null && data.contains(stack.getDurability());
	}

	public boolean ignores(World world) {
		return !ignoredWorlds.isEmpty() && ignoredWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
	}
}
