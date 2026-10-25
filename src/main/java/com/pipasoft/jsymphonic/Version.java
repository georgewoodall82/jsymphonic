package com.pipasoft.jsymphonic;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * the version of JSymphonic. It is written once, in the pom.xml, and copied by Maven into version.properties when
 * the program is built, so there is nothing else to update when making a release.
 */
public class Version {

	/** shown when the program runs from sources that Maven has not processed (an IDE for instance) */
	private static final String UNKNOWN = "dev";

	private static final String VERSION = read();

	private static String read() {
		try (InputStream in = Version.class.getResourceAsStream("/version.properties")) {
			if (in != null) {
				Properties properties = new Properties();
				properties.load(in);
				String version = properties.getProperty("version", UNKNOWN).trim();
				// not filtered by Maven: the placeholder is still there
				return version.startsWith("${") ? UNKNOWN : version;
			}
		} catch (IOException e) {
			// fall through
		}
		return UNKNOWN;
	}

	/**
	 * @return the version, like 0.7.0
	 */
	public static String get() {
		return VERSION;
	}

	/**
	 * @return the name and the version of the program, like JSymphonic v0.7.0
	 */
	public static String getNameAndVersion() {
		return "JSymphonic v" + VERSION;
	}
}
