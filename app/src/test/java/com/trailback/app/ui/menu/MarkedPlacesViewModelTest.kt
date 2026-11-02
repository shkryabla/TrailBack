package com.trailback.app.ui.menu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * НОВОЕ (предрелизный аудит — чек-лист тестов): parseCoordinates() раньше
 * вообще не была покрыта тестами, хотя это единственная граница валидации
 * пользовательского ввода перед записью в Room (см. MarkedPlacesViewModel).
 * Чистая функция, без Android-зависимостей — обычный JUnit, без Robolectric
 * (в отличие от HomeArrivalDetectorTest/TrackingStateStoreTest рядом,
 * которым Robolectric нужен из-за SharedPreferences/Location).
 */
class MarkedPlacesViewModelTest {
    @Test
    fun `valid coordinates parsed correctly`() {
        val result = MarkedPlacesViewModel.parseCoordinates("55.751244, 37.618423")
        assertEquals(55.751244, result?.first)
        assertEquals(37.618423, result?.second)
    }
    @Test
    fun `extra whitespace around values is trimmed`() {
        val result = MarkedPlacesViewModel.parseCoordinates("  55.75 ,   37.61  ")
        assertEquals(55.75, result?.first)
        assertEquals(37.61, result?.second)
    }
    @Test
    fun `negative coordinates are valid`() {
        val result = MarkedPlacesViewModel.parseCoordinates("-33.8688, -151.2093")
        assertEquals(-33.8688, result?.first)
        assertEquals(-151.2093, result?.second)
    }
    @Test
    fun `null input returns null`() {
        assertNull(MarkedPlacesViewModel.parseCoordinates(null))
    }
    @Test
    fun `blank input returns null`() {
        assertNull(MarkedPlacesViewModel.parseCoordinates(""))
    }
    @Test
    fun `missing comma separator returns null`() {
        assertNull(MarkedPlacesViewModel.parseCoordinates("55.75124 37.61842"))
    }
    @Test
    fun `too many parts returns null`() {
        assertNull(MarkedPlacesViewModel.parseCoordinates("55.75, 37.61, 100"))
    }
    @Test
    fun `non-numeric values return null`() {
        assertNull(MarkedPlacesViewModel.parseCoordinates("пятьдесят пять, тридцать семь"))
    }
    @Test
    fun `latitude out of range returns null`() {
        assertNull(MarkedPlacesViewModel.parseCoordinates("91.0, 37.6"))
        assertNull(MarkedPlacesViewModel.parseCoordinates("-91.0, 37.6"))
    }
    @Test
    fun `longitude out of range returns null`() {
        assertNull(MarkedPlacesViewModel.parseCoordinates("55.7, 181.0"))
        assertNull(MarkedPlacesViewModel.parseCoordinates("55.7, -181.0"))
    }
    @Test
    fun `boundary values are valid (inclusive)`() {
        val result = MarkedPlacesViewModel.parseCoordinates("90.0, 180.0")
        assertEquals(90.0, result?.first)
        assertEquals(180.0, result?.second)
    }
}
