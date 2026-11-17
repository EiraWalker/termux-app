package com.termux.app.privateadb;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** No commands, addresses, keys or arbitrary paths are accepted from Intent extras. */
public final class AutostartConfig {
    public static final String MANAGER = "/data/data/com.termux/files/usr/bin/tablet-daemon";
    public static final String HEARTBEAT = "/data/data/com.termux/files/home/.config/tablet-daemon/health.json";
    public final String expectedSerial;
    private AutostartConfig(String serial) { expectedSerial = serial; }
    public static AutostartConfig parse(String text) throws Exception {
        JSONObject j = new JSONObject(text);
        if (!Integer.valueOf(1).equals(j.get("version")) || !Boolean.TRUE.equals(j.get("enabled")) ||
            !MANAGER.equals(j.getString("managerPath")) || !HEARTBEAT.equals(j.getString("heartbeatPath")))
            throw new IOException("config_disabled_or_invalid");
        Object serial = j.get("expectedSerial");
        if (!(serial instanceof String) || !((String)serial).matches("[A-Za-z0-9._-]{1,128}"))
            throw new IOException("config_identity_invalid");
        return new AutostartConfig((String)serial);
    }
    public static File directory(Context c) {
        return new File(c.getFilesDir(), "home/.config/tablet-daemon");
    }
    public static AutostartConfig load(Context c) {
        try { return parse(read(new File(directory(c), "autostart.json"))); }
        catch (Exception ignored) { return null; }
    }
    static String read(File file) throws IOException {
        if (!file.isFile() || file.length() > 8192) throw new IOException("invalid_file");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[1024]; int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() + n > 8192) throw new IOException("oversize");
                out.write(b, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
