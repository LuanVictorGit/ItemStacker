package me.itemstacker.utils;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import lombok.Getter;
import me.itemstacker.Core;

@Getter
public class ConfigManager {

	private final List<ItemStack> items = new ArrayList<>();
	private final boolean debug;
	
	public ConfigManager() {
		
		FileConfiguration config = Core.getInstance().getConfig();
		ConfigurationSection section = config.getConfigurationSection("Config");
		
		debug = section.getBoolean("debug");
		
		for(String id : section.getStringList("blacklist")) {
			ItemStack item = API.getItemStack(id);
			if (item == null) {
				Bukkit.getConsoleSender().sendMessage(Core.getInstance().getTag() + " §cCould not convert the item to ItemStack ID: §f" + id + " §cblacklist!");
				continue;
			}
			this.items.add(API.getItemStack(id));
		}
		
	}
	
	public static ConfigManager get() {return Core.getInstance().getConfigManager();}
	
}
