package com.samuel.verificador;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecureTemplateRepository {

    private static final String LEGACY_KEY_ALIAS =
            "BioVerifyTemplateKey";

    private static final String LEGACY_FILE_NAME =
            "face_template.enc";

    private static final int VECTOR_SIZE = 128;

    private static final int MAX_TEMPLATES = 3;

    private static final int FORMAT_VERSION = 1;

    private static final int IV_LENGTH = 12;

    private static final int GCM_TAG_BITS = 128;

    private final AtomicFile templateFile;

    private final String keyAlias;


    // Constructor individual por perfil.
    public SecureTemplateRepository(
            Context context,
            String profileId
    ) {

        if (profileId == null ||
                !profileId.matches("[A-Za-z0-9_-]{1,80}")) {

            throw new IllegalArgumentException(
                    "Identificador de perfil inválido"
            );
        }

        File file = new File(
                context.getFilesDir(),
                "face_template_" + profileId + ".enc"
        );

        templateFile = new AtomicFile(file);

        keyAlias = "BioVerifyTemplateKey_" + profileId;
    }


    // Obtener o crear clave AES individual.
    private SecretKey getOrCreateKey()
            throws GeneralSecurityException, IOException {

        KeyStore keyStore = KeyStore.getInstance(
                "AndroidKeyStore"
        );

        keyStore.load(null);

        if (keyStore.containsAlias(keyAlias)) {

            return (SecretKey) keyStore.getKey(
                    keyAlias,
                    null
            );
        }

        KeyGenerator generator = KeyGenerator.getInstance(
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
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build();

        generator.init(specification);

        return generator.generateKey();
    }


    // Guardar una sola plantilla.
    // Se conserva este método para la versión actual de CameraActivity.
    public synchronized void saveTemplate(float[] embedding)
            throws GeneralSecurityException, IOException {

        saveTemplates(new float[][]{embedding});
    }


    // Guardar entre una y tres plantillas.
    public synchronized void saveTemplates(float[][] embeddings)
            throws GeneralSecurityException, IOException {

        if (embeddings == null ||
                embeddings.length < 1 ||
                embeddings.length > MAX_TEMPLATES) {

            throw new IllegalArgumentException(
                    "Debe existir entre una y tres plantillas"
            );
        }

        ByteArrayOutputStream byteBuffer =
                new ByteArrayOutputStream();

        DataOutputStream dataOutput =
                new DataOutputStream(byteBuffer);

        dataOutput.writeInt(FORMAT_VERSION);

        dataOutput.writeInt(embeddings.length);

        for (float[] embedding : embeddings) {

            if (embedding == null ||
                    embedding.length != VECTOR_SIZE) {

                throw new IllegalArgumentException(
                        "Cada vector debe tener 128 componentes"
                );
            }

            for (float value : embedding) {

                if (Float.isNaN(value) ||
                        Float.isInfinite(value)) {

                    throw new IllegalArgumentException(
                            "El vector contiene valores inválidos"
                    );
                }

                dataOutput.writeFloat(value);
            }
        }

        dataOutput.flush();

        byte[] plainData = byteBuffer.toByteArray();

        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
        );

        cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateKey()
        );

        byte[] iv = cipher.getIV();

        byte[] encryptedData = cipher.doFinal(plainData);

        FileOutputStream stream = templateFile.startWrite();

        try {

            DataOutputStream output =
                    new DataOutputStream(stream);

            output.writeInt(iv.length);

            output.write(iv);

            output.write(encryptedData);

            output.flush();

            templateFile.finishWrite(stream);

        } catch (IOException | RuntimeException e) {

            templateFile.failWrite(stream);

            throw e;
        }
    }


    // Recuperar las plantillas.
    public synchronized float[][] loadTemplates()
            throws GeneralSecurityException, IOException {

        byte[] iv;
        byte[] encryptedData;

        try (
                DataInputStream input =
                        new DataInputStream(templateFile.openRead())
        ) {

            int ivLength = input.readInt();

            if (ivLength != IV_LENGTH) {

                throw new IOException(
                        "Longitud de IV inválida"
                );
            }

            iv = new byte[ivLength];

            input.readFully(iv);

            ByteArrayOutputStream buffer =
                    new ByteArrayOutputStream();

            byte[] chunk = new byte[1024];

            int count;

            while ((count = input.read(chunk)) != -1) {

                buffer.write(chunk, 0, count);
            }

            encryptedData = buffer.toByteArray();
        }

        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
        );

        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(GCM_TAG_BITS, iv)
        );

        byte[] decryptedData = cipher.doFinal(encryptedData);

        if (decryptedData.length < 8) {

            throw new IOException(
                    "Archivo biométrico incompleto"
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(decryptedData);

        buffer.order(ByteOrder.BIG_ENDIAN);

        int version = buffer.getInt();

        if (version != FORMAT_VERSION) {

            throw new IOException(
                    "Versión de plantilla no compatible"
            );
        }

        int templateCount = buffer.getInt();

        if (templateCount < 1 ||
                templateCount > MAX_TEMPLATES) {

            throw new IOException(
                    "Cantidad de plantillas inválida"
            );
        }

        int expectedSize =
                8 + templateCount * VECTOR_SIZE * Float.BYTES;

        if (decryptedData.length != expectedSize) {

            throw new IOException(
                    "Tamaño de las plantillas incorrecto"
            );
        }

        float[][] embeddings =
                new float[templateCount][VECTOR_SIZE];

        for (int i = 0; i < templateCount; i++) {

            for (int j = 0; j < VECTOR_SIZE; j++) {

                float value = buffer.getFloat();

                if (Float.isNaN(value) ||
                        Float.isInfinite(value)) {

                    throw new IOException(
                            "Se encontró un valor biométrico inválido"
                    );
                }

                embeddings[i][j] = value;
            }
        }

        return embeddings;
    }


    // Recuperar la primera plantilla.
    // Permite que CameraActivity siga funcionando mientras
    // implementamos la captura guiada de tres poses.
    public synchronized float[] loadTemplate()
            throws GeneralSecurityException, IOException {

        float[][] embeddings = loadTemplates();

        return embeddings[0];
    }


    // Consultar si existe algún registro.
    public synchronized boolean hasTemplate() {

        return templateFile.getBaseFile().exists();
    }


    // Consultar cantidad de plantillas.
    public synchronized int getTemplateCount()
            throws GeneralSecurityException, IOException {

        if (!hasTemplate()) {
            return 0;
        }

        return loadTemplates().length;
    }


    // Eliminar plantillas y clave del perfil actual.
    public synchronized void deleteTemplate()
            throws GeneralSecurityException, IOException {

        templateFile.delete();

        KeyStore keyStore = KeyStore.getInstance(
                "AndroidKeyStore"
        );

        keyStore.load(null);

        if (keyStore.containsAlias(keyAlias)) {

            keyStore.deleteEntry(keyAlias);
        }
    }


    // =====================================================
    // MIGRACIÓN DE LA PLANTILLA DEL SISTEMA ANTERIOR
    // =====================================================

    public static synchronized boolean migrateLegacyTemplate(
            Context context,
            String profileId
    ) throws Exception {

        File legacyFile = new File(
                context.getFilesDir(),
                LEGACY_FILE_NAME
        );

        AtomicFile legacyAtomic = new AtomicFile(legacyFile);

        if (!legacyFile.exists()) {
            return false;
        }

        SecureTemplateRepository newRepository =
                new SecureTemplateRepository(
                        context,
                        profileId
                );

        // Nunca sobrescribir una plantilla que ya tenga el perfil.
        if (newRepository.hasTemplate()) {
            return false;
        }

        KeyStore keyStore = KeyStore.getInstance(
                "AndroidKeyStore"
        );

        keyStore.load(null);

        if (!keyStore.containsAlias(LEGACY_KEY_ALIAS)) {
            return false;
        }

        SecretKey legacyKey = (SecretKey) keyStore.getKey(
                LEGACY_KEY_ALIAS,
                null
        );

        byte[] iv;
        byte[] encryptedData;

        try (
                DataInputStream input =
                        new DataInputStream(legacyAtomic.openRead())
        ) {

            int ivLength = input.readInt();

            if (ivLength != IV_LENGTH) {

                throw new IOException(
                        "Plantilla antigua inválida"
                );
            }

            iv = new byte[ivLength];

            input.readFully(iv);

            ByteArrayOutputStream buffer =
                    new ByteArrayOutputStream();

            byte[] chunk = new byte[1024];

            int count;

            while ((count = input.read(chunk)) != -1) {

                buffer.write(chunk, 0, count);
            }

            encryptedData = buffer.toByteArray();
        }

        // Descifrar utilizando la antigua clave.
        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
        );

        cipher.init(
                Cipher.DECRYPT_MODE,
                legacyKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv)
        );

        byte[] decryptedData = cipher.doFinal(encryptedData);

        if (decryptedData.length !=
                VECTOR_SIZE * Float.BYTES) {

            throw new IOException(
                    "Tamaño de plantilla antigua incorrecto"
            );
        }

        ByteBuffer buffer = ByteBuffer.wrap(decryptedData);

        buffer.order(ByteOrder.BIG_ENDIAN);

        float[] embedding = new float[VECTOR_SIZE];

        for (int i = 0; i < VECTOR_SIZE; i++) {

            embedding[i] = buffer.getFloat();

            if (Float.isNaN(embedding[i]) ||
                    Float.isInfinite(embedding[i])) {

                throw new IOException(
                        "La plantilla antigua contiene valores inválidos"
                );
            }
        }

        // Cifrar la plantilla usando la nueva clave del perfil.
        newRepository.saveTemplate(embedding);

        // Verificar que se pueda recuperar correctamente.
        float[] recovered = newRepository.loadTemplate();

        if (!Arrays.equals(embedding, recovered)) {

            throw new IOException(
                    "No se pudo verificar la migración biométrica"
            );
        }

        // Eliminar el archivo y la clave antiguos solamente
        // después de confirmar que la nueva copia funciona.
        legacyAtomic.delete();

        keyStore.deleteEntry(LEGACY_KEY_ALIAS);

        android.util.Log.i(
                "BioVerifyAI",
                "Plantilla antigua migrada correctamente al perfil " +
                        profileId
        );

        return true;
    }
}