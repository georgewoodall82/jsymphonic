# 03 - What SonicStage does

Findings from `AuraPACAPI.dll` (SonicStage 4.3, the component for the NW-A8xx "Aura" family) and from a SonicStage
backup of an NW-A808. Formats are in [02](02-file-formats.md); the player side is in [04](04-player-firmware.md).

## Where the code is

The DLL's logging macros leave class and method names in the binary. The ones that matter:

| Name | Role |
|---|---|
| `CkoDeviceFormatFrank::Commit` (source `koDeviceFormatFrankLocal.cpp`) | Writes the whole database to the player when you press transfer/OK. Reports progress with `#INFO# 000/100`, `005/100`... |
| `CkoDeviceFormatFrank::SetContentId` | Writes a track's content ID (24 bytes at property 0x50029, plus 20 bytes at 0x50004) |
| `CkoDeviceFormatFrank::UpdateCidSidListOne`, `IsSupportCIDListFile`, `CheckCIDListFile` | Maintains `05CIDLST.DAT` (if the player supports it) |
| `CkoDeviceFormatFrank::CheckDamedaFile`, `ReadExtendedGroupTrees`, `ModifyItemCheck4ExtensionTrees` (`koDeviceFormatFrankExTree.cpp`) | The "extension trees" (`A_WM/EXTREExx.DAT`, `EXGINFxx.DAT`), and `07GTCHLG.DAT` |
| `CkoDeviceFormatFrank::GetDeviceBehaviorType`, `JudgeBehaviorType`, `StoreInitialBehaviorType`, `AppraiseCurrentBehaviorType`, `ChangeBehaviorTypeCiCoToGrouping` (`koDeviceFormatFrankBehavior.cpp`) | Decides how to treat the player, see below |
| `CAuraContentFileManager::LoadAuraData` / `MakeAuraDataToMediumContents`, `CAuraTree`, `CAuraMissingTrack::UpdateMissingTrackFile` | Reads and writes the `A_WM` data |
| `CAuraFuncManager` (+ `CPacFormatAuraMaintenanceLog`) | Maintains the mount log `AFMNTLOG.DAT` |
| `CGroupTreeManager::ReadGroupTreeFile` / `UpdateTreeListFile` | Reads and writes the `01TREExx`/`03GINFxx` files |
| `CPacFormatTreeListFile::IsFormatLeagal` | Validates the tree list |

Files the DLL has a name for (UTF-16 strings; `%02X` is a tree ID or a folder number):

```
OMGAUDIO\00GTRLST.DAT  01TREE%02X.DAT  02TREINF.DAT  03GINF%02X.DAT  04CNTINF.DAT  05CIDLST.DAT  05CHINDC.DAT
OMGAUDIO\07GTCHLG.DAT  08EDTERN.DAT  SRCIDLST.DAT/.BAK  ACTIVITY.DAT  MACLIST*.***   10F%02X\1000%04X.OMA
OMGAUDIO\20P??  and the format  20Pcc\1Gssssxx.JPG
OMGAUDIO\A_WM\AFMNTLOG.DAT  EXCNTINF.DAT  EXCNTMTA.DAT  EXGINF%02X.DAT  EXTREE%02X.DAT  ARTSTINF.DAT  ARTISTLK.DAT
         C2DETECT.DAT  MISCNIDL.DAT  MISCNMTD.DAT
OMGAUDIO\CONNECT\EXCNTMTA.DAT  ARTSTINF.DAT  DELCNLST.DAT
```

JSymphonic writes none of `05CHINDC`, `08EDTERN`, `EXGINF/EXTREE`, `ARTSTINF`, `ARTISTLK`, `C2DETECT`, `MISCN*`,
`CONNECT\*`, `SRCIDLST` and the player works without them. They were not studied further; they are the first place to
look if a feature (artist links, Windows Media Player transfers...) is missing.

## What SonicStage writes

### 1. A content ID for every track

When a track is transferred SonicStage writes a **content ID** in the track's own tag (the `EA3` header, offset 8)
and repeats it, with one element per title ID, in `05CIDLST.DAT`, `A_WM/EXCNTINF.DAT` and `A_WM/EXCNTMTA.DAT`.
The IDs are unique per track in the 379-track backup (379 distinct values). Observed shape:

```
00000000 010f5000 00040000 00 XX XX 57 9d91631b XXXXXXXX
```

**Inferred, not proven:** the player matches the elements of the `A_WM` files to tracks through the content ID, and
shows "incompatible database" when they don't fit. The evidence is circumstantial: with all content IDs at zero the
player still showed the error after the other files had been fixed, and it went away after the IDs were written
(together with two other changes, see [05](05-jsymphonic-implementation.md)). The A820 firmware does not read
`05CIDLST.DAT` at all, so on that model the ID in the `A_WM` files (or the track) is what matters, if anything does.

### 2. The mount log

