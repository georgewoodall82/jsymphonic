/*
 * Copyright (C) 2007, 2008, 2009 Patrick Balleux, Nicolas Cardoso De Castro
 * (nicolas_cardoso@users.sourceforge.net), Daniel Žalar (danizmax@gmail.com)
 *
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
 * Wav.java
 *
 * Created on October 2, 2006, 10:22 PM
 *
 */

package org.naurd.media.jsymphonic.title;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

/**
 *
 * @author Pat
 */
public class Wav extends Title{

    /** Creates a new instance of Wav */
    public Wav(java.net.URL url) {
        super(url);
    }
    public Wav(java.io.File f){
        super(f);
        format = Title.WAV;

        // Read info from header (using JAudioTagger lib)
        try {
            AudioHeader WAVinfo = AudioFileIO.read(f).getAudioHeader();
            bitRate = (int) WAVinfo.getBitRateAsNumber();
            frequency = WAVinfo.getSampleRateAsNumber()/1000.0; // this method returns the frequency in Hz, it should be in kHz
            vbr = WAVinfo.isVariableBitRate();
            nbChannels = Integer.parseInt(WAVinfo.getChannels());
            length = WAVinfo.getTrackLength()*1000; // this method returns the tracklength in second, it should be in millisecond
        }
        catch (Exception ex) {
            logger.warning("Exception while reading WAV info for file: '" + f.getPath()+ "'. Exception: "+ex.getMessage());
        }

        // Read the Vorbis tag if set so in the config file
        if(tryReadTagInfo){
            try {
                Tag WAVtag = AudioFileIO.read(f).getTag();
                if(WAVtag.getFirst(FieldKey.TITLE).length() > 0){ titleName = WAVtag.getFirst(FieldKey.TITLE);} // Read field is it exist
                if(WAVtag.getFirst(FieldKey.ARTIST).length() > 0){ artistName = WAVtag.getFirst(FieldKey.ARTIST);} // Read field is it exist
                if(WAVtag.getFirst(FieldKey.ALBUM).length() > 0){ albumName = WAVtag.getFirst(FieldKey.ALBUM);} // Read field is it exist
                if(WAVtag.getFirst(FieldKey.GENRE).length() > 0){ genre = WAVtag.getFirst(FieldKey.GENRE);} // Read field is it exist
                if(WAVtag.getFirst(FieldKey.TRACK).length() > 0){ titleNumber = Integer.parseInt(WAVtag.getFirst(FieldKey.TRACK));} // Read field is it exist
                if(WAVtag.getFirst(FieldKey.YEAR).length() > 0){ year = Integer.parseInt(WAVtag.getFirst(FieldKey.YEAR));} // Read field is it exist
            }
            catch (Exception ex) {
                logger.warning("Exception while reading WAV tag for file: '" + f.getPath()+ "'. Exception: "+ex.getMessage());
            }
        }
    }

/* STATIC METHODS */

    /**
     *Obtains the list of the possible file extentions for this format.
     *
     *@return A list of extentions separated with comas.
     */
    public static String getFileExtentionsList() {
        return "wav,wave";
    }

/* GET METHODS */

    /**
     *Obtains the format of the title as a string.
     *
     *@return the format of the title as a string.
     */
    @Override
    public String getFormatAsString() {
        return "Wave";
    }
}
