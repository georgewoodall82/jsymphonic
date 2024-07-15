package com.pipasoft.jsymphonic;

import java.io.*;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.stream.Collectors;

import javax.swing.ImageIcon;

import org.apache.commons.io.FileUtils;

/**
 * a more centralized place to load resources
 * 
 * @author bpipa
 *
 */
public class ResourceLoader {

	/**
	 * gets the URL to a resource
	 * 
	 * @param name the name of the resource
	 * @return the URL to the resource
	 */
	public static URL get(String name) {
		return ResourceLoader.class.getResource("/"+name);
	}

	/**
	 * gets an icon by name
	 * 
	 * @param name the name of the icon image
	 * @return an ImageIcon object
	 */
	public static ImageIcon getIcon(String name) {
		return new ImageIcon(get(name));
	}	
	
	/**
	 * gets the contents of a file in the resources folder as a String
	 * 
	 * @param fileName the name/path to a file in the resources folder
	 * @return the string contents of the file
	 */
	public static String getResourceFileAsString(String fileName){
		try {
		    ClassLoader classLoader = ClassLoader.getSystemClassLoader();
		    try (InputStream is = classLoader.getResourceAsStream(fileName)) {
		        if (is == null) return null;
		        try (InputStreamReader isr = new InputStreamReader(is);
		             BufferedReader reader = new BufferedReader(isr)) {
		            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
		        }
		    }
			
		} catch (Exception e) {
			e.printStackTrace();
			return "";
		}
	}	
	
}