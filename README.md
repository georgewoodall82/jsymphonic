# Jsymphonic Revival

Note that I am not the orignal author. Jsymphonic was/is located at https://sourceforge.net/projects/symphonic/ and it appears the original author, nicolas_cardoso, abandoned it. This repository is just me moving the code to github and changing a couple things so it will work on modern JVMs and converting the build so it's more modern (I changed it to build with Maven). I did not change any of the functionality, so this should work exactly as before. I tried it on linux and it works. If anyone tries it on Windows or mac, please let me know.

## Running Jsymphonic
### Via Flatpak
Install the flatpak from a gui software manager like mint software center, or run the command:
```
flatpak install io.github.georgewoodall82.jsymphonic
```

### Via Jar File
1. To transfer files over other than MP3 and ATRAC, you will need to install ffmpeg. See [Installing FFMPEG](INSTALLING-FFMPEG.md)
2. Download the latest jar from https://github.com/georgewoodall82/jsymphonic/releases/
3. Install Java 8 or above
4. Run the jar using `java -jar jsymphonic-*.jar` or double clicking it sometimes works

## Building Jsymphonic (for developers only)
Clone this repo then build it with the following:
```
mvn package
```
Once that's done, it that will create target/jsymphonic-*-jar-with-dependencies.jar which is your runnable jar

Any new files I add will be in https://github.com/brianpipa/jsymphonic/tree/main/src/main/java/com/pipasoft/jsymphonic I did add ResourceLoader.java to centralize the loading of the icon images.