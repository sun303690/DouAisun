package com.douai.module.config;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AnalyticsAiSettingsTest {
    @Test public void analysisSelectsAnExistingSupplierWithoutChangingOtherModelSelections() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment");
        store.putString(AnalyticsAiSettings.MODEL_ID, "comment");
        String before = store.getString(AiModelSettings.KEY, "");
        store.writes = 0;

        AiModelConfig selected = AnalyticsAiSettings.models(store).selected();
        assertEquals("comment", selected.id);
        assertEquals("https://comment.example.test/v1", selected.apiEndpoint);
        assertEquals("key-comment", selected.apiKey);
        assertEquals("comment-model", selected.modelName);
        assertEquals("primary-comment", selected.systemPrompt);
        assertTrue(selected.streaming());
        assertEquals("global", AiModelSettings.read(store).activeId);
        assertEquals("reply", AiModelSettings.readForAutomation(store, "reply").selected().id);
        assertEquals("comment", AiModelSettings.readForAutomation(store, "comment").selected().id);
        assertEquals(before, store.getString(AiModelSettings.KEY, ""));
        assertEquals("Analysis selection must never rewrite saved model profiles", 0, store.writes);
    }

    @Test public void missingOrClearedSelectionKeepsFollowingTheGlobalModel() throws Exception {
        MemoryStore store = configuredStore("global");
        assertEquals("global", AnalyticsAiSettings.models(store).selected().id);
        store.putString(AnalyticsAiSettings.MODEL_ID, "");
        assertEquals("global", AnalyticsAiSettings.models(store).selected().id);
        AiModelSettings.read(store).activate("reply").save(store);
        assertEquals("reply", AnalyticsAiSettings.models(store).selected().id);
    }

    @Test public void explicitAnalysisSelectionWorksWithoutAGlobalActiveModel() throws Exception {
        MemoryStore store = configuredStore("");
        store.putString(AnalyticsAiSettings.MODEL_ID, "comment");
        assertEquals("comment", AnalyticsAiSettings.models(store).selected().id);
        assertEquals("", AiModelSettings.read(store).activeId);
    }

    @Test public void deletedAnalysisSelectionDoesNotSilentlyUseAnotherSupplier() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString(AnalyticsAiSettings.MODEL_ID, "deleted-private-identifier");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AnalyticsAiSettings.models(store));
        assertTrue(failure.getMessage().contains("本地数据分析"));
        assertTrue(failure.getMessage().contains("请重新选择"));
        assertFalse(failure.getMessage().contains("deleted-private-identifier"));
        assertEquals("global", AiModelSettings.read(store).selected().id);
    }

    @Test public void analysisSecondaryPromptIsIndependentAndPreservedWithoutTruncation() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString("reply_prompt", "私信任务提示");
        store.putString("comment_prompt", "评论任务提示");
        String savedModels = store.getString(AiModelSettings.KEY, "");
        String prompt = "  独立分析任务\n" + "比较工作日与周末的统计差异。".repeat(1000) + "\n  ";
        store.putString(AnalyticsAiSettings.SECONDARY_PROMPT, prompt);
        assertEquals(prompt, AnalyticsAiSettings.secondaryPrompt(store));
        assertEquals("私信任务提示", store.getString("reply_prompt", ""));
        assertEquals("评论任务提示", store.getString("comment_prompt", ""));
        assertEquals(savedModels, store.getString(AiModelSettings.KEY, ""));
        assertEquals("primary-global", AiModelSettings.read(store).selected().systemPrompt);
    }

    @Test public void missingNullOrBlankSecondaryPromptRetainsTheOriginalAnalysisTask() {
        MemoryStore store = new MemoryStore();
        assertEquals(AnalyticsAiSettings.DEFAULT_SECONDARY_PROMPT, AnalyticsAiSettings.secondaryPrompt(store));
        for (String value : new String[]{null, "", " \t\n "}) {
            store.putString(AnalyticsAiSettings.SECONDARY_PROMPT, value);
            assertEquals(AnalyticsAiSettings.DEFAULT_SECONDARY_PROMPT, AnalyticsAiSettings.secondaryPrompt(store));
        }
    }

    private static MemoryStore configuredStore(String activeId) throws Exception {
        MemoryStore store = new MemoryStore();
        new AiModelSettings(List.of(profile("global", false), profile("reply", false), profile("comment", true)), activeId)
                .save(store);
        return store;
    }

    private static AiModelConfig profile(String id, boolean streaming) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", "供应商 " + id)
                .put("apiEndpoint", "https://" + id + ".example.test/v1").put("apiKey", "key-" + id)
                .put("modelName", id + "-model").put("systemPrompt", "primary-" + id)
                .put("responseMode", streaming ? AiModelConfig.RESPONSE_STREAM : AiModelConfig.RESPONSE_SYNC));
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        int writes;
        @Override public FeatureSettings load() { return FeatureSettings.defaults(); }
        @Override public void save(FeatureSettings settings) { }
        @Override public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        @Override public void putString(String key, String value) { values.put(key, value); writes++; }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); writes++; }
    }
}
