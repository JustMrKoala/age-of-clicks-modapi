package aoc.diplomacy;

import aoc.api.settings.ModSettings;
import aoc.api.settings.Setting;

import java.util.List;

/**
 * The mod's settings, edited in main menu > MODS > Code Mods and stored in
 * {@code mods/diplomacy/config.properties}. Values are read live, so saved changes apply
 * to the next message without a restart.
 */
final class Config {
    private final ModSettings settings;
    private final Setting<String> baseUrl;
    private final Setting<String> apiKey;
    private final Setting<String> model;
    private final Setting<Double> temperature;
    private final Setting<Integer> maxTokens;
    private final Setting<Boolean> jsonMode;
    private final Setting<String> hotkey;
    private final Setting<Boolean> allowWarOnPlayer;

    Config(ModSettings settings) {
        this.settings = settings;
        apiKey = settings.secret("api_key", "API key",
                "Key for the AI service. Free Groq keys: console.groq.com/keys. "
                        + "Leave empty to use the AOC_LLM_API_KEY environment variable.");
        baseUrl = settings.text("base_url", "AI service address", "https://api.groq.com/openai/v1",
                "Any OpenAI-compatible API. Groq: https://api.groq.com/openai/v1   OpenRouter: https://openrouter.ai/api/v1   "
                        + "OpenAI: https://api.openai.com/v1   Ollama (local): http://localhost:11434/v1");
        model = settings.text("model", "Model", "openai/gpt-oss-120b",
                "Groq: openai/gpt-oss-120b (smart) or qwen/qwen3.8-27b (lighter on rate limits).");
        temperature = settings.decimal("temperature", "Creativity", 0.8, 0, 2,
                "0 = predictable leaders, 1+ = more surprising. 0.8 suits role-play.");
        maxTokens = settings.integer("max_tokens", "Reply length limit", 1000, 200, 8000,
                "Tokens per reply, including the model's reasoning. Lower values help with free-tier rate limits.");
        jsonMode = settings.toggle("json_mode", "Strict JSON replies", true,
                "Asks the service for strict JSON. Turn off if your provider rejects response_format.");
        hotkey = settings.choice("hotkey", "Hotkey", "F8",
                List.of("F6", "F7", "F8", "F9", "F10", "F11", "F12"), "Opens and closes the diplomacy window in game.");
        allowWarOnPlayer = settings.toggle("allow_war_on_player", "Leaders may declare war on you", true,
                "When off, insulted or threatened leaders can still threaten you, but never attack.");
    }

    ModSettings settings() { return settings; }

    String baseUrl() {
        String url = baseUrl.get().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    String apiKey() {
        String key = apiKey.get().trim();
        return key.isEmpty() ? System.getenv().getOrDefault("AOC_LLM_API_KEY", "").trim() : key;
    }

    String model() { return model.get().trim(); }

    double temperature() { return temperature.get(); }

    int maxTokens() { return maxTokens.get(); }

    boolean jsonMode() { return jsonMode.get(); }

    String hotkey() { return hotkey.get(); }

    boolean allowWarOnPlayer() { return allowWarOnPlayer.get(); }
}
