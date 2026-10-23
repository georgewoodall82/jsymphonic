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
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.util.logging.Logger;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.nfd.NativeFileDialog;

/**
 * Lets the user pick a folder with the file dialog of the operating system. The native dialog is
 * used on Windows, Linux (needs GTK 3) and macOS. If it is not available, the Swing file chooser is
 * used instead.
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

        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                // Native folder picking is built in the AWT file dialog of macOS
                return chooseWithAwt(parent, title, start);
            }
            return chooseWithNfd(start);
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
