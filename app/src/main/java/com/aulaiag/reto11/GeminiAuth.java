package com.aulaiag.reto11;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

/** Android Google authorization for an institutional Gemini API session. Use from the UI thread. */
public final class GeminiAuth implements AutoCloseable {
    public static final String DOMAIN = "unal.edu.co";

    private static final String OPENID = "openid";
    private static final String EMAIL = "https://www.googleapis.com/auth/userinfo.email";
    private static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";
    private static final String GEMINI_RETRIEVER = "https://www.googleapis.com/auth/generative-language.retriever";
    private static final String USER_INFO = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final List<Scope> SCOPES = Collections.unmodifiableList(Arrays.asList(
            new Scope(OPENID), new Scope(EMAIL), new Scope(CLOUD_PLATFORM), new Scope(GEMINI_RETRIEVER)));
    private static final int PROFILE_LIMIT = 32_768;

    private final Activity activity;
    private final AuthorizationClient client;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean closed;
    private int operation;
    private PendingFlow pending;

    public GeminiAuth(Activity activity) {
        this.activity = activity;
        client = Identity.getAuthorizationClient(activity);
    }

    public void signIn(Listener listener) {
        if (!closed) {
            cancelPending();
            authorize(null, listener, operation);
        }
    }

    public void finishSignIn(Intent data, Listener listener) {
        if (closed) {
            return;
        }
        if (pending == null) {
            listener.onError("Se canceló la conexión con Google.");
            return;
        }
        finishSignIn(data, pending.previous, listener);
    }

    /** Completes a result from the app's pending Google resolution after Activity recreation. */
    public void finishSignIn(Intent data, Session previous, Listener listener) {
        if (closed) {
            return;
        }
        PendingFlow flow = pending;
        pending = null;
        if (data == null) {
            cancelPending();
            listener.onError("Se canceló la conexión con Google.");
            return;
        }

        try {
            AuthorizationResult result = client.getAuthorizationResultFromIntent(data);
            if (flow == null) {
                accept(result, previous, listener, operation);
            } else {
                accept(result, flow.previous, listener, flow.operation);
            }
        } catch (ApiException exception) {
            listener.onError(authMessage(exception));
        }
    }

    public void refresh(Session session, Listener listener) {
        if (closed) {
            return;
        }
        if (session == null) {
            signIn(listener);
            return;
        }
        cancelPending();
        int current = operation;
        ClearTokenRequest request = ClearTokenRequest.builder().setToken(session.accessToken()).build();
        client.clearToken(request)
                .addOnSuccessListener(unused -> {
                    if (isCurrent(current)) {
                        authorize(session, listener, current);
                    }
                })
                .addOnFailureListener(error -> {
                    if (isCurrent(current)) {
                        listener.onError(authMessage(error));
                    }
                });
    }

    public void signOut(Session session, Consumer<Boolean> completion) {
        if (closed) {
            return;
        }
        cancelPending();
        if (session == null) {
            completion.accept(true);
            return;
        }

        int current = operation;
        RevokeAccessRequest request = RevokeAccessRequest.builder()
                .setAccount(androidAccount(session))
                .setScopes(SCOPES)
                .build();
        client.revokeAccess(request).addOnCompleteListener(task -> {
            if (isCurrent(current)) {
                completion.accept(task.isSuccessful());
            }
        });
    }

    public void cancelPending() {
        operation++;
        pending = null;
    }

    @Override
    public void close() {
        closed = true;
        cancelPending();
        network.shutdownNow();
    }

