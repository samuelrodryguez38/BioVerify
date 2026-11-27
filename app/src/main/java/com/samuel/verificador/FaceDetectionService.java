package com.samuel.verificador;

import android.content.Context;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class FaceDetectionService {

    private final FaceDetector faceDetector;

    public interface FaceDetectionCallback {

        void onSuccess(List<Face> faces);

        void onError(Exception e);
    }


    public FaceDetectionService() {

        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()

                        .setPerformanceMode(
                                FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                        )

                        .setLandmarkMode(
                                FaceDetectorOptions.LANDMARK_MODE_ALL
                        )

                        .setClassificationMode(
                                FaceDetectorOptions.CLASSIFICATION_MODE_ALL
                        )

                        .build();

        faceDetector = FaceDetection.getClient(options);
    }


    public void detectFaces(
            Context context,
            File imageFile,
            FaceDetectionCallback callback
    ) {

        try {

            InputImage image = InputImage.fromFilePath(
                    context,
                    Uri.fromFile(imageFile)
            );

            faceDetector.process(image)

                    .addOnSuccessListener(callback::onSuccess)

                    .addOnFailureListener(callback::onError);

        } catch (IOException e) {

            callback.onError(e);
        }
    }


    public void close() {

        faceDetector.close();
    }
}