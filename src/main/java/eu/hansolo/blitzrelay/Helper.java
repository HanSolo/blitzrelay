package eu.hansolo.blitzrelay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class Helper {

    public static String lzwDecode(final String raw) {
        if (raw == null || raw.isEmpty()) { return ""; }

        final Map<Integer, String> e = new HashMap<>();
        int                        h = 256;
        int                        o = h;
        String                     c = String.valueOf(raw.charAt(0));
        String                     f = c;
        final List<String>         g = new ArrayList<>();
        g.add(c);

        for (int i = 1; i < raw.length(); i++) {
            final int    a_int = (int) raw.charAt(i);   // ord(d[i])
            final String a;
            if (h > a_int) {
                a = String.valueOf(raw.charAt(i));       // d[i] if h > a
            } else if (e.containsKey(a_int)) {
                a = e.get(a_int);                        // e[a] if e.get(a)
            } else {
                a = f + c;                               // f + c
            }
            g.add(a);
            c = String.valueOf(a.charAt(0));             // c = a[0]
            e.put(o, f + c);                             // e[o] = f + c
            o++;
            f = a;
        }

        return String.join("", g);
    }
}
