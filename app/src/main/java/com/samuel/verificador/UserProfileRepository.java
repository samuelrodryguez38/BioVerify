package com.samuel.verificador;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import java.nio.charset.StandardCharsets;

import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class UserProfileRepository {

    private final AtomicFile profileFile;

    private final String keyAlias;

    public UserProfileRepository(
            Context context,
            String profileId
    ) {

        if (profileId == null ||
                !profileId.matches(
                        "[A-Za-z0-9_-]{1,80}"
                )) {

            throw new IllegalArgumentException(
                    "Identificador de perfil inválido"
            );
        }

        File file = new File(
                context.getFilesDir(),
                "profile_" + profileId + ".enc"
        );

        profileFile = new AtomicFile(file);

        keyAlias = "BioVerifyProfileKey_" + profileId;
    }


    // Guardar los datos personales cifrados.
    public void saveProfile(
            String fullName,
            String username,
            String email,
            long createdAt,
            long consentAt
    ) throws Exception {

        JSONObject profile = new JSONObject();

        profile.put("full_name", fullName);
        profile.put("username", username);
        profile.put("email", email);

        profile.put("created_at", createdAt);

        profile.put("consent_accepted", true);
        profile.put("consent_at", consentAt);
        profile.put("consent_version", "1.0");

        byte[] data = profile.toString().getBytes(
                StandardCharsets.UTF_8
        );

        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
        );

        cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateKey()
        );

        byte[] iv = cipher.getIV();

        byte[] encryptedData = cipher.doFinal(data);

        FileOutputStream output = null;

        try {

            output = profileFile.startWrite();

            java.io.DataOutputStream dataOutput =
                    new java.io.DataOutputStream(output);

            dataOutput.writeInt(iv.length);
            dataOutput.write(iv);
            dataOutput.write(encryptedData);

            dataOutput.flush();

            profileFile.finishWrite(output);

        } catch (Exception e) {

            if (output != null) {
                profileFile.failWrite(output);
            }

            throw e;
        }
    }


    // Leer y descifrar el perfil.
    public JSONObject loadProfile() throws Exception {

        try (
                InputStream input = profileFile.openRead();

                DataInputStream dataInput =
                        new DataInputStream(input)
        ) {

            int ivLength = dataInput.readInt();

            if (ivLength < 12 || ivLength > 16) {
                throw new IllegalStateException(
                        "IV de cifrado inválido"
                );
            }

            byte[] iv = new byte[ivLength];

            dataInput.readFully(iv);

            ByteArrayOutputStream buffer =
                    new ByteArrayOutputStream();

            byte[] temporary = new byte[4096];

            int bytesRead;

            while ((bytesRead = dataInput.read(temporary)) != -1) {
                buffer.write(temporary, 0, bytesRead);
            }

            byte[] encryptedData = buffer.toByteArray();

            Cipher cipher = Cipher.getInstance(
                    "AES/GCM/NoPadding"
            );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(128, iv)
            );

            byte[] decryptedData = cipher.doFinal(
                    encryptedData
            );

            String json = new String(
                    decryptedData,
                    StandardCharsets.UTF_8
            );

            return new JSONObject(json);
        }
    }


    // Comprobar existencia del perfil.
    public boolean hasProfile() {
        return profileFile.getBaseFile().exists();
    }


    // Eliminar perfil y su clave.
    public void deleteProfile() throws Exception {

        profileFile.delete();

        KeyStore keyStore = KeyStore.getInstance(
                "AndroidKeyStore"
        );

        keyStore.load(null);

        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias);
        }
    }


    // Obtener clave o generar una nueva.
    private SecretKey getOrCreateKey() throws Exception {

        KeyStore keyStore = KeyStore.getInstance(
                "AndroidKeyStore"
        );

        keyStore.load(null);

        if (keyStore.containsAlias(keyAlias)) {

            Key key = keyStore.getKey(keyAlias, null);

            return (SecretKey) key;
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );

        KeyGenParameterSpec specification =
                new KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT |
                                KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(
                                KeyProperties.BLOCK_MODE_GCM
                        )
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE
                        )
                        .setRandomizedEncryptionRequired(true)
                        .build();

        keyGenerator.init(specification);

        return keyGenerator.generateKey();
    }
}