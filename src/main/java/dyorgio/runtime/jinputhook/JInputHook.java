/** *****************************************************************************
 * Copyright 2020 See AUTHORS file.
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map.Entry;
import net.java.games.input.Component;

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
                        if (controller.getComponents() != null && controller.getComponents().length > 5) {
                            localKeyboards.add((Keyboard) controller);
                        }
                    }
                }

                List<KeyboardState> localKeyboardStates = new ArrayList();
                for (Keyboard keyboard : localKeyboards) {
                    KeyboardState currentState = null;
                    if (keyboardStates != null) {
                        for (KeyboardState state : keyboardStates) {
                            if (state.keyboard.getName() == null ? keyboard.getName() == null : state.keyboard.getName().equals(keyboard.getName())) {
                                if (OSDetector.isMac()) {
                                    try {
                                        Field queueField = state.keyboard.getClass().getDeclaredField("queue");
                                        queueField.setAccessible(true);
                                        Object queue = queueField.get(state.keyboard);
                                        Class queueClass = queue.getClass();
                                        Method queueReleaseMethod = queueClass.getDeclaredMethod("release");
                                        queueReleaseMethod.setAccessible(true);
                                        queueReleaseMethod.invoke(queue);
                                    } catch (Exception ex) {
                                        LOGGER.log(Level.SEVERE, null, ex);
                                    }
                                }
                                currentState = new KeyboardState(keyboard);
                                currentState.keysPressed.addAll(state.keysPressed);
                                currentState.eventQueue.updateQueue(keyboard);
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
                                KeyboardEventQueue eventQueue;

                                while (!isInterrupted()) {
                                    synchronized (JInputHook.this) {
                                        updateDevices = false;
                                        for (KeyboardState keyboardState : keyboardStates) {
                                            try {
                                                if (keyboardState.keyboard.poll()) {
                                                    eventQueue = keyboardState.eventQueue;
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
                                                        } while (loopingCount < 1000 && eventQueue.getNextEvent(event));
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
        OSDetector.detect();
        if (OSDetector.isMac()) {
            extractOk = extractMacNatives();
        } else if (OSDetector.isWindows()) {
            extractOk = extractWindowsNatives();
        } else if (OSDetector.isLinux()) {
            extractOk = extractLinuxNatives();
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
            Set<ShortcutListener> listeners = INSTANCE.shortcutListeners.get(shortcut);
            if (listeners != null) {
                boolean result = listeners.remove(listener);
                if (result && listeners.isEmpty()) {
                    INSTANCE.shortcutListeners.remove(shortcut);
                }
                return result;
            }
            return false;
        }
    }

    public static boolean removeShortcutListener(ShortcutListener listener) {
        if (INSTANCE == null) {
            throw new IllegalStateException("JInputHook not initialized.");
        }
        synchronized (INSTANCE.shortcutListeners) {
            boolean removed = false;
            Set<Shortcut> toRemove = new HashSet();
            for (Entry<Shortcut, Set<ShortcutListener>> entry : INSTANCE.shortcutListeners.entrySet()) {
                if (entry.getValue().remove(listener)) {
                    removed = true;
                    if (entry.getValue().isEmpty()) {
                        toRemove.add(entry.getKey());
                    }
                }
            }
            for (Shortcut shortcut : toRemove) {
                INSTANCE.shortcutListeners.remove(shortcut);
            }

            return removed;
        }
    }

    private static boolean extractMacNatives() {
        return extractNative("/libjinput-osx.jnilib", System.mapLibraryName("jinput-osx"));
    }

    private static boolean extractWindowsNatives() {
        if (OSDetector.isOSx86()) {
            if (extractNative("/jinput-dx8.dll")) {
                return extractNative("/jinput-raw.dll");
            }
        } else if (extractNative("/jinput-dx8_64.dll")) {
            return extractNative("/jinput-raw_64.dll");
        }
        return false;
    }

    private static boolean extractLinuxNatives() {
        if (OSDetector.isOSx86()) {
            return extractNative("/libjinput-linux.so");
        } else {
            return extractNative("/libjinput-linux64.so");
        }
    }

    private static boolean extractNative(final String name) {
        return extractNative(name, null);
    }

    private static boolean extractNative(String name, final String expectedName) {
        // Finds a stream to the native file. Change path/class if necessary
        try (final InputStream inputStream = JInputHook.class.getResourceAsStream(name)) {
            name = expectedName == null ? name : expectedName;
            if (inputStream != null) {
                File nativeFile = new File(".", name);
                if (!nativeFile.exists()) {
                    File tmpDir = Files.createTempDirectory("JInputHook-").toFile();
                    tmpDir.deleteOnExit();
                    System.setProperty("net.java.games.input.librarypath", tmpDir.getAbsolutePath());
                    nativeFile = new File(tmpDir, name);
                    try (FileOutputStream outputStream = new FileOutputStream(nativeFile)) {
                        final byte[] array = new byte[8192];
                        for (int i = inputStream.read(array); i != -1; i = inputStream.read(array)) {
                            outputStream.write(array, 0, i);
                        }
                    }
                    nativeFile.deleteOnExit();
                }
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
        private final KeyboardEventQueue eventQueue;

        private KeyboardState(Keyboard keyboard) {
            this.keyboard = keyboard;
            if (OSDetector.isUnix()) {
                eventQueue = new PollKeyboardEventQueue();
            } else {
                eventQueue = new JInputKeyboardEventQueue();
            }
            eventQueue.updateQueue(keyboard);
        }
    }

    private static interface KeyboardEventQueue {

        void updateQueue(Keyboard keyboard);

        boolean getNextEvent(Event event);
    }

    private static class PollKeyboardEventQueue implements KeyboardEventQueue {

        private Component[] components;
        private float[] previousValues;
        private int lastIndex = 0;
        private float lastValue;

        @Override
        public void updateQueue(Keyboard keyboard) {
            this.components = keyboard.getComponents();
            if (previousValues == null || previousValues.length != components.length) {
                previousValues = new float[components.length];
                if (keyboard.poll()) {
                    for (int i = 0; i < components.length; i++) {
                        previousValues[i] = components[i].getPollData();
                    }
                }
            }
            lastIndex = 0;
        }

        @Override
        public boolean getNextEvent(Event event) {
            for (int i = lastIndex; i < components.length; i++) {
                lastValue = components[i].getPollData();
                if (lastValue != previousValues[i]) {
                    event.set(components[i], lastValue, 0); // dont generate timestamp for performance reasons (last parameter)
                    previousValues[i] = lastValue;
                    i++;
                    if (i == components.length) {
                        i = 0;
                    }
                    lastIndex = i;
                    return true;
                }
            }

            lastIndex = 0;

            try {
                sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

    }

    private static class JInputKeyboardEventQueue implements KeyboardEventQueue {

        private EventQueue eventQueue;

        @Override
        public void updateQueue(Keyboard keyboard) {
            this.eventQueue = keyboard.getEventQueue();
        }

        @Override
        public boolean getNextEvent(Event event) {
            return eventQueue.getNextEvent(event);
        }
    }
}
