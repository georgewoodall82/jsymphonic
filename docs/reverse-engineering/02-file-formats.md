# 02 - File formats

Everything here was read from databases written by SonicStage 4.3 for an NW-A808 (and by the players themselves),
and checked against the files JSymphonic writes. All numbers are **big endian** unless stated. `omgaudio_dump.py`
(in [tools](tools/)) parses all of it.

## The `OMGAUDIO` folder

```
OMGAUDIO/
  00GTRLST.DAT        list of the trees ("tree list")
  01TREExx.DAT        one tree per xx: groups + title IDs        (xx = tree ID, hexadecimal)
  02TREINF.DAT        name and key of each tree
  03GINFxx.DAT        one group-info file per tree: key and tags of each group
  04CNTINF.DAT        the tracks (title, artist, album, genre, length, codec...)
  04PATLST.DAT        JSymphonic only: where each track came from (not SonicStage, not read by the player)
  05CIDLST.DAT        content ID of each track
  07GTCHLG.DAT        change log of some trees (SonicStage)
  A_WM/AFMNTLOG.DAT   mount log: "this database matches these files"                   (extended players)
  A_WM/EXCNTINF.DAT   extended track info: rating                                       (extended players)
  A_WM/EXCNTMTA.DAT   extended track metadata: readings, year, track number, play count (extended players)
  ACTIVITY.DAT        2.5 MiB, written by the player (starts 01 01)
  MACLIST0.DAT/.BAK   32 KiB each, zeros in a new database
  RESERVED.DAT        4 MiB
  00010021.DAT, 0001001D.DAT   DRM key blocks (start with "EKB "); never touch
  10F00/10000001.OMA  the tracks: folder "10F" + 2 hex digits of (title ID >> 8), file "1000" + 4 hex digits of the title ID
  20P01/JM0001P0.JPG  album thumbnails
  db_backup/          JSymphonic only: copy of the database before JSymphonic first rewrote it
```

A **title ID** is the number of the track: `10F00/10000001.OMA` is title 1, `10F01/10000113.OMA` title 0x113 (275).
It indexes `04CNTINF.DAT`, `05CIDLST.DAT`, `EXCNTINF.DAT` and `EXCNTMTA.DAT` (element *n* is title *n*, 1-based, one
element per ID *including unused IDs*, which are zeros) and is what the trees refer to.

## The container shared by all `.DAT` files

```
0x00  4 bytes   table name        "GTLT", "TREE", "GPIF", "CNIF"...
0x04  4 bytes   version           01 01 00 00   (A_WM/AFMNTLOG.DAT: 01 00 00 00)
0x08  1 byte    number of classes (1 or 2)
0x09  7 bytes   zeros
0x10  16 bytes  per class: class name (4), offset of the class (4), length of the class (4), 4 zeros
...   (the classes, each starting at its offset, usually 0x20 or 0x30)
```

Each **class** starts with a 16 byte header, then the elements:

```
+0x00  4 bytes   class name         "GPLB", "CNFB"...
+0x04  2 bytes   number of elements
+0x06  2 bytes   length of one element
+0x08  4 bytes   extra value 1      see the table below
+0x0C  4 bytes   extra value 2
+0x10  ...       the elements, back to back; the class is padded with zeros up to its length in the description
```

| File | Table | Class (element length) | Extra values in the class header |
|---|---|---|---|
| `00GTRLST` | `GTLT` | `SYSB` (1 x 0x50), `GTLB` (54 x 0x50) | SYSB: `d0000000`, 0. GTLB: number of declared trees (21), `04070000` |
| `01TREExx` | `TREE` | `GPLB` (n x 8), `TPLB` (m x 2) | GPLB: number of *active* groups, 0. TPLB: number of titles, 0 |
| `02TREINF` | `GTIF` | `GTFB` (54 x 0x90) | 0 |
| `03GINFxx` | `GPIF` | `GPFB` (n x (0x10 + tags x 0x80)) | 0 |
| `04CNTINF` | `CNIF` | `CNFB` (n x 0x290) | 0 |
| `04PATLST` | `PALT` | `PATH` (n x 0x150) | 0 |
| `05CIDLST` | `CIDL` | `CILB` (n x 0x30) | 0 |
| `07GTCHLG` | `GTCL` | `BFCL` (n x 0x10) | 0 |
| `AFMNTLOG` | `AFML` | `DTBL` (1 x 0x80), `MLBL` (255 x 0x10) | 0 |
| `EXCNTINF` | `ARCI` | `ARCB` (n x 0x30) | 0 |
| `EXCNTMTA` | `EXCM` | `EXCB` (n x 0x2A0) | 0 |

