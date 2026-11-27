package com.samuel.verificador;

import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.face.Face;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;
import android.graphics.Bitmap;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.camera.core.ImageAnalysis;

public class CameraActivity extends ComponentActivity {

    // Componentes de la interfaz
    private PreviewView viewFinder;
    private Button btnTakePhoto;
    private TextView tvStatus;

    // Componentes de la cámara
    private ImageCapture imageCapture;
    // Análisis facial en tiempo real.
    private ImageAnalysis imageAnalysis;

    private LivenessDetectorService livenessDetectorService;

    private LivenessChallenge livenessChallenge;

    private final ExecutorService livenessExecutor =
            Executors.newSingleThreadExecutor();

    private volatile boolean livenessInProgress = false;

    private String lastLivenessMessage = "";

    // Servicio de detección facial
    private FaceDetectionService faceDetectionService;
    private FaceImageProcessor faceImageProcessor;

    // Solicitud de permiso
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private final ExecutorService imageProcessingExecutor =
            Executors.newSingleThreadExecutor();
    private FaceEmbeddingService faceEmbeddingService;
    private Button btnVerifyPhoto;
    private Button btnLogout;

    private SecureTemplateRepository templateRepository;
    private String currentProfileId;

    private boolean registrationMode = true;

    private BiometricAttemptManager biometricAttemptManager;

    private final Handler biometricLockoutHandler =
            new Handler(Looper.getMainLooper());
    // ======================================
// REGISTRO BIOMÉTRICO DE TRES POSES
// ======================================

    private int registrationStep = 0;

    private boolean enrollmentStarted = false;

    private boolean captureInProgress = false;

    // Tres embeddings de 128 componentes.
    private final float[][] pendingEmbeddings = new float[3][];

    // Límites iniciales de orientación.
    private static final float FRONT_MAX_YAW = 12f;

    private static final float TURN_MIN_YAW = 18f;

    private static final float TURN_MAX_YAW = 35f;
    private long livenessStartMs = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);

        // Inicializar componentes
        viewFinder = findViewById(R.id.viewFinder);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
        tvStatus = findViewById(R.id.tvStatus);
        btnVerifyPhoto = findViewById(R.id.btnVerifyPhoto);
        btnLogout = findViewById(R.id.btnLogout);

// Recuperar el perfil autenticado.
        currentProfileId = getIntent().getStringExtra(
                AuthManager.EXTRA_PROFILE_ID
        );


// Comprobar que exista un identificador válido.
        if (currentProfileId == null ||
                currentProfileId.trim().isEmpty() ||
                AuthManager.LEGACY_PROFILE_ID.equals(currentProfileId)) {

            tvStatus.setText(
                    "Error: no se pudo identificar el perfil."
            );

            finish();

            return;
        }


// Inicializar contador de intentos del perfil.
        biometricAttemptManager =
                new BiometricAttemptManager(
                        getApplicationContext(),
                        currentProfileId
                );

// Identificar el perfil más antiguo.
        ProfileManager manager = new ProfileManager(this);

        String oldestProfileId = manager.getOldestProfileId();


// Migrar la plantilla anterior únicamente al primer perfil.
        if (currentProfileId.equals(oldestProfileId)) {

            try {

                boolean migrated =
                        SecureTemplateRepository.migrateLegacyTemplate(
                                this,
                                currentProfileId
                        );

                if (migrated) {

                    android.util.Log.i(
                            "BioVerifyAI",
                            "Plantilla anterior recuperada para el primer perfil."
                    );
                }

            } catch (Exception e) {

                android.util.Log.e(
                        "BioVerifyAI",
                        "No se pudo migrar la plantilla antigua.",
                        e
                );
            }
        }


// Crear repositorio individual del perfil autenticado.
        templateRepository = new SecureTemplateRepository(
                getApplicationContext(),
                currentProfileId
        );


        int templateCount = 0;

        try {

            templateCount = templateRepository.getTemplateCount();

        } catch (Exception e) {

            android.util.Log.e(
                    "BioVerifyAI",
                    "No se pudo consultar el registro biométrico",
                    e
            );

            if (templateRepository.hasTemplate()) {
                templateCount = 1;
            }
        }


