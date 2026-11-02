package net.nether.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore-backed encrypted profile at rest; private keys never go to logs. */
public final class SecretStore {
    private static final String ALIAS = "nether_vpn_profile_v1";
    private static final String PREFS = "nethervpn_secure";
    private SecretStore() {}

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    public static synchronized void save(Context context, String config) throws Exception {
        VpnProfile.parse(config);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(config.getBytes(StandardCharsets.UTF_8));
        byte[] joined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
        SharedPreferences pref = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!pref.edit().putString("profile", Base64.encodeToString(joined, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Не удалось сохранить профиль");
    }

    public static synchronized String load(Context context) throws Exception {
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("profile", null);
        if (encoded == null) throw new IllegalStateException("Сначала импортируйте профиль");
        byte[] joined = Base64.decode(encoded, Base64.DEFAULT);
        if (joined.length < 29) throw new IllegalStateException("Профиль повреждён");
        byte[] iv = new byte[12];
        System.arraycopy(joined, 0, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(joined, 12, joined.length - 12), StandardCharsets.UTF_8);
    }

    public static boolean exists(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("profile");
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("profile").apply();
    }
}
