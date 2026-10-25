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
 * CoverArtToolBox.java
 *
 */

package org.naurd.media.jsymphonic.toolBox;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.datatype.Artwork;

/**
 * Reads the cover art of audio files and converts it to the pictures used by the Walkman.
 *
 * SonicStage stores the cover of a title in its OMA file, as a GEOB frame of the "ea3" tag holding a JPEG picture. The
 * players with an extended database also display album thumbnails, stored as JPEG files in the 20PXX folders.
 * All these pictures are square baseline JPEG files.
 */
public class CoverArtToolBox {
/* CONSTANTS */
    public static final int OMA_COVER_SIZE = 200; // Size of the cover written in the OMA files
    public static final String OMA_COVER_DESCRIPTION = "OMG_FENCA1"; // Description of the GEOB frame holding the cover, as written by SonicStage

    private static final int MAX_COVER_FILE_SIZE = 0x1000000; // Covers bigger than this are ignored (16MB)
    private static final float JPEG_QUALITY = 0.9f;

/* FIELDS */
    private static Logger logger = Logger.getLogger("org.naurd.media.jsymphonic.toolBox.CoverArtToolBox");

/* METHODS */
    /**
     * Read the cover art of an audio file. OMA files are read from their "ea3" tag, other formats with JAudioTagger.
     *
     * @param file The audio file.
     * @return The picture as stored in the file (JPEG, PNG,...), or null if the file has no cover.
     */
    public static byte[] readCover(File file) {
        if(file == null || !file.exists()){
            return null;
        }

        try {
            if(file.getName().toLowerCase().endsWith(".oma")){
                return readOmaCover(file);
            }

            Tag tag = AudioFileIO.read(file).getTag();
            if(tag == null){
                return null;
            }
            Artwork artwork = tag.getFirstArtwork();
            if(artwork == null || artwork.getBinaryData() == null || artwork.getBinaryData().length == 0){
                return null;
            }
            return artwork.getBinaryData();
        }
        catch(Throwable ex) {
            logger.fine("No cover read from '" + file.getPath() + "': " + ex.getMessage());
            return null;
        }
    }

