package eu.hansolo.blitzrelay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class Helper {
    // ── LZW Decoder ───────────────────────────────────────────
    // Ported from Python reference at https://www.gkbrk.com/blitzortung
    //
    // def decode(b):
    //     e = {}
    //     d = list(b.decode())
    //     c = d[0]; f = c; g = [c]; h = 256; o = h
    //     for i in range(1, len(d)):
    //         a = ord(d[i])
    //         a = d[i] if h > a else e[a] if e.get(a) else f + c
    //         g.append(a); c = a[0]; e[o] = f + c; o += 1; f = a
    //     return ''.join(g).encode()

    public static byte[] lzwDecode(final byte[] input) {
        if (input == null || input.length == 0) { return new byte[0]; }

        final Map<Integer, String> dict   = new HashMap<>();
        int                        h      = 256;
        int                        o      = h;
        String                     c      = String.valueOf((char)(input[0] & 0xFF));
        String                     f      = c;
        final List<String>         result = new ArrayList<>();
        result.add(c);

        for (int i = 1; i < input.length; i++) {
            final int    code = input[i] & 0xFF;
            final String a    = (h > code) ? String.valueOf((char) code) : dict.containsKey(code) ? dict.get(code) : f + c;
            result.add(a);
            c = String.valueOf(a.charAt(0));
            dict.put(o++, f + c);
            f = a;
        }
        return String.join("", result).getBytes(StandardCharsets.ISO_8859_1);
    }
}
