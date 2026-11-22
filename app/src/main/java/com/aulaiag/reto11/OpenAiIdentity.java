package com.aulaiag.reto11;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

final class OpenAiIdentity {
    static final String ISSUER = "https://auth.openai.com";
    private static final long CLOCK_SKEW_SECONDS = 60;

    private OpenAiIdentity() {}

    static JSONObject verify(String token, JSONObject jwks, String clientId, String nonce)
            throws IOException {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || token.length() > 65_536) {
                throw invalidIdentity();
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JSONObject header = new JSONObject(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8));
            String keyId = header.getString("kid");
            if (!"RS256".equals(header.optString("alg")) || keyId.isEmpty() || header.has("crit")) {
                throw invalidIdentity();
            }
            JSONObject key = publicSigningKey(jwks.getJSONArray("keys"), keyId);
            BigInteger modulus = new BigInteger(1, decoder.decode(key.getString("n")));
            BigInteger exponent = new BigInteger(1, decoder.decode(key.getString("e")));
            if (modulus.bitLength() < 2048) {
                throw invalidIdentity();
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(parts[2]))) {
                throw invalidIdentity();
            }

            JSONObject claims = new JSONObject(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis() / 1000;
            if (!ISSUER.equals(claims.optString("iss"))
                    || !validAudience(claims, clientId)
                    || claims.getLong("exp") <= now
                    || claims.getLong("iat") > now + CLOCK_SKEW_SECONDS
                    || claims.optLong("nbf", 0) > now + CLOCK_SKEW_SECONDS
                    || !nonce.equals(claims.optString("nonce"))
                    || claims.getString("sub").trim().isEmpty()) {
                throw invalidIdentity();
            }
            return claims;
        } catch (JSONException | IllegalArgumentException | GeneralSecurityException exception) {
            throw invalidIdentity();
        }
    }

    private static JSONObject publicSigningKey(JSONArray keys, String keyId)
            throws JSONException, IOException {
        for (int index = 0; index < keys.length(); index++) {
            JSONObject key = keys.getJSONObject(index);
            if (keyId.equals(key.optString("kid"))
                    && "RSA".equals(key.optString("kty"))
                    && "sig".equals(key.optString("use", "sig"))
                    && "RS256".equals(key.optString("alg", "RS256"))) {
                return key;
            }
        }
        throw invalidIdentity();
    }

    private static boolean validAudience(JSONObject claims, String clientId) throws JSONException {
        if (claims.has("azp") && !clientId.equals(claims.optString("azp"))) {
            return false;
        }
        Object audience = claims.get("aud");
        if (audience instanceof String) {
            return clientId.equals(audience);
        }
        if (audience instanceof JSONArray) {
            JSONArray list = (JSONArray) audience;
            if (list.length() > 1 && !clientId.equals(claims.optString("azp"))) {
                return false;
            }
            for (int index = 0; index < list.length(); index++) {
                if (clientId.equals(list.opt(index))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IOException invalidIdentity() {
        return new IOException("No se pudo verificar la identidad de ChatGPT.");
    }
}
