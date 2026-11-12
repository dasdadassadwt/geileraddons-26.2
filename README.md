<div align="center">

# GeilerAddons

**Overlays and puzzle solvers for Hypixel SkyBlock.**

GeilerAddons is a client-side mod. Network features include optional Hypixel island detection,
update checks, Party Finder profile lookups, and hosted player profiles. Sparkling Critter
announcements use ordinary in-game chat. Dungeon profile lookups are cached, and the mod never sends
a kick unless Auto Kick is enabled and you are party leader.

![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen)
![Loader](https://img.shields.io/badge/loader-Fabric-blue)
![License](https://img.shields.io/badge/license-MIT-blue)

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

Source descriptions below do not imply live Hypixel verification.

## Installation and compatibility

GeilerAddons 1.7.0 targets the **client** on Minecraft **26.2**. The Fabric instance needs Java 25,
Fabric Loader 0.19.5 or newer, Fabric API for Minecraft 26.2, and the Fabric implementation of the
Hypixel Mod API (version 1.0 or newer). Install the GeilerAddons jar and those dependencies in the
client's `mods` folder. GeilerAddons is a client mod and is not intended for server installation.

Download the jar from the [Releases](https://github.com/dasdadassadwt/geileraddons-26.2/releases) page.

### Building from source

Use Java 25 and the checked-in Gradle wrapper:

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew offlineChecks` runs the offline test suites.

## Modules

<details>
<summary><b>Dungeons</b> — Catacombs solvers, maps, alerts, route guides and run tools</summary>

<br>

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
<summary><b>Dungeon Guide and run tools</b> — routes, puzzle solvers, and run tracking</summary>

<br>

Dungeon features are separate modules, and each feature module starts disabled in a
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
Unsupported or ambiguous room state is left unknown rather than guessed.
Optional **Puzzle Timers** begin on the first confirmed puzzle-room entry and complete only on
source-supported puzzle events. Qualifying times compare against local puzzle bests. **Draft Prompt**
adds a clickable chat action for the supported puzzle-failure messages; it does not send the command
until clicked.

The remaining focused modules are **Scorer** (threshold, alert text, duration, and sound), **Run
Splits** (Blood, Camp, and Portal aggregates; all tracked floor boss phases; individual S1–S4 rows;
and a bottom Run total, with real time fixed first and **Second Time Display** for 50 ms tick
estimates, bracketed local PBs, red Time Lost, or Off; boss timers remain available), **Blood Camp**
(Watcher wave predictions and world markers), **Livid Solver** (Livid highlight and invulnerability
timer), **Invincibility Timer** (Bonzo Mask, Spirit Mask, and Phoenix timers), **Spirit Leap Menu**
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
has an individual color picker.
**Card Distance** controls the cards' screen-relative distance from center (8.5% of each screen axis
by default, adjustable from 5.5–18% and clamped to prevent overlap). **Extra Roundness** sets their
corner radius from 0–18 px (9 px by default). Any mouse button in a populated card quadrant selects
its teammate; an empty quadrant passes the click through. Clicks and configured keys use the same
resolved card-to-head mapping, and dispatch only if the corresponding live slot still contains a
player head. A resolved slot is dispatched through vanilla `PICKUP` input. Incoming leap titles appear
in the vanilla title overlay and resolve
class exclusively from the dungeon tab list; missing or invalid tab classes display **Class unknown**.
Arrival sound, title, and announcement options remain in **Spirit Leap Menu** settings.

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

**Dungeon Mob ESP** owns the Wither Bosses, Mimics, and Party Classes groups. It handles dungeon
specific mob overlays in one place. **Secret Clicked** controls dungeon-secret click boxes and the
secret chime.

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

**Room Alerts** report map-derived room-clear and secret status after the same room
footprint and checkmark remain stable for five client ticks. A first observation of a completed room
only establishes its state; each completion alert fires at most once per room per dungeon run, even
across brief detector gaps or footprint growth. Room Cleared subtitles default to cyan and Secrets Cleared
subtitles to green, with separate color controls. New alerts replace the current subtitle at Minecraft's
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

Dungeon Map is a movable 6×6 room map. It can show room names and secrets, doors,
checkmarks, special-room predictions, and loaded teammate markers and labels. Its settings control
map scale, room size and gap, padding, text and icon size/alignment, room and door colors, background
and outline, and player marker/name/class presentation. Before saved map colors and the Odin layout
are ready, the HUD shows a neutral provisional 6×6 grid and marks the local player cell without room
identities or connections. It switches to the existing authoritative map when ready. The map preview
in settings reflects the current choices. Its default uses the requested item-icon style and enables
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
are retained for at most 40 client ticks, with dead/departed identities removed. Source and offline
checks still need a live run to verify alignment, names, player markers, and rendering.

</details>

<details>
<summary><b>Secret Clicked</b> — click and pickup confirmation</summary>

<br>

**Secret Clicked** is an F7 feature toggle for click and pickup confirmation. Its
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
<summary><b>Dungeon Guide</b> — run player-authored Catacombs route guides</summary>

<br>

Each floor has one named route containing every built-in and saved custom phase.
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

Box Doors retains Normal, Glass, and Start classification for dungeon tracking but no
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

</details>

<details>
<summary><b>Enchanting</b> — Experimentation Table helpers</summary>

<br>

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

</details>

<details>
<summary><b>Hunting</b> — Critter, Tiki and Hideyho finders</summary>

<br>

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

</details>

<details>
<summary><b>Farming and Slayer</b> — Garden pests, plots, trackers and timers</summary>

<br>

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

Bounded diagnostics run automatically while **Infested plot** is enabled.

**Island detection has to be on.** Like every island-gated feature, this one needs the official
Hypixel Mod API, which is **Miscellaneous → General → Island Detection API**. The mod never guesses
your island from the scoreboard or the terrain, so if that switch is off the module says so in chat
rather than doing nothing in silence.

</details>

<details>
<summary><b>Garden and Slayer trackers</b> — cooldowns, rates, timers, and alerts</summary>

<br>

Fresh configs leave Crop Fever Timer off; saved enable choices are preserved.
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

Slayer Tracker shows session or total bosses per hour and offers spawn-time, kill-time, or both HUD
rows. Boss and nearby-miniboss alert switches default off. Boss alerts can filter by each recognized
nearby Slayer boss type; the unclassified fallback stays enabled by default, while conflicting
observed boss types suppress the alert. Alert text supports `{boss}` and `{miniboss}` placeholders,
with configurable chat/title/sound outputs and title duration. Chat is enabled by default when an
alert master is on; title and sound default off. The Garden and Slayer paths depend on live widget,
chat, scoreboard, entity, and island observations; they have not been verified in-game.

</details>

</details>

<details>
<summary><b>Foraging</b> — Tree gifts and felled-tree titles</summary>

<br>

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

</details>

<details>
<summary><b>Visual</b> — Highlights, scoreboard, camera, animations and player names</summary>

<br>

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
<summary><b>Custom Scoreboard, Animations, Particles, and Camera</b> — client-side visual controls</summary>

<br>

Custom Scoreboard replaces the vanilla sidebar locally with an editable list of
native scoreboard rows and custom text lines. Add, edit, remove, enable, and reorder rows in its
editor. The default uses a black rounded-glass background and a smooth bundled font; font and
background options are available. Move and scale the HUD element with **Move Elements**.

Font import supports local `.ttf` and `.otf` files up to 20 MiB. Portable config profiles include the
selected layout but not user-imported font files. **Animations** adjusts first-person item position
with X/Y/Z sliders, yaw/pitch/roll rotation sliders, actual item-size scale, and swing controls.
**Particles** clears existing particles and suppresses new ones while enabled. **Third Person Camera**
adjusts rear third-person distance, collision clipping, and whether your name appears. While enabled,
F5 cycles between rear third-person and first-person; front-facing view is unavailable. Its optional
**Free Look** key is held to look around temporarily, and releasing it restores the normal camera view.

</details>

<details>
<summary><b>Player Names and Player Size</b> — local self settings and hosted player profiles</summary>

<br>

**Player Names** applies hosted aliases and static or animated colors to names that
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
The owner-editable active source is `src/client/resources/data/player-presentations.json` on this
repository's `main` branch; the loader requests the corresponding
[raw GitHub file](https://raw.githubusercontent.com/dasdadassadwt/geileraddons-26.2/main/src/client/resources/data/player-presentations.json).
The hosted profile and bundled active profile currently contain `{"schemaVersion":1,"players":[]}`.
The separate `src/client/resources/data/player-presentations.example.json` is an inert format example
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

</details>

<details>
<summary><b>Miscellaneous</b> — Mod-wide settings, chat, macros, inventory and utilities</summary>

<br>

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
`<prefix>pt [username]`/`<prefix>ptme [username]`/`<prefix>transfer [username]`. The reply master
switch defaults on, and any valid player in Party chat can request a reply by default. Party is the
only incoming request channel enabled on a fresh configuration; Guild, Co-op, Private, and
**Custom Channels** have independent switches under **Incoming Reply Channels**. Custom Channels
controls replies arriving through user-created Chat Channels rules; it does not control custom reply
entries. For an incoming transfer without
a target, the requesting party member is the target. You can type
`<prefix>pt {args}` locally (for example, `!pt username` with the default prefix) to transfer
directly when you are party leader and the fresh roster confirms the exact target. A different party
member's incoming transfer request is ignored unless the allowlist is enabled and contains that exact
sender; an enabled blocklist entry still wins. The reply delay defaults to four client ticks. The master
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
command template; they are never executed from another player's message. Folder and entry enable
switches and global sender allow/block lists (32 usernames per list) control the reply system. Each
list has its own enable switch: an enabled allowlist restricts ordinary replies to its entries, while
an enabled blocklist excludes its entries and takes precedence. A disabled list is ignored even when
it has saved names. An enabled empty allowlist denies ordinary incoming replies; with the allowlist
disabled, any otherwise valid sender in an enabled channel may request them. Remote party transfers always require an
enabled allowlist containing the exact requester. Replies use the request's Party, Guild, Co-op, or
Private route (`/pc`, `/gc`, `/cc`, or
`/r`). Help lists every enabled command across lines capped at 170 characters; continuation lines
begin `Commands (continued):`. Sender filters apply to ordinary reply commands too. A delayed `/r` reply is dropped if an incoming private message or a recognized
local `/msg`-style command changes the tracked counterpart. Unrecognized whisper aliases or hidden
server-side changes to `/r` state cannot be detected by the client. Outgoing channel messages use the
normal chat submission path, and slash commands remain vanilla commands. Reply entries and filters
Reply entries and sender filters are stored in the feature-local chat settings file and are excluded
from shared config profiles.

</details>

<details>
<summary><b>Macros</b> — build clear, randomized input workflows</summary>

<br>

Create a macro in the **Miscellaneous** category and open its workflow editor. The editor is titled
**Macro Editor** and groups nodes into **Actions**, **Control**, **Player Input**, **Inventory**,
**Display**, **Data**, **Reusable**, and **World**. The Inspector is a separate panel on the right of
the canvas. Scroll over the canvas to zoom around the pointer; the palette and Inspector keep their
own list scrolling. Long node text expands the node to show the full value, and the canvas can pan to
reach that width. Zoom ranges from 1% to 100%; saved values migrate proportionally from the former
range and clamp at 100%. Drag previews show the whole node at its proposed drop location. If/Else trees show
their **Then** branch and an optional **Else** branch; Repeat selects
count, forever, or until mode in one node. Select a branch or body before adding steps there.
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

**Hypixel state conditions.** These compare locally observed island, confirmed
Catacombs floor, party membership/size/leader/member, and Garden pest status or count for the current
or a selected plot. They use existing client snapshots and make no extra network request. Missing
or stale data stays unknown. If the complete condition cannot be resolved, the workflow stops with
an unavailable-data message instead of treating the value as false or zero.

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
island is detected, and stops if a different known island loads. A macro can
also be restricted to selected islands and can run in the world, containers, or any non-text
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

Log sessions are retained rather than deleted automatically.

In macro text fields, use **Ctrl+A** to select all, **Ctrl+C**/**Ctrl+X** to copy or cut a selection,
and **Ctrl+V** or **Shift+Insert** to paste. You can also drag to select text and use the same
shortcuts in searchable picker fields.

The **Enable Macro System** toggle is the master switch for all macro hotkeys and running
workflows; each macro also has its own **Macro Enabled** toggle. **Share / Paste Macros** opens a
multi-select manager that copies selected macros as a portable clipboard package and appends
packages as new macros without overwriting existing ones. The current version writes
transfer format v8, which preserves unconnected editor blocks; v1–v7 packages remain importable,
with fields they do not contain using their defaults. **Manage Folders** provides a separate nested
macro tree.
Folder names may repeat because UI state is keyed by stable folder ID; deleting a non-empty folder
promotes its entries and child folders to its parent instead of deleting them.

</details>

<details>
<summary><b>Slot Locking</b> — lock hovered slots and bind inventory slots to the hotbar</summary>

<br>

Hover a player inventory slot and press the configured **Lock Key** to toggle its
lock. A locked slot cannot be moved, swapped, or dropped. To bind a slot, hover an inventory slot,
hold the configured **Bind Key** (G by default), move to a hotbar slot, then release. The editor draws
the link between the two slots. Shift-clicking either linked slot swaps it using that hotbar slot's
number key; locked endpoints remain protected. Link colors, line style, slot protection, and saved
profiles are configurable.

</details>

<details>
<summary><b>Snap Tap</b> — last-pressed movement key wins</summary>

<br>

Miscellaneous → Utilities. While enabled, holding two opposite movement keys (A/D or
W/S) moves you in the direction of the key pressed most recently instead of cancelling out; releasing
that key resumes the one still held. It is one on/off switch for both pairs, off by default, with no
hotkey. Jump, sneak, and sprint keys are not changed. This is client-side input handling only, but many
servers treat last-input-priority movement as an unfair advantage, so check the server rules before
enabling it. Diagnostics are automatic while enabled in `Miscellaneous/Snap_Tap.log`.

</details>

<details>
<summary><b>Local utilities</b> — supply requests, timer, clock, and lobby watch</summary>

<br>

`/ga ep [amount]` and `/ga sb [amount]` detect the first carried stack whose root
custom-data ID is `ENDER_PEARL` or `SUPERBOOM_TNT`, then request only the amount needed to reach the
target. The defaults are 16 pearls and 64 Superboom TNT; an optional positive integer changes the
target. For example, `/ga ep` with 15 pearls requests one, and `/ga sb` with 32 Superboom TNT
requests 32. At or above the target, neither command sends a request. `/ga timer` and `/ga timer get` show countdown status;
`/ga timer set`, `pause`, `resume`, and `reset` control it. Its **Timer Settings** are under General,
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

<details>
<summary><b>Theme</b> — one look for the whole mod</summary>

<br>

Everything the mod draws — the menu, the tracker panel, the notifier — reads from a single theme, so there's one place to change how it all looks.

Five colours define it: background, border, accent, text and muted text. Every hover tint, card fill and slider is worked out from those, so you can't end up with half of the GUI on the old scheme. Text sitting on the accent flips between black and white on its own, so a pale accent doesn't leave unreadable labels.

**Icons → Icon color** is a saved RGB tint for approved monochrome module-card icons. It starts at
the Theme Text color; selecting a preset updates it to that preset's Text color, and you can edit it
afterward. All module-card icons use this tint; the General and Theme toolbar buttons keep their
dedicated toolbar artwork and colors.

Nine presets to start from: **Tracker** (the flat translucent look, and the default), **Amethyst** (the original purple), **Midnight**, **Forest**, **Aurora**, **Ember**, **Orchid**, **Ice Glass**, and **Rose Glass**.

In-world colours — waypoint states, solver directions, device highlights — stay separate. Those are signals, not decoration, and a theme has no business repainting them.

</details>

<details>
<summary>Using the click GUI</summary>

<br>

- Modules are cards. Click the card to open its settings; the **switch** and keybind control keep their own actions. Each module has a bundled icon; **Theme → Icons → Icon color** tints approved monochrome card icons.
- The left rail shows only the nine main categories. Inside a category, module subcategories appear as titled sections with a divider above their cards: **Dungeons** has **F7** and **General**; **Hunting** has **Critter Safari** and **General**, with **Tiki Helper** under General; **Visual** separates **Player & Camera**, **Highlights & Effects**, and **Interface**; **Miscellaneous** separates **Chat & Party** and **Utilities**.
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
- **Move Elements**, at the bottom of the category list, opens a screen where you choose **All** or a populated module category from the compact selector at the bottom center. Its options open above the selector and scroll when the screen is short. Drag panels to move them and scroll over a panel to resize it; positions are kept as a share of the screen, so they survive resolution and GUI-scale changes. Previews use each HUD element's own styling and current data when available, with a representative sample otherwise. The editor adds no filled card behind them, though a HUD element's configured background still appears. Moving the Slot IDs sample preview does not move the actual per-slot badges. Title alerts use Minecraft's fixed subtitle position and are not movable.
- The panel scales to your screen and reopens wherever you left it.

Folded sections and macro/function list states are remembered while the game is open, then start
collapsed after a restart. The compact category rail, module grid, and settings list each scroll inside
their own clipped area; the wheel scrolls the area under the pointer. The compact rail contains the nine
top-level categories. Subcategory headings and their module cards scroll together inside the main grid.
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

</details>

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
collections to disk; `/ga config folder` opens the GeilerAddons config directory. Exported profiles
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

GeilerAddons is released under the [MIT License](LICENSE). You may use, modify and redistribute the code, but
you must keep the copyright notice and credit the original author. The Primer Octicons artwork license notice is
included at `src/main/resources/META-INF/licenses/OCTICONS-MIT.txt`.
