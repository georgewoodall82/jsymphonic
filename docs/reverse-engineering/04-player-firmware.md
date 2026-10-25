# 04 - What the player checks

How the NW-A80x/A82x firmware decides to start in Simple Mode, and what it does when it accepts the database. Findings
from the **NW-A820 firmware V1.02** (its A808 sibling could not be read, see below), checked against before/after
copies of the `OMGAUDIO` folder of a real **NW-A808**.

## Getting the firmware

| Item | Value |
|---|---|
| Updater | `NW-A820_V1_02.exe` (Sony's firmware update program), MD5 `7ed36a5255b90f8ced476713a5cde81b` |
| Inside | a Microsoft cabinet (`7z x` opens it) with `NW_WM_FW.UPG`, MD5 `fa3c6ae0152eb26c9ce94570735f0318` |
| `.UPG` | encrypted container ("UPG v1", the format handled by Rockbox's `nwztools`); once decrypted, the third member is a gzip'd tar of the root file system |
| Program | `usr/local/bin/gemini-ICX1115`, stripped 32-bit ARM ELF, loaded at 0x8000 |
| Languages | `ICX-COMMON_wording.dpk` and friends: compressed; "Simple Mode" / "Advance Mode" are in the language-independent block |

The NW-A800 series' update package is protected in a way that the tools used could not open, so the A808 firmware was
not analysed. Everything below that is about firmware is about the A820; it matches what the A808 does in every test
that was done, but that is a *hypothesis* for anything not tested.

Disassemble with `arm-none-eabi-objdump -D -b binary -m arm gemini-ICX1115`. Listed addresses are **file offsets**;
pointers inside the code are virtual addresses = file offset + 0x8000 (see [01](01-methodology.md)).

## Simple Mode

The player shows a "Simple Mode" popup when it starts the UI in a restricted profile: only basic features are
enabled. The profile comes from one global word, set by a routine that runs two checks on `A_WM/AFMNTLOG.DAT`
(file offsets in the listing):

| What | Where | Description |
|---|---|---|
| Popup | `0x281dc` | Opens the "Simple Mode" page (id 0x49) when the profile is 1 or 2 |
| Mode word | global at vaddr `0x6a812c`, set at `0x2ba9e4` | 1 = simple, 2 = "skip" (not studied), anything else = full mode |
| Check 1 | `0x2ba60c` | The two times of `DTBL` must be equal to the modification times of the files. A missing log counts as a mismatch |
| Check 2 | `0x2ba814` | Element 1 of `MLBL` must have the "processed" flag set to 1 and hold the time of `01TREE01.DAT` |
| Time decoders | `0x2c2dac` (BCD -> time), `0x2b8e3c` (file time -> time) | |
| Player's own writes | `0x2b921c` | Writes `MLBL` elements 5 and 6 |

**Check 1 in detail.** The routine opens `A_WM/AFMNTLOG.DAT`; if it doesn't exist (apparently a "not found" error,
0x501) the result is "no match".
It decodes the first `DTBL` time and the second (the BCD format in [02](02-file-formats.md)), and logs them with the
strings `AFMNTLOG.DAT: BasicTree` and `AFMNTLOG.DAT: ContentFringe`. So the firmware's names are:

| Firmware name | `DTBL` time | File compared |
|---|---|---|
| BasicTree | first | `01TREE01.DAT` |
| ContentFringe | second | `04CNTINF.DAT` |

It then reads the two files' modification times and compares each with its stored time, field by field, down to the
second. Any difference -> not valid -> Simple Mode.

**Check 2.** The same log, class `MLBL`: the first element must say "1" and carry the same time as `01TREE01.DAT`.

A database written by a program that doesn't know about the log fails both checks. Copying the folder (new modification times), editing a tree, or adding a track without rewriting the log all
bring the player back to Simple Mode.

### Experiment that confirmed it

A copy of the player's folder before and after one eject, written by JSymphonic (the build with the mount log):

* `AFMNTLOG.DAT` was the only file that changed.
* `DTBL` and element 1 were untouched (they matched the files), and the player had **added elements 5 and 6** with
  the same time. The player only does this once it has accepted the database, so it had processed the database in
  full mode. (The two messages were still shown, which led to the content ID work: see [05](05-jsymphonic-implementation.md).)
* `AFMNTLOG.DAT` itself had the player's own clock as modification time (2007-01-10): the player's clock was never
  set. The log's own time is not used by the checks.

## "Incompatible database"

The A820 firmware's code for this message could not be located: messages are compressed and the checks are spread
over a generic "database" layer. What is known:

* The first time (extended layout missing: not 21 trees, no `A_WM` folder) the message appeared with the *old*
  database, and disappeared after the layout was copied from a SonicStage database. So **the tree list must be exact**.
* After the mount log was accepted (Simple Mode gone) the message reappeared. At that point the player reads files it
  hadn't read before. `A_WM/EXCNTINF.DAT` is read by the A820 UI (path string at `0x4cc6bc`); a loop over the
  elements takes byte `0x20` divided by 10 (stars) and byte `0x21` (`FF` = 0) and stores them per title. Writing
  `EXCNTINF.DAT`/`EXCNTMTA.DAT` (with zero content IDs) was not enough on the A808.
* What the A820 firmware does **not** reference: `05CIDLST.DAT` (only `SRCIDLST.DAT`), `EXCNTMTA.DAT`, `07GTCHLG.DAT`.
  The A808 (a different, older model) may read them; unknown.
* The message went away when the **content IDs** were written (together with `MLBL` elements 3 and 4 and
  `07GTCHLG.DAT`), so it is **inferred** that the player cross-checks the `A_WM` elements with the tracks (their IDs).
  Which of the three changes was the decisive one is **unknown**: they were changed at the same time.

## Practical rules distilled from the above

1. After writing the database, set the modification time of `01TREE01.DAT` and `04CNTINF.DAT` (the same value, even
   number of seconds), read it back from the file system, and write it in `AFMNTLOG.DAT` (`DTBL`, `MLBL` 1, 3, 4).
2. Never change a database file after that without redoing step 1.
3. Keep the tree list and the 21 trees exactly as SonicStage writes them.
4. One element per title ID in `05CIDLST`, `EXCNTINF`, `EXCNTMTA`, all starting with the same content ID as the track.
5. Leave `MLBL` elements the player wrote (5, 6) alone.
6. Preserve the player's own trees and logs.

## Questions that are still open

* Which of the three final changes (content IDs, `MLBL` 3-4, `07GTCHLG`) is required by an NW-A808?
  A build with only one of them would tell. (It doesn't matter for users, who get all three.)
* Where the A808 differs from the A820: unknown without the A808 firmware.
* What `ArtistLink` (MLBL 2), `CONNECT\*`, `EXTREE`/`EXGINF` files, `MISCN*`, `C2DETECT` do.
* The meaning of the content ID's fixed bytes.
