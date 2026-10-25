# 01 - Methodology

How the NW-A80x problems were solved, in an order that can be repeated for another player.

Convention used in all the documents: **observed** means seen directly in a file, a disassembly or a test result,
**inferred** means deduced from observations but not confirmed, **unknown** means not determined.

## 1. Evidence sources, from cheapest to most expensive

1. **A database written by SonicStage** for the same player (a plain copy of the `OMGAUDIO` folder). Compare it,
   file by file, with what your program writes. This answers most questions about *layout*.
2. **Before/after copies of the player's folder** around one event (plug in, eject, play a song, rate a song). The
   files that change tell you what the *player* writes and when. Here, a before/after of one eject showed that only
   `AFMNTLOG.DAT` changed, with two new elements written by the player, which proved that the player had accepted the
   mount log and processed the database in full mode.
3. **A diff of two databases written by your program** (before and after a change, or two runs). Cheap regression
   test; see "Round-trip test" below.
4. **The SonicStage binaries.** Debug strings in them name the files they read and write, and often the methods.
5. **The player firmware.** It tells what the *player* verifies. Most expensive, but the only way to know why a
   given message is shown.
6. **Test builds on the real player.** Needed for the final answer, and slow: the reporter has to copy the files and
   look at the screen. Use steps 1-5 to make every test count, and change as few things as possible per test.

## 2. The playbook for a new player

1. Get a SonicStage backup of the player (copy the `OMGAUDIO` folder with a handful of tracks, or a lot), and a copy
   written by JSymphonic with the same tracks.
2. `python3 tools/omgaudio_dump.py diff SONICSTAGE_DIR JSYMPHONIC_DIR` lists the files that exist on only one side
   and the classes that differ. Anything that exists only in SonicStage's folder is suspect (here: the whole `A_WM`
   folder, `07GTCHLG.DAT`, `20Pxx`).
3. `python3 tools/omgaudio_dump.py mountlog DIR` and `ids DIR` run the checks documented in
   [04](04-player-firmware.md) and [03](03-sonicstage-behaviour.md).
4. Search the SonicStage components for the file names that matter (next section), and read the code around them.
5. If a message on the player is still unexplained, unpack the firmware (next section) and find the string or the
   file name in the UI program.
6. Write the smallest change that produces what SonicStage produces, check it with the tools, and ask the reporter to
   test it with a clean copy of the folder.
7. Run the round-trip test (below) before releasing.

## 3. Getting at the SonicStage components

The installer used was `SonicStage_4.3.01.14280-ULTIMATE.iso` (a SonicStage 4.3 image). Nothing was run: the files
were only unpacked and read.

```
7z x SonicStage_4.3.01.14280-ULTIMATE.iso -oiso
```

* `iso/SonicStage/ss/English/data1.cab` and `.../addon/English/data1.cab` are **InstallShield** cabinets (with a
  `data1.hdr`). `7z` can't open them; build [unshield](https://github.com/twogood/unshield) and run
  `unshield -d out x data1.cab`. (On Fedora the shared library failed to link without `-fPIC`; building with
  `-DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_STATIC=ON` fixed it.)
* `iso/Common/OpenMG/Data1.cab` is a plain Microsoft cabinet, `7z x` opens it. **This is where the code that writes
  the player's database lives**, as `*PACAPI.dll` files:

  | DLL | Players (inferred from the names and the files it knows) |
  |---|---|
  | `AuraPACAPI.dll` | NW-A8xx "Aura" family: the only one that mentions `AFMNTLOG`, `EXCNTINF`, `EXCNTMTA`, `MLBL`, `DTBL` |
  | `FrankPACAPI.dll`, `MsProPACAPI.dll` | Older players and Memory Stick devices: know `00GTRLST`, `04CNTINF`, `02TREINF`... but not the `A_WM` files |

Finding the right DLL is a one-liner, because the names are in the binary as UTF-16 strings:

```
for p in AFMNTLOG EXCNTINF 04CNTINF; do grep -rlaP "$(python3 -c "print(''.join(c+'\x00' for c in '$p'))")|$p" out_omg; done
strings -a -el AuraPACAPI.dll | grep -iE '\.DAT|OMGAUDIO|A_WM|20P'   # -el = UTF-16LE
```

## 4. Reading `AuraPACAPI.dll`

It is a 1.6 MB, 32-bit, C++ DLL with no symbols, but its logging macros embed the **source file name, the line
number and the method name** in the binary (for example `.\koDeviceFormatFrankLocal.cpp`,
`CkoDeviceFormatFrank::Commit`, `CAuraFuncManager`). That is what makes it readable:

1. Decompile every function once, headless, to one text file (the interactive REA/Ghidra MCP request timed out on
   this DLL, headless Ghidra took a few minutes): `tools/ghidra/DumpAll.java`.

   ```
   analyzeHeadless PROJECT_DIR aura -import AuraPACAPI.dll \
       -scriptPath tools/ghidra -postScript DumpAll.java /path/aura.c
   ```
2. Give the functions names from the method-name strings they contain (a few lines of Python: for each decompiled
   function, grep `"C[A-Za-z0-9_]+::[A-Za-z0-9_~]+"`). Most of the interesting functions get a name that way.
