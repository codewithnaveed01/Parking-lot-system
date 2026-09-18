package com.parking.util;

import java.util.*;

/** Minimal, safe JSON encoder + tiny flat-object parser (no external libraries). */
public final class Json {
    private Json() {}

    public static String encode(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String) writeString(sb, (String) o);
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map) {
            sb.append('{'); boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) sb.append(','); first = false;
                writeString(sb, String.valueOf(e.getKey())); sb.append(':'); write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection) {
            sb.append('['); boolean first = true;
            for (Object x : (Collection<Object>) o) { if (!first) sb.append(','); first = false; write(sb, x); }
            sb.append(']');
        } else writeString(sb, o.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;   // XSS hardening
                case '>': sb.append("\\u003e"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Parses a flat JSON object of string/number/bool values: {"a":"b","n":1}. */
    public static Map<String, String> parseFlat(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null) return out;
        int i = 0, n = json.length();
        i = skipWs(json, i);
        if (i >= n || json.charAt(i) != '{') return out;
        i++;
        while (i < n) {
            i = skipWs(json, i);
            if (i < n && json.charAt(i) == '}') break;
            if (i >= n || json.charAt(i) != '"') break;
            StringBuilder key = new StringBuilder();
            i = readString(json, i + 1, key);
            i = skipWs(json, i);
            if (i >= n || json.charAt(i) != ':') break;
            i = skipWs(json, i + 1);
            StringBuilder val = new StringBuilder();
            if (i < n && json.charAt(i) == '"') i = readString(json, i + 1, val);
            else { int s = i; while (i < n && ",}".indexOf(json.charAt(i)) < 0) i++; val.append(json, s, i); }
            out.put(key.toString(), val.toString().trim());
            i = skipWs(json, i);
            if (i < n && json.charAt(i) == ',') i++;
        }
        return out;
    }

    private static int skipWs(String s, int i) { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; return i; }

    private static int readString(String s, int i, StringBuilder out) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(++i);
                switch (nx) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 < s.length()) { out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                        break;
                    default: out.append(nx);
                }
                i++;
            } else if (c == '"') return i + 1;
            else { out.append(c); i++; }
        }
        return i;
    }
}
