package com.samuel.verificador;

public class FaceSimilarityService {

    // Comparar dos vectores mediante similitud coseno.
    public static double cosineSimilarity(
            float[] vectorA,
            float[] vectorB) {

        if (vectorA == null || vectorB == null ||
                vectorA.length == 0 ||
                vectorA.length != vectorB.length) {

            throw new IllegalArgumentException(
                    "Los vectores no son compatibles"
            );
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < vectorA.length; i++) {

            dotProduct += vectorA[i] * vectorB[i];

            normA += vectorA[i] * vectorA[i];

            normB += vectorB[i] * vectorB[i];
        }

        if (normA < 1e-12 || normB < 1e-12) {

            throw new IllegalArgumentException(
                    "No se pueden comparar vectores nulos"
            );
        }

        double similarity =
                dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));

        // Evitar errores numéricos fuera del intervalo.
        return Math.max(-1.0, Math.min(1.0, similarity));
    }

    // Convertir similitud coseno a grados.
    public static double cosineAngle(double similarity) {

        double value = Math.max(
                -1.0,
                Math.min(1.0, similarity)
        );

        return Math.toDegrees(Math.acos(value));
    }
}
