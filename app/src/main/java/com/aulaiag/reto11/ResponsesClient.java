package com.aulaiag.reto11;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

public final class ResponsesClient {
    private static final String API = "https://api.openai.com/v1";
    private static final int CONTEXT_TURNS = 6;
    private static final int CONTEXT_CHARACTERS = 24_000;
    private static final int RESPONSE_CHARACTERS = 48_000;
    private static final String INSTRUCTIONS =
            "Eres Aula IAG, un asistente de estudio. Responde en español claro. "
            + "Explica conceptos paso a paso, resume los textos que recibas y crea ejercicios "
            + "cuando te lo pidan. Usa ejemplos concretos y respuestas de extensión moderada. "
            + "Para practicar, ofrece primero una oportunidad de responder. "
            + "Si un dato es incierto, dilo. Evita inventar citas o fuentes.";

    public record Message(String role, String text) {
        public Message {
            if ((!role.equals("user") && !role.equals("assistant")) || text.trim().isEmpty()) {
                throw new IllegalArgumentException("El mensaje requiere un rol y contenido válidos.");
            }
        }
    }

    public String reply(String accessToken, List<Message> history, Consumer<String> partial)
            throws IOException {
        if (history.isEmpty() || !history.get(history.size() - 1).role().equals("user")) {
            throw new IllegalArgumentException("La conversación debe terminar con una pregunta.");
        }
        if (history.get(history.size() - 1).text().length() > CONTEXT_CHARACTERS) {
            throw new IllegalArgumentException("La pregunta es demasiado larga.");
        }
        checkInterrupted();
        String model = selectModel(accessToken);
        byte[] body = requestBody(model, history).getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection = open("/responses", accessToken);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            requireSuccess(connection);
            try (InputStreamReader response = new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8)) {
                return readStream(response, partial, connection.getHeaderField("x-request-id"));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String requestBody(String model, List<Message> history) throws IOException {
        int start = history.size() - 1;
        int remaining = CONTEXT_CHARACTERS - history.get(start).text().length();
        for (int i = start - 2; i >= 0 && (history.size() - i) / 2 < CONTEXT_TURNS; i -= 2) {
            Message question = history.get(i);
            Message answer = history.get(i + 1);
            int length = question.text().length() + answer.text().length();
            if (!question.role().equals("user") || !answer.role().equals("assistant")
                    || length > remaining) {
                break;
            }
            remaining -= length;
            start = i;
        }

        try {
            JSONArray input = new JSONArray();
            for (int i = start; i < history.size(); i++) {
                Message message = history.get(i);
                input.put(new JSONObject().put("role", message.role()).put("content", message.text()));
            }
            return new JSONObject()
                    .put("model", model)
                    .put("instructions", INSTRUCTIONS)
                    .put("input", input)
                    .put("store", false)
                    .put("stream", true)
                    .toString();
        } catch (JSONException error) {
            throw new IOException("No se pudo preparar el mensaje.", error);
        }
    }

    private String selectModel(String accessToken) throws IOException {
        HttpsURLConnection connection = open("/models", accessToken);
        try {
            connection.setRequestProperty("Accept", "application/json");
            requireSuccess(connection);
            try (InputStream response = connection.getInputStream()) {
                return visibleModel(readBody(response, 2_000_000));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String visibleModel(String body) throws IOException {
        try {
            JSONArray models = new JSONObject(body).getJSONArray("models");
            for (int i = 0; i < models.length(); i++) {
                JSONObject entry = models.getJSONObject(i);
                String slug = entry.optString("slug").trim();
                if (entry.optString("visibility").equals("list") && !slug.isEmpty()) {
                    return slug;
                }
            }
        } catch (JSONException error) {
            throw new IOException("OpenAI devolvió un catálogo de modelos inesperado.", error);
        }
        throw new IOException("Tu cuenta no tiene un modelo disponible para Aula IAG.");
    }

    private HttpsURLConnection open(String path, String accessToken) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(API + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(45_000);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        return connection;
    }

    private static void requireSuccess(HttpsURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String code = "";
        try (InputStream response = connection.getErrorStream()) {
            if (response != null) {
                code = errorCode(new JSONObject(readBody(response, 24_000)));
            }
        } catch (JSONException | IOException ignored) {
            // The HTTP status is sufficient when the gateway omits a structured error.
        }
        throw new ApiException(status, code, connection.getHeaderField("x-request-id"));
    }

    static String readStream(Reader source, Consumer<String> partial, String requestId)
            throws IOException {
        BufferedReader reader = new BufferedReader(source);
        StringBuilder event = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            checkInterrupted();
            if (line.startsWith("data:")) {
                if (event.length() > 0) {
                    event.append('\n');
                }
                String data = line.substring(5);
                event.append(data.startsWith(" ") ? data.substring(1) : data);
                if (event.length() > 1_000_000) {
                    throw new IOException("OpenAI envió un evento demasiado grande.");
                }
            } else if (line.isEmpty() && event.length() > 0) {
                String completed = handleEvent(event.toString(), answer, partial, requestId);
                if (completed != null) {
                    return completed;
                }
                event.setLength(0);
            }
        }
        if (event.length() > 0) {
            String completed = handleEvent(event.toString(), answer, partial, requestId);
            if (completed != null) {
                return completed;
            }
        }
        throw new IOException("La conexión terminó antes de recibir la respuesta completa.");
    }

    private static String handleEvent(String data, StringBuilder answer, Consumer<String> partial,
                                      String requestId) throws IOException {
        if (data.equals("[DONE]")) {
            return null;
        }
        try {
            JSONObject event = new JSONObject(data);
            switch (event.optString("type")) {
                case "response.output_text.delta", "response.refusal.delta" -> {
                    answer.append(event.getString("delta"));
                    if (answer.length() > RESPONSE_CHARACTERS) {
                        throw new IOException("La respuesta superó el límite de texto de Aula IAG.");
                    }
                    partial.accept(answer.toString());
                }
                case "response.completed" -> {
                    String complete = answer.toString().trim();
                    if (complete.isEmpty()) {
                        complete = extractCompletedText(event.optJSONObject("response"));
                    }
                    if (complete.isEmpty()) {
                        throw new IOException("OpenAI completó la solicitud sin devolver texto.");
                    }
                    if (complete.length() > RESPONSE_CHARACTERS) {
                        throw new IOException("La respuesta superó el límite de texto de Aula IAG.");
                    }
                    return complete;
                }
                case "error", "response.failed" -> {
                    JSONObject response = event.optJSONObject("response");
                    String code = errorCode(response == null ? event : response);
                    throw new ApiException(statusFor(code), code, requestId);
                }
                case "response.incomplete" -> throw new IOException(
                        "OpenAI dejó la respuesta incompleta. Intenta una pregunta más breve.");
                default -> { }
            }
            return null;
        } catch (JSONException error) {
            throw new IOException("OpenAI envió una respuesta de formato inesperado.", error);
        }
    }

    private static String extractCompletedText(JSONObject response) throws JSONException {
        if (response == null || response.optJSONArray("output") == null) {
            return "";
        }
        JSONArray output = response.getJSONArray("output");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONArray content = output.getJSONObject(i).optJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.getJSONObject(j);
                switch (part.optString("type")) {
                    case "output_text" -> text.append(part.optString("text"));
                    case "refusal" -> text.append(part.optString("refusal"));
                    default -> { }
                }
            }
        }
        return text.toString().trim();
    }

    private static String readBody(InputStream input, int limit) throws IOException {
        InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            checkInterrupted();
            if (result.length() + count > limit) {
                throw new IOException("OpenAI devolvió más datos de los esperados.");
            }
            result.append(buffer, 0, count);
        }
        return result.toString();
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Solicitud cancelada.");
        }
    }

    private static String errorCode(JSONObject object) {
        JSONObject error = object.optJSONObject("error");
        return (error == null ? object : error).optString("code");
    }

    private static int statusFor(String code) {
        return switch (code) {
            case "subscription_sharing_usage_limit_exceeded", "rate_limit_exceeded" -> 429;
            case "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported",
                 "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> 403;
            case "subscription_sharing_invalid_user" -> 401;
            case "subscription_sharing_usage_unavailable",
                 "subscription_sharing_user_unavailable", "server_error" -> 503;
            default -> 0;
        };
    }

    public static final class ApiException extends IOException {
        public final int status;
        public final String code;
        public final String requestId;

        ApiException(int status, String code, String requestId) {
            super(messageFor(status, code));
            this.status = status;
            this.code = code;
            this.requestId = requestId;
        }

        private static String messageFor(int status, String code) {
            if (code.equals("subscription_sharing_unsupported_capability")) {
                return "OpenAI no admite esta solicitud con el modelo o plan actual.";
            }
            return switch (status) {
                case 401 -> "OpenAI no aceptó la sesión. Revisa tu acceso en Cuenta.";
                case 403 -> "El plan, la cuenta o la región no permiten usar ChatGPT en esta app.";
                case 429 -> "Alcanzaste un límite de uso. Puedes revisarlo en Cuenta.";
                case 500, 502, 503, 504 -> "El servicio de OpenAI no está disponible. Intenta más tarde.";
                default -> "OpenAI no pudo completar la respuesta. Intenta de nuevo.";
            };
        }
    }
}
