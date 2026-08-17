package eu.hansolo.blitzrelay;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class Helper {

    public static String lzwDecode(final String raw) {
        if (raw == null || raw.isEmpty()) { return ""; }

        final Map<Integer, String> dict     = new HashMap<>();
        int                        nextCode = 256;   // h in Python
        String                     c        = String.valueOf(raw.charAt(0));
        String                     f        = c;
        final List<String>         out      = new ArrayList<>();
        out.add(c);

        for (int i = 1; i < raw.length(); i++) {
            final int    code = (int) raw.charAt(i);   // ord(d[i]) in Python
            final String a;
            if (code < nextCode && code >= 256) {
                // Dictionary entry, code is above ASCII range
                a = dict.containsKey(code) ? dict.get(code) : f + c;
            } else if (code < 256) {
                // Literal character
                a = String.valueOf(raw.charAt(i));
            } else {
                // Fallback
                a = f + c;
            }
            out.add(a);
            c = String.valueOf(a.charAt(0));
            dict.put(nextCode++, f + c);
            f = a;
        }
        return String.join("", out);
    }

    public static String lzwDecodeBytes(final byte[] input) {
        if (input == null || input.length == 0) { return ""; }

        final Map<Integer, String> dictionary = new HashMap<>();
        int                        nextCode   = 256;

        // Work with unsigned byte values 0-255, matching Python's ord()
        String c   = String.valueOf((char)(input[0] & 0xFF));
        String f   = c;
        final List<String> out = new ArrayList<>();
        out.add(c);

        for (int i = 1; i < input.length; i++) {
            final int    code = input[i] & 0xFF;
            final String a    = (code < 256) ? String.valueOf((char) code) : dictionary.containsKey(code) ? dictionary.get(code) : f + c;
            out.add(a);
            c = String.valueOf(a.charAt(0));
            dictionary.put(nextCode++, f + c);
            f = a;
        }
        return String.join("", out);
    }
}
