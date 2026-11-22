# Aula IAG

Aula IAG es una app Android de estudio en español. Permite hacer preguntas, pedir explicaciones, resumir un texto y practicar con ejercicios en una conversación. El proyecto corresponde al Reto 11 de integración con inteligencia artificial generativa y usa la modalidad de chatbot.

## Diseño

El [diseño de Aula IAG](docs/diseno-aula-iag.pdf) presenta las pantallas de inicio, conversación y cuenta, junto al flujo de conexión.

## Ejecutar en Android Studio

1. Abre la carpeta raíz del proyecto en Android Studio y sincroniza Gradle. Instala Android SDK Platform 36 y Build Tools 36.0.0 si el IDE los solicita.
2. Usa el JDK integrado de Android Studio compatible con Gradle 9.5. El código fuente usa Java 17.
3. Selecciona la configuración `app` y ejecuta en un emulador o dispositivo con Android 8.0 (API 26) o superior.
4. Elige **Probar demostración** para probar ejemplos locales o **Continuar con ChatGPT** para generar respuestas.


En Windows, si la ruta del proyecto usa acentos en forma Unicode descompuesta, los archivos generados del módulo se colocan en la caché de Gradle. Android Studio conserva el flujo normal de sincronización y ejecución.

## Conectar ChatGPT

1. Pulsa **Continuar con ChatGPT**. Se abrirá OpenAI en el navegador del dispositivo.
2. Selecciona tu cuenta y revisa la autorización de uso del plan. Vuelve a Aula IAG para confirmar y comenzar a conversar.
3. En **Cuenta** puedes revisar la sesión activa, cambiar de cuenta, consultar los límites de uso y cerrar sesión.

El flujo usa [Sign in with ChatGPT para aplicaciones de código abierto](https://developers.openai.com/siwc/token-sharing-open-source), disponible como función preliminar. La autorización de inferencia depende de la elegibilidad de la cuenta y del servicio en su región. La app usa OAuth y el catálogo de modelos de la cuenta. Se selecciona el primer modelo que OpenAI ofrece para mostrar en ese catálogo. La demostración utiliza ejemplos preescritos en el dispositivo y funciona sin conexión.

## Implementación

- Android nativo con Java y vistas de la plataforma.
- OAuth en el navegador con PKCE y retorno a un puerto local. La clave de cifrado de los tokens se protege en Android Keystore.
- Uso del endpoint [Responses API para Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) con el token de acceso. La app muestra texto a medida que llega y confirma la respuesta al recibir el evento de finalización.
- La conversación se mantiene en memoria. Para cada consulta conectada, la app envía a OpenAI la pregunta y el contexto reciente con `store: false`. Puedes iniciar una conversación nueva desde el chat.

## Compilar y probar

Con el SDK de Android configurado, desde PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

En macOS o Linux, usa `./gradlew`. Para la prueba de instrumentación conecta antes un emulador o dispositivo con ADB. Las comprobaciones usan datos de ejemplo para verificar el formato de la solicitud, la selección de modelo el procesamiento de respuestas en streaming, la validación de identidad firmada y el retorno local de OAuth.
