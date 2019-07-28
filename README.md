JInputHook
===============
[![Build Status](https://travis-ci.org/dyorgio/jinputhook.svg?branch=master)](https://travis-ci.org/dyorgio/jinputhook) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.dyorgio.runtime/jinputhook/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.dyorgio.runtime/jinputhook)

Global Key and Shortcuts listeners for Java.

Why use it?
-----
* Create global keyboard/shortcut listeners.
* As an alternative to excelent lib [JNativeHook](https://github.com/kwhat/jnativehook).

Usage
-----
For shortcut listener:

```java
// Initialize
JInputHook.initialize();
// Register shortcut listener
Shortcut shortcut = Shortcut.fromKeys(Key.LCONTROL, Key.LSHIFT, Key.D);
JInputHook.addShortcutListener(shortcut,
    new ShortcutListener() {
        @Override
        public void shortcutTriggered(Shortcut shortcut) {
            System.out.println("shortcutTriggered(" + shortcut + ").");
        }
    }
);
```

For keyboard listener:

```java
// Initialize
JInputHook.initialize();
// Register global keyboard listener
JInputHook.addListener(new GlobalKeyListener() {
    @Override
    public void keyPressed(Key key) {
        System.out.println(".keyPressed(" + key + ").");
    }

    @Override
    public void keyReleased(Key key) {
        System.out.println(".keyReleased(" + key + ").");
    }
});
```

Maven
-----
```xml
<dependency>
    <groupId>com.github.dyorgio.runtime</groupId>
    <artifactId>jinputhook</artifactId>
    <version>1.0.0</version>
    <!-- Optional classifier by OS, don't use classifier to support ALL -->
    <!--<classifier>mac-universal</classifier>-->
    <!--<classifier>linux-universal</classifier>-->
    <!--<classifier>win-universal</classifier>-->
</dependency>
```

Windows
-----
On Windows, include JNA as dependency.
```xml
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna-platform</artifactId>
    <version>${your.jna.version}</version>
</dependency>
```

Linux
-----
On Linux you need to adjust permissions of /dev/input/event* to can read:
```bash
sudo chmod o+r /dev/input/event*
```

Or, according with distro, create a new dev rule file on /etc/udev/rules.d/YOUR-FILE-NAME.rules:
```txt
SUBSYSTEMS=="input", KERNEL=="event*", MODE="644"
```