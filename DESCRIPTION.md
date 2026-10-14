<center>
<h2>ItemStacker</h2>
<p><strong>Ultra-Optimized Ground Item Stacking Plugin with Virtual Packet Names and Despawn Timers</strong></p>
<p>Author: <strong>lHawk_</strong> | Contact: <a href="https://minebuilders.com.br/discord">https://minebuilders.com.br/discord</a></p>
</center>

<hr>

<center>
<youtube>https://www.youtube.com/watch?v=afbDznRIxqY</youtube>
</center>

<hr>

<h3>1. Overview</h3>
<p><strong>ItemStacker</strong> is an ultra-high performance Minecraft item stacking plugin designed for modern survival, skyblock, and farm-heavy servers. Large mob farms and automatic crop harvesting systems frequently drop hundreds of thousands of loose item entities on the floor, resulting in severe tick lag, entity processing bottlenecks, and memory bloat.</p>

<p>ItemStacker solves this problem by merging nearby ground items into a single representative item entity. Rather than creating heavy server-side armor stand holograms or modifying persistent entity display names, ItemStacker renders stack counts and real-time despawn timers exclusively through client-side packets using <strong>PacketEvents</strong>. The server tracks large numbers cleanly as hidden 64-bit integers while preserving vanilla item mechanics, hoppers, and player inventory distribution.</p>

<hr>

