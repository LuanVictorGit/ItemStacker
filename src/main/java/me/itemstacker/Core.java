package me.itemstacker;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import lombok.Getter;
import me.itemstacker.commands.ItemStackerCommand;
import me.itemstacker.listeners.GeneralListeners;
import me.itemstacker.managers.Manager;
import me.itemstacker.utils.ConfigManager;
import me.itemstacker.utils.Timer;
import me.itemstacker.utils.tasks.TaskCheck;

@Getter
public class Core extends JavaPlugin {
	
	@Getter
	private static Core instance;
	private String tag, version = "§dv"+getDescription().getVersion();
	private Manager manager;
	private ConfigManager configManager;
	private java.util.Timer task;
	
	@Override
	public void onEnable() {
		instance = this;
		saveDefaultConfig();
		reloadPlugin();
		
		new ItemStackerCommand();
		
		sendConsole(" ");
		sendConsole(tag + " &aItemStacker loaded. &6[Author lHawk_] " + version);
		sendConsole(" ");
	}
	
	@Override
	public void onDisable() {
		HandlerList.unregisterAll(this);
		this.resetManagers();
		sendConsole(" ");
		sendConsole(tag + " &cItemStacker unloaded. &6[Author lHawk_] " + version);
		sendConsole(" ");
	}
	
	public void reloadPlugin() {
		reloadConfig();
		tag = getConfig().getString("Config.tag").replace("&", "§");
		
		resetManagers();
		manager = new Manager();
		configManager = new ConfigManager();
		
		List<Listener> listeners = new ArrayList<>();
		listeners.add(new GeneralListeners());
		listeners.forEach(listener -> Bukkit.getPluginManager().registerEvents(listener, this));
		
		manager.getTasks().add(new TaskCheck());
		
		if (task != null) task.cancel();
		task = new java.util.Timer();
		task.scheduleAtFixedRate(new Timer(), 0, 1);
	}
	
	private void resetManagers() {
		HandlerList.unregisterAll(this);
		if (task != null) task.cancel();
	}
	
	private void sendConsole(String msg) {Bukkit.getConsoleSender().sendMessage(msg.replace("&", "§"));}

}
