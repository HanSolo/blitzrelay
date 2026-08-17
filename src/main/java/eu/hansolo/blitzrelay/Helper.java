package eu.hansolo.blitzrelay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class Helper {

    public static String lzwDecode(final String raw) {
        // The reference implementation takes bytes and calls b.decode()
        // Java's WebSocket decoded the bytes as UTF-8, but they were Latin-1
        // Re-encode to ISO-8859-1 to get the original bytes back
        final byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);

        final Map<Integer, String> e = new HashMap<>();
        int                        h = 256;
        int                        o = h;

        // d = list(b.decode()), work with the bytes as Latin-1 chars
        String                     c = String.valueOf((char)(bytes[0] & 0xFF));
        String                     f = c;
        final List<String>         g = new ArrayList<>();
        g.add(c);

        for (int i = 1; i < bytes.length; i++) {
            final int    a_int = bytes[i] & 0xFF;   // ord(d[i])
            final String a;
            if (h > a_int) {
                a = String.valueOf((char) a_int);    // d[i] if h > a
            } else if (e.containsKey(a_int)) {
                a = e.get(a_int);                    // e[a] if e.get(a)
            } else {
                a = f + c;                           // f + c
            }
            g.add(a);
            c = String.valueOf(a.charAt(0));         // c = a[0]
            e.put(o, f + c);                         // e[o] = f + c
            o++;
            f = a;
        }
        return String.join("", g);                   // ''.join(g)
    }
}
