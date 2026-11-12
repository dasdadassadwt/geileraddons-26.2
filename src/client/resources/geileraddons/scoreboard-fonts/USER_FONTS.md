# Custom scoreboard fonts

Open **Custom Scoreboard → Edit Layout → Import Font**, then paste the full path to a local
`.ttf` or `.otf` file. GeilerAddons copies the file into
`config/geileraddons/scoreboard/fonts/` for this Minecraft profile and enables its generated
resource pack at `resourcepacks/geileraddons-scoreboard-fonts/`. The new font appears in the
editor's **Font** button after the resource reload finishes. The source file is not modified.

Imported font files are local profile data and are not included in mod exports. Import only fonts
you have permission to use; copying a file does not change its license. Remove a copied font by
deleting its file from the profile's `config/geileraddons/scoreboard/fonts/` folder, then restarting
the client. The generated pack is rebuilt from the remaining imported files at startup.

The bundled font choices are **Noto Sans**, **Atkinson Hyperlegible Next**, **IBM Plex Sans**, and
**Roboto Mono** (all SIL Open Font License 1.1), plus **Roboto Slab** (Apache License 2.0). The
Atkinson Hyperlegible Next and IBM Plex Sans records include immutable source/download URLs, pinned
upstream commits, and source and bundled SHA-256 hashes in `attribution.json`. All bundled fonts have
their complete license texts beside the font files in `assets/geileraddons/font/scoreboard/` in the
mod resources.
