# Reverse engineering SonicStage and the NW-A80x database

This folder documents how JSymphonic learned to write a database that the NW-A80x/A82x Walkmans (tested on an
NW-A808) accept in full mode, by studying what Sony's own software, SonicStage, writes and what the player's
firmware checks. It is meant for the next person who has to make JSymphonic (or any other tool) work with a
Walkman that shows "Simple Mode", "Incompatible database" or blank album art.

The work started with [issue #1](https://github.com/georgewoodall82/jsymphonic/issues/1) (a Japanese NW-A808 that
showed an "incompatible database" error after JSymphonic had written the database, and later "Simple Mode" and blank
album art). Thanks to the reporter, who tested every build on the real player and supplied a SonicStage backup and
before/after copies of the player's `OMGAUDIO` folder.

## Documents

| Document | What it covers |
|---|---|
| [01-methodology.md](01-methodology.md) | How the investigation was done, the tools used, a playbook for the next device, and the mistakes to avoid. |
| [02-file-formats.md](02-file-formats.md) | The format of every file of the `OMGAUDIO` folder and of the OMA tracks. |
| [03-sonicstage-behaviour.md](03-sonicstage-behaviour.md) | What SonicStage writes, when, and what it keeps (content IDs, mount log, change log, ratings, thumbnails...). |
| [04-player-firmware.md](04-player-firmware.md) | What the player firmware checks when it starts: Simple Mode, incompatible database. |
| [05-jsymphonic-implementation.md](05-jsymphonic-implementation.md) | What JSymphonic does now, where in the code, how it was tested, and what is still different from SonicStage. |
| [tools/](tools/) | `omgaudio_dump.py` (dump and check a database) and two Ghidra scripts used on SonicStage. |

## The short version

The player starts in **Simple Mode** (basic features only) unless `OMGAUDIO/A_WM/AFMNTLOG.DAT` records the exact
modification times of `01TREE01.DAT` and `04CNTINF.DAT`, and the first element of its MLBL class is set. Any program
that writes the database (or even copies the files, which changes their times) must rewrite this log.

Once the player trusts the database it reads more files, and shows **Incompatible database** if they don't match the
rest: `A_WM/EXCNTINF.DAT` and `A_WM/EXCNTMTA.DAT` (rating, readings, play count) must have one element per title
with the same **content ID** as `05CIDLST.DAT` and as the OMA file. SonicStage gives every track its own content ID,
writes it in the track's tag, and repeats it in all these files. JSymphonic used to leave it zero.

Album art is stored in two places: a 200x200 JPEG in each OMA file (GEOB frame `OMG_FENCA1`), and three JPEG
thumbnails per album in the `20Pxx` folders, referenced by the `PICP`/`PIC0` tags of tree 36.

When rewriting a database that SonicStage wrote, the trees filled in by the player (history, Top 100, bookmarks,
recent transfers) and the playlists must be preserved, otherwise the player's data is lost.

## Status

| Item | Status |
|---|---|
| Extended database layout (21 trees, `A_WM` files) | Done, confirmed on an NW-A808. |
| Cover art in OMA files and album thumbnails | Done, confirmed on an NW-A808. |
| Mount log (`AFMNTLOG.DAT`), content IDs, change log (`07GTCHLG.DAT`) | Done, confirmed working on an NW-A808. The exact one of these that cleared the "Simple Mode" and "incompatible database" messages was not isolated (they were changed together). |
| Keeping SonicStage's history/Top 100/bookmarks/playlists, exact lengths, readings, thumbnails | Done and checked with a byte-for-byte round trip, see [05](05-jsymphonic-implementation.md). |
| Other models (NW-A91x, NW-A82x, NW-A84x...) | Untested. The A82x firmware was analysed, the A808 firmware could not be read. |

## What is not in this repository

No Sony software, firmware or installer is included, and none is needed to read these documents. Everything was
observed on files the investigators legitimately owned (a SonicStage 4.3 installer, a SonicStage backup of a player,
Sony's public firmware updater). To reproduce the analysis you have to obtain your own copies. The documents describe
file formats and behaviour for interoperability; where a statement is an inference rather than an observation, it is
marked as such.
