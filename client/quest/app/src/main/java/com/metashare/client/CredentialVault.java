package com.metashare.client;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Pairing tokens are encrypted at rest using this app's non-exportable Android key. */
final class CredentialVault {
    private static final String ALIAS = "metuxa.pairing.v1";
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static String seal(String text, String endpoint) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    static String open(String sealed, String endpoint) throws Exception {
        if (sealed.isEmpty()) return "";
        String[] parts = sealed.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid stored credential");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
