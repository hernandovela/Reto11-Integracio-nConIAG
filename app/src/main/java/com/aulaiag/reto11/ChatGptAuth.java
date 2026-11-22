package com.aulaiag.reto11;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Sign in with ChatGPT's public-client flow for open-source apps. Call storage and I/O methods off the UI thread. */
public final class ChatGptAuth {
    private static final String ISSUER = OpenAiIdentity.ISSUER;
    private static final String AUTHORIZATION = ISSUER + "/api/accounts/authorize";
    private static final String TOKEN = ISSUER + "/api/accounts/oauth/token";
    private static final String DISCOVERY = ISSUER + "/.well-known/openid-configuration";
    private static final String JWKS = ISSUER + "/.well-known/jwks.json";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    private static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";
    private static final String SCOPES = "openid profile email offline_access resource.invoke " + PLAN_SCOPE;
    private static final long REFRESH_MARGIN_MS = 60_000;
    private static final int RESPONSE_LIMIT = 262_144;
    private static final Object SESSION_LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecureSessionStore store;
    private final AtomicReference<Attempt> pending = new AtomicReference<>();
    private final AtomicReference<String> retryRegistration = new AtomicReference<>();

    public ChatGptAuth(Context context) {
        store = new SecureSessionStore(context.getApplicationContext());
    }

