package com.termux.app.privateadb;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Strict flat JSON grammar: no JSONTokener coercions, comments or duplicate keys. */
final class MaintenanceProtocol {
    static final int MAX_PAYLOAD_BYTES = 2048;

    static final class Parsed {
        final MaintenanceLease.Request request;
        final String token;
        Parsed(MaintenanceLease.Request request, String token) {
            this.request = request;
            this.token = token;
        }
    }

    static Parsed parse(String verb, String text) {
        if (!("request".equals(verb) || "status".equals(verb) || "release".equals(verb))
                || text == null || text.length() > MAX_PAYLOAD_BYTES) throw invalid();
        try {
            if (StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text)).remaining() > MAX_PAYLOAD_BYTES) throw invalid();
        } catch (CharacterCodingException e) {
            throw invalid();
        }
        Map<String, Object> fields = new Reader(text).object();
        if (number(fields, "version") != 1) throw invalid();
        String token = hex(fields, "token");
        if (!"request".equals(verb)) {
            if (fields.size() != 2) throw invalid();
            return new Parsed(null, token);
        }
        if (fields.size() != 8) throw invalid();
        String boot = string(fields, "boot");
        if (!boot.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        long deadline = number(fields, "deadlineElapsedMs");
        String profile = hex(fields, "profileSha256");
        String release = hex(fields, "releaseSha256");
        long baseline = number(fields, "baselineVersion");
        long target = number(fields, "targetVersion");
        if (deadline <= 0 || baseline <= 0 || target < baseline) throw invalid();
        return new Parsed(new MaintenanceLease.Request(token, boot, deadline, profile, release,
                baseline, target), token);
    }

    static String readPayload(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        // Read at most limit + 1 even from a never-ending stream. EOF is still required.
        for (int count = 0; count <= MAX_PAYLOAD_BYTES; count++) {
            int b = input.read();
            if (b == -1) {
                try {
                    return StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                } catch (CharacterCodingException e) {
                    throw invalid();
                }
            }
            if (count == MAX_PAYLOAD_BYTES) throw invalid();
            bytes.write(b);
        }
        throw invalid();
    }

    private static String string(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String)) throw invalid();
        return (String) value;
    }

    private static String hex(Map<String, Object> fields, String key) {
        String value = string(fields, key);
        if (!value.matches("[0-9a-f]{64}")) throw invalid();
        return value;
    }

    private static long number(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof Long)) throw invalid();
        return (Long) value;
    }

    private static IllegalArgumentException invalid() {
        // Never include payload, token, parser excerpts or causes in externally visible errors.
        return new IllegalArgumentException("invalid maintenance payload");
    }

    private static final class Reader {
        private final String text;
        private int offset;
        Reader(String text) { this.text = text; }

        Map<String, Object> object() {
            Map<String, Object> fields = new HashMap<>();
            space();
            require('{');
            space();
            if (!take('}')) {
                do {
                    space();
                    String key = quoted();
                    if (fields.containsKey(key)) throw invalid();
                    space();
                    require(':');
                    space();
                    Object value = peek() == '"' ? quoted() : integer();
                    fields.put(key, value);
                    space();
                    if (take('}')) break;
                    require(',');
                } while (true);
            }
            space();
            if (offset != text.length()) throw invalid();
            return fields;
        }

        private Long integer() {
            int start = offset;
            take('-');
            if (!take('0')) {
                if (peek() < '1' || peek() > '9') throw invalid();
                while (peek() >= '0' && peek() <= '9') offset++;
            }
            try {
                return Long.valueOf(text.substring(start, offset));
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        private String quoted() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (offset == text.length()) throw invalid();
                char c = text.charAt(offset++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw invalid();
                if (c != '\\') { out.append(c); continue; }
                if (offset == text.length()) throw invalid();
                char escape = text.charAt(offset++);
                switch (escape) {
                    case '"': case '\\': case '/': out.append(escape); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        int value = 0;
                        for (int i = 0; i < 4; i++) {
                            if (offset == text.length()) throw invalid();
                            char digit = text.charAt(offset++);
                            int n = digit >= '0' && digit <= '9' ? digit - '0'
                                    : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                                    : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                            if (n < 0) throw invalid();
                            value = value * 16 + n;
                        }
                        out.append((char) value);
                        break;
                    default: throw invalid();
                }
            }
        }

        private char peek() { return offset < text.length() ? text.charAt(offset) : '\0'; }
        private boolean take(char c) {
            if (offset < text.length() && text.charAt(offset) == c) { offset++; return true; }
            return false;
        }
        private void require(char c) { if (!take(c)) throw invalid(); }
        private void space() {
            while (offset < text.length()) {
                char c = text.charAt(offset);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
                offset++;
            }
        }
    }

    private MaintenanceProtocol() {}
}
