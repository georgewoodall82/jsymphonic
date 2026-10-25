/*
 * This file is part of JSymphonic program.
 *
 * JSymphonic is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JSymphonic is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JSymphonic. If not, see <http://www.gnu.org/licenses/>.
 *
 *****
 *
 * DataBaseOmgaudioExtendedToolBox.java
 *
 */

package org.naurd.media.jsymphonic.toolBox;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.naurd.media.jsymphonic.title.Oma;
import org.naurd.media.jsymphonic.title.Title;

import static org.naurd.media.jsymphonic.toolBox.DataBaseOmgaudioToolBox.*;

/**
 * Writes the "extended" OMGAUDIO database used by the NW-A80x/NW-A91x Walkmans.
 *
 * On top of the base trees (01 to 04 and 22), these players expect 21 trees to be declared in 00GTRLST: bookmarks (10 to 15),
 * history (2D), top 100 (2E), ratings (2F), genre/year/initial browsing (30 to 34), recent transfers (35) and
 * artist/album with thumbnails (36). If the database doesn't have this exact layout, the player displays an
 * "incompatible database" error. The layout has been copied from a database created by SonicStage on a NW-A805.
 *
 * Each tree is stored in two files:
 * - 01TREEXX.DAT, holding the GPLB class (the groups of the tree, with their level and the index of their first title in the TPLB class) and the TPLB class (the title IDs, in the order they should be displayed),
 * - 03GINFXX.DAT, holding the GPFB class (one element per group of the GPLB class, giving its key and its name).
 */
public class DataBaseOmgaudioExtendedToolBox {
/* FIELDS */
    // Tags used in the 03GINFXX files
    private static final String[] TAGS_TITLE = {"TIT2"};
    private static final String[] TAGS_TITLE_SORT = {"TIT2", "XSOT"};
    private static final String[] TAGS_TITLE_PICTURES_SORT = {"TIT2", "PICP", "PIC0", "XSOT"};

    // Initials used by the AR_ITxxx trees (32, 33 and 34), in the order they are displayed by the device
    private static final List<String> INITIALS = Arrays.asList(
            "ア", "イ", "ウ", "エ", "オ", "カ", "キ", "ク", "ケ", "コ", "サ", "シ", "ス", "セ", "ソ", "タ", "チ", "ツ", "テ", "ト",
            "ナ", "ニ", "ヌ", "ネ", "ノ", "ハ", "ヒ", "フ", "ヘ", "ホ", "マ", "ミ", "ム", "メ", "モ", "ヤ", "ユ", "ヨ",
            "ラ", "リ", "ル", "レ", "ロ", "ワ", "ヲ", "ン",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            "0-9", "etc.");
    private static final String SMALL_KANA = "ァィゥェォッャュョヮヵヶ";
    private static final String BIG_KANA = "アイウエオツヤユヨワカケ";

    // Trees declared in 00GTRLST and 02TREINF, in the order used by SonicStage: {tree ID, type, number of levels}
    private static final int[][] TREES = {
        {0x01, 1, 1}, {0x02, 3, 1}, {0x03, 3, 1}, {0x04, 3, 1},
        {0x10, 2, 1}, {0x11, 2, 1}, {0x12, 2, 1}, {0x13, 2, 1}, {0x14, 2, 1}, {0x15, 2, 1},
        {0x22, 2, 0}, {0x2D, 2, 2}, {0x2E, 2, 1}, {0x2F, 4, 1},
        {0x30, 4, 3}, {0x31, 4, 2}, {0x32, 4, 1}, {0x33, 4, 3}, {0x34, 4, 2}, {0x35, 4, 2}, {0x36, 3, 2}
    };

    // Device files created by SonicStage which are kept untouched if they exist: {name, size}
    private static final Object[][] DEVICE_FILES = {
        {"MACLIST0.DAT", 0x8000}, {"MACLIST0.BAK", 0x8000}, {"ACTIVITY.DAT", 0x280000}, {"RESERVED.DAT", 0x400000}
    };

    // Trees filled in by the device (bookmarks, history, top 100 and recent transfers) and by SonicStage (playlists), kept when the database is rewritten
    private static final String[] KEPT_TREES = {"10", "11", "12", "13", "14", "15", "22", "2D", "2E", "35"};

    // Album thumbnails of tree 36, as written by SonicStage: the big picture (PICP tag) then the small ones (PIC0 tag)
    private static final int[] THUMBNAIL_SIZES = {200, 96, 32};
    private static final String[] THUMBNAIL_SUFFIXES = {"P0", "01", "02"};
    private static final String THUMBNAIL_FILES = "(?i)JM[0-9A-F]{4}(P0|01|02)\\.JPG";

    // Mount log, holding the modification time of 01TREE01.DAT and 04CNTINF.DAT
    private static final String MOUNT_LOG = "AFMNTLOG.DAT";
    private static final int MOUNT_LOG_SIZE = 0x10c0;
    private static final int MOUNT_LOG_TIMES = 0x40; // Position of the two times in the DTBL class
    private static final int MOUNT_LOG_FUNCTIONS = 0xd0; // Position of the first element of the MLBL class
    // Elements of the MLBL class written by SonicStage (Kana, ActivityLog and LatestTrans). The others are written by the device (Bookmark and DelBookmark) or not used (ArtistLink)
    private static final int[] MOUNT_LOG_PC_FUNCTIONS = {1, 3, 4};

    // Tree change log, holding the modification time of the trees written by SonicStage
    private static final String TREE_CHANGE_LOG = "07GTCHLG.DAT";
    private static final int[] TREE_CHANGE_LOG_TREES = {0x02, 0x03, 0x04, 0x36};

    // Extended content info written by SonicStage in A_WM: one element per title ID, starting with the element of the title in 05CIDLST
    private static final String CONTENT_INFO = "EXCNTINF.DAT"; // Rating of the titles
    private static final String CONTENT_META = "EXCNTMTA.DAT"; // Title and album readings, year, track number and play count
    private static final int CONTENT_INFO_LENGTH = 0x30;
    private static final int CONTENT_META_LENGTH = 0x2a0;
    private static final int CONTENT_ID_LENGTH = 0x20;
    private static final int CONTENT_RATING = 0x20; // Position of the rating in the EXCNTINF elements: 10 points per star
    private static final String[] CONTENT_META_TAGS = {"TSOT", "TSOA", "TYER", "TRCK", "PCNT"};

    private static Logger logger = Logger.getLogger("org.naurd.media.jsymphonic.toolBox.DataBaseOmgaudioExtendedToolBox");

    /**
     * Gives the label of a title for one level of a tree (the artist name, the album name,...).
     */
    private interface Labeler {
        String label(Title title);
    }

    /**
     * A level of a tree.
     */
    private static class Level {
        Labeler labeler; // Gives the name of the group a title belongs to
        Labeler sortKey; // Gives the key used to sort the groups
        boolean withSortTag; // True if the XSOT tag should be filled in with the name of the group
        List<String> fixedGroups; // If not null, all these groups are written, in this order, even if they are empty

        Level(Labeler labeler, Labeler sortKey, boolean withSortTag, List<String> fixedGroups) {
            this.labeler = labeler;
            this.sortKey = sortKey;
            this.withSortTag = withSortTag;
            this.fixedGroups = fixedGroups;
        }
    }

    /**
     * A group of a tree, i.e. an element of the GPLB class and its corresponding element of the GPFB class.
     */
    private static class Group {
        int level; // Level of the group in the tree (1 for the top level)
        int key; // Sum of the length of the titles directly in the group
        String[] tagValues; // Values of the tags in the 03GINFXX file
        int firstTitle; // Index (starting at 1) in the TPLB class of the first title of the group, 0 if the group holds no title directly
        List<Title> titles; // Titles directly in the group, null if the group isn't in the last level

        Group(int level, int key, String[] tagValues, int firstTitle) {
            this.level = level;
            this.key = key;
            this.tagValues = tagValues;
            this.firstTitle = firstTitle;
        }
    }

