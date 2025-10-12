package me.itemstacker.managers;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import lombok.Getter;
import me.itemstacker.Core;
import me.itemstacker.utils.API;

@Getter
public class ConfigManager {

	private final List<ItemStack> items = new ArrayList<>();
	private final String displayname_format;
	private final long delayCheckItems;
	private final int stack_size;
	private final int stack_distance;
	private final boolean debug;
	private final boolean playsound;
	private final boolean displayname_active;
	private final boolean displayname_always;
	
	public ConfigManager() {
		
		FileConfiguration config = Core.getInstance().getConfig();
		ConfigurationSection section = config.getConfigurationSection("Config");
		
		playsound = section.getBoolean("playsound");
		stack_distance = section.getInt("stack_distance");
		stack_size = section.getInt("stack_size");
		delayCheckItems = section.getLong("delayCheckItems");
		displayname_format = section.getString("displayname_format");
		displayname_always = section.getBoolean("displayname_always");
		displayname_active = section.getBoolean("displayname_active");
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
