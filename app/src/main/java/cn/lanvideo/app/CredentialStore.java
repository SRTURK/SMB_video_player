package cn.lanvideo.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** The encryption key stays in Android Keystore; only ciphertext is stored in preferences. */
final class CredentialStore {
    private static final String ALIAS="lanvideo.smb.password.v1";
    private static javax.crypto.SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static void save(SharedPreferences prefs,String password) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
        String data=Base64.encodeToString(cipher.doFinal(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        prefs.edit().putString("password_iv",iv).putString("password_ciphertext",data).apply();
    }
    static String read(SharedPreferences prefs) throws Exception {
        String data=prefs.getString("password_ciphertext",null);if(data==null)return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("password_iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
    static void clear(SharedPreferences prefs){prefs.edit().remove("password_iv").remove("password_ciphertext").putBoolean("autoConnect",false).apply();}
}
