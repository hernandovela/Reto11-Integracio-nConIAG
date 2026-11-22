package com.aulaiag.reto11;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credential state stored atomically, encrypted with an app-specific Android Keystore key. */
final class SecureSessionStore {
    private static final String ALIAS = "AulaIagSessions";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final AtomicFile file;

    SecureSessionStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "chatgpt.sessions"));
    }

    JSONObject read() throws IOException {
        try {
            byte[] encrypted = file.readFully();
            if (encrypted.length < IV_LENGTH + TAG_LENGTH / 8) {
                throw new IOException("El almacenamiento de cuentas no se pudo leer.");
            }
            byte[] iv = Arrays.copyOf(encrypted, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] data = cipher.doFinal(encrypted, IV_LENGTH, encrypted.length - IV_LENGTH);
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (FileNotFoundException exception) {
            if (!file.getBaseFile().exists()) {
                return new JSONObject();
            }
            throw new IOException("No se pudo abrir el almacenamiento de cuentas.", exception);
        } catch (GeneralSecurityException | JSONException exception) {
            throw new IOException("No se pudieron leer las cuentas protegidas.", exception);
        }
    }

    void write(JSONObject state) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] encrypted = cipher.doFinal(state.toString().getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            if (iv.length != IV_LENGTH) {
                throw new IOException("El almacenamiento protegido no está disponible.");
            }
            FileOutputStream output = file.startWrite();
            try {
                output.write(iv);
                output.write(encrypted);
                file.finishWrite(output);
            } catch (IOException exception) {
                file.failWrite(output);
                throw exception;
            }
        } catch (GeneralSecurityException exception) {
            throw new IOException("No se pudo guardar la cuenta protegida.", exception);
        }
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return (SecretKey) keyStore.getKey(ALIAS, null);
        }
        if (!create) {
            throw new GeneralSecurityException("Missing session encryption key");
        }
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