    /**
     * What is kept from the database on the device before it is rewritten.
     */
    public static class OldDatabase {
        private Map<String, byte[][]> trees = new HashMap<String, byte[][]>(); // Tree ID -> content of its 01TREEXX and 03GINFXX files
        private Map<Integer, byte[]> contentIds = new HashMap<Integer, byte[]>(); // Title ID -> start of its element in 05CIDLST
        private Map<Integer, Object[]> titles = new HashMap<Integer, Object[]>(); // Title ID -> name and length in 04CNTINF
        private Map<Integer, String[]> pictures = new HashMap<Integer, String[]>(); // Title ID -> PICP and PIC0 tags of its album in tree 36
    }

    private static final Labeler GENRE = new Labeler() { public String label(Title t) { return clean(t.getGenre()); } };
    private static final Labeler ARTIST = new Labeler() { public String label(Title t) { return clean(t.getArtist()); } };
    private static final Labeler ALBUM = new Labeler() { public String label(Title t) { return clean(t.getAlbum()); } };
    private static final Labeler YEAR = new Labeler() { public String label(Title t) { return t.getYear() > 0 ? String.valueOf(t.getYear()) : ""; } };
    // Unknown year first, then the most recent years first
    private static final Labeler YEAR_SORT = new Labeler() { public String label(Title t) { return t.getYear() > 0 ? String.format("%05d", 99999 - t.getYear()) : ""; } };
    private static final Labeler TITLE_INITIAL = new Labeler() { public String label(Title t) { return initialOf(t.getTitle()); } };
    private static final Labeler ARTIST_INITIAL = new Labeler() { public String label(Title t) { return initialOf(t.getArtist()); } };
    private static final Labeler ALBUM_INITIAL = new Labeler() { public String label(Title t) { return initialOf(t.getAlbum()); } };


/* METHODS */
    /**
     * Write 00GTRLST file in OMGAUDIO folder, declaring all the trees of the extended database.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder where to write the file.
     * @throws Exception
     */
    public static void write00GTRLST(File omgaudioDir) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(new File(omgaudioDir, "00GTRLST.DAT"), "rw");
        raf.setLength(0);

        int numberOfElements = 0x36; // One element for each possible tree ID, from 0x01 to 0x36

        WriteTableHeader(raf, "GTLT", 2);
        WriteClassDescription(raf, "SYSB", 0x30, 0x70);
        WriteClassDescription(raf, "GTLB", 0xa0, numberOfElements*0x50 + 0x10);

        // Class 1
        WriteClassHeader(raf, "SYSB", 1, 0x50, 0xd0000000, 0x00000000);
        WriteZeros(raf, 6 * 0x10);

        // Class 2
        WriteClassHeader(raf, "GTLB", numberOfElements, 0x50, TREES.length, 0x04070000);
        List<Integer> usedTrees = new ArrayList<Integer>();
        for (int[] tree : TREES) {
            usedTrees.add(tree[0]);
            if (tree[0] == 0x02) {
                WriteGTLBelement(raf, tree[0], tree[1], tree[2], "TPE1", "", new byte[0]);
            } else if (tree[0] == 0x03) {
                WriteGTLBelement(raf, tree[0], tree[1], tree[2], "TALB", "", new byte[0]);
            } else if (tree[0] == 0x04) {
                WriteGTLBelement(raf, tree[0], tree[1], tree[2], "TCON", "", new byte[0]);
            } else if (tree[0] == 0x36) {
                WriteGTLBelement(raf, tree[0], tree[1], tree[2], "TPE1", "TALB", ("TRNOTTCCTTCC").getBytes());
            } else {
                // The number of tags is written, but not the tags themselves
                WriteGTLBelement(raf, tree[0], tree[1], tree[2], "", "", new byte[0]);
            }
        }
        // Unused tree IDs
        for (int i = 1; i <= numberOfElements; i++) {
            if (!usedTrees.contains(i)) {
                WriteGTLBelement(raf, i, 0, 0, "", "", new byte[0]);
            }
        }