    public Attempt beginSignIn(String clientId, boolean requestConsent) throws IOException {
        final String host;
        final JSONObject saved;
        synchronized (SESSION_LOCK) {
            JSONObject state = store.read();
            host = hostId(state);
            saved = find(state, clientId);
            if (clientId != null && saved == null) {
                throw new AuthException("unknown_account", "Selecciona una cuenta de ChatGPT.");
            }
        }

        String issuedClientId = clientId == null ? retryRegistration.getAndSet(null) : clientId;
        String requestedClient = issuedClientId == null ? DYNAMIC_CLIENT : issuedClientId;
        LoopbackLogin listener = new LoopbackLogin();
        try {
            String state = randomValue();
            String nonce = randomValue();
            String verifier = randomValue();
            Uri.Builder url = Uri.parse(AUTHORIZATION).buildUpon()
                    .appendQueryParameter("client_id", requestedClient)
                    .appendQueryParameter("ext_agent_host_id", host)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", listener.redirectUri())
                    .appendQueryParameter("scope", SCOPES)
                    .appendQueryParameter("resource", RESOURCE)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge(verifier));
            if (DYNAMIC_CLIENT.equals(requestedClient)) {
                url.appendQueryParameter("agent_name_hint", "Aula IAG");
            }
            if (saved != null) {
                String idToken = saved.optString("id_token");
                if (!idToken.isEmpty()) {
                    url.appendQueryParameter("id_token_hint", idToken);
                }
                String email = saved.optString("email");
                if (!email.isEmpty()) {
                    url.appendQueryParameter("login_hint", email);
                }
            }
            if (requestConsent) {
                url.appendQueryParameter("prompt", "consent");
            }
            Attempt attempt = new Attempt(listener, url.build(), requestedClient, state, nonce, verifier);
            Attempt old = pending.getAndSet(attempt);
            if (old != null) {
                old.cancel();
            }
            return attempt;
        } catch (RuntimeException exception) {
            listener.close();
            throw exception;
        }
    }

    public Account finishSignIn(Attempt attempt) throws IOException {
        if (attempt == null || pending.get() != attempt || !attempt.started.compareAndSet(false, true)) {
            throw cancelled();
        }
        try {
            LoopbackLogin.Result result = attempt.listener.await(attempt.state);
            attempt.listener.close();
            requirePending(attempt);
            if ("access_denied".equals(result.error)) {
                throw new AuthException("authorization_cancelled",
                        "No se concedió permiso para conectar la cuenta.");
            }
            if (result.error != null || result.code == null || result.code.isEmpty()) {
                throw new AuthException("authorization_failed",
                        "No se pudo completar el inicio de sesión en ChatGPT.");
            }

            String clientId = validateClientId(attempt.requestedClient, result.issuedClientId);
            JSONObject tokens;
            try {
                tokens = post(TOKEN, form("grant_type", "authorization_code", "client_id", clientId,
                        "code", result.code, "code_verifier", attempt.verifier,
                        "redirect_uri", attempt.listener.redirectUri(), "resource", RESOURCE));
            } catch (AuthException exception) {
                if ("invalid_grant".equals(exception.code()) && DYNAMIC_CLIENT.equals(attempt.requestedClient)
                        && pending.get() == attempt) {
                    retryRegistration.set(clientId);
                }
                throw exception;
            }
            String idToken = tokens.optString("id_token");
            JSONObject identity = OpenAiIdentity.verify(idToken, get(JWKS), clientId, attempt.nonce);

            synchronized (SESSION_LOCK) {
                requirePending(attempt);
                JSONObject state = store.read();
                JSONObject record = find(state, clientId);
                if (record != null && !record.optString("subject").equals(identity.optString("sub"))) {
                    throw new AuthException("account_mismatch",
                            "La cuenta elegida en el navegador no coincide con el registro guardado.");
                }
                if (record == null) {
                    record = new JSONObject();
                    JSONArray accounts = state.optJSONArray("accounts");
                    if (accounts == null) {
                        accounts = new JSONArray();
                        put(state, "accounts", accounts);
                    }
                    put(record, "number", accounts.length() + 1);
                    accounts.put(record);
                }
                put(record, "client_id", clientId);
                put(record, "subject", identity.optString("sub"));
                put(record, "email", identity.optString("email"));
                put(record, "id_token", idToken);
                replaceTokens(record, tokens, null, false);
                put(state, "active", clientId);
                if (!pending.compareAndSet(attempt, null)) {
                    throw cancelled();
                }
                store.write(state);
                return account(record);
            }
        } catch (IOException exception) {
            if (attempt.cancelled.get()) {
                throw cancelled();
            }
            throw exception;
        } finally {
            attempt.listener.close();
            pending.compareAndSet(attempt, null);
        }
    }

    public void cancel(Attempt attempt) {
        if (attempt != null && pending.compareAndSet(attempt, null)) {
            attempt.cancel();
        }
    }

    public List<Account> listAccounts() throws IOException {
        synchronized (SESSION_LOCK) {
            JSONArray records = store.read().optJSONArray("accounts");
            if (records == null) {
                return Collections.emptyList();
            }
            List<Account> accounts = new ArrayList<>(records.length());
            for (int index = 0; index < records.length(); index++) {
                JSONObject record = records.optJSONObject(index);
                if (record != null) {
                    accounts.add(account(record));
                }
            }
            return Collections.unmodifiableList(accounts);
        }
    }

    public Account activeAccount() throws IOException {
        synchronized (SESSION_LOCK) {
            JSONObject state = store.read();
            JSONObject record = active(state);
            return record == null ? null : account(record);
        }
    }

    public Account selectAccount(String clientId) throws IOException {
        synchronized (SESSION_LOCK) {
            JSONObject state = store.read();
            JSONObject record = find(state, clientId);
            if (record == null) {
                throw new AuthException("unknown_account", "Selecciona una cuenta de ChatGPT.");
            }
            Account account = account(record);
            if (account.signedIn()) {
                put(state, "active", clientId);
                store.write(state);
            }
            return account;
        }
    }

    public void acknowledgePlanConfirmation() throws IOException {
        synchronized (SESSION_LOCK) {
            JSONObject state = store.read();
            JSONObject record = active(state);
            if (record != null && account(record).planEnabled()) {
                put(record, "confirmed", true);
                store.write(state);
            }
        }
    }

    public String accessToken() throws IOException {
        synchronized (SESSION_LOCK) {
            JSONObject state = store.read();
            JSONObject record = active(state);
            if (record == null || !account(record).signedIn()) {
                throw signInRequired();
            }
            if (!hasScope(record.optString("scope"), PLAN_SCOPE)) {
                throw new AuthException("plan_not_enabled",
                        "Autoriza el uso de tu plan de ChatGPT para enviar mensajes.");
            }

            long now = System.currentTimeMillis();
            String accessToken = record.optString("access_token");
            long expires = record.optLong("expires_at");
            if (!accessToken.isEmpty() && expires > now
                    && (expires - now > REFRESH_MARGIN_MS || record.optLong("earliest_refresh_at") > now)) {
                return accessToken;
            }
            String refresh = record.optString("refresh_token");
            if (refresh.isEmpty()) {
                if (!accessToken.isEmpty() && expires > now) {
                    return accessToken;
                }
                clearTokens(record);
                store.write(state);
                throw signInRequired();
            }
            try {
                JSONObject tokens = post(TOKEN, form("grant_type", "refresh_token",
                        "client_id", record.optString("client_id"), "refresh_token", refresh, "resource", RESOURCE));
                replaceTokens(record, tokens, record.optString("scope"), true);
                store.write(state);
                if (!hasScope(record.optString("scope"), PLAN_SCOPE)) {
                    throw new AuthException("plan_not_enabled",
                            "Vuelve a autorizar el uso del plan de ChatGPT.");
                }
                return record.optString("access_token");
            } catch (AuthException exception) {
                if (invalidRefresh(exception.code())) {
                    clearTokens(record);
                    store.write(state);
                    throw signInRequired();
                }
                throw exception;
            }
        }
    }

    public boolean signOut() throws IOException {
        synchronized (SESSION_LOCK) {
            Attempt attempt = pending.getAndSet(null);
            if (attempt != null) {
                attempt.cancel();
            }
            JSONObject state = store.read();
            JSONObject record = active(state);
            if (record == null) {
                return true;
            }
            String refresh = record.optString("refresh_token");
            boolean revoked = refresh.isEmpty() || revoke(refresh, record.optString("client_id"));
            clearTokens(record);
            store.write(state);
            return revoked;
        }
    }

    public void invalidateActive() throws IOException {
        synchronized (SESSION_LOCK) {
            JSONObject state = store.read();
            JSONObject record = active(state);
            if (record != null) {
                clearTokens(record);
                store.write(state);
            }
        }
    }

    private String hostId(JSONObject state) throws IOException {
        String host = state.optString("host_id");
        if (host.isEmpty()) {
            host = "urn:uuid:" + UUID.randomUUID();
            put(state, "host_id", host);
            store.write(state);
        }
        return host;
    }

    private static JSONObject active(JSONObject state) {
        return find(state, state.optString("active"));
    }

    private static JSONObject find(JSONObject state, String clientId) {
        JSONArray records = state.optJSONArray("accounts");
        if (clientId == null || clientId.isEmpty() || records == null) {
            return null;
        }
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.optJSONObject(index);
            if (record != null && clientId.equals(record.optString("client_id"))) {
                return record;
            }
        }
        return null;
    }

    private static Account account(JSONObject record) {
        String email = record.optString("email");
        String suffix = "Cuenta " + record.optInt("number", 1);
        String label = email.isEmpty() ? suffix : email + " · " + suffix;
        boolean signedIn = !record.optString("id_token").isEmpty();
        boolean planEnabled = signedIn && hasScope(record.optString("scope"), PLAN_SCOPE);
        return new Account(record.optString("client_id"), label, email, signedIn,
                planEnabled, planEnabled && !record.optBoolean("confirmed"));
    }

    private static void replaceTokens(JSONObject record, JSONObject tokens, String retainedScope,
                                      boolean refreshing) throws IOException {
        String scope = tokens.optString("scope", retainedScope == null ? "" : retainedScope);
        String access = tokens.optString("access_token");
        String refresh = tokens.optString("refresh_token");
        long lifetime = tokens.optLong("expires_in");
        if ((hasScope(scope, PLAN_SCOPE) || refreshing)
                && (access.isEmpty() || lifetime <= 0 || lifetime > 86_400
                || !"bearer".equalsIgnoreCase(tokens.optString("token_type")))) {
            throw new AuthException("invalid_token_response", "ChatGPT no entregó una sesión de uso válida.");
        }
        if (refreshing && refresh.isEmpty()) {
            throw new AuthException("invalid_token_response",
                    "ChatGPT no entregó la credencial de renovación.");
        }
        put(record, "access_token", access);
        put(record, "refresh_token", refresh);
        put(record, "scope", scope);
        put(record, "expires_at", System.currentTimeMillis() + lifetime * 1000);
        long earliestRefresh = tokens.optLong("earliest_refresh_at");
        if (earliestRefresh > 0 && earliestRefresh < 10_000_000_000L) {
            earliestRefresh *= 1000;
        }
        put(record, "earliest_refresh_at", earliestRefresh);
    }

    private static void clearTokens(JSONObject record) {
        record.remove("id_token");
        record.remove("access_token");
        record.remove("refresh_token");
        record.remove("scope");
        record.remove("expires_at");
        record.remove("earliest_refresh_at");
    }

    private static boolean hasScope(String scopes, String requested) {
        for (String grant : scopes.split("\\s+")) {
            if (requested.equals(grant)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invalidRefresh(String code) {
        return switch (code) {
            case "invalid_grant", "invalid_refresh_token", "token_expired",
                    "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused" -> true;
            default -> false;
        };
    }

    private static String validateClientId(String requested, String received) throws IOException {
        if (DYNAMIC_CLIENT.equals(requested)) {
            if (received != null && !DYNAMIC_CLIENT.equals(received)
                    && received.matches("[A-Za-z0-9._~-]{1,200}")) {
                return received;
            }
        } else if (received == null || requested.equals(received)) {
            return requested;
        }
        throw new AuthException("invalid_client",
                "ChatGPT no devolvió el identificador esperado para esta conexión.");
    }

    private void requirePending(Attempt attempt) throws IOException {
        if (pending.get() != attempt || attempt.cancelled.get()) {
            throw cancelled();
        }
    }

    private static boolean revoke(String refresh, String clientId) {
        String body = form("token", refresh, "token_type_hint", "refresh_token", "client_id", clientId);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String endpoint = get(DISCOVERY).optString("revocation_endpoint");
                if (!endpoint.startsWith(ISSUER + "/api/accounts/oauth/")) {
                    return false;
                }
                request(endpoint, body);
                return true;
            } catch (AuthException exception) {
                if (exception.status < 500) {
                    return false;
                }
            } catch (IOException exception) {
                // Try again once after a network failure.
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static JSONObject get(String url) throws IOException {
        return json(request(url, null));
    }

    private static JSONObject post(String url, String form) throws IOException {
        return json(request(url, form));
    }

    private static String request(String target, String form) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (form != null) {
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw responseError(status, connection.getErrorStream());
            }
            try (InputStream input = connection.getInputStream()) {
                return readBody(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static AuthException responseError(int status, InputStream input) {
        String code = "oauth_http_error";
        if (input != null) {
            try (InputStream body = input) {
                JSONObject error = json(readBody(body));
                JSONObject detail = error.optJSONObject("error");
                String remoteCode = detail == null ? error.optString("error") : detail.optString("code");
                if (remoteCode.matches("[a-z0-9_]{1,80}")) {
                    code = remoteCode;
                }
            } catch (IOException ignored) {
                // Preserve status even when the provider does not return an OAuth error object.
            }
        }
        String message = switch (code) {
            case "invalid_client" -> "ChatGPT no reconoció el registro de esta app.";
            case "invalid_grant" -> "El permiso de conexión caducó. Inicia sesión de nuevo.";
            default -> "ChatGPT no pudo completar la conexión (HTTP " + status + ").";
        };
        return new AuthException(code, message, status);
    }

    private static String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (response.size() + count > RESPONSE_LIMIT) {
                throw new IOException("La respuesta de autorización excede el tamaño esperado.");
            }
            response.write(buffer, 0, count);
        }
        return response.toString(StandardCharsets.UTF_8.name());
    }

    private static JSONObject json(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException exception) {
            throw new IOException("ChatGPT devolvió una respuesta de autorización no válida.");
        }
    }

    private static void put(JSONObject object, String name, Object value) throws IOException {
        try {
            object.put(name, value);
        } catch (JSONException exception) {
            throw new IOException("No se pudo guardar la sesión de ChatGPT.");
        }
    }

    private static String form(String... entries) {
        StringBuilder form = new StringBuilder();
        for (int index = 0; index < entries.length; index += 2) {
            if (index > 0) {
                form.append('&');
            }
            form.append(encode(entries[index])).append('=').append(encode(entries[index + 1]));
        }
        return form.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AuthException cancelled() {
        return new AuthException("authorization_cancelled", "Se canceló la conexión con ChatGPT.");
    }

    private static AuthException signInRequired() {
        return new AuthException("sign_in_required", "Vuelve a conectar tu cuenta de ChatGPT.");
    }

    public static final class Attempt {
        private final LoopbackLogin listener;
        private final Uri authorizationUri;
        private final String requestedClient;
        private final String state;
        private final String nonce;
        private final String verifier;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Attempt(LoopbackLogin listener, Uri uri, String requestedClient,
                        String state, String nonce, String verifier) {
            this.listener = listener;
            authorizationUri = uri;
            this.requestedClient = requestedClient;
            this.state = state;
            this.nonce = nonce;
            this.verifier = verifier;
        }

        public Uri authorizationUri() {
            return authorizationUri;
        }

        private void cancel() {
            cancelled.set(true);
            listener.close();
        }
    }

    public static final class Account {
        private final String clientId;
        private final String label;
        private final String email;
        private final boolean signedIn;
        private final boolean planEnabled;
        private final boolean planConfirmationPending;

        private Account(String clientId, String label, String email, boolean signedIn,
                        boolean planEnabled, boolean planConfirmationPending) {
            this.clientId = clientId;
            this.label = label;
            this.email = email;
            this.signedIn = signedIn;
            this.planEnabled = planEnabled;
            this.planConfirmationPending = planConfirmationPending;
        }

        public String clientId() { return clientId; }
        public String label() { return label; }
        public String email() { return email; }
        public boolean signedIn() { return signedIn; }
        public boolean planEnabled() { return planEnabled; }
        public boolean planConfirmationPending() { return planConfirmationPending; }
    }

    public static final class AuthException extends IOException {
        private final String code;
        private final int status;

        AuthException(String code, String message) {
            this(code, message, 0);
        }

        private AuthException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() { return code; }
        public boolean signInRequired() { return "sign_in_required".equals(code); }
    }
}
