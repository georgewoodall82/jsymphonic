# 05 - How JSymphonic does it now

What was changed in JSymphonic because of the findings in [02](02-file-formats.md)-[04](04-player-firmware.md), where it
lives, how it was tested, and what is still different from SonicStage. The extended layout is used for the
generation `GENERATION_7_EXTENDED` (NW-A80x and NW-A91x), everything else keeps the previous behaviour.

## History

All commits are on the branch `fix/nw-a80x-extended-database` (reported in
[issue #1](https://github.com/georgewoodall82/jsymphonic/issues/1)).

| Commit | Symptom on the NW-A808 | Change |
|---|---|---|
| `7e16d69` | "Incompatible database" right after the first write | Extended layout copied from a SonicStage database (21 trees, `02TREINF`, device files), plus bugs of the old writer |
| `51f01bd`, `68566b3` | (unrelated) MP3 bit rates, ffmpeg errors, SonicStage files kept, Track Info | Not covered here |
| `2276372` | Blank album art | Cover in the OMA (`GEOB` `OMG_FENCA1`) and the `20Pxx` thumbnails of tree 36 |
| `369ce89` | "Simple Mode" popup | Write `A_WM/AFMNTLOG.DAT` (`DTBL` times, FAT times rounded to 2 s) |
| `95503ea` | "Simple Mode" again | Also write `MLBL` element 1 (the firmware's second check) |
| `96d63c7` | "Incompatible database" (now the player trusts the database, so it reads the `A_WM` files) | Write `A_WM/EXCNTINF.DAT` and `EXCNTMTA.DAT` |
| `8ba7980` | Both messages still shown (the before/after eject copies proved the player was in full mode) | Reverse engineering of SonicStage: content IDs, `MLBL` 3 and 4, `07GTCHLG.DAT`, and everything that is *preserved* (below). Reported working on the NW-A808 afterwards. |

## The write sequence

`DataBaseOmgaudio.write()` (in `device/sony/nw/`):

1. **Read what must be kept** before anything is deleted: `DataBaseOmgaudioExtendedToolBox.readOldDatabase()`.
2. Back up the old database in `db_backup` (only if it doesn't exist yet), erase the files, keep the ones that belong
   to the player or to SonicStage (`isDeviceFile`).
3. Write `00GTRLST`, `01TREE01`-`04`, `22`, the extended trees and `02TREINF`, `04CNTINF`, `04PATLST`.
4. `writeMissingContentIds()`: tracks without a content ID get one (written into the OMA file).
5. `05CIDLST.DAT`, then `EXCNTINF.DAT` and `EXCNTMTA.DAT`.
6. **Last: the mount log** (`writeMountLog()`), which sets the modification times of `01TREE01.DAT`, `04CNTINF.DAT`
   (and the trees of `07GTCHLG.DAT`) and writes them into the logs. It *must* come after every other file.

## The changes

| Topic | Where | What |
|---|---|---|
| Mount log | `DataBaseOmgaudioExtendedToolBox.writeMountLog` | Times rounded to 2 s, read back from the file system, written in `DTBL`; `MLBL` 1, 3, 4 = `01 00 .. time` (`MOUNT_LOG_PC_FUNCTIONS`); elements 5, 6 (written by the player) and the rest are left alone |
| Change log | `writeTreeChangeLog` | `07GTCHLG.DAT` with trees 02, 03, 04, 36 and the same time |
| Extended info | `writeContentInfo`, `readContentElements` | One element per title ID; elements of SonicStage tracks are copied by content ID, others made from the tags (unrated, readings = names, play count 0) |
| Content IDs | `Oma.newContentId`, `Oma.writeContentId`, `Oma.writeTagInfoToFile`, `Title.getContentId/setContentId`, `NWOmgaudio` (after the copy) | New tracks get an ID when their OMA file is written; old files without one get it patched in at offset 8 of their `EA3` header; the ID is stored on the `Title` so `05CIDLST` and the `A_WM` files use it |
| Artist reading | `Oma.readEA3TagInfo` (`OMG_TPE1S`), `Title.getArtistReading`, `WriteCNFBelement` | `TSOP` = reading, or the artist's name if empty |
| Exact lengths | `DataBaseOmgaudioToolBox.read04CNTINF`, `NWOmgaudio.loadTitlesFromDevice` | Lengths of titles are taken from the old `04CNTINF.DAT` when the title name matches |
| File properties | `Oma.readFileProperties` | The 4 codec bytes of the OMA file are used as is instead of being recomputed |
| Player-owned trees | `readOldDatabase`, `writeKeptTree` | Trees 10-15, 22, 2D, 2E, 35 are re-written as they were |
| Deleted tracks | `writeKeptTree`, `isSameTitle` | A title is removed from those trees when its ID is no longer used, or is used by another content ID; the group keys and the `02TREINF` keys are recomputed; groups left empty go to the end with level 0 (several-level trees) |
| Thumbnails | `keptPictures`, `writeAlbumThumbnails`, `deleteUnusedThumbnails` | Existing thumbnails are kept for the albums that still exist; new ones are drawn from the covers; unreferenced ones are deleted, and empty `20Pxx` folders |
| OMA tag parsing | `Oma.readEA3TagInfo` | Empty date/track number no longer throw (they used to leave the title with a 210 s default length), missing tags stay empty instead of "unknown ... (JSoma)", padding bytes no longer turn into `�` |
| `04PATLST` reader | `DataBaseOmgaudio.buildPathList` | `readFully` instead of `read`: short reads shifted the entries |

## How it was tested

A scratch harness (`tools/java/RoundTripHarness.java`) calls the real writer with no user interface. Tests were done
on copies of a SonicStage backup (379 tracks) and of JSymphonic-written databases from the reporter's player, and
checked with `tools/omgaudio_dump.py`.

| Test | Result |
|---|---|
| Add a song, write, delete it, write, compare with a rewrite of the same backup that changed nothing | Identical except the times in `07GTCHLG.DAT` and `A_WM/AFMNTLOG.DAT` (which SonicStage changes on each write too) |
| The same rewrite compared with the original backup | Identical: `02TREINF`, `04CNTINF`, `05CIDLST`, `EXCNTINF`, `EXCNTMTA`, trees 10-15, 22, 2D, 2E, 35 and all 379 OMA files. Different: see below |
| Delete a track that is in the bookmarks (1 entry) and 34 history groups | Removed from all of them; every group key = sum of its titles' lengths; the tree keys in `02TREINF` match; the mount log check says FULL MODE |
| Content IDs | Unique per track (379/379); identical in the OMA headers, `05CIDLST`, `EXCNTINF`, `EXCNTMTA`; stable over successive writes; the 379 SonicStage IDs unchanged |
| The reporter's own database (JSymphonic-written, zero IDs) + add/delete/rewrite | No errors; every track gets a distinct ID; thumbnails kept and unused ones removed |
| `omgaudio_dump.py mountlog` | Reproduces the firmware's two checks (FULL/SIMPLE) |

No unit tests exist in the repository for this; the checks above are the regression suite and are worth re-running
before any change to the writer (they take a few seconds each).

## Still different from SonicStage

* **Numbering and order of the main trees** (`01TREE01`-`04`, `2F`-`34`, `36` and their `03GINF`): rebuilt with
  contiguous IDs and in JSymphonic's order, so the files differ in bytes while the player shows the same lists. SonicStage
  keeps old IDs and leaves unused groups at the end. The exact collation of artists/albums with readings (kana, kanji)
  is approximated.
* **Thumbnails**: JSymphonic can make them from covers in the OMA files, where SonicStage's backup had none
  (extra files appear); and names them after the lowest title ID, not the group ID.
* **JSymphonic's own files**: `04PATLST.DAT` (and `db_backup/`) are added to the folder. The path list stores one entry
  per ID, so the many SonicStage tracks that share the path "unknown" are indistinguishable (harmless, it's only used to
  avoid adding a file twice).
* **Files SonicStage can write and JSymphonic doesn't**: `05CHINDC.DAT`, `08EDTERN.DAT`, `SRCIDLST.DAT`,
  `A_WM/EXGINFxx`/`EXTREExx`, `ARTSTINF.DAT`, `ARTISTLK.DAT`, `C2DETECT.DAT`, `MISCN*`. The NW-A808 backup contains
  none of them, so none were needed there. Be careful with the others: the cleaning step of `write()` keeps the `A_WM`
  folder (and `SRCIDLST`, `07GTCHLG.DAT`, `20Pxx`, the key files and the tracks) but **deletes any other top-level
  file** it doesn't know, so a player that has such a file would lose it. Add it to `isDeviceFile` if that happens.
* **Models other than the NW-A808** are untested; the A82x/A91x firmware may check more.

## If a new player misbehaves

1. Compare with a SonicStage backup of that very model (`omgaudio_dump.py diff`), see [01](01-methodology.md).
2. `omgaudio_dump.py mountlog` and `ids`: if they don't say FULL MODE/0 differ, fix that first.
3. Do before/after copies of the player's folder around one eject and one power cycle; see which files the player
   changed.
4. Change one thing per test; keep every tested folder.
