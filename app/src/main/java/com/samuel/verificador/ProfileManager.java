package com.samuel.verificador;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ProfileManager {

    private static final String PREFS_NAME = "biometric_profiles";
    private static final String KEY_PROFILE_IDS = "profile_ids";

    private final Context context;
    private final SharedPreferences preferences;

    public ProfileManager(Context context) {

        this.context = context.getApplicationContext();

        preferences = this.context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );
    }

    // Genera un identificador único para cada perfil.
    public String generateProfileId() {
        return UUID.randomUUID().toString();
    }

    // Comprueba que el identificador tenga formato UUID.
    private boolean isValidProfileId(String id) {

        if (id == null) {
            return false;
        }

        try {
            UUID.fromString(id);
            return true;

        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Registra un perfil existente.
    public synchronized boolean addProfile(String profileId) {

        if (!isValidProfileId(profileId)) {
            return false;
        }

        UserProfileRepository repository =
                new UserProfileRepository(context, profileId);

        if (!repository.hasProfile()) {
            return false;
        }

        Set<String> ids = new HashSet<>(
                preferences.getStringSet(
                        KEY_PROFILE_IDS,
                        Collections.emptySet()
                )
        );

        ids.add(profileId);

        return preferences.edit()
                .putStringSet(KEY_PROFILE_IDS, ids)
                .commit();
    }

    // Recupera los identificadores registrados.
    public List<String> getProfileIds() {

        Set<String> ids = preferences.getStringSet(
                KEY_PROFILE_IDS,
                Collections.emptySet()
        );

        List<String> result = new ArrayList<>(ids);

        Collections.sort(result);

        return result;
    }

    // Comprueba si existen perfiles.
    public boolean hasProfiles() {
        return !getProfileIds().isEmpty();
    }

    // Número de perfiles registrados.
    public int getProfileCount() {
        return getProfileIds().size();
    }

    // Comprueba que un nombre de usuario esté disponible.
    public boolean isUsernameAvailable(String username)
            throws Exception {

        String normalizedUsername = username.trim();

        for (String id : getProfileIds()) {

            UserProfileRepository repository =
                    new UserProfileRepository(context, id);

            JSONObject profile = repository.loadProfile();

            String storedUsername =
                    profile.optString("username", "");

            if (storedUsername.equalsIgnoreCase(
                    normalizedUsername
            )) {
                return false;
            }
        }

        return true;
    }
    // Recuperar el perfil creado más antiguamente.
    public String getOldestProfileId() {

        String oldestProfileId = null;

        String fallbackProfileId = null;

        long oldestTime = Long.MAX_VALUE;

        for (String id : getProfileIds()) {

            try {

                if (fallbackProfileId == null) {
                    fallbackProfileId = id;
                }

                UserProfileRepository repository =
                        new UserProfileRepository(context, id);

                JSONObject profile = repository.loadProfile();

                long createdAt = profile.optLong(
                        "created_at",
                        Long.MAX_VALUE
                );

                if (createdAt < oldestTime) {

                    oldestTime = createdAt;

                    oldestProfileId = id;
                }

            } catch (Exception e) {

                android.util.Log.e(
                        "BioVerify",
                        "No se pudo consultar la fecha del perfil " + id,
                        e
                );
            }
        }

        return oldestProfileId != null
                ? oldestProfileId
                : fallbackProfileId;
    }
    // Eliminar identificador del registro central.
    public synchronized boolean removeProfile(String profileId) {

        if (!isValidProfileId(profileId)) {
            return false;
        }

        Set<String> ids = new HashSet<>(
                preferences.getStringSet(
                        KEY_PROFILE_IDS,
                        Collections.emptySet()
                )
        );

        if (!ids.contains(profileId)) {
            return false;
        }

        ids.remove(profileId);

        return preferences.edit()
                .putStringSet(KEY_PROFILE_IDS, ids)
                .commit();
    }
}