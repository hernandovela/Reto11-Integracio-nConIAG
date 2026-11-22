package com.aulaiag.reto11;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class DeviceChecks extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", "responsesAndSignIn");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        sendStatus(1, status);
        Bundle result = new Bundle();
        try {
            checkRequest();
            checkModelCatalog();
            checkStream();
            OpenAiIdentityChecks.run();
            checkLoopback();
            result.putString("stream", "\nComprobaciones de Responses API, identidad y retorno OAuth correctas.\n");
            sendStatus(0, status);
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError error) {
            result.putString("stream", "\nFallo: " + error + "\n");
            status.putString("stack", android.util.Log.getStackTraceString(error));
            sendStatus(-2, status);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkRequest() throws Exception {
        List<ResponsesClient.Message> history = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            history.add(new ResponsesClient.Message("user", "Pregunta " + i));
            if (i < 7) {
                history.add(new ResponsesClient.Message("assistant", "Respuesta " + i));
            }
        }
        JSONObject request = new JSONObject(ResponsesClient.requestBody("modelo-de-prueba", history));
        JSONArray input = request.getJSONArray("input");
        require(request.getBoolean("stream"), "La solicitud debe activar streaming.");
        require(!request.getBoolean("store"), "La solicitud debe desactivar el almacenamiento.");
        require(input.length() == 11, "La solicitud debe conservar los últimos seis turnos.");
        require(input.getJSONObject(0).getString("content").equals("Pregunta 2"),
                "El contexto debe empezar con una pregunta.");
        require(input.getJSONObject(10).getString("content").equals("Pregunta 7"),
                "La pregunta actual debe aparecer al final.");
    }

    private void checkModelCatalog() throws IOException {
        String body = """
                {"models":[
                    {"visibility":"hide","slug":"privado"},
                    {"visibility":"list","slug":"disponible"}
                ]}
                """;
        require(ResponsesClient.visibleModel(body).equals("disponible"),
                "El catálogo debe ignorar modelos ocultos.");
    }

    private void checkStream() throws IOException {
        String deltas = """
                : comentario

                data: {"type":"response.output_text.delta","delta":"Hola"}

                data: {"type":"response.output_text.delta",
                data: "delta":" mundo"}

                """;
        String complete = deltas + "data: {\"type\":\"response.completed\"}";
        List<String> updates = new ArrayList<>();
        String reply = ResponsesClient.readStream(new StringReader(complete), updates::add, "req-test");
        require(reply.equals("Hola mundo") && updates.size() == 2,
                "El chat debe recibir deltas y terminar con el texto completo.");

        try {
            ResponsesClient.readStream(new StringReader(deltas), value -> { }, "req-test");
            throw new AssertionError("Un stream cortado debe fallar.");
        } catch (IOException expected) {
            require(expected.getMessage().contains("antes"), "Debe identificar el corte.");
        }

        String limit = deltas + """
                data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}

                """;
        try {
            ResponsesClient.readStream(new StringReader(limit), value -> { }, "req-test");
            throw new AssertionError("Un límite de uso durante streaming debe fallar.");
        } catch (ResponsesClient.ApiException expected) {
            require(expected.status == 429 && expected.requestId.equals("req-test"),
                    "Debe conservar la clasificación y el ID de la solicitud.");
        }
    }

    private void checkLoopback() throws IOException {
        ChatGptAuth auth = new ChatGptAuth(getTargetContext());
        ChatGptAuth.Attempt attempt = auth.beginSignIn(null, false);
        Uri request = attempt.authorizationUri();
        Uri callback = Uri.parse(request.getQueryParameter("redirect_uri"));
        require("127.0.0.1".equals(callback.getHost()), "El retorno OAuth debe ser local.");
        require("/auth/callback".equals(callback.getPath()), "La ruta del retorno debe ser estable.");
        require("S256".equals(request.getQueryParameter("code_challenge_method")), "Se requiere PKCE S256.");
        AtomicReference<Throwable> browserFailure = new AtomicReference<>();
        Thread browser = new Thread(() -> {
            try {
                require(callbackStatus(callback, "invalid").contains(" 400 "),
                        "Debe rechazar state incorrecto.");
                String state = request.getQueryParameter("state");
                require(callbackStatus(callback, state).contains(" 200 "),
                        "Debe aceptar el state de la solicitud.");
            } catch (Exception | AssertionError error) {
                browserFailure.set(error);
                auth.cancel(attempt);
            }
        });
        browser.start();
        try {
            auth.finishSignIn(attempt);
            throw new AssertionError("La cancelación de OpenAI no debe iniciar sesión.");
        } catch (ChatGptAuth.AuthException expected) {
            require("authorization_cancelled".equals(expected.code()), "Debe reconocer el rechazo de OpenAI.");
        } finally {
            auth.cancel(attempt);
            try {
                browser.join(6_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Prueba de retorno interrumpida.", error);
            }
            if (browserFailure.get() != null || browser.isAlive()) {
                throw new AssertionError("Fallo en el navegador simulado.", browserFailure.get());
            }
        }
    }

    private String callbackStatus(Uri callback, String state) throws IOException {
        try (Socket socket = new Socket(callback.getHost(), callback.getPort())) {
            socket.setSoTimeout(4_000);
            String request = "GET /auth/callback?state=" + state + "&error=access_denied HTTP/1.1\r\n"
                    + "Host: " + callback.getHost() + ":" + callback.getPort() + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
