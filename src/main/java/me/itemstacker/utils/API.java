package me.itemstacker.utils;

import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class API {

	public static final long MAX_AMOUNT = Long.MAX_VALUE / 4;

	private API() {}

	public static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static int physical(long total, int cap) {
		return (int) Math.max(0, Math.min(total, cap));
	}

	public static long room(long total, long limit) {
		return Math.max(0, limit - total);
	}

	public static long sum(long a, long b) {
		return Math.min(MAX_AMOUNT, Math.addExact(a, b));
	}

	public static long clamp(long value) {
		return Math.max(0, Math.min(MAX_AMOUNT, value));
	}

	public static String pretty(Material type) {
		StringBuilder out = new StringBuilder();
		for (String word : type.name().split("_")) {
			if (word.isEmpty()) continue;
			if (out.length() > 0) out.append(' ');
			out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	public static String compact(long n) {
		if (n < 1000) return Long.toString(n);
		String units = "KMBTQ";
		int unit = -1;
		long whole = n, tenth = 0;
		while (whole >= 1000 && unit < units.length() - 1) {
			tenth = whole % 1000 / 100;
			whole /= 1000;
			unit++;
		}
		return whole + (whole < 100 && tenth > 0 ? "." + tenth : "") + units.charAt(unit);
	}

	public static String formatTime(String pattern, long remainingSeconds) {
		long total = Math.max(0, remainingSeconds);
		long m = total / 60;
		long s = total % 60;
		String mm = m < 10 ? "0" + m : Long.toString(m);
		String ss = s < 10 ? "0" + s : Long.toString(s);
		return pattern
				.replace("{mm}", mm)
				.replace("{m}", Long.toString(m))
				.replace("{ss}", ss)
				.replace("{s}", Long.toString(s))
				.replace("{total_seconds}", Long.toString(total));
	}

	public static String format(String pattern, String label, long amount) {
		return format(pattern, label, amount, 0, false, "{mm}:{ss}", null);
	}

	public static String format(String pattern, String label, long amount, long remainingSeconds, boolean timerActive, String timePattern, String timerSuffix) {
		String text = pattern.replace("{displayname}", label).replace("{amount}", Long.toString(amount)).replace("{short}", compact(amount));
		boolean hasPlaceholder = text.contains("{time}") || text.contains("{timer}") || text.contains("{despawn}") || text.contains("{despawn_time}") || text.contains("{time_left}") || text.contains("%timer%");
		if (timerActive) {
			String timeStr = formatTime(timePattern == null ? "{mm}:{ss}" : timePattern, remainingSeconds);
			if (hasPlaceholder) {
				text = text.replace("{time}", timeStr)
						.replace("{timer}", timeStr)
						.replace("{despawn}", timeStr)
						.replace("{despawn_time}", timeStr)
						.replace("{time_left}", timeStr)
						.replace("%timer%", timeStr);
			} else if (timerSuffix != null && !timerSuffix.isEmpty()) {
				text = text + timerSuffix.replace("{time}", timeStr).replace("{timer}", timeStr).replace("{despawn}", timeStr);
			}
		} else {
			if (hasPlaceholder) {
				text = text.replace("{time}", "")
						.replace("{timer}", "")
						.replace("{despawn}", "")
						.replace("{despawn_time}", "")
						.replace("{time_left}", "")
						.replace("%timer%", "")
						.replace("[]", "")
						.replace("()", "")
						.trim();
			}
		}
		return text;
	}

	public static int fill(int[] amounts, boolean[] same, int max, int want) {
		int left = want;
		for (int i = 0; i < amounts.length && left > 0; i++) {
			if (!same[i] || amounts[i] >= max) continue;
			int add = Math.min(max - amounts[i], left);
			amounts[i] += add;
			left -= add;
		}
		for (int i = 0; i < amounts.length && left > 0; i++) {
			if (amounts[i] != 0) continue;
			int add = Math.min(max, left);
			amounts[i] = add;
			same[i] = true;
			left -= add;
		}
		return want - left;
	}

	public static int insert(Inventory inventory, int slots, ItemStack proto, int want) {
		int max = Math.max(1, Math.min(proto.getMaxStackSize(), inventory.getMaxStackSize()));
		int[] amounts = new int[slots];
		boolean[] same = new boolean[slots];
		for (int i = 0; i < slots; i++) {
			ItemStack current = inventory.getItem(i);
			if (current == null || current.getType() == Material.AIR || current.getAmount() <= 0) continue;
			amounts[i] = current.getAmount();
			same[i] = current.isSimilar(proto);
		}
		int[] before = amounts.clone();
		int moved = fill(amounts, same, max, want);
		for (int i = 0; i < slots; i++) {
			if (amounts[i] == before[i]) continue;
			ItemStack stack = proto.clone();
			stack.setAmount(amounts[i]);
			inventory.setItem(i, stack);
		}
		return moved;
	}
}
