package com.aulaiag.reto11;

import android.net.Uri;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

final class LoopbackLogin implements AutoCloseable {
    static final String PATH = "/auth/callback";

    private static final String HOST = "127.0.0.1";
    private static final int LOGIN_TIMEOUT_MS = 300_000;
    private static final int REQUEST_TIMEOUT_MS = 2_000;
    private static final int REQUEST_LIMIT = 16_384;

    private final ServerSocket listener;
    private final long deadline;
    private final int port;
    private volatile Socket connection;

    LoopbackLogin() throws IOException {
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(HOST, 0), 4);
        } catch (IOException exception) {
            listener.close();
            throw exception;
        }
        port = listener.getLocalPort();
        deadline = SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS;
    }

    String redirectUri() {
        return "http://" + HOST + ":" + port + PATH;
    }

    Result await(String expectedState) throws IOException {
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                int remaining = (int) Math.max(1, deadline - SystemClock.elapsedRealtime());
                listener.setSoTimeout(remaining);
                try (Socket socket = listener.accept()) {
                    connection = socket;
                    if (listener.isClosed()) {
                        throw new SocketException("Login listener closed");
                    }
                    socket.setSoTimeout(REQUEST_TIMEOUT_MS);
                    Result result = readCallback(socket, expectedState);
                    if (result != null) {
                        return result;
                    }
                } finally {
                    connection = null;
                }
            }
        } catch (SocketTimeoutException exception) {
            // The listener timeout is the authorization deadline.
        }
        throw new ChatGptAuth.AuthException("authorization_timeout",
                "Se agotó el tiempo para conectar la cuenta. Inténtalo de nuevo.");
    }

    private Result readCallback(Socket socket, String expectedState) throws IOException {
        Result result = null;
        try {
            result = parse(readHeaders(socket), expectedState);
        } catch (IOException | IllegalArgumentException exception) {
            // Ignore incomplete, unrelated, and malformed requests while waiting for the browser.
        }
        try {
            if (result == null) {
                respond(socket, "400 Bad Request", "La solicitud no corresponde a este inicio de sesión.");
            } else {
                respond(socket, "200 OK", "Vuelve a Aula IAG para continuar.");
            }
        } catch (IOException exception) {
            // The browser may have navigated away after delivering the callback.
        }
        return result;
    }

    private String readHeaders(Socket socket) throws IOException {
        long requestDeadline = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS;
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int end = 0;
        while (bytes.size() < REQUEST_LIMIT && SystemClock.elapsedRealtime() < requestDeadline) {
            int next = input.read();
            if (next < 0) {
                break;
            }
            bytes.write(next);
            end = (end << 8) | next;
            if (end == 0x0d0a0d0a) {
                return bytes.toString(StandardCharsets.US_ASCII.name());
            }
        }
        throw new IOException("Incomplete callback request");
    }

    private Result parse(String headers, String expectedState) {
        String[] lines = headers.split("\\r\\n");
        String[] request = lines[0].split(" ", -1);
        if (request.length != 3 || !"GET".equals(request[0])
                || !(PATH.equals(request[1]) || request[1].startsWith(PATH + "?"))
                || !request[2].startsWith("HTTP/1.")) {
            return null;
        }

        String host = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.toLowerCase(Locale.ROOT).startsWith("host:")) {
                if (host != null) {
                    return null;
                }
                host = line.substring("host:".length()).trim();
            }
        }
        if (!(HOST + ":" + port).equals(host)) {
            return null;
        }

        Uri target = Uri.parse(request[1]);
        String state = unique(target, "state");
        if (state == null || !MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return new Result(unique(target, "code"), unique(target, "client_id"), unique(target, "error"));
    }

    private static String unique(Uri uri, String name) {
        List<String> values = uri.getQueryParameters(name);
        if (values.size() > 1) {
            throw new IllegalArgumentException("Repeated parameter");
        }
        return values.isEmpty() ? null : values.get(0);
    }

    private static void respond(Socket socket, String status, String message) throws IOException {
        byte[] content = ("<!doctype html><html lang=\"es\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Aula IAG</title><h1>Aula IAG</h1><p>" + message + "</p></html>")
                .getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + content.length + "\r\n"
                + "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n"
                + "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(content);
        output.flush();
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        Socket socket = connection;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    static final class Result {
        final String code;
        final String issuedClientId;
        final String error;

        Result(String code, String issuedClientId, String error) {
            this.code = code;
            this.issuedClientId = issuedClientId;
            this.error = error;
        }
    }
}
