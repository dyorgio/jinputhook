package dyorgio.runtime.jinputhook.cleaner;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import java.lang.reflect.Field;

/**
 *
 * @author dyorgio
 */
class WinJInputCleaner extends JInputCleaner {

    @Override
    public void cleanup() throws Exception {
        cleanShutdowHooks();

        // Close dummy window
        Thread[] activeThreads = new Thread[(int) (Thread.activeCount() * 1.25f)];
        int readed = Thread.enumerate(activeThreads);
        for (int i = 0; i < readed; i++) {
            Thread thread = activeThreads[i];
            if (thread.getClass().getName().startsWith("net.java.games.input.RawInput")) {
                thread.interrupt();

                Class threadClass = thread.getClass();
                Field dummyWindowField = threadClass.getDeclaredField("window");
                dummyWindowField.setAccessible(true);
                Object dummyWindowObj = dummyWindowField.get(thread);
                Class dummyWindowClass = dummyWindowObj.getClass();

                Field hwndAddressField = dummyWindowClass.getDeclaredField("hwnd_address");
                hwndAddressField.setAccessible(true);
                User32.WPARAM param1 = new User32.WPARAM();
                User32.LPARAM param2 = new User32.LPARAM();
                User32.INSTANCE.PostMessage(
                        new WinDef.HWND(new Pointer(hwndAddressField.getLong(dummyWindowObj))),
                        User32.WM_DESTROY,
                        param1, param2
                );
            }
        }
    }

}
