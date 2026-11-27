package com.samuel.verificador;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends Activity {

    private AuthManager authManager;
    private String profileId;

    private TextView tvLoginTitle;
    private TextView tvLoginSubtitle;
    private TextView tvLoginStatus;

    private EditText etPassword;
    private EditText etConfirmPassword;

    private Button btnLogin;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean creatingPassword = false;

    private boolean operationInProgress = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_login);

        profileId = getIntent().getStringExtra(
                AuthManager.EXTRA_PROFILE_ID
        );

// Compatibilidad temporal con el acceso anterior.
// Cuando terminemos la pantalla de selección,
// siempre llegará un identificador de perfil.
        if (profileId == null || profileId.trim().isEmpty()) {
            profileId = AuthManager.LEGACY_PROFILE_ID;
        }

        authManager = new AuthManager(this, profileId);

        tvLoginTitle = findViewById(R.id.tvLoginTitle);
        tvLoginSubtitle = findViewById(R.id.tvLoginSubtitle);
        tvLoginStatus = findViewById(R.id.tvLoginStatus);

        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);

        btnLogin = findViewById(R.id.btnLogin);

        configureScreen();

        btnLogin.setOnClickListener(v -> authenticate());
    }


    // Configurar pantalla según el estado de la aplicación.
    private void configureScreen() {

        creatingPassword = !authManager.hasPassword();

        if (creatingPassword) {

            tvLoginTitle.setText("Configura tu acceso");

            tvLoginSubtitle.setText(
                    "Crea una contraseña para proteger BioVerify."
            );

            etConfirmPassword.setVisibility(View.VISIBLE);

            btnLogin.setText("CREAR CONTRASEÑA");

        } else {

            tvLoginTitle.setText("Bienvenido");

            tvLoginSubtitle.setText(
                    "Introduce tu contraseña para continuar."
            );

            etConfirmPassword.setVisibility(View.GONE);

            btnLogin.setText("INICIAR SESIÓN");
        }

        checkLockout();
    }


    // Registrar o verificar contraseña.
    private void authenticate() {

        if (operationInProgress) {
            return;
        }

        if (authManager.isLockedOut()) {
            checkLockout();
            return;
        }

        String password = etPassword.getText().toString();

        if (password.isEmpty()) {

            showMessage(
                    "Introduce tu contraseña.",
                    "#F59E0B"
            );

            return;
        }

        if (creatingPassword) {

            String confirmation =
                    etConfirmPassword.getText().toString();

            if (password.length() < 8) {

                showMessage(
                        "La contraseña debe tener al menos 8 caracteres.",
                        "#F59E0B"
                );

                return;
            }

            if (!password.equals(confirmation)) {

                showMessage(
                        "Las contraseñas no coinciden.",
                        "#F59E0B"
                );

                return;
            }

            createPassword(password);

        } else {

            verifyPassword(password);
        }
    }


    // Crear contraseña inicial.
    private void createPassword(String password) {

        operationInProgress = true;

        btnLogin.setEnabled(false);

        showMessage(
                "Configurando acceso seguro...",
                "#0EA5E9"
        );

        executor.execute(() -> {

            try {

                authManager.createPassword(password);

                runOnUiThread(() -> {

                    operationInProgress = false;

                    openCamera();

                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    operationInProgress = false;

                    btnLogin.setEnabled(true);

                    showMessage(
                            "No se pudo crear la contraseña.",
                            "#EF4444"
                    );
                });
            }
        });
    }


    // Verificar contraseña existente.
    private void verifyPassword(String password) {

        operationInProgress = true;

        btnLogin.setEnabled(false);

        showMessage(
                "Verificando credenciales...",
                "#0EA5E9"
        );

        executor.execute(() -> {

            try {

                AuthManager.LoginResult result =
                        authManager.verifyPassword(password);

                runOnUiThread(() -> {

                    operationInProgress = false;

                    switch (result) {

                        case SUCCESS:

                            openCamera();

                            break;

                        case INVALID_PASSWORD:

                            int remaining =
                                    3 - authManager.getFailedAttempts();

                            btnLogin.setEnabled(true);

                            etPassword.setText("");

                            showMessage(
                                    "Contraseña incorrecta.\n" +
                                            "Intentos restantes: " + remaining,
                                    "#F59E0B"
                            );

                            break;

                        case LOCKED:

                            etPassword.setText("");

                            checkLockout();

                            break;

                        case NOT_CONFIGURED:

                            configureScreen();

                            showMessage(
                                    "Configura una contraseña.",
                                    "#F59E0B"
                            );

                            break;
                    }
                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    operationInProgress = false;

                    btnLogin.setEnabled(true);

                    showMessage(
                            "Error al verificar las credenciales.",
                            "#EF4444"
                    );
                });
            }
        });
    }


    // Comprobar y gestionar bloqueo temporal.
    private void checkLockout() {

        if (!authManager.isLockedOut()) {

            btnLogin.setEnabled(true);

            return;
        }

        btnLogin.setEnabled(false);

        updateLockoutMessage();
    }


    private void updateLockoutMessage() {

        long remaining =
                authManager.getRemainingLockoutSeconds();

        if (remaining <= 0) {

            btnLogin.setEnabled(true);

            showMessage(
                    "Ya puedes volver a intentarlo.",
                    "#22C55E"
            );

            return;
        }

        showMessage(
                "ACCESO TEMPORALMENTE BLOQUEADO\n\n" +
                        "Demasiados intentos fallidos.\n" +
                        "Inténtalo nuevamente en " +
                        remaining + " segundos.",
                "#EF4444"
        );

        handler.postDelayed(
                this::updateLockoutMessage,
                1000
        );
    }


    // Mostrar mensajes.
    private void showMessage(String message, String color) {

        tvLoginStatus.setText(message);

        tvLoginStatus.setTextColor(
                Color.parseColor(color)
        );
    }


    // Abrir cámara después de autenticación.
    private void openCamera() {

        Intent intent = new Intent(
                LoginActivity.this,
                CameraActivity.class
        );

        intent.putExtra(
                AuthManager.EXTRA_PROFILE_ID,
                profileId
        );

        startActivity(intent);

        finish();
    }


    @Override
    protected void onDestroy() {

        handler.removeCallbacksAndMessages(null);

        executor.shutdown();

        super.onDestroy();
    }
}