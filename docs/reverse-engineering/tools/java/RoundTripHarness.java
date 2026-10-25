// Runs JSymphonic's database writer on a copy of an OMGAUDIO folder without any user interface, to test it:
// add files, or delete the titles whose name contains a text, then write the database.
//
// Build JSymphonic first (mvn -q package, or javac over src/main/java with the jars of ~/.m2 on the class path),
// then, with CLASSES = the compiled classes and LIBS = the jars of jaudiotagger, swing-layout and usbdrivedetector:
//
//   javac -cp "$LIBS:$CLASSES" -d out RoundTripHarness.java
//   cd WORKDIR        # WORKDIR contains the OMGAUDIO folder to test (a COPY!), and "export" is created inside it
//   java -cp "$LIBS:$CLASSES:out" org.naurd.media.jsymphonic.device.sony.nw.RoundTripHarness WORKDIR song.mp3
//   java -cp "$LIBS:$CLASSES:out" org.naurd.media.jsymphonic.device.sony.nw.RoundTripHarness WORKDIR -d "song"
//   java -cp "$LIBS:$CLASSES:out" org.naurd.media.jsymphonic.device.sony.nw.RoundTripHarness WORKDIR      # just rewrite
//
// Set -Dgen=N to use another generation than GENERATION_7_EXTENDED (the NW-A80x layout). It must be in this package
// because it uses the package-level API of the device classes. Never run it on a real player's folder without a copy.
package org.naurd.media.jsymphonic.device.sony.nw;

import java.io.File;
import java.util.logging.*;

public class RoundTripHarness {
    public static void main(String[] a) throws Exception {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        Logger.getLogger("org.jaudiotagger").setLevel(Level.WARNING);
        for (Handler h : root.getHandlers()) h.setLevel(Level.FINE);

        final Object done = new Object();
        final boolean[] finished = {false};
        NWGenericListener listener = (NWGenericListener) java.lang.reflect.Proxy.newProxyInstance(
            RoundTripHarness.class.getClassLoader(), new Class[]{NWGenericListener.class},
            (proxy, method, args) -> {
                if (!method.getName().equals("fileProgressChanged") && !method.getName().equals("loadingProgresChanged"))
                    System.out.println("EVENT " + method.getName() + " " + java.util.Arrays.toString(args));
                if (method.getName().equals("transferTermination")) synchronized (done) { finished[0] = true; done.notifyAll(); }
                return null;
            });

        NWOmgaudio nw = new NWOmgaudio(a[0], "test", "test", null, Integer.getInteger("gen", NWGeneric.GENERATION_7_EXTENDED), listener, a[0] + "/export");
        nw.setTempPath(a[0]);
        nw.setTranscodeBitrate(128);

        if (a.length > 2 && a[1].equals("-d")) {
            for (org.naurd.media.jsymphonic.title.Title t : nw.getTitles()) {
                if (t.getTitle().contains(a[2])) {
                    System.out.println("DELETE " + t);
                    nw.scheduleDeletion(t);
                }
            }
        } else {
            // With no file this still makes JSymphonic rewrite the database, which is what "just rewrite" needs
            File[] files = new File[a.length - 1];
            for (int i = 1; i < a.length; i++) files[i - 1] = new File(a[i]);
            System.out.println("scheduleImport -> " + nw.scheduleImport(files, true));
        }
        nw.applyChanges();
        synchronized (done) { while (!finished[0]) done.wait(); }
        for (org.naurd.media.jsymphonic.title.Title t : nw.getTitles())
            System.out.println("AFTER " + t + " status=" + t.getStatus());
        System.exit(0);
    }
}
