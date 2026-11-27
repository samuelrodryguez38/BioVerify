package com.samuel.verificador;

import com.google.mlkit.vision.face.Face;

import java.security.SecureRandom;
import java.util.List;

public class LivenessChallenge {

    private enum ChallengeType {
        BLINK,
        TURN_POSITIVE,
        TURN_NEGATIVE
    }

    private ChallengeType challenge;

    private final SecureRandom random = new SecureRandom();

    private boolean active = false;

    private long deadline;

    private int stage = 0;

    private int stableFrames = 0;

    private static final long TIMEOUT_MS = 15000;

    private static final float OPEN_THRESHOLD = 0.65f;

    private static final float CLOSED_THRESHOLD = 0.30f;

    private static final float NEUTRAL_ANGLE = 15f;

    private static final float TURN_ANGLE = 12f;

    private static final float MAX_TURN_ANGLE = 60f;


    public static class Update {

        public final String message;

        public final boolean finished;

        public final boolean passed;

        public Update(
                String message,
                boolean finished,
                boolean passed
        ) {
            this.message = message;
            this.finished = finished;
            this.passed = passed;
        }
    }


    // Iniciar un desafío aleatorio.
    public synchronized String start() {

        int selection = random.nextInt(3);

        switch (selection) {

            case 0:

                challenge = ChallengeType.BLINK;

                break;

            case 1:

                challenge = ChallengeType.TURN_POSITIVE;

                break;

            default:

                challenge = ChallengeType.TURN_NEGATIVE;

                break;
        }

        active = true;
        android.util.Log.i(
                "BioVerifyDebug",
                "DESAFIO SELECCIONADO: " + challenge
        );

        deadline = System.currentTimeMillis() + TIMEOUT_MS;

        stage = 0;

        stableFrames = 0;


        switch (challenge) {

            case BLINK:

                return "PRUEBA DE VIDA\n\n" +
                        "Parpadea una vez y vuelve a abrir los ojos.";

            case TURN_POSITIVE:

                return "PRUEBA DE VIDA\n\n" +
                        "Mira al frente y después gira suavemente " +
                        "la cabeza hacia tu derecha.";

            case TURN_NEGATIVE:

                return "PRUEBA DE VIDA\n\n" +
                        "Mira al frente y después gira suavemente " +
                        "la cabeza hacia tu izquierda.";
        }

        return "Iniciando prueba de vida...";
    }


    // Analizar cada rostro detectado en tiempo real.
    public synchronized Update process(
            List<Face> faces,
            long currentTime
    ) {

        if (!active) {

            return new Update(
                    "No hay ningún desafío activo.",
                    true,
                    false
            );
        }


        // Comprobar tiempo límite.
        if (currentTime >= deadline) {

            active = false;

            return new Update(
                    "PRUEBA DE VIDA NO COMPLETADA\n\n" +
                            "Se agotó el tiempo. Inténtalo nuevamente.",
                    true,
                    false
            );
        }


        // Debe existir exactamente un rostro.
        if (faces == null || faces.isEmpty()) {

            stableFrames = 0;

            return new Update(
                    "No se detecta un rostro.\n" +
                            "Coloca tu cara frente a la cámara.",
                    false,
                    false
            );
        }


        if (faces.size() > 1) {

            stableFrames = 0;

            return new Update(
                    "Se detectaron varios rostros.\n" +
                            "Debe aparecer una sola persona.",
                    false,
                    false
            );
        }


        Face face = faces.get(0);


        float yaw = face.getHeadEulerAngleY();

        float pitch = face.getHeadEulerAngleX();

        float roll = face.getHeadEulerAngleZ();


        // Evitar movimientos excesivos de inclinación.
        if (Math.abs(pitch) > 20 ||
                Math.abs(roll) > 20) {

            stableFrames = 0;

            return new Update(
                    "Mantén la cabeza recta.",
                    false,
                    false
            );
        }


        switch (challenge) {

            case BLINK:

                return processBlink(face, yaw);


            case TURN_POSITIVE:

                return processTurn(yaw, true);


            case TURN_NEGATIVE:

                return processTurn(yaw, false);
        }


        return new Update(
                "Analizando...",
                false,
                false
        );
    }


