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

    public static String unpackBlitzortung(final String raw) {
        if (raw == null || raw.isEmpty()) { return ""; }

        final Map<Integer, String> dictionary = new HashMap<>();
        char                       c          = raw.charAt(0);
        String                     f          = String.valueOf(c);
        int                        nextCode   = 256;
        final StringBuilder        out        = new StringBuilder(raw.length());
        out.append(c);

        for (int i = 1; i < raw.length(); i++) {
            final int    code = raw.charAt(i);
            final String a;
            if (code < 256) {
                a = String.valueOf(raw.charAt(i));
            } else {
                final String entry = dictionary.get(code);
                a = (entry != null) ? entry : f + c;
            }
            out.append(a);
            c = a.charAt(0);
            dictionary.put(nextCode++, f + c);
            f = a;
        }
        return out.toString();
    }
}