    private void authorize(Session previous, Listener listener, int current) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(activity)
                != ConnectionResult.SUCCESS) {
            listener.onError("Actualiza Servicios de Google Play o usa un dispositivo que los incluya.");
            return;
        }

        AuthorizationRequest.Builder request = AuthorizationRequest.builder()
                .filterByHostedDomain(DOMAIN)
                .setRequestedScopes(SCOPES)
                .setOptOutIncludingGrantedScopes(true);
        if (previous == null) {
            request.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT);
        } else {
            request.setAccount(androidAccount(previous));
        }

        client.authorize(request.build())
                .addOnSuccessListener(result -> {
                    if (isCurrent(current)) {
                        accept(result, previous, listener, current);
                    }
                })
                .addOnFailureListener(error -> {
                    if (isCurrent(current)) {
                        listener.onError(authMessage(error));
                    }
                });
    }

    private void accept(AuthorizationResult result, Session previous, Listener listener, int current) {
        if (result.hasResolution()) {
            PendingIntent resolution = result.getPendingIntent();
            if (resolution == null) {
                listener.onError("Google no pudo abrir la autorización.");
                return;
            }
            pending = new PendingFlow(previous, current);
            listener.onResolution(resolution);
            return;
        }
        List<String> granted = result.getGrantedScopes();
        String token = result.getAccessToken();
        if (granted == null || !granted.contains(CLOUD_PLATFORM) || !granted.contains(GEMINI_RETRIEVER)
                || !(granted.contains(EMAIL) || granted.contains("email"))
                || token == null || token.isEmpty()) {
            listener.onError("Autoriza tu correo institucional, Google Cloud y Gemini para continuar.");
            return;
        }

        network.execute(() -> {
            try {
                Session session = verifyProfile(loadProfile(token), token, previous);
                main.post(() -> {
                    if (isCurrent(current)) {
                        listener.onSuccess(session);
                    }
                });
            } catch (IOException exception) {
                main.post(() -> {
                    if (isCurrent(current)) {
                        listener.onError(exception.getMessage());
                    }
                });
            }
        });
    }

    private static JSONObject loadProfile(String token) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(USER_INFO).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Google no pudo confirmar tu cuenta. Vuelve a conectarla.");
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (output.size() + size > PROFILE_LIMIT) {
                        throw new IOException("Google devolvió un perfil de tamaño inesperado.");
                    }
                    output.write(buffer, 0, size);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            } catch (JSONException exception) {
                throw new IOException("Google devolvió un perfil no válido.");
            }
        } finally {
            connection.disconnect();
        }
    }

    static Session verifyProfile(JSONObject profile, String token, Session previous) throws IOException {
        Object emailClaim = profile.opt("email");
        Object subjectClaim = profile.opt("sub");
        Object domainClaim = profile.opt("hd");
        if (!(emailClaim instanceof String) || ((String) emailClaim).trim().isEmpty()
                || !(subjectClaim instanceof String) || ((String) subjectClaim).trim().isEmpty()
                || !(domainClaim instanceof String) || !DOMAIN.equalsIgnoreCase((String) domainClaim)
                || !Boolean.TRUE.equals(profile.opt("email_verified"))) {
            throw new IOException("Elige una cuenta de Google Workspace verificada de la UNAL.");
        }

        String subject = (String) subjectClaim;
        if (previous != null && !previous.subject().equals(subject)) {
            throw new IOException("Selecciona la misma cuenta institucional para renovar la autorización.");
        }
        return new Session((String) emailClaim, subject, token);
    }

    private boolean isCurrent(int current) {
        return !closed && current == operation;
    }

    private static Account androidAccount(Session session) {
        return new Account(session.email(), "com.google");
    }

    private static String authMessage(Exception error) {
        if (error instanceof ApiException) {
            int status = ((ApiException) error).getStatusCode();
            switch (status) {
                case CommonStatusCodes.DEVELOPER_ERROR:
                    return "Google rechazó la configuración OAuth. Revisa el registro Android y su SHA-1.";
                case CommonStatusCodes.CANCELED:
                    return "Se canceló la conexión con Google.";
                case CommonStatusCodes.SIGN_IN_REQUIRED:
                    return "Agrega tu cuenta de la UNAL al dispositivo y vuelve a conectarla.";
                case CommonStatusCodes.NETWORK_ERROR:
                    return "No se pudo conectar con Google. Revisa tu conexión e inténtalo de nuevo.";
                default:
                    break;
            }
        }
        return "Google no autorizó la conexión. Revisa el consentimiento y la política de tu cuenta UNAL.";
    }

    public interface Listener {
        void onResolution(PendingIntent resolution);
        void onSuccess(Session session);
        void onError(String message);
    }

    public static final class Session {
        private final String email;
        private final String subject;
        private final String accessToken;

        private Session(String email, String subject, String accessToken) {
            this.email = email;
            this.subject = subject;
            this.accessToken = accessToken;
        }

        public String email() { return email; }
        public String subject() { return subject; }
        public String accessToken() { return accessToken; }
    }

    private static final class PendingFlow {
        private final Session previous;
        private final int operation;

        private PendingFlow(Session previous, int operation) {
            this.previous = previous;
            this.operation = operation;
        }
    }
}
