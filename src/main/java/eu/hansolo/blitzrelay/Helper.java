package eu.hansolo.blitzrelay;

import java.util.HashMap;
import java.util.Map;


public final class Helper {

    public static String decode(final String raw) {
        if (raw == null || raw.isEmpty()) { return ""; }

        final Map<Integer, String> dictionary = new HashMap<>();
        char                       c          = raw.charAt(0);
        String                     f          = String.valueOf(c);
        final StringBuilder        out        = new StringBuilder(raw.length());
        out.append(c);
        int nextCode = 256;

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
