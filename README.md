# Aula IAG

Aula IAG es una app Android de estudio en español para explicar conceptos, resumir textos y practicar con ejercicios. El proyecto implementa la modalidad de chatbot del Reto 11 de integración con inteligencia artificial generativa.

## Diseño

El diseño presenta el inicio, la demostración, el chat con Gemini y la configuración de cuenta. [Diseño de Aula IAG](docs/diseno-aula-iag.pdf)

## Ejecutar en Android Studio

1. Abre la carpeta raíz del proyecto en Android Studio y sincroniza Gradle. Instala Android SDK Platform 36 y Build Tools 36.0.0 si el IDE los solicita.
2. Usa el JDK integrado de Android Studio compatible con Gradle 9.5. El código fuente usa Java 17.
3. Selecciona la configuración `app` y ejecuta en un dispositivo con Android 8.0 (API 26) o superior. Para autorizar el chat interno en un emulador, usa una imagen con Google Play.

En Windows, si la ruta del proyecto usa acentos en forma Unicode descompuesta, los archivos generados del módulo se colocan en la caché de Gradle. Android Studio conserva el flujo normal de sincronización y ejecución.

## Abrir Gemini con la cuenta institucional

Pulsa **Abrir Gemini institucional** y elige tu cuenta de la Universidad Nacional de Colombia en el navegador. La conversación se realiza en Gemini con el acceso habilitado por Google Workspace. [Gemini con una cuenta educativa](https://support.google.com/gemini/answer/14620100)

**Probar demostración** abre ejemplos preescritos en el dispositivo. Puedes probar los atajos de explicar, resumir y practicar sin conexión.

## Configurar el chat Gemini en Aula IAG

El chat de Aula IAG requiere un proyecto de Google Cloud autorizado para Gemini API. Con el acceso a Gemini en Google Workspace puedes usar el servicio institucional en el navegador y probar la demostración de Aula IAG mientras consigues un proyecto autorizado.

1. Crea o solicita un proyecto de Google Cloud. Habilita **Generative Language API** (`generativelanguage.googleapis.com`) y copia su **ID del proyecto**. [Configuración de Gemini API con OAuth](https://ai.google.dev/gemini-api/docs/oauth)
2. En **Google Auth Platform**, configura la pantalla de consentimiento. Usa el público **Interno** si el proyecto está en la organización de la Universidad Nacional y la cuenta pertenece a ella. Para una aplicación externa en pruebas, agrega la cuenta institucional en **Usuarios de prueba**. Registra los alcances que solicita la app en **Acceso a datos**. Incluye `openid`, `https://www.googleapis.com/auth/userinfo.email`, `https://www.googleapis.com/auth/cloud-platform` y `https://www.googleapis.com/auth/generative-language.retriever`. [Configuración de consentimiento](https://developers.google.com/workspace/guides/configure-oauth-consent)
3. En **Clientes**, crea un cliente OAuth de tipo **Android** con el nombre de paquete `com.aulaiag.reto11` y la huella SHA-1 del certificado que firma el APK instalado. En Windows, obtén la huella de la variante con `.\gradlew.bat :app:signingReport`. En macOS o Linux usa `./gradlew :app:signingReport`. Para un APK ya generado, usa `apksigner verify --print-certs nombre.apk`. Registra la nueva huella si cambia la firma. [Credenciales OAuth para Android](https://developers.google.com/identity/protocols/oauth2/native-app#android) · [apksigner](https://developer.android.com/tools/apksigner)
4. En **IAM**, comprueba que la cuenta institucional tenga `serviceusage.services.use` en el proyecto. El rol **Service Usage Consumer** (`roles/serviceusage.serviceUsageConsumer`) concede ese permiso. [Permisos del proyecto de cuota](https://docs.cloud.google.com/docs/quotas/set-quota-project#permissions)
5. En la pantalla inicial, pulsa **Configurar Gemini en Aula IAG**. En **Cuenta**, escribe el **ID del proyecto** y pulsa **Guardar proyecto**. Selecciona **Conectar cuenta UNAL** y autoriza tu cuenta institucional habilitada para ese proyecto. Al conectarte, pulsa **Abrir conversación**.

La primera consulta comprueba el acceso a Gemini API. La cuota y la disponibilidad dependen del proyecto. Si Google bloquea la autorización para una cuenta administrada, consulta al administrador de Google Workspace o al encargado del proyecto.

## Implementación

- Android nativo con Java y vistas de la plataforma.
- Google Identity Services autoriza el acceso a Gemini API con OAuth en Android. Aula IAG comprueba el correo institucional con Google y conserva el token de acceso en memoria. [Autorización en Android](https://developer.android.com/identity/authorization)
- El chat usa `gemini-3.5-flash-lite` con `streamGenerateContent`. La solicitud incluye el proyecto de cuota en `x-goog-user-project` y la app muestra la respuesta a medida que llega. [Generación de texto con Gemini](https://ai.google.dev/gemini-api/docs/text-generation)

## Compilar y probar

Con el SDK de Android configurado, desde PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

En macOS o Linux, usa `./gradlew`. Para la prueba de instrumentación conecta antes un emulador o dispositivo con ADB. Una prueba de generación real requiere el proyecto y una cuenta autorizados por Google.