### Tags

Text is stored as **tags**: 4 ASCII characters, 2 bytes `00 02` (encoding: UTF-16 big endian), then the text in
UTF-16BE, zero padded; a tag is **0x80 bytes** long, so the text is at most 61 UTF-16 code units. Tag names seen:

| Tag | Meaning |
|---|---|
| `TIT2` `TPE1` `TALB` `TCON` | title, artist, album, genre (ID3 names) |
| `TSOP` | artist **reading** (e.g. `ナカシマミカ` for `中島美嘉`); SonicStage fills it with the artist name when there is no reading |
| `XSOT` | the name used to **sort** a group (reading if there is one) |
| `PICP`, `PIC0` | album pictures (tree 36), see "Pictures" |
| `TSOT`, `TSOA`, `TYER`, `TRCK`, `PCNT` | title reading, album reading, year, track number, play count (`EXCNTMTA`) |

## `00GTRLST.DAT` - the tree list

54 elements of 0x50 bytes, one per possible tree ID (0x01-0x36). Element layout:

```
0x00  2 bytes  tree ID                 0x0002
0x02  2 bytes  tree type               0x0003
0x04  12 zeros
0x10  2 bytes  number of tags/levels   0x0001
0x12  2 zeros
0x14  tags     4 characters each, e.g. "TPE1"
```

The 21 trees SonicStage declares on the NW-A808 (the others are zero elements):

| ID | Type | Levels | Name in `02TREINF` | Content |
|---|---|---|---|---|
| 01 | 1 | 1 | (empty) | Albums, flat; each group has `TIT2` (album), `TPE1`, `TCON` tags |
| 02 | 3 | 1 | (empty) | Artists -> titles (tag `TPE1`) |
| 03 | 3 | 1 | (empty) | Albums -> titles (tag `TALB`) |
| 04 | 3 | 1 | (empty) | Genres -> titles (tag `TCON`) |
| 10-14 | 2 | 1 | `AR_BOKMK1_PL`..`AR_BOKMK5_PL` | Bookmarks 1-5: **filled in by the player** |
| 15 | 2 | 1 | `AR_DELBOK_PL` | "Delete Bookmark": filled in by the player |
| 22 | 2 | 0 | (empty) | Playlists (SonicStage) |
| 2D | 2 | 2 | `AR_HISTORY_PL` | Play history: month -> day -> titles; filled in by the player |
| 2E | 2 | 1 | `AR_TOP100_PL` | Top 100: filled in by the player |
| 2F | 4 | 1 | `AR_RATE_DB` | Ratings: 5 stars ... 1 star, unrated |
| 30 | 4 | 3 | `AR_TCON_DB_JPN` | Genre -> Artist -> Album |
| 31 | 4 | 2 | `AR_TYER_DB_JPN` | Year -> Artist |
| 32 | 4 | 1 | `AR_ITRCK_DB_JPN` | Initial of the title (kana, A-Z, `0-9`, `etc.`) |
| 33 | 4 | 3 | `AR_ITPE1_DB_JPN` | Initial of the artist -> Artist -> Album |
| 34 | 4 | 2 | `AR_ITALB_DB_JPN` | Initial of the album -> Album |
| 35 | 4 | 2 | `AR_LSTTRN_DB` | Recent transfers: `3rd Recent`, `2nd Recent`, `Most Recent` -> albums |
| 36 | 3 | 2 | `STD_TPE1_THUMBNAIL` | Artist -> Album with thumbnails |

A database that doesn't declare *exactly* this set is reported by the player as "incompatible database" (the earlier
bug reports for issue #1).

## `01TREExx.DAT` and `03GINFxx.DAT` - a tree

Two files describe a tree. `01TREExx` has the structure, `03GINFxx` the data of the groups.

**`GPLB`** (one 8 byte element per group, **in display order**):

```
0x00  2 bytes  ID of the group = position (1-based) of its element in 03GINFxx
0x02  1 byte   level: 1 = top level, 2 = below a level 1 group... ; 0 = unused group
0x03  1 byte   0
0x04  2 bytes  index (1-based) of the group's first title in TPLB; 0 if the group holds no title directly
0x06  2 bytes  0
```

The class length is at least 0x4010. A group holds the titles from its first index up to the first index of the next
group that has titles. SonicStage keeps deleted groups **at the end with level 0** (the GPLB header then counts fewer
*active* groups than elements). Parents of other groups hold no titles (index 0).

