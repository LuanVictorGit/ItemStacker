package me.itemstacker.utils.tasks;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;

import me.itemstacker.events.ItemCheckEvent;
import me.itemstacker.utils.Task;

public class TaskCheck extends Task {
	
	public TaskCheck() {
		super(new Runnable() {
			
			private long checkInterval;
			
			@Override
			public void run() {
				
				long currentTime = System.currentTimeMillis();
				if (currentTime-checkInterval < 500) return;
				checkInterval = currentTime;
				
				Task.run(()-> {
					for(World world : Bukkit.getWorlds()) {
						for(Item item : world.getEntitiesByClass(Item.class)) {
							if (item.isDead() || !item.isValid()) continue;
							ItemCheckEvent event = new ItemCheckEvent(item);
							Bukkit.getPluginManager().callEvent(event);
						}
					}
				});
				
			}
			
		});
	}

}
