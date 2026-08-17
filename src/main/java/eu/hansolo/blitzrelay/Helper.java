package eu.hansolo.blitzrelay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class Helper {
    public static String lzwDecode(final String raw) {
        final List<Character> d = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) { d.add(raw.charAt(i)); }
        if (d.isEmpty()) { return ""; }

        final Map<Integer, String> dictionary = new HashMap<>();
        int                        nextCode   = 256;
        String                     c          = String.valueOf(d.get(0));
        String                     f          = c;
        final List<String>         out        = new ArrayList<>();
        out.add(c);

        for (int i = 1; i < d.size(); i++) {
            final int    code = (int) d.get(i);
            final String a    = (code < 256) ? String.valueOf(d.get(i)) : dictionary.containsKey(code) ? dictionary.get(code) : f + c;
            out.add(a);
            c = String.valueOf(a.charAt(0));
            dictionary.put(nextCode++, f + c);
            f = a;
        }
        return String.join("", out);
    }
}
