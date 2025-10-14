package me.itemstacker.listeners;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import me.itemstacker.Core;
import me.itemstacker.events.ItemCheckEvent;
import me.itemstacker.managers.ConfigManager;
import me.itemstacker.utils.API;

public class GeneralListeners implements Listener {
	
	private final Core plugin = Core.getInstance();
	private final ConfigManager config = ConfigManager.get();
    private final int DISTANCE = config.getStack_distance();
    private final int MAX_STACK_SIZE = config.getStack_size();
    
	@EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        Player p = e.getPlayer();
        Item item = e.getItem();
        ItemStack itemStack = item.getItemStack().clone();

        e.setCancelled(true);
        item.remove();

        ItemStack itemCloned = itemStack.clone();
        itemCloned.setAmount(1);
        
        Location loc = item.getLocation().clone();
        
        for (int i = 0; i < itemStack.getAmount(); i++) {
        	HashMap<Integer, ItemStack> rest = p.getInventory().addItem(itemCloned.clone());

            if (!rest.isEmpty()) {
                for (ItemStack remaining : rest.values()) {
                    p.getWorld().dropItemNaturally(loc, remaining);
                }
            }
		}
        
        if (config.isDebug()) {
        	Bukkit.getConsoleSender().sendMessage(plugin.getTag() + " §7Stacked item in X:" + loc.getBlockX() + " Y:"+loc.getBlockY() + " Z:"+loc.getBlockZ() + "! [DEBUG]");
        }

        p.updateInventory();
        playSound(p);
    }
	
	@EventHandler
	public void onSpawn(EntitySpawnEvent e) {
		if (!(e.getEntity() instanceof Item)) return;
		Item item = (Item) e.getEntity();
		Bukkit.getScheduler().runTask(Core.getInstance(), ()-> {
			if (item.isDead() || !item.isValid()) return;
			ItemCheckEvent event = new ItemCheckEvent(item);
			Bukkit.getPluginManager().callEvent(event);
		});
	}
	
	private Sound originalSound;
	private void playSound(Player p) {
		if (!config.isPlaysound()) return;
		if (originalSound != null) {
			playSoundToPlayers(p);
			return;
		}
		List<String> sounds = Arrays.asList("ENTITY_ITEM_PICKUP", "ENTITY.ITEM.PICKUP", "ITEM_PICKUP", "ITEM.PICKUP");
		for(Sound sound : Sound.values()) {
			if (sounds.contains(sound.name().toUpperCase())) {
				originalSound = sound;
				playSoundToPlayers(p); 
				break;
			}
		}
    }
	
	private void playSoundToPlayers(Player p) {
		if (originalSound == null) {
			playSound(p);
			return;
		}
		for(Player viewer : Bukkit.getOnlinePlayers()) {
			if (!viewer.getWorld().equals(p.getWorld())) continue;
			if (viewer.getLocation().distanceSquared(p.getLocation()) > (10*10)) continue;
			viewer.playSound(p.getLocation(), originalSound, 0.5f, 10);
		}
	}
    
	private void checkItem(Item item) {
        ItemStack itemStack = item.getItemStack();

        if (itemStack == null || itemStack.getAmount() <= 0) return;

        for(ItemStack itemBlacklist : config.getItems()) {
        	if (API.isSameTypeAndData(itemStack, itemBlacklist)) return;
        }
        
        List<Item> otherItems = item.getNearbyEntities(DISTANCE, DISTANCE, DISTANCE)
        	    .stream()
        	    .filter(e -> e instanceof Item)
        	    .map(e -> (Item) e)
        	    .collect(Collectors.toList());
        
        for (Item otherItem : otherItems) {
            if (otherItem.equals(item)) continue;
            if (otherItem.isDead() || !otherItem.isValid()) continue;

            ItemStack otherStack = otherItem.getItemStack();
            if (otherStack == null || otherStack.getAmount() <= 0) continue;

            Location loc = otherItem.getLocation();
            if (loc.distanceSquared(item.getLocation()) > (DISTANCE * DISTANCE)) continue;

            if (!otherStack.isSimilar(itemStack)) continue;

            int totalAmount = otherStack.getAmount() + itemStack.getAmount();

            if (totalAmount > MAX_STACK_SIZE) {
                otherStack.setAmount(MAX_STACK_SIZE);
                itemStack.setAmount(totalAmount - MAX_STACK_SIZE);
                updateItemName(otherItem);
                updateItemName(item);
            } else {
                otherStack.setAmount(totalAmount);
                updateItemName(otherItem);
                item.remove();
            }
            return;
        }

        updateItemName(item);
	}
	
    @EventHandler
    public void onCheck(ItemCheckEvent e) {
        checkItem(e.getItem());
    }

    private void updateItemName(Item item) {
    	if (!config.isDisplayname_active()) return;
        ItemStack stack = item.getItemStack();
        if (stack == null) return;

        int amount = stack.getAmount();
        String materialName = stack.getType().name().toLowerCase().replace("_", " ");
        
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
        	materialName = stack.getItemMeta().getDisplayName();
        }
        
        if (config.isDisplayname_always()) {
        	item.setCustomNameVisible(true);
        }
        
        item.setCustomName(config.getDisplayname_format()
        		.replace("{amount}", String.valueOf(amount))
        		.replace("{displayname}", materialName));

        if (amount == 1) {
            item.setCustomNameVisible(false);
        }
    }
}
