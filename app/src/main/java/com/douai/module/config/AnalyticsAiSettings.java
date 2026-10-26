package com.douai.module.config;

/** Independent model and task prompt for explicitly requested local data analysis. */
public final class AnalyticsAiSettings {
    public static final String MODEL_ID = "analytics_model_id";
    public static final String SECONDARY_PROMPT = "analytics_prompt";
    public static final String DEFAULT_SECONDARY_PROMPT =
            "请根据附件的本地记录和可追溯融合指标，用中文完成人物侧写，并为每张图表给出对应解读。";

    private AnalyticsAiSettings() { }

    /** Resolves a saved profile without changing the global or automation selections. */
    public static AiModelSettings models(SettingsStore store) {
        AiModelSettings profiles = AiModelSettings.read(store);
        String id = store.getString(MODEL_ID, "");
        if (id == null || id.isEmpty()) return profiles;
        for (AiModelConfig config : profiles.configs) if (id.equals(config.id)) {
            return new AiModelSettings(profiles.configs, id);
        }
        throw new IllegalStateException("本地数据分析所选模型配置已不存在，请重新选择");
    }

    /** Empty saved values retain the original analysis task. Custom text is not rewritten. */
    public static String secondaryPrompt(SettingsStore store) {
        String prompt = store.getString(SECONDARY_PROMPT, "");
        return prompt == null || prompt.trim().isEmpty() ? DEFAULT_SECONDARY_PROMPT : prompt;
    }
}
