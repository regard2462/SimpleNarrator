package app.simplenarrator.speech

import org.junit.Assert.assertTrue
import org.junit.Test

/** Sprechtext für Deutsch und Englisch: Zahlen, Daten, Abkürzungen, Einheiten, Aufräumen. */
class NormalizerTest {
    private val failures = mutableListOf<String>()
    private fun de(input: String, want: String) = check(Normalizer.normalize(input, "de"), want, input)
    private fun en(input: String, want: String) = check(Normalizer.normalize(input, "en"), want, input)
    private fun check(got: String, want: String, input: String) {
        if (got != want) failures += "$input\n  bekommen: $got\n  erwartet: $want"
    }

    private fun verify() = assertTrue(failures.size.toString() + " Abweichung(en):\n" + failures.joinToString("\n"), failures.isEmpty())

    /** Abkürzungen */
    @Test
    fun abkuerzungen() {
        de("Das gilt z. B. für Äpfel.", "Das gilt zum Beispiel für Äpfel.")
        de("Das gilt z.B. für Äpfel.", "Das gilt zum Beispiel für Äpfel.")
        de("Äpfel, Birnen usw.", "Äpfel, Birnen und so weiter.")
        de("Er traf Dr. Müller und Prof. Schmidt.", "Er traf Doktor Müller und Professor Schmidt.")
        de("Vgl. dazu Kap. 3.", "Vergleiche dazu Kapitel drei.")
        de("Siehe S. 42 ff.", "Siehe Seite zweiundvierzig folgende.")
        de("Er wohnt in der Hauptstr. 5.", "Er wohnt in der Hauptstraße fünf.")
        de("Sie fuhren nach St. Pauli.", "Sie fuhren nach Sankt Pauli.")
        de("Das war 50 v. Chr.", "Das war fünfzig vor Christus.")
        de("Es kostet ca. 20 €.", "Es kostet circa zwanzig Euro.")
        verify()
    }

    /** Geld */
    @Test
    fun geld() {
        de("Das Buch kostet 12,50 €.", "Das Buch kostet zwölf Euro fünfzig.")
        de("Nur 1 € pro Stück.", "Nur ein Euro pro Stück.")
        de("Er zahlte € 3,99.", "Er zahlte drei Euro neunundneunzig.")
        de("Der Preis: 1.250 €.", "Der Preis: tausendzweihundertfünfzig Euro.")
        de("Es kostet 0,50 €.", "Es kostet fünfzig Cent.")
        de("Der Umsatz lag bei 3,5 Mio. Euro.", "Der Umsatz lag bei drei Komma fünf Millionen Euro.")
        de("Sie erbte 1 Mio. €.", "Sie erbte eine Million Euro.")
        de("Er hatte 20 $ dabei.", "Er hatte zwanzig Dollar dabei.")
        verify()
    }

    /** Daten und Zeiten */
    @Test
    fun datenUndZeiten() {
        de("Am 3. Mai kam er an.", "Am dritten Mai kam er an.")
        de("Die Konferenz begann am 3. Mai 2024.", "Die Konferenz begann am dritten Mai zweitausendvierundzwanzig.")
        de("Berlin, 12.10.2024", "Berlin, zwölfter Oktober zweitausendvierundzwanzig")
        de("Vom 3. bis 5. Juni war er weg.", "Vom dritten bis fünften Juni war er weg.")
        de("Geboren am 24. Dez. 1990.", "Geboren am vierundzwanzigsten Dezember neunzehnhundertneunzig.")
        de("Der Zug fährt um 14:30 Uhr.", "Der Zug fährt um vierzehn Uhr dreißig.")
        de("Treffen um 1 Uhr nachts.", "Treffen um ein Uhr nachts.")
        de("Sie kam um 9.05 Uhr.", "Sie kam um neun Uhr fünf.")
        verify()
    }

    /** Jahre */
    @Test
    fun jahre() {
        de("1990 fiel die Mauer.", "Neunzehnhundertneunzig fiel die Mauer.".replaceFirstChar { it.lowercaseChar() })
        de("Im Jahr 1871 wurde das Reich gegründet.", "Im Jahr achtzehnhunderteinundsiebzig wurde das Reich gegründet.")
        de("Die 1920er Jahre waren wild.", "Die neunzehnhundertzwanziger Jahre waren wild.")
        de("Seit 1990 Mitglied.", "Seit neunzehnhundertneunzig Mitglied.")
        de("Es kamen 1500 Menschen.", "Es kamen tausendfünfhundert Menschen.")
        de("Von 1939–1945 herrschte Krieg.", "Von neunzehnhundertneununddreißig bis neunzehnhundertfünfundvierzig herrschte Krieg.")
        de("Die Saison 2023/24 war gut.", "Die Saison zweitausenddreiundzwanzig bis vierundzwanzig war gut.")
        verify()
    }

