package com.afusekt.lsp.sync;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class WebDavHttpClient {

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        public boolean isSuccess() {
            return code >= 200 && code < 300;
        }
    }

    private WebDavHttpClient() {
    }

    public static Response get(String url, String username, String password) throws IOException {
        HttpURLConnection connection = open(url, username, password, "GET");
        connection.setDoInput(true);
        return read(connection);
    }

    public static Response getToFile(String url, String username, String password, File dest)
            throws IOException {
        HttpURLConnection connection = open(url, username, password, "GET");
        connection.setDoInput(true);
        int code = connection.getResponseCode();
        if (code >= 200 && code < 300) {
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } else {
            readStream(connection.getErrorStream());
        }
        connection.disconnect();
        return new Response(code, "");
    }

    public static Response put(String url, String username, String password, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return putBytes(url, username, password, bytes);
    }

    public static Response putFile(String url, String username, String password, File file) throws IOException {
        HttpURLConnection connection = open(url, username, password, "PUT");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(file.length());
        try (InputStream in = new FileInputStream(file); OutputStream out = connection.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return read(connection);
    }

    public static Response putBytes(String url, String username, String password, byte[] bytes)
            throws IOException {
        HttpURLConnection connection = open(url, username, password, "PUT");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }
        return read(connection);
    }

    private static HttpURLConnection open(String url, String username, String password, String method)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", basicAuth(username, password));
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", "AfusektUnlock-WebDAV/1.0");
        return connection;
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readStream(stream);
        connection.disconnect();
        return new Response(code, body);
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String basicAuth(String username, String password) {
        String token = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }
}
