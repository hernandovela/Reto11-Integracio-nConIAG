package com.aulaiag.reto11;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.aulaiag.reto11.StudyUi.BACKGROUND;
import static com.aulaiag.reto11.StudyUi.BORDER;
import static com.aulaiag.reto11.StudyUi.ERROR;
import static com.aulaiag.reto11.StudyUi.GREEN;
import static com.aulaiag.reto11.StudyUi.INK;
import static com.aulaiag.reto11.StudyUi.LIGHT;
import static com.aulaiag.reto11.StudyUi.MUTED;
import static com.aulaiag.reto11.StudyUi.PAPER;
import static com.aulaiag.reto11.StudyUi.QUESTION;

public final class MainActivity extends Activity {
    private static final Uri INSTITUTIONAL_GEMINI = Uri.parse("https://gemini.google.com/");
    private static final String PROJECT_PREFERENCE = "gemini_project_id";
    private static final int GOOGLE_RESOLUTION = 901;
    private static final int MAX_MESSAGES = 21;
    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final String[] SHORTCUTS = {"Explicar", "Resumir", "Practicar"};
    private static final String[] PREFIXES = {
            "Explícame con un ejemplo sencillo: ",
            "Resume este texto en tres ideas:\n",
            "Hazme tres preguntas para practicar: "
    };
    private static final String[] DEMO_PROMPTS = {
            "Explícame la fotosíntesis con un ejemplo sencillo.",
            "Resume este texto: Las abejas transportan polen de flor en flor. "
                    + "Este proceso permite que muchas plantas produzcan frutos y semillas.",
            "Hazme tres preguntas para practicar fracciones."
    };
    private static final String[] DEMO_REPLIES = {
            "La fotosíntesis es el proceso con el que una planta usa la luz para convertir "
                    + "agua y dióxido de carbono en azúcares. También libera oxígeno.\n\n"
                    + "Piensa en una hoja como una pequeña fábrica que funciona con luz solar.",
            "• Las abejas llevan polen entre flores.\n"
                    + "• Ese traslado se llama polinización.\n"
                    + "• Gracias a él, muchas plantas pueden producir semillas y frutos.",
            "1. ¿Cuál es mayor: 1/3 o 1/4?\n"
                    + "2. ¿Cuánto es 1/2 + 1/4?\n"
                    + "3. ¿Qué fracción representa tres partes de un total de ocho?"
    };

    private enum Screen { START, CHAT, ACCOUNT }
    private record ReplyIssue(String message, boolean reconnect) { }

