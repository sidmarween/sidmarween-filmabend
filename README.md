# 🎬 Filmabend

Android-App für zwei: eure gemeinsame Liste für Filme und Serien.

- **Abhaken**, was ihr gesehen habt – jeder für sich, bei Serien auch pro Staffel
- **Merkliste** für alles, was ihr noch sehen wollt
- **Bewerten** von 1 bis 10 (Grottig … Meisterwerk), du siehst auch die Wertung deines Partners
- **Charts**: Top 10, Flop, „Hier seid ihr euch uneinig“, Statistik und Verteilung – nach eurem Schnitt, deiner oder ihrer Wertung, für Filme oder Serien
- **Entdecken**: Vorschläge nach eurem Geschmack („für uns beide“, „für mich“, „für sie/ihn“), berechnet aus den Genres, die ihr gut bewertet, und den aktuellen deutschen Charts
- **Post**: Schickt euch gegenseitig Filme und Serien mit kurzer Nachricht – mit Benachrichtigung aufs Handy
- Zeigt bei Serien den Sender bzw. Streamingdienst

Eure Listen liegen in eurem eigenen, kostenlosen Firebase-Projekt. Film-Infos kommen ohne Anmeldung oder Schlüssel aus dem deutschen iTunes-Store (Filme) und von TVmaze (Serien).

---

## Einmalige Einrichtung (ca. 10 Minuten)

### 1. Firebase-Projekt anlegen (für eure gemeinsame Liste)
1. <https://console.firebase.google.com> → **Projekt hinzufügen** → Name z. B. `Filmabend` (Google Analytics kannst du ausschalten).
2. Auf der Projektseite das **Android-Symbol** antippen → Paketname: `de.marween.filmabend` → **App registrieren** → **google-services.json herunterladen**. Die restlichen Schritte dort einfach mit „Weiter“ überspringen.
3. Links **Authentication** → **Jetzt starten** → **E-Mail-Adresse/Passwort** → aktivieren → Speichern.
4. Links **Firestore Database** → **Datenbank erstellen** → Standort `eur3 (Europe)` oder `europe-west3 (Frankfurt)` → **Produktionsmodus**.
5. In Firestore den Reiter **Regeln** öffnen, alles löschen, den Inhalt der Datei [`firestore.rules`](firestore.rules) aus diesem Projekt einfügen → **Veröffentlichen**.

### 2. Schlüssel bei GitHub hinterlegen
Repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret** (nur dieses eine):

| Name | Inhalt |
|---|---|
| `GOOGLE_SERVICES_JSON` | den **kompletten Text** der Datei `google-services.json` (Datei mit einem Texteditor öffnen, alles kopieren) |

### 3. APK bauen
Reiter **Actions** → **APK bauen** → **Run workflow**. Nach ein paar Minuten liegt unter **Releases** die Datei `Filmabend.apk`.

### 4. Loslegen
1. Beide installieren die APK und erstellen ein Konto (E-Mail + Passwort).
2. Einer tippt **Gemeinsame Liste anlegen** und teilt den 6-stelligen Code.
3. Der andere tippt **Mit Code verbinden** – fertig. Ab jetzt seht ihr alles auf beiden Handys.

---

## APK herunterladen

Jeder Push auf `main` baut automatisch eine neue APK.
Download: **Releases** → neueste Version → `Filmabend.apk`.

Die App ist immer mit demselben Schlüssel signiert, neue Versionen lassen sich also einfach drüber installieren.

Filmdaten: Apple iTunes Search API · Seriendaten: [TVmaze](https://www.tvmaze.com) (CC BY-SA)

© marween
