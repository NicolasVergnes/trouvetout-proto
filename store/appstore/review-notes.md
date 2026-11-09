# App Review — notes for the reviewer

Paste into App Store Connect → App Review Information → Notes (English).

---

TrouveTout records where the user puts physical objects ("passport in the top drawer of the dresser") and finds them
later from a question in their own words. Everything stays on the device: no account, no server, no analytics. No
login is needed to review the app.

How to try it:
1. On the home screen, type "keys @ hall" and tap the send arrow; create the place "hall" when asked. A confirmation
   card appears.
2. Type "where are the keys?" — the search screen opens with a one-line answer (place, date).
3. Places (top right) shows the hierarchy; an item's sheet shows its photos, tags, reminders and history.

Permissions, each asked at first use with an explanation, and optional:
- Microphone and Speech Recognition: dictating a sentence. Recognition runs on the device only
  (requiresOnDeviceRecognition = true); audio is never recorded or sent. Without them the app works with the keyboard.
- Camera: attaching a photo to an object or a place, or searching with a photo. Photos stay in the app's container.
- Face ID: optional app lock (Settings → Privacy).
- Notifications: reminders the user sets ("remind me where the passport is the day before March 12").

Search uses an embedding model (EmbeddingGemma 2, Apache 2.0) bundled in the app and run on the device with Google's
LiteRT-LM runtime; nothing is downloaded.

In-app purchases: a 6-month free trial starts at first launch; after it, only adding new objects needs a purchase
(non-consumable "unlock_lifetime" or yearly subscription "subscription_yearly"); everything else keeps working. To see
the purchase screen during review, use Settings → Purchase.

Encryption: the database is encrypted with SQLCipher (AES-256) and the optional backup with AES-256-GCM, to protect the
user's own data; see the export compliance answers.

Contact: glopglopdev@gmail.com
