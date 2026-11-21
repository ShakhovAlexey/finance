package com.shakhov.finance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun screensOpen() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val oct = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -1); set(java.util.Calendar.DAY_OF_MONTH, 8) }.timeInMillis
        Db.get(ctx).insert(Tx(ts = oct, amount = 66.0, category = "Кафе и рестораны", merchant = "ארומה", merchantRu = "Арома, Нацрат-Илит (кафе)", src = Src.MANUAL))
        rule.onNodeWithText("Расходы").assertExists()
        // go to October (old table data)
        rule.onNodeWithContentDescription("Предыдущий месяц").performClick()
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Арома, Нацрат-Илит (кафе)"))
        rule.onAllNodesWithText("Арома, Нацрат-Илит (кафе)").onFirst().performClick()
        rule.onNodeWithText("Операция").assertExists()
        rule.onNodeWithText("Отмена").performClick()
        rule.onNodeWithText("Отчёт").performClick()
        rule.onNodeWithText("Остаток месяца").assertExists()
        rule.onNodeWithText("Добавить").performClick()
        rule.onNodeWithText("Вручную").performClick()
        rule.onNodeWithText("Новая операция").assertExists()
        rule.onNodeWithText("Отмена").performClick()
        rule.onNodeWithText("Настройки").performClick()
        rule.onNodeWithText("1. Чтение СМС").assertExists()
        rule.onNodeWithText("Облачная копия").assertExists()
    }
}
