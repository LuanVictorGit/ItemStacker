package me.itemstacker;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import com.github.retrooper.packetevents.PacketEvents;

import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.itemstacker.commands.ItemStackerCommand;
import me.itemstacker.listeners.GeneralListeners;
import me.itemstacker.managers.ConfigManager;
import me.itemstacker.managers.Manager;
import me.itemstacker.utils.Platform;
import me.itemstacker.utils.Store;
import me.itemstacker.utils.Visuals;

public class Core extends JavaPlugin {

	private static Core instance;
	private ConfigManager configManager;
	private Manager manager;
	private boolean packets;

	public static Core getInstance() {
		return instance;
	}

	public ConfigManager getConfigManager() {
		return configManager;
	}

	public Manager getManager() {
		return manager;
	}

	public String getTag() {
		return configManager.tag;
	}

	@Override
	public void onLoad() {
		instance = this;
		bootPackets();
	}

	private void bootPackets() {
		packets = false;
		try {
			SpigotPacketEventsBuilder.clearBuildCache();
			PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
			PacketEvents.getAPI().getSettings().checkForUpdates(false).reEncodeByDefault(false);
			PacketEvents.getAPI().load();
			packets = true;
		} catch (Throwable t) {
			getLogger().log(Level.WARNING, "PacketEvents could not be loaded, stack names are disabled", t);
		}
	}

	@Override
	public void onEnable() {
		if (packets && PacketEvents.getAPI().isTerminated()) bootPackets();
		saveDefaultConfig();
		configManager = new ConfigManager(this);
		Platform platform = new Platform(this);
		manager = new Manager(this, configManager, platform, new Store(this, platform));
		if (packets) {
			try {
				PacketEvents.getAPI().init();
				manager.visuals = new Visuals(manager);
			} catch (Throwable t) {
				getLogger().log(Level.WARNING, "PacketEvents could not be started, stack names are disabled", t);
			}
		}
		GeneralListeners listeners = new GeneralListeners(manager);
		Bukkit.getPluginManager().registerEvents(listeners, this);
		listeners.register();
		new ItemStackerCommand();
		manager.start();
		sendConsole(" ");
		sendConsole(getTag() + " &aItemStacker loaded. &6[Author lHawk_] &dv" + getDescription().getVersion() + " &7(" + manager.platform.name + ")");
		sendConsole(" ");
	}

	@Override
	public void onDisable() {
		HandlerList.unregisterAll(this);
		try {
			if (manager != null) manager.stop();
		} finally {
			shutdownPackets();
		}
		if (configManager == null) return;
		sendConsole(" ");
		sendConsole(getTag() + " &cItemStacker unloaded. &6[Author lHawk_] &dv" + getDescription().getVersion());
		sendConsole(" ");
	}

	private void shutdownPackets() {
		try {
			if (manager != null && manager.visuals != null) manager.visuals.close();
			if (packets) PacketEvents.getAPI().terminate();
		} catch (Throwable t) {
			getLogger().log(Level.WARNING, "PacketEvents could not be stopped", t);
		}
	}

	public void reloadPlugin() {
		reloadConfig();
		configManager = new ConfigManager(this);
		manager.reload(configManager);
	}

	private void sendConsole(String message) {
		Bukkit.getConsoleSender().sendMessage(message.replace("&", "§"));
	}
}