**`TPLB`** (2 bytes per title): the title IDs, in display order. Padded to a multiple of 16 bytes.

**`GPFB`** in `03GINFxx` (one element per group, in group-ID order):

```
0x00  8 bytes  zeros
0x08  4 bytes  key: sum of the length (ms) of the titles directly in the group; 0 for a parent group
0x0C  4 bytes  00 NN 00 80: NN tags, 0x80 bytes each
0x10  tags     NN x 0x80 bytes
```

The tags are `TIT2` (the name shown), plus `XSOT` (sort name, trees 2F-36), plus `PICP`/`PIC0` in tree 36
(`TIT2`, `PICP`, `PIC0`, `XSOT`) and in tree 01 (`TIT2`, `TPE1`, `TCON`, `TSOP`, `PICP`, `PIC0`).

Examples seen in a SonicStage database: tree 02 had 29 groups, 22 active, 379 titles. Tree 36 starts with an empty
first group (`TIT2` empty), then for each artist a level 1 group and a level 2 group per album.

### Pictures (tree 36 and playlists)

```
PICP  200200;20P02\JM0022P0.JPG
PIC0  096096;20P02\JM002201.JPG|032032;20P02\JM002202.JPG
```

`WWWHHH;folder\file` (size as 3+3 digits). Per album, three **baseline JPEGs** (200x200, 96x96, 32x32) in a `20Pxx`
folder, named `JMiiiiP0.JPG`, `JMiiii01.JPG`, `JMiiii02.JPG`. In SonicStage's database `iiii` is the hex ID of the album's
group in `03GINF36.DAT` (the one active picture in the backup: group 34 -> `JM0022`), and `xx` is that number modulo
0x20 (hex). JSymphonic names them after the album's lowest *title ID* instead, because group IDs change whenever
albums are added or removed, which would rename every thumbnail. Playlist pictures use another prefix (`II`), seen
in `03GINF22.DAT` of SonicStage's backup. The player only follows the path in the tag, so any unique name works.

## `02TREINF.DAT`

54 elements of 0x90 bytes: 8 zeros, the **key of the tree** (4 bytes), `00 01 00 80`, a `TIT2` tag with the tree name.
The key is the total length (ms) of all the tracks (`01`-`04`, `2F`-`34`, `36`), or the sum of the keys of the
groups having titles (`10`-`15`, `22`, `2D`, `2E`, `35`: those the player fills in).

## `04CNTINF.DAT` - the tracks

One element of 0x290 bytes per title ID:

```
0x00  2 bytes  0
0x02  2 bytes  protection: FFFF none, FFFE encrypted MP3, 0001 LSI DRM
0x04  4 bytes  file properties = the same 4 bytes as in the OMA file (codec and bit rate/sampling rate)
0x08  4 bytes  length in milliseconds (exact: the tag in the OMA file is rounded to the second)
0x0C  4 bytes  00 05 00 80: 5 tags of 0x80 bytes
0x10  tags     TIT2, TPE1, TALB, TCON, TSOP
```

File properties, first byte: `01` ATRAC3plus and `03` MP3 (seen); `02` is AAC (from JSymphonic's writer, not seen in
the SonicStage backup). The other 3 bytes encode bit rate and sampling rate (examples: `00 28 2e`, `80 dc 10`).

## `05CIDLST.DAT` - content IDs

One element of 0x30 bytes per title ID: the 0x18 byte **content ID** of the track, zero padded. For tracks with LSI
DRM the element is the DRM key (0x30 bytes). See [03](03-sonicstage-behaviour.md) for how the content IDs are built.

## `07GTCHLG.DAT` - tree change log

One 0x10 byte element per tree that SonicStage re-sorted, in the SonicStage backup the trees 02, 03, 04 and 36:

```
0x00  2 bytes  tree ID
0x02  6 bytes  zeros
0x08  8 bytes  time (BCD, see below)
```

Its purpose is **inferred**: remembering when the trees that depend on SonicStage's own sorting were last written. The
A820 firmware doesn't read it.

## `A_WM/AFMNTLOG.DAT` - mount log

Class `DTBL` (1 element of 0x80): bytes 0-7 the time of `01TREE01.DAT`, bytes 8-15 the time of `04CNTINF.DAT`, the rest
zeros. Class `MLBL` (255 elements of 0x10): element *n* describes **function** *n*:

