import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.WebhookBot;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.*;

public class TelegramArticleBot extends TelegramLongPollingBot implements WebhookBot {
    // =============== CONFIG ================
    private static final String BOT_TOKEN = System.getenv("BOT_TOKEN");
    private static final String BOT_USERNAME = System.getenv("BOT_USERNAME");
    private static final String N8N_WEBHOOK_URL = System.getenv("N8N_WEBHOOK_URL");
    private static final String CHANNEL_ID = System.getenv("CHANNEL_ID");
    private static final String GOOGLE_DOCS_URL_PREFIX = "https://docs.google.com/document/d/";
    private static final String BASEROW_API_URL = System.getenv("BASEROW_API_URL");
    private static final String BASEROW_TOKEN = System.getenv("BASEROW_TOKEN");
    private static final String IMGBB_API_KEY = System.getenv("IMGBB_API_KEY");
    private static final String IMGBB_UPLOAD_URL = System.getenv("IMGBB_UPLOAD_URL");


    @Override
    public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
        return null;
    }

    @Override
    public void setWebhook(SetWebhook setWebhook) throws TelegramApiException {

    }

    @Override
    public String getBotPath() {
        return "";
    }
    // =======================================

    private enum ActionType { GENERATE, REWRITE }
    private enum LengthType { SHORT, MEDIUM, LONG }
    private enum StyleType { STORYTELLING, EXPERT, DIGEST }

    private static class UserState {
        ActionType action;
        LengthType length;
        StyleType style;
        String topic;
        String description;
        boolean awaitingOriginal;
        boolean awaitingFeedback;
        String originalText;
        String zenDocumentId;
    }

    private static class ArticleResult {
        String text, picture;
        String zenDocumentId;
        ArticleResult(String t, String p) { text = t; picture = p; }
        ArticleResult(String docId) { zenDocumentId = docId; }
    }

    private final Map<Long, UserState> states = new HashMap<>();
    private final Map<Long, ArticleResult> lastResults = new HashMap<>();
    private final Set<Long> greeted = new HashSet<>();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofMinutes(2))
            .readTimeout(java.time.Duration.ofMinutes(2))
            .build();

    // Persistent reply keyboard
    private final ReplyKeyboardMarkup mainMenuKeyboard;
    {
        mainMenuKeyboard = new ReplyKeyboardMarkup();
        mainMenuKeyboard.setResizeKeyboard(true);
        mainMenuKeyboard.setOneTimeKeyboard(false);
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton("Главное меню"));
        List<KeyboardRow> rows = new ArrayList<>();
        rows.add(row);
        mainMenuKeyboard.setKeyboard(rows);
    }

    private String uploadImageToImgBB(String imageUrl) throws IOException {
        // 1. Download image
        Request downloadRequest = new Request.Builder().url(imageUrl).build();
        byte[] imageBytes;
        try (Response response = http.newCall(downloadRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download image: " + response.code());
            }
            imageBytes = response.body().bytes();
        }

        // 2. Upload to ImgBB
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("key", IMGBB_API_KEY)
                .addFormDataPart("image", "image.jpg",
                        RequestBody.create(imageBytes, MediaType.parse("image/jpeg")))
                .build();

        Request uploadRequest = new Request.Builder()
                .url(IMGBB_UPLOAD_URL)
                .post(requestBody)
                .build();

        try (Response response = http.newCall(uploadRequest).execute()) {
            String jsonResponse = response.body().string();
            JSONObject json = new JSONObject(jsonResponse);

            if (!json.getBoolean("success")) {
                throw new IOException("ImgBB API error: " + json.getJSONObject("error").getString("message"));
            }

            return json.getJSONObject("data").getString("url");
        }
    }

    private void resetUserState(long chatId) {
        states.remove(chatId);
        lastResults.remove(chatId);
        greeted.remove(chatId);
    }

    @Override
    public void onUpdateReceived(Update upd) {
        if (upd.hasMessage() && upd.getMessage().hasText() && "/start".equals(upd.getMessage().getText())) {
            long chatId = upd.getMessage().getChatId();
            resetUserState(chatId);
            sendActionChoice(chatId);
            return;
        }

        if (upd.hasCallbackQuery()) {
            try {
                handleCallback(upd.getCallbackQuery());
                DeleteMessage deleteMessage = new DeleteMessage(
                        String.valueOf(upd.getCallbackQuery().getMessage().getChatId()),
                        upd.getCallbackQuery().getMessage().getMessageId()
                );
                executeSilently(deleteMessage);
            } catch(Exception e) {
                e.printStackTrace();
                sendActionChoice(upd.getCallbackQuery().getMessage().getChatId());
            }
        } else if (upd.hasMessage() && upd.getMessage().hasText()) {
            handleText(upd.getMessage());
        }
    }

    private void handleCallback(CallbackQuery cb) throws Exception {
        long chat = cb.getMessage().getChatId();
        int msgId = cb.getMessage().getMessageId();
        String data = cb.getData();
        UserState st = states.get(chat);
        ArticleResult ar = lastResults.get(chat);

        switch (data) {
            case "VIEW":
                if (ar != null && ar.zenDocumentId != null) {
                    String url = GOOGLE_DOCS_URL_PREFIX + ar.zenDocumentId;
                    InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
                    back.setCallbackData("BACK");
                    InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                            Collections.singletonList(Collections.singletonList(back))
                    );
                    sendMessage(chat, "🔗 Ссылка на статью в Google Docs: " + url, kb);
                }
                return;

            case "BACK":
                execute(new DeleteMessage(String.valueOf(chat), msgId));
                if (ar != null) {
                    if (ar.zenDocumentId != null) sendZenArticleButtons(chat, ar);
                    else sendArticleWithButtons(chat, ar);
                }
                return;

            case "MAIN_MENU":
                execute(new DeleteMessage(String.valueOf(chat), msgId));
                resetUserState(chat);
                sendActionChoice(chat);
                return;

            case "PUBLISH_TG":
                if (ar != null && ar.text != null) {
                    sendToChannel(ar.text, ar.picture);
                    sendText(chat, "✅ Опубликовано в Telegram канал!");
                }
                resetUserState(chat);
                sendActionChoice(chat);
                return;

            case "PUBLISH_ZEN":
                if (ar != null && ar.text != null && ar.zenDocumentId != null) {
                    saveToBaserow(ar.text, ar.picture);
                    sendText(chat, "✅ Опубликовано на сайт!");
                }
                resetUserState(chat);
                sendActionChoice(chat);
                return;

            case "REREWRITE":
                if (ar == null) {
                    sendText(chat, "❌ Сначала сгенерируйте статью.");
                    sendActionChoice(chat);
                    return;
                }
                UserState newState = new UserState();
                newState.action = ActionType.REWRITE;
                newState.awaitingFeedback = true;
                newState.originalText = (ar.zenDocumentId != null) ? ar.zenDocumentId : (ar.text != null ? ar.text : "");
                // Копируем параметры из последней генерации, если они есть
                if (st != null) {
                    newState.length = st.length;
                    newState.style = st.style;
                }
                states.put(chat, newState);
                sendText(chat, "✏️ Что нужно изменить в статье?");
                return;

            default:
                if (data.startsWith("ACT_")) {
                    UserState s = new UserState();
                    s.action = "ACT_GEN".equals(data) ? ActionType.GENERATE : ActionType.REWRITE;
                    states.put(chat, s);
                    sendLengthChoice(chat);
                } else if (data.startsWith("LEN_")) {
                    if (st == null) {
                        sendText(chat, "❌ Ошибка состояния. Попробуйте заново.");
                        sendActionChoice(chat);
                        return;
                    }
                    switch (data) {
                        case "LEN_SHORT":
                            st.length = LengthType.SHORT;
                            break;
                        case "LEN_MEDIUM":
                            st.length = LengthType.MEDIUM;
                            break;
                        case "LEN_LONG":
                            st.length = LengthType.LONG;
                            break;
                    }
                    sendStyleChoice(chat);
                } else if (data.startsWith("STYLE_")) {
                    if (st == null) {
                        sendText(chat, "❌ Ошибка состояния. Попробуйте заново.");
                        sendActionChoice(chat);
                        return;
                    }
                    switch (data) {
                        case "STYLE_STORY":
                            st.style = StyleType.STORYTELLING;
                            break;
                        case "STYLE_EXPERT":
                            st.style = StyleType.EXPERT;
                            break;
                        case "STYLE_DIGEST":
                            st.style = StyleType.DIGEST;
                            break;
                    }
                    if (st.action == ActionType.GENERATE) {
                        sendText(chat, "📝 Введите тему статьи:");
                    } else {
                        st.awaitingOriginal = true;
                        sendText(chat, "🔄 Пришлите статью для рерайта:");
                    }
                }
        }
    }

    private void handleText(Message msg) {
        long chat = msg.getChatId();
        String txt = msg.getText();
        UserState st = states.get(chat);

        if ("Главное меню".equalsIgnoreCase(txt)) {
            resetUserState(chat);
            sendActionChoice(chat);
            return;
        }
        if (st == null) {
            if (!greeted.contains(chat)) {
                sendActionChoice(chat);
                greeted.add(chat);
            } else {
                sendActionChoice(chat);
            }
            return;
        }
        if (st.awaitingOriginal) {
            st.originalText = txt;
            st.awaitingOriginal = false;
            st.awaitingFeedback = true;
            sendText(chat, "✏️ Укажите, что изменить:");
            return;
        }
        if (st.awaitingFeedback) {
            sendText(chat, "⏳ Переписываю...");
            ArticleResult ar = callRewrite(chat, st.originalText, txt);
            if (ar == null) {
                sendText(chat, "❌ Ошибка при рерайте.");
                sendActionChoice(chat);
            } else {
                lastResults.put(chat, ar);
                if (ar.zenDocumentId != null) sendZenArticleButtons(chat, ar);
                else sendArticleWithButtons(chat, ar);
            }
            states.remove(chat);
            return;
        }
        if (st.action == ActionType.GENERATE) {
            if (st.topic == null) {
                st.topic = txt;
                sendText(chat, "📝 Опишите подробнее:");
                return;
            }
            if (st.description == null) {
                st.description = txt;
                sendText(chat, "⏳ Генерирую...");
                ArticleResult ar = fetchFromN8n(chat, st);
                if (ar == null) {
                    sendText(chat, "❌ Ошибка генерации.");
                    sendActionChoice(chat);
                } else {
                    lastResults.put(chat, ar);
                    if (ar.zenDocumentId != null) sendZenArticleButtons(chat, ar);
                    else sendArticleWithButtons(chat, ar);
                }
            }
        }
    }

    private void sendActionChoice(long chat) {
        InlineKeyboardButton gen = new InlineKeyboardButton("📝 Генерировать");
        gen.setCallbackData("ACT_GEN");
        InlineKeyboardButton rew = new InlineKeyboardButton("✍️ Переписать");
        rew.setCallbackData("ACT_REWRITE");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(gen),
                        Collections.singletonList(rew)
                )
        );
        sendMessage(chat, "Выберите действие:", kb);
    }

    private void sendLengthChoice(long chat) {
        InlineKeyboardButton short_btn = new InlineKeyboardButton("📄 2000-3000 символов");
        short_btn.setCallbackData("LEN_SHORT");
        InlineKeyboardButton medium_btn = new InlineKeyboardButton("📝 4000-5000 символов");
        medium_btn.setCallbackData("LEN_MEDIUM");
        InlineKeyboardButton long_btn = new InlineKeyboardButton("📚 5000+ символов");
        long_btn.setCallbackData("LEN_LONG");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(short_btn),
                        Collections.singletonList(medium_btn),
                        Collections.singletonList(long_btn)
                )
        );
        sendMessage(chat, "Выберите количество символов для статьи:", kb);
    }

    private void sendStyleChoice(long chat) {
        InlineKeyboardButton story = new InlineKeyboardButton("💬 Разговорный сторителлинг");
        story.setCallbackData("STYLE_STORY");
        InlineKeyboardButton expert = new InlineKeyboardButton("🎓 Экспертно-образовательный");
        expert.setCallbackData("STYLE_EXPERT");
        InlineKeyboardButton digest = new InlineKeyboardButton("📰 Краткий дайджест");
        digest.setCallbackData("STYLE_DIGEST");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(story),
                        Collections.singletonList(expert),
                        Collections.singletonList(digest)
                )
        );
        sendMessage(chat, "Выберите стиль статьи:", kb);
    }

    private void sendArticleWithButtons(long chat, ArticleResult ar) {
        InlineKeyboardButton re = new InlineKeyboardButton("✍️ Переписать");
        re.setCallbackData("REREWRITE");
        InlineKeyboardButton pu = new InlineKeyboardButton("🚀 Запостить");
        pu.setCallbackData("PUBLISH_TG");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(re),
                        Collections.singletonList(pu)
                )
        );
        if (ar.picture != null && !ar.picture.isEmpty()) {
            SendPhoto ph = new SendPhoto();
            ph.setChatId(String.valueOf(chat));
            ph.setPhoto(new InputFile(ar.picture));
            String full = ar.text;
            ph.setCaption(full.length() <= 1024 ? full : full.substring(0, 1024));
            ph.setReplyMarkup(kb);
            safeSend(ph);
            if (full.length() > 1024) sendText(chat, full.substring(1024));
        } else {
            SendMessage m = new SendMessage(String.valueOf(chat), ar.text);
            m.setReplyMarkup(kb);
            executeSilently(m);
        }
    }

    private void sendZenArticleButtons(long chat, ArticleResult ar) {
        InlineKeyboardButton view = new InlineKeyboardButton("👀 Посмотреть");
        view.setUrl(GOOGLE_DOCS_URL_PREFIX + ar.zenDocumentId);
        InlineKeyboardButton re = new InlineKeyboardButton("✍️ Переписать");
        re.setCallbackData("REREWRITE");
        InlineKeyboardButton pu = new InlineKeyboardButton("🚀 Запостить");
        pu.setCallbackData("PUBLISH_ZEN");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(view),
                        Arrays.asList(re, pu)
                )
        );
        sendMessage(chat, "✅ Статья готова! Вы можете:", kb);
    }

    private ArticleResult fetchFromN8n(long chat, UserState st) {
        String lengthParam = "";
        switch (st.length) {
            case SHORT:
                lengthParam = "2000-3000";
                break;
            case MEDIUM:
                lengthParam = "4000-5000";
                break;
            case LONG:
                lengthParam = "5000+";
                break;
        }

        String styleParam = "";
        switch (st.style) {
            case STORYTELLING:
                styleParam = "storytelling";
                break;
            case EXPERT:
                styleParam = "expert";
                break;
            case DIGEST:
                styleParam = "digest";
                break;
        }

        String payload = String.format(Locale.ROOT,
                "{\"chat_id\":%d,\"action\":\"%s\",\"length\":\"%s\",\"style\":\"%s\"," +
                        "\"topic\":\"%s\",\"description\":\"%s\"}",
                chat, st.action.name().toLowerCase(),
                lengthParam, styleParam,
                escape(st.topic), escape(st.description)
        );
        return callN8n(payload, true);
    }

    private ArticleResult callRewrite(long chat, String orig, String fb) {
        UserState st = states.get(chat);
        if (st == null) {
            sendText(chat, "❌ Ошибка состояния. Попробуйте заново.");
            sendActionChoice(chat);
            return null;
        }

        String lengthParam = "";
        switch (st.length) {
            case SHORT:
                lengthParam = "2000-3000";
                break;
            case MEDIUM:
                lengthParam = "4000-5000";
                break;
            case LONG:
                lengthParam = "5000+";
                break;
        }

        String styleParam = "";
        switch (st.style) {
            case STORYTELLING:
                styleParam = "storytelling";
                break;
            case EXPERT:
                styleParam = "expert";
                break;
            case DIGEST:
                styleParam = "digest";
                break;
        }

        String payload = String.format(Locale.ROOT,
                "{\"chat_id\":%d,\"action\":\"rewrite\",\"length\":\"%s\",\"style\":\"%s\"," +
                        "\"original\":\"%s\",\"feedback\":\"%s\"}",
                chat, lengthParam, styleParam,
                escape(orig), escape(fb)
        );
        return callN8n(payload, false);
    }

    private ArticleResult callN8n(String payload, boolean isGeneration) {
        System.out.println("→ n8n payload: " + payload);
        RequestBody body = RequestBody.create(payload, MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(N8N_WEBHOOK_URL).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            String s = resp.body() != null ? resp.body().string() : null;
            System.out.println("← n8n response: " + s);
            if (!resp.isSuccessful() || s == null) return null;

            if (s.startsWith("[")) {
                JSONArray arr = new JSONArray(s);
                if (arr.length() > 0) {
                    JSONObject j = arr.getJSONObject(0);
                    if (j.has("output")) {
                        JSONObject output = j.getJSONObject("output");
                        String text = output.optString("text");
                        String picture = output.optString("picture");
                        String docId = j.optString("documentId");
                        ArticleResult result = new ArticleResult(docId);
                        result.text = text;
                        result.picture = picture;
                        return result;
                    }
                }
            } else {
                JSONObject j = new JSONObject(s);
                String text = j.optString("text");
                String picture = j.optString("picture");
                return new ArticleResult(text, picture);
            }
            return null;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendToChannel(String text, String pic) {
        if (pic != null && !pic.isEmpty()) {
            SendPhoto ph = new SendPhoto();
            ph.setChatId(CHANNEL_ID);
            ph.setPhoto(new InputFile(pic));
            ph.setCaption(text);
            safeSend(ph);
        } else {
            sendText(Long.parseLong(CHANNEL_ID), text);
        }
    }

    private void saveToBaserow(String text, String imageUrl) {
        try {
            System.out.println("Начало сохранения в Baserow...");

            if (text == null || text.isEmpty()) {
                System.err.println("Ошибка: Пустой текст статьи");
                return;
            }

            JSONObject payload = new JSONObject();
            payload.put("content", text);
            payload.put("date_created", Instant.now().toString());

            System.out.println("Полученный URL картинки: " + imageUrl);

            if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    System.out.println("Загрузка изображения на ImgBB...");
                    String permanentUrl = uploadImageToImgBB(imageUrl);
                    System.out.println("Получен постоянный URL: " + permanentUrl);
                    payload.put("image_url", permanentUrl);
                } catch (IOException e) {
                    System.err.println("Ошибка загрузки на ImgBB:");
                    e.printStackTrace();
                    payload.put("image_url", imageUrl); // Fallback - сохраняем оригинальный URL
                }
            } else {
                System.out.println("Картинка отсутствует - пропускаем");
            }

            System.out.println("Отправляемые данные в Baserow:");
            System.out.println(payload.toString(2));

            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASEROW_API_URL)
                    .header("Authorization", "Token " + BASEROW_TOKEN)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                System.out.println("Ответ Baserow. Код: " + response.code());

                if (!response.isSuccessful()) {
                    System.err.println("Ошибка Baserow!");
                    if (response.body() != null) {
                        String errorBody = response.body().string();
                        System.err.println("Тело ошибки: " + errorBody);
                    }
                } else {
                    String responseBody = response.body() != null ? response.body().string() : "null";
                    System.out.println("Успешный ответ: " + responseBody);
                }
            }
        } catch (Exception e) {
            System.err.println("Критическая ошибка:");
            e.printStackTrace();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void sendText(long chat, String text) {
        SendMessage m = new SendMessage(String.valueOf(chat), text);
        m.setReplyMarkup(mainMenuKeyboard);
        executeSilently(m);
    }

    private void sendMessage(long chat, String text, InlineKeyboardMarkup inlineKb) {
        SendMessage m = new SendMessage(String.valueOf(chat), text);
        m.setReplyMarkup(inlineKb);
        executeSilently(m);
    }

    private void executeSilently(SendMessage m) {
        try { execute(m); } catch (Exception ignore) {}
    }

    private void executeSilently(DeleteMessage m) {
        try { execute(m); } catch (Exception ignore) {}
    }

    private void safeSend(SendPhoto p) {
        try { execute(p); } catch (Exception ignore) {}
    }

    @Override public String getBotUsername() { return BOT_USERNAME; }
    @Override public String getBotToken() { return BOT_TOKEN; }

    public static void main(String[] args) {
        try {
            System.out.println("=== Starting Bot ===");
            System.out.println("BOT_USERNAME: " + (BOT_USERNAME != null ? BOT_USERNAME : "NULL"));
            System.out.println("BOT_TOKEN: " + (BOT_TOKEN != null ? "***" + BOT_TOKEN.substring(BOT_TOKEN.length()-4) : "NULL"));

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            TelegramArticleBot bot = new TelegramArticleBot();
            try {
                bot.execute(new DeleteWebhook());
                System.out.println("Webhook successfully removed");
            } catch (Exception e) {
                System.err.println("Warning: Could not remove webhook - " + e.getMessage());
            }
            botsApi.registerBot(bot);


            System.out.println("✅ Bot successfully started!");
        } catch (Exception e) {
            System.err.println("❌ Failed to start bot:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
