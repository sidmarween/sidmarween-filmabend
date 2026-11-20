# 🎬 Filmabend

Android-App für zwei: eure gemeinsame Liste für Filme und Serien.

- **Abhaken**, was ihr gesehen habt – jeder für sich, bei Serien auch pro Staffel
- **Merkliste** für alles, was ihr noch sehen wollt
- **Bewerten** von 1 bis 10 (Grottig … Meisterwerk), du siehst auch die Wertung deines Partners
- **Charts**: Top 10, Flop, „Hier seid ihr euch uneinig“, Statistik und Verteilung – nach eurem Schnitt, deiner oder ihrer Wertung, für Filme oder Serien
- **Entdecken**: Vorschläge nach eurem Geschmack („für uns beide“, „für mich“, „für sie/ihn“), berechnet aus allem, was ihr mit 7+ bewertet habt
- **Post**: Schickt euch gegenseitig Filme und Serien mit kurzer Nachricht – mit Benachrichtigung aufs Handy
- Zeigt, bei welchem Streaming-Abo ein Titel gerade läuft (Deutschland)

Die Daten liegen in eurem eigenen, kostenlosen Firebase-Projekt; Film-Infos kommen von TMDB.

---

## Einmalige Einrichtung (ca. 15 Minuten)

### 1. TMDB-Schlüssel holen (für Suche, Poster & Vorschläge)
1. Auf <https://www.themoviedb.org> ein kostenloses Konto anlegen.
2. Profilbild → **Einstellungen** → **API** → API-Schlüssel anfordern (Typ „Developer“, private Nutzung).
3. Den **API-Schlüssel** (kurze Zeichenkette) kopieren. Der lange „Lesezugriffs-Token“ funktioniert auch.

### 2. Firebase-Projekt anlegen (für eure gemeinsame Liste)
1. <https://console.firebase.google.com> → **Projekt hinzufügen** → Name z. B. `Filmabend` (Google Analytics kannst du ausschalten).
2. Auf der Projektseite das **Android-Symbol** antippen → Paketname: `de.marween.filmabend` → **App registrieren** → **google-services.json herunterladen**. Die restlichen Schritte dort einfach mit „Weiter“ überspringen.
3. Links **Authentication** → **Jetzt starten** → **E-Mail-Adresse/Passwort** → aktivieren → Speichern.
4. Links **Firestore Database** → **Datenbank erstellen** → Standort `eur3 (Europe)` oder `europe-west3 (Frankfurt)` → **Produktionsmodus**.
5. In Firestore den Reiter **Regeln** öffnen, alles löschen, den Inhalt der Datei [`firestore.rules`](firestore.rules) aus diesem Projekt einfügen → **Veröffentlichen**.

### 3. Schlüssel bei GitHub hinterlegen
Repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:

| Name | Inhalt |
|---|---|
| `GOOGLE_SERVICES_JSON` | den **kompletten Text** der Datei `google-services.json` (Datei mit einem Texteditor öffnen, alles kopieren) |
| `TMDB_API_KEY` | dein TMDB-Schlüssel aus Schritt 1 |

### 4. APK bauen
Reiter **Actions** → **APK bauen** → **Run workflow**. Nach ein paar Minuten liegt unter **Releases** die Datei `Filmabend.apk`.

### 5. Loslegen
1. Beide installieren die APK und erstellen ein Konto (E-Mail + Passwort).
2. Einer tippt **Gemeinsame Liste anlegen** und teilt den 6-stelligen Code.
3. Der andere tippt **Mit Code verbinden** – fertig. Ab jetzt seht ihr alles auf beiden Handys.

---

## APK herunterladen

Jeder Push auf `main` baut automatisch eine neue APK.
Download: **Releases** → neueste Version → `Filmabend.apk`.

Die App ist immer mit demselben Schlüssel signiert, neue Versionen lassen sich also einfach drüber installieren.

Film- und Seriendaten: [The Movie Database (TMDB)](https://www.themoviedb.org). Diese App nutzt die TMDB-API, ist aber nicht von TMDB unterstützt oder zertifiziert.

© marween
