package com.samuel.verificador;

import android.content.Context;
import android.content.SharedPreferences;

public class BiometricAttemptManager {

    private static final int MAX_ATTEMPTS = 3;

    private static final long LOCKOUT_DURATION = 60 * 1000L;

    private static final String KEY_ATTEMPTS = "failed_attempts";

    private static final String KEY_LOCKOUT = "lockout_until";

    private final SharedPreferences preferences;


    public BiometricAttemptManager(
            Context context,
            String profileId
    ) {

        if (profileId == null ||
                !profileId.matches("[A-Za-z0-9_-]{1,80}")) {

            throw new IllegalArgumentException(
                    "Identificador de perfil inválido"
            );
        }

        String preferencesName =
                "biometric_attempts_" + profileId;

        preferences = context.getApplicationContext()
                .getSharedPreferences(
                        preferencesName,
                        Context.MODE_PRIVATE
                );
    }


    // Registrar un intento biométrico fallido.
    public synchronized int registerFailure() {

        if (isLockedOut()) {
            return MAX_ATTEMPTS;
        }

        int attempts = preferences.getInt(
                KEY_ATTEMPTS,
                0
        ) + 1;

        SharedPreferences.Editor editor =
                preferences.edit();

        editor.putInt(KEY_ATTEMPTS, attempts);

        if (attempts >= MAX_ATTEMPTS) {

            editor.putInt(KEY_ATTEMPTS, MAX_ATTEMPTS);

            editor.putLong(
                    KEY_LOCKOUT,
                    System.currentTimeMillis() +
                            LOCKOUT_DURATION
            );
        }

        editor.commit();

        return attempts;
    }


    // Consultar si el perfil está bloqueado.
    public synchronized boolean isLockedOut() {

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


    // Consultar intentos fallidos.
    public synchronized int getFailedAttempts() {

        if (isLockedOut()) {
            return MAX_ATTEMPTS;
        }

        return preferences.getInt(
                KEY_ATTEMPTS,
                0
        );
    }


    // Consultar tiempo restante.
    public synchronized long getRemainingSeconds() {

        long remaining = preferences.getLong(
                KEY_LOCKOUT,
                0
        ) - System.currentTimeMillis();

        if (remaining <= 0) {
            return 0;
        }

        return (remaining + 999) / 1000;
    }


    // Restablecer contador al verificar correctamente.
    public synchronized void resetAttempts() {

        preferences.edit()
                .putInt(KEY_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT, 0)
                .commit();
    }


    // Eliminar los datos de intentos de un perfil.
    public static boolean deleteData(
            Context context,
            String profileId
    ) {

        if (profileId == null ||
                !profileId.matches("[A-Za-z0-9_-]{1,80}")) {

            return false;
        }

        return context.getApplicationContext()
                .getSharedPreferences(
                        "biometric_attempts_" + profileId,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
    }
}