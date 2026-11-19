package me.itemstacker.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import me.itemstacker.managers.ConfigManager;
import me.itemstacker.managers.Group;
import me.itemstacker.managers.Manager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Visuals extends PacketListenerAbstract {

	private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");
	private static final Pattern BUKKIT_HEX_PATTERN = Pattern.compile("[§&][xX]([§&][0-9a-fA-F]){6}");
	private static final Pattern LEGACY_PATTERN = Pattern.compile("[§&]([0-9a-fk-orA-FK-OR])");
	private static final java.util.Map<Character, String> LEGACY_TAGS = new java.util.HashMap<>();
	static {
		String codes = "0123456789abcdefklmnor";
		String[] tags = { "<black>", "<dark_blue>", "<dark_green>", "<dark_aqua>", "<dark_red>", "<dark_purple>", "<gold>", "<gray>",
				"<dark_gray>", "<blue>", "<green>", "<aqua>", "<red>", "<light_purple>", "<yellow>", "<white>",
				"<obfuscated>", "<bold>", "<strikethrough>", "<underlined>", "<italic>", "<reset>" };
		for (int i = 0; i < codes.length(); i++) LEGACY_TAGS.put(codes.charAt(i), tags[i]);
	}

	private final Manager manager;
	private final ServerVersion version;

	public Visuals(Manager manager) {
		this.manager = manager;
		version = PacketEvents.getAPI().getServerManager().getVersion();
		PacketEvents.getAPI().getEventManager().registerListener(this);
	}

	public void close() {
		PacketEvents.getAPI().getEventManager().unregisterListener(this);
	}

	static String prepareMiniMessage(String text) {
		// &#RRGGBB e o formato hex do Bukkit (§x§R§R§G§G§B§B, que getDisplayName() devolve para nomes em degradê)
		Matcher bukkitHex = BUKKIT_HEX_PATTERN.matcher(text);
		StringBuffer hex = new StringBuffer();
		while (bukkitHex.find()) {
			String digits = bukkitHex.group().replaceAll("[§&xX]", "");
			bukkitHex.appendReplacement(hex, "<#" + digits + ">");
		}
		bukkitHex.appendTail(hex);
		Matcher matcher = HEX_PATTERN.matcher(hex.toString());
		StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			matcher.appendReplacement(sb, "<#" + matcher.group(1) + ">");
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	static String toMiniMessageTags(String text) {
		String s = prepareMiniMessage(text);
		if (!s.contains("&") && !s.contains("§")) return s;
		Matcher matcher = LEGACY_PATTERN.matcher(s);
		StringBuffer sb = new StringBuffer();
		while (matcher.find()) {
			String tag = LEGACY_TAGS.get(Character.toLowerCase(matcher.group(1).charAt(0)));
			matcher.appendReplacement(sb, Matcher.quoteReplacement(tag == null ? matcher.group() : tag));
		}
		matcher.appendTail(sb);
		return sb.toString().replace("§", "");
	}

	static Object name(ServerVersion version, String text) {
		if (text == null || text.isEmpty()) return none(version);
		if (!text.contains("<") && !text.contains("&#")) {
			String legacy = text.replace('&', '§');
			if (version.isOlderThan(ServerVersion.V_1_13)) return legacy.length() > 64 ? legacy.substring(0, legacy.charAt(63) == '§' ? 63 : 64) : legacy;
			return Optional.of(AdventureSerializer.serializer().fromLegacy(legacy));
		}
		try {
			String mm = toMiniMessageTags(text);
			Component component = MiniMessage.miniMessage().deserialize(mm);
			if (version.isOlderThan(ServerVersion.V_1_13)) {
				String legacy = AdventureSerializer.serializer().asLegacy(component);
				return legacy.length() > 64 ? legacy.substring(0, legacy.charAt(63) == '§' ? 63 : 64) : legacy;
			}
			return Optional.of(component);
		} catch (Throwable t) {
			String legacy = text.replace('&', '§');
			if (version.isOlderThan(ServerVersion.V_1_13)) return legacy.length() > 64 ? legacy.substring(0, legacy.charAt(63) == '§' ? 63 : 64) : legacy;
			return Optional.of(AdventureSerializer.serializer().fromLegacy(legacy));
		}
	}

	static Object none(ServerVersion version) {
		return version.isOlderThan(ServerVersion.V_1_13) ? "" : Optional.empty();
	}

	@SuppressWarnings("unchecked")
	static List<EntityData<?>> entries(ServerVersion version, Object name, boolean visible) {
		List<EntityData<?>> list = new ArrayList<>(2);
		if (version.isOlderThan(ServerVersion.V_1_13)) list.add(new EntityData<>(2, EntityDataTypes.STRING, (String) name));
		else list.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, (Optional<Component>) name));
		if (version.isOlderThan(ServerVersion.V_1_9)) list.add(new EntityData<>(3, EntityDataTypes.BYTE, (byte) (visible ? 1 : 0)));
		else list.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, visible));
		return list;
	}

	private Object view(Group group, long total) {
		ConfigManager cfg = manager.cfg;
		long seconds = cfg.timerActive ? group.lastSeconds : -1;
		Group.View view = group.view;
		if (view == null || view.total != total || view.seconds != seconds) {
			String formatted = API.format(cfg.format, group.label, total, seconds, cfg.timerActive, cfg.timeFormat, cfg.timerSuffix);
			group.view = view = new Group.View(total, seconds, name(version, formatted));
		}
		return view.name;
	}

	private static int amountOf(Object value) {
		Object stack = value instanceof Optional ? ((Optional<?>) value).orElse(null) : value;
		return stack instanceof ItemStack ? ((ItemStack) stack).getAmount() : -1;
	}

	@Override
	public void onPacketSend(PacketSendEvent event) {
		if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) return;
		Object buffer = event.getByteBuf();
		int start = ByteBufHelper.readerIndex(buffer);
		int id = ByteBufHelper.readVarInt(buffer);
		ByteBufHelper.readerIndex(buffer, start);
		Group group = manager.group(id);
		ConfigManager config = manager.cfg;
		if (group == null || !config.nameActive && !group.named) return;
		WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
		List<EntityData<?>> list = new ArrayList<>(packet.getEntityMetadata().size() + 2);
		int amount = -1;
		for (EntityData<?> data : packet.getEntityMetadata()) {
			if (data.getIndex() == 2 || data.getIndex() == 3) continue;
			list.add(data);
			amount = Math.max(amount, amountOf(data.getValue()));
		}
		Group.View known = group.view;
		long total = amount >= 0 ? amount + group.extra : known == null ? -1 : known.total;
		if (total < 0) return;
		boolean show = config.nameActive && total >= config.minShown;
		if (!show && !group.named) return;
		list.addAll(entries(version, show ? view(group, total) : none(version), show && config.nameAlways));
		if (show) group.named = true;
		packet.setEntityMetadata(list);
		event.markForReEncode(true);
	}

	public void push(Group group, long total, List<Player> viewers) {
		ConfigManager config = manager.cfg;
		boolean show = config.nameActive && total >= config.minShown;
		if (!show && !group.named) return;
		WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(group.id, entries(version, show ? view(group, total) : none(version), show && config.nameAlways));
		if (show) group.named = true;
		else if (!config.nameActive) group.named = false;
		send(viewers, packet);
	}

	public void collect(Group group, Player picker, int count, List<Player> viewers) {
		send(viewers, new WrapperPlayServerCollectItem(group.id, picker.getEntityId(), count));
	}

	private void send(List<Player> viewers, PacketWrapper<?> packet) {
		PlayerManager players = PacketEvents.getAPI().getPlayerManager();
		for (Player player : viewers) players.sendPacketSilently(player, packet);
	}
}
