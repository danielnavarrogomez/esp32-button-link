package com.navarrofernandez.esp32buttonlink.net;

import android.util.Base64;

import com.navarrofernandez.esp32buttonlink.data.EndpointConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class EndpointCaller {
    public static Result call(EndpointConfig endpoint) {
        HttpURLConnection connection = null;
        try {
            String body = buildBody(endpoint);
            boolean post = "POST".equalsIgnoreCase(endpoint.method);
            URL target = new URL(post || body.isEmpty() ? endpoint.url : appendQuery(endpoint.url, body));
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod(post ? "POST" : "GET");
            connection.setInstanceFollowRedirects(true);

            if (!endpoint.credentialsAsParams && !endpoint.username.isEmpty()) {
                String auth = endpoint.username + ":" + endpoint.password;
                String encoded = Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                connection.setRequestProperty("Authorization", "Basic " + encoded);
            }

            if (post) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(bytes);
                }
            }

            int code = connection.getResponseCode();
            String message = connection.getResponseMessage();
            drain(connection);
            return new Result(code == 200, code, message == null ? "" : message);
        } catch (Exception error) {
            return new Result(false, -1, error.getMessage() == null ? error.toString() : error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String buildBody(EndpointConfig endpoint) throws IOException {
        List<String> pairs = new ArrayList<>();
        if (endpoint.credentialsAsParams && !endpoint.username.isEmpty()) {
            pairs.add(pair("user", endpoint.username));
            pairs.add(pair("pass", endpoint.password));
        }
        for (String line : endpoint.params.split("\\R|&")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals < 0) {
                pairs.add(pair(trimmed, ""));
            } else {
                pairs.add(pair(trimmed.substring(0, equals), trimmed.substring(equals + 1)));
            }
        }
        return String.join("&", pairs);
    }

    private static String pair(String key, String value) throws IOException {
        return URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8");
    }

    private static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static void drain(HttpURLConnection connection) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getResponseCode() >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
            }
        } catch (Exception ignored) {
        }
    }

    public static class Result {
        public final boolean ok;
        public final int statusCode;
        public final String message;

        public Result(boolean ok, int statusCode, String message) {
            this.ok = ok;
            this.statusCode = statusCode;
            this.message = message;
        }
    }
}