    /** Ordnungszahlen und römische Zahlen */
    @Test
    fun ordnungszahlenUndRoemischeZahlen() {
        de("Er wurde der 3. Sieger.", "Er wurde der dritte Sieger.")
        de("Zum 5. Mal gewann sie.", "Zum fünften Mal gewann sie.")
        de("Im 19. Jahrhundert war das anders.", "Im neunzehnten Jahrhundert war das anders.")
        de("Ludwig XIV. regierte lange.", "Ludwig der Vierzehnte regierte lange.")
        de("Unter Ludwig XIV. blühte Frankreich.", "Unter Ludwig dem Vierzehnten blühte Frankreich.")
        de("Elisabeth II. starb 2022.", "Elisabeth die Zweite starb zweitausendzweiundzwanzig.")
        de("Kapitel IV beginnt hier.", "Kapitel vier beginnt hier.")
        de("Das war im XVIII. Jahrhundert.", "Das war im achtzehnten Jahrhundert.")
        verify()
    }

    /** Maße, Prozent, Brüche */
    @Test
    fun masseProzentBrueche() {
        de("Er lief 5 km in 20 min.", "Er lief fünf Kilometer in zwanzig Minuten.")
        de("Das dauert 1 h.", "Das dauert eine Stunde.")
        de("Die Wohnung hat 85 m².", "Die Wohnung hat fünfundachtzig Quadratmeter.")
        de("Er fuhr 120 km/h.", "Er fuhr hundertzwanzig Kilometer pro Stunde.")
        de("Es wiegt 2,5 kg.", "Es wiegt zwei Komma fünf Kilogramm.")
        de("Die Quote stieg um 3,5 %.", "Die Quote stieg um drei Komma fünf Prozent.")
        de("Nur 1 % schaffte es.", "Nur ein Prozent schaffte es.")
        de("Draußen sind -5 °C.", "Draußen sind minus fünf Grad Celsius.")
        de("Gib 1/2 Liter Milch dazu.", "Gib ein halb Liter Milch dazu.")
        de("Drei Viertel, also 3/4, fehlten.", "Drei Viertel, also drei Viertel, fehlten.")
        de("Es gilt § 5 Abs. 2.", "Es gilt Paragraph fünf Absatz zwei.")
        verify()
    }

    /** Zahlen und Genus */
    @Test
    fun zahlenUndGenus() {
        de("1 Katze und 1 Hund spielten.", "eine Katze und ein Hund spielten.")
        de("Er nahm 1 kleine Tasse.", "Er nahm eine kleine Tasse.")
        de("Er nahm 1 großes Buch.", "Er nahm ein großes Buch.")
        de("Es waren 1.234.567 Stimmen.", "Es waren eine Million zweihundertvierunddreißigtausendfünfhundertsiebenundsechzig Stimmen.")
        de("Pi ist etwa 3,14.", "Pi ist etwa drei Komma eins vier.")
        de("Das Ergebnis war 21.", "Das Ergebnis war einundzwanzig.")
        de("Sein Ziel war Nr. 1.", "Sein Ziel war Nummer eins.")
        verify()
    }

    /** Aufräumen */
    @Test
    fun aufraeumen() {
        de("Wie schon gesagt[12], war es spät.", "Wie schon gesagt, war es spät.")
        de("Das ist wichtig¹ für alle.", "Das ist wichtig für alle.")
        de("Mehr unter https://www.beispiel.de/seite", "Mehr unter beispiel Punkt de")
        de("Er kam – und ging.", "Er kam, und ging.")
        de("Das COVID-19-Virus breitete sich aus.", "Das COVID-neunzehn-Virus breitete sich aus.")
        de("Er kaufte einen 3D-Drucker.", "Er kaufte einen 3D-Drucker.")
        verify()
    }

    /** Englisch */
    @Test
    fun englisch() {
        en("Dr. Smith arrived on May 3, 2024.", "Doctor Smith arrived on May third, twenty twenty-four.")
        en("It costs $5.99 today.", "It costs five dollars and ninety-nine cents today.")
        en("They raised $2.5 million.", "They raised two point five million dollars.")
        en("The meeting is at 3:30 pm.", "The meeting is at three thirty p m.")
        en("He finished 1st and she 22nd.", "He finished first and she twenty-second.")
        en("In 1990 the wall fell.", "In nineteen ninety the wall fell.")
        en("The 1960s were wild.", "The nineteen sixties were wild.")
        en("Henry VIII had six wives.", "Henry the Eighth had six wives.")
        en("Then I went home.", "Then I went home.")
        en("Read Chapter XII first.", "Read Chapter twelve first.")
        en("World War II ended in 1945.", "World War two ended in nineteen forty-five.")
        en("It weighs 1,250 kg.", "It weighs one thousand two hundred fifty kilograms.")
        en("Growth was 3.5% e.g. in Q2.", "Growth was three point five percent for example in Q2.")
        en("Mix 3/4 cup of flour.", "Mix three quarters cup of flour.")
        en("Pages 10-15 explain it.", "Pages ten to fifteen explain it.")
        en("She lives on Baker St.", "She lives on Baker Street.")
        verify()
    }
}