After the database is written SonicStage stores in `AFMNTLOG.DAT` (`DTBL`) the modification times of `01TREE01.DAT`
and `04CNTINF.DAT` (in its backup both files, and the log, carry the same time, written within one 2 s step), and marks
its functions in `MLBL`: 1 `Kana`, 3 `ActivityLog`, 4 `LatestTrans`, each with version 1.0 and the time. The player compares these times with the files' times on every
start. The function names and the "Not Requested / Not Supported / Not Supported Function Version (Req = x.y, Sup = x.y)"
messages come from `CAuraFuncManager`: the player *requests* functions with a version, SonicStage says which it
supports. **Inferred:** the player's request list is why 5 and 6 are the player's.

### 3. The tree change log

`07GTCHLG.DAT` records the time at which SonicStage last wrote trees 02, 03, 04 and 36 (the trees that rely on
SonicStage's own sorting). **Inferred:** it is read back by `CheckDamedaFile`, to notice trees that were changed by
something else. The A820 firmware doesn't read it.

### 4. Everything derived from the tracks

All of `00GTRLST`, `01TREE01`-`04`, `2F`-`34`, `36`, their `03GINF`, `02TREINF`, `04CNTINF`. These are *rebuilt from
the list of tracks* and are completely determined by it (up to numbering, see below).

* **Sorting.** Trees whose groups have a sort name use the reading when there is one (`TSOP` for artists,
  `XSOT` in the group tags). Latin names, hiragana/katakana and kanji are ordered by their Unicode value inside a
  level in the trees without a sort name (`02`, `03`, `04`): in the backup the artists come Latin first, then
  katakana, then kanji. The kana initial groups of trees 32-34 fold small kana (`ァィゥェォッャュョヮヵヶ`) into the
  big ones (`アイウエオツヤユヨワカケ`). The exact collation of the other trees was not worked out; JSymphonic's
  `sort()` is an approximation which the player accepts.
* **Ratings (tree 2F).** Groups are `☆☆☆☆☆` ... `☆`, then an empty-named group for unrated tracks; a track is in a
  group according to byte 0x20 of its `EXCNTINF` element divided by 10.
* **Key values.** A group's key is the sum of the lengths (ms) of its directly contained titles; a tree's key in
  `02TREINF` is the total (all titles), or the sum of its keys for the player-filled trees.
* **Unused groups are not deleted.** SonicStage keeps the group elements of albums/artists that no longer have a
  track, at the end of the tree with level 0, and keeps the old group *numbers* (IDs are never reused while the
  file lives). A database rebuilt from scratch has fresh, contiguous numbers: **different bytes, same display**.

### 5. Album art

SonicStage embeds the cover into the OMA tag (`GEOB`, `OMG_FENCA1`, a 200x200 baseline JPEG). The three thumbnails
for tree 36 exist only on the player (in the `20Pxx` folders), generated by SonicStage with Intel's JPEG library;
**it can't regenerate them without the source files**, so a tool that rewrites the database must keep the ones that
are there, or rebuild them from the covers in the OMA files, which is what JSymphonic does.

## What SonicStage does **not** write

These are *owned by the player*; SonicStage never rebuilds them, they would be lost (or reset) if a tool recreated
them:

| Tree | Content | Filled by |
|---|---|---|
| 10-14, 15 | Bookmarks | player |
| 2D | Play history | player |
| 2E | Top 100 | player |
| 35 | Recent transfers (also touched by SonicStage when transferring) | player/SonicStage |
| 22 | Playlists | SonicStage |
| `ACTIVITY.DAT`, `MACLIST0.*`, `RESERVED.DAT` | player's logs | player |
| `PCNT` (play count) in `EXCNTMTA`, `0x20` rating in `EXCNTINF` | player (and SonicStage for ratings given on the PC) | both |

What SonicStage does with these trees when a track is deleted was **not observed** (it couldn't be run). What the
backup shows is the bookkeeping: group keys equal the sum of the lengths of their titles, and groups that are no
longer used sit at the end of a tree with level 0. JSymphonic removes the deleted track from these trees and keeps
that bookkeeping consistent, see [05](05-jsymphonic-implementation.md).

## Player "behaviour types"

`GetDeviceBehaviorType` reads the device information; for the product `ATRACHD1` (the ATRAC hard disk players) the
answer is "check-in/check-out", otherwise it sends a vendor command and tests bit 0x80 of a flag byte of the answer:
set means a Grouping player. SonicStage logs the result as:

| Log text | Value | Meaning |
|---|---|---|
| `e_DevBehavior_CheckInCheckOut` | 2 | Check-in/check-out players (older) |
| `e_DevBehavior_Grouping` | 4 | **Grouping players**, such as the NW-A8xx |
| `e_PacApiBehavior_Normal` ("ATRAC HDD 0.90") | 1 | Old hard-disk players |

`StoreInitialBehaviorType` also logs `TreeList Version : 0x%04X`, a version SonicStage reads from the tree list and
requires to be in `0x0100-0x01FF` for check-in/check-out players and `0x0200-0x02FF` for Grouping players; `0xFFFF` is
accepted as "unknown". Where this version sits in `00GTRLST.DAT` was **not identified**. JSymphonic's `00GTRLST.DAT`
(copied from SonicStage's layout) has been accepted by the player; this may be a point to check if a future player
refuses a tree list.