// Permitir completar o actualizar el registro si faltan referencias.
        btnTakePhoto.setVisibility(
                templateCount < 3 ? View.VISIBLE : View.GONE
        );


// Permitir verificar cuando exista al menos una referencia.
        btnVerifyPhoto.setVisibility(
                templateCount > 0 ? View.VISIBLE : View.GONE
        );


        if (templateCount == 0) {

            btnTakePhoto.setText("Registrar rostro (3 poses)");

        } else if (templateCount < 3) {

            btnTakePhoto.setText("Actualizar registro (3 poses)");

        }

        // Crear el detector facial
        faceDetectionService = new FaceDetectionService();
        faceImageProcessor = new FaceImageProcessor();
        livenessDetectorService = new LivenessDetectorService();

        livenessChallenge = new LivenessChallenge();
        // Cargar MobileFaceNet en segundo plano.
        imageProcessingExecutor.execute(() -> {

            try {

                faceEmbeddingService = new FaceEmbeddingService(
                        getApplicationContext()
                );

                // Obtener información de las entradas y salidas.
                String tensorInfo = faceEmbeddingService.getTensorInfo();

                android.util.Log.i(
                        "BioVerifyAI",
                        "MobileFaceNet preparado:\n" + tensorInfo
                );

                runOnUiThread(() -> {

                    if (!isFinishing() && !isDestroyed()) {
                        tvStatus.setText("Modelo de inteligencia artificial cargado");
                    }

                });

            } catch (Exception e) {

                android.util.Log.e(
                        "BioVerifyAI",
                        "Error al cargar MobileFaceNet",
                        e
                );

                runOnUiThread(() -> {

                    if (!isFinishing() && !isDestroyed()) {
                        tvStatus.setText("Error al cargar el modelo neuronal");
                    }

                });
            }
        });

        // Configurar botón
// Registrar identidad.
        btnTakePhoto.setOnClickListener(v -> {

            // Iniciar un registro nuevo únicamente una vez.
            if (!enrollmentStarted) {

                enrollmentStarted = true;

                registrationStep = 0;

                for (int i = 0; i < pendingEmbeddings.length; i++) {
                    pendingEmbeddings[i] = null;
                }
            }

            registrationMode = true;

            updateEnrollmentPrompt();

            takePhoto();
        });

