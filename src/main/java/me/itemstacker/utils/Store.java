package me.itemstacker.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;

public final class Store {

	private final Plugin plugin;
	private final Platform platform;
	private final File file, bad;
	private final Map<UUID, Long> records = new ConcurrentHashMap<>();
	private final Object order = new Object();
	private volatile boolean dirty;
	private long issued, written;

	public Store(Plugin plugin, Platform platform) {
		this.plugin = plugin;
		this.platform = platform;
		file = new File(plugin.getDataFolder(), "groups.dat");
		bad = new File(plugin.getDataFolder(), "groups.dat.bad");
		if (platform.pdc || !file.isFile()) return;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())))) {
			for (int i = in.readInt(); i > 0; i--) records.put(new UUID(in.readLong(), in.readLong()), API.clamp(in.readLong()));
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "Could not read groups.dat, it was kept as groups.dat.bad", e);
			try {
				Files.move(file.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
			}
		}
	}

	public long read(Item item) {
		if (platform.pdc) return API.clamp(platform.extra(item));
		Long value = records.get(item.getUniqueId());
		return value == null ? 0 : value;
	}

	public void write(Item item, long extra) {
		if (platform.pdc) {
			platform.extra(item, extra);
			return;
		}
		if (extra > 0) records.put(item.getUniqueId(), extra);
		else records.remove(item.getUniqueId());
		dirty = true;
	}

	public void drop(UUID id) {
		if (!platform.pdc && records.remove(id) != null) dirty = true;
	}

	public int size() {
		return records.size();
	}

	public void flush(boolean blocking) {
		if (platform.pdc || !blocking && !dirty) return;
		long[] data;
		int length = 0;
		long id;
		synchronized (order) {
			dirty = false;
			data = new long[records.size() * 3 + 3];
			for (Map.Entry<UUID, Long> entry : records.entrySet()) {
				if (length + 3 > data.length) data = Arrays.copyOf(data, data.length * 2);
				data[length++] = entry.getKey().getMostSignificantBits();
				data[length++] = entry.getKey().getLeastSignificantBits();
				data[length++] = entry.getValue();
			}
			id = ++issued;
		}
		long[] snapshot = data;
		int count = length;
		if (blocking) write(snapshot, count, id);
		else platform.async(() -> write(snapshot, count, id));
	}

	private synchronized void write(long[] data, int length, long id) {
		if (id < written) return;
		File temp = new File(file.getPath() + ".tmp");
		try {
			plugin.getDataFolder().mkdirs();
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp.toPath())))) {
				out.writeInt(length / 3);
				for (int i = 0; i < length; i++) out.writeLong(data[i]);
			}
			try {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			written = id;
		} catch (IOException e) {
			dirty = true;
			plugin.getLogger().log(Level.WARNING, "Could not write groups.dat", e);
		}
	}
}
