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

import net.java.games.input.Component.Identifier.Key;

/**
 *
 * @author dyorgio
 */
public class JInputHookExample {
    
    public static void main(String[] args) throws Exception {
        JInputHook.initialize();
        Shortcut shortcut = Shortcut.fromKeys(Key.LCONTROL, Key.D);
        JInputHook.addShortcutListener(//
                shortcut,//
                new ShortcutListener() {
            @Override
            public void shortcutTriggered(Shortcut shortcut) {
                System.out.println("shortcutTriggered(" + shortcut + ").");
            }
        });
        
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

        // wait one minute to finish
        Thread.sleep(60l * 1000l);
    }
}
