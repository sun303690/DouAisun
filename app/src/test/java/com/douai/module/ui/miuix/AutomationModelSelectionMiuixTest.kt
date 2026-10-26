package com.douai.module.ui.miuix

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.douai.module.config.AiModelConfig
import com.douai.module.config.AiModelSettings
import com.douai.module.config.HostSettingsStore
import com.douai.module.ui.ModuleSettingsScreen
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomationModelSelectionMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: HostSettingsStore
    private lateinit var screen: ModuleSettingsScreen
    private lateinit var savedProfiles: String

    @Before fun openSettings() {
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            store.putString("reply_mode", "ai")
            store.putString("comment_mode", "ai")
            AiModelSettings(listOf(profile("first", "供应商一", false),
                profile("second", "供应商二", true), profile("global", "供应商三", true)), "global").save(store)
            savedProfiles = store.getString(AiModelSettings.KEY, "")
            screen = ModuleSettingsScreen(compose.activity, store)
            compose.activity.setContentView(screen.createMiuixView())
        }
    }

    @After fun closeSettings() { compose.runOnUiThread { screen.close() } }

    @Test fun existingSuppliersCanBeSelectedIndependentlyForReplyAndCommentInHostMiuix() {
        compose.onNodeWithTag("settings.category.5").performScrollTo().performClick()
        compose.onNodeWithTag("settings.option.回复内容").performClick()
        compose.onNodeWithTag("settings.option.模型配置").assertIsDisplayed().performClick()
        compose.onNodeWithText("跟随全局模型").assertIsDisplayed()
        compose.onNodeWithText("供应商一 · first-model · 非流式").assertIsDisplayed()
        compose.onNodeWithText("供应商二 · second-model · 流式").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.runOnIdle {
            assertEquals("first", store.getString(AiModelSettings.REPLY_MODEL_ID, ""))
            assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""))
        }
        compose.runOnUiThread { screen.dismissChildPages() }
        compose.onNodeWithTag("settings.category.6").performScrollTo().performClick()
        compose.onNodeWithTag("settings.option.评论内容").performClick()
        compose.onNodeWithTag("settings.option.模型配置").assertIsDisplayed().performClick()
        compose.onNodeWithTag("miuix.dialog.choice.2").performClick()
        compose.runOnIdle {
            assertEquals("first", store.getString(AiModelSettings.REPLY_MODEL_ID, ""))
            assertEquals("second", store.getString(AiModelSettings.COMMENT_MODEL_ID, ""))
            assertEquals("global", AiModelSettings.read(store).activeId)
            assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""))
        }
        compose.onNodeWithText("供应商二 · second-model · 流式").assertExists()
    }

    private fun profile(id: String, name: String, streaming: Boolean): AiModelConfig =
        AiModelConfig.fromJson(JSONObject().put("id", id).put("name", name)
            .put("apiEndpoint", "https://$id.example.test/v1").put("apiKey", "fixture-key")
            .put("modelName", "$id-model").put("responseMode", if (streaming)
                AiModelConfig.RESPONSE_STREAM else AiModelConfig.RESPONSE_SYNC))
}