    /**
     * Read the cover of an OMA file, stored in a GEOB frame of its "ea3" tag.
     *
     * @param file The OMA file.
     * @return The picture, or null if the file has no cover.
     * @throws IOException
     */
    private static byte[] readOmaCover(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            // Header: "ea3", version (2 bytes), flags (1 byte), size of the tag (4 bytes, 7 bits per byte)
            byte[] header = new byte[10];
            if(in.read(header) != 10 || header[0] != 'e' || header[1] != 'a' || header[2] != '3'){
                return null;
            }
            int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14) | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);
            if(tagSize > MAX_COVER_FILE_SIZE){
                return null;
            }
            byte[] tag = new byte[tagSize];
            int read = 0;
            while(read < tagSize){
                int count = in.read(tag, read, tagSize - read);
                if(count < 0){
                    break;
                }
                read += count;
            }

            // Frames: ID (4 bytes), size (4 bytes), flags (2 bytes), value
            int position = 0;
            while(position + 10 <= read && tag[position] != 0){
                String frameId = new String(tag, position, 4, "ISO-8859-1");
                int frameSize = DataBaseOmgaudioToolBox.bytes2int(new byte[]{tag[position+4], tag[position+5], tag[position+6], tag[position+7]});
                int frameStart = position + 10;
                if(frameSize <= 0 || frameStart + frameSize > read){
                    break;
                }
                if(frameId.equals("GEOB")){
                    byte[] picture = readGeobPicture(tag, frameStart, frameSize);
                    if(picture != null){
                        return picture;
                    }
                }
                position = frameStart + frameSize;
            }
            return null;
        }
        finally {
            in.close();
        }
    }

    /**
     * Read the picture of a GEOB frame: encoding (1 byte), MIME type, file name, description, then the data.
     *
     * @return The picture, or null if the GEOB frame doesn't hold a picture (DRM keys,...).
     */
    private static byte[] readGeobPicture(byte[] tag, int start, int size) throws IOException {
        int end = start + size;
        int encoding = tag[start];
        int position = start + 1;

        // MIME type, ended by a zero
        int mimeEnd = position;
        while(mimeEnd < end && tag[mimeEnd] != 0){
            mimeEnd++;
        }
        String mimeType = new String(tag, position, mimeEnd - position, "ISO-8859-1");
        if(!mimeType.toLowerCase().startsWith("image/")){
            return null;
        }
        position = mimeEnd + 1;

        // File name and description, ended by one or two zeros depending on the encoding
        for(int i = 0; i < 2; i++){
            position = skipString(tag, position, end, encoding);
        }
        if(position >= end){
            return null;
        }

        byte[] picture = new byte[end - position];
        System.arraycopy(tag, position, picture, 0, picture.length);
        return picture;
    }

    /**
     * Skip a string ended by a zero (or two zeros for 16 bits encodings) and return the position after it.
     */
    private static int skipString(byte[] tag, int position, int end, int encoding) {
        if(encoding == 0 || encoding == 3){
            while(position < end && tag[position] != 0){
                position++;
            }
            return position + 1;
        }
        while(position + 1 < end && (tag[position] != 0 || tag[position+1] != 0)){
            position += 2;
        }
        return position + 2;
    }

    /**
     * Build the GEOB frame holding a cover, as written by SonicStage in the OMA files.
     *
     * @param jpeg The JPEG picture.
     * @return The frame, header included.
     * @throws IOException
     */
    public static byte[] buildOmaCoverFrame(byte[] jpeg) throws IOException {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        value.write(2); // UTF-16BE encoding for the strings
        value.write("image/jpeg".getBytes("ISO-8859-1"));
        value.write(0);
        value.write(new byte[]{0, 0}); // No file name
        value.write(OMA_COVER_DESCRIPTION.getBytes("UTF-16BE"));
        value.write(new byte[]{0, 0});
        value.write(jpeg);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write("GEOB".getBytes("ISO-8859-1"));
        frame.write(DataBaseOmgaudioToolBox.int2bytes(value.size(), 4));
        frame.write(new byte[]{0, 0}); // Flags
        value.writeTo(frame);
        return frame.toByteArray();
    }

    /**
     * Convert a picture to a square baseline JPEG picture, as used by the Walkman. The picture is scaled to fit, the
     * space left (if the picture isn't square) is filled with black.
     *
     * @param picture The picture to convert (any format readable by Java: JPEG, PNG, GIF, BMP).
     * @param size The width and height of the JPEG picture.
     * @return The JPEG picture, or null if the picture can't be read.
     */
    public static byte[] toSquareJpeg(byte[] picture, int size) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(picture));
            if(source == null){
                return null;
            }

            // Scale the picture to fit in the square
            double scale = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage scaled = scale(source, width, height);

            BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = square.createGraphics();
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, size, size);
            graphics.drawImage(scaled, (size - width) / 2, (size - height) / 2, null);
            graphics.dispose();

            // Write the JPEG picture
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            ImageOutputStream out = ImageIO.createImageOutputStream(jpeg);
            writer.setOutput(out);
            writer.write(null, new IIOImage(square, null, null), param);
            out.close();
            writer.dispose();
            return jpeg.toByteArray();
        }
        catch(Throwable ex) {
            logger.warning("Cannot convert cover to JPEG: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Scale a picture down by halving its size step by step, which looks better than scaling it at once.
     */
    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        int currentWidth = source.getWidth();
        int currentHeight = source.getHeight();
        do {
            currentWidth = Math.max(width, currentWidth / 2);
            currentHeight = Math.max(height, currentHeight / 2);
            BufferedImage next = new BufferedImage(currentWidth, currentHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = next.createGraphics();
            graphics.setColor(Color.BLACK); // Transparent parts become black
            graphics.fillRect(0, 0, currentWidth, currentHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(current, 0, 0, currentWidth, currentHeight, null);
            graphics.dispose();
            current = next;
        } while(currentWidth != width || currentHeight != height);
        return current;
    }
}
