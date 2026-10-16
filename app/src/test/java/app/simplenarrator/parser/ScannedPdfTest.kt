package app.simplenarrator.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Erkennen gescannter PDF-Seiten anhand der Textebene. */
class ScannedPdfTest {
    private val text = "Die Sonne ging langsam hinter den Hügeln unter, und die Pferde warteten auf den Bauern."

    @Test
    fun pagesWithoutLettersCountAsScanned() {
        // Leere Seite, nur Seitenzahl, kurzer Rest unter der Schwelle, richtige Textseite
        val pages = listOf("", "  12 \n", "Kapitel 3", text)
        assertEquals(listOf(0, 1, 2), PdfParser.scannedPages(pages))
    }

    @Test
    fun askOnlyFromOneThirdScannedPages() {
        assertTrue(PdfParser.mostlyScanned(listOf("", "")))
        assertTrue(PdfParser.mostlyScanned(listOf("", text, text)))
        assertFalse(PdfParser.mostlyScanned(listOf("", text, text, text)))
        assertFalse(PdfParser.mostlyScanned(listOf(text, text)))
        assertFalse(PdfParser.mostlyScanned(emptyList()))
    }
}
