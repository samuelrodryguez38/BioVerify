package com.samuel.verificador;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

public class ProfileSelectionActivity extends Activity {

    private ProfileManager profileManager;

    private LinearLayout profileContainer;

    private TextView tvEmptyProfiles;
    private TextView tvSelectionStatus;

    private Button btnAddProfile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_profile_selection);

        profileManager = new ProfileManager(this);

        profileContainer = findViewById(R.id.profileContainer);

        tvEmptyProfiles = findViewById(R.id.tvEmptyProfiles);

        tvSelectionStatus = findViewById(R.id.tvSelectionStatus);

        btnAddProfile = findViewById(R.id.btnAddProfile);

        btnAddProfile.setOnClickListener(v -> openProfileCreation());

        loadProfiles();
    }


    @Override
    protected void onResume() {

        super.onResume();

        if (profileManager != null) {
            loadProfiles();
        }
    }


    // Consultar los perfiles registrados.
    private void loadProfiles() {

        profileContainer.removeAllViews();

        tvSelectionStatus.setText("");

        List<String> profileIds = profileManager.getProfileIds();

        if (profileIds.isEmpty()) {

            tvEmptyProfiles.setVisibility(View.VISIBLE);

            return;
        }

        tvEmptyProfiles.setVisibility(View.GONE);

        for (String profileId : profileIds) {

            try {

                UserProfileRepository repository =
                        new UserProfileRepository(this, profileId);

                JSONObject profile = repository.loadProfile();

                String fullName = profile.optString(
                        "full_name",
                        "Usuario"
                );

                String username = profile.optString(
                        "username",
                        "Sin usuario"
                );

                addProfileCard(
                        profileId,
                        fullName,
                        username
                );

            } catch (Exception e) {

                tvSelectionStatus.setText(
                        "No se pudo cargar uno de los perfiles."
                );
            }
        }
    }


    // Construir tarjeta visual para cada perfil.
    private void addProfileCard(
            String profileId,
            String fullName,
            String username
    ) {

        LinearLayout card = new LinearLayout(this);

        card.setOrientation(LinearLayout.VERTICAL);

        card.setPadding(
                dp(20),
                dp(18),
                dp(20),
                dp(18)
        );

        GradientDrawable background = new GradientDrawable();

        background.setColor(Color.WHITE);

        background.setCornerRadius(dp(18));

        background.setStroke(
                dp(1),
                Color.rgb(203, 213, 225)
        );

        card.setBackground(background);

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        cardParams.setMargins(
                0,
                0,
                0,
                dp(16)
        );

        card.setLayoutParams(cardParams);


        TextView name = new TextView(this);

        name.setText(fullName);

        name.setTextSize(20);

        name.setTextColor(Color.rgb(23, 37, 84));

        name.setTypeface(null, Typeface.BOLD);


        TextView user = new TextView(this);

        user.setText("@" + username);

        user.setTextSize(15);

        user.setTextColor(Color.rgb(100, 116, 139));

        LinearLayout.LayoutParams userParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        userParams.setMargins(0, dp(5), 0, dp(15));

        user.setLayoutParams(userParams);


        Button selectButton = new Button(this);

        selectButton.setText("Seleccionar perfil");

        selectButton.setAllCaps(false);

        selectButton.setTextColor(Color.WHITE);

        selectButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(37, 99, 235)
                )
        );

        selectButton.setOnClickListener(
                v -> openLogin(profileId)
        );


        card.addView(name);

        card.addView(user);

        card.addView(selectButton);


// Botón de eliminación.
        Button deleteButton = new Button(this);

        deleteButton.setText("Eliminar perfil");

        deleteButton.setAllCaps(false);

        deleteButton.setTextColor(Color.WHITE);

        deleteButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(185, 28, 28)
                )
        );

        deleteButton.setOnClickListener(
                v -> openProfileDeletion(profileId)
        );


        card.addView(deleteButton);

        profileContainer.addView(card);
    }


    // Abrir pantalla de autenticación.
    private void openLogin(String profileId) {

        Intent intent = new Intent(
                ProfileSelectionActivity.this,
                LoginActivity.class
        );

        intent.putExtra(
                AuthManager.EXTRA_PROFILE_ID,
                profileId
        );

        startActivity(intent);
    }


    // Abrir creación de perfil.
    private void openProfileCreation() {

        Intent intent = new Intent(
                ProfileSelectionActivity.this,
                ProfileActivity.class
        );

        startActivity(intent);
    }


    private int dp(float value) {

        return (int) (
                value * getResources()
                        .getDisplayMetrics().density
        );
    }
    private void openProfileDeletion(String profileId) {

        Intent intent = new Intent(
                ProfileSelectionActivity.this,
                ProfileDeletionActivity.class
        );

        intent.putExtra(
                AuthManager.EXTRA_PROFILE_ID,
                profileId
        );

        startActivity(intent);
    }
}