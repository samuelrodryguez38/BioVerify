# BioVerify

Aplicación Android de autenticación de usuarios mediante reconocimiento facial y una prueba activa de presencia (liveness). El proyecto fue desarrollado en Java como prototipo académico.

> **Estado:** prototipo funcional en pruebas. Los resultados biométricos y los umbrales deben interpretarse como experimentales; no es un sistema certificado para controlar accesos críticos.

## Funcionalidades

- Gestión de varios perfiles, con información y credenciales independientes.
- Inicio y cierre de sesión mediante contraseña.
- Almacenamiento local cifrado de la información del perfil y de las plantillas faciales.
- Registro facial guiado con hasta tres referencias: frontal, giro a la izquierda y giro a la derecha.
- Detección facial, alineación de rostro y extracción de embeddings mediante MobileFaceNet.
- Comparación de embeddings mediante distancia coseno.
- Prueba activa de presencia con retos aleatorios de parpadeo o movimiento de cabeza.
- Límite de intentos fallidos y bloqueo temporal.

## Tecnologías

- Java 11.
- Android SDK; `minSdk 24`.
- Android CameraX para captura y análisis de imagen.
- ML Kit Face Detection para detección y puntos/ángulos faciales.
- TensorFlow Lite / LiteRT para ejecutar MobileFaceNet en el dispositivo.
- Android Keystore y AES-GCM para proteger datos y plantillas locales.
- PBKDF2-HMAC-SHA256 para derivar el verificador de contraseña.

## Arquitectura general

El código de la aplicación se encuentra en `app/src/main/java/com/samuel/verificador/`.

| Componente | Responsabilidad |
|---|---|
| `CameraActivity` | Coordina el registro y la verificación desde la cámara. |
| `FaceDetectionService` | Detecta rostros en las imágenes capturadas. |
| `FaceImageProcessor` | Recorta, orienta y alinea el rostro para el modelo. |
| `FaceEmbeddingService` | Ejecuta MobileFaceNet y obtiene los embeddings faciales. |
| `SecureTemplateRepository` | Guarda y recupera de forma cifrada las referencias biométricas de cada perfil. |
| `LivenessChallenge` / `LivenessDetectorService` | Gestionan y observan los retos activos de presencia. |
| `AuthManager` | Gestiona la autenticación por contraseña y los intentos fallidos. |
| `ProfileManager` / `UserProfileRepository` | Gestionan los perfiles y sus datos locales. |
| `BiometricAttemptManager` | Controla los intentos fallidos de verificación biométrica. |

La implementación concreta puede variar entre versiones; consulta las clases fuente para conocer los métodos y parámetros exactos.

## Modelo biométrico

El modelo utilizado es `mobilefacenet.tflite`, ubicado en:

```text
app/src/main/assets/mobilefacenet.tflite
```

La aplicación prepara las imágenes faciales a 112 × 112 píxeles y utiliza embeddings de 128 componentes. La verificación compara la muestra capturada con las referencias almacenadas para el perfil activo.

El umbral de distancia coseno usado actualmente es **0,40**. Se trata de un parámetro provisional del prototipo, no de un umbral universal ni de uno calibrado con una base de datos representativa.

## Seguridad y privacidad

- Las plantillas faciales y los datos de perfil se almacenan cifrados localmente mediante AES-GCM.
- Las claves de cifrado se gestionan mediante Android Keystore.
- La contraseña se procesa con PBKDF2-HMAC-SHA256 y una sal individual.
- Se aplican límites de intentos y bloqueos temporales.
- No publiques en el repositorio perfiles reales, fotografías faciales de usuarios, archivos cifrados de perfiles, contraseñas, claves privadas ni copias de `local.properties`.

La prueba de presencia es un mecanismo activo experimental. Los rechazos observados frente a fotografías impresas o imágenes estáticas no demuestran resistencia frente a todos los ataques de suplantación; los ataques mediante vídeo deben evaluarse por separado. No debe describirse como una solución anti-spoofing certificada.

## Requisitos para ejecutar

1. Android Studio con soporte para proyectos Android y Java 11.
2. Android SDK instalado.
3. Dispositivo Android compatible o emulador con cámara disponible.
4. El archivo `mobilefacenet.tflite` presente en `app/src/main/assets/`.

## Instalación y ejecución

1. Clona el repositorio:

   ```bash
   git clone https://github.com/USUARIO/BioVerify.git
   ```

2. Abre la carpeta del proyecto en Android Studio.
3. Espera a que finalice la sincronización de Gradle.
4. Conecta un dispositivo Android, habilita la depuración USB y acepta los permisos solicitados.
5. Ejecuta la configuración `app` desde Android Studio.

No subas `local.properties`: Android Studio debe generar ese archivo localmente según la ubicación del SDK en cada equipo.

## Compilación

En Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

El APK de depuración se genera normalmente en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Pruebas unitarias

Las pruebas unitarias locales deben ubicarse en:

```text
app/src/test/java/com/samuel/verificador/
```

Para ejecutarlas en Windows:

```powershell
.\gradlew.bat testDebugUnitTest
```

Para generar el reporte HTML de JUnit:

```text
app/build/reports/tests/testDebugUnitTest/index.html
```

Las pruebas deben concentrarse en la lógica de dominio que pueda ejecutarse en la JVM local, aislando las dependencias de Android (cámara, almacenamiento, Keystore y UI) cuando corresponda. No se deben declarar pruebas como exitosas hasta ejecutarlas y revisar el resultado de Gradle.

## Limitaciones conocidas

- El umbral biométrico actual es provisional y requiere calibración con un conjunto mayor y más diverso de usuarios e impostores.
- Las mediciones de FAR y FRR realizadas hasta ahora tienen un tamaño de muestra reducido; no permiten generalizar el rendimiento del sistema.
- La iluminación baja produjo falsos rechazos en las pruebas preliminares.
- La resistencia frente a ataques de vídeo aún requiere pruebas específicas.
- El comportamiento puede variar según el dispositivo, la cámara y las condiciones ambientales.

## Uso académico

Proyecto desarrollado con fines educativos y de demostración. Antes de utilizarlo en un entorno real, se requiere una evaluación de seguridad, privacidad, accesibilidad, robustez biométrica y cumplimiento normativo.
