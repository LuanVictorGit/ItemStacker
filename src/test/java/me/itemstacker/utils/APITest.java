package me.itemstacker.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class APITest {

	@Test
	void physicalAmountNeverExceedsTheCap() {
		assertEquals(0, API.physical(0, 64));
		assertEquals(1, API.physical(1, 64));
		assertEquals(64, API.physical(64, 64));
		assertEquals(64, API.physical(65, 64));
		assertEquals(64, API.physical(127, 64));
		assertEquals(64, API.physical(32767, 64));
		assertEquals(99, API.physical(Long.MAX_VALUE, 99));
		assertEquals(0, API.physical(-5, 64));
	}

	@Test
	void splitBetweenPhysicalAndHiddenKeepsTheTotal() {
		long[] totals = { 1, 63, 64, 65, 127, 128, 2304, 32767, 32768, 100000, 2147483647L, 2147483648L, API.MAX_AMOUNT };
		int[] caps = { 1, 16, 64, 99 };
		for (long total : totals) {
			for (int cap : caps) {
				int physical = API.physical(total, cap);
				long hidden = total - physical;
				assertEquals(total, physical + hidden);
				assertTrue(physical >= 1 && physical <= cap);
				assertTrue(hidden >= 0);
			}
		}
	}

	@Test
	void roomIsNeverNegative() {
		assertEquals(90, API.room(10, 100));
		assertEquals(0, API.room(100, 100));
		assertEquals(0, API.room(150, 100));
		assertEquals(API.MAX_AMOUNT, API.room(0, API.MAX_AMOUNT));
	}

	@Test
	void sumIsSaturatedInsteadOfOverflowing() {
		assertEquals(30, API.sum(10, 20));
		assertEquals(API.MAX_AMOUNT, API.sum(API.MAX_AMOUNT, API.MAX_AMOUNT));
		assertEquals(API.MAX_AMOUNT, API.sum(API.MAX_AMOUNT, 1));
		assertThrows(ArithmeticException.class, () -> API.sum(Long.MAX_VALUE, 1));
	}

	@Test
	void clampKeepsAmountsInRange() {
		assertEquals(0, API.clamp(-1));
		assertEquals(5, API.clamp(5));
		assertEquals(API.MAX_AMOUNT, API.clamp(Long.MAX_VALUE));
	}

	@Test
	void chunkKeysAreUniqueAndOrderedByCoordinates() {
		assertNotEquals(API.key(1, 2), API.key(2, 1));
		assertNotEquals(API.key(-1, -1), API.key(1, 1));
		assertNotEquals(API.key(0, -1), API.key(-1, 0));
		assertEquals(API.key(-30000000, 29999999), API.key(-30000000, 29999999));
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) assertTrue(seen.add(API.key(x, z)));
	}

	@Test
	void compactAmounts() {
		assertEquals("0", API.compact(0));
		assertEquals("999", API.compact(999));
		assertEquals("1K", API.compact(1000));
		assertEquals("1.2K", API.compact(1234));
		assertEquals("15K", API.compact(15000));
		assertEquals("100K", API.compact(100000));
		assertEquals("999K", API.compact(999999));
		assertEquals("1.5M", API.compact(1500000));
		assertEquals("2.1B", API.compact(2147483647L));
		assertEquals("1T", API.compact(1000000000000L));
		assertEquals("2305Q", API.compact(API.MAX_AMOUNT));
	}

	@Test
	void prettyMaterialNames() {
		assertEquals("Diamond Sword", API.pretty(Material.DIAMOND_SWORD));
		assertEquals("Stone", API.pretty(Material.STONE));
		assertEquals("Iron Ingot", API.pretty(Material.IRON_INGOT));
	}

	@Test
	void formatReplacesEveryPlaceholder() {
		assertEquals("§bStone §a1234567x (1.2M)", API.format("§b{displayname} §a{amount}x ({short})", "Stone", 1234567));
		assertEquals("a a", API.format("{displayname} {displayname}", "a", 1));
	}

	@Test
	void fillTopsUpPartialStacksBeforeUsingEmptySlots() {
		int[] amounts = { 10, 0, 60, 5, 0 };
		boolean[] same = { true, false, true, false, false };
		int moved = API.fill(amounts, same, 64, 100);
		assertEquals(100, moved);
		assertArrayEquals(new int[] { 64, 42, 64, 5, 0 }, amounts);
	}

	@Test
	void fillStopsWhenTheInventoryIsFull() {
		int[] amounts = { 64, 64, 12, 64 };
		boolean[] same = { true, true, false, true };
		int moved = API.fill(amounts, same, 64, 500);
		assertEquals(0, moved);
		assertArrayEquals(new int[] { 64, 64, 12, 64 }, amounts);
	}

	@Test
	void fillReportsOnlyWhatFitted() {
		int[] amounts = new int[36];
		boolean[] same = new boolean[36];
		assertEquals(2304, API.fill(amounts, same, 64, 5000));
		for (int amount : amounts) assertEquals(64, amount);
	}

	@Test
	void fillHandlesUnstackableItems() {
		int[] amounts = new int[4];
		boolean[] same = new boolean[4];
		assertEquals(4, API.fill(amounts, same, 1, 100));
		assertArrayEquals(new int[] { 1, 1, 1, 1 }, amounts);
		assertEquals(0, API.fill(amounts, same, 1, 100));
	}

	@Test
	void fillNeverInventsOrLosesItems() {
		Random random = new Random(42);
		for (int round = 0; round < 5000; round++) {
			int slots = 1 + random.nextInt(40);
			int max = 1 + random.nextInt(99);
			int[] amounts = new int[slots];
			boolean[] same = new boolean[slots];
			boolean[] foreign = new boolean[slots];
			long capacity = 0, before = 0;
			for (int i = 0; i < slots; i++) {
				int kind = random.nextInt(3);
				if (kind == 1) {
					amounts[i] = 1 + random.nextInt(max);
					same[i] = true;
					capacity += max - amounts[i];
				} else if (kind == 2) {
					amounts[i] = 1 + random.nextInt(max);
					foreign[i] = true;
				} else {
					capacity += max;
				}
				before += amounts[i];
			}
			int[] original = amounts.clone();
			int want = random.nextInt(4000);
			int moved = API.fill(amounts, same, max, want);
			long after = 0;
			for (int i = 0; i < slots; i++) {
				after += amounts[i];
				if (foreign[i]) assertEquals(original[i], amounts[i]);
				else assertTrue(amounts[i] <= max);
			}
			assertEquals(Math.min(want, capacity), moved);
			assertEquals(before + moved, after);
		}
	}

	@Test
	void formatTimeOutputsExpectedMinuteAndSecondCombinations() {
		assertEquals("00:00", API.formatTime("{mm}:{ss}", 0));
		assertEquals("00:05", API.formatTime("{mm}:{ss}", 5));
		assertEquals("01:05", API.formatTime("{mm}:{ss}", 65));
		assertEquals("05:00", API.formatTime("{mm}:{ss}", 300));
		assertEquals("60:05", API.formatTime("{mm}:{ss}", 3605));
		assertEquals("1m 5s", API.formatTime("{m}m {s}s", 65));
		assertEquals("65s", API.formatTime("{total_seconds}s", 65));
		assertEquals("00:00", API.formatTime("{mm}:{ss}", -10));
	}

	@Test
	void formatWithDespawnTimerEnabledReplacesPlaceholders() {
		String res1 = API.format("§b{displayname} §a{amount}x §7[{time}]", "Diamond", 64, 245, true, "{mm}:{ss}", null);
		assertEquals("§bDiamond §a64x §7[04:05]", res1);

		String res2 = API.format("{displayname} {amount}x {timer}", "Iron", 10, 65, true, "{m}m {s}s", null);
		assertEquals("Iron 10x 1m 5s", res2);

		String res3 = API.format("{displayname} {amount}x {despawn}", "Gold", 5, 10, true, "{total_seconds}s", null);
		assertEquals("Gold 5x 10s", res3);

		String res4 = API.format("{displayname} %timer%", "Emerald", 1, 30, true, "{ss}", null);
		assertEquals("Emerald 30", res4);
	}

	@Test
	void formatWithDespawnTimerDisabledCleansPlaceholders() {
		String res = API.format("§b{displayname} §a{amount}x §7[{time}]", "Diamond", 64, 245, false, "{mm}:{ss}", null);
		assertEquals("§bDiamond §a64x §7", res);

		String res2 = API.format("{displayname} {amount}x {timer}", "Iron", 10, 65, false, "{mm}:{ss}", null);
		assertEquals("Iron 10x", res2);
	}

	@Test
	void formatWithTimerActiveAppendsSuffixWhenPlaceholderAbsent() {
		String res = API.format("§b{displayname} §a{amount}x", "Stone", 100, 300, true, "{mm}:{ss}", " §7({time})");
		assertEquals("§bStone §a100x §7(05:00)", res);
	}
}
