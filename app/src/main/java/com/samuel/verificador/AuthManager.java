package com.samuel.verificador;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public class AuthManager {

    private static final String PREFS_NAME = "biometric_auth";

    public static final String EXTRA_PROFILE_ID =
            "com.samuel.verificador.PROFILE_ID";

    public static final String LEGACY_PROFILE_ID = "legacy_default";

    private static final String KEY_SALT = "password_salt";
    private static final String KEY_HASH = "password_hash";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCKOUT = "lockout_until";

    private static final int ITERATIONS = 600000;
    private static final int KEY_LENGTH = 32;
    private static final int SALT_LENGTH = 16;

    private static final int MAX_ATTEMPTS = 3;

    private static final long LOCKOUT_DURATION = 60 * 1000L;

    private final SharedPreferences preferences;
    private final String profileId;


    public enum LoginResult {
        SUCCESS,
        INVALID_PASSWORD,
        LOCKED,
        NOT_CONFIGURED
    }


    // Cada perfil obtiene sus propias preferencias.
    public AuthManager(Context context, String profileId) {

        if (profileId == null ||
                !profileId.matches("[A-Za-z0-9_-]{1,80}")) {

            throw new IllegalArgumentException(
                    "Identificador de perfil inválido"
            );
        }

        this.profileId = profileId;

        String preferencesName;

        // Conservamos las preferencias antiguas para no perder
        // la contraseña que ya estaba configurada.
        if (LEGACY_PROFILE_ID.equals(profileId)) {

            preferencesName = PREFS_NAME;

        } else {

            preferencesName = PREFS_NAME + "_" + profileId;
        }

        preferences = context.getApplicationContext()
                .getSharedPreferences(
                        preferencesName,
                        Context.MODE_PRIVATE
                );
    }


    public String getProfileId() {
        return profileId;
    }


    // Comprueba si este perfil tiene contraseña.
    public boolean hasPassword() {

        return preferences.contains(KEY_HASH)
                && preferences.contains(KEY_SALT);
    }


    // Crea la contraseña de un perfil.
    public synchronized void createPassword(String password)
            throws GeneralSecurityException {

        if (hasPassword()) {
            throw new IllegalStateException(
                    "Este perfil ya tiene una contraseña"
            );
        }

        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException(
                    "La contraseña debe tener al menos 8 caracteres"
            );
        }

        byte[] salt = new byte[SALT_LENGTH];

        new SecureRandom().nextBytes(salt);

        byte[] hash = deriveKey(
                password,
                salt,
                ITERATIONS
        );

        boolean saved = preferences.edit()
                .putString(
                        KEY_SALT,
                        Base64.encodeToString(salt, Base64.NO_WRAP)
                )
                .putString(
                        KEY_HASH,
                        Base64.encodeToString(hash, Base64.NO_WRAP)
                )
                .putInt(KEY_ITERATIONS, ITERATIONS)
                .putInt(KEY_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT, 0)
                .commit();

        Arrays.fill(hash, (byte) 0);
        Arrays.fill(salt, (byte) 0);

        if (!saved) {
            throw new IllegalStateException(
                    "No se pudo guardar la contraseña"
            );
        }
    }


    // Comprueba la contraseña del perfil seleccionado.
    public synchronized LoginResult verifyPassword(String password)
            throws GeneralSecurityException {

        if (!hasPassword()) {
            return LoginResult.NOT_CONFIGURED;
        }

        if (isLockedOut()) {
            return LoginResult.LOCKED;
        }

        if (password == null) {
            password = "";
        }

        String savedSalt = preferences.getString(KEY_SALT, null);
        String savedHash = preferences.getString(KEY_HASH, null);

        if (savedSalt == null || savedHash == null) {
            return LoginResult.NOT_CONFIGURED;
        }

        byte[] salt = Base64.decode(savedSalt, Base64.NO_WRAP);

        byte[] expectedHash =
                Base64.decode(savedHash, Base64.NO_WRAP);

        int iterations = preferences.getInt(
                KEY_ITERATIONS,
                ITERATIONS
        );

        byte[] calculatedHash = deriveKey(
                password,
                salt,
                iterations
        );

        boolean correct = MessageDigest.isEqual(
                expectedHash,
                calculatedHash
        );

        Arrays.fill(calculatedHash, (byte) 0);
        Arrays.fill(expectedHash, (byte) 0);
        Arrays.fill(salt, (byte) 0);

        if (correct) {

            resetAttempts();

            return LoginResult.SUCCESS;
        }

        int attempts = getFailedAttempts() + 1;

        if (attempts >= MAX_ATTEMPTS) {

            preferences.edit()
                    .putInt(KEY_ATTEMPTS, MAX_ATTEMPTS)
                    .putLong(
                            KEY_LOCKOUT,
                            System.currentTimeMillis()
                                    + LOCKOUT_DURATION
                    )
                    .commit();

            return LoginResult.LOCKED;
        }

        preferences.edit()
                .putInt(KEY_ATTEMPTS, attempts)
                .commit();

        return LoginResult.INVALID_PASSWORD;
    }


    // Derivación PBKDF2-HMAC-SHA256.
    private byte[] deriveKey(
            String password,
            byte[] salt,
            int iterations
    ) throws GeneralSecurityException {

        if (iterations < 1 || iterations > 5000000) {
            throw new GeneralSecurityException(
                    "Número de iteraciones inválido"
            );
        }

        byte[] passwordBytes =
                password.getBytes(StandardCharsets.UTF_8);

        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(
                passwordBytes,
                "HmacSHA256"
        ));

        // Primer bloque PBKDF2.
        ByteBuffer block = ByteBuffer.allocate(salt.length + 4);

        block.order(ByteOrder.BIG_ENDIAN);

        block.put(salt);
        block.putInt(1);

        byte[] u = mac.doFinal(block.array());

        byte[] result = u.clone();

        // Iteraciones restantes.
        for (int i = 1; i < iterations; i++) {

            byte[] next = mac.doFinal(u);

            Arrays.fill(u, (byte) 0);

            u = next;

            for (int j = 0; j < result.length; j++) {
                result[j] ^= u[j];
            }
        }

        Arrays.fill(passwordBytes, (byte) 0);
        Arrays.fill(u, (byte) 0);

        return result;
    }


    // Consultar intentos fallidos de este perfil.
    public int getFailedAttempts() {

        if (!isLockedOut()) {
            return preferences.getInt(KEY_ATTEMPTS, 0);
        }

        return MAX_ATTEMPTS;
    }


    // Consultar bloqueo de este perfil.
    public boolean isLockedOut() {

        long lockoutUntil = preferences.getLong(
                KEY_LOCKOUT,
                0
        );

        if (lockoutUntil == 0) {
            return false;
        }

        if (System.currentTimeMillis() >= lockoutUntil) {

            resetAttempts();

            return false;
        }

        return true;
    }


    // Tiempo restante del bloqueo.
    public long getRemainingLockoutSeconds() {

        long remaining = preferences.getLong(
                KEY_LOCKOUT,
                0
        ) - System.currentTimeMillis();

        if (remaining <= 0) {
            return 0;
        }

        return (remaining + 999) / 1000;
    }


    // Restablecer intentos.
    private void resetAttempts() {

        preferences.edit()
                .putInt(KEY_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT, 0)
                .commit();
    }


    // MIGRACIÓN:
    // Transfiere la contraseña antigua al primer perfil creado.
    public static synchronized boolean migrateLegacyPassword(
            Context context,
            String newProfileId
    ) {

        if (newProfileId == null ||
                newProfileId.equals(LEGACY_PROFILE_ID) ||
                !newProfileId.matches("[A-Za-z0-9_-]{1,80}")) {

            return false;
        }

        SharedPreferences legacy = context.getApplicationContext()
                .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                );

        SharedPreferences target = context.getApplicationContext()
                .getSharedPreferences(
                        PREFS_NAME + "_" + newProfileId,
                        Context.MODE_PRIVATE
                );

        // No existe una contraseña antigua que migrar.
        if (!legacy.contains(KEY_HASH) ||
                !legacy.contains(KEY_SALT)) {

            return false;
        }

        // No sobrescribir una contraseña que ya tenga el perfil.
        if (target.contains(KEY_HASH)) {
            return false;
        }

        SharedPreferences.Editor editor = target.edit();

        editor.putString(
                KEY_SALT,
                legacy.getString(KEY_SALT, null)
        );

        editor.putString(
                KEY_HASH,
                legacy.getString(KEY_HASH, null)
        );

        editor.putInt(
                KEY_ITERATIONS,
                legacy.getInt(KEY_ITERATIONS, ITERATIONS)
        );

        editor.putInt(
                KEY_ATTEMPTS,
                legacy.getInt(KEY_ATTEMPTS, 0)
        );

        editor.putLong(
                KEY_LOCKOUT,
                legacy.getLong(KEY_LOCKOUT, 0)
        );

        boolean saved = editor.commit();

        // Eliminar datos antiguos solamente después de confirmar
        // que la copia al perfil nuevo se guardó correctamente.
        if (saved) {
            legacy.edit().clear().commit();
        }

        return saved;
    }
    // Eliminar las credenciales de un perfil.
    public static boolean deleteProfileCredentials(
            Context context,
            String profileId
    ) {

        if (profileId == null ||
                !profileId.matches("[A-Za-z0-9_-]{1,80}")) {

            return false;
        }

        String preferencesName;

        if (LEGACY_PROFILE_ID.equals(profileId)) {
            preferencesName = PREFS_NAME;
        } else {
            preferencesName = PREFS_NAME + "_" + profileId;
        }

        return context.getApplicationContext()
                .getSharedPreferences(
                        preferencesName,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
    }
}