import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class TelegramArticleBot extends TelegramLongPollingBot {

    // =============== НАСТРОЙКИ ================
    private static final String BOT_TOKEN = System.getenv("BOT_TOKEN") != null ?
            System.getenv("BOT_TOKEN") : "7611891299:AAFo7yWNXV-5j6i-p5y__waD4DXc28Z86uM";
    private static final String BOT_USERNAME = System.getenv("BOT_USERNAME") != null ?
            System.getenv("BOT_USERNAME") : "ClientSeo_bot";

    private static final String N8N_WEBHOOK_URL = System.getenv("N8N_WEBHOOK_URL") != null ?
            System.getenv("N8N_WEBHOOK_URL") :
            "https://primary-production-98a7.up.railway.app/webhook/026f5739-e42e-4cbc-a052-c19ec813a9de";

    private static final String CHANNEL_ID = System.getenv("CHANNEL_ID");
    private static final String GOOGLE_DOCS_URL = "https://docs.google.com/document/d/1HbO62iTUzqMrhb1Npnf8HToze7Jp7LrX_-RAS7tcD6w/edit?tab=t.0";

    // Enums для типов
    private enum ActionType { GENERATE, REWRITE }
    private enum LengthType { SHORT, MEDIUM, LONG }
    private enum StyleType { STORYTELLING, EXPERT, DIGEST }
    private enum InputType { TEXT, VOICE }

    // Класс для хранения данных ввода
    private static class InputData {
        String content;        // Текст или base64 аудио
        InputType type;       // Тип ввода
        byte[] audioData;     // Сырые данные аудио (если голос)

        InputData(String content, InputType type) {
            this.content = content;
            this.type = type;
        }

        InputData(String content, InputType type, byte[] audioData) {
            this.content = content;
            this.type = type;
            this.audioData = audioData;
        }
    }

    // Класс состояния пользователя
    private static class UserState {
        ActionType action;
        LengthType length;
        StyleType style;

        // Для генерации - храним два ввода
        InputData topicInput;        // Первый ввод (тема)
        InputData descriptionInput;   // Второй ввод (описание)

        // Для рерайта
        InputData originalInput;      // Оригинальный текст
        InputData feedbackInput;      // Фидбек

        boolean isProcessing = false;
        long lastActivityTime = System.currentTimeMillis();

        // Методы для проверки готовности
        boolean isReadyForGeneration() {
            return action == ActionType.GENERATE &&
                    topicInput != null &&
                    descriptionInput != null;
        }

        boolean isReadyForRewrite() {
            return action == ActionType.REWRITE &&
                    originalInput != null &&
                    feedbackInput != null;
        }

        void updateActivity() {
            lastActivityTime = System.currentTimeMillis();
        }
    }

    // Класс результата
    private static class ArticleResult {
        String text;
        String documentUrl;
        boolean success;

        ArticleResult(String text, boolean success) {
            this.text = text;
            this.success = success;
            this.documentUrl = GOOGLE_DOCS_URL;
        }
    }

    private final Map<Long, UserState> states = new HashMap<>();
    private final Map<Long, ArticleResult> lastResults = new HashMap<>();
    private final Set<Long> greeted = new HashSet<>();

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build();

    // Клавиатура главного меню
    private final ReplyKeyboardMarkup mainMenuKeyboard;
    {
        mainMenuKeyboard = new ReplyKeyboardMarkup();
        mainMenuKeyboard.setResizeKeyboard(true);
        mainMenuKeyboard.setOneTimeKeyboard(false);
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton("🏠 Главное меню"));
        row.add(new KeyboardButton("❌ Отмена"));
        List<KeyboardRow> rows = new ArrayList<>();
        rows.add(row);
        mainMenuKeyboard.setKeyboard(rows);
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            // Команда /start
            if (update.hasMessage() && update.getMessage().hasText()) {
                String text = update.getMessage().getText();
                if ("/start".equals(text)) {
                    long chatId = update.getMessage().getChatId();
                    resetUserState(chatId);
                    sendWelcomeMessage(chatId);
                    sendActionChoice(chatId);
                    return;
                }
            }

            // Обработка callback кнопок
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }

            // Обработка сообщений
            if (update.hasMessage()) {
                Message message = update.getMessage();
                long chatId = message.getChatId();

                // Обработка специальных команд
                if (message.hasText()) {
                    String text = message.getText();
                    if ("🏠 Главное меню".equals(text)) {
                        resetUserState(chatId);
                        sendActionChoice(chatId);
                        return;
                    }
                    if ("❌ Отмена".equals(text)) {
                        resetUserState(chatId);
                        sendText(chatId, "❌ Операция отменена");
                        sendActionChoice(chatId);
                        return;
                    }
                }

                // Обработка обычных сообщений
                UserState state = states.get(chatId);
                if (state == null) {
                    if (!greeted.contains(chatId)) {
                        sendWelcomeMessage(chatId);
                        greeted.add(chatId);
                    }
                    sendActionChoice(chatId);
                    return;
                }

                // Обновляем время активности
                state.updateActivity();

                // Обработка голосовых сообщений
                if (message.hasVoice()) {
                    handleVoiceInput(message, state);
                }
                // Обработка текстовых сообщений
                else if (message.hasText()) {
                    handleTextInput(message, state);
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка обработки update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Обработка текстового ввода
     */
    private void handleTextInput(Message message, UserState state) {
        long chatId = message.getChatId();
        String text = message.getText();

        if (state.isProcessing) {
            sendText(chatId, "⏳ Подождите, идет обработка предыдущего запроса...");
            return;
        }

        // Создаем InputData для текста
        InputData input = new InputData(text, InputType.TEXT);

        // Обрабатываем в зависимости от действия
        processInput(chatId, state, input);
    }

    /**
     * Обработка голосового ввода
     */
    private void handleVoiceInput(Message message, UserState state) {
        long chatId = message.getChatId();

        if (state.isProcessing) {
            sendText(chatId, "⏳ Подождите, идет обработка предыдущего запроса...");
            return;
        }

        sendText(chatId, "🎤 Получено голосовое сообщение, сохраняю...");

        try {
            // Скачиваем голосовой файл
            Voice voice = message.getVoice();
            GetFile getFile = new GetFile();
            getFile.setFileId(voice.getFileId());

            org.telegram.telegrambots.meta.api.objects.File file = execute(getFile);
            String fileUrl = "https://api.telegram.org/file/bot" + BOT_TOKEN + "/" + file.getFilePath();

            Request request = new Request.Builder().url(fileUrl).build();
            byte[] audioData;

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Не удалось скачать файл");
                }
                audioData = response.body().bytes();
            }

            // Создаем InputData для голоса
            String base64Audio = Base64.getEncoder().encodeToString(audioData);
            InputData input = new InputData(base64Audio, InputType.VOICE, audioData);

            // Обрабатываем ввод
            processInput(chatId, state, input);

        } catch (Exception e) {
            System.err.println("Ошибка обработки голоса: " + e.getMessage());
            sendText(chatId, "❌ Ошибка обработки голосового сообщения. Попробуйте еще раз.");
        }
    }

    /**
     * Обработка любого ввода (текст или голос)
     */
    private void processInput(long chatId, UserState state, InputData input) {
        // Для генерации статьи
        if (state.action == ActionType.GENERATE) {
            // Первый ввод - тема
            if (state.topicInput == null) {
                state.topicInput = input;

                String inputTypeText = input.type == InputType.VOICE ?
                        "🎤 Голосовое сообщение (тема)" : "📝 Текст (тема)";

                sendText(chatId,
                        "✅ Первый ввод получен: " + inputTypeText + "\n\n" +
                                "2️⃣ Шаг 2 из 2: Теперь отправьте ОПИСАНИЕ темы\n" +
                                "🎤 Можете записать голосовое сообщение\n" +
                                "📝 Или написать текстом\n\n" +
                                "Это будет подробное описание того, о чем должна быть статья");
                return;
            }

            // Второй ввод - описание
            if (state.descriptionInput == null) {
                state.descriptionInput = input;

                String inputTypeText = input.type == InputType.VOICE ?
                        "🎤 Голосовое сообщение (описание)" : "📝 Текст (описание)";

                sendText(chatId, "✅ Второй ввод получен: " + inputTypeText);

                // Проверяем готовность и отправляем на обработку
                if (state.isReadyForGeneration()) {
                    processGeneration(chatId, state);
                }
                return;
            }
        }

        // Для рерайта
        if (state.action == ActionType.REWRITE) {
            // Первый ввод - оригинальный текст
            if (state.originalInput == null) {
                state.originalInput = input;

                String inputTypeText = input.type == InputType.VOICE ?
                        "🎤 Голосовое сообщение (оригинал)" : "📝 Текст (оригинал)";

                sendText(chatId,
                        "✅ Первый ввод получен: " + inputTypeText + "\n\n" +
                                "2️⃣ Шаг 2 из 2: Что нужно изменить?\n" +
                                "🎤 Можете записать голосовое сообщение\n" +
                                "📝 Или написать текстом");
                return;
            }

            // Второй ввод - фидбек
            if (state.feedbackInput == null) {
                state.feedbackInput = input;

                String inputTypeText = input.type == InputType.VOICE ?
                        "🎤 Голосовое сообщение (правки)" : "📝 Текст (правки)";

                sendText(chatId, "✅ Второй ввод получен: " + inputTypeText);

                // Проверяем готовность и отправляем на обработку
                if (state.isReadyForRewrite()) {
                    processRewrite(chatId, state);
                }
                return;
            }
        }
    }

    /**
     * Обработка генерации когда оба ввода получены
     */
    private void processGeneration(long chatId, UserState state) {
        state.isProcessing = true;

        sendText(chatId,
                "📊 Собранные данные:\n" +
                        "• Тема: " + (state.topicInput.type == InputType.VOICE ? "🎤 Голос" : "📝 Текст") + "\n" +
                        "• Описание: " + (state.descriptionInput.type == InputType.VOICE ? "🎤 Голос" : "📝 Текст") + "\n" +
                        "• Длина: " + getLengthDescription(state.length) + "\n" +
                        "• Стиль: " + getStyleDescription(state.style) + "\n\n" +
                        "⏳ Отправляю на обработку...");

        try {
            JSONObject payload = new JSONObject();
            payload.put("chat_id", chatId);
            payload.put("action", "generate");
            payload.put("length", getLengthString(state.length));
            payload.put("style", getStyleString(state.style));

            // Добавляем данные о теме
            JSONObject topicData = new JSONObject();
            topicData.put("type", state.topicInput.type.toString());
            topicData.put("content", state.topicInput.content);
            payload.put("topic_data", topicData);

            // Добавляем данные об описании
            JSONObject descData = new JSONObject();
            descData.put("type", state.descriptionInput.type.toString());
            descData.put("content", state.descriptionInput.content);
            payload.put("description_data", descData);

            // Отправляем в N8N
            ArticleResult result = callN8n(payload);
            handleArticleResult(chatId, result);

        } catch (Exception e) {
            handleError(chatId, "Ошибка при генерации статьи", e);
        } finally {
            state.isProcessing = false;
            states.remove(chatId);
        }
    }

    /**
     * Обработка рерайта когда оба ввода получены
     */
    private void processRewrite(long chatId, UserState state) {
        state.isProcessing = true;

        sendText(chatId,
                "📊 Собранные данные:\n" +
                        "• Оригинал: " + (state.originalInput.type == InputType.VOICE ? "🎤 Голос" : "📝 Текст") + "\n" +
                        "• Правки: " + (state.feedbackInput.type == InputType.VOICE ? "🎤 Голос" : "📝 Текст") + "\n" +
                        "• Длина: " + getLengthDescription(state.length) + "\n" +
                        "• Стиль: " + getStyleDescription(state.style) + "\n\n" +
                        "⏳ Отправляю на обработку...");

        try {
            JSONObject payload = new JSONObject();
            payload.put("chat_id", chatId);
            payload.put("action", "rewrite");
            payload.put("length", getLengthString(state.length));
            payload.put("style", getStyleString(state.style));

            // Добавляем данные об оригинале
            JSONObject originalData = new JSONObject();
            originalData.put("type", state.originalInput.type.toString());
            originalData.put("content", state.originalInput.content);
            payload.put("original_data", originalData);

            // Добавляем данные о фидбеке
            JSONObject feedbackData = new JSONObject();
            feedbackData.put("type", state.feedbackInput.type.toString());
            feedbackData.put("content", state.feedbackInput.content);
            payload.put("feedback_data", feedbackData);

            // Отправляем в N8N
            ArticleResult result = callN8n(payload);
            handleArticleResult(chatId, result);

        } catch (Exception e) {
            handleError(chatId, "Ошибка при рерайте", e);
        } finally {
            state.isProcessing = false;
            states.remove(chatId);
        }
    }

    /**
     * Обработка callback кнопок
     */
    private void handleCallback(CallbackQuery callback) {
        long chatId = callback.getMessage().getChatId();
        String data = callback.getData();

        try {
            DeleteMessage deleteMessage = new DeleteMessage(
                    String.valueOf(chatId),
                    callback.getMessage().getMessageId()
            );
            execute(deleteMessage);

            UserState state = states.get(chatId);

            switch (data) {
                case "ACT_GEN":
                    state = new UserState();
                    state.action = ActionType.GENERATE;
                    states.put(chatId, state);
                    sendLengthChoice(chatId);
                    break;

                case "ACT_REW":
                    state = new UserState();
                    state.action = ActionType.REWRITE;
                    states.put(chatId, state);
                    sendLengthChoice(chatId);
                    break;

                case "LEN_SHORT":
                    if (state != null) {
                        state.length = LengthType.SHORT;
                        sendStyleChoice(chatId);
                    }
                    break;

                case "LEN_MEDIUM":
                    if (state != null) {
                        state.length = LengthType.MEDIUM;
                        sendStyleChoice(chatId);
                    }
                    break;

                case "LEN_LONG":
                    if (state != null) {
                        state.length = LengthType.LONG;
                        sendStyleChoice(chatId);
                    }
                    break;

                case "STYLE_STORY":
                    if (state != null) {
                        state.style = StyleType.STORYTELLING;
                        startArticleCreation(chatId, state);
                    }
                    break;

                case "STYLE_EXPERT":
                    if (state != null) {
                        state.style = StyleType.EXPERT;
                        startArticleCreation(chatId, state);
                    }
                    break;

                case "STYLE_DIGEST":
                    if (state != null) {
                        state.style = StyleType.DIGEST;
                        startArticleCreation(chatId, state);
                    }
                    break;

                case "PUBLISH":
                    ArticleResult lastResult = lastResults.get(chatId);
                    if (lastResult != null && CHANNEL_ID != null) {
                        publishToChannel(lastResult.text);
                        sendText(chatId, "✅ Статья опубликована в канал!");
                    } else {
                        sendText(chatId, "❌ Нет статьи для публикации или канал не настроен");
                    }
                    sendActionChoice(chatId);
                    break;

                case "MAIN_MENU":
                    resetUserState(chatId);
                    sendActionChoice(chatId);
                    break;
            }

        } catch (Exception e) {
            handleError(chatId, "Ошибка обработки команды", e);
        }
    }

    /**
     * Начало создания статьи
     */
    private void startArticleCreation(long chatId, UserState state) {
        String params = String.format(
                "📋 Параметры статьи:\n" +
                        "• Длина: %s\n" +
                        "• Стиль: %s\n\n",
                getLengthDescription(state.length),
                getStyleDescription(state.style)
        );

        if (state.action == ActionType.GENERATE) {
            sendText(chatId, params +
                    "📝 ГЕНЕРАЦИЯ СТАТЬИ\n\n" +
                    "1️⃣ Шаг 1 из 2: Отправьте ТЕМУ статьи\n" +
                    "🎤 Можете записать голосовое сообщение\n" +
                    "📝 Или написать текстом\n\n" +
                    "Например: 'Влияние искусственного интеллекта на медицину'");
        } else {
            sendText(chatId, params +
                    "✏️ РЕРАЙТ ТЕКСТА\n\n" +
                    "1️⃣ Шаг 1 из 2: Отправьте ОРИГИНАЛЬНЫЙ текст\n" +
                    "🎤 Можете записать голосовое сообщение\n" +
                    "📝 Или написать/вставить текст");
        }
    }
    /**
     * Очистка текста от лишних символов и форматирования
     */
    private String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // Убираем escape-последовательности
        text = text.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\");

        // Конвертируем markdown заголовки для Telegram
        text = text.replaceAll("^#{1,2}\\s+(.+)$", "**$1**"); // H1-H2 в bold
        text = text.replaceAll("^#{3,6}\\s+(.+)$", "_$1_");   // H3-H6 в italic

        // Убираем лишние символы из списков
        text = text.replaceAll("^\\s*[-*]\\s+", "• "); // Маркированные списки
        text = text.replaceAll("^\\s*(\\d+)\\.\\s+", "$1. "); // Нумерованные списки

        // Убираем двойные пробелы и лишние переносы
        text = text.replaceAll("\\s{2,}", " ")
                .replaceAll("\n{3,}", "\n\n");

        // Убираем markdown ссылки, оставляя только текст
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");

        return text.trim();
    }

    /**
     * Общий метод вызова N8N
     */
    private ArticleResult callN8n(JSONObject payload) {
        try {
            System.out.println("→ N8N request: " + payload.toString(2));

            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(N8N_WEBHOOK_URL)
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                System.out.println("← N8N response: " + responseBody);

                if (!response.isSuccessful() || responseBody.isEmpty()) {
                    return new ArticleResult("Ошибка получения ответа от сервера", false);
                }

                // Парсим JSON ответ
                try {
                    JSONObject json = new JSONObject(responseBody);

                    // Проверяем, это статус или готовая статья
                    if (json.has("status") && json.getString("status").equals("processing")) {
                        // Возвращаем специальный результат для ожидания
                        return new ArticleResult("PROCESSING", true);
                    }

                    // Извлекаем текст статьи из разных возможных форматов
                    String articleText = "";

                    if (json.has("text")) {
                        articleText = json.getString("text");
                    }

                    if (json.has("title")) {
                        String title = json.getString("title");
                        articleText = "📝 **" + title + "**\n\n" + articleText;
                    }

                    if (articleText.isEmpty()) {
                        // Если нет text или title, возвращаем как есть
                        return new ArticleResult(cleanText(responseBody), true);
                    }

                    return new ArticleResult(cleanText(articleText), true);

                } catch (Exception e) {
                    // Если не JSON, возвращаем как plain text
                    return new ArticleResult(cleanText(responseBody), true);
                }

            }

        } catch (Exception e) {
            System.err.println("Ошибка вызова N8N: " + e.getMessage());
            return new ArticleResult("Ошибка соединения с сервером", false);
        }
    }


    /**
     * Обработка результата
     */
    private void handleArticleResult(long chatId, ArticleResult result) {
        if (result.success) {
            String text = result.text;

            // Специальная обработка для статуса "PROCESSING"
            if ("PROCESSING".equals(text)) {
                sendText(chatId, "⏳ Статья генерируется...\n\nЭто может занять 1-2 минуты.\nВы получите уведомление, когда статья будет готова.");

                // Здесь можно добавить логику для ожидания реального результата
                // или webhook от N8N с готовой статьей
                return;
            }

            // Сохраняем результат
            lastResults.put(chatId, result);

            // Формируем клавиатуру с действиями (БЕЗ Google Docs)
            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rows = new ArrayList<>();

            // Кнопка публикации в канал (если настроен)
            if (CHANNEL_ID != null) {
                List<InlineKeyboardButton> row1 = new ArrayList<>();
                InlineKeyboardButton publishBtn = new InlineKeyboardButton();
                publishBtn.setText("📢 Опубликовать в канал");
                publishBtn.setCallbackData("PUBLISH");
                row1.add(publishBtn);
                rows.add(row1);
            }

            // Кнопка главного меню
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            InlineKeyboardButton menuBtn = new InlineKeyboardButton();
            menuBtn.setText("🏠 Главное меню");
            menuBtn.setCallbackData("MAIN_MENU");
            row2.add(menuBtn);
            rows.add(row2);

            keyboard.setKeyboard(rows);

            // Подготавливаем сообщение
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));

            // Обрезаем текст если слишком длинный
            if (text.length() > 4096) {
                // Telegram limit is 4096 characters
                text = text.substring(0, 4000) + "\n\n... (текст обрезан)";
            }

            message.setText("✅ Статья готова!\n\n" + text);
            message.setReplyMarkup(keyboard);
            message.setParseMode("Markdown"); // Включаем поддержку Markdown

            try {
                execute(message);
            } catch (Exception e) {
                // Попробуем без Markdown если не получилось
                message.setParseMode(null);
                message.setText("✅ Статья готова!\n\n" + text.replaceAll("[*_`\\[\\]]", ""));
                try {
                    execute(message);
                } catch (Exception ex) {
                    sendText(chatId, "✅ Статья готова, но слишком большая для отправки в Telegram.\n\nПопробуйте сгенерировать более короткую версию.");
                }
            }
        } else {
            sendText(chatId, "❌ " + result.text);
            sendActionChoice(chatId);
        }
    }

    /**
     * Публикация в канал
     */
    private void publishToChannel(String text) {
        if (CHANNEL_ID == null || CHANNEL_ID.isEmpty()) {
            return;
        }

        try {
            SendMessage message = new SendMessage();
            message.setChatId(CHANNEL_ID);

            if (text.length() > 4000) {
                text = text.substring(0, 3900) + "...\n\n📄 Полная версия: " + GOOGLE_DOCS_URL;
            }

            message.setText(text);
            execute(message);

        } catch (Exception e) {
            System.err.println("Ошибка публикации в канал: " + e.getMessage());
        }
    }

    // === Вспомогательные методы ===

    private void sendWelcomeMessage(long chatId) {
        String welcome = "👋 Добро пожаловать в SEO Article Bot!\n\n" +
                "Я помогу вам создать качественные статьи для вашего сайта.\n\n" +
                "🎯 Как это работает:\n" +
                "1. Выберите действие (генерация или рерайт)\n" +
                "2. Настройте параметры (длина и стиль)\n" +
                "3. Отправьте 2 сообщения:\n" +
                "   • Первое - тема/оригинал\n" +
                "   • Второе - описание/правки\n\n" +
                "💡 Можете использовать:\n" +
                "• 🎤 Голосовые сообщения\n" +
                "• 📝 Текстовые сообщения\n" +
                "• Любую комбинацию!";

        sendText(chatId, welcome);
    }

    private void sendActionChoice(long chatId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton genBtn = new InlineKeyboardButton();
        genBtn.setText("📝 Сгенерировать статью");
        genBtn.setCallbackData("ACT_GEN");
        row1.add(genBtn);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton rewBtn = new InlineKeyboardButton();
        rewBtn.setText("✏️ Переписать текст");
        rewBtn.setCallbackData("ACT_REW");
        row2.add(rewBtn);

        rows.add(row1);
        rows.add(row2);
        keyboard.setKeyboard(rows);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("🤖 Выберите действие:");
        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            System.err.println("Ошибка отправки меню: " + e.getMessage());
        }
    }

    private void sendLengthChoice(long chatId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        String[] options = {
                "📄 Короткая (2000-3000 символов)",
                "📝 Средняя (4000-5000 символов)",
                "📚 Длинная (5000+ символов)"
        };
        String[] callbacks = {"LEN_SHORT", "LEN_MEDIUM", "LEN_LONG"};

        for (int i = 0; i < options.length; i++) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(options[i]);
            btn.setCallbackData(callbacks[i]);
            row.add(btn);
            rows.add(row);
        }

        keyboard.setKeyboard(rows);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("📏 Выберите длину статьи:");
        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            System.err.println("Ошибка отправки выбора длины: " + e.getMessage());
        }
    }

    private void sendStyleChoice(long chatId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        String[] options = {
                "💬 Сторителлинг",
                "🎓 Экспертный",
                "📰 Дайджест"
        };
        String[] callbacks = {"STYLE_STORY", "STYLE_EXPERT", "STYLE_DIGEST"};

        for (int i = 0; i < options.length; i++) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(options[i]);
            btn.setCallbackData(callbacks[i]);
            row.add(btn);
            rows.add(row);
        }

        keyboard.setKeyboard(rows);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("✍️ Выберите стиль написания:");
        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            System.err.println("Ошибка отправки выбора стиля: " + e.getMessage());
        }
    }

    private void sendText(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(mainMenuKeyboard);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка отправки сообщения: " + e.getMessage());
        }
    }

    private void resetUserState(long chatId) {
        states.remove(chatId);
        lastResults.remove(chatId);
        System.out.println("State reset for chatId: " + chatId);
    }

    private String getLengthString(LengthType length) {
        if (length == null) return "4000-5000";
        switch (length) {
            case SHORT: return "2000-3000";
            case MEDIUM: return "4000-5000";
            case LONG: return "5000+";
            default: return "4000-5000";
        }
    }

    private String getStyleString(StyleType style) {
        if (style == null) return "storytelling";
        switch (style) {
            case STORYTELLING: return "storytelling";
            case EXPERT: return "expert";
            case DIGEST: return "digest";
            default: return "storytelling";
        }
    }

    private String getLengthDescription(LengthType length) {
        if (length == null) return "Средняя";
        switch (length) {
            case SHORT: return "Короткая (2000-3000 символов)";
            case MEDIUM: return "Средняя (4000-5000 символов)";
            case LONG: return "Длинная (5000+ символов)";
            default: return "Средняя";
        }
    }

    private String getStyleDescription(StyleType style) {
        if (style == null) return "Сторителлинг";
        switch (style) {
            case STORYTELLING: return "Сторителлинг (живой рассказ)";
            case EXPERT: return "Экспертный (профессиональная подача)";
            case DIGEST: return "Дайджест (краткие факты)";
            default: return "Сторителлинг";
        }
    }

    private void handleError(long chatId, String errorMessage, Exception e) {
        System.err.println("Error for chatId " + chatId + ": " + errorMessage);
        if (e != null) {
            e.printStackTrace();
        }

        sendText(chatId, "❌ " + errorMessage + "\n\n" +
                "Попробуйте еще раз или нажмите '🏠 Главное меню'");

        resetUserState(chatId);
    }

    // Очистка старых состояний (запускать периодически)
    private void cleanupOldStates() {
        long now = System.currentTimeMillis();
        long timeout = 30 * 60 * 1000; // 30 минут

        states.entrySet().removeIf(entry ->
                (now - entry.getValue().lastActivityTime) > timeout
        );
    }

    @Override
    public String getBotUsername() {
        return BOT_USERNAME;
    }

    @Override
    public String getBotToken() {
        return BOT_TOKEN;
    }

    // === Main метод ===
    public static void main(String[] args) {
        try {
            System.out.println("=== Starting Telegram Bot ===");
            System.out.println("Username: " + BOT_USERNAME);
            System.out.println("N8N URL: " + N8N_WEBHOOK_URL);
            System.out.println("Mode: Two-input collection");

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            TelegramArticleBot bot = new TelegramArticleBot();

            try {
                DeleteWebhook deleteWebhook = new DeleteWebhook();
                bot.execute(deleteWebhook);
                System.out.println("✅ Webhook удален");
            } catch (Exception e) {
                System.out.println("⚠️ Webhook не был установлен");
            }

            botsApi.registerBot(bot);
            System.out.println("✅ Bot started successfully!");
            System.out.println("🎤 Voice support: ENABLED");
            System.out.println("📝 Text support: ENABLED");
            System.out.println("✨ Mixed input: ENABLED");
            System.out.println("\nBot is ready! Send /start to begin");

            // Периодическая очистка старых состояний
            new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(600000); // 10 минут
                        bot.cleanupOldStates();
                        System.out.println("Old states cleaned up");
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }).start();

        } catch (Exception e) {
            System.err.println("❌ Failed to start bot:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
