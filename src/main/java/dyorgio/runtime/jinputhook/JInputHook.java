/** *****************************************************************************
 * Copyright 2019 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************** */
package dyorgio.runtime.jinputhook;

import dyorgio.runtime.jinputhook.cleaner.JInputCleaner;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import static java.lang.Thread.sleep;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.java.games.input.Component.Identifier.Key;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import net.java.games.input.Event;
import net.java.games.input.EventQueue;
import net.java.games.input.Keyboard;
import static dyorgio.runtime.jinputhook.Shortcut.fromKeys;
import java.util.Map.Entry;

/**
 *
 * @author dyorgio
 */
public final class JInputHook {

    private static final Logger LOGGER = Logger.getLogger(JInputHook.class.getName());

    private static JInputHook INSTANCE;

    private final Set<GlobalKeyListener> globalKeyboardListeners = new HashSet();

    private final Map<Shortcut, Set<ShortcutListener>> shortcutListeners = new HashMap();

    private final ExecutorService fireEventsExecutor = new ThreadPoolExecutor(1, Integer.MAX_VALUE,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ThreadFactory() {
        AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "FireEventsThread-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Thread updateKeyboardsThread;
    private Thread poolingKeyboardInputThread;
    private KeyboardState[] keyboardStates;
    private boolean errorOnUpdateKeyboards = false;

    private JInputHook() {
        updateInputDevices();
        if (!errorOnUpdateKeyboards) {
            updateKeyboardsThread = new Thread(null, null, "UpdateKeyboardsThread", 64l * 1024l) {
                {
                    setDaemon(true);
                }

                @Override
                @SuppressWarnings("SleepWhileInLoop")
                public void run() {
                    while (!isInterrupted()) {
                        try {
                            sleep(60000);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        updateInputDevices();
                    }
                }

            };
        } else {
            updateKeyboardsThread = null;
        }
    }

    private void fireKeyPressed(final Key key) {
        fireEventsExecutor.submit(new Runnable() {
            @Override
            public void run() {
                synchronized (globalKeyboardListeners) {
                    for (GlobalKeyListener listener : globalKeyboardListeners) {
                        listener.keyPressed(key);
                    }
                }
            }
        });
    }

    private void fireKeyReleased(final Key key) {
        fireEventsExecutor.submit(new Runnable() {
            @Override
            public void run() {
                synchronized (globalKeyboardListeners) {
                    for (GlobalKeyListener listener : globalKeyboardListeners) {
                        listener.keyReleased(key);
                    }
                }
            }
        });
    }

    private void fireShortcutPressed(final Shortcut shortcut) {
        fireEventsExecutor.submit(new Runnable() {
            @Override
            public void run() {
                Set<ShortcutListener> listeners = shortcutListeners.get(shortcut);
                if (listeners != null) {
                    synchronized (listeners) {
                        for (ShortcutListener listener : listeners) {
                            listener.shortcutTriggered(shortcut);
                        }
                    }
                }
            }
        });
    }

    private void updateInputDevices() {
        synchronized (this) {
            try {
                List<Keyboard> localKeyboards = new ArrayList();
                for (Controller controller : getDefaultEnvironment().getControllers()) {
                    if (controller.getType() == Controller.Type.KEYBOARD) {
                        localKeyboards.add((Keyboard) controller);
                    }
                }

                List<KeyboardState> localKeyboardStates = new ArrayList();
                for (Keyboard keyboard : localKeyboards) {
                    KeyboardState currentState = null;
                    if (keyboardStates != null) {
                        for (KeyboardState state : keyboardStates) {
                            if (state.keyboard.getName().equals(keyboard.getName())) {
                                currentState = new KeyboardState(keyboard);
                                currentState.keysPressed.addAll(state.keysPressed);
                                break;
                            }
                        }
                    }
                    if (currentState == null) {
                        currentState = new KeyboardState(keyboard);
                    }
                    localKeyboardStates.add(currentState);
                }

                keyboardStates = localKeyboardStates.toArray(new KeyboardState[0]);

                if (keyboardStates.length > 0) {
                    if (poolingKeyboardInputThread == null) {
                        poolingKeyboardInputThread = new Thread(null, null, "PoolingKeyboardThread", 16l * 1024l) {
                            {
                                setDaemon(true);
                            }

                            @Override
                            @SuppressWarnings("SleepWhileInLoop")
                            public void run() {
                                final Event event = new Event();
                                boolean updateDevices;
                                int loopingCount;
                                Keyboard keyboard;
                                EventQueue eventQueue;

                                while (!isInterrupted()) {
                                    synchronized (JInputHook.this) {
                                        updateDevices = false;
                                        for (KeyboardState keyboardState : keyboardStates) {
                                            try {
                                                keyboard = keyboardState.keyboard;
                                                eventQueue = keyboard.getEventQueue();
                                                if (keyboard.poll()) {
                                                    if (eventQueue.getNextEvent(event)) {
                                                        loopingCount = 0;
                                                        do {
                                                            loopingCount++;
                                                            Key key = (Key) event.getComponent().getIdentifier();
                                                            if (event.getValue() > 0) {
                                                                fireKeyPressed(key);
                                                                keyboardState.keysPressed.add(key);
                                                                if (keyboardState.keysPressed.size() > 1 && !shortcutListeners.isEmpty()) {
                                                                    fireShortcutPressed(fromKeys(keyboardState.keysPressed));
                                                                }
                                                            } else {
                                                                keyboardState.keysPressed.remove(key);
                                                                fireKeyReleased(key);
                                                            }
                                                        } while (loopingCount < 10 && eventQueue.getNextEvent(event));
                                                    }
                                                } else {
                                                    updateDevices = true;
                                                }
                                            } catch (Exception e) {
                                                LOGGER.throwing(getClass().getName(), "run", e);
                                            }
                                        }
                                        if (updateDevices) {
                                            updateInputDevices();
                                        }
                                    }
                                    try {
                                        sleep(0, 1);
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            }
                        };
                        poolingKeyboardInputThread.start();
                    }
                } else {
                    if (poolingKeyboardInputThread != null) {
                        poolingKeyboardInputThread.interrupt();
                        poolingKeyboardInputThread = null;
                    }
                }
                errorOnUpdateKeyboards = false;
            } catch (Throwable t) {
                if (!errorOnUpdateKeyboards) {
                    LOGGER.log(Level.SEVERE, "Problems on updateInputDevices", t);
                }
                errorOnUpdateKeyboards = true;
            }
        }
    }

    private static ControllerEnvironment getDefaultEnvironment() {
        try {
            JInputCleaner.getInstance().cleanup();
            Constructor<ControllerEnvironment> constructor = (Constructor<ControllerEnvironment>) Class.forName("net.java.games.input.DefaultControllerEnvironment").getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static synchronized void initialize() throws Exception {
        if (INSTANCE != null) {
            throw new IllegalStateException("JInputHook already initialized.");
        }
        // extract plataform natives
        boolean extractOk = false;
        boolean is64Bits = System.getenv("ProgramFiles(x86)") != null || System.getProperty("os.arch", "x86").contains("64");
        String OS = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        if ((OS.contains("mac")) || (OS.contains("darwin"))) {
            extractOk = extractMacNatives();
        } else if (OS.contains("win")) {
            extractOk = extractWindowsNatives(is64Bits);
        } else if (OS.contains("nux")) {
            extractOk = extractLinuxNatives(is64Bits);
        }

        if (!extractOk) {
            throw new IOException("JInputHook could not extract jinput natives.");
        }

        INSTANCE = new JInputHook();

        if (INSTANCE.errorOnUpdateKeyboards) {
            throw new RuntimeException("JInputHook could not load input devices.");
        }

        INSTANCE.updateKeyboardsThread.start();
    }

    public static boolean addListener(GlobalKeyListener listener) {
        if (INSTANCE == null) {
            throw new IllegalStateException("JInputHook not initialized.");
        }
        synchronized (INSTANCE.globalKeyboardListeners) {
            return INSTANCE.globalKeyboardListeners.add(listener);
        }
    }

    public static boolean removeListener(GlobalKeyListener listener) {
        if (INSTANCE == null) {
            throw new IllegalStateException("JInputHook not initialized.");
        }
        synchronized (INSTANCE.globalKeyboardListeners) {
            return INSTANCE.globalKeyboardListeners.remove(listener);
        }
    }

    public static boolean addShortcutListener(Shortcut shortcut, ShortcutListener listener) {
        if (INSTANCE == null) {
            throw new IllegalStateException("JInputHook not initialized.");
        }
        synchronized (INSTANCE.shortcutListeners) {
            Set<ShortcutListener> listeners = INSTANCE.shortcutListeners.get(shortcut);
            if (listeners == null) {
                listeners = new HashSet();
                INSTANCE.shortcutListeners.put(shortcut, listeners);
            }
            synchronized (listeners) {
                return listeners.add(listener);
            }
        }
    }

    public static boolean removeShortcutListener(Shortcut shortcut) {
        if (INSTANCE == null) {
            throw new IllegalStateException("JInputHook not initialized.");
        }
        synchronized (INSTANCE.shortcutListeners) {
            return INSTANCE.shortcutListeners.remove(shortcut) != null;
        }
    }

    public static boolean removeShortcutListener(Shortcut shortcut, ShortcutListener listener) {
        if (INSTANCE == null) {
            throw new IllegalStateException("JInputHook not initialized.");
        }
        synchronized (INSTANCE.shortcutListeners) {
            return INSTANCE.shortcutListeners.remove(shortcut, listener);
        }
    }

    public static boolean removeShortcutListener(ShortcutListener listener) {
        if (INSTANCE == null) {
            throw new IllegalStateException("JInputHook not initialized.");
        }
        synchronized (INSTANCE.shortcutListeners) {
            boolean removed = false;
            for (Entry<Shortcut, Set<ShortcutListener>> entry : INSTANCE.shortcutListeners.entrySet()) {
                if (entry.getValue().remove(listener)) {
                    removed = true;
                }
            }
            return removed;
        }
    }

    private static final boolean extractMacNatives() {
        return extractNative("/libjinput-osx.jnilib", System.mapLibraryName("jinput-osx"));
    }

    private static final boolean extractWindowsNatives(boolean is64Bits) {
        if (is64Bits) {
            if (extractNative("/jinput-dx8_64.dll")) {
                return extractNative("/jinput-raw_64.dll");
            }
        } else if (extractNative("/jinput-dx8.dll")) {
            return extractNative("/jinput-raw.dll");
        }
        return false;
    }

    private static final boolean extractLinuxNatives(boolean is64Bits) {
        if (is64Bits) {
            return extractNative("/libjinput-linux64.so");
        } else {
            return extractNative("/libjinput-linux.so");
        }
    }

    private static final boolean extractNative(final String name) {
        return extractNative(name, null);
    }

    private static final boolean extractNative(String name, final String expectedName) {
        // Finds a stream to the native file. Change path/class if necessary
        try (final InputStream inputStream = JInputHook.class.getResourceAsStream(name)) {
            name = expectedName == null ? name : expectedName;
            if (inputStream != null) {
                File nativeFile = new File(".", name);
                if (!nativeFile.exists()) {
                    File tmpDir = Files.createTempDirectory("JInputHook-").toFile();
//                    tmpDir.deleteOnExit();
                    System.setProperty("net.java.games.input.librarypath", tmpDir.getAbsolutePath());
                    nativeFile = new File(tmpDir, name);
//                    System.err.println("extracting " + name + "...");
                    try (FileOutputStream outputStream = new FileOutputStream(nativeFile)) {
                        final byte[] array = new byte[8192];
                        for (int i = inputStream.read(array); i != -1; i = inputStream.read(array)) {
                            outputStream.write(array, 0, i);
                        }
                    }
//                    nativeFile.deleteOnExit();
                }
//                else {
//                    System.err.println("Using " + name + " found in folder.");
//            }

                return true;
            }
        } catch (final Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    private static final class KeyboardState {

        private final Keyboard keyboard;
        private final Set<Key> keysPressed = new HashSet();

        private KeyboardState(Keyboard keyboard) {
            this.keyboard = keyboard;
        }
    }
}
