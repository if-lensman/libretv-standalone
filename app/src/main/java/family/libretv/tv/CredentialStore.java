package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One phone-configured profile, encrypted and bound to the exact NAS origin. */
final class CredentialStore {
    private static final String ALIAS = "libretv.phone-login.v1";
    private final SharedPreferences prefs;
    CredentialStore(Context context) { prefs = context.getSharedPreferences("phone-login", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    void save(URI origin, String secret) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(origin.toString().getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("origin", origin.toString()).putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("无法保存登录配置，请检查电视存储空间");
    }
    String load(URI origin) throws Exception {
        if (!origin.toString().equals(prefs.getString("origin", ""))) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        cipher.updateAAD(origin.toString().getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("secret", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    boolean hasSaved() { return prefs.contains("secret"); }
    boolean hasSavedFor(URI origin) {
        return hasSaved() && prefs.contains("iv") && origin.toString().equals(prefs.getString("origin", ""));
    }
    void clear() { prefs.edit().clear().commit(); }
}
