# SGF support and game persistence

Import/export lives in the board's three-dot menu. Android's document picker
provides access to the selected file; the app does not request broad storage or
network permissions. Clear board starts a new 19×19 game with the current rules
and komi. Export first if the current record needs to be retained.

## Supported data

- FF[4], GM[1], SZ[19] (or the square equivalent 19:19).
- Main line, branches, pass moves, comments and player/game metadata.
- Root AB/AW/AE setup stones and handicap; root and later PL declarations.
- Chinese, Japanese and Korean rule identifiers, including recognized Chinese
  spellings; decimal, positive or negative komi within −400 to 400.
- UTF-8 export and SGF-declared input character encodings supported by Android.
- Multi-game collections: choose the game to import.

Rules, komi and setup changes after the root are rejected. Files are limited to
2 MiB, collections to 100 games, paths to 2,000 moves, trees to the parser's node
limit and nesting to 128 levels. All branches are checked by official KataGo
legality before replacing the current document. A failed import preserves it.

The current tree, selected continuation and settings survive Activity recreation
and app restarts. Restoring a game pauses automatic moves. SGF export snapshots
the document before opening the picker and includes all real branches. Temporary
engine variation previews are never added to the exported SGF.

Changing rules validates the whole record off the main thread, then commits the
complete settings draft. Previous analyses cannot be reused across different
history/rules/komi identities. See [architecture](katago-integration.md).
