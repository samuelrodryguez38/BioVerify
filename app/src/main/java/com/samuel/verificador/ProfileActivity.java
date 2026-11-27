package com.samuel.verificador;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;

import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

public class ProfileActivity extends Activity {

    private EditText etFullName;
    private EditText etUsername;
    private EditText etEmail;

    private CheckBox cbConsent;

    private TextView tvStatus;

    private Button btnSave;

    private ProfileManager profileManager;

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9._-]{3,20}$"
            );


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_profile);

        profileManager = new ProfileManager(this);

        etFullName = findViewById(R.id.etFullName);

        etUsername = findViewById(R.id.etUsername);

        etEmail = findViewById(R.id.etEmail);

        cbConsent = findViewById(R.id.cbConsent);

        tvStatus = findViewById(R.id.tvProfileStatus);

        btnSave = findViewById(R.id.btnSaveProfile);

        btnSave.setOnClickListener(v -> saveProfile());
    }


    private void saveProfile() {

        String fullName = etFullName.getText()
                .toString().trim();

        String username = etUsername.getText()
                .toString().trim();

        String email = etEmail.getText()
                .toString().trim();


        if (fullName.length() < 2) {

            etFullName.setError(
                    "Introduce tu nombre completo"
            );

            return;
        }


        if (!USERNAME_PATTERN.matcher(username).matches()) {

            etUsername.setError(
                    "Usa entre 3 y 20 caracteres: letras, números, punto, guion o guion bajo"
            );

            return;
        }


        if (!TextUtils.isEmpty(email) &&
                !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {

            etEmail.setError(
                    "Correo electrónico no válido"
            );

            return;
        }


        if (!cbConsent.isChecked()) {

            tvStatus.setText(
                    "Debes aceptar el consentimiento biométrico."
            );

            return;
        }


        btnSave.setEnabled(false);

        try {

            // Comprobar que no exista otro usuario con ese nombre.
            if (!profileManager.isUsernameAvailable(username)) {

                etUsername.setError(
                        "Este nombre de usuario ya está registrado"
                );

                btnSave.setEnabled(true);

                return;
            }


            // Comprobar si será el primer perfil.
            boolean firstProfile = !profileManager.hasProfiles();


            // Crear identificador único.
            String profileId =
                    profileManager.generateProfileId();


            long currentTime = System.currentTimeMillis();


            // Crear repositorio individual.
            UserProfileRepository repository =
                    new UserProfileRepository(
                            this,
                            profileId
                    );


            // Guardar datos cifrados.
            repository.saveProfile(
                    fullName,
                    username,
                    email,
                    currentTime,
                    currentTime
            );


            // Registrar identificador en el administrador.
            boolean registered =
                    profileManager.addProfile(profileId);


            if (!registered) {

                repository.deleteProfile();

                throw new IllegalStateException(
                        "No se pudo registrar el perfil"
                );
            }


            // Conservar la contraseña antigua en el primer perfil.
            if (firstProfile) {

                AuthManager.migrateLegacyPassword(
                        this,
                        profileId
                );
            }


            Toast.makeText(
                    this,
                    "Perfil creado correctamente",
                    Toast.LENGTH_LONG
            ).show();


            // Abrir la pantalla de autenticación
            // correspondiente a este perfil.
            Intent intent = new Intent(
                    ProfileActivity.this,
                    LoginActivity.class
            );

            intent.putExtra(
                    AuthManager.EXTRA_PROFILE_ID,
                    profileId
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
            );

            startActivity(intent);

            finish();


        } catch (Exception e) {

            btnSave.setEnabled(true);

            tvStatus.setText(
                    "Error al crear el perfil: " + e.getMessage()
            );
        }
    }
}