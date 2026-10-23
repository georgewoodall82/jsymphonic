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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

import net.samuelcampos.usbdrivedetector.USBDeviceDetectorManager;
import net.samuelcampos.usbdrivedetector.USBStorageDevice;

/**
 * A combo box listing the device paths of all the USB drives currently plugged in. The last entry
 * always lets the user browse for a path by hand, for when no drive is detected or the right one is
 * missing from the list.
 */
public class DevicePathComboBox extends JComboBox<String> {
/* FIELDS */
    private static final Logger logger = Logger.getLogger(DevicePathComboBox.class.getName());

    private final String browseLabel = java.util.ResourceBundle.getBundle("localization/jsymphonicprofilesdialog").getString("JSymphonicProfilesDialog.Select_device_path");
    private final String invalidPath = java.util.ResourceBundle.getBundle("localization/jsymphonicproperties").getString("JSymphonicProperties.invalid_path");
    private String lastPath = invalidPath;
    private boolean updating = false;

/* CONSTRUCTOR */
    public DevicePathComboBox() {
        setPaths(new ArrayList<String>(), null);
        addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectionChanged();
            }
        });
    }

/* STATIC METHODS */
    /**
     * Search for the root directories of all the removable drives currently plugged in.
     *
     * @return The paths found, empty if there is none.
     */
    public static List<String> detectUsbPaths() {
        List<String> paths = new ArrayList<String>();
        USBDeviceDetectorManager driveDetector = new USBDeviceDetectorManager();
        try {
            for (USBStorageDevice drive : driveDetector.getRemovableDevices()) {
                String path = drive.getRootDirectory().getAbsolutePath();
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            }
            driveDetector.close();
        } catch (Exception e) {
            logger.warning(String.valueOf(e.getMessage()));
        }
        return paths;
    }

    /**
     * Find the first path which looks like a walkman (it contains an OMGAUDIO or ESYS folder).
     *
     * @return The path found, or an empty string if none matches.
     */
    public static String findWalkmanPath(List<String> paths) {
        for (String path : paths) {
            if (new File(path + "/OMGAUDIO").exists() || new File(path + "/omgaudio").exists()
                    || new File(path + "/ESYS").exists() || new File(path + "/esys").exists()) {
                return path;
            }
        }
        return "";
    }

/* METHODS */
    /**
     * Fill the list with the given paths, followed by the entry used to browse for another one.
     *
     * @param paths The USB paths to offer.
     * @param selected The path to select. If it is not in the list, it is added. If it is null or
     * empty, the first path is selected.
     */
    public void setPaths(List<String> paths, String selected) {
        List<String> items = new ArrayList<String>(paths);
        if (selected != null && selected.length() > 0 && !items.contains(selected)) {
            items.add(selected);
        }
        if (items.isEmpty()) {
            items.add(invalidPath); // Nothing to choose from, only the browse entry is usable
        }
        String toSelect = (selected != null && items.contains(selected)) ? selected : items.get(0);

        updating = true;
        try {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>(items.toArray(new String[0]));
            model.addElement(browseLabel);
            setModel(model);
            setSelectedItem(toSelect);
            lastPath = toSelect;
        } finally {
            updating = false;
        }
    }

    /**
     * @return The path currently selected.
     */
    public String getDevicePath() {
        return lastPath;
    }

    /** Called when the user picks an entry: if it is the browse entry, let the user choose a folder. */
    private void selectionChanged() {
        if (updating || !browseLabel.equals(getSelectedItem())) {
            if (!updating && getSelectedItem() != null) {
                lastPath = (String) getSelectedItem();
            }
            return;
        }
        // Wait for the popup to close before opening the chooser
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                browse();
            }
        });
    }

    private void browse() {
        String chosen = NativeFolderChooser.chooseFolder(this, browseLabel, lastPath);
        if (chosen == null) {
            chosen = lastPath;
        }

        // Add the chosen path just before the browse entry if it is new, then select it.
        // If the user cancelled, this puts the previous selection back.
        updating = true;
        try {
            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) getModel();
            if (model.getIndexOf(chosen) < 0) {
                model.insertElementAt(chosen, model.getSize() - 1);
            }
            setSelectedItem(chosen);
            lastPath = chosen;
        } finally {
            updating = false;
        }
    }
}
