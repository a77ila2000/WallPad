package com.homepad.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialStore {
    static final class Credentials {
        final String username;
        final String password;

        Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    private static final String KEY_ALIAS = "homepad_official_credentials_v1";
    private static final String PREFS = "homepad_secure_credentials";
    private static final String VALUE = "official_ciphertext";
    private static final String IV = "official_iv";
    private final SharedPreferences preferences;

    CredentialStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean hasCredentials() {
        return preferences.contains(VALUE) && preferences.contains(IV);
    }

    void save(String username, String password) throws Exception {
        JSONObject value = new JSONObject();
        value.put("username", username);
        value.put("password", password);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    Credentials load() throws Exception {
        if (!hasCredentials()) return null;
        byte[] encrypted = Base64.decode(preferences.getString(VALUE, ""), Base64.NO_WRAP);
        byte[] iv = Base64.decode(preferences.getString(IV, ""), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        JSONObject value = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        return new Credentials(value.optString("username"), value.optString("password"));
    }

    void clear() {
        preferences.edit().clear().apply();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry existing = store.getEntry(KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
