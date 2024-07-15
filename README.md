# Jsymphonic Revival

Note that I am not the orignal author. Jsymphonic was/is located at https://sourceforge.net/projects/symphonic/ and it appears the original author, nicolas_cardoso, abandoned it. This repository is just me moving the code to github and changing a couple things so it will work on modern JVMs and converting the build so it's more modern (I changed it to build with Maven). I did not change any of the functionality, so this should work exactly as before. I tried it on linux and it works. If anyone tries it on Windows or mac, please let me know.

## Running Jsymphonic
Download the latest jar from https://github.com/brianpipa/jsymphonic/releases/ and then you need to run it. Depending on your OS, and what you have configured, the way to do this may vary. See https://brianpipa.github.io/jsymphonic for full run instructions

## Building Jsymphonic (for developers only)
Clone this repo then build it with the following:
```
mvn package
```
Once that's done, it that will create target/jsymphonic-0.4-jar-with-dependencies.jar which is your runnable jar

Any new files I add will be in https://github.com/brianpipa/jsymphonic/tree/main/src/main/java/com/pipasoft/jsymphonic I did add ResourceLoader.java to centralize the loading of the icon images.

I had to change two places in the code where it looked at the java version. One is here: https://sourceforge.net/p/symphonic/code/HEAD/tree/jsymphonic/branches/v0.3/src/org/danizmax/jsymphonic/gui/JSymphonicWindow.java#l361 - this code can't handle modern JVMs when they return a version of something like "17.0.7". I ended up just removing the version check altogether - you can see it here: https://github.com/brianpipa/jsymphonic/blob/main/src/main/java/org/danizmax/jsymphonic/gui/JSymphonicWindow.java#L363
