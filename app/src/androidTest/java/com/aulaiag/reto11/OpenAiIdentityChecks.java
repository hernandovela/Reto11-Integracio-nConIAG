package com.aulaiag.reto11;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

final class OpenAiIdentityChecks {
    private static final String CLIENT = "oaiapp_test";
    private static final String NONCE = "local-test-nonce";
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private OpenAiIdentityChecks() {}

    static void run() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        JSONObject jwk = new JSONObject()
                .put("kid", "test-key")
                .put("kty", "RSA")
                .put("use", "sig")
                .put("alg", "RS256")
                .put("n", encodeNumber(publicKey.getModulus()))
                .put("e", encodeNumber(publicKey.getPublicExponent()));
        JSONObject jwks = new JSONObject().put("keys", new JSONArray().put(jwk));
        JSONObject header = new JSONObject().put("kid", "test-key").put("alg", "RS256");
        long now = System.currentTimeMillis() / 1000;
        JSONObject claims = new JSONObject()
                .put("iss", OpenAiIdentity.ISSUER)
                .put("aud", CLIENT)
                .put("sub", "local-test-account")
                .put("nonce", NONCE)
                .put("iat", now - 60)
                .put("exp", now + 60);

        String valid = sign(pair, header, claims);
        JSONObject identity = OpenAiIdentity.verify(valid, jwks, CLIENT, NONCE);
        if (!"local-test-account".equals(identity.optString("sub"))) {
            throw new AssertionError("Verified subject differs from signed identity");
        }

        expectRejection(valid, jwks, "oaiapp_other", NONCE);
        expectRejection(valid, jwks, CLIENT, "different-nonce");

        String[] jwt = valid.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(jwt[2]);
        signature[0] ^= (byte) 1;
        String tampered = jwt[0] + "." + jwt[1] + "." + BASE64.encodeToString(signature);
        expectRejection(tampered, jwks, CLIENT, NONCE);

        claims.put("exp", now - 1);
        expectRejection(sign(pair, header, claims), jwks, CLIENT, NONCE);
    }

    private static String sign(KeyPair pair, JSONObject header, JSONObject claims) throws Exception {
        String signingInput = encodeJson(header) + "." + encodeJson(claims);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + BASE64.encodeToString(signature.sign());
    }

    private static void expectRejection(String jwt, JSONObject jwks, String client, String nonce)
            throws Exception {
        try {
            OpenAiIdentity.verify(jwt, jwks, client, nonce);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid identity was accepted");
    }

    private static String encodeJson(JSONObject value) {
        return BASE64.encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeNumber(BigInteger number) {
        byte[] bytes = number.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return BASE64.encodeToString(bytes);
    }
}
