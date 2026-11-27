package com.samuel.verificador;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;

import androidx.exifinterface.media.ExifInterface;

import com.google.mlkit.vision.face.Face;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import android.graphics.Color;
import android.graphics.PointF;

import com.google.mlkit.vision.face.FaceLandmark;

public class FaceImageProcessor {

    // Margen adicional alrededor del rostro.
    private static final float PADDING_FACTOR = 1.35f;

    // 1. Recortar el rostro de una fotografía.
    public Bitmap cropFace(File imageFile, Face face)
            throws IOException {

        // Leer la fotografía original.
        Bitmap original = BitmapFactory.decodeFile(
                imageFile.getAbsolutePath()
        );

        if (original == null) {
            throw new IOException("No se pudo cargar la fotografía");
        }

        // Corregir la orientación mediante los metadatos EXIF.
        Bitmap upright = correctOrientation(original, imageFile);

        if (upright != original) {
            original.recycle();
        }

        int imageWidth = upright.getWidth();
        int imageHeight = upright.getHeight();

        // Obtener las coordenadas del rostro detectado por ML Kit.
        Rect faceBounds = new Rect(face.getBoundingBox());

        // Evitar coordenadas fuera de la imagen.
        faceBounds.intersect(
                0,
                0,
                imageWidth,
                imageHeight
        );

        if (faceBounds.isEmpty()) {
            throw new IOException("El rostro está fuera de la imagen");
        }

        // Calcular el centro del rostro.
        float centerX = faceBounds.centerX();
        float centerY = faceBounds.centerY();

        // Calcular un recorte cuadrado con margen.
        int side = (int) Math.ceil(
                Math.max(faceBounds.width(), faceBounds.height())
                        * PADDING_FACTOR
        );

        // Evitar que el recorte supere las dimensiones disponibles.
        side = Math.min(side, Math.min(imageWidth, imageHeight));

        // Calcular la posición del recorte.
        int left = Math.round(centerX - side / 2f);
        int top = Math.round(centerY - side / 2f);

        // Mantener el recorte dentro de la fotografía.
        left = Math.max(0, Math.min(left, imageWidth - side));
        top = Math.max(0, Math.min(top, imageHeight - side));

        // Extraer el rostro.
        return Bitmap.createBitmap(
                upright,
                left,
                top,
                side,
                side
        );
    }

    // 2. Corregir la orientación de la fotografía.
    private Bitmap correctOrientation(
            Bitmap bitmap,
            File imageFile
    ) throws IOException {

        ExifInterface exif = new ExifInterface(
                imageFile.getAbsolutePath()
        );

        int rotation = exif.getRotationDegrees();

        if (rotation == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);

        return Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );
    }

    // 3. Guardar el rostro recortado como JPEG.
    public void saveBitmap(Bitmap bitmap, File destination)
            throws IOException {

        File parent = destination.getParentFile();

        if (parent != null && !parent.exists()
                && !parent.mkdirs()) {
            throw new IOException("No se pudo crear el directorio");
        }

        try (FileOutputStream output =
                     new FileOutputStream(destination)) {

            boolean saved = bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    output
            );

            if (!saved) {
                throw new IOException("No se pudo guardar el recorte");
            }
        }
    }
    // ========================================
