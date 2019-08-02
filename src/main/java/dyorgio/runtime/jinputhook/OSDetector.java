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

import java.util.Locale;

/**
 *
 * @author dyorgio
 */
public class OSDetector {

    private static boolean DETECT_CALLED = false;
    private static boolean MAC;
    private static boolean WINDOWS;
    private static boolean LINUX;

    private static boolean X86;
    
    static {
        detect();
    }

    public static void detect() {
        if (DETECT_CALLED) {
            return;
        }
        DETECT_CALLED = true;
        String osName = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        if ((osName.indexOf("mac") != -1) || (osName.indexOf("darwin") != -1)) {
            MAC = true;

            WINDOWS = false;
            LINUX = false;
        } else if (osName.indexOf("win") != -1) {
            WINDOWS = true;

            MAC = false;
            LINUX = false;
        } else if (osName.indexOf("nux") != -1) {
            LINUX = true;

            MAC = false;
            WINDOWS = false;
        } else {
            MAC = false;
            WINDOWS = false;
            LINUX = false;
        }

        X86 = !(WINDOWS ? System.getenv("ProgramFiles(x86)") != null : System.getProperty("os.arch", "x86").indexOf("64") != -1);
    }

    public static final boolean isMac() {
        return MAC;
    }

    public static final boolean isWindows() {
        return WINDOWS;
    }

    public static final boolean isLinux() {
        return LINUX;
    }
    
    public static final boolean isUnix() {
        return !WINDOWS;
    }

    public static final boolean isOSx86() {
        return X86;
    }
}