    // Detectar secuencia: ojos abiertos -> cerrados -> abiertos.
    private Update processBlink(Face face, float yaw) {

        if (Math.abs(yaw) > 18) {

            stableFrames = 0;

            return new Update(
                    "Mira directamente a la cámara para parpadear.",
                    false,
                    false
            );
        }


        Float leftEye = face.getLeftEyeOpenProbability();

        Float rightEye = face.getRightEyeOpenProbability();


        if (leftEye == null || rightEye == null) {

            return new Update(
                    "No se puede analizar la apertura de los ojos.\n" +
                            "Mejora la iluminación y mira al frente.",
                    false,
                    false
            );
        }


        boolean eyesOpen =
                leftEye >= OPEN_THRESHOLD &&
                        rightEye >= OPEN_THRESHOLD;

        boolean eyesClosed =
                leftEye <= CLOSED_THRESHOLD &&
                        rightEye <= CLOSED_THRESHOLD;


        // Etapa 0: comprobar ojos abiertos.
        if (stage == 0) {

            if (eyesOpen) {

                stableFrames++;

                if (stableFrames >= 2) {

                    stage = 1;

                    stableFrames = 0;

                    return new Update(
                            "Ahora cierra los ojos y vuelve a abrirlos.",
                            false,
                            false
                    );
                }

            } else {

                stableFrames = 0;
            }
        }


        // Etapa 1: detectar cierre de ojos.
        else if (stage == 1) {

            if (eyesClosed) {

                stage = 2;

                stableFrames = 0;

                return new Update(
                        "¡Bien! Ahora abre los ojos.",
                        false,
                        false
                );
            }
        }


        // Etapa 2: comprobar reapertura.
        else if (stage == 2) {

            if (eyesOpen) {

                stableFrames++;

                if (stableFrames >= 2) {

                    active = false;

                    return new Update(
                            "PRUEBA DE VIDA SUPERADA",
                            true,
                            true
                    );
                }

            } else {

                stableFrames = 0;
            }
        }


        if (stage == 0) {

            return new Update(
                    "Mantén los ojos abiertos.",
                    false,
                    false
            );
        }

        if (stage == 1) {

            return new Update(
                    "Parpadea ahora.",
                    false,
                    false
            );
        }

        return new Update(
                "Abre nuevamente los ojos.",
                false,
                false
        );
    }


    // Detectar secuencia: frente -> giro -> frente.
    private Update processTurn(
            float yaw,
            boolean positiveDirection
    ) {

        float targetYaw = positiveDirection
                ? yaw
                : -yaw;
        android.util.Log.d(
                "BioVerifyDebug",
                "ETAPA: " + stage +
                        " | YAW: " + yaw +
                        " | TARGET: " + targetYaw +
                        " | DIRECCION POSITIVA: " + positiveDirection +
                        " | FRAMES: " + stableFrames
        );


        // Etapa 0: posición frontal.
        if (stage == 0) {

            if (Math.abs(yaw) <= NEUTRAL_ANGLE) {

                stableFrames++;

                if (stableFrames >= 2) {

                    stage = 1;

                    stableFrames = 0;

                    return new Update(
                            "Gira suavemente la cabeza hacia " +
                                    (positiveDirection
                                            ? "la derecha."
                                            : "la izquierda."),
                            false,
                            false
                    );
                }

            } else {

                stableFrames = 0;
            }
        }


        // Etapa 1: comprobar el giro solicitado.
        else if (stage == 1) {

            if (targetYaw >= TURN_ANGLE &&
                    targetYaw <= MAX_TURN_ANGLE) {

                stableFrames++;

                if (stableFrames >= 2) {

                    stage = 2;

                    stableFrames = 0;

                    return new Update(
                            "Perfecto. Ahora vuelve a mirar al frente.",
                            false,
                            false
                    );
                }

            } else {

                stableFrames = 0;
            }
        }


        // Etapa 2: regresar al frente.
        else if (stage == 2) {

            if (Math.abs(yaw) <= NEUTRAL_ANGLE) {

                stableFrames++;

                if (stableFrames >= 2) {

                    active = false;

                    return new Update(
                            "PRUEBA DE VIDA SUPERADA",
                            true,
                            true
                    );
                }

            } else {

                stableFrames = 0;
            }
        }


        if (stage == 0) {

            return new Update(
                    "Mira directamente a la cámara.",
                    false,
                    false
            );
        }

        if (stage == 1) {

            return new Update(
                    "Gira suavemente la cabeza hacia " +
                            (positiveDirection
                                    ? "la izquierda."
                                    : "la derecha."),
                    false,
                    false
            );
        }

        return new Update(
                "Vuelve a mirar directamente a la cámara.",
                false,
                false
        );
    }


    public synchronized boolean isActive() {
        return active;
    }
}