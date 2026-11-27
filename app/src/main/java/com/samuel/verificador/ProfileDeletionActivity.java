package com.samuel.verificador;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileDeletionActivity extends Activity {

    private EditText etPassword;

    private TextView tvTitle;
    private TextView tvStatus;

    private Button btnVerifyPassword;

    private String profileId;

    private AuthManager authManager;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean operationInProgress = false;


    private final Runnable lockoutRunnable = new Runnable() {

        @Override
        public void run() {

            if (authManager == null ||
                    !authManager.isLockedOut()) {

                btnVerifyPassword.setEnabled(true);

                tvStatus.setText(
                        "Ya puedes volver a intentarlo."
                );

                return;
            }

            long remaining =
                    authManager.getRemainingLockoutSeconds();

            btnVerifyPassword.setEnabled(false);

            tvStatus.setText(
                    "ACCESO BLOQUEADO\n\n" +
                            "Inténtalo nuevamente en " +
                            remaining + " segundos."
            );

            handler.postDelayed(this, 1000);
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_profile_deletion);

        etPassword = findViewById(R.id.etDeletePassword);

        tvTitle = findViewById(R.id.tvDeleteTitle);

        tvStatus = findViewById(R.id.tvDeleteStatus);

        btnVerifyPassword =
                findViewById(R.id.btnVerifyDelete);

        profileId = getIntent().getStringExtra(
                AuthManager.EXTRA_PROFILE_ID
        );


        if (profileId == null ||
                !profileId.matches("[A-Za-z0-9_-]{1,80}")) {

            Toast.makeText(
                    this,
                    "Perfil inválido",
                    Toast.LENGTH_SHORT
            ).show();

            finish();

            return;
        }


        authManager = new AuthManager(
                getApplicationContext(),
                profileId
        );


        loadProfileInformation();


        btnVerifyPassword.setOnClickListener(
                v -> verifyPassword()
        );


        checkExistingLockout();
    }


    private void loadProfileInformation() {

        try {

            UserProfileRepository repository =
                    new UserProfileRepository(this, profileId);

            JSONObject profile = repository.loadProfile();

            String username = profile.optString(
                    "username",
                    "Usuario"
            );

            tvTitle.setText(
                    "Eliminar perfil @" + username
            );

        } catch (Exception e) {

            tvTitle.setText("Eliminar perfil");
        }
    }


    // Comprobar la contraseña antes de permitir eliminar.
    private void verifyPassword() {

        if (operationInProgress) {
            return;
        }

        if (authManager.isLockedOut()) {

            checkExistingLockout();

            return;
        }


        String password = etPassword.getText()
                .toString();


        if (password.isEmpty()) {

            etPassword.setError(
                    "Introduce la contraseña del perfil"
            );

            return;
        }


        operationInProgress = true;

        btnVerifyPassword.setEnabled(false);

        tvStatus.setText(
                "Verificando contraseña..."
        );


        executor.execute(() -> {

            try {

                AuthManager.LoginResult result =
                        authManager.verifyPassword(password);


                runOnUiThread(() -> {

                    operationInProgress = false;

                    switch (result) {

                        case SUCCESS:

                            etPassword.setText("");

                            handler.removeCallbacksAndMessages(null);

                            showDeleteConfirmation();

                            break;


                        case INVALID_PASSWORD:

                            int remaining =
                                    3 - authManager.getFailedAttempts();

                            etPassword.setText("");

                            btnVerifyPassword.setEnabled(true);

                            tvStatus.setText(
                                    "Contraseña incorrecta.\n" +
                                            "Intentos restantes: " +
                                            remaining
                            );

                            break;


                        case LOCKED:

                            etPassword.setText("");

                            checkExistingLockout();

                            break;


                        case NOT_CONFIGURED:

                            btnVerifyPassword.setEnabled(true);

                            tvStatus.setText(
                                    "Este perfil todavía no tiene una contraseña configurada."
                            );

                            break;
                    }

                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    operationInProgress = false;

                    btnVerifyPassword.setEnabled(true);

                    tvStatus.setText(
                            "Error al verificar la contraseña."
                    );

                });
            }

        });
    }


    private void checkExistingLockout() {

        if (authManager.isLockedOut()) {

            btnVerifyPassword.setEnabled(false);

            handler.removeCallbacks(lockoutRunnable);

            handler.post(lockoutRunnable);

        } else {

            btnVerifyPassword.setEnabled(true);
        }
    }


    // Confirmación explícita de eliminación.
    private void showDeleteConfirmation() {

        new AlertDialog.Builder(this)

                .setTitle("Eliminar perfil definitivamente")

                .setMessage(
                        "Esta acción no se puede deshacer.\n\n" +

                                "Se eliminarán:\n" +
                                "• Datos personales.\n" +
                                "• Contraseña y credenciales.\n" +
                                "• Referencias faciales.\n" +
                                "• Claves de cifrado.\n" +
                                "• Historial de intentos biométricos.\n\n" +

                                "También se retirará tu consentimiento " +
                                "para el tratamiento biométrico en esta aplicación.\n\n" +

                                "¿Deseas continuar?"
                )

                .setNegativeButton(
                        "Cancelar",
                        (dialog, which) -> {
                            tvStatus.setText(
                                    "Eliminación cancelada."
                            );
                        }
                )

                .setPositiveButton(
                        "Eliminar definitivamente",
                        (dialog, which) -> deleteProfile()
                )

                .setCancelable(false)

                .show();
    }


    // Eliminar todos los datos asociados al perfil.
    private void deleteProfile() {

        if (operationInProgress) {
            return;
        }

        operationInProgress = true;

        btnVerifyPassword.setEnabled(false);

        tvStatus.setText(
                "Eliminando información del perfil..."
        );


        executor.execute(() -> {

            try {

                // 1. Eliminar plantillas biométricas y clave AES.
                SecureTemplateRepository biometricRepository =
                        new SecureTemplateRepository(
                                getApplicationContext(),
                                profileId
                        );

                biometricRepository.deleteTemplate();


                // 2. Eliminar datos personales y clave AES.
                UserProfileRepository profileRepository =
                        new UserProfileRepository(
                                getApplicationContext(),
                                profileId
                        );

                profileRepository.deleteProfile();


                // 3. Eliminar contraseña y credenciales.
                boolean credentialsDeleted =
                        AuthManager.deleteProfileCredentials(
                                getApplicationContext(),
                                profileId
                        );

                if (!credentialsDeleted) {
                    throw new IllegalStateException(
                            "No se pudieron eliminar las credenciales"
                    );
                }


                // 4. Eliminar intentos y bloqueos biométricos.
                boolean attemptsDeleted =
                        BiometricAttemptManager.deleteData(
                                getApplicationContext(),
                                profileId
                        );

                if (!attemptsDeleted) {
                    throw new IllegalStateException(
                            "No se pudieron eliminar los intentos biométricos"
                    );
                }


                // 5. Retirar el perfil del administrador.
                ProfileManager manager =
                        new ProfileManager(getApplicationContext());

                boolean removed =
                        manager.removeProfile(profileId);

                if (!removed) {
                    throw new IllegalStateException(
                            "No se pudo retirar el perfil del registro"
                    );
                }


                runOnUiThread(() -> {

                    Toast.makeText(
                            this,
                            "Perfil eliminado correctamente",
                            Toast.LENGTH_LONG
                    ).show();


                    Intent intent = new Intent(
                            ProfileDeletionActivity.this,
                            ProfileSelectionActivity.class
                    );

                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK |
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                    );

                    startActivity(intent);

                    finish();

                });


            } catch (Exception e) {

                android.util.Log.e(
                        "BioVerifyDeletion",
                        "Error eliminando perfil",
                        e
                );

                runOnUiThread(() -> {

                    operationInProgress = false;

                    tvStatus.setText(
                            "No se pudo completar la eliminación.\n" +
                                    "Consulta Logcat para identificar el problema."
                    );

                    btnVerifyPassword.setEnabled(true);
                });
            }

        });
    }


    @Override
    protected void onDestroy() {

        handler.removeCallbacksAndMessages(null);

        executor.shutdownNow();

        super.onDestroy();
    }
}