    private static final class Conversation {
        private final List<GeminiClient.Message> messages = new ArrayList<>();
        private Screen screen = Screen.START;
        private boolean demo;
        private String draft = "";
        private ReplyIssue issue;
        private GeminiAuth.Session session;
        private boolean geminiVerified;
        private boolean awaitingGoogle;
        private boolean refreshing;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final GeminiClient client = new GeminiClient();
    private GeminiAuth auth;
    private StudyUi ui;
    private Conversation conversation;
    private String projectId;
    private FrameLayout root;
    private EditText composer;
    private ScrollView timeline;
    private LinearLayout messageList;
    private TextView partialView;
    private boolean sending;
    private String partialReply = "";
    private boolean authBusy;
    private String authStatus;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ui = new StudyUi(this);
        Conversation previous = (Conversation) getLastNonConfigurationInstance();
        conversation = previous == null ? new Conversation() : previous;
        projectId = getPreferences(MODE_PRIVATE).getString(PROJECT_PREFERENCE, "");
        authBusy = conversation.awaitingGoogle;
        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        configureWindow();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        render();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        keepDraft();
        if (sending) {
            conversation.issue = new ReplyIssue("La solicitud se interrumpió. Puedes reintentar.", false);
        }
        return conversation;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (auth != null) {
            auth.close();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation") // Android 13+ registers its dispatcher in onCreate.
    public void onBackPressed() {
        back();
    }

    private void back() {
        if (authBusy) {
            return;
        }
        if (conversation.screen == Screen.ACCOUNT) {
            showScreen(conversation.demo || canChat() ? Screen.CHAT : Screen.START);
        } else if (conversation.screen == Screen.CHAT) {
            if (!sending) {
                showScreen(Screen.START);
            }
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    private void configureWindow() {
        Window window = getWindow();
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            root.post(() -> {
                android.view.WindowInsetsController controls = window.getInsetsController();
                if (controls != null) {
                    controls.setSystemBarsAppearance(appearance, appearance);
                }
            });
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                view.setPadding(bars.left, bars.top, bars.right, bottom);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        } else {
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private boolean hasProject() {
        return !projectId.isEmpty();
    }

    private boolean canChat() {
        return hasProject() && conversation.session != null;
    }

    private boolean reconnectRequired() {
        return conversation.issue != null && conversation.issue.reconnect();
    }

    private void keepDraft() {
        if (composer != null) {
            conversation.draft = composer.getText().toString();
        }
    }

    private void showScreen(Screen screen) {
        hideKeyboard();
        conversation.screen = screen;
        render();
    }

    private void render() {
        keepDraft();
        root.removeAllViews();
        composer = null;
        messageList = null;
        timeline = null;
        partialView = null;
        View screen = switch (conversation.screen) {
            case START -> buildStart();
            case CHAT -> buildChat();
            case ACCOUNT -> buildAccount();
        };
        root.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.requestFocus();
    }

    private ScrollView page(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildStart() {
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(24), ui.dp(32), ui.dp(24), ui.dp(22));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_aula);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(icon, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        ui.block(body, ui.heading("Tu espacio\nde estudio", 36), 12);
        ui.block(body, ui.text("Pregunta, resume y practica.\nEmpieza con el tema que quieras.",
                16, MUTED, false), 12);
        String access = hasProject()
                ? "Conecta tu cuenta UNAL para usar Gemini en Aula IAG."
                : "Abre Gemini en el navegador y elige tu cuenta institucional UNAL.";
        ui.block(body, infoPanel("Estudia con Gemini", access), 32);
        body.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1f));
        addStatus(body);

        if (hasProject()) {
            boolean resume = canChat() && !reconnectRequired();
            Button connect = ui.button(resume ? "Volver a la conversación" : "Conectar cuenta UNAL",
                    true, () -> {
                        if (resume) {
                            enterLiveChat();
                        } else {
                            showScreen(Screen.ACCOUNT);
                            connectAccount();
                        }
                    });
            connect.setEnabled(!authBusy);
            ui.block(body, connect, 22);
        } else {
            ui.block(body, ui.button("Abrir Gemini institucional", true, this::openInstitutionalGemini), 22);
        }
        Button demo = ui.button("Probar demostración", false, this::enterDemo);
        demo.setEnabled(!authBusy);
        ui.block(body, demo, 10);
        Button settings = ui.button("Configurar Gemini en Aula IAG", false,
                () -> showScreen(Screen.ACCOUNT));
        settings.setEnabled(!authBusy);
        ui.block(body, settings, 10);
        return page(body);
    }

    private View buildChat() {
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        LinearLayout header = ui.row();
        LinearLayout title = ui.column();
        ui.block(title, ui.heading("Aula IAG", 22), 0);
        ui.block(title, ui.text("Asistente de estudio", 12, MUTED, false), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button newChat = ui.button("Nuevo", false, this::askNewConversation);
        newChat.setEnabled(!sending && !authBusy);
        addCompactButton(header, newChat);
        addCompactButton(header, ui.button("Cuenta", false, () -> showScreen(Screen.ACCOUNT)));
        ui.block(body, header, 0);

        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1));
        line.topMargin = ui.dp(9);
        body.addView(divider, line);

        timeline = new ScrollView(this);
        messageList = ui.column();
        messageList.setPadding(0, ui.dp(6), 0, ui.dp(8));
        timeline.addView(messageList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(timeline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        boolean chatActions = !sending && !authBusy
                && (conversation.demo || canChat() && !reconnectRequired());
        LinearLayout shortcuts = ui.row();
        for (int index = 0; index < SHORTCUTS.length; index++) {
            int action = index;
            Button button = ui.button(SHORTCUTS[index], false, () -> preparePrompt(action));
            button.setEnabled(chatActions);
            button.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            slot.setMarginStart(index == 0 ? 0 : ui.dp(6));
            shortcuts.addView(button, slot);
        }
        ui.block(body, shortcuts, 3);

        LinearLayout inputRow = ui.row();
        inputRow.setGravity(Gravity.BOTTOM);
        composer = new EditText(this);
        composer.setTextSize(15);
        composer.setTextColor(INK);
        composer.setHintTextColor(MUTED);
        composer.setBackground(ui.shape(PAPER, BORDER, 17));
        composer.setPadding(ui.dp(13), ui.dp(8), ui.dp(13), ui.dp(8));
        composer.setMinHeight(ui.dp(53));
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMaxLines(4);
        composer.setHint("Escribe tu pregunta");
        composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_PROMPT_LENGTH)});
        composer.setText(conversation.draft);
        composer.setEnabled(chatActions);
        if (Build.VERSION.SDK_INT >= 34) {
            composer.setAutoHandwritingEnabled(false);
        }
        inputRow.addView(composer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = ui.button("Enviar", true, this::sendPrompt);
        send.setEnabled(chatActions);
        addCompactButton(inputRow, send);
        ui.block(body, inputRow, 8);

        if (conversation.demo) {
            ui.block(body, ui.text("Demostración · ejemplos locales", 12, GREEN, true), 8);
        } else {
            String status;
            if (!canChat()) {
                status = "Gemini · Conexión pendiente";
            } else {
                status = conversation.geminiVerified
                        ? "Gemini · Conectado" : "Gemini · Proyecto " + projectId;
            }
            ui.block(body, ui.text(status, 12, GREEN, true), 8);
        }
        showMessages();
        return body;
    }

    private void addCompactButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(ui.dp(7));
        row.addView(button, params);
    }