        raf.close();
    }

    /**
     * Write 02TREINF file in OMGAUDIO folder, giving the key and the name of all the trees of the extended database.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder where to write the file.
     * @param titles Map of the titles in the database.
     * @throws Exception
     */
    public static void write02TREINF(File omgaudioDir, Map titles) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(new File(omgaudioDir, "02TREINF.DAT"), "rw");
        raf.setLength(0);

        int globalKey = 0;
        for (Object title : titles.keySet()) {
            if (title != null) {
                globalKey += ((Title) title).getLength();
            }
        }

        int numberOfElements = 0x36;
        WriteTableHeader(raf, "GTIF", 1);
        WriteClassDescription(raf, "GTFB", 0x20, 0x1f00);
        WriteClassHeader(raf, "GTFB", numberOfElements, 0x90);

        for (int i = 1; i <= numberOfElements; i++) {
            String name;
            int key = globalKey;
            switch (i) {
                case 0x01: case 0x02: case 0x03: case 0x04: name = ""; break;
                case 0x10: case 0x11: case 0x12: case 0x13: case 0x14: name = "AR_BOKMK" + (i - 0x0F) + "_PL"; key = keptTreeKey(omgaudioDir, i); break;
                case 0x15: name = "AR_DELBOK_PL"; key = keptTreeKey(omgaudioDir, i); break;
                case 0x22: name = ""; key = keptTreeKey(omgaudioDir, i); break;
                case 0x2D: name = "AR_HISTORY_PL"; key = keptTreeKey(omgaudioDir, i); break;
                case 0x2E: name = "AR_TOP100_PL"; key = keptTreeKey(omgaudioDir, i); break;
                case 0x2F: name = "AR_RATE_DB"; break;
                case 0x30: name = "AR_TCON_DB_JPN"; break;
                case 0x31: name = "AR_TYER_DB_JPN"; break;
                case 0x32: name = "AR_ITRCK_DB_JPN"; break;
                case 0x33: name = "AR_ITPE1_DB_JPN"; break;
                case 0x34: name = "AR_ITALB_DB_JPN"; break;
                case 0x35: name = "AR_LSTTRN_DB"; key = keptTreeKey(omgaudioDir, i); break;
                case 0x36: name = "STD_TPE1_THUMBNAIL"; break;
                default: name = null;
            }

            if (name == null) {
                WriteZeros(raf, 0x90); // Unused tree ID
            } else {
                WriteGTFBelement(raf, key, name);
            }
        }
        WriteZeros(raf, 0x1f00 - 0x10 - numberOfElements*0x90); // Fill in the class with zeros

        raf.close();
    }

    /**
     * Gives the key of a tree filled in by the device in 02TREINF: the sum of the keys of its groups having titles (the
     * sum of the length of their titles), as SonicStage does. The tree must have been written.
     */
    private static int keptTreeKey(File omgaudioDir, int treeId) {
        byte[] tree = readFile(findFile(omgaudioDir, String.format("01TREE%02X.DAT", treeId)));
        byte[] ginf = readFile(findFile(omgaudioDir, String.format("03GINF%02X.DAT", treeId)));
        List<int[]> groups = readGroups(tree, ginf);
        if (groups == null) {
            return 0;
        }
        int[] gpfb = classInfo(ginf, 0, "GPIF", -1);
        int key = 0;
        for (int[] group : groups) {
            if (group[1] > 0 && group[2] > 0) {
                int position = ginfElement(gpfb, group[0]) + 8;
                key += bytes2int(Arrays.copyOfRange(ginf, position, position + 4));
            }
        }
        return key;
    }

    /**
     * Write the trees which only exist in the extended database (10 to 15, 2D to 36) in OMGAUDIO folder.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder where to write the files.
     * @param titles Map of the titles in the database (titles as keys, title IDs as values).
     * @throws Exception
     */
    public static void writeExtendedTrees(File omgaudioDir, JSymphonicMap titles, OldDatabase oldDatabase) throws Exception {
        if (oldDatabase == null) {
            oldDatabase = new OldDatabase();
        }
        List<Title> allTitles = new ArrayList<Title>();
        for (Object title : titles.keySet()) {
            if (title != null) {
                allTitles.add((Title) title);
            }
        }

        // Playlists, written by SonicStage (the base database only has an empty tree)
        writeKeptTree(omgaudioDir, "22", oldDatabase, titles);

        // History, filled in by the device
        if (!writeKeptTree(omgaudioDir, "2D", oldDatabase, titles)) {
            writeTree(omgaudioDir, "2D", TAGS_TITLE, new ArrayList<Group>(), new ArrayList<Integer>());
        }

        // Bookmarks, filled in by the device
        for (int i = 1; i <= 5; i++) {
            if (!writeKeptTree(omgaudioDir, "1" + (i - 1), oldDatabase, titles)) {
                writeTree(omgaudioDir, "1" + (i - 1), TAGS_TITLE, singleEmptyGroup("Bookmark " + i), new ArrayList<Integer>());
            }
        }
        if (!writeKeptTree(omgaudioDir, "15", oldDatabase, titles)) {
            writeTree(omgaudioDir, "15", TAGS_TITLE, singleEmptyGroup("Delete Bookmark"), new ArrayList<Integer>());
        }

        // Top 100, filled in by the device
        if (!writeKeptTree(omgaudioDir, "2E", oldDatabase, titles)) {
            writeTree(omgaudioDir, "2E", TAGS_TITLE, singleEmptyGroup("TOP100"), new ArrayList<Integer>());
        }

        // Ratings: 5 stars to 1 star, then the unrated titles, with the ratings kept by SonicStage in EXCNTINF
        List<Group> groups = new ArrayList<Group>();
        List<Integer> titleIds = new ArrayList<Integer>();
        String[] ratings = {"☆☆☆☆☆", "☆☆☆☆", "☆☆☆", "☆☆", "☆", ""};
        Map<String, byte[]> contentInfo = readContentElements(findFile(findFile(omgaudioDir, "A_WM"), CONTENT_INFO), "ARCI", CONTENT_INFO_LENGTH);
        List<Title> sortedTitles = sort(allTitles, new Level[]{new Level(ARTIST, ARTIST, true, null), new Level(ALBUM, ALBUM, true, null)});
        for (int i = 0; i < ratings.length; i++) {
            List<Title> titlesInGroup = new ArrayList<Title>();
            for (Title title : sortedTitles) {
                if (ratings.length - 1 - starsOf(title, contentInfo) == i) {
                    titlesInGroup.add(title);
                }
            }
            groups.add(new Group(1, totalLength(titlesInGroup), new String[]{ratings[i], ""}, titlesInGroup.isEmpty() ? 0 : titleIds.size() + 1));
            addTitleIds(titleIds, titlesInGroup, titles);
        }
        writeTree(omgaudioDir, "2F", TAGS_TITLE_SORT, groups, titleIds);

        // Genre > Artist > Album
        writeTree(omgaudioDir, "30", TAGS_TITLE_SORT, titles, allTitles, new Level[]{
            new Level(GENRE, GENRE, true, null),
            new Level(ARTIST, ARTIST, true, null),
            new Level(ALBUM, ALBUM, true, null)});

        // Year > Artist
        writeTree(omgaudioDir, "31", TAGS_TITLE_SORT, titles, allTitles, new Level[]{
            new Level(YEAR, YEAR_SORT, true, null),
            new Level(ARTIST, ARTIST, true, null)});

        // Initial of the title > Titles
        writeTree(omgaudioDir, "32", TAGS_TITLE_SORT, titles, allTitles, new Level[]{
            new Level(TITLE_INITIAL, TITLE_INITIAL, false, INITIALS)});

        // Initial of the artist > Artist > Album
        writeTree(omgaudioDir, "33", TAGS_TITLE_SORT, titles, allTitles, new Level[]{
            new Level(ARTIST_INITIAL, ARTIST_INITIAL, false, INITIALS),
            new Level(ARTIST, ARTIST, true, null),
            new Level(ALBUM, ALBUM, true, null)});

        // Initial of the album > Album
        writeTree(omgaudioDir, "34", TAGS_TITLE_SORT, titles, allTitles, new Level[]{
            new Level(ALBUM_INITIAL, ALBUM_INITIAL, false, INITIALS),
            new Level(ALBUM, ALBUM, true, null)});

        // Recent transfers, filled in by the device
        if (!writeKeptTree(omgaudioDir, "35", oldDatabase, titles)) {
            groups = new ArrayList<Group>();
            String[] recents = {"3rd Recent", "2nd Recent", "Most Recent"};
            for (String recent : recents) {
                groups.add(new Group(1, 0, new String[]{recent, ""}, 0));
            }
            writeTree(omgaudioDir, "35", TAGS_TITLE_SORT, groups, new ArrayList<Integer>());
        }

        // Artist > Album (with thumbnails), the first group is empty as in the databases written by SonicStage
        groups = new ArrayList<Group>();
        titleIds = new ArrayList<Integer>();
        groups.add(new Group(1, 0, new String[]{"", "", "", ""}, 0));
        Level[] levels = {new Level(ARTIST, ARTIST, true, null), new Level(ALBUM, ALBUM, true, null)};
        addGroups(groups, titleIds, sort(allTitles, levels), levels, 0, TAGS_TITLE_PICTURES_SORT, titles);
        // Album thumbnails: the ones already on the device are kept (SonicStage only stores them there), the others are made from the cover of the titles
        Set<String> usedPictures = new HashSet<String>();
        String[][] pictures = new String[groups.size()][];
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            if (group.titles != null) {
                pictures[i] = keptPictures(omgaudioDir, group.titles, oldDatabase, titles);
                if (pictures[i] != null) {
                    usedPictures.addAll(pictureFiles(pictures[i]));
                }
            }
        }
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            if (group.titles != null && pictures[i] == null) {
                // Named after the lowest title ID of the album, so that the names don't change when other albums are added or removed
                int fileId = 0xFFFF;
                for (Title title : group.titles) {
                    fileId = Math.min(fileId, (Integer) titles.get(title));
                }
                pictures[i] = writeAlbumThumbnails(omgaudioDir, fileId, group.titles, usedPictures);
            }
            if (pictures[i] != null) {
                group.tagValues[1] = pictures[i][0]; // PICP
                group.tagValues[2] = pictures[i][1]; // PIC0
            }
        }
        writeTree(omgaudioDir, "36", TAGS_TITLE_PICTURES_SORT, groups, titleIds);
        deleteUnusedThumbnails(omgaudioDir, usedPictures);
    }

    /**
     * Read what is kept from the database on the device, before it is rewritten.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder.
     * @return The trees filled in by the device and the playlists, with what is needed to update them.
     */
    public static OldDatabase readOldDatabase(File omgaudioDir) {
        OldDatabase oldDatabase = new OldDatabase();
        oldDatabase.titles = read04CNTINF(omgaudioDir);

        byte[] cidlst = readFile(findFile(omgaudioDir, "05CIDLST.DAT"));
        int[] elements = classInfo(cidlst, 0, "CIDL", 0x30);
        if (elements != null) {
            for (int i = 0; i < elements[1]; i++) {
                oldDatabase.contentIds.put(i + 1, Arrays.copyOfRange(cidlst, elements[0] + i*0x30, elements[0] + i*0x30 + 0x18));
            }
        }

        for (String treeId : KEPT_TREES) {
            byte[] tree = readFile(findFile(omgaudioDir, "01TREE" + treeId + ".DAT"));
            byte[] ginf = readFile(findFile(omgaudioDir, "03GINF" + treeId + ".DAT"));
            if (readGroups(tree, ginf) != null) {
                oldDatabase.trees.put(treeId, new byte[][]{tree, ginf});
            }
        }

        // Thumbnails of the albums of tree 36, for each of their titles
        byte[] tree = readFile(findFile(omgaudioDir, "01TREE36.DAT"));
        byte[] ginf = readFile(findFile(omgaudioDir, "03GINF36.DAT"));
        List<int[]> groups = readGroups(tree, ginf);
        if (groups != null) {
            int[] tplb = classInfo(tree, 1, "TREE", 0x2);
            int[] gpfb = classInfo(ginf, 0, "GPIF", -1);
            for (int i = 0; i < groups.size(); i++) {
                int[] group = groups.get(i);
                int start = ginfElement(gpfb, group[0]);
                String picp = tagValue(ginf, start, gpfb[2], "PICP");
                if (group[2] == 0 || picp.length() == 0) {
                    continue;
                }
                String[] pictures = {picp, tagValue(ginf, start, gpfb[2], "PIC0")};
                for (int titleId : groupTitles(tree, tplb, groups, i)) {
                    oldDatabase.pictures.put(titleId, pictures);
                }
            }
        }
        return oldDatabase;
    }

    /**
     * Write a tree filled in by the device as it was before the database was rewritten, without the titles which
     * aren't on the device any more. As SonicStage does, the groups left without titles are kept at the end of the
     * groups, with a level of 0, and the key of the groups (the sum of the length of their titles) is updated.
     *
     * @return True if the tree has been written, false if there was no valid tree on the device.
     */
    private static boolean writeKeptTree(File omgaudioDir, String treeId, OldDatabase oldDatabase, JSymphonicMap titles) throws IOException {
        byte[][] files = oldDatabase.trees.get(treeId);
        if (files == null) {
            return false;
        }
        byte[] tree = files[0];
        byte[] ginf = files[1].clone();
        List<int[]> groups = readGroups(tree, ginf); // {ID in 03GINFXX, level, first title}
        int[] gplb = classInfo(tree, 0, "TREE", 0x8);
        int[] tplb = classInfo(tree, 1, "TREE", 0x2);
        int[] gpfb = classInfo(ginf, 0, "GPIF", -1);

        // Remove the titles which aren't on the device any more
        boolean changed = false;
        int maxLevel = 0;
        List<List<Integer>> titlesOfGroups = new ArrayList<List<Integer>>();
        for (int i = 0; i < groups.size(); i++) {
            maxLevel = Math.max(maxLevel, groups.get(i)[1]);
            List<Integer> titlesOfGroup = new ArrayList<Integer>();
            int removedLength = 0;
            for (int titleId : groupTitles(tree, tplb, groups, i)) {
                if (isSameTitle(titleId, oldDatabase, titles)) {
                    titlesOfGroup.add(titleId);
                } else {
                    Object[] oldTitle = oldDatabase.titles.get(titleId);
                    removedLength += oldTitle == null ? 0 : (Integer) oldTitle[1];
                    changed = true;
                }
            }
            if (removedLength > 0) {
                int key = ginfElement(gpfb, groups.get(i)[0]) + 8;
                int newKey = Math.max(0, (int) bytes2int(Arrays.copyOfRange(ginf, key, key + 4)) - removedLength);
                System.arraycopy(int2bytes(newKey, 4), 0, ginf, key, 4);
            }
            titlesOfGroups.add(titlesOfGroup);
        }

        File treeFile = findFile(omgaudioDir, "01TREE" + treeId + ".DAT");
        File ginfFile = findFile(omgaudioDir, "03GINF" + treeId + ".DAT");
        if (!changed) {
            writeFile(treeFile, tree);
            writeFile(ginfFile, ginf);
            return true;
        }

        // In the trees with several levels, the groups left without titles are removed, then their parents left without groups
        boolean[] active = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            int[] group = groups.get(i);
            active[i] = group[1] > 0 && !(maxLevel > 1 && group[2] > 0 && titlesOfGroups.get(i).isEmpty());
        }
        for (int level = maxLevel - 1; level >= 1 && !treeId.equals("35"); level--) { // The groups of the recent transfers are always displayed
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i)[1] != level || groups.get(i)[2] > 0) {
                    continue;
                }
                boolean hadChildren = false, hasChildren = false;
                for (int j = i + 1; j < groups.size() && groups.get(j)[1] > level; j++) {
                    hadChildren = true;
                    hasChildren |= active[j];
                }
                if (hadChildren && !hasChildren) {
                    active[i] = false;
                }
            }
        }

        // Active groups first, in their order, then the others
        List<Integer> order = new ArrayList<Integer>();
        int numberOfActiveGroups = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (active[i]) {
                order.add(i);
                numberOfActiveGroups++;
            }
        }
        for (int i = 0; i < groups.size(); i++) {
            if (!active[i]) {
                order.add(i);
            }
        }

        List<Integer> titleIds = new ArrayList<Integer>();
        int tplbLength = 0x10 + roundUp16(countTitles(titlesOfGroups)*0x2);
        RandomAccessFile raf = new RandomAccessFile(treeFile, "rw");
        try {
            raf.setLength(0);
            WriteTableHeader(raf, "TREE", 2);
            WriteClassDescription(raf, "GPLB", 0x30, gplb[3]);
            WriteClassDescription(raf, "TPLB", 0x30 + gplb[3], tplbLength);
            WriteClassHeader(raf, "GPLB", groups.size(), 0x8, numberOfActiveGroups, 0);
            for (int i : order) {
                List<Integer> titlesOfGroup = titlesOfGroups.get(i);
                raf.write(int2bytes(groups.get(i)[0], 2));
                raf.write(active[i] ? groups.get(i)[1] : 0);
                raf.write(0);
                raf.write(int2bytes(active[i] && !titlesOfGroup.isEmpty() ? titleIds.size() + 1 : 0, 2));
                WriteZeros(raf, 2);
                if (active[i]) {
                    titleIds.addAll(titlesOfGroup);
                }
            }
            WriteZeros(raf, gplb[3] - 0x10 - groups.size()*0x8);
            WriteClassHeader(raf, "TPLB", titleIds.size(), 0x2, titleIds.size(), 0);
            for (int titleId : titleIds) {
                raf.write(int2bytes(titleId, 2));
            }
            WriteZeros(raf, tplbLength - 0x10 - titleIds.size()*0x2);
        } finally {
            raf.close();
        }
        writeFile(ginfFile, ginf);
        return true;
    }

    /**
     * Tells if a title ID of the database on the device still refers to the same title (the title hasn't been deleted,
     * and its ID hasn't been given to a new title).
     */
    private static boolean isSameTitle(int titleId, OldDatabase oldDatabase, JSymphonicMap titles) {
        Title title = (Title) titles.getKey(titleId);
        if (title == null) {
            return false;
        }
        byte[] oldContentId = oldDatabase.contentIds.get(titleId);
        return oldContentId == null || isZero(oldContentId) || Arrays.equals(oldContentId, Arrays.copyOf(contentIdOf(title), oldContentId.length));
    }

    /**
     * Read the groups of a tree, in their order.
     *
     * @return For each group, its ID in 03GINFXX, its level and the index of its first title in the TPLB class. Null if the files aren't valid.
     */
    private static List<int[]> readGroups(byte[] tree, byte[] ginf) {
        int[] gplb = classInfo(tree, 0, "TREE", 0x8);
        int[] tplb = classInfo(tree, 1, "TREE", 0x2);
        int[] gpfb = classInfo(ginf, 0, "GPIF", -1);
        if (gplb == null || tplb == null || gpfb == null) {
            return null;
        }
        List<int[]> groups = new ArrayList<int[]>();
        for (int i = 0; i < gplb[1]; i++) {
            int position = gplb[0] + i*0x8;
            int[] group = {(int) bytes2int(Arrays.copyOfRange(tree, position, position + 2)), tree[position + 2] & 0xff,
                (int) bytes2int(Arrays.copyOfRange(tree, position + 4, position + 6))};
            if (group[0] < 1 || group[0] > gpfb[1] || group[2] > tplb[1]) {
                return null;
            }
            groups.add(group);
        }
        return groups;
    }

    /**
     * Gives the title IDs of a group of a tree: from its first title to the first title of the next group having titles.
     */
    private static List<Integer> groupTitles(byte[] tree, int[] tplb, List<int[]> groups, int index) {
        List<Integer> titleIds = new ArrayList<Integer>();
        int first = groups.get(index)[2];
        if (first == 0) {
            return titleIds;
        }
        int end = tplb[1] + 1;
        for (int[] group : groups) {
            if (group[2] > first && group[2] < end) {
                end = group[2];
            }
        }
        for (int i = first; i < end; i++) {
            int position = tplb[0] + (i - 1)*0x2;
            titleIds.add((int) bytes2int(Arrays.copyOfRange(tree, position, position + 2)));
        }
        return titleIds;
    }

    private static int countTitles(List<List<Integer>> titlesOfGroups) {
        int count = 0;
        for (List<Integer> titlesOfGroup : titlesOfGroups) {
            count += titlesOfGroup.size();
        }
        return count;
    }

    /**
     * Gives the position of a class in a file of the database.
     *
     * @param file The content of the file.
     * @param classIndex The index of the class in the file.
     * @param tableName The expected name of the table.
     * @param elementLength The expected length of the elements, -1 if any.
     * @return The position of the first element, the number of elements, their length and the length of the class. Null if the file isn't valid.
     */
    private static int[] classInfo(byte[] file, int classIndex, String tableName, int elementLength) {
        if (file == null || file.length < 0x20 + classIndex*0x10 || !new String(file, 0, 4).equals(tableName) || (file[8] & 0xff) <= classIndex) {
            return null;
        }
        int description = 0x10 + classIndex*0x10;
        int offset = (int) bytes2int(Arrays.copyOfRange(file, description + 4, description + 8));
        int classLength = (int) bytes2int(Arrays.copyOfRange(file, description + 8, description + 12));
        if (offset < 0 || offset + 0x10 > file.length) {
            return null;
        }
        int count = (int) bytes2int(Arrays.copyOfRange(file, offset + 4, offset + 6));
        int length = (int) bytes2int(Arrays.copyOfRange(file, offset + 6, offset + 8));
        if ((elementLength >= 0 && length != elementLength) || offset + 0x10 + (long) count*length > file.length) {
            return null;
        }
        return new int[]{offset + 0x10, count, length, classLength};
    }

    /**
     * Gives the position of an element of the GPFB class of a 03GINFXX file, from its ID (starting at 1).
     */
    private static int ginfElement(int[] gpfb, int id) {
        return gpfb[0] + (id - 1)*gpfb[2];
    }

    /**
     * Gives the value of a tag in an element of a 03GINFXX file, an empty string if the element doesn't have the tag.
     */
    private static String tagValue(byte[] ginf, int element, int elementLength, String tagName) {
        for (int position = element + 0x10; position + 0x80 <= element + elementLength; position += 0x80) {
            if (new String(ginf, position, 4).equals(tagName)) {
                try {
                    return new String(ginf, position + 6, 0x7a, "UTF-16BE").replace("" + (char) 0, "");
                } catch (IOException ex) {
                    return "";
                }
            }
        }
        return "";
    }

    /**
     * Gives the thumbnails which were on the device for the album of some titles, if their files still exist.
     *
     * @return The values of the PICP and PIC0 tags, or null if there were no thumbnails.
     */
    private static String[] keptPictures(File omgaudioDir, List<Title> titlesOfAlbum, OldDatabase oldDatabase, JSymphonicMap titles) {
        for (Title title : titlesOfAlbum) {
            Integer titleId = (Integer) titles.get(title);
            String[] pictures = titleId == null ? null : oldDatabase.pictures.get(titleId);
            if (pictures == null || !isSameTitle(titleId, oldDatabase, titles)) {
                continue;
            }
            boolean exist = true;
            for (String picture : pictureFiles(pictures)) {
                String[] path = picture.split("\\\\");
                exist &= path.length == 2 && findFile(findFile(omgaudioDir, path[0]), path[1]).isFile();
            }
            if (exist) {
                return pictures;
            }
        }
        return null;
    }

    /**
     * Gives the files referred to by the PICP and PIC0 tags of an album ("200200;20P01\JM0001P0.JPG" for instance), in upper case.
     */
    private static Set<String> pictureFiles(String[] pictures) {
        Set<String> files = new HashSet<String>();
        for (String tag : pictures) {
            for (String picture : tag.split("\\|")) {
                if (picture.contains(";")) {
                    files.add(picture.substring(picture.indexOf(';') + 1).toUpperCase());
                }
            }
        }
        return files;
    }

    /**
     * Delete the album thumbnails which aren't used by tree 36 any more, and the 20PXX folders left empty.
     */
    private static void deleteUnusedThumbnails(File omgaudioDir, Set<String> usedPictures) {
        File[] folders = omgaudioDir.listFiles();
        if (folders == null) {
            return;
        }
        for (File folder : folders) {
            if (!folder.isDirectory() || !folder.getName().matches("(?i)20P[0-9A-F]{2}")) {
                continue;
            }
            File[] files = folder.listFiles();
            for (File file : files == null ? new File[0] : files) {
                if (file.getName().matches(THUMBNAIL_FILES) && !usedPictures.contains((folder.getName() + "\\" + file.getName()).toUpperCase())) {
                    if (!file.delete()) {
                        logger.warning("Cannot delete unused album thumbnail " + file.getPath());
                    }
                }
            }
            String[] left = folder.list();
            if (left != null && left.length == 0) {
                folder.delete();
            }
        }
    }

    private static byte[] readFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                byte[] content = new byte[(int) raf.length()];
                raf.readFully(content);
                return content;
            } finally {
                raf.close();
            }
        } catch (IOException ex) {
            logger.warning("Cannot read " + file.getPath() + ": " + ex.getMessage());
            return null;
        }
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content);
        } finally {
            out.close();
        }
    }

    /**
     * Write the thumbnails of an album of tree 36 in a 20PXX folder, from the cover of its first title having one.
     * As SonicStage does, the files are named after an ID: JMxxxxP0.JPG (200x200), JMxxxx01.JPG (96x96) and
     * JMxxxx02.JPG (32x32). If these files are used by another album, the next free ID is used.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder.
     * @param groupId The ID to name the files after.
     * @param titles Titles of the album.
     * @param usedPictures The files used by the other albums ("20PXX\JMXXXXP0.JPG" for instance), the files written are added.
     * @return The values of the PICP and PIC0 tags, or null if no thumbnail has been written.
     */
    private static String[] writeAlbumThumbnails(File omgaudioDir, int groupId, List<Title> titles, Set<String> usedPictures) {
        byte[] cover = null;
        for (Title title : titles) {
            cover = CoverArtToolBox.readCover(title.getCoverFile());
            if (cover != null) {
                break;
            }
        }
        if (cover == null) {
            return null;
        }

        int fileId = groupId;
        while (fileId < 0xFFFF && isUsed(fileId, usedPictures)) {
            fileId++;
        }
        String folderName = String.format("20P%02X", fileId % 0x20);
        File folder = findFile(omgaudioDir, folderName);
        if (!folder.exists() && !folder.mkdir()) {
            logger.warning("Cannot create folder " + folder.getPath() + " for album thumbnails.");
            return null;
        }

        String[] values = new String[THUMBNAIL_SIZES.length];
        for (int i = 0; i < THUMBNAIL_SIZES.length; i++) {
            int size = THUMBNAIL_SIZES[i];
            byte[] jpeg = CoverArtToolBox.toSquareJpeg(cover, size);
            if (jpeg == null) {
                return null;
            }
            String fileName = String.format("JM%04X%s.JPG", fileId, THUMBNAIL_SUFFIXES[i]);
            try {
                FileOutputStream out = new FileOutputStream(findFile(folder, fileName));
                try {
                    out.write(jpeg);
                } finally {
                    out.close();
                }
            } catch (IOException ex) {
                logger.warning("Cannot write album thumbnail " + fileName + ": " + ex.getMessage());
                return null;
            }
            values[i] = String.format("%03d%03d;%s\\%s", size, size, folderName, fileName);
        }

        String smallPictures = values[1];
        for (int i = 2; i < values.length; i++) {
            smallPictures += "|" + values[i];
        }
        String[] pictures = {values[0], smallPictures};
        usedPictures.addAll(pictureFiles(pictures));
        return pictures;
    }

    /**
     * Tells if the thumbnail files of an ID are used by an album.
     */
    private static boolean isUsed(int fileId, Set<String> usedPictures) {
        for (String suffix : THUMBNAIL_SUFFIXES) {
            if (usedPictures.contains(String.format("20P%02X\\JM%04X%s.JPG", fileId % 0x20, fileId, suffix))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create the files SonicStage puts in the OMGAUDIO folder of these devices, if they don't exist.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder where to write the files.
     * @throws Exception
     */
    public static void createDeviceFiles(File omgaudioDir) throws Exception {
        for (Object[] deviceFile : DEVICE_FILES) {
            File file = findFile(omgaudioDir, (String) deviceFile[0]);
            if (file.exists()) {
                continue;
            }

            int size = (Integer) deviceFile[1];
            byte[] buffer = new byte[0x10000];
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            for (int written = 0; written < size; written += buffer.length) {
                raf.write(buffer, 0, Math.min(buffer.length, size - written));
            }
            if (file.getName().equals("ACTIVITY.DAT")) {
                // The activity log starts with its version
                raf.seek(0);
                raf.write(new byte[]{1, 1});
            }
            raf.close();
        }
    }

    /**
     * Write the extended content info of the titles in A_WM/EXCNTINF.DAT (ratings) and A_WM/EXCNTMTA.DAT (readings of
     * the title and the album, year, track number and play count), as SonicStage does. Both files have one element per
     * title ID, like 05CIDLST. The device uses them once the database is known to be up to date (see writeMountLog),
     * and reports an incompatible database if they don't match the other files.
     * The elements of the titles transfered by SonicStage are kept (they are found with their content ID), the ones of
     * the other titles are made from their info, without rating or reading.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder.
     * @param titles Map of the titles in the database (titles as keys, title IDs as values).
     * @throws Exception
     */
    public static void writeContentInfo(File omgaudioDir, JSymphonicMap titles) throws Exception {
        File folder = findFile(omgaudioDir, "A_WM");
        if (!folder.exists() && !folder.mkdir()) {
            throw new IOException("Cannot create folder " + folder.getPath());
        }
        File infoFile = findFile(folder, CONTENT_INFO);
        File metaFile = findFile(folder, CONTENT_META);
        Map<String, byte[]> oldInfo = readContentElements(infoFile, "ARCI", CONTENT_INFO_LENGTH);
        Map<String, byte[]> oldMeta = readContentElements(metaFile, "EXCM", CONTENT_META_LENGTH);
        int numberOfElements = titles.isEmpty() ? 0 : titles.maxValue();

        RandomAccessFile rafInfo = new RandomAccessFile(infoFile, "rw");
        RandomAccessFile rafMeta = new RandomAccessFile(metaFile, "rw");
        try {
            rafInfo.setLength(0);
            rafMeta.setLength(0);
            writeContentHeader(rafInfo, "ARCI", "ARCB", numberOfElements, CONTENT_INFO_LENGTH);
            writeContentHeader(rafMeta, "EXCM", "EXCB", numberOfElements, CONTENT_META_LENGTH);

            for (int i = 1; i <= numberOfElements; i++) {
                Title title = (Title) titles.getKey(i);
                byte[] contentId = contentIdOf(title);
                String key = toHex(contentId);

                if (oldInfo.containsKey(key) && oldMeta.containsKey(key)) {
                    // Title transfered by SonicStage, keep its info
                    rafInfo.write(oldInfo.get(key));
                    rafMeta.write(oldMeta.get(key));
                    continue;
                }

                rafInfo.write(contentId);
                if (title == null) {
                    WriteZeros(rafInfo, CONTENT_INFO_LENGTH - CONTENT_ID_LENGTH);
                } else {
                    rafInfo.write(new byte[]{0, (byte) 0xff}); // Not rated
                    WriteZeros(rafInfo, CONTENT_INFO_LENGTH - CONTENT_ID_LENGTH - 2);
                }

                rafMeta.write(contentId);
                if (title == null) {
                    WriteZeros(rafMeta, CONTENT_META_LENGTH - CONTENT_ID_LENGTH);
                } else {
                    String[] values = {title.getTitle(), title.getAlbum(),
                        title.getYear() > 0 ? String.valueOf(title.getYear()) : "",
                        title.getTitleNumber() > 0 ? String.valueOf(title.getTitleNumber()) : "",
                        "0"}; // Play count
                    for (int j = 0; j < CONTENT_META_TAGS.length; j++) {
                        WriteTag(rafMeta, CONTENT_META_TAGS[j], values[j]);
                    }
                }
            }
        } finally {
            rafInfo.close();
            rafMeta.close();
        }
    }

    /**
     * Write the header of EXCNTINF/EXCNTMTA: a table with one class of elements.
     */
    private static void writeContentHeader(RandomAccessFile raf, String tableName, String className, int numberOfElements, int elementLength) throws IOException {
        raf.write(tableName.getBytes());
        raf.write(new byte[]{1, 0, 0, 0, 1, 0, 0, 0});
        WriteZeros(raf, 4);
        WriteClassDescription(raf, className, 0x20, 0x10 + numberOfElements*elementLength);
        WriteClassHeader(raf, className, numberOfElements, elementLength);
    }

    /**
     * Read the elements of EXCNTINF/EXCNTMTA which belong to a title with a content ID.
     *
     * @return The elements, with the hexadecimal value of their first 0x20 bytes (the content ID) as keys. Empty if the file doesn't exist or isn't valid.
     */
    private static Map<String, byte[]> readContentElements(File file, String tableName, int elementLength) {
        Map<String, byte[]> elements = new HashMap<String, byte[]>();
        if (!file.exists()) {
            return elements;
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                byte[] header = new byte[0x30];
                raf.readFully(header);
                int numberOfElements = ((header[0x24] & 0xff) << 8) | (header[0x25] & 0xff);
                int length = ((header[0x26] & 0xff) << 8) | (header[0x27] & 0xff);
                if (!new String(header, 0, 4, "ISO-8859-1").equals(tableName) || length != elementLength || raf.length() < 0x30 + (long) numberOfElements*length) {
                    logger.warning(file.getName() + " is not valid, it is rewritten.");
                    return elements;
                }
                for (int i = 0; i < numberOfElements; i++) {
                    byte[] element = new byte[length];
                    raf.readFully(element);
                    byte[] contentId = Arrays.copyOf(element, CONTENT_ID_LENGTH);
                    if (!isZero(contentId)) {
                        elements.put(toHex(contentId), element);
                    }
                }
            } finally {
                raf.close();
            }
        } catch (IOException ex) {
            logger.warning("Cannot read " + file.getName() + ": " + ex.getMessage());
        }
        return elements;
    }

    /**
     * Give a content ID to the OMA files on the device which have none (files transfered by older versions of
     * JSymphonic). SonicStage gives one to each file, and all the titles would share the same one otherwise.
     *
     * @param titles Map of the titles in the database (titles as keys, title IDs as values).
     */
    public static void writeMissingContentIds(JSymphonicMap titles) {
        int numberOfTitles = titles.isEmpty() ? 0 : titles.maxValue();
        for (int i = 1; i <= numberOfTitles; i++) {
            Title title = (Title) titles.getKey(i);
            if (!(title instanceof Oma) || ((Oma) title).getDrm() != Oma.NO_DRM || !isZero(title.getContentId())
                    || title.getSourceFile() == null || !title.getSourceFile().exists()) {
                continue;
            }
            byte[] contentId = Oma.newContentId();
            if (Oma.writeContentId(title.getSourceFile(), contentId)) {
                title.setContentId(contentId);
            }
        }
    }

    /**
     * Gives the start of the element of a title in 05CIDLST: its DRM key or its content ID, or zeros.
     */
    private static byte[] contentIdOf(Title title) {
        byte[] contentId = new byte[CONTENT_ID_LENGTH];
        if (title != null) {
            byte[] source = (title instanceof Oma && ((Oma) title).getDrm() == Oma.DRM_LSI) ? ((Oma) title).getDrmKey() : title.getContentId();
            System.arraycopy(source, 0, contentId, 0, Math.min(source.length, CONTENT_ID_LENGTH));
        }
        return contentId;
    }

    /**
     * Gives the number of stars of a title (0 if it isn't rated), from the elements of EXCNTINF.
     */
    private static int starsOf(Title title, Map<String, byte[]> contentInfo) {
        byte[] contentId = contentIdOf(title);
        if (isZero(contentId) || !contentInfo.containsKey(toHex(contentId))) {
            return 0;
        }
        int stars = (contentInfo.get(toHex(contentId))[CONTENT_RATING] & 0xff) / 10;
        return Math.min(stars, 5);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Write the modification time of 01TREE01.DAT and 04CNTINF.DAT in A_WM/AFMNTLOG.DAT, as SonicStage does.
     *
     * When it starts, the device compares these times with the ones of the files. If the log is missing or if they
     * differ (because the database has been written by another program), it only enables its basic features and
     * displays a "Simple Mode" message.
     * The elements of the MLBL class tell which version of each function of the database has been processed, and when:
     * SonicStage writes the ones of the functions it handles (version 1.0 and the time of 01TREE01.DAT), the device
     * writes the others, which are kept as they are.
     * The modification time of the files is set to the current time first, rounded to 2 seconds as stored by FAT
     * file systems, so that the time read by the device is exactly the one written in the log. The tree change log
     * (07GTCHLG.DAT) is written with the same time.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder.
     * @param create True to create the logs if they don't exist, false to only update existing logs.
     * @throws Exception
     */
    public static void writeMountLog(File omgaudioDir, boolean create) throws Exception {
        File logFile = findFile(findFile(omgaudioDir, "A_WM"), MOUNT_LOG);
        if (!logFile.exists() && !create) {
            return;
        }

        File treeFile = findFile(omgaudioDir, "01TREE01.DAT");
        File contentFile = findFile(omgaudioDir, "04CNTINF.DAT");
        if (!treeFile.exists() || !contentFile.exists()) {
            throw new IOException("01TREE01.DAT or 04CNTINF.DAT is missing");
        }
        long time = (System.currentTimeMillis() / 2000) * 2000;
        treeFile.setLastModified(time);
        contentFile.setLastModified(time);

        // Use the times read back from the file system, in case it rounds them differently
        byte[] times = new byte[16];
        System.arraycopy(toBcdTime(treeFile.lastModified()), 0, times, 0, 8);
        System.arraycopy(toBcdTime(contentFile.lastModified()), 0, times, 8, 8);

        if (!isMountLog(logFile)) {
            // Create an empty log
            File folder = logFile.getParentFile();
            if (!folder.exists() && !folder.mkdir()) {
                throw new IOException("Cannot create folder " + folder.getPath());
            }
            RandomAccessFile raf = new RandomAccessFile(logFile, "rw");
            try {
                raf.setLength(0);
                raf.write("AFML".getBytes());
                raf.write(new byte[]{1, 0, 0, 0, 2, 0, 0, 0});
                WriteZeros(raf, 4);
                WriteClassDescription(raf, "DTBL", 0x30, 0x90);
                WriteClassDescription(raf, "MLBL", 0xc0, 0x1000);
                WriteClassHeader(raf, "DTBL", 1, 0x80);
                WriteZeros(raf, 0x80);
                WriteClassHeader(raf, "MLBL", 0xff, 0x10);
                WriteZeros(raf, 0xff * 0x10);
            } finally {
                raf.close();
            }
        }

        RandomAccessFile raf = new RandomAccessFile(logFile, "rw");
        try {
            raf.seek(MOUNT_LOG_TIMES);
            raf.write(times);
            for (int function : MOUNT_LOG_PC_FUNCTIONS) {
                // Version (major, minor), then the time
                raf.seek(MOUNT_LOG_FUNCTIONS + (function - 1)*0x10);
                raf.write(new byte[]{1, 0});
                WriteZeros(raf, 6);
                raf.write(times, 0, 8);
            }
        } finally {
            raf.close();
        }

        writeTreeChangeLog(omgaudioDir, time, create);
    }

    /**
     * Write the modification time of the trees in 07GTCHLG.DAT, as SonicStage does: one element per tree, with the tree
     * ID (2 bytes), 6 zeros and the time.
     */
    private static void writeTreeChangeLog(File omgaudioDir, long time, boolean create) throws Exception {
        File logFile = findFile(omgaudioDir, TREE_CHANGE_LOG);
        if (!logFile.exists() && !create) {
            return;
        }

        Map<Integer, File> treeFiles = new LinkedHashMap<Integer, File>();
        for (int tree : TREE_CHANGE_LOG_TREES) {
            File treeFile = findFile(omgaudioDir, String.format("01TREE%02X.DAT", tree));
            if (treeFile.exists()) {
                treeFile.setLastModified(time);
                treeFiles.put(tree, treeFile);
            }
        }

        RandomAccessFile raf = new RandomAccessFile(logFile, "rw");
        try {
            raf.setLength(0);
            WriteTableHeader(raf, "GTCL", 1);
            WriteClassDescription(raf, "BFCL", 0x20, 0x10 + treeFiles.size()*0x10);
            WriteClassHeader(raf, "BFCL", treeFiles.size(), 0x10);
            for (Map.Entry<Integer, File> treeFile : treeFiles.entrySet()) {
                raf.write(int2bytes(treeFile.getKey(), 2));
                WriteZeros(raf, 6);
                raf.write(toBcdTime(treeFile.getValue().lastModified()));
            }
        } finally {
            raf.close();
        }
    }

    /**
     * Tells if a file is a valid mount log.
     */
    private static boolean isMountLog(File file) throws IOException {
        if (!file.exists() || file.length() != MOUNT_LOG_SIZE) {
            return false;
        }
        byte[] header = new byte[0x34];
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.readFully(header);
        } finally {
            raf.close();
        }
        return new String(header, 0, 4, "ISO-8859-1").equals("AFML") && new String(header, 0x30, 4, "ISO-8859-1").equals("DTBL");
    }

    /**
     * Convert a time to the format used in the mount log: year (2 bytes), month, day, hour, minute, second in BCD, then a zero.
     * The local time is used, as FAT file systems store local times.
     */
    static byte[] toBcdTime(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        int[] values = {calendar.get(Calendar.YEAR) / 100, calendar.get(Calendar.YEAR) % 100, calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND)};
        byte[] bcd = new byte[8];
        for (int i = 0; i < values.length; i++) {
            bcd[i] = (byte) (((values[i] / 10) << 4) | (values[i] % 10));
        }
        return bcd;
    }

    /**
     * Tells if a file in the OMGAUDIO folder belongs to the device and should be kept when the database is rewritten.
     *
     * @param fileName The name of the file.
     * @return True if the file must be kept.
     */
    public static boolean isDeviceFile(String fileName) {
        for (Object[] deviceFile : DEVICE_FILES) {
            if (((String) deviceFile[0]).equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return fileName.equalsIgnoreCase("A_WM") // Files transfered with Windows Media Player
                || fileName.equalsIgnoreCase("07GTCHLG.DAT") // Tree change log written by SonicStage
                || fileName.matches("(?i)20P[0-9A-F]{2}"); // Album art pictures written by SonicStage
    }

    /**
     * Search a file in a folder, whatever the case of its name.
     */
    private static File findFile(File dir, String name) {
        String[] files = dir.list();
        if (files != null) {
            for (String file : files) {
                if (file.equalsIgnoreCase(name)) {
                    return new File(dir, file);
                }
            }
        }
        return new File(dir, name);
    }

    /**
     * Sort the titles, then build and write a tree.
     */
    private static void writeTree(File omgaudioDir, String treeId, String[] tagNames, Map titles, List<Title> allTitles, Level[] levels) throws Exception {
        List<Group> groups = new ArrayList<Group>();
        List<Integer> titleIds = new ArrayList<Integer>();
        addGroups(groups, titleIds, sort(allTitles, levels), levels, 0, tagNames, titles);
        writeTree(omgaudioDir, treeId, tagNames, groups, titleIds);
    }

    /**
     * Build the groups of a level of a tree, and recursively the groups of the next levels.
     *
     * @param groups List where the groups are added.
     * @param titleIds List where the title IDs are added (TPLB class).
     * @param titlesToAdd The titles to add, sorted.
     * @param levels The levels of the tree.
     * @param depth The level to build (0 for the top level).
     * @param tagNames The tags used in the 03GINFXX file.
     * @param titles Map of the titles in the database (titles as keys, title IDs as values).
     */
    private static void addGroups(List<Group> groups, List<Integer> titleIds, List<Title> titlesToAdd, Level[] levels, int depth, String[] tagNames, Map titles) {
        Level level = levels[depth];
        boolean isLastLevel = (depth == levels.length - 1);

        // Split the titles into groups, keeping their order
        List<String> groupNames = new ArrayList<String>();
        List<List<Title>> groupTitles = new ArrayList<List<Title>>();
        if (level.fixedGroups != null) {
            for (String groupName : level.fixedGroups) {
                List<Title> titlesInGroup = new ArrayList<Title>();
                for (Title title : titlesToAdd) {
                    if (level.labeler.label(title).equals(groupName)) {
                        titlesInGroup.add(title);
                    }
                }
                groupNames.add(groupName);
                groupTitles.add(titlesInGroup);
            }
        } else {
            for (Title title : titlesToAdd) {
                String groupName = level.labeler.label(title);
                if (groupNames.isEmpty() || !groupNames.get(groupNames.size() - 1).equals(groupName)) {
                    groupNames.add(groupName);
                    groupTitles.add(new ArrayList<Title>());
                }
                groupTitles.get(groupTitles.size() - 1).add(title);
            }
        }

        // Write the groups
        for (int i = 0; i < groupNames.size(); i++) {
            String[] tagValues = new String[tagNames.length];
            for (int j = 0; j < tagNames.length; j++) {
                if (tagNames[j].equals("TIT2") || (tagNames[j].equals("XSOT") && level.withSortTag)) {
                    tagValues[j] = groupNames.get(i);
                } else {
                    tagValues[j] = "";
                }
            }

            List<Title> titlesInGroup = groupTitles.get(i);
            if (isLastLevel) {
                Group group = new Group(depth + 1, totalLength(titlesInGroup), tagValues, titlesInGroup.isEmpty() ? 0 : titleIds.size() + 1);
                group.titles = titlesInGroup;
                groups.add(group);
                addTitleIds(titleIds, titlesInGroup, titles);
            } else {
                groups.add(new Group(depth + 1, 0, tagValues, 0));
                addGroups(groups, titleIds, titlesInGroup, levels, depth + 1, tagNames, titles);
            }
        }
    }

    /**
     * Write a tree in its two files 01TREEXX.DAT and 03GINFXX.DAT.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder where to write the files.
     * @param treeId The ID of the tree, in hexadecimal, as used in the file names.
     * @param tagNames The tags used in the 03GINFXX file.
     * @param groups The groups of the tree.
     * @param titleIds The title IDs, in the order they should be displayed.
     * @throws Exception
     */
    private static void writeTree(File omgaudioDir, String treeId, String[] tagNames, List<Group> groups, List<Integer> titleIds) throws Exception {
        RandomAccessFile rafTree = new RandomAccessFile(new File(omgaudioDir, "01TREE" + treeId + ".DAT"), "rw");
        RandomAccessFile rafGinf = new RandomAccessFile(new File(omgaudioDir, "03GINF" + treeId + ".DAT"), "rw");
        rafTree.setLength(0);
        rafGinf.setLength(0);

        int gplbLength = Math.max(0x4010, roundUp16(0x10 + groups.size()*0x8));
        int tplbLength = 0x10 + roundUp16(titleIds.size()*0x2);
        int ginfElementLength = 0x10 + tagNames.length*0x80;

        // 01TREEXX
        WriteTableHeader(rafTree, "TREE", 2);
        WriteClassDescription(rafTree, "GPLB", 0x30, gplbLength);
        WriteClassDescription(rafTree, "TPLB", 0x30 + gplbLength, tplbLength);

        WriteClassHeader(rafTree, "GPLB", groups.size(), 0x8, groups.size(), 0);
        for (int i = 0; i < groups.size(); i++) {
            rafTree.write(int2bytes(i + 1, 2)); // ID of the element in 03GINFXX
            rafTree.write(groups.get(i).level);
            rafTree.write(0);
            rafTree.write(int2bytes(groups.get(i).firstTitle, 2));
            WriteZeros(rafTree, 2);
        }
        WriteZeros(rafTree, gplbLength - 0x10 - groups.size()*0x8);

        WriteClassHeader(rafTree, "TPLB", titleIds.size(), 0x2, titleIds.size(), 0);
        for (Integer titleId : titleIds) {
            rafTree.write(int2bytes(titleId, 2));
        }
        WriteZeros(rafTree, tplbLength - 0x10 - titleIds.size()*0x2);

        // 03GINFXX
        WriteTableHeader(rafGinf, "GPIF", 1);
        WriteClassDescription(rafGinf, "GPFB", 0x20, 0x10 + groups.size()*ginfElementLength);
        WriteClassHeader(rafGinf, "GPFB", groups.size(), ginfElementLength);
        for (Group group : groups) {
            WriteZeros(rafGinf, 8);
            rafGinf.write(int2bytes(group.key, 4));
            rafGinf.write(new byte[]{0, (byte) tagNames.length, 0, (byte) 0x80});
            for (int j = 0; j < tagNames.length; j++) {
                WriteTag(rafGinf, tagNames[j], group.tagValues[j]);
            }
        }

        rafTree.close();
        rafGinf.close();
    }

    private static List<Group> singleEmptyGroup(String name) {
        List<Group> groups = new ArrayList<Group>();
        groups.add(new Group(1, 0, new String[]{name}, 0));
        return groups;
    }

    private static void addTitleIds(List<Integer> titleIds, List<Title> titlesToAdd, Map titles) {
        for (Title title : titlesToAdd) {
            titleIds.add((Integer) titles.get(title));
        }
    }

    private static int totalLength(List<Title> titles) {
        int length = 0;
        for (Title title : titles) {
            length += title.getLength();
        }
        return length;
    }

    private static int roundUp16(int value) {
        return (value + 0xF) & ~0xF;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Sort the titles according to the sort keys of the levels, then the title number and the title name.
     */
    private static List<Title> sort(List<Title> titles, final Level[] levels) {
        List<Title> sortedTitles = new ArrayList<Title>(titles);
        Collections.sort(sortedTitles, new Comparator<Title>() {
            public int compare(Title t1, Title t2) {
                for (Level level : levels) {
                    int result = level.sortKey.label(t1).compareToIgnoreCase(level.sortKey.label(t2));
                    if (result == 0) {
                        result = level.labeler.label(t1).compareTo(level.labeler.label(t2)); // Keep the groups with the same name but a different case apart
                    }
                    if (result != 0) {
                        return result;
                    }
                }
                if (t1.getTitleNumber() != t2.getTitleNumber()) {
                    return t1.getTitleNumber() < t2.getTitleNumber() ? -1 : 1;
                }
                return clean(t1.getTitle()).compareToIgnoreCase(clean(t2.getTitle()));
            }
        });
        return sortedTitles;
    }

    /**
     * Gives the initial of a name, as displayed by the device in the AR_ITxxx trees: a katakana, a latin letter, "0-9" or "etc.".
     * Names starting with a kanji can't be sorted without their reading, they are put in "etc.".
     *
     * @param name The name.
     * @return The initial, which is one of the INITIALS.
     */
    static String initialOf(String name) {
        name = clean(name);
        if (name.length() == 0) {
            return "etc.";
        }

        // Full width characters to normal width, then remove accents and dakuten
        String first = Normalizer.normalize(name.substring(0, Character.charCount(name.codePointAt(0))), Normalizer.Form.NFKC);
        first = Normalizer.normalize(first, Normalizer.Form.NFD).substring(0, 1);
        char c = Character.toUpperCase(first.charAt(0));

        if (c >= 'ぁ' && c <= 'ゖ') {
            c = (char) (c + ('ァ' - 'ぁ')); // Hiragana to katakana
        }
        int smallKanaIndex = SMALL_KANA.indexOf(c);
        if (smallKanaIndex >= 0) {
            c = BIG_KANA.charAt(smallKanaIndex);
        }
        if (c == 'ヰ') {
            c = 'イ';
        } else if (c == 'ヱ') {
            c = 'エ';
        }

        if (c >= '0' && c <= '9') {
            return "0-9";
        }
        String initial = String.valueOf(c);
        if (INITIALS.contains(initial)) {
            return initial;
        }
        return "etc.";
    }
}
