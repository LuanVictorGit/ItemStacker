package me.itemstacker.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import io.github.retrooper.packetevents.netty.NettyManagerImpl;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;

class VisualsTest {

	private static ServerVersion current = ServerVersion.V_1_8_8;

	@BeforeAll
	static void api() {
		PacketEvents.setAPI(new PacketEventsAPI<Object>() {
			private final NettyManager netty = new NettyManagerImpl();

			@Override
			public boolean isLoaded() {
				return true;
			}

			@Override
			public void init() {}

			@Override
			public boolean isInitialized() {
				return true;
			}

			@Override
			public boolean isTerminated() {
				return false;
			}

			@Override
			public Object getPlugin() {
				return null;
			}

			@Override
			public ServerManager getServerManager() {
				return () -> current;
			}

			@Override
			public ProtocolManager getProtocolManager() {
				return null;
			}

			@Override
			public PlayerManager getPlayerManager() {
				return null;
			}

			@Override
			public NettyManager getNettyManager() {
				return netty;
			}

			@Override
			public ChannelInjector getInjector() {
				return null;
			}
		});
	}

	private List<EntityData<?>> roundTrip(ServerVersion version, Object name, boolean visible) {
		current = version;
		WrapperPlayServerEntityMetadata written = new WrapperPlayServerEntityMetadata(321, Visuals.entries(version, name, visible));
		written.setBuffer(Unpooled.buffer());
		written.write();
		WrapperPlayServerEntityMetadata read = new WrapperPlayServerEntityMetadata(0, new ArrayList<>());
		read.setBuffer(written.getBuffer());
		read.read();
		assertEquals(321, read.getEntityId());
		return read.getEntityMetadata();
	}

	@Test
	void everySupportedVersionEncodesAndDecodesTheNameEntries() {
		int checked = 0;
		for (ServerVersion version : ServerVersion.values()) {
			if (!version.isNewerThanOrEquals(ServerVersion.V_1_8) || !version.name().startsWith("V_")) continue;
			current = version;
			List<EntityData<?>> entries = roundTrip(version, Visuals.name(version, "§bStone §a1234x"), true);
			assertEquals(2, entries.size(), version.name());
			assertEquals(2, entries.get(0).getIndex(), version.name());
			assertEquals(3, entries.get(1).getIndex(), version.name());
			Object value = entries.get(0).getValue();
			if (version.isOlderThan(ServerVersion.V_1_13)) {
				assertEquals("§bStone §a1234x", value, version.name());
			} else {
				Optional<?> name = (Optional<?>) value;
				assertTrue(name.isPresent(), version.name());
				assertEquals("§bStone §a1234x", AdventureSerializer.serializer().asLegacy((Component) name.get()), version.name());
			}
			Object flag = entries.get(1).getValue();
			assertEquals(version.isOlderThan(ServerVersion.V_1_9) ? (Object) (byte) 1 : (Object) Boolean.TRUE, flag, version.name());
			checked++;
		}
		assertTrue(checked > 40, "versions checked: " + checked);
	}

	@Test
	void clearingEntriesEncodeAnEmptyNameAndAHiddenFlag() {
		for (ServerVersion version : new ServerVersion[] { ServerVersion.V_1_8_8, ServerVersion.V_1_12_2, ServerVersion.V_1_13, ServerVersion.V_1_16_5, ServerVersion.V_1_21_11, ServerVersion.V_26_3 }) {
			List<EntityData<?>> entries = roundTrip(version, Visuals.none(version), false);
			Object value = entries.get(0).getValue();
			if (version.isOlderThan(ServerVersion.V_1_13)) assertEquals("", value);
			else assertFalse(((Optional<?>) value).isPresent());
			assertEquals(version.isOlderThan(ServerVersion.V_1_9) ? (Object) (byte) 0 : (Object) Boolean.FALSE, entries.get(1).getValue());
		}
	}

	@Test
	void legacyNamesAreTruncatedWithoutDanglingColorCodes() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 31; i++) text.append("ab");
		text.append('§').append('c').append("overflow");
		String name = (String) Visuals.name(ServerVersion.V_1_8_8, text.toString());
		assertTrue(name.length() <= 64);
		assertFalse(name.endsWith("§"));
		assertEquals(text.substring(0, 40), Visuals.name(ServerVersion.V_1_8_8, text.substring(0, 40)));
	}

	@Test
	void miniMessageParsingOnModernVersions() {
		Object modern = Visuals.name(ServerVersion.V_1_21_11, "<green>Diamond</green> <gold>64x</gold>");
		assertTrue(modern instanceof Optional);
		assertTrue(((Optional<?>) modern).isPresent());
		List<EntityData<?>> entries = roundTrip(ServerVersion.V_1_21_11, modern, true);
		assertEquals(2, entries.size());

		Object grad = Visuals.name(ServerVersion.V_26_3, "<gradient:#00d2ff:#3a7bd5>Netherite Ingot</gradient> <yellow>10x</yellow>");
		assertTrue(grad instanceof Optional);
		assertTrue(((Optional<?>) grad).isPresent());
	}

	@Test
	void miniMessageDownsamplingOnLegacyVersions() {
		Object legacy = Visuals.name(ServerVersion.V_1_8_8, "<green>Diamond</green> <gold>64x</gold>");
		assertTrue(legacy instanceof String);
		assertTrue(((String) legacy).contains("Diamond"));
		assertTrue(((String) legacy).contains("64x"));
	}

	@Test
	void hexColorCodesParsing() {
		Object modernHex = Visuals.name(ServerVersion.V_1_16_5, "&#00d2ffCobblestone &#3a7bd5100x");
		assertTrue(modernHex instanceof Optional);
		assertTrue(((Optional<?>) modernHex).isPresent());

		Object legacyHex = Visuals.name(ServerVersion.V_1_8_8, "&#00d2ffCobblestone &#3a7bd5100x");
		assertTrue(legacyHex instanceof String);
		assertTrue(((String) legacyHex).contains("Cobblestone"));
	}
}
