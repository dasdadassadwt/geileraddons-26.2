<div align="center">

# GeilerAddons

**Overlays and puzzle solvers for Hypixel SkyBlock.**

GeilerAddons is a client-side mod. Network features include optional Hypixel island detection,
update checks, Party Finder profile lookups, and hosted player profiles. Sparkling Critter
announcements use ordinary in-game chat. Dungeon profile lookups are cached, and the mod never sends
a kick unless Auto Kick is enabled and you are party leader.

**Official Discord server:** [discord.gg/VmHPgB5Jyc](https://discord.gg/VmHPgB5Jyc) — support, feedback and release notices.
The GitHub repository is [dasdadassadwt/geileraddons-26.2](https://github.com/dasdadassadwt/geileraddons-26.2).

![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen)
![Loader](https://img.shields.io/badge/loader-Fabric-blue)
![License](https://img.shields.io/badge/license-CC0--1.0-lightgrey)

</div>

---

Open the menu in-game with **`/ga`**.

Fresh configurations start with feature modules disabled. An upgrade preserves each module's saved
enable choice; settings and HUD positions migrate when modules are renamed or split.

The **Dev → Slot IDs** module contains the optional slot overlay. It labels each inventory slot
with the menu id used by vanilla (not the backing-inventory index), which makes slot-based
workflows and puzzle reports reproducible. Its **Label Text Color** is configurable while the dark
badge background stays fixed. **Move Elements** includes a separate sample badge preview for
placement; moving that preview does not move the actual per-slot badges. **Dev → Debug** independently
enables the retained, category/module-separated GeilerAddons logs under `logs/geileraddons/`.

Features identified as **Unreleased** below are present in the current source tree but are not part
of the last published release. Source descriptions do not imply live Hypixel verification.

## Installation and compatibility

GeilerAddons 1.7.0 targets the **client** on Minecraft **26.2**. The Fabric instance needs Java 25,
Fabric Loader 0.19.5 or newer, Fabric API for Minecraft 26.2, and the Fabric implementation of the
Hypixel Mod API (version 1.0 or newer). Install the GeilerAddons jar and those dependencies in the
client's `mods` folder. GeilerAddons is a client mod and is not intended for server installation.

## Modules

<details>
<summary><b>General</b> — the mod-wide switches: macro chat, island detection, updates</summary>

<br>

**General** in **Miscellaneous** holds the settings that belong to the mod as a whole rather than to
one overlay. Cheat-style options are controlled directly where they are used; there is no separate
master toggle that overrides their individual settings.

**Chat Triggers In Text Screens** (off by default) lets chat-triggered macro stacks run while a chat
box, editor or menu has the keyboard. Leave it off and chat can never make a macro type into what you
are writing.

**Show Availability Notices** controls the inactive-reason text shown on module cards and their
tooltips. Turn it off to keep the grid quieter; module names, descriptions, and settings remain
visible.

**Macros → Replay Last Blocked Macro** is an explicit settings button. It starts the chat-triggered
stack that was most recently refused — the usual case being a message that arrived while chat was
open — and explains itself when there is nothing to replay. Individual chat stacks can also have
their own replay hotkey.

**Check for Updates** lives here now instead of in the config file. The old top-level
`checkForUpdates` key still seeds it once on the first launch after the move, so nobody loses their
choice.

</details>

<details>
<summary><b>Chat Channels and Party Commands</b> — clickable channel buttons and reply tools</summary>

<br>

**Chat** extends Minecraft's normal chat instead of drawing a second window. **Extra Width**,
**Extra Focused Height**, and **Extra Unfocused Height** add GUI-scaled pixels to the current vanilla
chat dimensions; all start at `0`, and the effective size is clamped to the scaled screen viewport.
Chat keeps its vanilla position, rendering, input, and scrolling. Right-click a visible message to
copy its complete text; **Copy Message on Right-click** is enabled by default.

**Chat Channels** adds translucent **All**, **Party**, **Guild**, **Co-op**, **Private**, and **System**
buttons directly above the vanilla chat input. Selecting a tab is local and sends no server command.
With a tab selected, ordinary text is sent immediately with the matching prefix: `/ac`, `/pc`, `/gc`,
`/cc`, or `/r` for All, Party, Guild, Co-op, or Private. Slash commands pass through unchanged.
The selected tab filters Minecraft's normal chat history: **All** shows every message, while
**System** shows Vanilla/System lines, including Officer messages that also remain in All. Tab
selection has no cooldown, and the selected button resets to All after restart. While either module
is active, the client keeps a bounded in-memory channel index; vanilla chat remains the only displayed
history.

**Party Commands** is a separate module for configured incoming replies, local actions, and party
transfer helpers. Its settings include the command prefix, reply delay, allowed incoming channels,
and a Manage Replies editor for bounded command entries and folders.

Incoming chat replies support `<prefix>help`/`<prefix>h`, `<prefix>coords`/`<prefix>co`,
`<prefix>odin`/`<prefix>od`, `<prefix>cf`, `<prefix>8ball`, `<prefix>dice`, `<prefix>ping`,
`<prefix>tps`, `<prefix>fps`, `<prefix>time`, and `<prefix>holding`, plus Party-only
`<prefix>pt [username]`/`<prefix>ptme [username]`/`<prefix>transfer [username]`, `<prefix>warp`, `<prefix>allinv`,
`<prefix>boop`, `<prefix>kick`, `<prefix>promote`, `<prefix>demote`, `<prefix>ko`, the queue commands
`<prefix>f1` to `<prefix>t5`, `<prefix>dt [reason]`/`<prefix>undt` and `<prefix>reinvite`, and Private-only
`<prefix>inv`. Leader-only commands (everything above except boop, dt and reinvite) run only when you are the
known party leader; if the party leader is not known yet they do nothing. The queue commands send the same
`joininstance` command as the f1-t5 aliases, so the Odin mod is not needed. **Downtime**: `!dt [reason]` from a party
member sets a reminder and holds Instance Requeue for that run; at the end of the run you get the list of reasons, and
your own reason is posted to party chat. `!undt` removes it. **Reinvite**: invites the sender 5 seconds later (not when
someone else leads the party). A private `!inv` shows a clickable invite; **Auto Confirm Invite** invites at once.
Sender names are read with a rank tag, the `ቾ`/`⚒` badge after the name, or a guild rank tag after the name. The reply master
switch defaults on, and any valid player in Party chat can request a reply by default. Party is the
only incoming request channel enabled on a fresh configuration; Guild, Co-op, Private, and
**Custom Channels** have independent switches under **Incoming Reply Channels**. Custom Channels
controls replies arriving through user-created Chat Channels rules; it does not control custom reply
entries. For an incoming transfer without
a target, the requesting party member is the target. You can type
`<prefix>pt {args}` locally (for example, `!pt username` with the default prefix) to transfer
directly when you are party leader and the fresh roster confirms the exact target. Any sender who is not
on your blocklist can request a transfer; an enabled blocklist entry always wins. The reply delay defaults to four client ticks. The master
switch and incoming channel controls are in **Party Commands** settings.

`!coords`/`!co` reports local integer block coordinates. `!tps` estimates tick rate from increasing
`gameTime` values in received time packets and monotonic client receive times. It reports the latest
fresh interval and trailing 10-second and 1-minute windows only when observed intervals continuously
cover the whole window; partial edge intervals use their measured average rate. Since these packets
are not guaranteed to arrive periodically, `N/A` includes a reason when samples are stale or coverage
is incomplete. This remains a bounded client estimate, not an authoritative server TPS metric.

The Reply Commands editor supports up to 16 named commands organized in folders. Each entry can be
a **Text Reply**, which responds to matching incoming chat, or a **Local Action**, which runs only
when you type the command yourself. Local Action templates accept `{args}` and a single slashless
command template; they are never executed from another player's message. A Text Reply can hold tags that
are filled in when it is sent: `{sender}` (who typed the command), `{me}` (your name), `{time}`,
`{rng:1-10}` (a random whole number in that range, up to 1000000) and `{pick:a|b|c}` (one random option, up to
16). For example, a reply named `rng` with `{sender} rolled {rng:1-10}` answers `!rng` in party chat with
`Steve rolled 7`. The **How Replies Work (i)** button prints a step-by-step guide to making a command in chat, and **Preview** shows a sample with the tags filled in. Unknown tags stay as written; a bad `{rng}` or `{pick}` stops the reply from saving. Folder and entry enable
switches and a global blocklist (32 usernames) control the reply system. Everyone who is not blocked can
use every enabled command, party actions included (kick, promote and demote are off by default). Add a name
with **Player Name** and **Block**, and remove it with **Remove** in the list below; the Blocklist
switch turns the whole list off. Party commands trust the `Party >` chat prefix, so no party list is needed;
leader-only actions are skipped only when another player is known to lead the party, otherwise Hypixel
enforces leadership. Replies use the request's Party, Guild, Co-op, or
Private route (`/pc`, `/gc`, `/cc`, or
`/r`). Help lists every enabled command across lines capped at 170 characters; continuation lines
begin `Commands (continued):`. Sender filters apply to ordinary reply commands too. A delayed `/r` reply is dropped if an incoming private message or a recognized
local `/msg`-style command changes the tracked counterpart. Unrecognized whisper aliases or hidden
server-side changes to `/r` state cannot be detected by the client. Outgoing channel messages use the
normal chat submission path, and slash commands remain vanilla commands. Reply entries and filters
Reply entries and the blocklist are stored in the feature-local chat settings file and are excluded
from shared config profiles.

</details>

<details>
<summary><b>Party Finder Stats</b> — shows dungeon stats for joining players</summary>

<br>

When the first Party Finder player joins, the module sends `/p list` and fetches the current party
members except you. Already-checked players are not fetched again by later `/party list` output.
The queued floor is captured from the selected values in Hypixel's Group Builder when you click
`Confirm Group`, then accepted only when Hypixel confirms the queue. It displays a bold cyan
bordered card fitted to the current chat width with configurable Catacombs level, joined class
level, class average, magical power, secret average, floor PB, gear, and actions. The Click GUI
shows an inline sample card, and its ten stat toggles update both the preview and future cards
immediately. Hover over SA for total secrets and a class for all class levels. **Normal PBs** and
**Master PBs** have separate floor lists and separate hovers; the Party Finder card also shows the
queued-floor PB. `Kick` appears only for
the party leader. Joining someone else's listing adds one final **Leave Party** action after all
profiles finish.

When Party Finder Stats is enabled, joining a listing in Catacombs or the Dungeon Hub shows one
`[PF] Check party stats.` reminder per party session. The initial roster scan still fetches profiles
and runs Auto Kick checks, but its stats cards are suppressed; the final **Leave Party** action is
retained.

If a profile cannot be fetched, the line says so and provides a green **PV** button that runs
`/pv <player>`. **Compact** collapses the card to one line. When profile lookup is unavailable, the
card says so instead of showing a row of unknown values. If the Hypixel Mod API switch is off, the
card adds an **Island detection API is off** status line. You can also fetch one player's card with
`/ga dstats [player-name]`; leaving the name out looks up your own account. While entering a target,
Tab suggests matching names from the current server's listed players. Names must contain 1–16
letters, digits, or underscores. The command uses the same display toggles.
Gear checks read Odin's wrapped compressed inventory payload; a missing or invalid inventory still
leaves only those gear fields marked unavailable. Profile requests are paced and a brief HTTP 429 gets
one retry. If the service keeps rate-limiting or returns incomplete data, Auto Kick leaves the name
unverified and issues no kick; use **Retry Stats** after the limit clears.

</details>

<details>
<summary><b>Party Finder Listings</b> — member count, floor, open classes and level colors on listings</summary>

<br>

Reads each Party Finder listing (a player head whose lore has a `Members:` block) in the menu you have open.
**Member Count** draws `3/5` on the icon (red when full), **Floor** draws the floor (`F7`, `M7`, red for Master
Mode), **Open Classes** adds an `Open:` line under the member rows in the hover tooltip, and **Class Level
Colors** colors each row's level (red below 30, yellow below 40, green below 50, gold at 50). The lore layout
follows Skyblocker's listing parser and has not been checked against a live listing; the module's automatic log
records what it found. Your own selected class is not highlighted because no stable source for it is known.

</details>

<details>
<summary><b>Dungeon Guide and run tools</b> — routes, puzzle solvers, and run tracking</summary>

<br>

**Unreleased.** Dungeon features are separate modules, and each feature module starts disabled in a
fresh configuration. Existing saved enable choices are preserved during upgrades.

**Dungeon Guide** contains route editing, stage controls, and **Phase Outputs**. The shared run cues
have one **Clear** group; floor-specific cues are organized under **Normal Mode** and **Master Mode**,
then by floor. Each cue can independently show a title, HUD hint, chat line, or sound.

**Puzzle Solvers** has separate switches for Water Board, TP Maze, Ice Fill, Blaze, Creeper Beams,
Three Weirdos, Quiz, Boulder, and Tic Tac Toe. Room detection chooses the applicable solver
automatically; there is no global puzzle-mode selector. Puzzle solutions use world-space markings and
paths. Water Board countdowns appear as projected floating labels above pending levers; its tracer and
the other puzzle markings remain in the world. Tic Tac Toe checks every occupied map frame for a unique board
square and a recognized X/O mark; empty squares are allowed. On a legal board when it is O's turn, it
shows a green box and short guidance line. **Predict Machine Moves** is a separate, off-by-default
option: when the player follows the hint and exactly one minimax-optimal X reply exists, amber and
cyan plates show that reply and O's next move. If multiple X replies are equally strong, future moves
are hidden. Inactive, ambiguous, illegal, completed, or unrecognized board state clears stale
guidance. It does not click for you and is not included in Puzzle Timers.
Solver settings expose only their relevant choices, such as route variant, Blaze room position, TP Maze
colors and tracer, or Creeper Beam sounds.
Unsupported or ambiguous room state is left unknown rather than guessed. Solver catalogs and their
source/license records are listed in [Dungeon solver data provenance](docs/provenance/dungeon-solver-data.md).
Optional **Puzzle Timers** begin on the first confirmed puzzle-room entry and complete only on
source-supported puzzle events. Qualifying times compare against local puzzle bests. **Draft Prompt**
adds a clickable chat action for the supported puzzle-failure messages; it does not send the command
until clicked.

The remaining focused modules are **Scorer** (threshold, alert text, duration, and sound), **Run
Splits** (Blood, Camp, and Portal aggregates; all tracked floor boss phases; individual S1–S4 rows;
and a bottom Run total, with real time fixed first and **Second Time Display** for 50 ms tick
estimates, bracketed local PBs, red Time Lost, or Off; the Goldor and Storm timers moved to **Tick Timers**, the M3 fire-freeze countdown to **Fire Freeze Timer**), **Blood Camp**
(Watcher wave predictions and world markers), **Livid Solver** (Livid highlight and invulnerability
timer), **Invincibility Timer** (movable HUD with a head icon per Bonzo Mask, Spirit Mask, and Phoenix proc: gold seconds
while active, red seconds on cooldown, green check when ready; the centered title shows only an active window; optional **Always Show In Dungeon** keeps the
HUD up for the whole run and **Party Announce** sends `Bonzo proc`, `Spirit proc`, or `Phoenix proc` to party chat), **Spirit Leap Menu**
(centered teammate cards, quadrant selection, adjustable card styling, sorting and key modes, incoming
leap titles, announcements, and arrival sounds), **Terminal Stats** (terminal breakdown and optional
self-only counts), and **Terminal Titles** (optional title replacement and template).
They can be enabled and configured independently.

**Blood Camp** detects the Watcher from its head texture, then tracks matching mob head textures on
Armor Stands within 20 blocks during dungeon clear. It does not require a confirmed Blood room or a
fixed height band. The assist projects the first wave 16.1 blocks along the mob's movement and later
waves 11.9 blocks after the Watcher moves. **Tick** defaults to 38 (adjustable from 35–41); the time
offset defaults to 40 ms, Ping Offset and interpolation are on, and Manual Offset defaults to 0. Green
marks the mob's current position, red its spawn, and dark aqua its final point; **Line** draws a red tracer and
**Time Left** shows the colored spawn estimate. **Watcher Bar** displays estimated Watcher health.
The Click GUI preview shows marker styles without a countdown; there is no Move HUD or separate
coordinate-list HUD during play.
**Move Prediction**, **Move Message**, **Kill Title**, **Blood Camp Assist**, **Line**, **Time Left**,
and **Watcher Bar** are on by default; **Party Move Message** is off. The default **Box Size** is 1,
with green position, red spawn, and dark aqua final colors. Disabling **Move Prediction** suppresses
move messages and the Kill Mobs title, but a recognized Watcher move still switches assist prediction
to the later-wave distance.

**Spirit Leap Menu** places up to four 200×75 teammate cards in a compact 2×2 group at the center of
the open leap inventory. Teammates are identified from validated player heads in menu slots 11–15,
so cards remain available when the party snapshot drops; party, tab, and loaded-player data fill in
class and status when present. Missing class data displays **Class unknown**. If no valid teammate
head is present, the vanilla inventory remains visible. Cards show the player's face and name. The
translucent class fills default to Tank
green (`#00AA00`), Berserk red (`#AA0000`), Mage blue (`#5555FF`), Archer orange (`#FFAA00`), and
Healer pink (`#FF55FF`). Keep the **Class Colors** master switch enabled to use these fills; each class
has an individual color picker. These colors also drive **Dungeon Class ESP** (teammate fill, outline, and
name titles) and the class-colored player labels on the dungeon map.
**Card Distance** controls the cards' screen-relative distance from center (8.5% of each screen axis
by default, adjustable from 5.5–18% and clamped to prevent overlap). **Extra Roundness** sets their
corner radius from 0–18 px (9 px by default). Any mouse button in a populated card quadrant selects
its teammate; an empty quadrant passes the click through. Clicks and configured keys use the same
resolved card-to-head mapping, and dispatch only if the corresponding live slot still contains a
player head. A resolved slot is dispatched through vanilla `PICKUP` input. Incoming leap titles appear
in the vanilla title overlay and resolve
class exclusively from the dungeon tab list; missing or invalid tab classes display **Class unknown**.
Arrival sound, title, and announcement options remain in **Spirit Leap Menu** settings.
**Show Dungeon Map** (off by default) draws the live dungeon map centered above the card group, never
rotated, with teammate names while you hold the leap item. **Map Scale** (0.5–4, default 1.5) sets the map size
and may cover the cards when large; the map stays on screen and is hidden when no live map exists. It is
also hidden while the Dungeon Room Overlay map (**Show Map**) is already visible, so only one map shows. With
the cursor over the map, any mouse button leaps to the teammate marker nearest the cursor, on the
configured press or release phase; the click is consumed even when no teammate resolves, and the
cards behave as before.

**Show POVs** uses Minecraft 26.2's backend-neutral GPU APIs to render four teammate views. Its vanilla
backend gate allows OpenGL and Vulkan after the same capability checks; the Vulkan path has not yet been
verified in a client. Sodium requires the exact `0.9.2+mc26.2` build and a successful private-target
capability preflight. Nvidium stays closed because the pinned `0.4.4-beta6-26.1` build has not been
verified on 26.2. Fabric Loader and Fabric API must be present; their patch versions are recorded in
diagnostics but are not pinned. Unknown Sodium or Nvidium versions and unsupported backends remain
closed. Iris, Canvas, and Distant Horizons are blocked until their renderer interactions are verified;
VulkanMod is not blocked by name, but still needs a client run. Sodium uses a private-target capability
preflight; an observed wrong target latches the feature closed, while route hooks that do not run during a
pass are not treated as failures. The first eligible loaded teammate/quadrant is privately rendered as
warmup; its frame becomes available only after the framebuffer bindings and viewport pass restoration
checks, then the scheduler promotes to four-feed round robin. The model-view stack and output-texture
overrides are restored separately. Targets are half resolution and reused, with one update per main frame
and a 4 Hz cap per feed under a shared target-pixel budget. On Skyblocker `6.10.4+26.2`, a version-gated
bridge skips its primitive callbacks only during private POV rendering; the main-world renderer is
unchanged, and unknown Skyblocker versions keep POVs closed. Unavailable teammates or any missing chunk
in the local 3×3 FULL-chunk neighborhood keep their quadrant hidden; validated menu-head identities
remain eligible when party online status is unknown, but the feed still requires a matching alive player
loaded in the client world. The chunk check never requests
remote chunks. A render/state failure stays latched across menu
reopens and clears on a POV/module toggle off-to-on, world change, or successful resource reload. This
source-level support path has not received live visual or performance verification; diagnostics do not
prove visible pixels or a frame-time budget.

Dungeon ESP is split into three modules. **Dungeon Mob ESP** owns Starred Mobs, Minibosses, and Fels.
**Dungeon Boss ESP** owns the F7 Wither Boss and known Mimic chest highlights. **Dungeon Class ESP**
highlights teammates by class: the fill defaults to the class color (**Fill By Class Color**, with a
**Class Fill Opacity** slider; turn it off to use the custom **Party Class Fill**), the outline is always the
class color, and **Class Titles** draws each teammate's name above their head in that color, with a yellow
`[H]`-style class letter in front (**Show Class In Title**), 3.25 blocks above their feet (**Title Height**) and
for every loaded teammate regardless of range, like NoFrills' class nametags. The class highlight and titles stay
active through the boss fight. Class colors are the Spirit Leap Menu colors; **Edit Class Colors** opens that module.
Profiles saved before the split keep their Wither, Mimic, and Party Classes values and on/off state.
**Secret Clicked** controls dungeon-secret click boxes and the secret chime.

Run times and bests are stored locally in `config/geileraddons/dungeon-split-personal-bests.json`;
the file is bounded, atomically replaced, and excluded from exported profiles. Run Splits aggregates
Entry through Blood Open as **Blood**, Blood Open through Watcher completion as **Camp**, and Watcher
completion through the first observed boss-entry cue as **Portal**. Floors expose that cue through their
opening boss dialogue. It then shows the tracked boss stages and the live or
completed **Run** total. F2 labels Scarf's Minions as **Minions**, F6 labels Sadan's Giants as **Giants**,
and F3 combines Professor and Transformed Professor durations into one **Professor** row while keeping
their stored phase bests intact. Every split row always shows elapsed real time first. **Second Time
Display** defaults to **Local PB** and selects one secondary value: **Srv Ticks** estimates duration
from clientbound ping packets with negative IDs, counted after their handoff to the client thread, at
50 ms per server tick; **Local PB** shows the saved real-time best in gray brackets; **Time Lost**
shows the nonnegative difference between real duration and the server-tick estimate, floored to full
50 ms intervals in red with a plus sign; or **Off** hides the secondary value. Differences under
50 ms display as `+0.00s`. The server-tick estimate is a client-observed signal, not authoritative
server duration. Existing nonzero ping-packet totals and personal-best storage remain unchanged. F7
and M7 show the aggregate Terminals row and each
tracked S1–S4 phase. Move Elements previews current split rows when available and uses an F7 sample otherwise.
The selector and sample HUD preview fit in the dev client at 856×506 with GUI Scale Auto. A live
dungeon run is still needed to validate timing against observed phase boundaries.

</details>

<details>
<summary><b>Room Alerts, Key Pickup, and Glass Doors</b> — room status, keys, and door display</summary>

<br>

**Unreleased.** **Room Alerts** report map-derived room-clear and secret status after the same room
footprint and checkmark remain stable for five client ticks. A first observation of a completed room
only establishes its state; each completion alert fires at most once per room per dungeon run, even
across brief detector gaps or footprint growth. Room Cleared subtitles ("Cleared") default to cyan and Secrets Cleared
subtitles ("Secrets Done") to green, with separate color controls. When a room jumps straight to all secrets
found, both appear: Cleared as the title and Secrets Done below it. New alerts replace the current subtitle at Minecraft's
fixed subtitle position. The two sounds are independently configurable; Room
Cleared defaults to the level-up sound and Secrets Cleared to a note-block pling. **Key Pickup**
detects key-carrying ArmorStands from clientbound entity metadata after vanilla applies it, matching
only the exact names `Wither Key` and `Blood Key`. All currently detected key stands can be highlighted
and traced at the same time. A matching metadata update triggers **Alert on Newly Spawned Keys** once
per target. The alert defaults on in a new configuration, while a saved value is preserved. Key stands
already loaded when the module starts are added as targets without a spawn alert. Item-name matching is
used only as a local pickup-packet fallback; it does not classify visual targets or trigger spawn
alerts.

Pickup confirmation also retains the exact server lines `A Wither Key was picked up!` and `A Blood Key
was picked up!`, plus named `X has obtained ... Key!` lines with optional rank prefixes. A named actor
is shown as self or teammate only when a recent complete current-party roster verifies that name;
otherwise the optional unattributed alert uses a generic key label and never guesses from proximity or
the local username alone. Local, party, unattributed, title, and sound outputs are configurable. Key
Pickup writes bounded diagnostics automatically while enabled; its trace omits raw chat and
actor/player names. Box Doors keeps its own door key-state, boxes, and tracers; it does not share Key
Pickup targets. **Glass Doors** projects configured glass states into the selected Start area
and the detected Wither/Blood door positions shared with Box Doors; it does not change server block
state. Bounded Glass Doors diagnostics are logged automatically while the module is enabled, without
a debug toggle. Live Room Alerts map transitions, packet/chat pickup routing, party-roster checks,
ArmorStand metadata timing, and key rendering still need an in-game check.

</details>

<details>
<summary><b>Dungeon Map</b> — room names, secrets, exits, and teammate markers</summary>

<br>

**Unreleased.** Dungeon Map is a movable 6×6 room map. It can show room names and secrets, doors,
checkmarks, special-room predictions, and loaded teammate markers and labels. Its settings control
map scale, room size and gap, padding, text and icon size/alignment, room and door colors, background
and outline, and player marker/name/class presentation. Before saved map colors and the Odin layout
are ready, the HUD shows a neutral provisional 6×6 grid and marks the local player cell without room
identities or connections. It switches to the existing authoritative map when ready. The map preview
in settings reflects the current choices. A **Map Info** panel is attached under the map, as wide as the map, with Odin's
two rows: secrets found, needed and total, score, deaths (or unfound secrets), Mimic/Prince/Bat markers and crypts. It has its own
switch and settings (Include Remaining, Remaining Secrets, Deaths, Hide Map Info In Boss, background color). Its Unfound count uses the
secrets of the rooms already opened on the map. Its default uses the requested item-icon style and enables
**Illegal Map: Reveal Unopened Rooms**. The **Map Style** settings group can copy all Dungeon Map settings to the clipboard
as Base64 JSON, import bounded JSON or Base64 from the clipboard, or reset every map setting to its
declared default. Imports include the icon choice, reveal and prediction toggles, and unopened-room
fill/text colors; invalid, incompatible, or out-of-range values are rejected without changing any
settings. Known rooms show their name and local secret progress together (for example, `Flags` and
`7/7`); counts remain `?/max` until an in-room actionbar counter or green completion state confirms
progress. Unrecognized rooms remain `?`. With **Illegal Map: Reveal Unopened Rooms** off, the map
shows every room Odin marks viewable from the normal map checkmarks, the player's current room even
before a checkmark appears, and positively confirmed door cues attached to a visible room. At an
in-grid endpoint whose room is not viewable, a confirmed door also draws the existing gray question
tile as a hint; the hint is only a destination cue and never creates a room, label, outline, bridge, or
door. **Show Exits** controls both cues and hints. With reveal enabled, hints are suppressed and the
existing room and door rendering is preserved; that mode also shows map pixels marked undiscovered
and exact catalog room cores already read from loaded
center chunks; a confirmed visible room or neighboring door is not required. An exact core with an
incomplete multi-tile footprint appears as a neutral single cell until the full footprint is confirmed,
without a guessed type, name, secret count, or outline. Multi-tile names require a complete,
unambiguous footprint. Unsupported border cells stay empty. Map pixels determine distinct room types.
For shared-color categories, compatible catalog data adds a name and secret maximum only when the
type and full footprint agree; an explicit unknown-color room pixel may be resolved by an exact
complete catalog footprint. Mismatches keep the map-derived type and omit catalog identity and
secret data. Odin scans each map tile's center chunk when it loads, including chunks already loaded
when map scanning starts. It never requests or force-loads chunks. Confirmed map evidence, checkmarks,
and catalog identities survive temporary map-read gaps for the current run. Door cues merge map-pixel
edges with positively confirmed loaded door-tracker edges by grid edge. A cue renders only when it is
attached to a room visible in the selected mode; it never establishes neighboring room occupancy.
During temporary map-read gaps, retained map exits remain while core-scan fallback supplies complete
room identities or neutral single-cell occupancy for incomplete exact footprints. Unopened partial
cells still follow **Reveal Unopened Rooms**.
**Show Checkmarks** controls only its small status icon and never hides room text. **Disable
Predictions** controls predictions separately from the reveal setting. Counts are
local to the client and reset on
a new run, floor/world change, or disconnect; they are not party-synchronized. The HUD contains the
map only: no header, debug details, or footer.

Yellow/Champion rooms have no text label. Puzzle rooms show their puzzle name instead of a secret
count, including when **Room Text** is set to **Room Secrets**.

Teammate map decorations pair with the current ordered living dungeon roster only when the calibrated
map layout is valid and the decoration and roster counts match. The local marker follows the player
entity directly. If ordered pairing is unavailable, only name-matched loaded players provide remote
positions; identities are never guessed. Recently confirmed remote positions survive brief missing
updates for up to 40 client ticks, then expire; dead or departed roster entries and run, floor, world,
or connection changes clear them. Existing settings still control marker, name, and class display.
When player-name labels
are enabled during a held Spirit Leap, the full name stays inside the map viewport, moving or scaling
as needed. Plus-suffixed rank
tags such as `[MVP+]` are accepted;
removed player-info UUIDs, a world change, or connection close clear their roster entries. Because
Normal and Rare share a map color, so the display uses the Normal fallback until a loaded room
signature identifies Rare. Unopened-room reveal and map predictions have separate controls; reveal
is on by default, with its own fill/text colors and darkening setting. The “Illegal Map” phrase in
the reveal setting name is a compatibility alias for this local verified-room display behavior;
**Icon Style = Item Icon** is a separate choice. Yellow/Champion rooms and
verified miniboss metadata have independent colors. Boss entry clears the current-room highlight;
dungeon exit clears the run's visited-room history.

Odin is the only active Dungeon Map detection backend. Map pixels determine distinct room types;
catalog names and secret counts require compatible types and complete footprints. Room signatures are
read only from loaded center chunks, so cells can remain unknown when their data is not available.
Door exits merge map-pixel evidence with positively confirmed loaded door-tracker edges by grid edge;
a tracker-only edge renders only when attached to a room visible in the selected mode. With reveal off,
a confirmed visible-room exit can add a gray question hint at its in-grid, non-viewable endpoint; this
overlay does not add a room or alter room geometry. Reveal-on room and door output stays unchanged.
Temporary map-data gaps keep last-confirmed exits while loaded-core fallback supplies complete
identities or neutral partial cells; unopened cells still follow the reveal setting. A door alone never
creates a room. Teammate decorations pair with current ordered living tab rows only when counts and
layout match; otherwise name-matched loaded players are the fallback. Briefly missing remote markers
are retained for at most 40 client ticks, with dead/departed identities removed. The map and solver source/data records are
listed in the [dungeon data provenance audit](docs/provenance/dungeon-solver-data.md). Source and offline
checks still need a live run to verify alignment, names, player markers, and rendering.

</details>

<details>
<summary><b>Secret Clicked</b> — click and pickup confirmation</summary>

<br>

**Unreleased.** **Secret Clicked** is an F7 feature toggle for click and pickup confirmation. Its
controls include **Secret Boxes**, **Box Style**, configurable **Box Outline Color** and **Locked
Outline Color**, **Depth Check**, **Box Time To Stay**, **Box In Boss**, and **Item Boxes**. The
**Secret Chime** has independent sound, volume, pitch, and boss-room controls. The existing
**Confirmation Window (ms)** still bounds server confirmation, while displayed boxes last for the
configured duration. **Track Secret Blocks in Boss** controls whether chest and lever clicks are
observed there. Box and chime feedback in the boss phase are off by default. Existing waypoint data
remains in `config/geileraddons/secret-waypoints.json`; waypoint markers and editing are temporarily
unavailable.

</details>

<details>
<summary><b>Auto Kick</b> — checks Party Finder requirements per floor</summary>

<br>

Configure F1–F7 and M1–M7 separately. Each folded floor heading carries its Auto Kick switch, each
floor has an **Ask Before** toggle, and numeric requirements are entered as text. Requirements
include Cata, selected-class level, class average, secrets, secret average, MP, PB, bank,
Term/Hype/GDrag, and duplicate-class checks.

The PB limit accepts `m:ss` (for example, `6:42`) or legacy seconds; a player's PB must be that
time or faster. Enter `0` to disable the PB check.

Auto Kick only enforces requirements while you are party leader. Automatic kicks first wait a
random 1–2 seconds, send the reasons to party chat, wait another random 1–2 seconds, and then kick
if leadership and membership are still valid. Multiple actions are serialized. **Ask Before**
instead shows the reasons client-side with a clickable **Kick** button and schedules no command.
Missing API data never causes an automatic kick.
The profile retry window and retry interval are configurable under **Safety**; after the window
expires the mod stops retrying and offers a manual **Kick** action, with a reminder to ban or
ignore the player yourself if that is what you want.

</details>

<details>
<summary><b>Experimentation Solver</b> — reads Experimentation Table boards</summary>

<br>

The Experimentation Solver recognizes **Chronomatron**, **Ultrasequencer**, and **Superpairs**. It
shows the sequence or discovered card information in a configurable overlay and can prevent clicks
that do not match the current sequence step. Preview depth, colours, dimensions, and the selected
sound, pitch, and volume are configurable. **Hide Text Above Board** hides the vanilla text above
the experiment; **Show Superpairs Clicks Remaining** displays the observed remaining-click count
when available. **Max Click Alert** (on by default) plays the selected sound once when an experiment
reaches its maximum-click milestone. **Complete Sounds** also enables that same milestone sound.
There is no chat alert. Its input assistance is separate from
**Auto Experiments**; Superpairs is manual-only.

</details>

<details>
<summary><b>Auto Experiments</b> — replays supported experiment sequences</summary>

<br>

Auto Experiments can replay **Chronomatron** and **Ultrasequencer**. Choose the games to automate and
set the first-click and between-click delays. **Superpairs is not automated.** Chronomatron continues
to use the shared experiment session. Experimentation Solver and Auto share one experiment-board
owner and session, so enabling both does not create duplicate board observations. Ultrasequencer Auto
consumes the shared solver's complete immutable sequence view and keeps a separate timed execution
cursor; it does not parse a second board sequence or treat vanilla click dispatch as server
acknowledgment. Each
click is guarded against the current screen, menu, session, and slot. Manual input, an incomplete or
changed solution, or a refused/uncertain dispatch pauses the executor without retrying a click. Its
diagnostics record solver progress, Auto cursor/delay state, ownership, pause reason, and dispatch
result; these do not establish live server acceptance. The shared solver logic/model and Chronomatron
path were not changed by this Auto integration; Chronomatron remains on its existing scheduler. While the
matching Ultrasequencer is open, Experimentation Solver also shows Auto's cursor for the exact active
screen, menu, session, and solution; this is display-only and does not affect manual click protection.
Experimentation Solver's **Max Click Alert** plays the selected sound once at the maximum-click
milestone. The alert defaults on, and **Complete Sounds** also enables the same milestone sound.

</details>

<details>
<summary><b>Dungeon Guide</b> — run player-authored Catacombs route guides</summary>

<br>

**Unreleased.** Each floor has one named route containing every built-in and saved custom phase.
Legacy routes migrate into that route in phase order, with their original route and step order
preserved within each phase; the original files are archived for recovery. The editor groups steps by
phase and keeps the selected step's label, position, phase, conditions, and shape in one inspector.
Appearance and searchable macro controls are collapsed until needed. Add a step at your feet or at
the block under your crosshair. Box, ring, beacon, and text-only shapes are available. World labels
are plain text without a backplate.

Choose **ALL** or **ANY** for manual advance, radius, delay, chat phrase, and detected phase
conditions. Chat phrases can match an **Exact** normalized line or a **Contains** phrase; saved routes
without a match setting keep the Exact behavior. Manual advance remains available alongside automatic
conditions, and does not block those conditions. Only the active step is marked and traced; after its
conditions pass, the next step appears. Each step can enable **Skip ahead**: entering its trigger
radius jumps to the furthest later enabled step in the selected route and leaves that target active.
Bypassed steps do not fire their linked macros. Forward phase changes also advance past earlier-phase
steps without firing their macros; a phase regression does not rewind progress. New steps on the
active floor use its phase only after detection or a manual choice establishes it. If that phase is
unknown, or when adding to another floor, the selected step's valid phase is used, then **Entry**.
Turn on **Always Display All Route Steps** to keep every step in the active phase visible after progress
advances. The module's normal keybind opens the editor. In the editor, **Simulate** cycles through the
floor's phases and filters the route list to the selected phase without changing a live run. Inspector
fields have explanatory tooltips, and outline, fill, and label colors use the Click GUI color picker.
An optional linked enabled macro runs its **On Call** stack when a step advances. Each Ring step has
its own ring count, height, radius, speed, line width, and optional filled interior in the advanced
appearance section; these controls appear only while that step uses the Ring shape. Its **Marker
size** scales the ring height and radius. Filled interiors start off. Older routes with shared ring
settings migrate those values to each existing Ring step; imported older routes receive the same
migration. Guide status remains an ordinary HUD panel.

Dungeon presence and floor are read from the displayed scoreboard independently of Party Finder
Stats and Auto Kick. When the dungeon is detected but its floor is unknown, Dungeon Guide stays
inactive instead of assuming F1. Use its **Choose Dungeon Floor** action to open the floor picker.
The manual choice remains until leaving the dungeon,
then automatic detection resumes. The **Edit Guide Stages** action opens the per-floor stage manager:
choose a stage manually or return to automatic detection, and add, reorder, or remove custom stages
with exact-chat, location-radius, or timer triggers. The built-in ordered stage model starts with
**Entry**, **Clear**, **Blood Open**,
**Blood Clear**, and **Boss Entry**, then adds floor-specific boss steps: **Bonzo** on Entrance;
**Bonzo's Sike/Bonzo** on F1; **Scarf's Minions/Scarf** on F2; **Guardians/Professor/Transformed
Professor** on F3; **Thorn** on F4; **Livid** on F5; **Terracottas/Sadan's Giants/Sadan** on F6;
and **Maxor/Storm/Terminals/Goldor/Necron**, plus **Wither King** on M7. **Complete** ends each
stage list. These are available route groups and selectable stages; they do not all have automatic
chat evidence. Chat recognition covers run start, blood opening/clearing, boss entry/defeat, and
specific dialogue or terminal milestones on supported floors. Saved custom phases and older broad
Blood/Boss phase IDs are retained during migration. The route editor provides built-in and saved custom phases;
route markers are depth-tested by default, and their **Draw Through Walls** option is a direct choice. Scoreboard
formats, stage chat, and floor transitions still need live verification.

</details>

<details>
<summary><b>Box Doors</b> — highlight dungeon doors</summary>

<br>

**Unreleased.** Box Doors retains Normal, Glass, and Start classification for dungeon tracking but no
longer submits boxes for those classes; visible door boxes are limited to closed Wither and Blood
doors. They have separate
fill and outline switches, colors with opacity, and through-wall options, and default to red without
the matching key and green after that door's key is carried or the server reports its pickup. Wither
and Blood key state is tracked separately. The map's visible 1×1 Entrance countdown tile still
identifies its connected Start door.
The Wither-door and Blood-door tracers each have
an independent enable switch and share color, width, and through-wall controls. Each tracer starts
The Wither trace follows the route from the player's current mapped room toward Blood, including the
pink 1×1 Fairy Room on the critical path. It targets the next ordered CLOSED door even when the player
is far from that door or not next to its room boundary. An OPEN route door advances the target;
UNKNOWN state holds that target without drawing a line. If map routing is unavailable, incomplete, or
ambiguous, the tracker-ordered Wither route is used, followed by the Blood door. This fallback also
skips only authoritatively OPEN doors and holds at UNKNOWN state. Both tracer lines ignore **Door Render
Range**; that setting continues to cap door boxes, and **Wither Doors Ahead** affects boxes only. Blood
is withheld while a Wither route target is next, so the two lines do not compete.
The **Blood Rush Classes** group has a general **Filter by Blood Rush Class** switch, off by default,
plus Tank, Healer, Mage, Berserk, and Archer toggles that all start on. When enabled, Box Doors reads
the local player's class from the live tab-list row and gates both boxes and tracers. A missing or
unrecognized row, or an unchecked class, hides both; door tracking continues.
The dungeon tracker keeps one Wither/Blood record with each door's identity, type, bounds, and coal or
red-terracotta source positions. It retains those records across non-source and open-state rescans and
chunk unloads; Box Doors and Glass Doors consume the same records. The latest authoritative state gates
ESP: only CLOSED doors are eligible, while OPEN or UNKNOWN doors are hidden. Glass clears at an
authoritative air update. Bounded door-selection, route source, class resolution/filter decision,
presence, eligibility, and render-submission diagnostics are logged automatically while Box Doors is
enabled; the log does not prove that pixels were visible on screen. The scanner works in bounded slices
of already-loaded chunks and does not request new chunks.
The **Glass Doors** module starts off on a fresh configuration. Its independent Start, Wither, and
Blood replacement toggles are on by default when the module is enabled. Start selection records the
first loaded player pose, then scans the forward grid edge whose center is 17–20 blocks ahead, using the
nearest cardinal yaw within 10 degrees. Every 20 client ticks it samples a 5-wide × 4-high × 3-deep
volume at the fixed `y=69..72` band, reading only already-loaded positions. The scan must be fully loaded
(60/60 positions) and contain exactly one face-connected component of regular or infested Chiseled Stone
Bricks across the full 3D volume; the panel may span any or all three depth slices. The first complete scan
with one component selects it immediately, before source blocks can transition to Barrier or Air.
Missing/unloaded samples, non-cardinal yaw, an out-of-range edge, unsupported materials, or multiple
disconnected panels leave Start unchanged and retry later. Only the selected chiseled source positions
become glass targets; other blocks in the scan volume remain unchanged. The calibrated map Entrance scan
is diagnostic-only and cross-checks the spawn-selected edge. The old Mort setting key remains inert for
saved-config compatibility, is hidden from the settings UI, and Mort/spawn data is diagnostic only.

For temporary panel-position evidence, target one chiseled Start-door corner and run `/ga startdoor mark`,
then target the opposite corner and run it again. `/ga startdoor cancel` clears a pending point. The
capture checks authoritative source blocks, reports the region relative to the calibrated Entrance grid,
and is discarded on cancel or run/map change; it does not save an anchor or alter the current selector.
An unsupported mark reports the clicked block ID and authoritative source ID. Manual marks are
diagnostic-only and never select replacement targets.

Selected Start sources project configured glass for each selected non-air state, including Barrier.
Authoritative air clears glass immediately and retires that position; later non-air updates at a retired
position remain unchanged until world/dungeon state is reset. Wither and Blood masks begin from every
Coal Block and Red Terracotta source respectively inside the detected door bounds. Later server non-air
states such as Barrier remain projected. Authoritative air removes glass and retires that source
position. A full chunk refresh rebases authoritative states and reapplies retained non-air selections;
actual chunk unload or world reset clears the affected masks. Disabling the module or a door type
restores its currently loaded positions while retaining active masks in the current world.

Wither and Blood masks begin from Coal Blocks and Red Terracotta respectively inside the tracked door
bounds. Later server non-air states such as Barrier remain projected. Authoritative air removes the
glass immediately while retaining that position's mask; a later non-air state can project glass again.
A full chunk refresh rebases authoritative states and preserves active masks; an actual chunk unload
or world reset clears them. Disabling the module or a door type restores its current loaded positions
while keeping active masks for the current world.
**Replacement Blocks** keeps the searchable registered-block picker for each door type; the defaults
are clear glass, black stained glass, and red stained glass. Server block updates are cached as the
authoritative source and reconciled on the client thread before dungeon trackers consume them. Chunk
loads seed the source cache after Vanilla installs the chunk; full chunk refresh rebases authoritative
states and reapplies retained selections without forgetting them. Actual chunk unload evicts its cache
without restoring into dropped storage. World teardown restores still-owned loaded positions from the
latest server state. Vanilla mining queries use each owned projection's latest authoritative source
state, preserving its block-specific mining behavior through source and Barrier transitions.

The local projection is renderer-independent, including with Sodium/Nvidium, and the projection itself
sends no block-action packet or changes server state. Because it updates the local world state, local
collision and selection can follow the projected glass while active; ordinary player interactions
still use Vanilla's normal interaction path and packets. Bounded replacement, Start-gap selection,
source transitions, and mining-state diagnostics are logged automatically while Glass Doors is enabled;
no debug switch is required. The Start trace reports the first loaded player pose, spawn-facing grid edge,
yaw and forward-distance window, exact `y=69..72` volume bounds, 60-position loaded status, per-depth
material counts, full-volume 3D component count, selection outcome, and selected source positions. The map
Entrance scan is retained as a diagnostic cross-check only. Mort and spawn data are diagnostic only.
Source and focused offline checks
can establish selection rules but cannot prove the panel mapping or rendered appearance in a live
dungeon; live verification remains pending.

</details>

<details>
<summary><b>i4 Helper</b> — Catacombs F7/M7 4th device</summary>

<br>

Takes the guesswork out of the 4th device puzzle.

- **Boxes the panel** — completed blocks in red, the live target in green.
- **Marks where to aim** — a bright dot on the exact spot for the current shot, so you're not eyeballing block edges mid-fight.
- **Shows the next shots too** — every still-open block gets a dimmer marker, so you can pre-aim instead of reacting.
- **Plays a jingle** when your team finishes a device, and hides the notification spam that buries it.
- **Recovers instantly when you land back on the plate.** Arriving part-way through a device, or stepping back on after the device itself went inactive, re-reads the live target instead of guessing a blank board.

Colours and marker sizes are all adjustable.

</details>

<details>
<summary><b>Tiki Helper</b> — Sneaky Tiki hunting in Torrhus</summary>

<br>

Sneaky Tikis are three stacked heads that have to be rotated into alignment by clicking them. This module handles finding them and telling you exactly what to click.

**Waypoints** — boxes every saved tiki spot, green if one is standing there and red if not. Markers stay put after the spot leaves render distance, so you keep your bearings while running a route. An optional tracer line points at the nearest solvable tiki.

**Solver** — floats a number over the head that needs clicking. `+3` means left-click it three times, `-2` means right-click it twice. It updates the instant a click registers, and handles the case where one head is hidden behind another block — you still get the direction, marked `+?`, because the count genuinely can't be known. The rest of the solve shows too, greyed out ahead of time, so you can see every remaining click instead of just the next one.

**Debug Logging** — off by default. Writes every rotation, click and sound to a file. This is how the solver's rule was worked out, and it's there for anyone who wants to check that rule against a live server.

The module switches itself on only in Torrhus, and sits idle everywhere else in SkyBlock — so it costs you nothing while you're doing something else. The card in the menu tells you where you currently are when it's idle.

</details>

<details>
<summary><b>Safari Floor Drops</b> — highlights drops in the Critter Safari</summary>

<br>

Boxes floor drops as they appear, so you spot them without sweeping the floor with your eyes.

There's no list of spots behind this. A drop is caught **when it spawns**, from the particle the server sends at it, and confirmed by the model it's made of — so it works on spots nobody has mapped, and a drop someone else already took stops being marked instead of leading you to nothing. Boxes clear themselves a few seconds after the drop goes, and immediately once you act on the block.

**Depth Check** decides whether walls hide the box. On means it behaves like anything else in the world; off means you see it through terrain.

The module only runs on the Safari and sits idle everywhere else, so it costs you nothing while you're doing something else.

</details>

<details>
<summary><b>Hideyho Finder</b> — boxes the Hideyho wherever it is</summary>

<br>

Sweeps the Haunted biome once a second for the Hideyho itself and boxes it through walls until it's gone.

**It only runs in Haunted**, that being the only part of the Safari a Hideyho spawns in, so it costs you nothing while you're working the other three. The card tells you when it's idle for that reason. Which biome you're in comes from the biome the server reports rather than from a map of coordinates, so there's nothing to go stale when Hypixel moves a wall.

**It looks for the critter, not for the places it hides.** A list of hiding spots goes stale the moment Hypixel adds one, can only ever check spots whose chunks happen to be loaded, and misses a Hideyho standing a few blocks off a listed spot — so there isn't one. Nothing needs triggering and no chat line needs catching: if it's in range, it's boxed.

It's recognised by its skin, with its name as a backup so a reskin doesn't quietly switch the module off.

**Debug Mode** boxes every fake player in range in a faded colour — that's how you tell "it wasn't there" apart from "it was there and wasn't recognised".

Scan rate and colour are both yours to set.

</details>

<details>
<summary><b>Sparkling Critter</b> — finds the rare variants, and can call them out</summary>

<br>

Sweeps the Safari for sparkling critters and boxes them through walls, with the species written above each one.

**It reads name tags.** Hypixel names the label above a rare one `Sparkling Rockmite` rather than `Rockmite`, and a name tag renders through terrain in vanilla anyway — so this points out something that was already on your screen, it just stops you having to notice it. A label is matched whole, so a dropped item called a "Sparkling Tepid Shard" is not mistaken for a Tepid.

Boxes follow the critter rather than its name tag: Hypixel floats that on a separate marker with no hitbox at all, and the mod resolves it back to the mob underneath — never to a player standing nearby.

**Telling your party.** Off by default. When it's on, the first time a sparkling comes within your **Range to Send** it posts `Sparkling Rockmite found at x:131 y:54 z:12` to party chat. Each critter is called out once, and one that was too far away when you first saw it still gets called out when you get closer.

**Debug** prints that line to your own chat instead of sending it, and never sends anything while it's on — so you can watch exactly what it would say without messaging anybody.

Colour, text size, an optional tracer line and the sweep rate are all yours to set.

</details>

<details>
<summary><b>Critter Tracker</b> — dex progress, what's missing, Hunter trades, and shard value per run and per hour</summary>

<br>

Follows one Critter Safari run from the moment you arrive. It reads your `CAPTURE!` lines and your party's `LOOT SHARE!` lines, so it counts what the party has caught **since walking in**, which the in-game Critterdex cannot (it counts everything you have ever caught).

Three movable panels (drag them with **Move Elements**):

- **Critter Progress**: run time, party and personal dex counts, a bar per biome, the run's value, coins per hour, your Hunting Hotspot, how many Snooper and Troodon walls are left, and the Rockmite mounds found and broken.
- **Critter Missing**: what is still uncaught in the biome you are standing in. A species with a fixed number of spawns (Gemzie 3, Troodon 3, Gazer 4, Wumpa, Hideyho and Doomspiral 1) stays listed until every one is caught, unless **Unique Catches Only** is on. Snoozle drops off once every Cavern wall is broken and none came.
- **Critter Hunter Trades**: the Hunter offers found this run, nearest first.

**Time, money and money per hour.** **Track Shard Value** prices the shards you received (your own catches plus loot share) at the live Bazaar and shows the total and the coins per hour. **Price Mode** picks instant sell, sell offers, or both side by side; both are top-of-book prices with the 1.25% Bazaar tax taken off. The per-hour figure waits for the first minute of a run. Prices come from Hypixel's public Bazaar endpoint, two minutes apart at most; no key or account is needed and nothing about you is in the request. A finished run keeps its length and value until the next one opens.

**Commands.** `/critters` (or `/ct`) prints the run summary. `/critters missing`, `copy`, `share` (posts the missing list to party chat), `players` (unique catches per player per biome), `prices` (refresh now), `text`, `reset`, `waypoints`, `debug`. Quick toggles: `/critters hud`, `panel`, `alerts`, `biomedone`.

Warden Darkness removal lives in **No Render** (the **Warden Darkness** switch); it drops the Darkness effect on your client and sends nothing to the server.

Not here: the History and Stats tabs, log replay and the tabbed run screen of the mod this follows ([Critter Mod](https://github.com/MrCloudy2/critterMod)).

</details>

<details>
<summary><b>Critter Markers</b> — boxes the Safari's walls, bee spots, mounds, trades, Bloodbats and Duplico</summary>

<br>

Each marker has its own toggle and colour, and shows only in its own biome (all off by default except Bloodbat Boxes):

- **Snooper Walls** (Cavern) and **Troodon Walls** (Icy): the preset positions, kept until you have looked at one and seen it broken.
- **Bee Spots** (Forest): the eleven preset spots. Looking at one tells you whether it is a nest (its own colour) or a hive or nothing (the mark goes). Punching a nest and getting a bee out clears it.
- **Rockmite Mounds** (Cavern): found by looking at them, then kept until you look and see them gone.
- **Hunter Trades**: where a Hunter offered you a shard.
- **Bloodbat Boxes** and **Duplico Boxes** (on by default): a box on each Bloodbat you can see, and on each Duplico the client has even while it hides as a block (through terrain, as Skyblocker does). Settings are folded into groups. **Hideonwall ESP** is its own module and boxes the Hideonwalls the client has, through walls; the old preset perch spots were only possibilities and are gone.

**What it will and will not tell you.** A fixed position is map knowledge, so it is boxed through terrain. Anything read from the world (a wall's state, a nest, a mound, a critter) is only used once you have a clear line to it, because the server sends blocks and entities far past what you can see. A partymate breaking a wall across the Cavern does not clear your mark until you look.

Floor drops, sparkling critters and the Hideyho are not here; they are their own modules.

</details>

<details>
<summary><b>Hideonwall ESP</b> — boxes Hideonwall critters through walls</summary>

<br>

In the Critter Safari, boxes every Hideonwall the client has (the labelled critter and its body), through walls, with a name and distance label. **Box Color** and **Show Labels** are the only settings. It marks only a Hideonwall that exists on your client, never a place one might be; the preset perch spots that Critter Markers used to show are gone. Whether a hidden Hideonwall is already known to the client before you get close is not verified in game.

</details>

<details>
<summary><b>Critter Alerts</b> — encounter, milestone, Macaw and Hunter trade calls, with party sharing</summary>

<br>

Calls out: Gemzie, Wumpa and Doomspiral at ready, started and done (each its own toggle, and only while you are in that biome unless **Encounter Alerts In Biome Only** is off); a biome completed, everything except the Macaw, and everything done; a Macaw spawning (from the Birdfeeder line or by seeing one), optionally the Bluebird and Parakeet too; a Hunter trade offer with its biome and coordinates; and your Hunting Hotspot. Sound is off by default. `/critters testalert` fires the main ones so you can check them.

**Party and all chat.** Every announcement has its own audience: nobody (the default), party or all chat. Posts are queued and spaced one a second so a burst cannot trip Hypixel's chat limits.

**Trade sharing.** A Hunter trade posts as `Hunter Dennis (Icy -106 87 -7): Gimmiegold Shard for Purple Gem` and `Trade taken: Gimmiegold Shard`, the same wording as Critter Mod, so partymates on either mod see your trades in their tracker and boxes. **Read Shared Trades From Party** controls reading them; your own name is ignored.

</details>

<details>
<summary><b>Fishing</b> — bobber timer, bait, rare catches, geysers, hotspots and lava (SkyHanni behavior)</summary>

<br>

Seven modules in the **Fishing** category. All are off by default and only work on SkyBlock.

- **Fishing Bobber Timer** — a movable `Bobber: 5.3s` line while you hold a rod and the bobber is out. **Start On Liquid Touch** starts the clock when the bobber first touches water or lava instead of at the cast.
- **Fishing Hook Display** — mirrors the game's own hook timer big: when a fish is on its way the game puts a countdown on the hook, then `!!!` when it bites; this shows that text as a movable HUD element (place it in Move Elements), sized by **Text Scale**, with an optional **Bite Sound**.
- **Fishing Bait** — reads the bait in your last hotbar slot and shows `1,234x Name` with its icon while you hold a rod, or `No Bait` in red. **Bait Change Warning** gives a title, a click and a chat line when the bait differs from the last one; **No Bait Warning** does the same when you cast with none. The bait is read from the `Bait Remaining` line of the item, so it needs the usual SkyBlock bait item in that slot.
- **Sea Creature Alerts** — when your catch chat line names one of the 23 rare sea creatures (Water Hydra, Lord Jawbus, Thunder, Yeti, Reindrake and the rest) it shows a title and plays a sound; **Show Creature Name** switches the title between the creature's name and `RARE CATCH!`. Another player's rare creature that spawns nearby, read from its name tag, gives a `NEARBY!` title. **Announce In Party** (off by default) posts `I caught a Lord Jawbus!` to the party, with a `DOUBLE HOOK: ` prefix for a double hook. Creatures within 30 blocks get a green box. It is off in dungeons and Kuudra.
- **Geyser Fishing** — on the Crimson Isle, finds the trophy-fishing geyser from its cloud particles and draws a thin box at y 117.9 within 96 blocks; **Only With Rod** draws it only while you hold a rod, and **Hide Particles Near Bobber** drops the geyser's particles when your bobber sits in them.
- **Hotspot Radar** — after you use the Hotspot Radar, the flame particle path it leaves is fitted and extrapolated to the hotspot, which is marked `HOTSPOT` with its distance and an optional line from your crosshair. The mark clears within 10 blocks. There is no island pathfinding; a guess more than 1000 blocks away is ignored.
- **Lava Replacement** — draws lava as water (on the opaque layer) so the bobber is easy to see: on the Crimson Isle, in Kuudra, in the Catacombs, or everywhere. It is purely visual. Chunks rebuild when it switches on or off.

Each module writes its own automatic log. These are ported from SkyHanni's behavior and have not been checked in a live client yet; see [future ideas](docs/project/future-ideas.md#fishing-follow-ups).

</details>

<details>
<summary><b>Mining</b> — commissions, powder, HotM, chests, Mineshaft, Glacite, Crystal Hollows and Dwarven Mines helpers (SkyHanni and Skyblocker behavior)</summary>

<br>

Thirteen modules in the **Mining** category, split into Crystal Hollows, Dwarven Mines, Glacite Tunnels and General subcategories. All are off by default and only work on SkyBlock. HUD modules share
**Background**, **Text Color**, **Background Color** and **Text Scale**.

- **Mining Commissions** — your commissions and their progress, read from the tab list (or the Commissions menu while it is open). Names and progress only. **Hide Completed**, **Highlight Completed**.
- **Mining Powder** — Mithril, Gemstone and (**Show Glacite**) Glacite powder from the tab list, with **Show Gain** and **Show Per Hour** for the session. **Drill Fuel Bar** draws the fuel as a bar on your drill.
- **HotM Tree Helper** — in the Heart of the Mountain menu: **Level On Slot** shows each perk's level as a number, **Highlight Perks** marks maxed, affordable and unaffordable perks, and the hover text can add **Powder Spent**, **Powder To Max**, the powder for the **Next Levels** (default 10, reduced to the levels the perk can still take), **Tokens On Heart** and **Powder You Have**. The Heart of the Forest has its own module, **HotF Tree Helper**, under Foraging.
- **Powder Chests** — in the Crystal Hollows, boxes powder chests that appeared near you (not ones other players opened), with an **ESP Range** (0 shows every chest, default 40), **Timer On Chest**, **Show Chest Age** and **Connect Chests**, a line from the first chest to the last. **Color By Time Left** turns the box red as the timer runs out.
- **Mineshaft Timer** — time in the current Glacite Mineshaft with the best and last time and an optional chat summary.
- **Corpse Finder** — in a Mineshaft, boxes each frozen corpse through walls with its type (Lapis, Mineral, Armor of Yog, Vanguard). **Hide Looted** and **Line To Corpse** are optional; the tab list says which are looted.
- **Metal Detector** — the Mines of Divan treasure solver, after SkyHanni: shows where the chest can be from your readings and marks the best spot; **Stop When All Tools** and **Pling On Match** are optional.
- **Glacite Guide** — draws a route (coloured by target with **Colour By Target**: Peridot green, Citrine brown, Onyx black, Aquamarine blue, Tungsten gray, Umber orange) through the Glacite Tunnels to your next commission or gemstone target, started by a left click on the Royal Pigeon (**Left Click Royal Pigeon**, on by default; turn it off to show the route automatically).
- **Heat and Cold** — **Mute High Heat** drops the Crystal Hollows heat sound while your heat is 90 or more and you are at y 65 or below, and **Cold Overlay** blues the screen once the Glacite cold passes **Cold Threshold**, up to **Max Opacity**.
- **Goblin Highlighter** — boxes your own Golden and Diamond Goblin and Goblin Raid Superprotectrons.
- **Mining Events** — the active mining event with its time, a title when one starts, a goblin spawn alert and a Don Expresso reminder.
- **Mining Solvers** — **Fetchur** names today's item from his hint, **Puzzler** boxes the dig spot, and the **Fossil Excavator** solver colors the best slot green with its chance.
- **Crystal Hollows Helper** — waypoints for the Hollows structures, found without walking in by reading the block patterns of chunks you already have loaded (**Scan Loaded Chunks**: nearest chunks first, up to six every tenth of a second within a 3 ms cap, each chunk at most twice a lobby; King Yolkar and Odawa are NPCs and are marked when their name tag comes into view; it only reads blocks the client has and sends nothing) or when the sidebar names the area you are in, plus a Crystal Nucleus marker and a box on treasure chests you uncover. When coordinates appear in chat, the mod asks what they are with one-click buttons (the structure it guessed comes first) and adds nothing by itself. `/ga hollows add <structure|other> [x y z]`, `remove <name>`, `list`, `clear` and `share [name]` (sends `Name: x y z` to party chat) manage the markers. **Show Map** adds a movable HUD map of the whole Hollows (Nucleus, markers and you; **Map Size**). There is no server sharing; Skyblocker's websocket is not used.

Each module writes its own automatic log in `Mining/`. The modules are ported from SkyHanni and Skyblocker behavior and have not been checked in a live client yet; see [future ideas](docs/project/future-ideas.md#mining-follow-ups).

</details>

<details>
<summary><b>Mob Highlight</b> — box any mob you name</summary>

<br>

Make as many highlights as you want, each with its own colours and its own text to match.

- **Match by name or by type.** Name matches what the mob calls itself, so `crypt ghoul` finds it whatever level tag Hypixel hangs off it. Type matches what kind of thing it is, so `zombie` catches every zombie regardless of name.
- **Colour codes and levels don't get in the way.** Matching runs on the readable text, ignoring case, so you type what you see rather than what the server sent.
- **Two colours per highlight** — outline and fill, set separately — plus its own depth check and its own scan rate.
- **The name you give it is drawn on the box**, in the real game font, so several highlights running at once stay tellable apart.
- **Its own switch, on its own heading.** Turn a highlight off without deleting it or opening it up.
- **Pick the islands it runs on.** Each highlight has its own **Islands** list, folded up inside it, covering every island the mod can recognise. **They all start off**, so you say where a highlight belongs rather than switching off the two dozen places you didn't mean. Where the island can't be named at all — single player, another server, or the moment before the handshake lands — the highlight stays idle until the official API supplies a recognised island. It never guesses from terrain or scoreboard text. Highlights made before this existed keep their old all-island selections until you narrow them.

Highlights are built in the settings panel itself: **Create Mob Highlight** adds an empty one, and each becomes its own foldable section you fill in. No separate screen to keep in step.

Use **Manage Folders** to organize highlights into nested groups. Folders are independent from the
Macros and Block ESP trees; renaming or moving one never changes another feature's folders.

</details>

<details>
<summary><b>No Loading Screen</b> — skip the terrain loading screen</summary>

<br>

Closes the terrain loading screen shown after joining a world or changing dimension, so the game is visible at once. The
chunks still load in the background and your character does not move until its chunk is loaded, as in vanilla, so you can
briefly see an unloaded world. The connection screens ("Joining world", reconfiguring) and the startup loading overlay are
not touched. Off by default.

</details>

<details>
<summary><b>General settings: Custom Window Icon, Discord</b></summary>

<br>

**Custom Window Icon** (off by default) swaps the game window and taskbar icon for the GeilerAddons logo and restores the
vanilla icon when switched off. It is skipped on macOS and on Wayland, where the icon cannot be set safely. **Open Discord**
opens the official server after a link confirmation.

</details>

<details>
<summary><b>Aliases</b> — type a short word, send a command</summary>

<br>

Type an alias in chat and the server receives its command instead: the alias `dh` set to `warp dh` sends `/warp dh`.
Add an alias with the **Alias** and **Command** fields and **Add Alias**; each saved alias has its own switch, editable
command and **Delete**. The whole message must equal the alias (case and a leading `/` are ignored), the command may be
typed with or without a slash and is always sent as a command, and a replacement is never aliased again. Up to 128
aliases are stored in `config/geileraddons/aliases.json`.

**Built-in Aliases** (one switch, on by default) send Hypixel dungeon queues without Odin: `f1`–`f7` run
`joininstance CATACOMBS_FLOOR_ONE`–`CATACOMBS_FLOOR_SEVEN`, `m1`–`m7` run `joininstance MASTER_CATACOMBS_FLOOR_ONE`–`…_SEVEN`,
and `t1`–`t5` run `joininstance KUUDRA_NORMAL`, `KUUDRA_HOT`, `KUUDRA_BURNING`, `KUUDRA_FIERY`, `KUUDRA_INFERNAL`. They are
not stored in `aliases.json`, cannot be deleted, and a saved alias cannot reuse their names. The whole module must be
enabled for them to work.

</details>

<details>
<summary><b>Instance Requeue</b> — requeue automatically after a dungeon run</summary>

<br>

Sends `/instancerequeue` after **Requeue Delay** seconds (default 2, 0-30) once the run's `> EXTRA STATS <` line appears.
**Disable On Leave/Kick** (on by default) skips the requeue after you leave or are kicked from the party, including
during the delay; the next run end requeues normally again. **Announce Kick** (off) sends `/pc I was kicked!` when the
server kicks you from an instance. Changing world clears a pending requeue.

</details>

<details>
<summary><b>Trail</b> — a fading line at your feet</summary>

<br>

Draws a line behind you while you move. **Trail Color** (with alpha), **Trail Range** (how many blocks of path stay
visible, default 8), **Trail Decay (ticks)** (how long a point lives and fades, default 40), **Trail Density** (points
per block, default 4), **Trail Width**, and **Show In First Person** (on). A teleport restarts the trail, and at most 512
points are kept.

</details>

<details>
<summary><b>Item Rarity Backgrounds</b> — color the slot behind SkyBlock items</summary>

<br>

Colors the slot behind each SkyBlock item by rarity in inventories, containers and the hotbar. The rarity is the
rarity word found in the item's lore (checked from the bottom line up); pets use their stored tier, one tier higher with
the tier boost item. Settings (off by default as a module): **Rarity Backgrounds**, **Legacy Rarity Colors** (plain chat
colors instead of the SkyBlock palette), **Background Style** (Square or Circular), **Background Opacity** (50% by
default), **Jacob Medal Backgrounds** (the contest medal bracket color) and **Attribute Item Backgrounds** (red behind
items that carry attributes). Per-item results are cached and cleared every five minutes.

</details>

<details>
<summary><b>Invincibility Timers — Show On Item</b></summary>

<br>

With **Show On Item** on, the Bonzo's Mask and Spirit Mask items show a half-transparent green fill over their slot while
that mask's cooldown runs. The fill covers the whole slot at the proc and shrinks to nothing as the cooldown ends; a ready
mask shows no box. It works in the inventory, containers and the hotbar, and uses the same timers as the HUD.

</details>

<details>
<summary><b>No Cursor Reset</b> — keep the cursor when menus chain</summary>

<br>

When a container opens right after another screen closed (as SkyBlock menus do), the cursor stays where it was instead of
jumping to the center. **Unhook Timeout** (default 150 ms, 0-1000) is how long after the last screen activity the cursor is
kept. Only container screens are affected.

</details>

<details>
<summary><b>Dungeon Mob ESP</b> — highlight starred mobs and minibosses</summary>

<br>

One module keeps independently switchable **Starred Mobs**, **Minibosses**, and **Fels** settings
groups; their group-header switches work without opening the settings page. The existing text-based
**Mob Highlight** remains unchanged. All groups share one **Depth Check** control, which starts off,
so highlights and the Miniboss tracer render through walls by default. The dungeon groups retain their
existing pre-boss stage gate. Fel highlights also stop when the current room is cleared. **Fels Only
Current Room** checks the confirmed room both when collecting and drawing Fels. A Fel is tracked as an
Enderman when it appears; if starred, its highlight starts with that first spawned body.

Starred detection reads ArmorStand name metadata after the client applies it and rechecks loaded labels
every five ticks. A star must appear after the first visible name character. The classifier recognizes
Shadow Assassins, Fels, Skeleton Masters, Withermancers, Lords, Zombie Commanders, Super Archers,
Adventurers, Angry Archaeologists, King Midas, and a generic starred-mob fallback. It tries the body at
entity ID minus one, or minus three for Withermancers. If that body is unavailable, it accepts only one
unambiguous living body directly below the label. Missing bodies remain pending; matches are
deduplicated and cleared on despawn or world change. It does not force-load chunks.

Miniboss detection uses exact player-profile names for Shadow Assassin, Lost Adventurer, Diamond Guy,
and King Midas, regardless of star state. It also recognizes a loaded player entity from the
`SILENT_DEATH` held-item ID or dyed-boots RGB `6029470`, independently of UUID version. That equipment
match excludes the local or verified party identities. Starred nameplates also classify Shadow Assassins, Adventurers,
Angry Archaeologists, and King Midas as miniboss targets. Shadow Assassin nameplate identity is
recognized even when its label has no star marker; star evidence is recorded separately. **Only Starred
Minibosses** filters ordinary minibosses, while Shadow Assassin identity remains eligible without a star.
The Shadow Assassin uses
a full 0.8×2-block box, and its miniboss tracer is on by default. Starred and Miniboss targets already
loaded by Minecraft have no additional mod-side distance cutoff, including remembered 2D boxes. The
existing range settings remain relevant to
Fel and optional appearance-capture scans, not packet-classified Starred or Miniboss targets. Each target
group's **Only Current Room** filter remains off by default. The Starred and Miniboss groups keep their
style, outline/fill colors, and ring controls under **Appearance**; ring controls appear only when
**Ring** is selected.

While **Dungeon Mob ESP** is enabled, its session log is written automatically to
`logs/geileraddons/<session timestamp>/Dungeons/Dungeon_Mob_ESP.log`; no debug switch is needed. It
records bounded packet-classification, body association, profile match, room filter, and tracer
submission counts, an entity census, and world/HUD render status, coalesced at 20 ticks with a 100-tick
unchanged-state heartbeat. The census samples at most 8,192 rendered entities and stores at most 32
sanitized candidate rows; it omits raw entity/profile names, UUIDs, and coordinates. These records show
what the client matched and submitted, not whether Hypixel accepted an action or the renderer displayed
pixels. Attach a screenshot and the session log when reporting a missed target.

With **Highlight Stationary Fels** enabled, every resolved stationary Fel with a nearby **Fels** name
source gets an independent highlight; no starred-mob sighting is required. Choose its own **Fel Style**,
outline/fill colors, outline width, ring controls, and tracer color/width in the **Fels** group. The
marker follows the reference's nominal approximately 0.6-block waypoint bounds, rather than the Fel
body's full hitbox. A moving Fel uses the existing Starred style only when its nameplate has a star.
Fel range follows **Starred Range**.
Current/max health labels (for example `16k/25k❤`) and supported dungeon level,
attribute, star, and private-use glyph prefixes are normalized before matching the exact miniboss/Fels
roster, including labels such as `Healthy Fels`.

Moving Fel detection still depends on a `Fels` name source being present and associable with its living
body. Stationary Fel markers are recognized independently by their exact player-head texture, but
that marker signal does not identify the moving body. Whether Hypixel exposes a moving Fel's name
before it moves remains unverified in-game. The automatic session log omits raw skull texture and
equipment data.

The separate opt-in **Capture Miniboss Appearances** setting records bounded appearance candidates
to the local Dungeon Mob ESP session log for later human review. While enabled during dungeon play,
it automatically captures a player-profile skin when an exact known miniboss profile name confirms
the entity, or when a recognized miniboss nameplate is uniquely correlated to that player body.
Ambiguous body associations, conflicting labels, unknown labels, yourself, and current party members
are skipped. This does not infer star status. No crosshair targeting or per-sample label entry is
needed. A record contains the label, profile name, entity ID, tick, capture source, a SHA-256
signature of a validated Minecraft skin texture identity, and a normalized skin model. The capture
file to review or send is
`logs/geileraddons/<session timestamp>/Dungeons/Dungeon_Mob_ESP.log`. Records omit profile UUIDs,
signed profile payloads, texture URLs, and image data; nothing is sent to a remote service. Duplicate
records are suppressed and capture stops at 64 unique records per client session. Capture
observations are never imported automatically. Human-reviewed player-type miniboss signatures can be added to
`config/geileraddons/miniboss-appearance-signatures.json`; its empty template is created on first
profile packet and is loaded on the next client start. Each entry must include the signature, skin
model (`classic`, `slim`, or `unknown`), and one exact built-in miniboss label (`Shadow Assassin`,
`Lost Adventurer`, `Diamond Guy`, `Angry Archaeologist`, `Frozen Adventurer`, or `King Midas`). Set
`reviewed: true` only after human verification; every row also needs source, source reference, license,
and evidence text. The loader accepts at most 128 entries and 64 KiB, rejects duplicate signature/model
pairs or malformed files, and ignores valid entries with `reviewed: false`. Exact recognized profile
names keep precedence over this file. Unmatched signatures stay unmatched, and skin matching never
classifies a moving Fel. Capture logs and the matcher catalog are separate files; a capture must be
reviewed and copied into the catalog by a person before it can change matching.

Example catalog entry (replace the example hash and evidence with a reviewed capture):

```json
{
  "schema": "geileraddons-miniboss-appearance-catalog",
  "version": 1,
  "profiles": [
    {
      "signature": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "skinModel": "classic",
      "label": "Frozen Adventurer",
      "reviewed": true,
      "source": "GeilerAddons capture",
      "sourceReference": "local session log, reviewed entry 12",
      "license": "user-local",
      "evidence": "Visually confirmed as Frozen Adventurer in an F7 run"
    }
  ]
}
```

To enable automatic collection, enable **Dungeon Mob ESP**, then open its
**Automatic miniboss appearance capture** settings and turn on **Capture Miniboss Appearances**.
Samples are appended during dungeon play to
`logs/geileraddons/<session timestamp>/Dungeons/Dungeon_Mob_ESP.log`; send that file for review.
**Optional Manual Appearance Label** and **Capture Crosshair Player Profile** remain available as a
manual fallback for a player-type NPC. Neither method captures a moving Fel's Enderman body.
The capture format is not the matcher format and has no automatic promotion path. A person must
verify a captured identity, then create a reviewed catalog entry with explicit provenance; player
presentation profiles remain separate.

Remembered mobs remain highlighted at their updated position while the entity is alive and stays in
the detected connected room, even if its name match or room snapshot briefly drops. The footprint is
retained while the player remains inside it; a confirmed room exit, death/removal, room clear, or
expiry clears the target. When the
entity's anchor chunk unloads, its last bounds are retained for up to ten seconds.

</details>

<details>
<summary><b>Block ESP</b> — highlight registered block types</summary>

<br>

Create up to 32 entries, each selecting exactly one registered block. The entry's **Block: ...**
action shows the selected registry ID and opens a picker that filters both readable names and
technical IDs, so you do not have to remember unusual block names. Each entry has independent **Box**,
**Fill**, and **Outline** switches, depth check, label, tracer, island list, and
**Connect Touching Blocks** option. Connected blocks of the same type share one exposed voxel
shape, label, and tracer; face-adjacent blocks connect, while edge and corner contact does not.

New entries start with every island off. Scans are centered on you and use the full effective render
distance by default. Turn on **Use Custom Range** to give one entry its own radius (32 blocks by
default); it is still limited by render distance. There is no match-count cap. Scanning advances in
bounded, distance-ordered batches over loaded chunks only, skipping sections whose block palette
cannot contain the selected type—no chunks are loaded and no network requests are made. The last
complete highlight stays visible while a refresh runs, avoiding the clear-and-pop flicker of a full
rescan. **Manage Folders** organizes entries in its own nested tree.

</details>

<details>
<summary><b>Pest Highlighter</b> — highlights every Garden pest</summary>

<br>

Recognises Garden pests from the textured player heads Hypixel puts on their ArmorStands, so it
does not depend on a name tag or a hard-coded list of positions. It covers every known pest
texture, including the Earthworm and Firefly variants.

The module only runs in the Garden. Box, animated ring mode, tracer and the `Pest: <type>` name
can be switched independently. Ring mode supports several phase-shifted rings and the tracer
selects the camera-facing point on the nearest moving ring, so it remains attached while the
animation runs. The box has separate outline/fill colours, alpha and outline width; rings have
their own colour, alpha, width, radius, count and speed. Depth Check applies to all world markers,
while the projected name remains a normal HUD label. HP is intentionally not shown because the
head marker does not provide reliable health data.

</details>

<details>
<summary><b>Infested plot</b> — fixed 3D boxes around Garden plots</summary>

<br>

Draws fixed world-space 3D boxes for Garden plot status: confirmed infested, confirmed clear, or
unknown because evidence is missing or stale.

**Where the plot data comes from.** A complete, valid **Pests** tab-list snapshot is Garden-wide
authority: listed plot IDs are infested, and plots omitted from a complete snapshot are clear. Enable
the widget in game (`/widget` → Pests). An absent, incomplete, or unreadable read does not clear
existing evidence; evidence older than **Data Max Age** becomes unknown. An explicit whole-Garden
no-pests confirmation also clears the grid.

Plot-specific observations update only the exact plot they identify. A parsed spawn message with a
numeric plot ID, a clean-plot confirmation with that ID, or a current-plot scoreboard count changes
only that plot. A Garden-wide pest total is diagnostic context only. Nearby pest entities never assign
plot status.

**Labels and borders.** Confirmed infested borders and their labels use a Garden-wide range that
covers every plot from anywhere in the Garden, including when the plot's chunks are beyond the
client's chunk render distance. A temporary extended projection prevents the normal far plane from
clipping distant 3D borders; it is restored immediately after those borders are drawn. An infested
label shows `Plot <id>` and adds the exact pest count when
known; unknown counts are omitted, and **Show Labels** controls whether labels appear. Confirmed-clear
and unknown borders remain optional through **Show Clear Plots** and **Show Unknown Plots**; unknown
borders stay dashed. **Depth Check**
remains available for world-space borders; labels are projected HUD text.

**The boxes stay put.** Each border is a world-space 3D box drawn between a fixed world height and a
chosen wall height, so it is a landmark you can navigate by — it does not ride your eye level while
you walk or jump. The floor is captured automatically once the player is standing on something in the
Garden; **Capture Current Y** sets it manually, and **Wall Height** controls the box height. Clear and
unknown boxes retain their bounded local render range.

Bounded diagnostics run automatically while **Infested plot** is enabled; see the [diagnostic guide](docs/debugging.md).

**Island detection has to be on.** Like every island-gated feature, this one needs the official
Hypixel Mod API, which is **Miscellaneous → General → Island Detection API**. The mod never guesses
your island from the scoreboard or the terrain, so if that switch is off the module says so in chat
rather than doing nothing in silence.

</details>

<details>
<summary><b>Macros</b> — build clear, randomized input workflows</summary>

<br>

Create a macro in the **Miscellaneous** category and open its workflow editor. The editor is titled
**Macro Editor** and groups nodes into **Actions**, **Control**, **Player Input**, **Inventory**,
**Display**, **Data**, **Reusable**, and **World**. The Inspector is a separate panel on the right of
the canvas. Scroll over the canvas to zoom around the pointer; the palette and Inspector keep their
own list scrolling (the block list scrolls under the search box and category chips). Drop a block on the palette to delete it; Ctrl+Z restores it. Long node text expands the node to show the full value, and the canvas can pan to
reach that width. Zoom ranges from 1% to 100%; saved values migrate proportionally from the former
range and clamp at 100%. Blocks look and connect like Scratch, with smooth antialiased edges at any GUI scale
and zoom: each block hugs its text, stacks flush
under the one above with a notch-and-bump joint, and shows values as pills (white for typed values,
darker with ▾ for choices) and conditions as hexagons; editing still happens in the Inspector. Event
hats have a domed top. Drag previews show a ghost block at the proposed drop location. If/Else, Repeat
and Switch are C-shaped blocks whose arm wraps their child blocks: If/Else has an **else** bar for its
optional second branch, Switch shows one `case` bar per case plus **otherwise**, Repeat shows a loop
arrow, and **repeat forever** has a flat bottom. Repeat selects count, forever, or until mode in one
node. Drop a block on the top half of a C-block's bottom bar to append it inside, on the lower half to
place it after the block. Select a branch (click its gap or bar) before adding steps there.
Comment blocks can snap into a stack, and detached square notes can still be placed anywhere without
joining the macro chain.

Actions include one **Send Message** node for chat and commands (a leading `/` runs a command),
captured keyboard/mouse/hotbar input in one **Press or Hold Key** node, **Scroll** for a menu or
hotbar, slot-id clicks, item-name clicks, Escape, **Title**, and **Sound**. Search the palette for
terms such as “mouse” or “hotbar” to find Scroll. A Title step displays centered text with a chosen
game font and size, text color, optional background, and independent fade-in/hold/fade-out times. It does not
pause the workflow, and a later Title step replaces the title already on screen. A Sound step
chooses a registered game sound and plays it at its default volume and pitch. Key nodes use a
capture button and readable key name rather than an ID. Click Item supports
comma-separated names as alternatives, exact or partial matching, search scope, match number, mouse
button, and shift-click. A Hold key samples a randomized duration between its minimum and maximum,
separate from the node's pre-delay. Shift, Control, Alt, and Super can be captured as the key itself.
Wait uses an independent randomized range. **Repeat** can run a body a set number of times or forever;
**Repeat Until** checks its stop condition before each iteration and runs its body while the
condition is false. For example, Repeat Until with an **Item → Missing** condition and a comma-
separated partial-name list can click any matching item until none remain, with a delay on the Click
Item node. Conditions can use item present/missing, exact/partial matching, screen, slot, chat, and
world state, variables, and Hypixel state, combined with AND, OR, and NOT. The **Inventory item
count** condition compares matching carried stacks, including armor and offhand; it does not count
items in open containers or sacks. Wait Until keeps waiting until its condition becomes true,
including while required client data is unavailable. Its chat checks scan the bounded history for a
matching line received after the wait began; numeric Switch cases treat `1` and `1.0` as equal.
Item conditions with **Player Inventory** scope also work when no menu is open.
**Call another macro** waits for that macro's **On Call** stack and can carry its own condition:
with one set, the call is skipped when the condition is false and the workflow continues with the
next block, so a gated call never has to be wrapped in an If/Else.

Each macro node category has its own editable color in **Macros → Macro Node Colors**. With
**Theme → Theme Macro + Inventory Colors** enabled, those category colors are tinted toward the
selected theme while staying distinct; turning it off restores the editable colors.

**Hypixel state conditions (unreleased).** These compare locally observed island, confirmed
Catacombs floor, party membership/size/leader/member, and Garden pest status or count for the current
or a selected plot. They use existing client snapshots and make no extra network request. Missing
or stale data stays unknown. If the complete condition cannot be resolved, the workflow stops with
an unavailable-data message instead of treating the value as false or zero.

**Player conditions and Set View (unreleased).** The **Player view, position or block** condition (If/Else,
Wait Until) checks where you look (**Yaw**, **Pitch**, equal within a tolerance in degrees, default 5; yaw goes
the short way round, so 179 and -179 are 2 degrees apart), your block coordinate (**X**, **Y**, **Z**), the block
you stand on (**Standing Block**) or the block at an `x y z` position (**Block At**). Block ids may leave out
`minecraft:` (`stone`, `Dirt`). A block in an unloaded chunk, a bad number or a bad position is unavailable and
stops the workflow instead of reading as air or zero. **Set view angle** (Input category) turns to a yaw and pitch
with the Rotation Engine at the speed set there and ends once aimed (within half a degree) or after its time limit.

**Chat event stacks.** Besides a hotkey and a world area, a macro can have a stack that fires from a
received chat line: give it the text to look for and it starts whenever a line contains it (or
matches the whole line, if you switch that off). Matching ignores Minecraft formatting codes and
case, so a rank or channel prefix in front of the message does not matter, and action-bar lines count
too. Each chat stack has a **minimum repeat delay** so a repeated line cannot restart it constantly,
and it will not react to its own message being echoed back by the server. Chat stacks do not run while
you are typing: **General → Chat Triggers In Text Screens** overrides that, and
**Macros → Replay Last Blocked Macro** starts whichever stack was most recently refused. A chat
stack can also be given a **replay hotkey** of its own, which starts it by hand at any time.

World Switch destinations are selected from a dropdown of named islands. Island names cannot be
typed manually. The run waits while the destination is unresolved, completes the switch when that
island is detected, and stops if a different known island loads. A macro without a World Switch node
keeps running through a lobby change (for example `/is`, Wait, `/warp canyon`); it waits up to 15 seconds for the island to be
reported. A macro can also be restricted to selected islands; that list decides whether the macro may start, and a run that
has already changed world is not stopped by it and can run in the world, containers, or any non-text
screen.
Each node has a labeled minimum and maximum pre-node delay in milliseconds; there is no hidden
macro-wide default delay.

Event stacks run independently: a macro's key, world-area, chat and `On Call` stacks each have their
own run state, concurrent runs are supported, and re-entry is prevented per stack rather than per
macro. Starting another stack does not replace one already running; pressing a stack's own hotkey
while it runs is ignored. Duplicate hotkeys are rejected with a client-side warning. Use the
**Stop Run** node or the editor's **Stop Run** control to cancel an active run and release its held
input; disabling a macro also stops its active runs. A missing slot or item is retried for five
seconds, while Wait Until and World Switch can wait indefinitely. Click **Set hotkey** and release a
key to save it; **Escape** clears it, and the editor shows a visible listening banner while capturing.
The **Help** button gives step-by-step recipes for loop behavior, condition composition, modifier-key
capture, randomized holds, and the dropdown-only island rule. The `/ga` command is registered locally,
so Brigadier can suggest the command and its `dstats` subcommand while typing. Per-launch diagnostics
are available under `logs/geileraddons/`;
macro runtime and editor diagnostics are automatic while Macros are enabled or the editor is open.
See [Debugging and diagnostics](docs/debugging.md) for their contents and other diagnostic switches.
Log sessions are retained rather than deleted automatically.

In macro text fields, use **Ctrl+A** to select all, **Ctrl+C**/**Ctrl+X** to copy or cut a selection,
and **Ctrl+V** or **Shift+Insert** to paste. You can also drag to select text and use the same
shortcuts in searchable picker fields.

The **Enable Macro System** toggle is the master switch for all macro hotkeys and running
workflows; each macro also has its own **Macro Enabled** toggle. **Share Macros** opens a list of every
macro with only its name and a switch; switch on the ones to share and press **Copy** at the top to put them on
the clipboard as a portable package (with the macros and functions they call). **Paste Macros** appends
packages as new macros without overwriting existing ones. The current source writes the unreleased
transfer format v8, which preserves unconnected editor blocks; v1–v7 packages remain importable,
with fields they do not contain using their defaults. **Manage Folders** provides a separate nested
macro tree.
Folder names may repeat because UI state is keyed by stable folder ID; deleting a non-empty folder
promotes its entries and child folders to its parent instead of deleting them.

</details>

<details>
<summary><b>Inventory Buttons</b> — run macros or send text from the player inventory</summary>

<br>

Open **Visual → Inventory Buttons → Edit Layout** while in a world. The faint 18×18 grid follows
the vanilla inventory slot lattice and only exposes fully visible cells outside the inventory and
any open recipe book. Click an empty cell to choose an existing macro or create one; after editing a
new macro, its button returns to that original cell. Select a button to edit its appearance, custom
hover tooltip, and macro. Drag it or use **Move** and click a destination; **Delete** or
Delete/Backspace removes it. Saved placements are not silently rearranged if a GUI size changes:
invalid cells are marked in red, and **Reflow** explicitly moves them to the nearest free exterior
cells while reporting any that could not fit.

Buttons can use a registered item/block icon, short text, or a PNG selected from
`.minecraft/config/geileraddons/inventory-button-icons/`. The editor shows a live icon preview and
the hover tooltip; at runtime the tooltip also shows the macro name and any eligibility reason.
Choose **Text action** when a button should not start a macro. Enter `/gfs ender_pearl 16` to send
that command, or enter text without `/` to send an ordinary chat message. Text actions save with the
button placement and remain independent from the macro catalog.
Only the standard player inventory is supported. When not editing, an unassigned placement is
hidden. If its macro is deleted, the placement remains so it can be rebound.

Buttons store the macro's stable numeric ID and use the macro's enable switch, trigger context,
island filter, and the Macro System master switch. Ineligible buttons stay visible but disabled,
show the reason on hover, and consume clicks so an inventory slot underneath cannot be activated.
Their layout is local and is not included in shared macro packages.

</details>

<details>
<summary><b>Tree Tracker</b> — gift counting for Helix, Fig and Mangrove</summary>

<br>

Reads the gift message out of chat and keeps score, so you can tell whether a spot is actually worth farming.

**The panel** shows gifts per hour, the running count, and how long you've been at it — for whichever tree gifted last, so it follows you around without any switching. It appears when a gift lands and takes itself off screen once you stop, so it isn't sitting there while you're doing something else.

**It knows when you stopped.** After a set idle time the clock freezes and the panel hides. The next gift picks up where it left off — same session, nothing lost. Time spent walking somewhere else doesn't count against your rate. The timeout is yours to set.

**Session and all-time.** A session runs until you reset it or restart the game; either way its gifts roll into the all-time totals rather than disappearing. Flip the panel between the two.

Drag the panel wherever you like with **Move Elements** in the menu.

</details>

<details>
<summary><b>Tree Broken Notifier</b> — a title when a tree comes down</summary>

<br>

Flashes a title across the screen when you fell a tree or one gives you a gift — so you can keep your eyes on the trees instead of on chat.

**Two separate titles.** Gifts get one, **TIMBER!** and **PETALFALL!** get another, each with its own wording, sound and pitch, so you can tell them apart without reading. They really are separate events — a felling can happen without a gift and can fire more than once for one tree — and each has its own switch if you only want one of them. A gift block that names its tree on several lines still only shows one title.

Alerts appear in Minecraft's fixed subtitle position, alongside standard titles. Duration and volume are shared; text size is controlled by Minecraft. Write `{tree}` anywhere in either message and it becomes Helix, Fig or Mangrove.

**Hide the chat spam.** Each side can suppress its own message: the whole gift block — bars, header, rewards line and all — or the TIMBER!/PETALFALL! line. Hidden gifts are still counted; hiding only affects what you see. Off by default.

</details>

<details>
<summary><b>Foraging Highlights</b> — Sea Lumies, Ruby Veilshrooms and Honeyhives</summary>

<br>

Boxes the things worth picking up on the Foraging islands, through walls, so you can find them in foliage.

**Sea Lumies** (Moonglade Marsh) are sea pickles, and a lone pickle is rarely worth the walk, so **Minimum Pickles** (default 3) only boxes the clusters. **Ruby Veilshrooms** (Torrhus Canyon) are boxed wherever they grow. **Honeyhives** (Torrhus Canyon) are boxed once they are full of honey; the hives that hang under a birch fence belong to protected trees and are left out. Full Honeyhives also get a waypoint: a label with the distance above each one.

Each target has its own switch and colour. The scan covers the chunks around you (up to eight) once a second and boxes the nearest 256 of each kind, and each target only works on its own island.

</details>

<details>
<summary><b>Temple Puzzles</b> — Forest Temple and Desert Temple</summary>

<br>

Solves the two temple puzzles on the Foraging islands. Each has its own switch.

**Forest Temple** (Moonglade Marsh): the floor terracotta has to match the wall. A number above each floor block says how many clicks it needs: **+2** is two right clicks, **-1** is one left click, and a block that is already right shows nothing.

**Desert Temple** (Torrhus Canyon): numbers the coloured buttons in the order to press them (the colour with the fewest glass blocks on the floor first), and draws an arrow from each statue to where it goes. The glass floor is read every five seconds, and the numbers go away once the door is open.

Both only draw while you are near their temple.

</details>

<details>
<summary><b>HotF Tree Helper</b> — the Heart of the Forest menu</summary>

<br>

The Forest counterpart of the HotM Tree Helper, for the Heart of the Forest menu. Everything has its own switch.

**Slots:** **Level On Slot** writes each perk's level on its slot (gold once it is maxed), **Highlight Perks** colours the slot behind it (green enabled, red disabled, gray locked) and **Tokens On Heart** shows your tokens on the heart.

**Tooltip:** hover a perk to see **Whispers Spent** (and the share of the total), **Whispers To Max**, **Whispers For Next Levels** (default 10, cut down to the levels the perk can still take) and **Whispers You Have**. They sit under the perk's cost line and use the currency that line names, **Forest Whispers** or **Desert Whispers**, so a Desert perk shows Desert numbers. Abilities and other perks with no whisper cost add nothing. Both amounts you hold are read from the heart item, so they are only known while the menu is open.

</details>

<details>
<summary><b>Tuner Solver</b> — the Tune Frequency menu</summary>

<br>

Puts the number of clicks on the colour, speed and pitch buttons of the Tune Frequency menu: **+3** is three right clicks, **-2** is two left clicks, and the number counts down as you click.

The target colour is the pane in the top row. The target speed is measured from how quickly that pane moves, so the speed button needs a moment before it shows a number. The target pitch is heard from the bass notes the menu plays; five notes at the current pitch mean it is already right.

</details>

<details>
<summary><b>Sweep Overlay</b> — see which logs the next swing cuts</summary>

<br>

Boxes the logs your next axe swing will cut. The count comes from your **Sweep** stat, which the mod reads from the tab list (if the overlay stays empty, make sure Sweep shows in your tab list with `/tablist`), and from the kind of tree: Fig, Mangrove and Helix each need different amounts of Sweep, and Hub and Park trees cut one log plus one per Sweep point, up to 36. Helix trees are cut one colour at a time, and the overlay follows that.

**Thrown Axe Overlay** does the same for the throwable axes: it follows the axe's arc up to 50 blocks, boxes the tree it will land in, and shows half as many logs in a dimmer colour. Works on the Foraging islands, the Hub and your Private Island. **Depth Check** (on by default) hides boxes behind terrain; the default colour is faint (alpha 24) and the box list is rebuilt every ten ticks.

</details>

<details>
<summary><b>Contest Timer</b> — a countdown to the next SkyBlock day</summary>

<br>

An always-visible, movable HUD element counting down to the next SkyBlock day, which starts every 20 real minutes. Useful for timing Safari critter tickets. Drag it with **Move Elements** in the menu. **Display** switches between the next-day countdown and Agatha's Contest, read from the tab list's Agatha's Contest widget (the day clock shows while that widget is absent). The day clock follows the SkyBlock epoch of the Hypixel wiki; the wiki gives no contest start time, so the contest text is whatever Hypixel's own widget says.

</details>

<details>
<summary><b>Theme</b> — one look for the whole mod</summary>

<br>

Everything the mod draws — the menu, the tracker panel, the notifier — reads from a single theme, so there's one place to change how it all looks.

Five colours define it: background, border, accent, text and muted text. Every hover tint, card fill and slider is worked out from those, so you can't end up with half of the GUI on the old scheme. Text sitting on the accent flips between black and white on its own, so a pale accent doesn't leave unreadable labels.

**Icons → Icon color** is a saved RGB tint for approved monochrome module-card icons. It starts at
the Theme Text color; selecting a preset updates it to that preset's Text color, and you can edit it
afterward. All module-card icons use this tint; the General and Theme toolbar buttons keep their
dedicated toolbar artwork and colors.

**Click GUI → Style** picks the menu layout. **Modern** is the sidebar with module cards. **Classic** shows one column per category with its modules listed below
(blue = on), a search field at the bottom centre with the GitHub, General settings, Theme settings and Move HUD elements buttons beside it, and a button
to switch between the Modules and Macros workspaces. In Classic, left-click toggles a module; right-click (or left-click on a module without a switch) opens its
settings in the Modern layout, and Esc or Back returns to the columns. Scroll over a column to scroll it; typing searches. Open Theme settings from the Classic
toolbar to change the style back.

Nine presets to start from: **Tracker** (the flat translucent look, and the default), **Amethyst** (the original purple), **Midnight**, **Forest**, **Aurora**, **Ember**, **Orchid**, **Ice Glass**, and **Rose Glass**.

In-world colours — waypoint states, solver directions, device highlights — stay separate. Those are signals, not decoration, and a theme has no business repainting them.

</details>

<details>
<summary>Using the click GUI</summary>

<br>

- Modules are cards. Click the card to open its settings; the **switch** and keybind control keep their own actions. Each module has a bundled icon; **Theme → Icons → Icon color** tints approved monochrome card icons.
- The left rail shows only the ten main categories. Inside a category, module subcategories appear as titled sections with a divider above their cards: **Dungeons** has **F7**, **M7**, **Highlight**, **Room / Map**, and **General**; **Hunting** has **Critter Safari** and **General**, with **Tiki Helper** under General; **Visual** separates **Player & Camera**, **Highlights & Effects**, and **Interface**; **Miscellaneous** separates **Chat & Party**, **Utilities**, **Inventory**, **Player**, **World**, **Display**, and **Fix**.
- Smooth heart icons on categories and module cards mark favorites. Favorite categories and modules
  sort to the top, with multiple modules ordered alphabetically. The fixed GitHub, General, Theme, and
  Move HUD Elements toolbar buttons are not favoritable; their icons stay centered in their buttons.
- On compact cards, the heart and enable switch have separate non-overlapping click targets, and the heart, switch, and keybind align on one control column. Descriptions are larger while card dimensions stay the same.
- Settings are grouped into sections you can fold shut. It remembers which ones you closed.
- Colours open a proper picker: drag the square for shade, the strip under it for hue, the one below that for transparency, or type a hex code straight in.
- Small numeric ranges (maximum 50 or below) are draggable sliders with a live readout. Larger
  ranges use a direct text input so values such as delays and scan intervals can be entered
  precisely; press Enter to apply them.
- In **Dev**, **Debug** controls diagnostic logs and **Slot IDs** independently adds the runtime
	container slot index to each slot as a small badge with configurable label text color.
- **Move Elements**, at the bottom of the category list, opens a screen where you choose **All** or a populated module category from the compact selector at the bottom center. Its options open above the selector and scroll when the screen is short. Drag panels to move them and scroll over a panel to resize it; positions are kept as a share of the screen, so they survive resolution and GUI-scale changes. Previews use each HUD element's own styling and current data when available, with a representative sample otherwise. The editor adds no filled card behind them, though a HUD element's configured background still appears. Elements of disabled modules are hidden, and right-clicking a panel opens its module page. Moving the Slot IDs sample preview does not move the actual per-slot badges. Title alerts use Minecraft's fixed subtitle position and are not movable.
- The panel scales to your screen and reopens wherever you left it.

Folded sections and macro/function list states are remembered while the game is open, then start
collapsed after a restart. The compact category rail, module grid, and settings list each scroll inside
their own clipped area; the wheel scrolls the area under the pointer. The compact rail contains the nine
top-level categories. Subcategory headings and their module cards scroll together inside the main grid.
Module cards use four columns on wide windows (three, then two on narrower ones). A row of subcategory chips
(**All** plus that category's subcategories) sits above the grid and shows only the chosen subcategory; the choice
is remembered until the game closes, and searching always looks across every subcategory.
Category, per-category grid, and settings-list positions persist.

The header shows the enlarged mod logo with the current version to its right; player name and skin
displays are removed. Search remains the same width with a thinner field. Category labels have no
icons. The GitHub, General Settings, Theme, and Move HUD Elements controls use official Primer
Octicons. The
module grid fits three smaller cards per row when wide and two when narrow; descriptions use a more
readable scale. The General Settings and Theme modules have no module cards; their toolbar buttons
keep opening their settings. The rounded outer
outline follows the panel edge, while its category and header dividers remain straight and inset.
The settings screen retains the category rail, follows the same navy-and-cyan styling, and has a
visible Back button in its header; press Escape to return as well. **General → Show Availability Notices** hides
inactive-reason text from cards and tooltips without hiding module names or descriptions.

Macros, Mob Highlight, and Block ESP each have an independent nested folder tree. New and legacy
entries belong to the root until moved. Deleting a populated folder promotes its entries and child
folders to its parent; folder names can repeat because UI state uses stable IDs. Inventory Buttons
are a spatial layout, not a multi-entry folder list.

</details>

<details>
<summary><b>Garden and Slayer trackers</b> — cooldowns, rates, timers, and alerts</summary>

<br>

**Unreleased.** Fresh configs leave Crop Fever Timer off; saved enable choices are preserved.
It starts a 60-second countdown from the observed Crop Fever message and clears it on the cure message.
Its settings preview uses the same scale, text color, background, and opacity as the live HUD. Pest
Tracker reads the Pests widget cooldown and spawn chat, shows time since the last spawn and session or total pests
per hour, and estimates spawn intervals. Its held-item filter defaults to farming tools only. The
optional custom cooldown defaults off at 135 seconds (75 seconds with Finnegan); configurable
warnings can lead cooldown by five seconds. AFK exclusion uses crop-action gaps, defaulting to 30
seconds rather than treating a normal pest interval as idle. Spawn alerts, cooldown warnings, and
ready alerts default off; each enabled alert can use chat (default), title, and/or sound. The
`PEST_SPAWNED` macro event follows each parsed spawn message. `PEST_COOLDOWN_READY` fires once after
the widget shows COUNTDOWN then READY; when custom timing is enabled, it also waits for a parsed
spawn's custom deadline.
Chat-observed infested plots drive **Warp to Latest Infested Plot**. Set its keybind in the module
settings; the warp prefers another known infested plot when the player is already on one, and repeats
the current plot only when it is the sole target.

Slayer Tracker shows session or total bosses per hour. Boss and miniboss alerts live in the separate
**Slayer Alerts** module. The Garden and Slayer paths depend on live widget, chat, scoreboard, entity, and island
observations; they have not been verified in-game.

Five separate Slayer modules (all default off, none verified in-game) read the same nearby boss labels. They
stay off in dungeons, Kuudra, and outside SkyBlock. A boss is yours only when a nearby "Spawned by" label holds
your name, or, with no owner label in range, when it is the lone boss while your sidebar says "Slay the boss!";
other players' bosses never trigger a module's alerts, timers or tracers.

- **Slayer Alerts** alerts (chat, title, sound) when your boss spawns, with filters per boss type and editable text
  (`{boss}`, `{miniboss}`), and when a miniboss appears within 24 blocks while your quest is active. The miniboss
  alert shows only the miniboss name and skips ones already standing there when the quest became active.
- **Slayer Boss Highlight** boxes your boss and minibosses (highlights follow the entity smoothly between ticks),
  with fill/outline colors and a range of 8 to 64 blocks. Other players' bosses are off by default. **Hide Sven Pup
  Name Tags** is off by default.
- **Enderman Slayer** shows a title and sound when a Yang Glyph is thrown, boxes the landed beacon with a
  five-second countdown and a tracer from your crosshair, boxes Nukekubi skulls, and counts down the laser phase
  over the boss (client ticks, with a short grace if the boss label drops out of a scan).
- **Blaze Slayer** boxes the Inferno Demonlord's demons in the color of their shield attunement (Ashen black,
  Spirit white, Crystal blue, Auric yellow, Immune red) and shows the fire pillar label as a title for its last five
  seconds. While Slayer Boss Highlight boxes the boss, that box takes the attunement color; with it off, Blaze Slayer
  boxes the boss itself.
- **Vampire Slayer** warns on Twinclaws ice (with a delay setting), the Steak Stake marker, and when a Blood Ichor or
  Killer Spring appears, and draws boxes and crosshair tracers to those (and optionally the boss).
- **Slayer HUD** shows your own boss as a movable element: **Boss Bar** (green, yellow below half, red below a quarter),
  **Text Lines** (name, health and percent, fight time) and **Kill Time Message** (a chat line with the spawn and kill
  time when the sidebar says "Boss slain!"). The bar fills against the highest health seen in the fight, since the boss
  label shows no maximum. Spawn time is shown only when the quest was seen before the boss.

</details>

<details>
<summary><b>Slot Locking</b> — lock hovered slots and bind inventory slots to the hotbar</summary>

<br>

**Unreleased.** Hover a player inventory slot and press the configured **Lock Key** to toggle its
lock. A locked slot cannot be moved, swapped, or dropped. To bind a slot, hover an inventory slot,
hold the configured **Bind Key** (G by default), move to a hotbar slot, then release. The editor draws
the link between the two slots. Shift-clicking either linked slot swaps it using that hotbar slot's
number key; locked endpoints remain protected. Link colors, line style, slot protection, and saved
profiles are configurable.

</details>

<details>
<summary><b>Freecam, Fullbright, No Fog, Auto Sprint, Block Overlay, Scrollable Tooltip</b> — view and quality-of-life modules</summary>

<br>

**Unreleased.** All are off by default and client-side only.

- **Freecam** (Visual → Player & Camera): detaches the camera. While on, W/A/S/D, Space, Shift and the mouse fly the
  camera at the **Speed** setting; your player gets no movement or look input and stands still. The camera entity stays your
  player, so attacks, item use and the block you target keep using the player's own position and aim, and nothing is sent to
  the server. Bind a key to the module to toggle it.
- **Fullbright** (Visual): every light level renders fully lit.
- **No Fog** (Visual): removes fog: underwater and lava (**Remove Underwater Fog**, **Remove Lava Fog**), nether, overworld, end and effect fog (**Remove Nether, World And Effect Fog**) and render-distance fog (**Remove Render Distance Fog**). All four are on by default.
- **Auto Sprint** (Miscellaneous → Utilities): holds sprint while no screen is open. Vanilla still stops sprinting when you are hungry, blind or sneaking.
- **Block Overlay** (Visual): replaces the vanilla block outline with a configurable outline and fill, with Devonian's settings:
  Highlight Entity, Block Outline Color, Block Fill Color, Outline Line Width, Outline Wire Phase, Outline Fill Phase and
  Dynamic Phase (through walls only in first person). Defaults are the saved Devonian values: outline `#14FEEA`, no fill, width 3, outline through walls.
- **Scrollable Tooltip** (Miscellaneous → Utilities): over an inventory item, the mouse wheel scrolls its tooltip up and down,
  Shift+wheel sideways, Ctrl+wheel scales it and Alt shortens the step. Settings: Allow Horizontal/Vertical/Scaling,
  Tooltip Lock In Place, Tooltip Ignore Scale, Tooltip Reset (on a new slot), Tooltip Start At Top and a Reset button.
  Scale and offset are not saved between sessions.
- **Storage Overlay** (Miscellaneous → Inventory): Skyblocker's storage overlay. Opening `/storage`, `/echest N` or `/backpack N`
  shows every Ender Chest and Backpack page you have opened on one scrollable screen, with your inventory below it. Click a page to
  open it (this sends `/echest N` or `/backpack N`), click the open page's slots as usual (Shift-click moves items), and type in the
  search box to hide pages with no item whose name or lore matches and dim the other slots. **Home** opens the vanilla Storage menu
  (upgrades, skins) and **Hide** shows the vanilla screen for the current page. A page appears once you have opened it; the
  `/storage` menu adds empty placeholders for the pages it lists. The cache is saved per account (`config/geileraddons/storage-overlay/`)
  and loaded the first time you open a storage page, so pages you have opened before show after a restart. Placing
  or removing a backpack shows the vanilla menu once. Item Rarity Backgrounds also draw on the overlay's slots, and the vanilla chest background (with your own inventory) is hidden behind it. Settings: Style (Click GUI or Minecraft), Pages Per Row, Remember Search.

Diagnostics are automatic while enabled under `Visual/` and `Miscellaneous/`.

</details>

<details>
<summary><b>Hide Particles, No Render, Animation/Double Use/Disconnect Fix</b> — hiding effects and fixing server quirks</summary>

<br>

**Unreleased.** All are off by default and client-side only. Behavior follows NoFrills (no code copied).

- **Hide Particles** (Visual): **Hide All** (on by default, the old behavior), **Block Breaking** (the chips while mining) and
  **Explosions** (explosion, explosion emitter and gust particles, from the server or a local blast).
- **No Render** (Visual): one switch each for Fire Overlay, Food Bar, Armor Bar, Boss Bar, Effect Display, Recipe Book, Dead Entities
  (dead mobs and their health tags), Dead Poof, Lightning, Falling Blocks (not in the Rift), Entity Fire, Mage Beam (dungeons),
  Powder Coating, Guided Sheep (dungeons), Bone Plating (dungeons), Tree Bits (Galatea, Hub, Park), Nausea, Blindness (removes the blindness fog and darkening; the effect itself stays on you), Warden Darkness (removes the Darkness effect on your client), Exp Orbs and Stuck Arrows.
- **Animation Fix** (Miscellaneous): your own sneak and swim animation no longer plays twice. **Skyblock Only** limits it to SkyBlock.
- **Double Use Fix** (Miscellaneous): Blaze daggers no longer fire a second time when aimed at a block, and a fishing rod casts once.
  **Skyblock Only** limits it to SkyBlock.
- **Disconnect Fix** (Miscellaneous): corrupted entity-data packets (for example Tarantula slayer) are skipped instead of disconnecting you.

Diagnostics are automatic while enabled under `Visual/` and `Miscellaneous/`.

</details>

<details>
<summary><b>Hide Players, Hide F7 Boss Sounds, Boss Bar Percent</b> — quieter, cleaner boss fights</summary>

<br>

**Unreleased.** All are off by default and client-side only. Behavior follows Odin (Hide Players) and Devonian (the other two); no code copied.

- **Hide Players** (Visual): stops drawing other real players. **Hide All Players** hides everyone at any range; otherwise
  **Hide Within** (0-32 blocks, default 3) hides players that close. **Only Hide In Dungeons** limits it to dungeon runs.
  NPCs and yourself are never hidden, and a hidden player's name tag goes with them.
- **Hide F7 Boss Sounds** (Dungeons): in the F7 and M7 boss room only, drops sounds at volume 2 or louder plus the Wither's hurt and
  death noises. **Cancel Sounds Louder Than 1** also drops anything above volume 1; **Cap Loud Sounds At 1** plays those at volume 1
  instead of dropping them.
- **Boss Bar Percent** (Dungeons): adds the boss health to the boss bar name in any dungeon boss fight, for example `Maxor - 25.0%`.
  Dungeon bosses only; Kuudra is not covered.

Diagnostics are automatic while enabled under `Visual/` and `Dungeons/`.

</details>

<details>
<summary><b>Item Cooldowns</b> — cooldown numbers on ability items</summary>

<br>

**Unreleased.** Visual → Interface. Right-clicking an item whose lore has `Ability: <name>  RIGHT CLICK` and a `Cooldown: Ns` line starts
a timer for that item (the first such ability; the action bar's `-N Mana (<name>)` switches it to the ability that was really used on items with
several). The server's `This ability is on cooldown for Ns.` message sets the exact remaining time, and `You do not have enough mana` cancels a
timer that never started. Holding right click does not restart a running cooldown. **Mode**: **On Item** (a small number at the bottom right of the item, in the hotbar and in inventories),
**Box** (a movable HUD box with item icon and time) or **Both**. **Show Check When Ready** keeps a green check on the item and in
the box for **Check Stays (s)** seconds after it is ready. **Apply Mage Cooldown Reduction** shortens dungeon cooldowns for Mage.
Items are told apart by their SkyBlock id, so two copies of one item share a timer. If nothing shows, `Visual/Item_Cooldowns.log` counts every
reason a use was dropped (`noId`, `noAbility`, ...) and records the first action-bar texts that mention Mana but did not match.

</details>

<details>
<summary><b>Waypoints</b> — Skyblocker and Odin behavior</summary>

<br>

- **Waypoints** (Miscellaneous): groups of waypoints per island, drawn as a box, beam or outline (five types). The module's settings page has a **Waypoint Library** (also opened by `/ga waypoint`), built like the Macro Library: every island is a row with a switch, an island opens to its groups, a group opens to its waypoints, and each group and waypoint has its own switch. **New Group** creates a group on that island; **Add Waypoint At My Position** adds one. A group's **Options** hold Name, **Show Text** (the name and distance label of that group), Ordered, Through Walls, Type, Island (moves the group) and Export. A waypoint holds Name, Position (`x, y, z`), Color and Delete. **Import From Clipboard** and **Export All Groups** sit at the top. The search box filters the library, or any settings page you are on.
- **Ordered routes**: only the previous (red), current (white) and next (green) waypoint show, with a line to the next one. Settings: Activation Radius, Allow Skipping Waypoints, Allow Going Backwards, Line To Next Waypoint, Line Color, Line Width. `/ga waypoint next|previous|reset` steps the route.
- **Share strings**: Import and Export use the clipboard in Skyblocker's `[Skyblocker-Waypoint-Data-V1]` format.
- **Chat Waypoints** (Miscellaneous, its own module): coordinates like `x: 10, y: 70, z: -20` sent to party chat (optionally guild or all chat) become a short-lived waypoint, one per sender. Settings: From Party / Guild / All Chat, Own Messages, Duration, Chat Waypoint Color, Show Distance, Text Scale, Max Distance. Older configs move their chat settings over automatically.
- Commands: `/ga waypoint add [name]`, `list`, `remove <name>`, `import`, `export`. Waypoints are saved in `config/geileraddons/waypoints.json`.

</details>

<details>
<summary><b>F7 and dungeon helpers</b> — Odin, NoFrills and NoammAddons behavior</summary>

<br>

- **Blessing Display** (Dungeons): HUD of the active blessings (Power, Time, Stone, Life, Wisdom), read from the tab list. Power and Time are on by default.
- **Tick Timers** (F7): Odin's boss-room timers as separate movable HUDs: Goldor (tick timer plus the Start timer), Storm Pad, Storm Lightning, Storm PY, and Storm Tick. Chat lines start them, server ticks run them. Settings: Display in Ticks, Display Symbol, Show Prefix and one switch per HUD.
- **Fire Freeze Timer** (F7, M3): a movable countdown from the Professor's guardian line (206 server ticks) to the moment you should use the Fire Freeze Staff (100 ticks left), then `Use Fire Freeze`. Chat starts it, server ticks run it. Settings: Display in Ticks, Display Symbol, Show Prefix. It was the Fire Freeze Hud inside Tick Timers; that switch is gone.
- **Relic Timer** (F7, M7): a green countdown for the 45 server ticks between Necron's death line and the relics spawning (Devonian's timer).
- **Wither Dragons** (F7, M7 phase 5): Odin's dragon tools: spawn timer (HUD and world label, Milliseconds / Seconds / Ticks), dragon boxes, **Statue Boxes** (off by default: a 3×3×3 box in the dragon's color at each dragon statue until its block turns to air; the box size is not verified in game), a tracer to the dragon that spawns first, dragon health, a spawn title, and optional chat lines (confirmation, time alive with personal bests, spawned, ice sprayed).
- **King Relic** (F7, M7 phase 5): marks the cauldron that matches the relic in your inventory with a beam and, new, a tracer; the place time is kept as a personal best.
- **Dragon Stack Aimer** (F7, M7, Archer / Berserk / unknown class): shows where to aim an arrow stack for the next dragon (a box and tracer at the aim direction) and a millisecond timer for when to start running. Settings: Account for Ping, HUD Display, Run Timer Offset, Aim Color, Aim Tracer Color (Devonian's aimer).
- **Terminal Simulator** (F7): Odin's simulator for the panes, rubix, numbers, starts-with, select-all and melody terminals, with a start menu (personal bests, reset, random). Open it with `/ga termsim [ping]` or `/ga termsim <panes|rubix|numbers|starts_with|select|melody> [ping]`, or enable the module (it opens the menu and switches itself off). Settings: Ping, First Click Prot. It runs entirely on your client: the simulator is a chest the server never opened, it sends no packet and no chat line, and a firewall drops any container packet while it is open. The Terminal Solver works inside it.
- **Compact Damage** (Visual): Skyblocker's damage numbers: `3,825` becomes `3.825k`, crits get a gradient. Settings: Precision, Normal Damage Color, Crit Damage Gradient Start / End Color.
- **Terminal Solver** (F7): Odin's solver with its custom GUI: the solved slots of the panes, rubix, numbers, starts-with, select-all and melody terminals as a compact grid you click, with Client Prediction, first-click protection, Rubix Mode, Rubix Replace Clicks (a wrong-button click on a rubix slot is sent as the button it needs), all colors, and Click / Completion Sounds. Three optional helpers, all off by default: **Aim Assist** (a click that misses a clickable slot by up to **Aim Assist Range** still presses it, up to 30 blocks; with two slots in range it picks the nearer one, and one of them at random when they are equally near), **Block Wrong Melody Clicks** (a melody button sends only while the pointer is lined up with the column, and only the lit row) and **Melody Auto Cursor** (the cursor jumps to the middle melody button when the terminal opens). Only the custom GUI render type exists; Odin's Odin and Normal render types were not ported. **Analyse Terms** (off by default, Analysis group) records every terminal you solve by hand, in a real run or in the Terminal Simulator, to a local file (`config/geileraddons/recordings/terminals/<session>.N.jsonl`, at most 20 MB, nothing is sent anywhere): the cursor path every frame, each click with the slot it hovered and whether it was sent, and the slot updates. The chat shows `Analyse Terms: PANES #7 ...` after each run. Turn Aim Assist off while recording. `python tools/analyze_terminals.py <folder>` turns the file into reaction time, click delays, cursor movement and target-choice numbers, to tune Auto Terms against.
- **Auto Terms** (F7, off by default, needs **Terminal Solver**): solves the panes, rubix, numbers, starts-with and select-all terminals for you like the person in the recordings: a reaction delay, the next slot chosen the way they chose (the nearest to the cursor about 70% of the time, reading order for the first click about 45%), a cursor that travels there with a human speed curve, rests on the slot and clicks a random spot inside it. Nothing is ever clicked that the solver would refuse, and it waits out First Click Prot. Settings: a switch per terminal (**Melody** is off by default: it is untested, from one unfinished recording), **Speed** (**Slow** is the recorded speed, **Fast** is 20% quicker, **Very Fast** 50% quicker; every delay scales, and **Speed Scale** fine-tunes on top), **First Click Delay**, **Cursor Start Delay**, **Click Time Base / Per Distance / Variation**, **Minimum Click Gap**, **Rest On Slot**, **Nearest Slot Chance**, **First Slot In Reading Order**, **Finish Rubix Tile At Once** and **Repeat Click Gap** (a rubix tile that says +2 is clicked twice in a quick burst before the next tile), **Move Cursor** (off = clicks without moving the mouse), **Landing Spread / Height Bias**, **Overshoot Chance / Size**, **Correction Chance / Primary Miss Size** (a long move first misses a little and is then corrected), **Path Curve**, **Tremor**, **Hover Drift**, **Click Shake** (the clicks of a burst land a few pixels apart), **Pause On Manual Click** (a click of yours stops it for that terminal) and **Chat Messages**. The cursor moves in a fast primary movement plus corrections, with a slight bow, tremor, drift and whole-pixel positions; on the melody terminal it waits on the right button before it lights and clicks a reaction time after. The timing, slot choice and landing defaults are the medians and spreads of 34 simulator runs recorded with Terminal Solver's **Analyse Terms**; the movement shape (misses, tremor, shake, burst gap, finish-tile chance) and Melody Reaction are typical human values until **Cursor Tracker** recordings refit them. Keep your hands off the mouse while it runs; it works in the Terminal Simulator too. Turn **Analyse Terms** on to record its runs (clicks are marked `auto`) and compare them with yours: `python tools/analyze_terminals.py <folder> --who auto` against `--who human`. It needs the Terminal Solver custom GUI, so it does nothing with Stop Melody Solver on for the melody terminal.
- **Cursor Tracker** (Dev, off by default): debug recorder for Auto Terms. While on, every terminal Terminal Solver shows (real or in the simulator) is written to the Analyse Terms file with your raw mouse stream (every move event with its time, every button down and up) and the slot the solver wants next (for melody: the button the pointer's row leads to and whether it is lit yet). Play ten or so of each terminal (a rubix with +2 tiles, a melody) and run `python tools/analyze_terminals.py <folder>`: the extra section reports the speed profile, speed peaks and corrections per move, path bow, overshoot, resting tremor and drift, rubix bursts, button press length and the melody pre-aim. Needs Terminal Solver on; Aim Assist changes which slot is sent, so leave it off.
- **Terminal Times** (F7): Odin's Terminal Times. **Terminal Times** prints how long each terminal took, from opening to solved, with a personal best per terminal type (saved in `config/geileraddons/terminal-personal-bests.json`, **Reset PBs** clears it). It reads the solved state from Terminal Solver, so that module must be on; the simulator keeps its own bests. **Terminal Splits** appends `(section s | phase s)` to each Goldor terminal, lever and device chat line and prints the section times and phase total when the core opens. Real time only; Odin's server-tick option is not ported.
- **Price Display** (Visual): Skyblocker's tooltip prices on Skyblock: NPC, George pet, Motes (Rift), Bazaar buy/sell, Lowest BIN and 1-day / 3-day average prices, plus **Show Essence Cost** (essence shop items priced in coins) and **Crafting Cost** (Off / Buy Order / Buy Instantly: the cheapest way to craft an item, from the NotEnoughUpdates item repository, downloaded once and cached under `config/geileraddons/neu-recipes.json`). The price data comes from `hysky.de` (the same service Skyblocker uses); **Refresh Prices** reloads it. **Enable Estimated Item Value** adds the item's estimated total value (the networth-calculator library Skyblocker uses, fed by the Hypixel items resource).
- **Terminal ESP** (Dungeons): outlines the hitbox of each inactive Phase 3 terminal. **Show All Terminals** (on by default) also covers the other sections: from Goldor's opening line every terminal gets a marker at its known spot (**Unloaded Fill / Outline Color**) until its real stand loads. A marker disappears when its stand vanishes within 48 blocks of you, or when the `X activated a terminal!` line names a player who is standing next to it. The marker size and spot are estimates and not yet checked in game. Settings: Style, Show Through Walls, Fill / Outline Color, Line Width, Flash On Click, Flash Color.
- **Lever Aim Assist** (F7): helps with the two levers of each Phase 3 section (not the device). A lever is the block next to a `Not Activated` armor stand; pulled levers and levers out of **Max Reach** are skipped. Off by default. **Pull** turns the view part of the way to the nearest lever inside **Pull FOV** and then stops: **Pull Strength** is the share of the angle that gets closed, **Pull Speed** how fast. **Trigger Bot** turns the view onto a lever inside **Trigger FOV** with the Rotation Engine's settings and right-clicks it once the crosshair is on it, after a random delay between **Delay Min** and **Delay Max** (ms); a clicked lever rests for 1.5 s. Nothing is sent while a screen is open. Automatic aiming and clicking can get an account banned on servers that forbid it. The stand name is taken from Odin and not yet checked in game.
- **Melody Message** (Dungeons): sends a party message when you open the Melody terminal and, optionally, `Melody 67%` / `Melody 33%` as it progresses. Messages can be edited (`/pc ...`, `{percent}`).
- **Trapped Chest ESP** (Dungeons): boxes every trapped chest in a dungeon, which is where the Mimic hides. Settings: colors, Line Width, Range, Depth Check.
- **Chest Roll** (Dungeons): a case-opening animation for dungeon reward chests. When you click Open Reward Chest on a chest tier you enabled (Wood, Gold, Diamond, Emerald, Obsidian, Bedrock; Emerald and up by default), the click is held while a translucent band of rarity-colored item cards scrolls past a center line, with tick sounds, and lands on the chest's most valuable item. The band then closes and the item grows with its name, price (Croesus price data), the other items in the chest and its normal tooltip; then the held click is sent and the chest opens as usual. Click or any key skips ahead. The spinning cards come from the loot pool of the floor you opened in Croesus (F7 and M7 ship with the mod; other floors reuse the chest's own items). **Spin Seconds** (2 to 8), **Play Sound** and **Volume** adjust it.
- **Arrow Align** (Dungeons): shows how many clicks each arrow frame still needs (green under 3, gold under 5, red otherwise) and can block clicks on frames that are already right (Shift overrides).
- **Speed Display** (Miscellaneous): a movable HUD line with your speed stat (Devonian behavior).
- **Quiver Display** (Miscellaneous, unreleased): a movable HUD line with the quiver name and the arrows left, read from the `Arrows Remaining` lore line of the arrow or feather item in your inventory (Odin behavior). Hidden while no such item is found.
- **Inventory Capture** (Dev, unreleased): a debug key (the key field also accepts mouse buttons), set on the module page, that saves every item of the open menu and your inventory (slot, container, item id, count, name and lore) to `config/geileraddons/captures/inventory-<time>.txt`, so it can be sent to someone who needs to see where things are. It works while a container screen is open and is off until you bind a key.
- **Command Keybinds** (Miscellaneous, unreleased): one key per preset command, after Odin: Pets (`/pets`), Storage, Armor Wardrobe (`/wardrobe`), Equip Wardrobe (`/equipment`), Loadouts (`/loadout`), Stats, Dungeon Hub (`/warp dungeon_hub`) and Potion Bag. Every key starts unbound; a key works in the world view only (not in chat or menus).
- **Kuudra** (its own Kuudra category, unreleased; data and behavior ported from the IQ mod, Apache-2.0, see `docs/provenance/external-sources.md`). The six modules only act on the Kuudra island and follow the run phase (supplies, build, eaten, stun, DPS, skip, boss) that Elle's chat lines set, so a mod started mid-run shows nothing until the next line. **Kuudra Info** boxes Kuudra (the highest size 30 magma cube) and shows his health over him and on a movable HUD line (on tier 5 at 25k or less it can show the 240M scale). **Pearl Waypoints** (supplies) shows the timed pearl landings of the lobby area you stand in, with the throw time above each; **Supply Helper** (supplies) marks each supply crate ("Pick Up!") and the piles to deliver to, drops a pile once its "SUPPLIES RECEIVED" stand is up, and shows how many seconds into the run each supply was collected. **Kuudra Waypoints** shows the bundled safe spots in the supplies phase and the DPS and skip spots in the boss phases. **Build Helper** (build) shows builders and build percent on a HUD, colours each unfinished pile from red to green by progress, and shows "Go to stun" at the **Stun Percent** (default 93, off at 0) on tier 3 and up. **Danger Zone Alert** shows "Jump!" in the fight phases when the block under you is yellow, orange or red terracotta (a tentacle danger zone).
- **Mouse Lock** (Farming, unreleased): while on, the mouse cannot turn your view, so a farming run keeps its angle. Switch it on from the module page, with its keybind, or with `/ga mouselock` (after SkyHanni's mouse lock). **Visitor Message Hider** (Farming, unreleased) hides the `NEW VISITOR ... has arrived on your Garden!` chat line.
- **Rare Crop Title** (Farming, unreleased): in the Garden, a `RARE CROP!` or `VERY RARE CROP!` drop line shows a title and sound with the item name (after SkyHanni's Burrowing Spores notifier, for any drop). **Composter Display** (Farming, unreleased) shows the composter's time left, organic matter, fuel and stored compost on a movable HUD, read from the Composter widget in the tab list, and warns (at most once per 5 minutes) when organic matter or fuel falls under **Low Organic Matter** (default 20,000) or **Low Fuel** (default 3,000); 0 turns a warning off. Enable the Composter widget in the tab list for it to have data.
- **Garden Keybinds** (Farming, unreleased): set a key for Attack, Use Item, Left, Right, Forward, Back, Jump or Sneak and only that key drives the control while you farm (after SkyHanni's Garden custom keybinds). It is active in the Garden with a farming tool (hoe, dicer, chopper, knife, cutter, mousemat or pest vacuum) in hand and no screen open; **Exclude Barn** keeps normal keys in the barn and greenhouse and **Require Farming Tool** can be switched off. A control left empty keeps its normal key, and a key you give to one control stops driving the control it normally drives. **Visitor Shopping List** (Farming, unreleased) shows the items your Garden visitors want and how many you hold, read from the Items Required of each visitor menu you open and dropped when the visitor leaves the tab list; with a chat, inventory or other screen open, left-click an item row to copy the amount you are missing and run `/bz <item>`; you click the result, Buy and Custom Amount yourself and press Ctrl+V in the sign (the mod never clicks for you). Each visitor shows ready, partial or missing from what you hold. It does not count sacks and has no Bazaar price. **Greenhouse Helper** (Farming, unreleased) colours the status slot in the Crop Diagnostics menu (green harvestable, yellow drops, red otherwise) and the water slot when watered, and counts down to the next growth stage with a notice when it is ready.
- **Ghost Vision** (Visual): in the Dwarven Mines, boxes the ghosts (creepers below Y 100) and removes their charged swirl. Settings: Style, Fill / Outline Color, Remove Charge Effect (NoFrills behavior).
- **Croesus** (Dungeons): in the Croesus menu, tints runs by state (green unopened, gold opened, red none left), hides claimed runs and outlines runs with a Kismet Feather. In a run's chest preview it prices every chest, lists them by profit and marks the best two: the second mark is the second-best buy, so it counts the Dungeon Chest Key (3-day average price) against that chest and only shows while the profit stays above zero; an opened chest shows its own profit. **Include Essence** (on by default) can leave essence out of every profit. A Chest Count HUD warns at a threshold (default 55). Prices are downloaded from Odin's price service (`lb.odtheking.com`, the 7-day file plus the 3-day file for the key) when the module is enabled; **Refresh Prices** reloads them.
- **Simon Says** (Dungeons): marks the buttons in order (first, second, then the rest), can block wrong clicks, can announce `SS n/4` to the party and stops you breaking the device blocks. It handles the first round of the SS skip: the buttons are shown in the order that works there, and **Block Wrong On Start** (with **Max Start Clicks**, default 4) blocks extra start-button clicks in that round unless you sneak.

</details>

<details>
<summary><b>Gyro Wand, Mimic Announcer, Terracotta Timer, Spring Boots, Ragnarock, Wardrobe and Loadout Keybinds</b> — Odin ports</summary>

<br>

**Unreleased.** Behavior and settings follow Odin (no code copied).

- **Gyro Wand** (Visual): shows the Gyrokinetic Wand's 10-block area where it will land and turns to the cooldown color for 30 s after a cast.
  Settings: Range Color, Show Cooldown, Cooldown Color, Depth Check, Fill (off draws only the two outline rings).
- **Mimic Announcer** (Dungeons): sends `Mimic Killed!`, `Prince Killed!` and `Bat Killed!` to party chat. Settings: Send Mimic / Prince / Bat Message.
- **Terracotta Timer** (Dungeons): in the F6 boss, counts down each terracotta's respawn (15 s, 12 s in Master) above its pot.
- **Spring Boots** (Miscellaneous): shows the jump height while you charge the boots (a HUD line and a red box that high above you).
- **Ragnarock** (Miscellaneous): alerts when the axe is cast or cancelled and shows the strength gained. Settings: Cast Alert,
  Cancel Alert, Strength Gained, Announce Strength In Party.
- **Wardrobe Keybinds** (Miscellaneous): with the Wardrobe menu open, keys change page (Right/Left), unequip, or equip set 1-9.
  **Prevent Unequipping Equipped Set** keeps the equipped set on. **Loadout Keybinds**: the same for the Loadout menu (Right/Left,
  and keys 1-9, 0, -, = for the twelve loadouts). **Pet Keybinds** (unreleased): the same for the Pets menu (Right/Left page, an optional Unequip key, and keys 1-9 for the first nine pets on the page); **Prevent Unequipping Equipped Pet** shows a message instead of despawning the pet that is out, and **Close If Equipped** also closes the menu. Bind keys in the module page (click the field, press a key; **Esc**, Backspace or Delete clears it); mouse buttons are not supported.

Diagnostics are automatic while enabled under `Dungeons/`, `Visual/` and `Miscellaneous/`.

</details>

<details>
<summary><b>Snap Tap</b> — last-pressed movement key wins</summary>

<br>

**Unreleased.** Miscellaneous → Utilities. While enabled, holding two opposite movement keys (A/D or
W/S) moves you in the direction of the key pressed most recently instead of cancelling out; releasing
that key resumes the one still held. It is one on/off switch for both pairs, off by default, with no
hotkey. Jump, sneak, and sprint keys are not changed. This is client-side input handling only, but many
servers treat last-input-priority movement as an unfair advantage, so check the server rules before
enabling it. Diagnostics are automatic while enabled in `Miscellaneous/Snap_Tap.log`.

</details>

<details>
<summary><b>Custom Scoreboard, Animations, Particles, and Camera</b> — client-side visual controls</summary>

<br>

**Unreleased.** Custom Scoreboard replaces the vanilla sidebar locally with an editable list of
native scoreboard rows and custom text lines. Add, edit, remove, enable, and reorder rows in its
editor. The default uses a black rounded-glass background and a smooth bundled font; font and
background options are available. **Line Spacing** (0 to 16, default 1) sets the gap between rows. Move and scale the HUD element with **Move Elements**. See the
[Custom Scoreboard and fonts guide](docs/scoreboard-fonts.md) for storage and bundled font details.

Font import supports local `.ttf` and `.otf` files up to 20 MiB. Portable config profiles include the
selected layout but not user-imported font files. **Animations** adjusts first-person item position
with X/Y/Z sliders, yaw/pitch/roll rotation sliders, actual item-size scale, and swing controls.
**Particles** clears existing particles and suppresses new ones while enabled. **Third Person Camera**
adjusts rear third-person distance, collision clipping, and whether your name appears. While enabled,
F5 cycles between rear third-person and first-person; front-facing view is unavailable. Its optional
**Free Look** is off and unbound by default; once you pick a key it is held to look around temporarily, and
releasing it restores the normal camera view.

</details>

<details>
<summary><b>Player Names and Player Size</b> — local self settings and hosted player profiles</summary>

<br>

**Unreleased.** **Player Names** applies hosted aliases and static or animated colors to names that
appear in the hosted profile. The local **Your Name** settings can edit your own alias, solid color, or
gradient. **Player Size** has a local own-size control and can apply optional hosted size profiles to
other players. Local size changes apply only to your own model; remote size profiles are the only
source for other players. All model size changes are client-side and do not
change hitboxes or server state.

Player Names transforms matched username tokens at Minecraft's shared font text-preparation, width,
substring, split, and wrapping paths, in addition to its targeted chat, tab, name-tag, scoreboard,
tooltip, and roster hooks.
This covers ordinary vanilla and plugin text drawn through Minecraft's `Font`, including Auction House
item names and seller lore, incoming `@username` mentions, scoreboard rows, and names shown while
typing in editable fields. Matching is case-insensitive against each profile's source username and
uses full username-token boundaries, so `From Alice:` and `@Alice,` can match Alice without matching
`Alice2` or a longer word. Matches may span component styles or formatted-character runs; styles,
clicks, and hover metadata are retained. String font inputs recognize legacy `§` formatting codes
while matching names. Formatted component translations and supported hover text are handled; a name
available only through an unresolved server-side score, selector, or NBT lookup cannot be identified
or rewritten. A mod that draws text through a renderer outside Minecraft's Font paths may also need a
specific hook.

Displayed text in a chat input can show an alias while the stored input stays unchanged; chat messages,
commands, account identity, and network traffic use the original values. Animated gradients flow
continuously through the glyphs at the configured speed (0.25 cycles per second by default), and chat
keeps its wrapping stable while colors animate. **Replace Names** and **Apply Name Styles** control name
behavior independently. Player Size has its own module toggle.
These are source-level coverage claims; live Hypixel screens have not been visually verified.
The owner-editable active source is `26.2/src/client/resources/data/player-presentations.json` on this
repository's `main` branch; the loader requests the corresponding
[raw GitHub file](https://raw.githubusercontent.com/dasdadassadwt/geileraddons-26.2/main/src/client/resources/data/player-presentations.json).
The hosted profile and bundled active profile currently contain `{"schemaVersion":1,"players":[]}`.
The separate `26.2/src/client/resources/data/player-presentations.example.json` is an inert format example
and is not loaded by the runtime. Add entries to the active `players` list with a required Minecraft account
`name` (the source username key) and any of the optional fields below. `alias` is the replacement
display name. `color`
uses `#RRGGBB`; choose either `color` or `gradient`, which accepts two to eight color stops. Add
`animation` to a gradient to scroll it across the name; omitting `animation` leaves the gradient
static. `speed` is cycles per second and defaults to 0.25. `alias`, styling, and `size` are
independent. Size values are limited to `0.1`–`4.0` and only
change the locally rendered avatar model, not its hitbox or server state.
The profile loader caps responses at 256 KiB and parses the document incrementally before building
the Gson tree. It rejects duplicate object members, nesting deeper than 8 levels, more than 32,768
JSON tokens, or more than 16,384 value nodes. This also bounds arbitrary nested legacy `effect`
metadata before it is retained. For example:

```json
{
  "schemaVersion": 1,
  "players": [
    { "name": "ExamplePlayer", "alias": "Example", "size": 1.25, "color": "#55FFFF" },
    {
      "name": "AnotherPlayer",
      "gradient": ["#55FFFF", "#FF55C8", "#FFFF55"],
      "animation": { "mode": "scroll", "speed": 0.25 }
    }
  ]
}
```

No player names are supplied yet; a valid local cache or the bundled empty profile remains
available if download or parsing fails. When Player Names is enabled, the loader
reads local data and fetches asynchronously, caps profiles at 256 KiB,
and retains the last valid copy on failure. Successful downloads are cached locally and refreshed
every six hours; failures retry after fifteen minutes. The schema's optional `effect` field is
legacy opaque metadata and has no potion or gameplay behavior; use `color`, `gradient`, and
`animation` for name appearance.

</details>

<details>
<summary><b>Local utilities</b> — supply requests, timer, clock, and lobby watch</summary>

<br>

**Unreleased.** `/ga ep [amount]` and `/ga sb [amount]` detect the first carried stack whose root
custom-data ID is `ENDER_PEARL` or `SUPERBOOM_TNT`, then request only the amount needed to reach the
target. The defaults are 16 pearls and 64 Superboom TNT; an optional positive integer changes the
target. For example, `/ga ep` with 15 pearls requests one, and `/ga sb` with 32 Superboom TNT
requests 32. At or above the target, neither command sends a request. `/ga timer` and `/ga timer get` show countdown status;
`/ga timer set`, `pause`, `resume`, and `reset` control it. Debug aids: `/ga chat <text>` shows a client-side chat line (`&` colour codes work) and runs every chat listener on it as if the server sent it; `/ga testlever` places three client-side levers with `Not Activated` stands in front of you (Lever Aim Assist then works outside F7, in singleplayer or anywhere; the server never sees the levers, so a pull only exercises the aim) and `/ga testlever clear` removes them. Its **Timer Settings** are under General,
not a separate module.
Completion sends
`[Timer] Countdown complete.` in client chat and plays a sound by default; its text, chat/title/sound
outputs, and title duration are configurable. System Clock uses the operating system's local timezone
and defaults to white `HH:mm:ss` text with no background. Its settings are shown directly without an
extra dropdown; choose 12/24-hour time, show or hide seconds, and configure text and background.
**Lobby Sniper** lets you add multiple player names, enable or disable each entry, and choose a custom
title. It matches names case-insensitively against the current lobby roster and alerts once per
presence interval.

</details>

## Macros workspace

The **Macros** button at the bottom of the Click GUI category rail switches to a second workspace with its own
categories; **Modules** switches back. Its modules, kill list, statistics and view state are saved in
`.minecraft/config/geileraddons/macros-workspace.json`, separate from `config.json`. The pathfinder, rotation engine and
Area Killer are a port of the CC0 "Area Killer" mod (see `docs/provenance/external-sources.md`).

> These macros move, turn and click for you. Using them on a multiplayer server can break that server's rules.

- **Pathfinder** (category Engine). It has no target field: `/ga pathfind <x> <y> <z>` (or another module) starts a
  trip, and the module switches itself off when the trip ends. `/ga pathfind stop` stops it, and `/ga pathfind snapshot
  [radius]` writes the walkable surfaces around you to `.minecraft/config/geileraddons/macros/snapshots/latest.json`
  (feet positions only, no other blocks). Settings: sprint mode, allowed moves (diagonals, jumps, wade, swim, jump
  boost, sprint-jump, max drop), search (scan radius, search time per tick), style (route randomness, straight routes,
  waypoint spacing, waypoints off centre) and memory (route map, save world, learn area). The same rules apply to the Area
  Killer's routes. The pathfinder never enters the Area Killer's gate zone.
- **Rotation Engine** (category Engine). How the view turns for every macro, with one engine. Settings with a low and a
  high end are range sliders with two thumbs: every turn (or click, or mob) draws its own value between the two. Each turn
  draws its own peak speed (*Speed*) and lasts within *Turn time*; from rest a combat turn starts after a *Reaction
  delay*. Humanized turns bend, sometimes overshoot and come back, hesitate, vary their speed and let the aim drift
  slightly; the two teleport activities never overshoot or drift. Pitch is only used as far as the target needs it, so
  on flat ground the view stays level. Also a distance curve, presets (built in: Human, Relaxed, Fast, Precise; your
  own can be saved and deleted) and Instant Rotations (hold to enable).
- **Area Killer** (category Area Killer). Turn the module on (or bind its key) to hunt the mobs on its kill list on your
  side of the gate; it never crosses the gate and stops if you end up in its zone. The gate is one straight row of
  blocks along x or z plus a safety margin. Settings cover the weapon and click rate, aim zones, the Black Hole
  finisher, approach and chase distances, the failsafe, teleport jumps and etherwarp, the world overlay and the stats
  card (a HUD element you place with Move Elements). Commands, all under `/ga`: `/ga areakiller start|stop|status|gui`,
  `/ga areakiller add [mob]`, `remove <mob>`, `list`, `gate <x1> <y1> <z1> <x2> <y2> <z2>`, `margin <0-3>`, `slot <1-9>`,
  `radius <4-64>`, `overlay <true|false>`, `rest [set|clear|<true|false>|radius <blocks>|delay <seconds>]`, and
  `/ga addmobkill [mob]`. The *rest point* (`rest set` stores where you stand) is one setting for the macro, not one for each
  area: with nothing in reach for the delay (4 s) the player walks there and wanders within the radius (4 blocks), a few
  seconds at a spot, until a mob loads in. *Turn before walking* (default 100) holds the keys only for a view further off than that;
  between 30 degrees and it the macro runs on along the view on W alone while the view turns, with no A, D or S. Mobs far above or below
  count extra when the next one is chosen (a block of height past the first is 2.5 blocks of walk), so a flat walk beats a climb. *Painted areas* (`/ga area`) say where the macro may walk: `/ga area paint <name> <radius 1-16> <allowed|preferred|forbidden|wall|rest|erase>`
  (also `/ga brush <name> <type> <radius>`; `/ga brush off`) arms a brush and every right click stamps it on the ground round the block you look at (`/ga area paint off` puts it down). Green is
  allowed ground, gold preferred (routes lean toward it), red forbidden, purple a wall (shuts every height of its column), blue a place to rest (walkable; with nothing to hit the macro walks to one of them instead of the rest point). Preferred is the stronger of the two walkable paints: a preferred step costs 0.55 of a plain one. Painted ground is drawn as joined planes. Once any ground
  is painted allowed or preferred the macro stands on painted ground only; with only forbidden or wall paint the rest stays open. The
  area being painted, shown (`/ga area show <name>|off`) or used is drawn in the world. `/ga area use <name>|none` picks the one the
  Area Killer starts with (it reads it at the start), `list`, `info`, `clear` and `delete <name>` manage them; they are files under
  `config/geileraddons/areas/`. The gate is optional now: with no gate the painted area, or nothing, limits the macro
  (`/ga areakiller gate clear` removes a saved one; the gate the source shipped as a default, x -120, is dropped from saved files).
  With *Teleport jumps* on, a mob the feet cannot get to - out of a pit onto a platform, across a gap - is reached by a hop of the
  teleport item's length (a right click, no sneak): a fan of looks, steeply up as well as level, is tried and the one that lands nearest
  the mob is taken (the fall puts the player on the ground). Near mobs (under *Etherwarp min distance*) get the hop first, far ones the
  warp first. With the Etherwarp Sim on, the hop length is the sim's 8 blocks plus one per tuner, not *Jump distance*.
  The Etherwarp Sim works without the Etherwarp module being on (right click without sneaking is the Instant Transmission, which now
  works on the floor too: the ray is cast from the eyes and the body is lifted to where it fits). Route jumps and hops click once the view is
  within 5 degrees of the way, and the walk goes on while the view turns. *Jump cost* (Pathfinder, Movement, 100 %) makes routes prefer stairs to
  jumping up; a jump of more than one block costs extra. The stats card uses the tracker panel style (*Card background*).
  The plain right click (Instant Transmission) puts the player in the middle of the block it ends in. *Auto detect item* (Area Killer and
  Pathfinder, Travel; on by default) finds the Aspect of the End / Void or etherwarp item on the hotbar and takes its slot and how far it
  goes (8 or 57 plus one per Transmission Tuner) from the item; off, *Teleport slot* and the distances are used. The Pathfinder has its own
  Travel group (teleport jumps, etherwarp, slot, distances). A route is not skipped by a teleport while other mobs are near it: they are
  killed on the way. Two or more mobs within reach make the player stand and fight them, turning the view between them. Mobs on another
  level wait while there are mobs on this one. Doors and gaps one block wide get a point in line with them a little before, so the route goes
  through straight. The jump key is never pressed twice within 8 ticks where the player may fly (no creative flight toggled by a walk).
  *Scan radius* goes up to 1024, 0 for no limit (routes are planned within 96 blocks); the Area Killer has its own (default 128, `/ga areakiller radius`) and the Pathfinder's counts only for a `/pathfind` trip. *Max teleport drop* (default 4) is the most a plain teleport may leave the player above the ground it lands on, so a hop never carries them off a ledge. *Hop gap* (3 ticks) and *Etherwarp gap* (8 ticks), each with up to two more at random, are the least time between two clicks of the teleport item; a click is a press and release in one tick, never a hold. The Pathfinder keeps out of the Area Killer's gate only with *Respect Area Killer gate* on; it searches again every 2 s while its route stops short of the place, tries a teleport on the second time it is stuck, and says so when there is nowhere to stand near the place. The Area Killer searches the nearest eight mobs at once (the first three of its plan, or for nearest-first the three that cost least by distance, a mob with neighbours within three blocks counting up to three blocks nearer), searches again for a far mob only after it has moved a tenth of its distance (one to three blocks), and gives a search less time while a long route is being walked and more when there is none. A new target order has to be 40 percent cheaper to replace the kept one. Round 11: the default target order is *Human* - it costs each mob by the walk to it from the map of the ground (not the line), leaves out mobs that are a detour (a walk over 2.5 times the line and over 25 blocks) or cannot be walked to, prefers what is ahead of the way it is going and crowds, and does not switch for a small gain; a mob it could not get to is left alone 6, then 15, then 40 seconds. *Scan radius* 0 means no limit (up to 1024); routes are planned within 96 blocks. The view leads and the keys follow it: W alone while the view turns, sideways keys only on a line that needs the exact heading, the keys held for at most half a second and only when the view is over 100 degrees off, and the camera goes to a mob only when it lies within 25 degrees of the way it is walking and can be seen. Every warp is checked with the Etherwarp Sim (the green block) when planned and again at the click with the real view, and no teleport crosses a painted wall or forbidden cell; along a route it warps only when 35 or more blocks are left, no listed mob is within 20 blocks and the landing takes at least 80 percent of the flight off the walk. Off the path replans first; a teleport escape needs a second off-path within four seconds or a real stall.
  There is no drop limit to set: routes may drop up to 40 blocks, so a jump boost that put the
  player on a high ledge does not leave them there. *Target pattern* orders the mobs in range as a short
  tour that weighs the walk against the turning (Human, the default for new configs, Nearest, Least turn, Sweep, Cluster, Near random); the plan is kept until a
  mob joins or leaves or a tour is clearly cheaper, so it does not flip. When a mob dies the next one of the plan starts in
  the same tick, and while a route is searched the player keeps walking toward it instead of standing. *View cone* (default
  70 degrees) makes the next target come from the mobs in front of you (or within 5 blocks of you, on any side) while there are any, and *Turn before walking*
  (default 60 degrees) holds the movement keys until the view has turned to the way it is going, so it does not back-run or
  strafe at a mob behind it; on a straight run only W is held. *Assume one tap*
  counts a hit that would kill as a kill at once, and *Plan the next mob while fighting* works out the next route early; the
  next mob and its route are drawn in gold in the world overlay. In singleplayer `/ga spawnmob <count> <radius> <mob>` spawns
  mobs on the ground around you. Without a mob, `add` and `/ga addmobkill` take the
  creature under your crosshair (up to 64 blocks). A kill list entry is an entity id (`zombie`) or the words of a name
  (`name:treasure hoarder`) for a server's own creatures. What the macro has read of the world and learned about the
  area is kept under `.minecraft/config/geileraddons/macros/worlds/` and is never exported.
- **Human Recorder** (category Engine). Records how you play by hand, to tune the Rotation Engine and the Area Killer's
  targeting against a real player: turn it on (or `/ga record start`), `/ga spawnmob 30 30 creeper`, kill them yourself, turn
  it off (`/ga record stop`). It writes the view every frame, the keys and position every tick, the kill-list mobs around you
  with their distance, bearing and crowd, every click and every kill to
  `.minecraft/config/geileraddons/recordings/<session>.<n>.jsonl` (at most 20 MB, the oldest samples are dropped). It only
  reads the game's own state and sends nothing. `python tools/analyze_recording.py <folder>` (private repo) prints the numbers.
- **Speed Sim**, **Jump Boost**, **1 Tap**, **Etherwarp Sim** (category Sim). Singleplayer only, and not in a world
  opened to LAN. Speed Sim multiplies ground speed by Speed / 100; Jump Boost applies the real effect at the chosen
  level; 1 Tap applies Strength 255. Etherwarp Sim makes a diamond shovel the etherwarp item for the Etherwarp module
  (turn that on too): sneak and right click to land in the middle of the block you look at, up to 57 blocks away plus 1
  per *Transmission Tuner* (0 to 4), if there is room to stand. Everything they applied is removed when the module is
  turned off or you leave the world.
- **Etherwarp** (category Visual). Odin's Etherwarp overlay: with an etherwarp item (ethermerge, or the Etherwarp Conduit,
  which needs no sneak) held and sneaking, a box shows where the warp lands, green when it works and red when there is no
  room. Settings and defaults follow Odin's: Show Guess, Success and Fail colour, Show when failed, Box Style, Use Server
  Position, Full Block, Depth, and Custom Sounds with a sound id, pitch and volume. The module never moves you or sends a
  position on a server (no zero-ping warp): the server does the warp, and its etherwarp sound is replaced by your own.
  One warp goes through per two ticks; clicks inside that time are dropped. In singleplayer with Etherwarp Sim on, a
  diamond shovel is the item. The Hypixel-side parts (item data, the sound swap) have only been written against Odin's behavior.
- The `/etw` recorder is shelved: its source and checks stay, but it is not started and its command is not registered.

## Settings and data

Module settings and Click GUI state live in `.minecraft/config/geileraddons/config.json`. Macros and
reusable macro functions are saved as individual JSON files under `macros/` and `functions/`, with
`macro-index.json` as their manifest. On the first save after upgrading, inline macros/functions are
migrated and the old config is preserved once as `config.before-split.json`.

Dungeon Guide steps are stored by floor and phase under
`.minecraft/config/geileraddons/dungeon-guides/<floor>/<phase>/`; the route name is shared across every
phase on that floor. Upgraded phase-specific route files are archived under
`dungeon-guides/archive/legacy-v1/`.

`/ga reset` provides a guided factory reset instead of requiring manual file deletion. Every reset
area starts checked; uncheck any areas to preserve, review the selection, then confirm. The reset
flow creates a recovery export before applying changes.

Use `/ga config export [name]` to create a full `.gacfg.zip` profile. `/ga config import` lists
available profiles, validates and previews the selected archive, asks for a separate confirmation,
and creates a recovery export before importing. `/ga config save` flushes the main config and split
collections to disk; `/ga config folder` opens the GeilerAddons config directory; `/ga config reload` (unreleased) re-reads the config files
from disk into the running game without saving first, so a file you swapped in is picked up and unsaved in-game edits are dropped. Exported profiles
and recovery copies are stored in `.minecraft/config/geileraddons/exports/`; the commands provide a
chat link to that folder.

For manual recovery, module settings and Click GUI state are in `config.json`, while macros/functions,
guide routes, and other feature collections use the separate files and folders described above.
Deleting `config.json` alone only resets module settings and GUI state.

**Update check:** on launch, the mod asks GitHub whether a newer release exists, and tells you in chat if so. When one does, the Click GUI header grows a row with **Download** and **Info**: Download opens the release page through the usual link confirmation, and Info opens a scrollable panel with the release notes and the version you have. Both stay inside the mod — nothing is ever downloaded or installed. Turn the check off with **Miscellaneous → General → Check for Updates** (an existing `"checkForUpdates"` key in the config file seeds that setting once, then the setting is authoritative).

**Island detection:** modules that only apply on one island need to know which island you're on, so the mod subscribes to the official Hypixel Mod API. The shared API handles the greeting and registration, then reports the island whenever you change server. It is a normal row — **Miscellaneous → General → Island Detection API** — and an existing `"hypixelModApi"` key in the config file seeds that setting once, then the setting is authoritative.

Turning it off leaves island-gated modules idle, and they say so: the config file is not the only
place that knows why. No terrain or scoreboard guess is allowed to activate them, so an off switch
means an idle feature rather than a wrong one.

**Keybinds:** binding a key that another module, macro or script already uses reports the collision in
chat. It is reported rather than resolved — a macro hotkey is checked before module keybinds, so on a
collision both would otherwise fire from one press.

## License

Original GeilerAddons code is [CC0 1.0](LICENSE). The Primer Octicons artwork license notice is
included at `26.2/src/main/resources/META-INF/licenses/OCTICONS-MIT.txt`.
