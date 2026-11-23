package com.aulaiag.reto11;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

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
        status.putString("test", "geminiAndUnalAccount");
        status.putInt("numtests", 1);
        status.putInt("current", 1);
        sendStatus(1, status);
        Bundle result = new Bundle();
        try {
            checkRequest();
            checkStream();
            checkProfile();
            result.putString("stream", "\nComprobaciones de Gemini y cuenta UNAL correctas.\n");
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
        List<GeminiClient.Message> history = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            history.add(new GeminiClient.Message("user", "Pregunta " + i));
            if (i < 7) {
                history.add(new GeminiClient.Message("assistant", "Respuesta " + i));
            }
        }
        JSONObject request = new JSONObject(GeminiClient.requestBody(history));
        JSONArray contents = request.getJSONArray("contents");
        require(request.has("systemInstruction"), "Gemini espera systemInstruction.");
        require(contents.length() == 11, "Deben enviarse los seis turnos más recientes.");
        require(contents.getJSONObject(1).getString("role").equals("model"),
                "Gemini espera el rol model en las respuestas previas.");
        JSONObject latest = contents.getJSONObject(10);
        require(latest.getString("role").equals("user"), "La pregunta cierra el contexto.");
        require(latest.getJSONArray("parts").getJSONObject(0).getString("text").equals("Pregunta 7"),
                "Se debe enviar la pregunta más reciente.");
        require(GeminiClient.isProjectIdValid("aula-unal-123456"), "Reconoce un ID de proyecto válido.");
        require(!GeminiClient.isProjectIdValid("otro\r\nHeader: bad"), "Rechaza un ID de proyecto inválido.");
    }

    private void checkStream() throws IOException {
        String chunks = """
                : comentario

                data: {"candidates":[{"content":{"parts":[{"text":"interno","thought":true},{"text":"Hola"}]}}]}

                data: {"candidates":[{"content":{"parts":[
                data: {"text":" mundo"}]}}]}

                """;
        String stop = "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}";
        List<String> updates = new ArrayList<>();
        String reply = GeminiClient.readStream(new StringReader(chunks + stop), updates::add);
        require(reply.equals("Hola mundo") && updates.size() == 2,
                "El chat debe recibir texto progresivo y omitir partes internas.");

        try {
            GeminiClient.readStream(new StringReader(chunks), value -> { });
            throw new AssertionError("Un stream cortado debe fallar.");
        } catch (IOException expected) {
            require(expected.getMessage().contains("antes"), "Debe identificar el corte.");
        }

        String limit = chunks + """
                data: {"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}

                """;
        try {
            GeminiClient.readStream(new StringReader(limit), value -> { });
            throw new AssertionError("Un límite de uso durante streaming debe fallar.");
        } catch (GeminiClient.ApiException expected) {
            require(expected.status == 429 && expected.code.equals("RESOURCE_EXHAUSTED"),
                    "Debe conservar el estado de cuota de Gemini.");
        }
    }

    private void checkProfile() throws Exception {
        JSONObject profile = new JSONObject()
                .put("sub", "fixture-UNAL")
                .put("email", "ejemplo@unal.edu.co")
                .put("hd", "unal.edu.co")
                .put("email_verified", true);
        GeminiAuth.Session account = GeminiAuth.verifyProfile(profile, "fixture-token", null);
        require(account.email().equals("ejemplo@unal.edu.co"), "Reconoce la cuenta institucional.");

        profile.put("sub", "fixture-otra-persona");
        rejectProfile(profile, account, "La renovación debe pertenecer a la misma cuenta.");
        profile.put("hd", "otro.example");
        rejectProfile(profile, null, "El sufijo del correo por sí solo no acredita la cuenta UNAL.");
        profile.put("hd", "unal.edu.co").put("email_verified", false);
        rejectProfile(profile, null, "Se requiere un correo verificado por Google.");
    }

    private void rejectProfile(JSONObject profile, GeminiAuth.Session previous, String message)
            throws Exception {
        try {
            GeminiAuth.verifyProfile(profile, "fixture-token", previous);
            throw new AssertionError(message);
        } catch (IOException expected) {
            require(expected.getMessage() != null, "Debe identificar el error de identidad.");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
