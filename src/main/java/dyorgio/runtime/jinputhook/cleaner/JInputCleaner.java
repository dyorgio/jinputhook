package dyorgio.runtime.jinputhook.cleaner;

import dyorgio.runtime.jinputhook.OSDetector;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 *
 * @author dyorgio
 */
public abstract class JInputCleaner {

    private static final JInputCleaner INSTANCE;

    static {
        OSDetector.detect();
        if (OSDetector.isMac()) {
            // do nothing on macOS (no leaks)
            INSTANCE = new DummyJInputCleaner();
        } else if (OSDetector.isWindows()) {
            INSTANCE = new WinJInputCleaner();
        } else if (OSDetector.isLinux()) {
            INSTANCE = new LinuxJInputCleaner();
        } else {
            INSTANCE = new DummyJInputCleaner();
        }
    }

    public abstract void cleanup() throws Exception;

    static void cleanShutdowHooks() throws Exception {
        Class applicationShutdownHooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
        //private static IdentityHashMap<Thread, Thread> hooks;
        Field hooksField = applicationShutdownHooksClass.getDeclaredField("hooks");
        hooksField.setAccessible(true);
        IdentityHashMap<Thread, Thread> applicationHooks = (IdentityHashMap<Thread, Thread>) hooksField.get(null);

        Collection<Thread> threads;
        synchronized (applicationShutdownHooksClass) {
            threads = applicationHooks.keySet();
            applicationHooks = null;
        }

        Set<Thread> toRemove = new HashSet();
        for (Thread hook : threads) {
            if (hook.getClass().getName().startsWith("net.java.games.input")) {
                try {
                    hook.start();
                } catch (IllegalThreadStateException its) {
                    // ignore
                }
                toRemove.add(hook);
            }
        }
        for (Thread hook : toRemove) {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    public static JInputCleaner getInstance() {
        return INSTANCE;
    }
}
