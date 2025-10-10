package me.itemstacker.events;

import org.bukkit.entity.Item;

import lombok.AllArgsConstructor;
import lombok.Getter;
import me.itemstacker.utils.Listener;

@Getter
@AllArgsConstructor
public class ItemCheckEvent extends Listener {

	private final Item item;
	
}