    private View buildAccount() {
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(20));
        LinearLayout header = ui.row();
        header.addView(ui.heading("Cuenta", 32), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addCompactButton(header, ui.button("Volver", false, this::back));
        ui.block(body, header, 0);

        GeminiAuth.Session session = conversation.session;
        String accountDetail = session == null ? "Sesión sin conectar" : session.email();
        ui.block(body, infoPanel("Cuenta UNAL", accountDetail), 20);
        if (hasProject() && (session == null || reconnectRequired())) {
            Button connect = ui.button("Conectar cuenta UNAL", true, this::connectAccount);
            connect.setEnabled(!authBusy && !sending);
            ui.block(body, connect, 12);
        }
        if (canChat() && !reconnectRequired()) {
            Button openChat = ui.button("Abrir conversación", true, this::enterLiveChat);
            openChat.setEnabled(!authBusy && !sending);
            ui.block(body, openChat, 12);
        }
        if (session != null && hasProject()) {
            Button switchAccount = ui.button("Cambiar cuenta", false, this::chooseAnotherAccount);
            switchAccount.setEnabled(!authBusy && !sending);
            ui.block(body, switchAccount, 8);
        }
        if (session != null) {
            Button signOut = ui.button("Cerrar sesión en Aula IAG", false, this::signOut);
            signOut.setEnabled(!authBusy && !sending);
            ui.block(body, signOut, 8);
        }
        addStatus(body);

        String projectDetail;
        if (!hasProject()) {
            projectDetail = "Falta configurar un proyecto con la API de Gemini habilitada.";
        } else if (conversation.geminiVerified) {
            projectDetail = "Gemini respondió desde este proyecto.";
        } else {
            projectDetail = "Proyecto configurado. La primera consulta comprobará el acceso a Gemini.";
        }
        LinearLayout project = infoPanel("Proyecto Google Cloud", projectDetail);
        EditText editor = new EditText(this);
        editor.setTextColor(INK);
        editor.setHintTextColor(MUTED);
        editor.setTextSize(15);
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHint("ID del proyecto");
        editor.setText(projectId);
        editor.setEnabled(!authBusy && !sending);
        ui.block(project, editor, 8);
        Button save = ui.button("Guardar proyecto", false, () -> saveProject(editor));
        save.setEnabled(!authBusy && !sending);
        ui.block(project, save, 8);
        ui.block(body, project, 24);

        ui.block(body, ui.button("Abrir Gemini institucional", false, this::openInstitutionalGemini), 18);
        ui.block(body, ui.text("Elige tu cuenta institucional UNAL en el navegador.",
                14, MUTED, false), 6);
        return page(body);
    }

