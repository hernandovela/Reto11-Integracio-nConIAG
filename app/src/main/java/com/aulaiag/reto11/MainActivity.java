package com.aulaiag.reto11;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
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
    private static final Uri USAGE = Uri.parse("https://chatgpt.com/settings/usage");
    private static final Uri PLAN_HELP = Uri.parse(
            "https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites");
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
    private record ReplyIssue(String message, boolean usage, boolean reconnect) { }

    private static final class Conversation {
        private final List<ResponsesClient.Message> messages = new ArrayList<>();
        private Screen screen = Screen.START;
        private boolean demo;
        private String draft = "";
        private ReplyIssue issue;
        private String owner;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ResponsesClient client = new ResponsesClient();
    private ChatGptAuth auth;
    private StudyUi ui;
    private Conversation conversation;
    private ChatGptAuth.Account account;
    private ChatGptAuth.Attempt pendingAttempt;
    private FrameLayout root;
    private EditText composer;
    private ScrollView timeline;
    private LinearLayout messageList;
    private TextView partialView;
    private boolean sending;
    private String partialReply = "";
    private boolean authBusy;
    private String authStatus;
    private int authOperation;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ui = new StudyUi(this);
        auth = new ChatGptAuth(this);
        Conversation previous = (Conversation) getLastNonConfigurationInstance();
        conversation = previous == null ? new Conversation() : previous;
        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        configureWindow();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        refreshAccount(previous == null);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        keepDraft();
        if (sending) {
            conversation.issue = new ReplyIssue("La solicitud se interrumpió. Puedes reintentar.", false, false);
        }
        return conversation;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (pendingAttempt != null) {
            auth.cancel(pendingAttempt);
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
        if (authBusy && pendingAttempt == null) {
            return;
        }
        if (pendingAttempt != null) {
            cancelSignIn();
        } else if (conversation.screen == Screen.ACCOUNT) {
            showScreen(conversation.demo || canUsePlan() ? Screen.CHAT : Screen.START);
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

    private void refreshAccount(boolean firstLaunch) {
        authBusy = true;
        authStatus = "Verificando la sesión…";
        render();
        worker.execute(() -> {
            try {
                ChatGptAuth.Account current = auth.activeAccount();
                onUi(() -> {
                    authBusy = false;
                    authStatus = null;
                    account = current;
                    if (firstLaunch && canUsePlan()) {
                        enterLiveChat();
                    } else {
                        render();
                        confirmPlanUse();
                    }
                });
            } catch (IOException problem) {
                onUi(() -> failAuth(problem));
            }
        });
    }

    private boolean canUsePlan() {
        return account != null && account.signedIn() && account.planEnabled();
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
        ui.block(body, infoPanel("Estudia a tu ritmo",
                "Usa tu plan de ChatGPT para las consultas compatibles."), 32);
        ui.block(body, ui.button("Cómo funciona el acceso", false, () -> openLink(PLAN_HELP)), 8);
        body.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1f));
        addStatus(body);

        boolean resume = canUsePlan() && !reconnectRequired();
        Button connect = ui.button(resume ? "Volver a la conversación" : "Continuar con ChatGPT",
                true, () -> {
                    if (resume) {
                        enterLiveChat();
                    } else {
                        startSignIn(account == null ? null : account.clientId());
                    }
                });
        connect.setEnabled(!authBusy);
        ui.block(body, connect, 22);
        Button demo = ui.button("Probar demostración", false, this::enterDemo);
        demo.setEnabled(!authBusy);
        ui.block(body, demo, 10);
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

        LinearLayout shortcuts = ui.row();
        for (int index = 0; index < SHORTCUTS.length; index++) {
            int action = index;
            Button button = ui.button(SHORTCUTS[index], false, () -> preparePrompt(action));
            button.setEnabled(!sending && !authBusy);
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
        composer.setEnabled(!sending && !authBusy);
        if (Build.VERSION.SDK_INT >= 34) {
            composer.setAutoHandwritingEnabled(false);
        }
        inputRow.addView(composer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = ui.button("Enviar", true, this::sendPrompt);
        send.setEnabled(!sending && !authBusy);
        addCompactButton(inputRow, send);
        ui.block(body, inputRow, 8);

        if (conversation.demo) {
            ui.block(body, ui.text("Demostración · ejemplos locales", 12, GREEN, true), 8);
        } else if (canUsePlan()) {
            LinearLayout plan = ui.row();
            plan.addView(ui.text("Usando tu plan de ChatGPT", 12, GREEN, true),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            addCompactButton(plan, ui.button("Gestionar uso", false, () -> openLink(USAGE)));
            ui.block(body, plan, 4);
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

        boolean signedIn = account != null && account.signedIn();
        if (signedIn) {
            LinearLayout panel = infoPanel("Sesión conectada", account.label());
            ui.block(panel, ui.text(account.planEnabled() ? "Uso del plan autorizado"
                    : "Uso del plan pendiente de autorización", 14, MUTED, false), 5);
            ui.block(body, panel, 20);
        } else {
            ui.block(body, infoPanel("Sesión sin conectar",
                    "Conecta una cuenta de ChatGPT para generar respuestas."), 20);
        }
        if (!canUsePlan() || reconnectRequired()) {
            Button connect = ui.button("Continuar con ChatGPT", true,
                    () -> startSignIn(account == null ? null : account.clientId()));
            connect.setEnabled(!authBusy && !sending);
            ui.block(body, connect, 22);
        } else if (conversation.demo) {
            ui.block(body, ui.button("Abrir conversación", true, this::enterLiveChat), 20);
        }
        if (signedIn) {
            ui.block(body, ui.button("Gestionar uso en ChatGPT", false, () -> openLink(USAGE)), 16);
        }
        Button change = ui.button(signedIn ? "Cambiar cuenta" : "Elegir una cuenta", false,
                this::chooseAccount);
        change.setEnabled(!authBusy && !sending);
        ui.block(body, change, 10);
        if (signedIn) {
            Button signOut = ui.button("Cerrar sesión", false, this::signOut);
            signOut.setEnabled(!authBusy && !sending);
            ui.block(body, signOut, 10);
        }
        addStatus(body);
        ui.block(body, ui.text("Puedes revisar y administrar tus conexiones en ChatGPT.",
                14, MUTED, false), 24);
        return page(body);
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
        if (pendingAttempt != null) {
            ui.block(body, ui.button("Cancelar acceso", false, this::cancelSignIn), 6);
        }
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
        for (ResponsesClient.Message message : conversation.messages) {
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
            if (issue.usage()) {
                ui.block(messageList, ui.button("Gestionar uso", true, () -> openLink(USAGE)), 7);
            }
            if (issue.reconnect()) {
                ui.block(messageList, ui.button("Continuar con ChatGPT", true,
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
        if (sending || authBusy) {
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
        conversation.messages.add(new ResponsesClient.Message("user", prompt));
        composer.setText("");
        conversation.draft = "";
        hideKeyboard();

        if (conversation.demo) {
            conversation.messages.add(new ResponsesClient.Message("assistant", exampleReply(prompt)));
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
        if (!canUsePlan()) {
            conversation.issue = new ReplyIssue("Vuelve a conectar tu cuenta para enviar la pregunta.",
                    false, true);
            showMessages();
            return;
        }
        sending = true;
        partialReply = "";
        conversation.issue = null;
        List<ResponsesClient.Message> history = new ArrayList<>(conversation.messages);
        render();
        worker.execute(() -> {
            try {
                long[] reportedAt = {0};
                String result = client.reply(auth.accessToken(), history, partial -> {
                    long now = SystemClock.elapsedRealtime();
                    if (now - reportedAt[0] >= 70) {
                        reportedAt[0] = now;
                        onUi(() -> showPartial(partial));
                    }
                });
                onUi(() -> {
                    sending = false;
                    partialReply = "";
                    conversation.messages.add(new ResponsesClient.Message("assistant", result));
                    render();
                });
            } catch (IOException problem) {
                handleReplyError(problem);
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
        boolean usage = false;
        boolean reconnect = false;
        String explanation = "No se pudo recibir la respuesta. Revisa la conexión y reintenta.";
        ChatGptAuth.Account updated = null;
        if (problem instanceof ResponsesClient.ApiException apiError) {
            usage = "subscription_sharing_usage_limit_exceeded".equals(apiError.code)
                    || "subscription_sharing_usage_unavailable".equals(apiError.code);
            reconnect = apiError.status == 401;
            explanation = apiError.getMessage();
        } else if (problem instanceof ChatGptAuth.AuthException authError) {
            reconnect = authError.signInRequired() || "plan_not_enabled".equals(authError.code());
            explanation = authError.getMessage();
        }
        if (reconnect) {
            try {

                updated = auth.activeAccount();
            } catch (IOException ignored) {
                // Authorization can still be retried after a local storage error.
            }
        }
        ReplyIssue issue = new ReplyIssue(explanation == null ? "No se pudo completar la consulta."
                : explanation, usage, reconnect);
        ChatGptAuth.Account refreshedAccount = updated;
        onUi(() -> {
            if (issue.reconnect()) {
                account = refreshedAccount;
            }
            sending = false;
            partialReply = "";
            conversation.issue = issue;
            render();
        });
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
        conversation.owner = null;
        showScreen(Screen.CHAT);
    }

    private void enterLiveChat() {
        if (conversation.demo || !account.clientId().equals(conversation.owner)) {
            clearConversation();
        }
        conversation.demo = false;
        conversation.owner = account.clientId();
        if (conversation.issue != null && conversation.issue.reconnect()) {
            conversation.issue = new ReplyIssue("Ya puedes reintentar la consulta.", false, false);
        }
        showScreen(Screen.CHAT);
        confirmPlanUse();
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


    private void startSignIn(String clientId) {
        if (authBusy || sending) {
            return;
        }
        authBusy = true;
        authStatus = "Preparando el acceso…";
        int operation = ++authOperation;
        render();
        worker.execute(() -> {
            try {
                ChatGptAuth.Attempt attempt = auth.beginSignIn(clientId, true);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || operation != authOperation) {
                        auth.cancel(attempt);
                        return;
                    }
                    pendingAttempt = attempt;
                    authStatus = "Completa el ingreso y la autorización en el navegador.";
                    render();
                    if (openLink(attempt.authorizationUri())) {
                        worker.execute(() -> finishSignIn(attempt, operation));
                    } else {
                        cancelSignIn();
                    }
                });
            } catch (IOException problem) {
                onUi(() -> {
                    if (operation == authOperation) {
                        failAuth(problem);
                    }
                });
            }
        });
    }

    private void finishSignIn(ChatGptAuth.Attempt attempt, int operation) {
        try {
            ChatGptAuth.Account connected = auth.finishSignIn(attempt);
            onUi(() -> {
                if (operation == authOperation) {
                    account = connected;
                    pendingAttempt = null;
                    authBusy = false;
                    authStatus = null;
                    if (canUsePlan()) {
                        enterLiveChat();
                    } else {
                        showScreen(Screen.ACCOUNT);
                    }
                }
            });
        } catch (IOException problem) {
            onUi(() -> {
                if (operation == authOperation) {
                    failAuth(problem);
                }
            });
        }
    }

    private void cancelSignIn() {
        authOperation++;
        ChatGptAuth.Attempt attempt = pendingAttempt;
        pendingAttempt = null;
        authBusy = false;
        authStatus = "Acceso cancelado.";
        if (attempt != null) {
            auth.cancel(attempt);
        }
        render();
    }

    private void chooseAccount() {
        authBusy = true;
        authStatus = "Consultando las cuentas…";
        render();
        worker.execute(() -> {
            try {
                List<ChatGptAuth.Account> accounts = auth.listAccounts();
                onUi(() -> {
                    authBusy = false;
                    authStatus = null;
                    render();
                    CharSequence[] options = new CharSequence[accounts.size() + 1];
                    for (int index = 0; index < accounts.size(); index++) {
                        options[index] = accounts.get(index).label();
                    }
                    options[accounts.size()] = "Conectar otra cuenta";
                    new AlertDialog.Builder(this)
                            .setTitle("Cambiar cuenta")
                            .setItems(options, (dialog, index) -> {
                                if (index == accounts.size()) {
                                    startSignIn(null);
                                } else {
                                    selectAccount(accounts.get(index));
                                }
                            })
                            .setNegativeButton("Cancelar", null)
                            .show();
                });
            } catch (IOException problem) {
                onUi(() -> failAuth(problem));
            }
        });
    }

    private void selectAccount(ChatGptAuth.Account selected) {
        if (!selected.signedIn()) {
            startSignIn(selected.clientId());
            return;
        }
        authBusy = true;
        authStatus = "Abriendo la sesión…";
        render();
        worker.execute(() -> {
            try {
                ChatGptAuth.Account current = auth.selectAccount(selected.clientId());
                onUi(() -> {
                    authBusy = false;
                    authStatus = null;
                    account = current;
                    if (canUsePlan()) {
                        enterLiveChat();
                    } else {
                        render();
                    }
                });
            } catch (IOException problem) {
                onUi(() -> failAuth(problem));
            }
        });
    }

    private void signOut() {
        authBusy = true;
        authStatus = "Cerrando sesión…";
        render();
        worker.execute(() -> {
            try {
                boolean remote = auth.signOut();
                ChatGptAuth.Account current = auth.activeAccount();
                onUi(() -> {
                    account = current;
                    authBusy = false;
                    authStatus = remote ? null
                            : "Cerraste sesión en este dispositivo. Revisa las conexiones en ChatGPT.";
                    clearConversation();
                    conversation.owner = null;
                    showScreen(Screen.START);
                });
            } catch (IOException problem) {
                onUi(() -> failAuth(problem));
            }
        });
    }

    private void confirmPlanUse() {
        if (!canUsePlan() || !account.planConfirmationPending()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Estás usando tu plan de ChatGPT")
                .setMessage("Las consultas compatibles de Aula IAG usan tu plan. "
                        + "Puedes revisar y ajustar el uso en la configuración de ChatGPT.")
                .setCancelable(false)
                .setPositiveButton("Entendido", (dialog, which) -> worker.execute(() -> {
                    try {
                        auth.acknowledgePlanConfirmation();
                        ChatGptAuth.Account current = auth.activeAccount();
                        onUi(() -> account = current);
                    } catch (IOException problem) {
                        onUi(() -> Toast.makeText(this,
                                "No se guardó la confirmación.", Toast.LENGTH_SHORT).show());
                    }
                }))
                .show();
    }

    private void failAuth(IOException problem) {
        pendingAttempt = null;
        authBusy = false;
        authStatus = problem instanceof ChatGptAuth.AuthException ? problem.getMessage()
                : "No se pudo verificar la cuenta. Revisa tu conexión y vuelve a intentar.";
        render();
    }

    private boolean openLink(Uri url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE));
            return true;
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Necesitas un navegador para abrir este enlace.", Toast.LENGTH_LONG).show();
            return false;
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