<h3>2. Key Features</h3>
<ul>
<li><strong>Pure Packet-Based Displays:</strong> Stack labels and amounts are rendered entirely via client metadata packets. Zero physical holograms, zero invisible armor stands, and zero server-side custom name corruption.</li>
<li><strong>Despawn Countdown Timer:</strong> Real-time display of the remaining time until a pile despawns using the <code>{time}</code> or <code>{timer}</code> placeholder (similar to RoseStacker).</li>
<li><strong>Fork-Native Despawn Detection:</strong> Automatically reads and respects the item despawn rate defined in your server's fork configuration (such as <code>spigot.yml</code> or Paper world configurations), rather than forcing a rigid hardcoded timer.</li>
<li><strong>MiniMessage &amp; HEX Color Support:</strong> Full support for rich Adventure MiniMessage formatting (<code>&lt;gradient:#00d2ff:#3a7bd5&gt;</code>, <code>&lt;#00ffc8&gt;</code>, <code>&lt;green&gt;</code>) and legacy HEX codes (<code>&amp;#00d2ff</code>). Rich colors automatically downsample cleanly on legacy client versions.</li>
<li><strong>Toggleable Displayname Timer:</strong> Controlled directly in <code>config.yml</code> via <code>despawn_timer_active</code> with customizable formatting (e.g. <code>{mm}:{ss}</code> or <code>{m}m {s}s</code>).</li>
<li><strong>Extreme Scalability:</strong> Supports pile quantities up to millions of items per stack without entity overflow or client crashes.</li>
<li><strong>Smart Item Merging:</strong> Only merges items that share identical attributes (Material, durability, custom display names, lore, enchantments, and persistent data tags). Custom RPG items and enchanted gear are never accidentally merged with vanilla items.</li>
<li><strong>Intelligent Inventory Filling:</strong> Picking up a pile tops up existing partial stacks in the player's inventory first, then fills open slots up to capacity, leaving any remainder safely on the floor.</li>
<li><strong>Optimized Hopper Transfers:</strong> Hoppers collect items with rate-limited transfer ticks, preventing hopper lag storms across dense mob grinders.</li>
<li><strong>Native Folia &amp; Paper Compatibility:</strong> Built from the ground up to respect Folia threaded regions, entity schedulers, and asynchronous packet events.</li>
</ul>

<hr>

<h3>3. Platform Compatibility</h3>
<table>
<tr>
<th>Platform</th>
<th>Supported Versions</th>
<th>Status</th>
</tr>
<tr>
<td><strong>Paper</strong></td>
<td>1.16.5 to 26.2+</td>
<td>Fully Compatible</td>
</tr>
<tr>
<td><strong>Spigot</strong></td>
<td>1.16.5 to 26.2+</td>
<td>Fully Compatible</td>
</tr>
<tr>
<td><strong>Purpur</strong></td>
<td>1.16.5 to 26.2+</td>
<td>Fully Compatible</td>
</tr>
<tr>
<td><strong>Folia</strong></td>
<td>1.19.4 to 26.2+</td>
<td>Fully Compatible (Thread-Safe Region Schedulers)</td>
</tr>
<tr>
<td><strong>Legacy Spigot</strong></td>
<td>1.8.8 to 1.15.2</td>
<td>Supported (Java 8 bytecode, legacy color downsampling)</td>
</tr>
</table>

<hr>

<h3>4. Requirements &amp; Dependencies</h3>
<table>
<tr>
<th>Requirement</th>
<th>Details</th>
<th>Status</th>
</tr>
<tr>
<td>Java Runtime</td>
<td>Java 8, 11, 17, 21, or 25+</td>
<td>Universal Java 8 bytecode</td>
</tr>
<tr>
<td>PacketEvents</td>
<td>Bundled and relocated internally</td>
<td>No external installation required</td>
</tr>
<tr>
<td>ViaVersion / ProtocolLib</td>
<td>Optional soft-dependencies</td>
<td>Supported seamlessly if present</td>
</tr>
</table>

<hr>

<h3>5. Installation Guide</h3>
<ol>
<li>Download the latest <code>ItemStacker-1.1.0.jar</code>.</li>
<li>Place the jar file into your server's <code>plugins/</code> directory.</li>
<li>Start or restart your Minecraft server to generate default configurations.</li>
<li>Customize your desired stacking distance, format, and timer settings in <code>plugins/ItemStacker/config.yml</code>.</li>
<li>Type <code>/itemstacker reload</code> in console or in-game to apply configuration changes instantly.</li>
</ol>

<hr>

<h3>6. Commands &amp; Permissions</h3>
<table>
<tr>
<th>Command</th>
<th>Permission</th>
<th>Description</th>
</tr>
<tr>
<td><code>/itemstacker help</code></td>
<td><code>itemstacker.adm</code></td>
<td>Shows the list of available commands and instructions.</td>
</tr>
<tr>
<td><code>/itemstacker reload</code></td>
<td><code>itemstacker.adm</code></td>
<td>Reloads <code>config.yml</code>, message formats, and world despawn rates.</td>
</tr>
<tr>
<td><code>/itemstacker stats</code></td>
<td><code>itemstacker.adm</code></td>
<td>Displays real-time performance metrics, active groups, hidden items, and execution averages.</td>
</tr>
</table>

<hr>

<h3>7. Configuration Reference (config.yml)</h3>
<p>Below is an excerpt of the core options available in <code>config.yml</code>:</p>
<pre><code>Config:
    # Prefix shown in console messages.
    tag: "&amp;6[ItemStacker]"

    # Name format. Supports MiniMessage (&lt;gradient:#00d2ff:#3a7bd5&gt;, &lt;#00ffc8&gt;),
    # HEX colors (&amp;#00d2ff), and legacy color codes (&amp;b).
    # Placeholders: {displayname}, {amount}, {short}, {time}, {timer}, {despawn}
    displayname_format: "&amp;b{displayname} &amp;a{amount}x"

    # Display remaining despawn time above stacked items.
    despawn_timer_active: false

    # Despawn time format: {mm} (minutes), {ss} (seconds), {total_seconds}.
    despawn_time_format: "{mm}:{ss}"

    # Suffix appended when despawn_timer_active is true and {time} is omitted.
    despawn_timer_suffix: " &amp;7({time})"

    # Visibility controls
    displayname_active: true
    displayname_always: true
    displayname_min_amount: 2
    view_range: 32
    visual_interval: 5

    # Merge behavior
    stack_distance: 8
    delayCheckItems: 3000
    merge_on_spawn: true
    stack_size: 100000
    playsound: true</code></pre>

<hr>

<h3>8. Frequently Asked Questions (FAQ)</h3>
<details>
<summary><strong>How does ItemStacker prevent entity lag?</strong></summary>
<p>Traditional servers create a separate entity for every dropped item stack (e.g. 64 items per entity). When thousands of items drop, entity ticking exhausts CPU cycles. ItemStacker bundles the items into a single physical entity holding up to a safe vanilla stack count, keeping the remaining quantity stored efficiently in memory and persistent data containers. Pile display names are delivered as client-side packet updates, preventing entity overhead.</p>
</details>

<details>
<summary><strong>How does the despawn timer detect server despawn times?</strong></summary>
<p>ItemStacker dynamically inspects your server fork's world settings (such as <code>item-despawn-rate</code> in <code>spigot.yml</code> or Paper's world configuration). It tracks the item's age (<code>ticksLived</code>) and calculates the exact remaining time until the server will remove the item, ensuring the countdown matches your server's gameplay rules.</p>
</details>

<details>
<summary><strong>Can I use MiniMessage and HEX gradients?</strong></summary>
<p>Yes. MiniMessage is the recommended formatting system for ItemStacker. You can use gradients like <code>&lt;gradient:#00d2ff:#3a7bd5&gt;{displayname}&lt;/gradient&gt; &lt;green&gt;{amount}x&lt;/green&gt; &lt;gray&gt;[&lt;yellow&gt;{time}&lt;/yellow&gt;]&lt;/gray&gt;</code>. On modern Minecraft versions (1.16+), players will see full 24-bit RGB gradients, while older clients will receive cleanly downsampled colors.</p>
</details>

<details>
<summary><strong>Is Folia supported?</strong></summary>
<p>Yes. All chunk operations, entity pickups, and merging tasks utilize Folia's region scheduler and entity schedulers. Shared state is isolated across thread boundaries to guarantee thread safety without deadlocks.</p>
</details>

<hr>

<center>
<p><strong>Need help or support?</strong></p>
<p>Author: <strong>lHawk_</strong> | Community Discord: <a href="https://minebuilders.com.br/discord">https://minebuilders.com.br/discord</a></p>
</center>
