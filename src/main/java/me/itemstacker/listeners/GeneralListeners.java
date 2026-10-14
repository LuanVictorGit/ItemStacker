package me.itemstacker.listeners;

import java.util.Arrays;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import me.itemstacker.managers.Manager;
import me.itemstacker.utils.Platform;

public class GeneralListeners implements Listener {

	private final Manager manager;
	private final Platform platform;

	public GeneralListeners(Manager manager) {
		this.manager = manager;
		this.platform = manager.platform;
	}

	public void register() {
		if (platform.entityPickup) {
			platform.listen(this, "org.bukkit.event.entity.EntityPickupItemEvent", EventPriority.HIGHEST, e -> {
				org.bukkit.entity.Entity picker = ((EntityEvent) e).getEntity();
				manager.pickup((Cancellable) e, picker instanceof Player ? (Player) picker : null, platform.pickedItem(e), platform.remaining(e));
			});
		} else {
			platform.listen(this, "org.bukkit.event.player.PlayerPickupItemEvent", EventPriority.HIGHEST, e -> {
				PlayerPickupItemEvent pickup = (PlayerPickupItemEvent) e;
				manager.pickup(pickup, pickup.getPlayer(), pickup.getItem(), pickup.getRemaining());
			});
		}
		platform.listen(this, "org.bukkit.event.entity.EntityRemoveEvent", EventPriority.MONITOR, e -> {
			org.bukkit.entity.Entity removed = ((EntityEvent) e).getEntity();
			if (removed instanceof Item && !platform.causeOf(e).equals("UNLOAD")) manager.despawn((Item) removed);
		});
		if (platform.entityStorage) {
			platform.listen(this, "org.bukkit.event.world.EntitiesLoadEvent", EventPriority.MONITOR, e -> manager.load(platform.entitiesOf(e), false));
			platform.listen(this, "org.bukkit.event.world.EntitiesUnloadEvent", EventPriority.MONITOR, e -> manager.unload(platform.entitiesOf(e)));
		} else {
			platform.listen(this, "org.bukkit.event.world.ChunkLoadEvent", EventPriority.MONITOR, e -> manager.load(Arrays.asList(((ChunkLoadEvent) e).getChunk().getEntities()), false));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onSpawn(ItemSpawnEvent e) {
		if (manager.absorb(e.getEntity())) e.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onMerge(ItemMergeEvent e) {
		manager.vanillaMerge(e);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onHopper(InventoryPickupItemEvent e) {
		manager.suck(e);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDespawn(ItemDespawnEvent e) {
		manager.despawn(e.getEntity());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onChunkUnload(ChunkUnloadEvent e) {
		manager.unload(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onWorldUnload(WorldUnloadEvent e) {
		manager.unload(e.getWorld());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onSave(WorldSaveEvent e) {
		manager.save();
	}
}
