package com.trailback.app.ui.compass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * НОВОЕ (предрелизный аудит): ArrowAngleSmoother вынесен из
 * CompassActivity.recomputeArrow() — раньше эта математика жила как три
 * приватных var-поля прямо в Activity и ни разу не была протестирована,
 * несмотря на то, что именно она отвечала за прошлый трудновоспроизводимый
 * баг "стрелка дёргается после сворачивания" (см. комментарии в
 * CompassActivity про дублирующиеся подписки). Чистая математика без
 * Android-зависимостей — обычный JUnit, как и у MarkedPlacesViewModelTest.
 */
class ArrowAngleSmootherTest {
    private fun angularDistance(a: Float, b: Float): Float {
        var diff = abs(a - b) % 360f
        if (diff > 180f) diff = 360f - diff
        return diff
    }
    @Test
    fun `first call is a cold start - returns raw angle unchanged`() {
        val smoother = ArrowAngleSmoother()
        val result = smoother.smooth(123.4f)
        assertEquals(123.4f, result, 0.01f)
    }
    @Test
    fun `second call blends toward raw value, old weight dominant`() {
        val smoother = ArrowAngleSmoother(oldWeight = 0.92f, newWeight = 0.08f)
        smoother.smooth(0f) // холодный старт — ровно 0°
        val result = smoother.smooth(90f) // резкий скачок на 90°
        // При весе 0.92 к старому значению итог должен остаться намного
        // ближе к 0°, чем к 90° — отфильтрованный шум, а не мгновенный скачок.
        assertTrue("ожидали значение близко к 0°, получили $result", angularDistance(result, 0f) < angularDistance(result, 90f))
    }
    @Test
    fun `wraparound near 0-360 boundary smooths through the short arc, not through 180`() {
        val smoother = ArrowAngleSmoother()
        smoother.smooth(350f) // холодный старт
        val result = smoother.smooth(10f) // соседняя точка через границу 0°/360°
        // Короткая дуга между 350° и 10° — всего 20° (через 0°), а не 340°
        // (в обход через 180°). Результат должен лежать на короткой дуге.
        assertTrue("смещение должно идти через короткую дугу (0°), а не через 180°, получили $result",
            angularDistance(result, 350f) < 180f)
    }
    @Test
    fun `reset makes the next call a cold start again`() {
        val smoother = ArrowAngleSmoother()
        smoother.smooth(0f)
        smoother.smooth(90f) // теперь внутреннее состояние сглажено, не на 90°
        smoother.reset()
        val result = smoother.smooth(200f)
        // После reset() — снова холодный старт: мгновенная установка.
        assertEquals(200f, result, 0.01f)
    }
    @Test
    fun `output is always normalized into [0, 360)`() {
        val smoother = ArrowAngleSmoother()
        val result = smoother.smooth(-45f) // отрицательный вход тоже может прийти с верхнего уровня
        assertTrue("результат вне диапазона [0,360): $result", result >= 0f && result < 360f)
    }
    @Test
    fun `repeated identical input converges to that value and stays stable`() {
        val smoother = ArrowAngleSmoother()
        smoother.smooth(45f)
        var result = 0f
        repeat(20) { result = smoother.smooth(45f) }
        assertEquals(45f, result, 0.1f)
    }
}
