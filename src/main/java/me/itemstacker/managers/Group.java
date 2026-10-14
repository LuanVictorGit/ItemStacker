package me.itemstacker.managers;

import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.itemstacker.utils.API;

public final class Group {

	public static final class View {

		public final long total, seconds;
		public final Object name;

		public View(long total, Object name) {
			this(total, -1, name);
		}

		public View(long total, long seconds, Object name) {
			this.total = total;
			this.seconds = seconds;
			this.name = name;
		}
	}

	public final Item item;
	public final int id;
	public final UUID uuid;
	public final Material type;
	public final short data;
	public final int hash;
	public final String label;
	public final boolean mergeable;

	Manager.Bucket bucket;
	double x, y, z;
	long total, shown = -1, shownSeconds = -1, cool;
	boolean dead, dirty;

	public volatile long extra;
	public volatile long lastSeconds;
	public volatile View view;
	public volatile boolean named;

	Group(Item item, ItemStack stack, boolean mergeable) {
		this.item = item;
		this.mergeable = mergeable;
		id = item.getEntityId();
		uuid = item.getUniqueId();
		type = stack.getType();
		data = stack.getDurability();
		ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
		hash = 31 * (31 * type.ordinal() + data) + (meta == null ? 0 : meta.hashCode());
		label = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : API.pretty(type);
	}
}
