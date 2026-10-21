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
import java.io.RandomAccessFile;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

        Group(int level, int key, String[] tagValues, int firstTitle) {
            this.level = level;
            this.key = key;
            this.tagValues = tagValues;
            this.firstTitle = firstTitle;
        }
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
                case 0x10: case 0x11: case 0x12: case 0x13: case 0x14: name = "AR_BOKMK" + (i - 0x0F) + "_PL"; key = 0; break;
                case 0x15: name = "AR_DELBOK_PL"; key = 0; break;
                case 0x22: name = ""; key = 0; break;
                case 0x2D: name = "AR_HISTORY_PL"; key = 0; break;
                case 0x2E: name = "AR_TOP100_PL"; key = 0; break;
                case 0x2F: name = "AR_RATE_DB"; break;
                case 0x30: name = "AR_TCON_DB_JPN"; break;
                case 0x31: name = "AR_TYER_DB_JPN"; break;
                case 0x32: name = "AR_ITRCK_DB_JPN"; break;
                case 0x33: name = "AR_ITPE1_DB_JPN"; break;
                case 0x34: name = "AR_ITALB_DB_JPN"; break;
                case 0x35: name = "AR_LSTTRN_DB"; key = 0; break;
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
     * Write the trees which only exist in the extended database (10 to 15, 2D to 36) in OMGAUDIO folder.
     *
     * @param omgaudioDir Instance of the OMGAUDIO folder where to write the files.
     * @param titles Map of the titles in the database (titles as keys, title IDs as values).
     * @throws Exception
     */
    public static void writeExtendedTrees(File omgaudioDir, Map titles) throws Exception {
        List<Title> allTitles = new ArrayList<Title>();
        for (Object title : titles.keySet()) {
            if (title != null) {
                allTitles.add((Title) title);
            }
        }

        // History, filled in by the device
        writeTree(omgaudioDir, "2D", TAGS_TITLE, new ArrayList<Group>(), new ArrayList<Integer>());

        // Bookmarks, filled in by the device
        for (int i = 1; i <= 5; i++) {
            writeTree(omgaudioDir, "1" + (i - 1), TAGS_TITLE, singleEmptyGroup("Bookmark " + i), new ArrayList<Integer>());
        }
        writeTree(omgaudioDir, "15", TAGS_TITLE, singleEmptyGroup("Delete Bookmark"), new ArrayList<Integer>());

        // Top 100, filled in by the device
        writeTree(omgaudioDir, "2E", TAGS_TITLE, singleEmptyGroup("TOP100"), new ArrayList<Integer>());

        // Ratings: all the titles are unrated (last group)
        List<Group> groups = new ArrayList<Group>();
        List<Integer> titleIds = new ArrayList<Integer>();
        String[] ratings = {"☆☆☆☆☆", "☆☆☆☆", "☆☆☆", "☆☆", "☆"};
        for (String rating : ratings) {
            groups.add(new Group(1, 0, new String[]{rating, ""}, 0));
        }
        List<Title> sortedTitles = sort(allTitles, new Level[]{new Level(ARTIST, ARTIST, true, null), new Level(ALBUM, ALBUM, true, null)});
        groups.add(new Group(1, totalLength(sortedTitles), new String[]{"", ""}, sortedTitles.isEmpty() ? 0 : 1));
        addTitleIds(titleIds, sortedTitles, titles);
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
        groups = new ArrayList<Group>();
        String[] recents = {"3rd Recent", "2nd Recent", "Most Recent"};
        for (String recent : recents) {
            groups.add(new Group(1, 0, new String[]{recent, ""}, 0));
        }
        writeTree(omgaudioDir, "35", TAGS_TITLE_SORT, groups, new ArrayList<Integer>());

        // Artist > Album (with thumbnails), the first group is empty as in the databases written by SonicStage
        groups = new ArrayList<Group>();
        titleIds = new ArrayList<Integer>();
        groups.add(new Group(1, 0, new String[]{"", "", "", ""}, 0));
        Level[] levels = {new Level(ARTIST, ARTIST, true, null), new Level(ALBUM, ALBUM, true, null)};
        addGroups(groups, titleIds, sort(allTitles, levels), levels, 0, TAGS_TITLE_PICTURES_SORT, titles);
        writeTree(omgaudioDir, "36", TAGS_TITLE_PICTURES_SORT, groups, titleIds);
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
        return fileName.equalsIgnoreCase("A_WM"); // Files transfered with Windows Media Player
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
                groups.add(new Group(depth + 1, totalLength(titlesInGroup), tagValues, titlesInGroup.isEmpty() ? 0 : titleIds.size() + 1));
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
