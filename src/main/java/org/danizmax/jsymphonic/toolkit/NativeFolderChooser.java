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
 */

package org.danizmax.jsymphonic.toolkit;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NativeFileDialog;

/**
 * Lets the user pick a folder with the file dialog of the operating system:
 * <ul>
 * <li>Windows: the native dialog, through lwjgl-nfd.</li>
 * <li>macOS: the native AWT file dialog, in folder mode.</li>
 * <li>Linux: the dialog of the desktop, run as a separate program (kdialog or zenity). It must not
 * be loaded in this process: the GTK of the dialog conflicts with the one of the GTK look and feel
 * of Swing, and freezes the interface once the dialog is closed.</li>
 * </ul>
 * If the native dialog is not available, the Swing file chooser is used instead.
 */
public final class NativeFolderChooser {
    private static final Logger logger = Logger.getLogger(NativeFolderChooser.class.getName());

    private NativeFolderChooser() {
    }

    /**
     * Ask the user to choose a folder.
     *
     * @param parent The component the dialog belongs to, can be null.
     * @param title The title of the dialog (not every system shows it).
     * @param startPath The folder the dialog should open in, can be null or invalid.
     * @return The absolute path of the folder chosen, or null if the user cancelled.
     */
    public static String chooseFolder(Component parent, String title, String startPath) {
        File start = (startPath != null && new File(startPath).isDirectory()) ? new File(startPath) : null;

        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                // Native folder picking is built in the AWT file dialog of macOS
                return chooseWithAwt(parent, title, start);
            } else if (os.contains("win")) {
                return chooseWithNfd(start);
            } else {
                return chooseWithDesktopDialog(title, start);
            }
        } catch (Throwable t) {
            logger.warning("Native file dialog unavailable (" + t + "), using the Swing one");
        }
        return chooseWithSwing(parent, title, start);
    }

    private static String chooseWithNfd(File start) {
        if (NativeFileDialog.NFD_Init() != NativeFileDialog.NFD_OKAY) {
            throw new IllegalStateException(NativeFileDialog.NFD_GetError());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.mallocPointer(1);
            int result = NativeFileDialog.NFD_PickFolder(out, start == null ? null : start.getAbsolutePath());
            if (result == NativeFileDialog.NFD_OKAY) {
                String path = out.getStringUTF8(0);
                NativeFileDialog.NFD_FreePath(out.get(0));
                return path;
            }
            if (result == NativeFileDialog.NFD_ERROR) {
                throw new IllegalStateException(NativeFileDialog.NFD_GetError());
            }
            return null; // Cancelled
        } finally {
            NativeFileDialog.NFD_Quit();
        }
    }

    private static String chooseWithDesktopDialog(String title, File start) throws IOException, InterruptedException {
        String startPath = (start != null ? start : new File(System.getProperty("user.home"))).getAbsolutePath();

        List<String[]> commands = new ArrayList<String[]>();
        String[] kdialog = {"kdialog", "--title", title, "--getexistingdirectory", startPath};
        String[] zenity = {"zenity", "--file-selection", "--directory", "--title=" + title, "--filename=" + startPath + "/"};
        if (System.getenv("XDG_CURRENT_DESKTOP") != null && System.getenv("XDG_CURRENT_DESKTOP").toUpperCase().contains("KDE")) {
            commands.add(kdialog);
            commands.add(zenity);
        } else {
            commands.add(zenity);
            commands.add(kdialog);
        }

        IOException lastError = new IOException("No file dialog program found (kdialog, zenity)");
        for (String[] command : commands) {
            try {
                return runDialogProcess(command);
            } catch (IOException e) {
                lastError = e; // Program not installed or not working, try the next one
            }
        }
        throw lastError;
    }

    /**
     * Run a dialog program and return what it prints. The user interface keeps being repainted while
     * the dialog is open.
     *
     * @return The path chosen, or null if the user cancelled.
     */
    private static String runDialogProcess(String[] command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command).redirectError(new File("/dev/null")).start();
        final StringBuilder output = new StringBuilder();
        final int[] exitCode = {-1};
        final Exception[] failure = {null};
        final SecondaryLoop loop = EventQueue.isDispatchThread()
                ? Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop() : null;

        Thread waiter = new Thread("folder-dialog") {
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                    exitCode[0] = process.waitFor();
                } catch (Exception e) {
                    failure[0] = e;
                } finally {
                    if (loop != null) {
                        loop.exit();
                    }
                }
            }
        };
        waiter.setDaemon(true);
        waiter.start();

        if (loop != null) {
            loop.enter(); // Keep processing the events of the interface while the dialog is open
        }
        waiter.join();

        if (failure[0] != null) {
            throw new IOException(failure[0]);
        }
        if (exitCode[0] == 0 && output.length() > 0) {
            return output.toString().trim();
        }
        if (exitCode[0] == 1) {
            return null; // Cancelled
        }
        throw new IOException(command[0] + " exited with code " + exitCode[0]);
    }

    private static String chooseWithAwt(Component parent, String title, File start) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        FileDialog dialog;
        if (owner instanceof Dialog) {
            dialog = new FileDialog((Dialog) owner, title, FileDialog.LOAD);
        } else {
            dialog = new FileDialog(owner instanceof Frame ? (Frame) owner : null, title, FileDialog.LOAD);
        }
        if (start != null) {
            dialog.setDirectory(start.getAbsolutePath());
        }
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        try {
            dialog.setVisible(true);
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false");
        }
        if (dialog.getFile() == null) {
            return null; // Cancelled
        }
        return new File(dialog.getDirectory(), dialog.getFile()).getAbsolutePath();
    }

    private static String chooseWithSwing(Component parent, String title, File start) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        fc.setDialogType(JFileChooser.OPEN_DIALOG);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (start != null) {
            fc.setCurrentDirectory(start);
        }
        if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            return fc.getSelectedFile().getAbsolutePath();
        }
        return null;
    }
}
