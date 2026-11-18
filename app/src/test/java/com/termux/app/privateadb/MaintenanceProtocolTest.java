package com.termux.app.privateadb;

import org.junit.Test;
import static org.junit.Assert.*;

public class MaintenanceProtocolTest {
    private static final String TOKEN = repeat('a', 64);
    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }

    private static final String BOOT = "01234567-89ab-cdef-0123-456789abcdef";
    private static String request() {
        return "{\"version\":1,\"token\":\"" + TOKEN + "\",\"boot\":\"" + BOOT
                + "\",\"deadlineElapsedMs\":123456,\"profileSha256\":\"" + repeat('b', 64)
                + "\",\"releaseSha256\":\"" + repeat('c', 64)
                + "\",\"baselineVersion\":118,\"targetVersion\":119}";
    }

    @Test public void requestConstructsImmutableLeaseFields() {
        MaintenanceProtocol.Parsed parsed = MaintenanceProtocol.parse("request", request());
        MaintenanceLease.Request r = parsed.request;
        assertEquals(TOKEN, parsed.token);
        assertEquals(TOKEN, r.token);
        assertEquals(BOOT, r.boot);
        assertEquals(123456L, r.deadline);
        assertEquals(repeat('b', 64), r.profile);
        assertEquals(repeat('c', 64), r.release);
        assertEquals(118L, r.baseline);
        assertEquals(119L, r.target);
    }

    @Test public void releaseAcceptsReorderedFieldsAndJsonWhitespace() {
        MaintenanceProtocol.Parsed p = MaintenanceProtocol.parse("release",
                " \n{\t\"token\" : \"" + TOKEN + "\", \"version\" : 1 }\r\n");
        assertEquals(TOKEN, p.token);
        assertNull(p.request);
    }

    private static void rejected(String verb, String text) {
        try {
            MaintenanceProtocol.parse(verb, text);
            fail("accepted invalid payload");
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid maintenance payload", expected.getMessage());
            assertNull(expected.getCause());
        }
    }

    @Test public void exactSchemasRejectMissingAndUnknownFields() {
        for (String verb : new String[]{"status", "release"}) {
            rejected(verb, "{\"version\":1}");
            rejected(verb, "{\"version\":1,\"token\":\"" + TOKEN + "\",\"path\":\"/tmp\"}");
            rejected(verb, request());
        }
        String[] keys = {"version", "token", "boot", "deadlineElapsedMs", "profileSha256",
                "releaseSha256", "baselineVersion", "targetVersion"};
        for (String key : keys) rejected("request", request().replace("\"" + key + "\":", "\"unknown\":"));
        rejected("request", request().replace("}", ",\"shell\":\"id\"}"));
    }

    @Test public void duplicateDecodedKeysAreRejected() {
        rejected("status", "{\"version\":1,\"token\":\"" + TOKEN + "\",\"version\":1}");
        rejected("status", "{\"version\":1,\"token\":\"" + TOKEN + "\",\"ver\\u0073ion\":1}");
        rejected("request", request().replace("}", ",\"token\":\"" + TOKEN + "\"}"));
    }

    @Test public void integerTypesAndBoundsAreNotCoerced() {
        for (String bad : new String[]{"\"1\"", "1.0", "1e0", "01", "+1", "true", "null", "[]", "{}", "2", "-1"})
            rejected("request", request().replace("\"version\":1,", "\"version\":" + bad + ","));
        for (String field : new String[]{"deadlineElapsedMs", "baselineVersion", "targetVersion"}) {
            String original = field.equals("deadlineElapsedMs") ? "123456" : field.equals("baselineVersion") ? "118" : "119";
            for (String bad : new String[]{"0", "-1", "\"119\"", "119.0", "1e3", "9223372036854775808", "-9223372036854775809"})
                rejected("request", request().replace("\"" + field + "\":" + original,
                        "\"" + field + "\":" + bad));
        }
        rejected("request", request().replace("\"targetVersion\":119", "\"targetVersion\":117"));
        assertEquals(Long.MAX_VALUE, MaintenanceProtocol.parse("request", request()
                .replace("\"targetVersion\":119", "\"targetVersion\":9223372036854775807")).request.target);
        assertEquals(118L, MaintenanceProtocol.parse("request", request()
                .replace("\"targetVersion\":119", "\"targetVersion\":118")).request.target);
    }

    @Test public void identifiersAreCanonicalAndNeverPathsOrCommands() {
        for (String bad : new String[]{repeat('A', 64), repeat('a', 63), repeat('g', 64), "../lease", "$(id)", "", "null"}) {
            rejected("request", request().replace(TOKEN, bad));
            rejected("request", request().replace(repeat('b', 64), bad));
            rejected("request", request().replace(repeat('c', 64), bad));
        }
        rejected("request", request().replace(BOOT, BOOT.toUpperCase(java.util.Locale.ROOT)));
        rejected("request", request().replace(BOOT, "1-1-1-1-1"));
        rejected("request", request().replace("\"" + BOOT + "\"", "null"));
        rejected("status", "{\"version\":1,\"token\":null}");
    }

    @Test public void nonJsonExtensionsAndTrailingDocumentsAreRejected() {
        String status = "{\"version\":1,\"token\":\"" + TOKEN + "\"}";
        for (String bad : new String[]{status + "{}", status + "x", status.replace("}", ",}"),
                status.replace('"', '\''), status.replace(":", "="), status.replace(",", ";"),
                "/*comment*/" + status, "//comment\n" + status, "\ufeff" + status,
                "\u00a0" + status, "[" + status + "]", status.replace("version", "ver\nsion"),
                status.replace("version", "ver\\xsion"), status.replace("version", "ver\\uZZZZsion")})
            rejected("status", bad);
    }

    @Test public void nullVerbUnknownVerbAndEmptyTextFailClosed() {
        for (String verb : new String[]{null, "", "REQUEST", "stop", "status;id"}) rejected(verb, request());
        for (String text : new String[]{null, "", "{}", "[]", "null", "true", "{", "{\"x\":", "{\"x\":\""})
            rejected("request", text);
    }

    @Test public void decodedJsonEscapesAreAllowedForCanonicalValues() {
        String escaped = "{\"ver\\u0073ion\":1,\"token\":\"\\u0061" + TOKEN.substring(1) + "\"}";
        assertEquals(TOKEN, MaintenanceProtocol.parse("status", escaped).token);
    }

    @Test public void utf8LimitIncludesWhitespaceAndRejectsUnpairedSurrogates() {
        String status = "{\"version\":1,\"token\":\"" + TOKEN + "\"}";
        String padded = status + repeat(' ', 2048 - status.length());
        assertEquals(TOKEN, MaintenanceProtocol.parse("status", padded).token);
        rejected("status", padded + " ");
        rejected("status", status + '\ud800');
        rejected("status", status + '\udc00');
        rejected("status", repeat('\u00e9', 1025));
    }

    @Test public void boundedStdinUsesStrictUtf8AndStopsAfterLimitPlusOne() throws Exception {
        java.lang.reflect.Method read = Class.forName("com.termux.app.privateadb.MaintenanceProtocol")
                .getDeclaredMethod("readPayload", java.io.InputStream.class);
        read.setAccessible(true);
        String status = "{\"version\":1,\"token\":\"" + TOKEN + "\"}";
        assertEquals(status, read.invoke(null, new java.io.ByteArrayInputStream(
                status.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        byte[][] invalid = {new byte[]{(byte) 0xc0, (byte) 0xaf},
                new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}, new byte[]{(byte) 0xc3},
                new byte[2049]};
        for (byte[] bytes : invalid) {
            try { read.invoke(null, new java.io.ByteArrayInputStream(bytes)); fail("accepted invalid stdin"); }
            catch (java.lang.reflect.InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
            }
        }
        final int[] count = {0};
        java.io.InputStream infinite = new java.io.InputStream() {
            @Override public int read() { count[0]++; return ' '; }
        };
        try { read.invoke(null, infinite); fail("accepted unbounded stdin"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        assertEquals(2049, count[0]);
    }

    @Test public void statusParsesVersionAndTokenWithoutRequest() throws Exception {
        Class<?> protocol = Class.forName("com.termux.app.privateadb.MaintenanceProtocol");
        java.lang.reflect.Method parse = protocol.getDeclaredMethod("parse", String.class, String.class);
        parse.setAccessible(true);
        Object parsed = parse.invoke(null, "status", "{\"version\":1,\"token\":\"" + TOKEN + "\"}");
        java.lang.reflect.Field token = parsed.getClass().getDeclaredField("token");
        java.lang.reflect.Field request = parsed.getClass().getDeclaredField("request");
        token.setAccessible(true);
        request.setAccessible(true);
        assertEquals(TOKEN, token.get(parsed));
        assertNull(request.get(parsed));
    }
}
