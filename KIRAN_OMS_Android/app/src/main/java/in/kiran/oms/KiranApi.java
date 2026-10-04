package in.kiran.oms;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class KiranApi {
    static final int DISCOVERY_PORT = 8789;
    static final String DISCOVERY_REQUEST = "KIRAN_OMS_DISCOVER_V1";
    static final String DISCOVERY_PREFIX = "KIRAN_OMS_HERE|";

    private KiranApi() {}

    static String normalize(String raw) {
        String x = raw == null ? "" : raw.trim();
        if (x.isEmpty()) return "";
        if (!x.startsWith("http://") && !x.startsWith("https://")) x = "http://" + x;
        if (!x.endsWith("/")) x += "/";
        if (x.startsWith("http://")) {
            try {
                URL u = new URL(x);
                if (u.getPort() == -1 && u.getHost() != null && !u.getHost().isEmpty()) {
                    x = "http://" + u.getHost() + ":8787/";
                }
            } catch (Exception ignored) {}
        }
        return x;
    }

    static String discover() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1800);
            byte[] out = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                    out, out.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            for (int i = 0; i < 2; i++) {
                socket.send(packet);
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    socket.receive(reply);
                    String msg = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8).trim();
                    if (msg.startsWith(DISCOVERY_PREFIX)) {
                        return normalize(msg.substring(DISCOVERY_PREFIX.length()));
                    }
                } catch (java.net.SocketTimeoutException ignored) {}
            }
        } catch (Exception ignored) {}
        return "";
    }

    static JSONObject getState(String server) throws Exception {
        return getJson(normalize(server) + "api/state");
    }

    static JSONObject getHealth(String server) throws Exception {
        return getJson(normalize(server) + "api/health");
    }

    static JSONObject saveKey(String server, String key, String jsonValue, String clientId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("client_id", clientId);
        body.put("key", key);
        body.put("value", jsonValue);
        return postJson(normalize(server) + "api/kv", body);
    }

    static JSONObject backup(String server) throws Exception {
        return postJson(normalize(server) + "api/backup", new JSONObject());
    }

    private static JSONObject getJson(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(3500);
        c.setReadTimeout(7000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            String text = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject postJson(String target, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(3500);
        c.setReadTimeout(9000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(raw.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(raw);
        }
        try {
            int code = c.getResponseCode();
            String text = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }
}
