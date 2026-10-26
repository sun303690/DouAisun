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
public final class AiModelAutomationSelectionTest {
    @Test public void replyAndCommentSelectDifferentExistingProfilesWithoutChangingTheGlobalProfile() throws Exception {
        MemoryStore store = configuredStore("global");
        String saved = store.getString(AiModelSettings.KEY, "");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        store.writes = 0;

        AiModelConfig reply = AiModelSettings.readForAutomation(store, "reply").selected();
        AiModelConfig comment = AiModelSettings.readForAutomation(store, "comment").selected();
        assertEquals("reply-provider", reply.id);
        assertEquals("comment-provider", comment.id);
        assertEquals("https://reply-provider.example.test/v1", reply.apiEndpoint);
        assertEquals("https://comment-provider.example.test/v1", comment.apiEndpoint);
        assertEquals("key-reply-provider", reply.apiKey);
        assertEquals("key-comment-provider", comment.apiKey);
        assertEquals("model-reply-provider", reply.modelName);
        assertEquals("model-comment-provider", comment.modelName);
        assertEquals("prompt-reply-provider", reply.systemPrompt);
        assertEquals("prompt-comment-provider", comment.systemPrompt);
        assertTrue(reply.streaming());
        assertFalse(comment.streaming());
        assertEquals("global", AiModelSettings.read(store).activeId);
        assertEquals(saved, store.getString(AiModelSettings.KEY, ""));
        assertEquals("Reading an automation selection must not rewrite model settings", 0, store.writes);
    }

    @Test public void omittedOrClearedBindingsKeepFollowingTheGlobalProfile() throws Exception {
        MemoryStore store = configuredStore("global");
        for (String kind : new String[]{"reply", "comment", "comment_reply"}) {
            assertEquals("global", AiModelSettings.readForAutomation(store, kind).selected().id);
            store.putString(AiModelSettings.automationModelKey(kind), "");
            assertEquals("global", AiModelSettings.readForAutomation(new LocalOptions(store.values), kind).selected().id);
        }
        AiModelSettings.read(store).activate("reply-provider").save(store);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "reply").selected().id);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "comment").selected().id);
    }

    @Test public void commentRepliesUseTheReplyBindingAndBothAutomationsCanSelectTheSameProfile() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        LocalOptions options = new LocalOptions(store.values);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(options, "comment_reply").selected().id);
        assertEquals(AiModelSettings.REPLY_MODEL_ID, AiModelSettings.automationModelKey("comment_reply"));
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "reply-provider");
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "reply").selected().id);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "comment").selected().id);
    }

    @Test public void boundProfilesWorkWhenThereIsNoGlobalActiveProfile() throws Exception {
        MemoryStore store = configuredStore("");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "reply").selected().id);
        assertEquals("comment-provider", AiModelSettings.readForAutomation(store, "comment").selected().id);
        assertEquals("", AiModelSettings.read(store).activeId);
    }

    @Test public void missingBoundProfilesNeverSilentlyUseAnotherProvider() throws Exception {
        MemoryStore store = configuredStore("global");
        for (String kind : new String[]{"reply", "comment", "comment_reply"}) {
            store.putString(AiModelSettings.automationModelKey(kind), "deleted-profile");
            IllegalStateException storeFailure = assertThrows(IllegalStateException.class,
                    () -> AiModelSettings.readForAutomation(store, kind));
            IllegalStateException snapshotFailure = assertThrows(IllegalStateException.class,
                    () -> AiModelSettings.readForAutomation(new LocalOptions(store.values), kind));
            assertEquals(storeFailure.getMessage(), snapshotFailure.getMessage());
            assertTrue(storeFailure.getMessage().contains("请重新选择"));
            assertFalse(storeFailure.getMessage().contains("deleted-profile"));
        }
        assertEquals("global", AiModelSettings.read(store).selected().id);
        assertThrows(IllegalArgumentException.class, () -> AiModelSettings.automationModelKey("manual"));
    }

    private static MemoryStore configuredStore(String activeId) throws Exception {
        MemoryStore store = new MemoryStore();
        AiModelConfig global = profile("global", false);
        AiModelConfig reply = profile("reply-provider", true);
        AiModelConfig comment = profile("comment-provider", false);
        new AiModelSettings(List.of(global, reply, comment), activeId).save(store);
        return store;
    }

    private static AiModelConfig profile(String id, boolean streaming) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", "配置 " + id)
                .put("provider", id).put("apiEndpoint", "https://" + id + ".example.test/v1")
                .put("apiKey", "key-" + id).put("modelName", "model-" + id)
                .put("systemPrompt", "prompt-" + id).put("responseMode",
                        streaming ? AiModelConfig.RESPONSE_STREAM : AiModelConfig.RESPONSE_SYNC));
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
