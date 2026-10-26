package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

final class AutomationModelFixtures {
    static AiModelSettings profiles(MockWebServer global, MockWebServer reply, MockWebServer comment) throws Exception {
        return new AiModelSettings(List.of(profile("global", global, false), profile("reply", reply, true),
                profile("comment", comment, true)), "global");
    }

    private static AiModelConfig profile(String id, MockWebServer server, boolean streaming) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", id + " supplier")
                .put("provider", id).put("protocol", AiModelConfig.CHAT)
                .put("apiEndpoint", server.url("/v1").toString()).put("apiKey", id + "-key")
                .put("modelName", id + "-model").put("allowInsecureHttp", true).put("contextRounds", 1)
                .put("responseMode", streaming ? AiModelConfig.RESPONSE_STREAM : AiModelConfig.RESPONSE_SYNC));
    }

    static MockResponse streamed(String text) throws Exception {
        JSONObject frame = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("index", 0).put("delta", new JSONObject().put("content", text)).put("finish_reason", "stop")));
        return new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: " + frame + "\n\ndata: [DONE]\n\n");
    }

    private AutomationModelFixtures() { }
}