```
0x00  1 byte   1 if the function has been processed, else 0
0x01  1 byte   0 (version "1.0": major.minor, inferred from messages in SonicStage)
0x02  6 bytes  zeros
0x08  8 bytes  the time (BCD) of the database it was processed with
```

Functions (names from SonicStage): 1 `Kana`, 2 `ArtistLink`, 3 `ActivityLog`, 4 `LatestTrans`, 5 `Bookmark`,
6 `DelBookmark`. SonicStage fills 1, 3 and 4 (a database that has only 1, after an eject, gained 5 and 6 and nothing
else, so the player writes only those two). **The player fills 5 and 6** with the same time as `DTBL` the first time
it accepts the database.

**BCD time**, 8 bytes: century, year, month, day, hour, minute, second, `00`, each as two decimal digits:
`20 10 02 27 13 31 02 00` is 2010-02-27 13:31:02. It is the **local** time of the FAT file system, to 2 seconds.
How the player uses all this: [04](04-player-firmware.md).

## `A_WM/EXCNTINF.DAT` - rating (`ARCI`/`ARCB`, 0x30 bytes per title ID)

```
0x00  0x18 bytes  content ID (same as 05CIDLST)
0x18  8 bytes     zeros
0x20  1 byte      rating on a 0-50 scale (the firmware divides by 10 to get 0-5 stars)
0x21  1 byte      FF = not set
...   zeros
```

In SonicStage's backup the rating byte took many values (30, 38, 40, 43, 49...), not only multiples of 10, so the
scale is finer than whole stars. JSymphonic writes `00 FF` for tracks it doesn't know.

## `A_WM/EXCNTMTA.DAT` - metadata (`EXCM`/`EXCB`, 0x2A0 bytes per title ID)

The 0x20 byte content ID header as above, then five tags: `TSOT` (title reading), `TSOA` (album reading), `TYER`,
`TRCK`, `PCNT` (play count, written by the player). Element 1 of SonicStage's backup: `Flag` / `NAMELESS WORLD` /
`2005` / `1` / `10`.

## The OMA files (`10Fxx/1000xxxx.OMA`)

An OMA file is an ID3-like tag, an "EA3" header and the audio stream.

1. **`ea3` tag.** `65 61 33`, version (2 bytes), flags, size (4 bytes, 7 bits each; the size does not include the
   10 byte header). Then ID3v2.3-like frames: id (4), size (4, normal 32-bit), flags (2), value; text values start
   with encoding `02` (UTF-16BE). Frames written by SonicStage:
   `TIT2 TPE1 TALB TCON TYER TCOM TLEN` (TLEN in ms, rounded to the second) and `TXXX` frames whose value is
   `name\0value` (`OMG_TPE1S` artist reading, `OMG_TRACK`, `OMG_AGENR`, `OMG_ALBMS` album reading, `OMG_ASGTM`,
   `OMG_ATPE1`, `OMG_TIT2S` title reading, `OMG_TRLDA` release date `2005/01/01 00:00:00`), and `GEOB` frames
   (`OMG_BKLSI`, `OMG_OLINF`...) which are binary. An empty `OMG_TRACK`/`OMG_TRLDA` value is legal.
   The tag is padded with zeros to a boundary (0xC00 is the usual end of the tag).
2. **Cover.** One `GEOB` frame, MIME `image/jpeg`, description `OMG_FENCA1` (UTF-16BE), holding a **200x200 baseline
   JPEG**. JSymphonic pads the tag to `0xC00 + cover frame size rounded up to 0x80`.
3. **`EA3` header** (0x60 bytes) at the end of the tag:

   ```
   0x00  "EA3"
   0x03  1 byte   version: 01 for ATRAC3plus files, 02 for wrapped MP3
   0x04  2 bytes  00 60 (size)
   0x06  2 bytes  protection: FFFF none, FFFE encrypted MP3, 0001 LSI DRM
   0x08  0x18     content ID (zeros for LSI DRM files: the DRM key goes there)
   0x20  4 bytes  file properties (see 04CNTINF)
   0x24  MP3: length in ms (4), number of frames (4); AAC: length (4), a derived number (4); WMA: length, a number
   ```
4. **Audio data.**

Content IDs of SonicStage 4.3: `00000000 010f5000 00040000 00 XX XX 57 9d91631b XXXXXXXX`. 18 of the 24 bytes
are constant in the 379 tracks of the backup, 6 vary (`XX`).

There was no checksum that could be found in the content ID, and its meaning is **unknown**. JSymphonic generates the
same shape (constant bytes as above, random `XX` bytes) and copies existing IDs unchanged.