// Verificar identidad.
        btnVerifyPhoto.setOnClickListener(v -> {

            if (biometricAttemptManager.isLockedOut()) {

                updateBiometricLockoutCountdown();

                return;
            }

            if (!templateRepository.hasTemplate()) {

                tvStatus.setText(
                        "Primero debes registrar un rostro."
                );

                return;
            }

            registrationMode = false;

            startLivenessChallenge();
        });
        btnLogout.setOnClickListener(v -> {

            Intent intent = new Intent(
                    CameraActivity.this,
                    ProfileSelectionActivity.class
            );

            intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
            );

            startActivity(intent);

            finish();
        });


        // Solicitar permiso de cámara
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        startCamera();
                    } else {
                        tvStatus.setText("Permiso de cámara denegado");
                    }
                }
        );

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {

            startCamera();

        } else {
            requestPermissionLauncher.launch(
                    Manifest.permission.CAMERA
            );
        }
    }
    private void updateEnrollmentPrompt() {

        switch (registrationStep) {

            case 0:

                btnTakePhoto.setText("Capturar frontal");

                tvStatus.setText(
                        "REGISTRO FACIAL — PASO 1/3\n\n" +
                                "Mira directamente a la cámara.\n" +
                                "Mantén la cabeza recta y el rostro dentro del óvalo."
                );

                break;


            case 1:

                btnTakePhoto.setText("Capturar izquierda");

                tvStatus.setText(
                        "REGISTRO FACIAL — PASO 2/3\n\n" +
                                "Gira suavemente la cabeza hacia tu izquierda.\n" +
                                "No inclines la cabeza.\n" +
                                "Mantén el rostro dentro del óvalo."
                );

                break;


            case 2:

                btnTakePhoto.setText("Capturar derecha");

                tvStatus.setText(
                        "REGISTRO FACIAL — PASO 3/3\n\n" +
                                "Ahora gira suavemente la cabeza hacia tu derecha.\n" +
                                "Mantén la cabeza recta."
                );

                break;
        }
    }
    private boolean isExpectedPose(Face face, int step) {

        float yaw = face.getHeadEulerAngleY();

        float pitch = face.getHeadEulerAngleX();

        float roll = face.getHeadEulerAngleZ();


        android.util.Log.i(
                "BioVerifyPose",
                "Paso: " + step +
                        " | Yaw: " + yaw +
                        " | Pitch: " + pitch +
                        " | Roll: " + roll
        );


        // Evitar inclinaciones o movimientos verticales excesivos.
        if (Math.abs(pitch) > 15 ||
                Math.abs(roll) > 15) {

            return false;
        }


        switch (step) {

            // Rostro frontal.
            case 0:

                return Math.abs(yaw) <= FRONT_MAX_YAW;


            // Giro hacia la izquierda.
            case 1:

                return yaw >= TURN_MIN_YAW &&
                        yaw <= TURN_MAX_YAW;


            // Giro hacia la derecha.
            case 2:

                return yaw <= -TURN_MIN_YAW &&
                        yaw >= -TURN_MAX_YAW;


            default:

                return false;
        }
    }
    private void finishCaptureAttempt() {

        captureInProgress = false;

        runOnUiThread(() -> {

            if (isFinishing() || isDestroyed()) {
                return;
            }

            if (btnTakePhoto.getVisibility() == View.VISIBLE) {
                btnTakePhoto.setEnabled(true);
            }

            if (btnVerifyPhoto.getVisibility() == View.VISIBLE) {

                boolean locked =
                        biometricAttemptManager != null &&
                                biometricAttemptManager.isLockedOut();

                btnVerifyPhoto.setEnabled(!locked);

                if (locked) {
                    updateBiometricLockoutCountdown();
                }
            }
        });
    }


    // ACTIVAR CÁMARA
    private void startCamera() {

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {

            try {

                ProcessCameraProvider cameraProvider =
                        cameraProviderFuture.get();

// PREVISUALIZACIÓN.
                Preview preview = new Preview.Builder().build();

                preview.setSurfaceProvider(
                        viewFinder.getSurfaceProvider()
                );


// CAPTURA DE FOTOGRAFÍAS.
                imageCapture = new ImageCapture.Builder()

                        .setCaptureMode(
                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        )

                        .build();


// ANÁLISIS EN TIEMPO REAL.
                imageAnalysis = new ImageAnalysis.Builder()

                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )

                        .build();


// Analizador continuo.
                imageAnalysis.setAnalyzer(
                        livenessExecutor,
                        imageProxy -> {

                            // Si no existe un desafío activo, descartar fotograma.
                            if (!livenessInProgress ||
                                    livenessDetectorService == null) {

                                imageProxy.close();

                                return;
                            }


                            livenessDetectorService.processFrame(
                                    imageProxy,

                                    new LivenessDetectorService.Callback() {

                                        @Override
                                        public void onFacesDetected(
                                                List<Face> faces
                                        ) {

                                            if (!livenessInProgress ||
                                                    isFinishing() ||
                                                    isDestroyed()) {

                                                return;
                                            }
                                            if (faces != null && faces.size() == 1) {

                                                Face detected = faces.get(0);

                                                android.util.Log.d(
                                                        "BioVerifyAngles",
                                                        "YAW: " + detected.getHeadEulerAngleY() +
                                                                " | PITCH: " + detected.getHeadEulerAngleX() +
                                                                " | ROLL: " + detected.getHeadEulerAngleZ()
                                                );
                                            }


                                            LivenessChallenge.Update update =
                                                    livenessChallenge.process(
                                                            faces,
                                                            System.currentTimeMillis()
                                                    );


                                            // Evitar actualizar la interfaz innecesariamente.
                                            if (!update.message.equals(
                                                    lastLivenessMessage
                                            )) {

                                                lastLivenessMessage =
                                                        update.message;

                                                tvStatus.setText(
                                                        update.message
                                                );
                                            }


                                            // Desafío finalizado.
                                            if (update.finished) {
                                                long livenessDuration =
                                                        android.os.SystemClock.elapsedRealtime()
                                                                - livenessStartMs;

                                                android.util.Log.i(
                                                        "BioVerifyTiming",
                                                        "LIVENESS_MS=" + livenessDuration +
                                                                " | PASSED=" + update.passed
                                                );

                                                livenessInProgress = false;

                                                btnVerifyPhoto.setEnabled(true);


                                                if (update.passed) {

                                                    tvStatus.setText(
                                                            "PRUEBA DE VIDA SUPERADA\n\n" +
                                                                    "Verificando identidad..."
                                                    );

                                                    registrationMode = false;

                                                    // Continuar inmediatamente con la captura.
                                                    takePhoto();

                                                } else {

                                                    tvStatus.setText(
                                                            update.message
                                                    );
                                                }
                                            }
                                        }


                                        @Override
                                        public void onError(Exception error) {

                                            android.util.Log.e(
                                                    "BioVerifyLiveness",
                                                    "Error analizando fotograma",
                                                    error
                                            );

                                        }
                                    }
                            );
                        }
                );


// CÁMARA FRONTAL.
                CameraSelector cameraSelector =
                        CameraSelector.DEFAULT_FRONT_CAMERA;


// Vincular los tres casos de uso.
                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalysis
                );

                tvStatus.setText("Cámara frontal activada");

            } catch (ExecutionException | InterruptedException e) {

                tvStatus.setText("No se pudo iniciar la cámara");
                e.printStackTrace();

            }

        }, ContextCompat.getMainExecutor(this));
    }
    private void startLivenessChallenge() {

        if (imageAnalysis == null ||
                livenessDetectorService == null ||
                livenessChallenge == null) {

            tvStatus.setText(
                    "El sistema de prueba de vida no está disponible."
            );

            btnVerifyPhoto.setEnabled(true);

            return;
        }


        // Generar desafío aleatorio.
        String instruction = livenessChallenge.start();
        livenessStartMs = android.os.SystemClock.elapsedRealtime();


        livenessInProgress = true;

        lastLivenessMessage = instruction;


        btnVerifyPhoto.setEnabled(false);


        tvStatus.setText(instruction);


        android.util.Log.i(
                "BioVerifyLiveness",
                "Nuevo desafío iniciado: " + instruction
        );
    }


    // TOMAR Y ANALIZAR FOTOGRAFÍA
    private void takePhoto() {

        if (captureInProgress) {
            return;
        }

        if (imageCapture == null) {

            tvStatus.setText(
                    "La cámara todavía no está lista"
            );

            return;
        }

        captureInProgress = true;

        btnTakePhoto.setEnabled(false);

        btnVerifyPhoto.setEnabled(false);

        final boolean isRegistration = registrationMode;

        final int expectedRegistrationStep = registrationStep;

        File photoFile = new File(
                getFilesDir(),
                "captura_" + System.currentTimeMillis() + ".jpg"
        );

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(
                        photoFile
                ).build();
        final long operationStartNs =
                android.os.SystemClock.elapsedRealtimeNanos();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),

                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults outputFileResults) {
                        android.util.Log.i(
                                "BioVerifyTiming",
                                "CAPTURE_SAVE_MS=" +
                                        (android.os.SystemClock.elapsedRealtimeNanos()
                                                - operationStartNs) / 1_000_000
                        );

                        tvStatus.setText(
                                "Fotografía guardada. Analizando rostro..."
                        );
                        final long detectionStartNs =
                                android.os.SystemClock.elapsedRealtimeNanos();
                        faceDetectionService.detectFaces(
                                CameraActivity.this,
                                photoFile,
                                new FaceDetectionService.FaceDetectionCallback() {

                                    @Override
                                    public void onSuccess(List<Face> faces) {
                                        android.util.Log.i(
                                                "BioVerifyTiming",
                                                "FACE_DETECTION_MS=" +
                                                        (android.os.SystemClock.elapsedRealtimeNanos()
                                                                - detectionStartNs) / 1_000_000
                                        );

                                        int cantidad = faces.size();

                                        // CASO 1: No se detectaron rostros.
                                        if (cantidad == 0) {

                                            tvStatus.setText("No se detectaron rostros.");

                                            photoFile.delete();
                                            finishCaptureAttempt();

                                            return;
                                        }

                                        // CASO 2: Se detectaron varios rostros.
                                        if (cantidad > 1) {

                                            tvStatus.setText(
                                                    "Se detectaron " + cantidad +
                                                            " rostros. Debe aparecer una sola persona."
                                            );

                                            photoFile.delete();
                                            finishCaptureAttempt();

                                            return;
                                        }

                                        // CASO 3: Se detectó exactamente un rostro.
                                        tvStatus.setText("Rostro detectado. Procesando...");

                                        Face detectedFace = faces.get(0);
                                        // Comprobar que el rostro tenga suficiente resolución.
                                        if (detectedFace.getBoundingBox().width() < 100 ||
                                                detectedFace.getBoundingBox().height() < 100) {

                                            tvStatus.setText(
                                                    "Acércate un poco más a la cámara."
                                            );

                                            photoFile.delete();

                                            finishCaptureAttempt();

                                            return;
                                        }


// Validar pose únicamente durante el registro.
                                        if (isRegistration &&
                                                !isExpectedPose(
                                                        detectedFace,
                                                        expectedRegistrationStep
                                                )) {

                                            float detectedYaw =
                                                    detectedFace.getHeadEulerAngleY();

                                            String expectedInstruction;

                                            if (expectedRegistrationStep == 0) {

                                                expectedInstruction = "Mira directamente a la cámara.";

                                            } else if (expectedRegistrationStep == 1) {

                                                expectedInstruction = "Gira hacia tu izquierda.";

                                            } else {

                                                expectedInstruction = "Gira hacia tu derecha.";
                                            }

                                            tvStatus.setText(
                                                    "ORIENTACIÓN INCORRECTA\n\n" +
                                                            expectedInstruction +
                                                            "\n\nÁngulo detectado: " +
                                                            Math.round(detectedYaw) + "°"
                                            );

                                            photoFile.delete();

                                            finishCaptureAttempt();

                                            return;
                                        }

                                        imageProcessingExecutor.execute(() -> {

                                            Bitmap faceBitmap = null;

                                            try {

                                                long alignmentStartNs =
                                                        android.os.SystemClock.elapsedRealtimeNanos();

                                                faceBitmap = faceImageProcessor.alignFace112(
                                                        photoFile,
                                                        detectedFace
                                                );

                                                long alignmentMs =
                                                        (android.os.SystemClock.elapsedRealtimeNanos()
                                                                - alignmentStartNs) / 1_000_000;

                                                android.util.Log.i(
                                                        "BioVerifyTiming",
                                                        "ALIGNMENT_MS=" + alignmentMs
                                                );

                                                if (faceEmbeddingService == null) {
                                                    throw new IllegalStateException(
                                                            "MobileFaceNet no está disponible"
                                                    );
                                                }

                                                long inferenceStartNs =
                                                        android.os.SystemClock.elapsedRealtimeNanos();

                                                float[][] embeddings =
                                                        faceEmbeddingService.extractPair(
                                                                faceBitmap,
                                                                faceBitmap
                                                        );

                                                long inferenceMs =
                                                        (android.os.SystemClock.elapsedRealtimeNanos()
                                                                - inferenceStartNs) / 1_000_000;

                                                android.util.Log.i(
                                                        "BioVerifyTiming",
                                                        "MOBILEFACENET_MS=" + inferenceMs
                                                );

                                                float[] currentEmbedding = embeddings[0];



                                                if (isRegistration) {

                                                    if (expectedRegistrationStep < 0 ||
                                                            expectedRegistrationStep >= 3) {

                                                        throw new IllegalStateException(
                                                                "Paso de registro inválido"
                                                        );
                                                    }


                                                    // Guardar temporalmente el embedding actual.
                                                    pendingEmbeddings[expectedRegistrationStep] =
                                                            currentEmbedding.clone();


                                                    int completedStep = expectedRegistrationStep + 1;


                                                    // Todavía faltan capturas.
                                                    if (completedStep < 3) {

                                                        runOnUiThread(() -> {

                                                            if (isFinishing() || isDestroyed()) {
                                                                return;
                                                            }

                                                            registrationStep = completedStep;

                                                            updateEnrollmentPrompt();

                                                            tvStatus.append(
                                                                    "\n\nReferencia " +
                                                                            completedStep +
                                                                            " de 3 procesada correctamente."
                                                            );

                                                        });

                                                    }

                                                    // Ya tenemos las tres referencias.
                                                    else {

                                                        float[][] completedEmbeddings =
                                                                new float[3][128];


                                                        for (int i = 0; i < 3; i++) {

                                                            if (pendingEmbeddings[i] == null) {

                                                                throw new IllegalStateException(
                                                                        "Falta una referencia biométrica"
                                                                );
                                                            }

                                                            completedEmbeddings[i] =
                                                                    pendingEmbeddings[i].clone();
                                                        }


                                                        // Guardar las tres plantillas cifradas.
                                                        templateRepository.saveTemplates(
                                                                completedEmbeddings
                                                        );


                                                        android.util.Log.i(
                                                                "BioVerifyAI",
                                                                "REGISTRO BIOMÉTRICO COMPLETADO: 3 EMBEDDINGS"
                                                        );


                                                        runOnUiThread(() -> {

                                                            if (isFinishing() || isDestroyed()) {
                                                                return;
                                                            }

                                                            registrationStep = 3;

                                                            enrollmentStarted = false;

                                                            tvStatus.setText(
                                                                    "¡REGISTRO COMPLETADO!\n\n" +
                                                                            "Se almacenaron correctamente las tres referencias faciales.\n\n" +
                                                                            "Frontal ✓\n" +
                                                                            "Izquierda ✓\n" +
                                                                            "Derecha ✓"
                                                            );

                                                            btnTakePhoto.setVisibility(View.GONE);

                                                            btnVerifyPhoto.setVisibility(View.VISIBLE);

                                                        });
                                                    }
                                                }


// ==========================================
// VERIFICACIÓN FACIAL 1:1 CON TRES REFERENCIAS
// ==========================================

                                                else {
                                                    long comparisonStartNs =
                                                            android.os.SystemClock.elapsedRealtimeNanos();
                                                    float[][] savedEmbeddings =
                                                            templateRepository.loadTemplates();


                                                    // Buscar la mayor similitud entre las referencias.
                                                    double similarity = -1.0;


                                                    for (float[] savedEmbedding : savedEmbeddings) {

                                                        double currentSimilarity =
                                                                FaceSimilarityService.cosineSimilarity(
                                                                        currentEmbedding,
                                                                        savedEmbedding
                                                                );

                                                        if (currentSimilarity > similarity) {

                                                            similarity = currentSimilarity;
                                                        }
                                                    }

                                                    long comparisonMs =
                                                            (android.os.SystemClock.elapsedRealtimeNanos()
                                                                    - comparisonStartNs) / 1_000_000;

                                                    long totalPipelineMs =
                                                            (android.os.SystemClock.elapsedRealtimeNanos()
                                                                    - operationStartNs) / 1_000_000;

                                                    android.util.Log.i(
                                                            "BioVerifyTiming",
                                                            "COMPARISON_MS=" + comparisonMs
                                                    );

                                                    android.util.Log.i(
                                                            "BioVerifyTiming",
                                                            "TOTAL_PIPELINE_MS=" + totalPipelineMs
                                                    );
                                                    double angle =
                                                            FaceSimilarityService.cosineAngle(similarity);


                                                    double cosineDistance = 1.0 - similarity;


                                                    android.util.Log.i(
                                                            "BioVerifyAI",
                                                            "REFERENCIAS DISPONIBLES: " +
                                                                    savedEmbeddings.length
                                                    );

                                                    android.util.Log.i(
                                                            "BioVerifyAI",
                                                            "MEJOR SIMILITUD: " + similarity
                                                    );

                                                    android.util.Log.i(
                                                            "BioVerifyAI",
                                                            "DISTANCIA COSENO: " + cosineDistance
                                                    );

                                                    android.util.Log.i(
                                                            "BioVerifyAI",
                                                            "ÁNGULO: " + angle
                                                    );


                                                    // Umbral provisional.
                                                    final double MAX_COSINE_DISTANCE = 0.40;

                                                    boolean isMatch =
                                                            cosineDistance <= MAX_COSINE_DISTANCE;


                                                    runOnUiThread(() -> {

                                                        if (isFinishing() || isDestroyed()) {
                                                            return;
                                                        }

                                                        if (isMatch) {

                                                            biometricAttemptManager.resetAttempts();
                                                            biometricLockoutHandler.removeCallbacksAndMessages(null);

                                                            tvStatus.setText(
                                                                    "¡IDENTIDAD VERIFICADA!\n\n" +
                                                                            "La identidad coincide con las referencias registradas."
                                                            );

                                                            enrollmentStarted = false;
                                                            registrationStep = 0;

                                                            btnTakePhoto.setText(
                                                                    "Actualizar referencias (3 poses)"
                                                            );
                                                            btnTakePhoto.setVisibility(View.VISIBLE);
                                                            btnVerifyPhoto.setVisibility(View.GONE);

                                                            registrationMode = true;

                                                        } else {

                                                            int attempts =
                                                                    biometricAttemptManager.registerFailure();

                                                            if (biometricAttemptManager.isLockedOut()) {

                                                                updateBiometricLockoutCountdown();

                                                            } else {

                                                                int remaining = 3 - attempts;

                                                                tvStatus.setText(
                                                                        "IDENTIDAD NO RECONOCIDA\n\n" +
                                                                                "No pudimos confirmar tu identidad.\n" +
                                                                                "Inténtalo nuevamente.\n\n" +
                                                                                "Intentos restantes: " + remaining
                                                                );
                                                            }
                                                        }
                                                    });
                                                }
                                            } catch (Exception e) {

                                                android.util.Log.e(
                                                        "BioVerifyAI",
                                                        "Error en el proceso biométrico",
                                                        e
                                                );

                                                runOnUiThread(() -> {

                                                    if (!isFinishing() && !isDestroyed()) {
                                                        tvStatus.setText(
                                                                "Error durante el procesamiento biométrico"
                                                        );
                                                    }
                                                });

                                            }
                                            finally {

                                                // Liberar memoria.
                                                if (faceBitmap != null &&
                                                        !faceBitmap.isRecycled()) {

                                                    faceBitmap.recycle();
                                                }

                                                // Eliminar fotografía original.
                                                if (photoFile.exists()) {

                                                    photoFile.delete();
                                                }

                                                // Permitir una nueva captura.
                                                finishCaptureAttempt();
                                            }
                                        });
                                    }

                                    @Override
                                    public void onError(Exception e) {

                                        tvStatus.setText(
                                                "Error durante el análisis facial."
                                        );

                                        e.printStackTrace();
                                        finishCaptureAttempt();
                                    }
                                }
                        );
                    }

                    @Override
                    public void onError(
                            @NonNull ImageCaptureException exception) {

                        tvStatus.setText(
                                "Error al guardar la fotografía"
                        );

                        exception.printStackTrace();
                        finishCaptureAttempt();
                    }
                }
        );
    }

    private void updateBiometricLockoutCountdown() {

        biometricLockoutHandler.removeCallbacksAndMessages(null);

        if (biometricAttemptManager == null ||
                !biometricAttemptManager.isLockedOut()) {

            btnVerifyPhoto.setEnabled(true);

            tvStatus.setText(
                    "El bloqueo ha finalizado. Puedes intentarlo nuevamente."
            );

            return;
        }


        long remaining =
                biometricAttemptManager.getRemainingSeconds();


        btnVerifyPhoto.setEnabled(false);


        tvStatus.setText(
                "ACCESO BIOMÉTRICO BLOQUEADO\n\n" +
                        "Se alcanzó el límite de intentos.\n" +
                        "Podrás volver a verificarte en " +
                        remaining + " segundos."
        );


        biometricLockoutHandler.postDelayed(
                this::updateBiometricLockoutCountdown,
                1000
        );
    }
    // LIBERAR RECURSOS
    @Override
    protected void onDestroy() {
        biometricLockoutHandler.removeCallbacksAndMessages(null);
        imageProcessingExecutor.execute(() -> {

            if (faceEmbeddingService != null) {
                livenessInProgress = false;

                if (imageAnalysis != null) {

                    imageAnalysis.clearAnalyzer();

                    imageAnalysis = null;
                }

                livenessExecutor.shutdown();

                if (livenessDetectorService != null) {

                    livenessDetectorService.close();

                    livenessDetectorService = null;
                }
                faceEmbeddingService.close();
                faceEmbeddingService = null;
            }

        });
        super.onDestroy();

        if (faceDetectionService != null) {
            faceDetectionService.close();
        }

        imageProcessingExecutor.shutdown();

    }
}