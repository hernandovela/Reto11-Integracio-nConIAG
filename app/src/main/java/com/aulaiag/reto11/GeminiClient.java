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
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public final class GeminiClient {
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
            + "gemini-3.5-flash-lite:streamGenerateContent?alt=sse";
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    private static final int CONTEXT_TURNS = 6;
    private static final int CONTEXT_CHARACTERS = 24_000;
    private static final int RESPONSE_CHARACTERS = 48_000;
    private static final int EVENT_CHARACTERS = 1_000_000;
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

    public static boolean isProjectIdValid(String projectId) {
        return projectId != null && PROJECT_ID.matcher(projectId).matches();
    }

    public String reply(String accessToken, String projectId, List<Message> history,
                        Consumer<String> partial) throws IOException {
        if (history.isEmpty() || !history.get(history.size() - 1).role().equals("user")) {
            throw new IllegalArgumentException("La conversación debe terminar con una pregunta.");
        }
        if (history.get(history.size() - 1).text().length() > CONTEXT_CHARACTERS) {
            throw new IllegalArgumentException("La pregunta es demasiado larga.");
        }
        if (!isProjectIdValid(projectId)) {
            throw new IOException("Configura un ID válido de proyecto de Google Cloud.");
        }
        checkInterrupted();
        byte[] body = requestBody(history).getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("x-goog-user-project", projectId);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            requireSuccess(connection);
            try (InputStreamReader response = new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8)) {
                return readStream(response, partial);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String requestBody(List<Message> history) throws IOException {
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
            JSONArray contents = new JSONArray();
            for (int i = start; i < history.size(); i++) {
                Message message = history.get(i);
                String role = message.role().equals("assistant") ? "model" : "user";
                contents.put(new JSONObject().put("role", role).put("parts", parts(message.text())));
            }
            return new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts", parts(INSTRUCTIONS)))
                    .put("contents", contents)
                    .put("generationConfig", new JSONObject().put("maxOutputTokens", 2048))
                    .toString();
        } catch (JSONException error) {
            throw new IOException("No se pudo preparar el mensaje.", error);
        }
    }

    private static JSONArray parts(String text) throws JSONException {
        return new JSONArray().put(new JSONObject().put("text", text));
    }

    private static void requireSuccess(HttpsURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String code = "";
        try (InputStream response = connection.getErrorStream()) {
            if (response != null) {
                JSONObject payload = new JSONObject(readBody(response, 24_000));
                JSONObject error = payload.optJSONObject("error");
                if (error != null) {
                    code = errorCode(error);
                }
            }
        } catch (JSONException | IOException ignored) {
            // The HTTP status is sufficient when the gateway omits a structured error.
        }
        throw new ApiException(status, code);
    }

    static String readStream(Reader source, Consumer<String> partial) throws IOException {
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
                if (event.length() > EVENT_CHARACTERS) {
                    throw new IOException("Gemini envió un evento demasiado grande.");
                }
            } else if (line.isEmpty() && event.length() > 0) {
                if (handleEvent(event.toString(), answer, partial)) {
                    return finishedAnswer(answer);
                }
                event.setLength(0);
            }
        }
        if (event.length() > 0 && handleEvent(event.toString(), answer, partial)) {
            return finishedAnswer(answer);
        }
        throw new IOException("La conexión terminó antes de recibir la respuesta completa.");
    }

    private static boolean handleEvent(String data, StringBuilder answer, Consumer<String> partial)
            throws IOException {
        try {
            JSONObject event = new JSONObject(data);
            JSONObject error = event.optJSONObject("error");
            if (error != null) {
                throw new ApiException(error.optInt("code"), errorCode(error));
            }
            JSONObject feedback = event.optJSONObject("promptFeedback");
            if (feedback != null && !feedback.optString("blockReason").isEmpty()) {
                throw new IOException("Gemini no pudo procesar esa pregunta. Reformúlala e inténtalo de nuevo.");
            }
            JSONArray candidates = event.optJSONArray("candidates");
            JSONObject candidate = candidates == null ? null : candidates.optJSONObject(0);
            if (candidate == null) {
                return false;
            }
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            int before = answer.length();
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    if (!part.optBoolean("thought")) {
                        answer.append(part.optString("text"));
                    }
                }
            }
            if (answer.length() > RESPONSE_CHARACTERS) {
                throw new IOException("La respuesta superó el límite de texto de Aula IAG.");
            }
            if (answer.length() > before) {
                partial.accept(answer.toString());
            }
            return switch (candidate.optString("finishReason")) {
                case "", "FINISH_REASON_UNSPECIFIED" -> false;
                case "STOP" -> true;
                case "MAX_TOKENS" -> throw new IOException(
                        "Gemini alcanzó el límite de respuesta. Intenta una pregunta más breve.");
                default -> throw new IOException(
                        "Gemini no pudo completar esa respuesta. Reformula tu pregunta e inténtalo de nuevo.");
            };
        } catch (JSONException error) {
            throw new IOException("Gemini envió una respuesta de formato inesperado.", error);
        }
    }

    private static String finishedAnswer(StringBuilder answer) throws IOException {
        String text = answer.toString().trim();
        if (text.isEmpty()) {
            throw new IOException("Gemini completó la solicitud sin devolver texto.");
        }
        return text;
    }

    private static String readBody(InputStream input, int limit) throws IOException {
        InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            checkInterrupted();
            if (result.length() + count > limit) {
                throw new IOException("Gemini devolvió más datos de los esperados.");
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

    private static String errorCode(JSONObject error) {
        JSONArray details = error.optJSONArray("details");
        if (details != null) {
            for (int i = 0; i < details.length(); i++) {
                JSONObject detail = details.optJSONObject(i);
                if (detail != null && detail.optString("@type").equals(
                        "type.googleapis.com/google.rpc.ErrorInfo")) {
                    return detail.optString("reason", error.optString("status"));
                }
            }
        }
        return error.optString("status");
    }

    public static final class ApiException extends IOException {
        public final int status;
        public final String code;

        ApiException(int status, String code) {
            super(messageFor(status, code));
            this.status = status;
            this.code = code;
        }

        private static String messageFor(int status, String code) {
            switch (code) {
                case "SERVICE_DISABLED":
                    return "Habilita la API de Gemini en el proyecto configurado.";
                case "USER_PROJECT_DENIED", "CONSUMER_INVALID":
                    return "La cuenta no puede usar ese proyecto de Google Cloud. Revisa el ID y sus permisos.";
                case "BILLING_DISABLED":
                    return "Google requiere facturación habilitada para este proyecto o modelo.";
                case "ACCESS_TOKEN_SCOPE_INSUFFICIENT":
                    return "Google no concedió el permiso de Cloud. Vuelve a conectar tu cuenta.";
                default:
                    return switch (status) {
                        case 401 -> "Google no aceptó la sesión. Vuelve a conectar tu cuenta UNAL.";
                        case 403 -> "Google no permite usar Gemini con esta cuenta y proyecto.";
                        case 404 -> "El modelo de Gemini no está disponible en este proyecto.";
                        case 429 -> "El proyecto alcanzó el límite de uso de Gemini. Intenta más tarde.";
                        case 500, 502, 503, 504 -> "Gemini no está disponible. Intenta más tarde.";
                        default -> "Gemini no pudo completar la respuesta. Intenta de nuevo.";
                    };
            }
        }
    }
}