// ALINEACIÓN FACIAL DE CINCO PUNTOS
// ========================================

    public Bitmap alignFace112(File imageFile, Face face)
            throws IOException {

        // Cargar fotografía original.
        Bitmap original = BitmapFactory.decodeFile(
                imageFile.getAbsolutePath()
        );

        if (original == null) {
            throw new IOException("No se pudo cargar la fotografía");
        }

        // Corregir orientación EXIF.
        Bitmap upright = correctOrientation(original, imageFile);

        if (upright != original) {
            original.recycle();
        }

        try {

            // Obtener los cinco puntos faciales.
            PointF eye1 = getLandmarkPosition(
                    face, FaceLandmark.LEFT_EYE
            );

            PointF eye2 = getLandmarkPosition(
                    face, FaceLandmark.RIGHT_EYE
            );

            PointF nose = getLandmarkPosition(
                    face, FaceLandmark.NOSE_BASE
            );

            PointF mouth1 = getLandmarkPosition(
                    face, FaceLandmark.MOUTH_LEFT
            );

            PointF mouth2 = getLandmarkPosition(
                    face, FaceLandmark.MOUTH_RIGHT
            );

            // Ordenar ojos de izquierda a derecha en la imagen.
            PointF[] eyes = sortByX(eye1, eye2);

            // Ordenar comisuras de izquierda a derecha.
            PointF[] mouth = sortByX(mouth1, mouth2);

            // Coordenadas originales.
            PointF[] sourcePoints = {
                    eyes[0],
                    eyes[1],
                    nose,
                    mouth[0],
                    mouth[1]
            };

            /*
             * Plantilla de referencia de Qualcomm.
             * Configuración:
             * 112 x 112
             * inner_padding_factor = 0.25
             * default_square = true
             */

            float[][] targetPoints = {
                    {44.1964f, 53.1309f},
                    {67.6879f, 53.0009f},
                    {56.0168f, 66.4911f},
                    {46.3662f, 80.2437f},
                    {65.8199f, 80.1361f}
            };

            // Calcular transformación geométrica.
            double[] transform = estimateSimilarityTransform(
                    sourcePoints,
                    targetPoints
            );

            double a = transform[0];
            double b = transform[1];
            double tx = transform[2];
            double ty = transform[3];

            double denominator = a * a + b * b;

            // Crear imagen de salida.
            int outputSize = 112;

            Bitmap aligned = Bitmap.createBitmap(
                    outputSize,
                    outputSize,
                    Bitmap.Config.ARGB_8888
            );

            int[] pixels = new int[outputSize * outputSize];

            // Transformación inversa: destino -> origen.
            for (int y = 0; y < outputSize; y++) {

                for (int x = 0; x < outputSize; x++) {

                    double dx = x - tx;
                    double dy = y - ty;

                    double sourceX =
                            (a * dx + b * dy) / denominator;

                    double sourceY =
                            (-b * dx + a * dy) / denominator;

                    pixels[y * outputSize + x] =
                            sampleBilinear(
                                    upright,
                                    (float) sourceX,
                                    (float) sourceY
                            );
                }
            }

            aligned.setPixels(
                    pixels,
                    0,
                    outputSize,
                    0,
                    0,
                    outputSize,
                    outputSize
            );

            return aligned;

        } finally {

            upright.recycle();
        }
    }


    // Obtener un punto facial.
    private PointF getLandmarkPosition(
            Face face,
            int landmarkType
    ) throws IOException {

        FaceLandmark landmark =
                face.getLandmark(landmarkType);

        if (landmark == null) {
            throw new IOException(
                    "No se detectaron todos los puntos faciales"
            );
        }

        return landmark.getPosition();
    }


    // Ordenar dos puntos horizontalmente.
    private PointF[] sortByX(PointF p1, PointF p2) {

        if (p1.x <= p2.x) {
            return new PointF[]{p1, p2};
        }

        return new PointF[]{p2, p1};
    }


    // Calcular transformación de similitud.
    private double[] estimateSimilarityTransform(
            PointF[] source,
            float[][] target
    ) throws IOException {

        double sourceMeanX = 0;
        double sourceMeanY = 0;

        double targetMeanX = 0;
        double targetMeanY = 0;

        int n = source.length;

        // Calcular centroides.
        for (int i = 0; i < n; i++) {

            sourceMeanX += source[i].x;
            sourceMeanY += source[i].y;

            targetMeanX += target[i][0];
            targetMeanY += target[i][1];
        }

        sourceMeanX /= n;
        sourceMeanY /= n;

        targetMeanX /= n;
        targetMeanY /= n;

        double numeratorA = 0;
        double numeratorB = 0;
        double denominator = 0;

        for (int i = 0; i < n; i++) {

            double sx = source[i].x - sourceMeanX;
            double sy = source[i].y - sourceMeanY;

            double dx = target[i][0] - targetMeanX;
            double dy = target[i][1] - targetMeanY;

            numeratorA += sx * dx + sy * dy;

            numeratorB += sx * dy - sy * dx;

            denominator += sx * sx + sy * sy;
        }

        if (denominator < 1e-10) {
            throw new IOException(
                    "Coordenadas faciales inválidas"
            );
        }

        double a = numeratorA / denominator;
        double b = numeratorB / denominator;

        double tx = targetMeanX -
                (a * sourceMeanX - b * sourceMeanY);

        double ty = targetMeanY -
                (b * sourceMeanX + a * sourceMeanY);

        if (a * a + b * b < 1e-12) {
            throw new IOException(
                    "Transformación facial inválida"
            );
        }

        return new double[]{a, b, tx, ty};
    }


    // Interpolación bilineal de píxeles.
    private int sampleBilinear(
            Bitmap bitmap,
            float x,
            float y
    ) {

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        if (x < 0 || y < 0 ||
                x > width - 1 || y > height - 1) {

            return Color.BLACK;
        }

        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);

        int x1 = Math.min(x0 + 1, width - 1);
        int y1 = Math.min(y0 + 1, height - 1);

        float fx = x - x0;
        float fy = y - y0;

        int c00 = bitmap.getPixel(x0, y0);
        int c10 = bitmap.getPixel(x1, y0);
        int c01 = bitmap.getPixel(x0, y1);
        int c11 = bitmap.getPixel(x1, y1);

        int red = interpolate(
                Color.red(c00),
                Color.red(c10),
                Color.red(c01),
                Color.red(c11),
                fx, fy
        );

        int green = interpolate(
                Color.green(c00),
                Color.green(c10),
                Color.green(c01),
                Color.green(c11),
                fx, fy
        );

        int blue = interpolate(
                Color.blue(c00),
                Color.blue(c10),
                Color.blue(c01),
                Color.blue(c11),
                fx, fy
        );

        return Color.rgb(red, green, blue);
    }


    // Interpolar un canal de color.
    private int interpolate(
            int c00,
            int c10,
            int c01,
            int c11,
            float fx,
            float fy
    ) {

        float top = c00 * (1 - fx) + c10 * fx;

        float bottom = c01 * (1 - fx) + c11 * fx;

        return Math.round(
                top * (1 - fy) + bottom * fy
        );
    }
}