    private void saveProject(EditText editor) {
        String value = editor.getText().toString().trim();
        if (!value.isEmpty() && !value.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")) {
            editor.setError("Escribe el ID de Google Cloud de 6 a 30 caracteres. "
                    + "Usa minúsculas, números y guiones.");
            return;
        }
        if (!projectId.equals(value)) {
            projectId = value;
            getPreferences(MODE_PRIVATE).edit().putString(PROJECT_PREFERENCE, projectId).apply();
            conversation.geminiVerified = false;
            if (!conversation.demo) {
                clearConversation();
            }
        }
        authStatus = null;
        hideKeyboard();
        render();
        Toast.makeText(this, hasProject() ? "Proyecto guardado" : "Proyecto retirado",
                Toast.LENGTH_SHORT).show();
    }

    private LinearLayout infoPanel(String title, String details) {
        LinearLayout panel = ui.column();
        panel.setPadding(ui.dp(15), ui.dp(13), ui.dp(15), ui.dp(14));
        panel.setBackground(ui.shape(LIGHT, 0, 18));
        ui.block(panel, ui.text(title, 16, GREEN, true), 0);
        ui.block(panel, ui.text(details, 14, MUTED, false), 5);
        return panel;
    }

    private void addStatus(LinearLayout body) {
        if (authStatus == null) {
            return;
        }
        TextView status = ui.text(authStatus, 14, authBusy ? GREEN : ERROR, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.block(body, status, 15);
    }

    private void showMessages() {
        if (messageList == null) {
            return;
        }
        messageList.removeAllViews();
        partialView = null;
        if (conversation.messages.isEmpty()) {
            String detail = conversation.demo
                    ? "Prueba un atajo para cargar una pregunta de muestra."
                    : "Escribe una pregunta o elige un atajo para empezar.";
            ui.block(messageList, infoPanel("¿Qué quieres aprender?", detail), 11);
        }
        for (GeminiClient.Message message : conversation.messages) {
            addMessage(message.role().equals("user"), message.text(), true);
        }
        if (sending) {
            partialView = addMessage(false, partialReply.isEmpty()
                    ? "Generando respuesta…" : partialReply, false);
        }
        ReplyIssue issue = conversation.issue;
        if (issue != null) {
            TextView error = ui.text(issue.message(), 14, ERROR, true);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.block(messageList, error, 14);
            if (issue.reconnect()) {
                ui.block(messageList, ui.button("Conectar cuenta UNAL", true,
                        () -> showScreen(Screen.ACCOUNT)), 7);
            } else {
                ui.block(messageList, ui.button("Reintentar", false, this::requestReply), 7);
            }
        }
        scrollToLatest();
    }

    private TextView addMessage(boolean fromUser, String contents, boolean selectable) {
        ui.block(messageList, ui.text(fromUser ? "Tú" : "Aula IAG", 12, MUTED, true), 12);
        TextView bubble = ui.text(contents, 15, INK, false);
        bubble.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        bubble.setBackground(ui.shape(fromUser ? QUESTION : PAPER, fromUser ? 0 : BORDER, 17));
        bubble.setTextIsSelectable(selectable);
        ui.block(messageList, bubble, 4);
        if (!fromUser && selectable) {
            Button copy = ui.button("Copiar", false, () -> copy(contents));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = ui.dp(4);
            messageList.addView(copy, params);
        }
        return bubble;
    }

    private void copy(String contents) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Respuesta de Aula IAG", contents));
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "Respuesta copiada", Toast.LENGTH_SHORT).show();
        }
    }

    private void scrollToLatest() {
        ScrollView scroll = timeline;
        if (scroll != null) {
            scroll.post(() -> scroll.scrollTo(0, scroll.getChildAt(0).getBottom()));
        }
    }

    private void preparePrompt(int action) {
        String current = composer.getText().toString().trim();
        for (String prefix : PREFIXES) {
            if (current.startsWith(prefix.trim())) {
                current = current.substring(prefix.trim().length()).trim();
                break;
            }
        }
        for (String example : DEMO_PROMPTS) {
            if (current.equals(example)) {
                current = "";
                break;
            }
        }
        String draft = current.isEmpty() && conversation.demo ? DEMO_PROMPTS[action]
                : PREFIXES[action] + current;
        if (draft.length() > MAX_PROMPT_LENGTH) {
            composer.setError("Acorta el texto para añadir el atajo.");
            return;
        }
        composer.setText(draft);
        composer.setSelection(composer.length());
        composer.requestFocus();
        if (!conversation.demo) {
            getSystemService(InputMethodManager.class).showSoftInput(composer,
                    InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void sendPrompt() {
        if (sending || authBusy || !conversation.demo && (!canChat() || reconnectRequired())) {
            return;
        }
        String prompt = composer.getText().toString().trim();
        if (prompt.isEmpty()) {
            composer.setError("Escribe una pregunta para continuar");
            return;
        }
        if (conversation.issue != null && !conversation.messages.isEmpty()) {
            conversation.messages.remove(conversation.messages.size() - 1);
            conversation.issue = null;
        }
        while (conversation.messages.size() >= MAX_MESSAGES - 1) {
            conversation.messages.subList(0, 2).clear();
        }
        conversation.messages.add(new GeminiClient.Message("user", prompt));
        composer.setText("");
        conversation.draft = "";
        hideKeyboard();

        if (conversation.demo) {
            conversation.messages.add(new GeminiClient.Message("assistant", exampleReply(prompt)));
            showMessages();
        } else {
            requestReply();
        }
    }

    private String exampleReply(String prompt) {
        for (int index = 0; index < DEMO_PROMPTS.length; index++) {
            if (DEMO_PROMPTS[index].equalsIgnoreCase(prompt)) {
                return DEMO_REPLIES[index];
            }
        }
        return "La demostración muestra respuestas de ejemplo. "
                + "Elige Explicar, Resumir o Practicar para cargar una pregunta de muestra.";
    }

    private void requestReply() {
        if (sending || authBusy || conversation.messages.isEmpty()) {
            return;
        }
        if (!canChat() || reconnectRequired()) {
            conversation.issue = new ReplyIssue("Conecta tu cuenta UNAL para enviar la pregunta.", true);
            showMessages();
            return;
        }
        sending = true;
        partialReply = "";
        conversation.issue = null;
        String token = conversation.session.accessToken();
        String targetProject = projectId;
        List<GeminiClient.Message> history = new ArrayList<>(conversation.messages);
        render();
        worker.execute(() -> {
            try {
                long[] reportedAt = {0};
                String result = client.reply(token, targetProject, history, partial -> {
                    long now = SystemClock.elapsedRealtime();
                    if (now - reportedAt[0] >= 70) {
                        reportedAt[0] = now;
                        onUi(() -> showPartial(partial));
                    }
                });
                onUi(() -> {
                    sending = false;
                    partialReply = "";
                    conversation.geminiVerified = true;
                    conversation.messages.add(new GeminiClient.Message("assistant", result));
                    render();
                });
            } catch (IOException problem) {
                onUi(() -> handleReplyError(problem));
            }
        });
    }

    private void showPartial(String partial) {
        partialReply = partial;
        if (partialView != null && timeline != null) {
            boolean nearBottom = messageList.getBottom() - timeline.getHeight()
                    - timeline.getScrollY() < ui.dp(70);
            partialView.setText(partial);
            if (nearBottom) {
                scrollToLatest();
            }
        }
    }

    private void handleReplyError(IOException problem) {
        String explanation = "No se pudo recibir la respuesta. Revisa la conexión y reintenta.";
        boolean reconnect = false;
        if (problem instanceof GeminiClient.ApiException apiError) {
            reconnect = apiError.status == 401;
            if (reconnect || apiError.status == 403 || apiError.status == 404) {
                conversation.geminiVerified = false;
            }
            if (apiError.getMessage() != null) {
                explanation = apiError.getMessage();
            }
        }
        sending = false;
        partialReply = "";
        conversation.issue = new ReplyIssue(explanation, reconnect);
        render();
    }

    private void askNewConversation() {
        keepDraft();
        if (conversation.messages.isEmpty() && conversation.draft.trim().isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("¿Empezar otra conversación?")
                .setMessage("Se borrarán los mensajes y el borrador de esta conversación.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Empezar", (dialog, which) -> {
                    clearConversation();
                    render();
                })
                .show();
    }

    private void enterDemo() {
        if (!conversation.demo) {
            clearConversation();
        }
        conversation.demo = true;
        showScreen(Screen.CHAT);
    }

    private void enterLiveChat() {
        if (!canChat()) {
            showScreen(Screen.ACCOUNT);
            return;
        }
        if (conversation.demo) {
            clearConversation();
        }
        conversation.demo = false;
        showScreen(Screen.CHAT);
    }

    private void clearConversation() {
        conversation.messages.clear();
        conversation.draft = "";
        conversation.issue = null;
        if (composer != null) {
            composer.setText("");
        }
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
    }


    private final GeminiAuth.Listener login = new GeminiAuth.Listener() {
        @Override
        public void onResolution(PendingIntent pending) {
            conversation.awaitingGoogle = true;
            try {
                startIntentSenderForResult(pending.getIntentSender(), GOOGLE_RESOLUTION, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException exception) {
                auth.cancelPending();
                finishLoginError("Google no pudo abrir la autorización.");
            }
        }

        @Override
        public void onSuccess(GeminiAuth.Session session) {
            GeminiAuth.Session previous = conversation.session;
            if (previous == null || !previous.subject().equals(session.subject())) {
                conversation.geminiVerified = false;
                clearConversation();
            } else if (reconnectRequired()) {
                conversation.issue = new ReplyIssue("Cuenta reconectada. Ya puedes reintentar.", false);
            }
            conversation.session = session;
            conversation.awaitingGoogle = false;
            conversation.refreshing = false;
            authBusy = false;
            authStatus = null;
            enterLiveChat();
        }

        @Override
        public void onError(String message) {
            finishLoginError(message);
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != GOOGLE_RESOLUTION || !conversation.awaitingGoogle) {
            return;
        }
        conversation.awaitingGoogle = false;
        if (resultCode == RESULT_OK) {
            GeminiAuth.Session previous = conversation.refreshing ? conversation.session : null;
            authorization().finishSignIn(data, previous, login);
        } else {
            authorization().cancelPending();
            finishLoginError("Se canceló la conexión con Google.");
        }
    }

    private void connectAccount() {
        authorizeAccount(conversation.session);
    }

    private void chooseAnotherAccount() {
        authorizeAccount(null);
    }

    private void authorizeAccount(GeminiAuth.Session previous) {
        if (authBusy || sending || !hasProject()) {
            return;
        }
        authBusy = true;
        conversation.refreshing = previous != null;
        authStatus = "Conectando cuenta UNAL…";
        render();
        if (previous == null) {
            authorization().signIn(login);
        } else {
            authorization().refresh(previous, login);
        }
    }

    private GeminiAuth authorization() {
        if (auth == null) {
            auth = new GeminiAuth(this);
        }
        return auth;
    }

    private void finishLoginError(String message) {
        conversation.awaitingGoogle = false;
        conversation.refreshing = false;
        authBusy = false;
        authStatus = message;
        render();
    }

    private void signOut() {
        GeminiAuth.Session session = conversation.session;
        conversation.session = null;
        conversation.geminiVerified = false;
        if (!conversation.demo) {
            clearConversation();
        }
        authBusy = true;
        authStatus = "Cerrando sesión…";
        render();
        authorization().signOut(session, revoked -> {
            authBusy = false;
            authStatus = revoked ? null
                    : "Cerraste sesión en Aula IAG. Google no confirmó el retiro del permiso.";
            render();
        });
    }

    private void openInstitutionalGemini() {
        Intent browser = new Intent(Intent.ACTION_VIEW, INSTITUTIONAL_GEMINI)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        browser.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "Necesitas un navegador para abrir Gemini.", Toast.LENGTH_LONG).show();
        }
    }

    private void onUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed && !isFinishing()) {
                action.run();
            }
        });
    }
}
