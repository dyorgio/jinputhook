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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.java.games.input.Component.Identifier.Key;

/**
 *
 * @author dyorgio
 */
public final class Shortcut {

    private final Set<Key> keys = new LinkedHashSet();

    public Shortcut(Set<Key> keys) {
        if (keys == null || keys.size() < 2) {
            throw new IllegalArgumentException("Shortcut must have 2 or more keys.");
        } else if (keys.contains(null)) {
            throw new IllegalArgumentException("Shortcut must have 2 or more keys.");
        }

        this.keys.addAll(keys.stream().sorted(new Comparator<Key>() {
            @Override
            public int compare(Key k1, Key k2) {
                return k1.getName().compareTo(k2.getName());
            }
        }).collect(Collectors.toCollection(new Supplier<Collection<Key>>() {
            @Override
            public Collection<Key> get() {
                return new ArrayList();
            }
        })));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.keys);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        return Objects.equals(this.keys, ((Shortcut) obj).keys);
    }

    @Override
    public String toString() {
        return keys.toString(); //To change body of generated methods, choose Tools | Templates.
    }

    public static Shortcut fromKeys(Collection<Key> keys) {
        return new Shortcut(new HashSet(keys));
    }

    public static Shortcut fromKeys(Key... keys) {
        return new Shortcut(new HashSet(Arrays.asList(keys)));
    }
}