3. Find who uses a file name with `tools/ghidra/Callers.java` (it prints the callers, recursively, of the function
   that contains the string) and walk up to a named function (`CkoDeviceFormatFrank::Commit` was the one that
   writes the whole database).
4. Read the code *only as far as needed*: the goal is to learn **what** is written, not to reproduce the code.

Useful facts about reading it:
* Strings are `wchar_t` strings; Ghidra shows them as `L"..."`.
* Tag names (`TIT2`, `TSOP`...) and class names (`CNFB`) appear as 4-byte constants.
* Dates are formatted with `wsprintf("%04d%02d%02d%02d%02d%02d")`, then packed to BCD (see the mount log).
* Hex dumps of the files are the best decompiler: when the code is hard to read, fall back to *what the file
  contains* and look for the field in the code afterwards.

## 5. Reading the player firmware

The NW-A820 firmware (V1.02) was used because it could be unpacked (see [04](04-player-firmware.md) for the
files and checksums). In short:

1. The updater `.exe` contains a Microsoft cabinet, `7z x` extracts `NW_WM_FW.UPG`.
2. The `.UPG` is an encrypted container (the format is the one handled by Rockbox's `nwztools`); the decrypted
   members include a gzip'd tar of the root file system.
3. The player's program is `usr/local/bin/gemini-ICX1115`, a stripped 32-bit ARM ELF.
4. Disassemble the file as a raw ARM binary: `arm-none-eabi-objdump -D -b binary -m arm gemini-ICX1115 > gemini.asm`.
   There is no decompiler step here: the useful code is small and can be read as assembly, guided by the
   **strings** (`strings -a -t x gemini-ICX1115`). In this listing the addresses are **file offsets**, while the
   pointers *inside* the code are virtual addresses, which are the file offset **plus 0x8000** (the load address of
   the program). For example the string at offset `0x4cf7c0` is referred to as `0x004d77c0`.
5. To find the code that uses a string, search the disassembly for the string's *virtual* address written as an
   8-digit hex word: ARM code loads it from a *literal pool* next to the function (`004d77c0` shown as the data word
   at offset `2ba80c`).
6. Translated UI messages are in compressed language resources (`*.dpk`), so grepping the binary for "Simple Mode"
   finds nothing useful; follow the *logic* (which file, which comparison) rather than the text.

## 6. Round-trip test (the regression test that found the data-loss bugs)

Take a copy of the SonicStage database and run these three, then compare:

| Run | What it is |
|---|---|
| A | Open the copy with JSymphonic, add a track, save. Then remove that track, save. |
| B | Open another copy and save without any change. |
| C | The untouched original. |

* **A and B must be identical**, apart from the two timestamp-carrying logs (`07GTCHLG.DAT`, `A_WM/AFMNTLOG.DAT`).
  If not, adding/removing a track leaves something behind.
* **B and C show what JSymphonic rewrites** compared with SonicStage. Anything *lost* (history, Top 100,
  bookmarks, playlists, readings, exact lengths, album art, rating, play counts) is a bug; anything *different but
  equivalent* (group numbering) is to be understood and documented.

`omgaudio_dump.py diff A B` lists the differing files and classes.

For automated runs, a small Java harness around `NWOmgaudio` (scheduleImport/scheduleDeletion, applyChanges, wait for
the termination event) was used; there is no UI involved.

## 7. Mistakes and surprises worth remembering

* **Two messages, two causes.** "Simple Mode" and "Incompatible database" were separate problems in separate files.
  Fixing the first made the second appear (the player only reads the files behind the second once it trusts the
  first).
* **File modification times are part of the database.** The mount log stores them, so copying or restoring files
  (which changes their times) silently puts the player back in Simple Mode. FAT stores times with a 2-second
  resolution, in local time; the writer rounds to 2 s and then *reads the time back* before storing it.
* **A test kit built by copying files must refresh the times.** Files copied from a backup get new times, which
  breaks the mount log; every test set needs the timestamps and the log rewritten after copying.
* **Zero is not "no content ID".** Every track had the same ID (zero), which is "valid" but made all the tracks look
  like one.
* **SonicStage rounds the length in the OMA tag to whole seconds** but stores exact milliseconds in `04CNTINF.DAT`;
  rebuilding the database from the tags loses precision unless the old `04CNTINF.DAT` is read first.
* **Rebuilding wipes the player's own data.** The history, Top 100, bookmarks and recent transfers are in the files
  that a rewrite deletes. They must be read before the old database is erased.
* **Empty values.** SonicStage writes empty `OMG_TRLDA` (date) and `OMG_TRACK` values; parsing them as numbers
  threw an exception that silently left the title with default values (including a 210 s length).
* **The temporary directory fills up.** Unpacked installers and database copies are large; delete what is not needed
  or the tools fail with confusing "disk quota" errors.
* **Know what you cannot do.** The A808's own firmware package could not be unpacked with the tools available, so
  the firmware analysis was done on the A820. The two models are related but not identical: treat the A820 results
  as strong hints, not as facts about the A808.
