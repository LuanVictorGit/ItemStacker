# ItemStacker

Stacks dropped items into a few representative entities, so a farm can pile up millions of items on the ground without millions of entities.

* One item entity represents a whole pile. The entity only carries a vanilla-safe stack (never above the material's stack size); the rest of the pile is kept as a hidden `long` amount.
* The pile name (`Cobblestone 1234567x`) is drawn with PacketEvents. Nothing is written to the item entity (no custom name, no holograms).
* One jar for Spigot, Paper and Folia (Java 8 bytecode, Minecraft 1.8 and newer). See [Compatibility](#compatibility) for exactly what was run and what was not.

## Installation

1. Drop `ItemStacker-<version>.jar` into `plugins/` and start the server.
2. Edit `plugins/ItemStacker/config.yml` and run `/itemstacker reload`.

Requirements: Java 8 or newer (whatever your Minecraft version requires). No other plugin is needed: PacketEvents is bundled and relocated, so it does not clash with a standalone PacketEvents. If you already run ViaVersion, ProtocolLib, ProtocolSupport or Geyser, ItemStacker loads after them (soft-depend).

Removing the plugin keeps only the physical part of each pile (at most one vanilla stack per entity); the hidden amounts of piles that were bigger than that are gone with the plugin.

Upgrading from 0.5: existing `config.yml` files keep working. `stack_size` now means the amount one pile may represent (it used to be written into the item entity, which corrupts items above the vanilla limit). Keys that do not exist in your file fall back to the defaults shipped in the jar. Item entities that still carry a custom name written by 0.5 are left alone until they despawn.

## Commands and permissions

| Command | Description |
| --- | --- |
| `/itemstacker help` | Lists the commands |
| `/itemstacker reload` | Reloads `config.yml` |
| `/itemstacker stats` | Groups, hidden items, queue size, merges, pickups, hopper pickups, name updates, processing time |

Permission: `itemstacker.adm` (default: op).

## Configuration

Every key lives under `Config:` in `config.yml` and is documented in the file. Keys of previous versions (`tag`, `blacklist`, `displayname_*`, `delayCheckItems`, `stack_size`, `stack_distance`, `playsound`, `debug`) keep their meaning.

| Key | Default | Meaning |
| --- | --- | --- |
| `stack_size` | `100000` | Maximum amount represented by one pile (any value up to 2^61) |
| `stack_limits` | `{}` | Per-material limit, e.g. `DIAMOND: 5000` |
| `stack_distance` | `8` | Merge radius in blocks (1-32) |
| `delayCheckItems` | `3000` | Milliseconds before a freshly dropped item is merged |
| `merge_on_spawn` | `true` | Absorb a dropped item into a nearby pile before its entity exists |
| `blacklist` | `diamond_sword` | Materials that are never stacked (`name`, or legacy `id`/`id>data`) |
| `whitelist` | `[]` | When not empty, only these materials are stacked (the blacklist still applies) |
| `ignored_worlds` | `[]` | Worlds where nothing is stacked |
| `stack_custom_items` | `true` | Stack items with names, lore, enchantments or plugin data (only identical ones merge) |
| `stack_unstackable_items` | `true` | Stack items whose vanilla stack size is 1 |
| `displayname_active` / `displayname_always` | `true` / `true` | Show the pile name / keep it visible from a distance |
| `displayname_format` | `&b{displayname} &a{amount}x` | Placeholders: `{displayname}`, `{amount}`, `{short}` (1.2M), `{time}` / `{timer}`. Supports MiniMessage (`<gradient:...>`, `<#rrggbb>`), HEX (`&#rrggbb`), and legacy (`&b`) |
| `despawn_timer_active` | `false` | Enable or disable despawn timer countdown in item stack display name |
| `despawn_time_format` | `{mm}:{ss}` | Format of remaining despawn time: `{mm}`, `{m}`, `{ss}`, `{s}`, `{total_seconds}` |
| `despawn_timer_suffix` | ` &7({time})` | Suffix automatically appended when `despawn_timer_active` is true and `{time}` is omitted |
| `displayname_min_amount` | `2` | Smallest amount that shows a name |
| `view_range` | `32` | Players farther than this do not receive name updates (keep it at least as large as the server's item tracking range, `entity-tracking-range.misc`, 32 by default) |
| `visual_interval` | `5` | Ticks between grouped name updates |
| `budget` | `1500` | Piles examined per cycle |
| `slice_ms` | `5` | Longest merge run of one chunk; a crowded chunk continues in the next cycle |
| `queue_limit` | `4096` | Chunks that may wait in the processing queues |
| `rescan_seconds` | `30` | Every chunk with items is re-checked this often (0 = never) |
| `save_seconds` | `30` | Write interval of `groups.dat` (versions below 1.14) |
| `playsound` | `true` | Pickup sound when a pile is collected |
| `debug` | `false` | Logs slow chunks |

## How it works

**Pile = entity + hidden amount.** `total = entity stack amount + hidden amount`. The entity stack never exceeds `min(material stack size, 99)`, which is what every Minecraft version can store (item counts are a byte up to 1.20.4 and are capped at 99 from 1.20.5). The hidden amount is a `long`:

* 1.14 and newer: stored in the entity's persistent data container (`itemstacker:extra`), so it is saved atomically with the chunk.
* 1.8 to 1.13: stored in `plugins/ItemStacker/groups.dat` (written asynchronously, on every world save and when the plugin is disabled).

**Comparison.** Piles are only merged when `ItemStack#isSimilar` agrees (type, durability, full meta including enchantments, lore, NBT and plugin data). A cached key (type, durability, meta hash) is only a pre-filter, so hash collisions can never merge different items.

**Collecting.** A pile with a hidden amount cancels the vanilla pickup and hands out exactly what fits: players get up to a full inventory (36 slots, topping up partial stacks first), hoppers get one stack per transfer with a vanilla-like cooldown, mobs get nothing. The remainder stays in the pile. Piles without a hidden amount are left to vanilla, and vanilla merging is cancelled whenever it would swallow a hidden amount.

**Performance.**
* Chunk buckets index every pile by world and chunk; a merge only looks at the chunks inside the merge radius.
* Work is queued per chunk with a due time (name updates first, merges second), deduplicated, bounded by `queue_limit` and limited per cycle by `budget`.
* One repeating task drives everything. There is no per-item task and no periodic scan of all entities; the only world scan is the one at enable time (non-Folia, for servers that enable the plugin after the worlds are loaded).
* Names are sent only for piles whose total changed, grouped per chunk and per `visual_interval`, and only to players within `view_range`. Players who log in, enter the area or receive a chunk again get the right name because the plugin rewrites the entity metadata packet the server sends anyway.

**Folia.** Chunk work is dispatched with the region scheduler, entities that wander into another region are re-registered with the entity scheduler, and a pile is only touched by the thread that owns its entity. Piles on both sides of a region border simply do not merge until they are in the same region.

## Known limitations

* Piles are ordinary item entities: when one despawns (5 minutes by default), burns, falls into lava or the void, or is blown up, everything it represents is lost, exactly like a vanilla stack. Merging resets the age to the younger item, like vanilla.
* Minecraft 1.11 and older have no event for mobs picking items up (and some silent removals): a mob that takes a pile removes the whole entity, including its hidden amount. From 1.12 on the pickup is cancelled for mobs.
* Below 1.14 the hidden amounts live in `groups.dat`. A crash can leave the file up to `save_seconds` older than the world save, which can lose or duplicate the items merged in that window. 1.14+ has no such window.
* Records in `groups.dat` of entities that vanished while their chunk was unloaded are kept (a few bytes each).
* Items that are not stacked by design: blacklisted or ignored-world items, items with a custom entity name, a pickup delay of 32767 or an owner set by another plugin.
* With `merge_on_spawn` an item is absorbed while its spawn event runs. A plugin that spawns an item with a placeholder stack and sets the real stack afterwards must run with `merge_on_spawn: false`.
* Names use the vanilla name tag (`displayname_always: false` shows it only while looking at the pile); legacy clients (below 1.13) cut it at 64 characters. Players beyond `view_range` but still tracking the item keep the last name they received until it changes again.
* Hopper throughput on a pile follows one stack per 8 ticks, regardless of the number of hoppers on the same pile.
* `/reload` is not supported on Folia (the world scan at enable time is skipped there).
* PacketEvents reports its version to bStats like any bundled copy; disable it in `plugins/bStats/config.yml` if you do not want that.

## Compatibility

Only what is listed here was run, on the code in this repository. Anything else is not claimed.

| Check | Result |
| --- | --- |
| Unit tests (`mvn test`) | 36 passed |
| Bytecode of the shaded jar | all 2287 classes are Java 8 (class file 52) |
| `ApiCheck`: every Bukkit/Paper member the plugin references exists in | spigot-api 1.8.8, 1.12.2, 1.16.5, 1.20.6, 1.21.11, 26.3 and paper-api 26.3 |

Server tests (`integration/`): the scenarios below run on a real server with the real jar, results in `integration/results/`.

@@MATRIX@@

Scenarios per target: spawn-time and delayed merging, per-material limit split, 128,000 items becoming 2 entities, amounts around 65 / 127 / 128 / 32767 / 32768, items that must not merge (name, lore, enchantment, durability), merging across a chunk border, blacklist, hopper into an empty and into a partly filled hopper, dropper and dispenser, 50 broken bookshelves, 30 killed pigs, a 200-item stack created by another plugin, the vanilla merge guard, reference cleanup after item removal, chunk unload and reload, restart persistence, a 6000-item burst, a 5,000,000-item pile, player pickups (bulk, partial, truncated by a nearly full inventory, unstackable, full inventory), and on targets with a protocol bot the names a real client receives (live update, cleared name, late join, re-tracking after leaving and returning). Folia targets also merge piles next to region borders.

"Protocol bot" means a scripted client of that Minecraft version was connected to the server. The bot library supports up to 26.1, so the 26.2 and 26.3 targets run the server-side scenarios without a client.

Not run: Minecraft 1.10, 1.11, 1.14, 1.20.5-1.20.6 and 1.21-1.21.10 servers, Spigot builds other than the ones listed, forks other than Paper and Folia, ViaVersion-translated clients, Bedrock clients through Geyser, and servers with real player load.

## Building

```
mvn clean package
```

Produces `target/ItemStacker-<version>.jar` (the shaded jar). The project targets Java 8 bytecode and is compiled against Spigot 1.8.8; every call to newer or platform specific API (Folia schedulers, persistent data, newer events) goes through `utils/Platform`.

## Tests

* `mvn test`: unit tests for the platform independent logic (amount arithmetic, inventory filling, number formatting, configuration parsing) and a round trip of the name metadata for every protocol version PacketEvents knows.
* `java integration/ApiCheck.java target/ItemStacker-<version>.jar <api jars...>`: checks that every Bukkit/Paper call in the jar exists in the given API jars (Java 24 or newer).
* `integration/`: real server tests (Node 22 or newer, the Java version each target needs).

```
cd integration
npm install
ACCEPT_EULA=true node run.mjs paper-1.21.11
ACCEPT_EULA=true node matrix.mjs --parallel=2 paper-1.8.8 folia-1.21.11
node summary.mjs
```

`run.mjs` downloads the server jar (Paper and Folia from the PaperMC download API, SHA-256 verified; Spigot jars are built with BuildTools and referenced by path in `integration/targets.json`), writes `eula.txt` into a scratch directory (`integration/.work`, or `WORK_DIR`) only when `ACCEPT_EULA=true` is set, because that means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), starts the server with the plugin jar from `target/` and the harness plugin from `integration/harness`, connects the bot, runs the scenarios and writes `integration/results/<target>.txt`. Set `JAVA_8`, `JAVA_21` and `JAVA_25` to the matching executables when they are not in the default locations. Build the harness once with `mvn -f integration/harness/pom.xml package`.

## License notes

ItemStacker bundles [PacketEvents](https://github.com/retrooper/packetevents) (GPL-3.0) and Adventure (MIT). Distribute the jar accordingly.
