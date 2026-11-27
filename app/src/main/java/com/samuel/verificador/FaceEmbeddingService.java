package com.samuel.verificador;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class FaceEmbeddingService {

    private static final String TAG = "BioVerifyAI";

    private static final String MODEL_FILE =
            "mobilefacenet.tflite";

    private final ByteBuffer modelBuffer;
    private final Interpreter interpreter;


    // =========================================
    // CONSTRUCTOR
    // =========================================

    public FaceEmbeddingService(Context context)
            throws IOException {

        modelBuffer = loadModel(context.getAssets());

        Interpreter.Options options =
                new Interpreter.Options();

        options.setNumThreads(2);

        interpreter = new Interpreter(
                modelBuffer,
                options
        );

        Log.i(TAG, "MobileFaceNet cargado correctamente");
    }


    // =========================================
    // CARGAR MODELO
    // =========================================

    private ByteBuffer loadModel(AssetManager assets)
            throws IOException {

        try (
                InputStream input = assets.open(MODEL_FILE);
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream()
        ) {

            byte[] buffer = new byte[4096];

            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }

            byte[] modelBytes = output.toByteArray();

            ByteBuffer byteBuffer =
                    ByteBuffer.allocateDirect(modelBytes.length);

            byteBuffer.order(ByteOrder.nativeOrder());

            byteBuffer.put(modelBytes);

            byteBuffer.rewind();

            return byteBuffer;
        }
    }


    // =========================================
    // PREPARAR IMAGEN PARA MOBILEFACENET
    // =========================================

    private float[][][][] bitmapToInput(Bitmap source) {

        Bitmap resized = Bitmap.createScaledBitmap(
                source,
                112,
                112,
                true
        );

        float[][][][] input = new float[1][3][112][112];

        for (int y = 0; y < 112; y++) {

            for (int x = 0; x < 112; x++) {

                int pixel = resized.getPixel(x, y);

                input[0][0][y][x] =
                        Color.red(pixel) / 255.0f;

                input[0][1][y][x] =
                        Color.green(pixel) / 255.0f;

                input[0][2][y][x] =
                        Color.blue(pixel) / 255.0f;
            }
        }

        if (resized != source) {
            resized.recycle();
        }

        return input;
    }


    // =========================================
    // EXTRAER DOS EMBEDDINGS
    // =========================================

    public synchronized float[][] extractPair(
            Bitmap firstFace,
            Bitmap secondFace) {

        if (firstFace == null || secondFace == null ||
                firstFace.isRecycled() ||
                secondFace.isRecycled()) {

            throw new IllegalArgumentException(
                    "Las imágenes faciales no son válidas"
            );
        }

        // Preparar las dos entradas.
        float[][][][] img1 = bitmapToInput(firstFace);

        float[][][][] img2 = bitmapToInput(secondFace);

        // Reservar espacio para los resultados.
        float[][] embeddings = new float[2][128];

        Map<Integer, Object> outputs = new HashMap<>();

        outputs.put(0, embeddings);

        // Ejecutar MobileFaceNet.
        interpreter.runForMultipleInputsOutputs(
                new Object[]{img1, img2},
                outputs
        );

        // Normalizar los dos vectores.
        normalizeL2(embeddings[0]);

        normalizeL2(embeddings[1]);

        return embeddings;
    }


    // =========================================
    // NORMALIZACIÓN L2
    // =========================================

    private void normalizeL2(float[] vector) {

        double sum = 0;

        for (float value : vector) {
            sum += value * value;
        }

        double norm = Math.sqrt(sum);

        if (norm < 1e-12) {

            throw new IllegalStateException(
                    "El embedding obtenido es inválido"
            );
        }

        for (int i = 0; i < vector.length; i++) {

            vector[i] /= (float) norm;
        }
    }


    // =========================================
    // INFORMACIÓN DE LOS TENSORES
    // =========================================

    public String getTensorInfo() {

        StringBuilder info = new StringBuilder();

        info.append("ENTRADAS:\n");

        for (int i = 0;
             i < interpreter.getInputTensorCount();
             i++) {

            Tensor tensor = interpreter.getInputTensor(i);

            info.append("Entrada ")
                    .append(i)
                    .append(": ")
                    .append(tensor.name())
                    .append(" ")
                    .append(Arrays.toString(tensor.shape()))
                    .append(" ")
                    .append(tensor.dataType())
                    .append("\n");
        }

        info.append("\nSALIDAS:\n");

        for (int i = 0;
             i < interpreter.getOutputTensorCount();
             i++) {

            Tensor tensor = interpreter.getOutputTensor(i);

            info.append("Salida ")
                    .append(i)
                    .append(": ")
                    .append(tensor.name())
                    .append(" ")
                    .append(Arrays.toString(tensor.shape()))
                    .append(" ")
                    .append(tensor.dataType())
                    .append("\n");
        }

        Log.i(TAG, info.toString());

        return info.toString();
    }


    // =========================================
    // LIBERAR RECURSOS
    // =========================================

    public synchronized void close() {

        interpreter.close();

        Log.i(TAG, "MobileFaceNet cerrado");
    }
}