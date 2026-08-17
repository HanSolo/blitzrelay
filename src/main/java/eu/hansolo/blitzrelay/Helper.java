package eu.hansolo.blitzrelay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class Helper {

    public static String lzwDecode(final byte[] b) {
        // Direct port of gkbrk.com/blitzortung reference:
        // def decode(b):
        //     e = {}
        //     d = list(b.decode())   <- bytes decoded to chars (latin-1)
        //     c = d[0]; f = c; g = [c]; h = 256; o = h
        //     for i in range(1, len(d)):
        //         a = ord(d[i])
        //         a = d[i] if h > a else e[a] if e.get(a) else f + c
        //         g.append(a); c = a[0]; e[o] = f + c; o += 1; f = a
        //     return ''.join(g).encode()

        if (b == null || b.length == 0) { return ""; }

        // b.decode() in Python uses latin-1 for byte arrays
        final char[] d = new char[b.length];
        for (int i = 0; i < b.length; i++) { d[i] = (char)(b[i] & 0xFF); }

        final Map<Integer, String> e = new HashMap<>();
        int                        h = 64;
        int                        o = h;
        String                     c = String.valueOf(d[0]);
        String                     f = c;
        final List<String>         g = new ArrayList<>();
        g.add(c);

        for (int i = 1; i < d.length; i++) {
            final int    a_int = (int) d[i];           // ord(d[i])
            final String a;
            if (h > a_int) {
                a = String.valueOf(d[i]);              // d[i] if h > a
            } else if (e.containsKey(a_int)) {
                a = e.get(a_int);                      // e[a] if e.get(a)
            } else {
                a = f + c;                             // f + c
            }
            g.add(a);
            c = String.valueOf(a.charAt(0));           // c = a[0]
            e.put(o, f + c);                           // e[o] = f + c
            o++;
            f = a;
        }

        return String.join("", g);
    }
}
