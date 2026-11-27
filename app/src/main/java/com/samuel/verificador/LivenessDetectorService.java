package com.samuel.verificador;

import android.media.Image;

import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class LivenessDetectorService {

    private final FaceDetector detector;

    private final AtomicBoolean processing =
            new AtomicBoolean(false);


    public interface Callback {

        void onFacesDetected(List<Face> faces);

        void onError(Exception error);
    }


    public LivenessDetectorService() {

        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()

                        .setPerformanceMode(
                                FaceDetectorOptions.PERFORMANCE_MODE_FAST
                        )

                        .setLandmarkMode(
                                FaceDetectorOptions.LANDMARK_MODE_NONE
                        )

                        .setClassificationMode(
                                FaceDetectorOptions.CLASSIFICATION_MODE_ALL
                        )

                        .enableTracking()

                        .setMinFaceSize(0.15f)

                        .build();

        detector = FaceDetection.getClient(options);
    }


    public void processFrame(
            ImageProxy imageProxy,
            Callback callback
    ) {

        // Evitar analizar varios fotogramas simultáneamente.
        if (!processing.compareAndSet(false, true)) {

            imageProxy.close();

            return;
        }


        Image mediaImage = imageProxy.getImage();


        if (mediaImage == null) {

            processing.set(false);

            imageProxy.close();

            callback.onError(
                    new IllegalStateException(
                            "No se pudo obtener el fotograma"
                    )
            );

            return;
        }


        try {

            InputImage inputImage = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees()
            );


            detector.process(inputImage)

                    .addOnSuccessListener(callback::onFacesDetected)

                    .addOnFailureListener(callback::onError)

                    .addOnCompleteListener(task -> {

                        imageProxy.close();

                        processing.set(false);
                    });


        } catch (Exception e) {

            imageProxy.close();

            processing.set(false);

            callback.onError(e);
        }
    }


    public void close() {

        detector.close();
    }
}