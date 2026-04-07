# Spend Tracker

An Android app that automatically tracks your income and expenses by reading bank SMS messages — with on-device machine learning for smart categorisation, foreign currency conversion, and full manual CRUD support.

## Features

### Automatic SMS Import
- Reads bank and credit card SMS messages from your inbox
- On-device Naive Bayes classifier categorises each message into:
  - **Salary Income** — actual salary credits only (not PF/PPF deposits)
  - **Expense** — card/bank debit transactions only (not credit limit updates)
  - **Ignored** — OTPs, promotional messages, and irrelevant alerts
- User corrections feed back into the model in real time (online retraining)

### Currency Handling
- All amounts stored and displayed in **INR**
- Foreign currency transactions (THB, USD, EUR, GBP, AED, SGD, JPY, MYR, etc.) are converted using the **[Frankfurter API](https://www.frankfurter.app/)** at the historical exchange rate on the date of the transaction
- Falls back to bundled static rates when offline
- Correctly ignores "Avl Limit" / "Avl Bal" INR amounts in the same SMS — only the actual transaction amount is used

### Month-by-Month View
- Home screen shows each month's total income and expenses
- Tap a month to drill into individual transactions
- Pie chart showing spend breakdown by category for each month

### Transactions
- Filter by All / Expenses / Income
- Sort expenses by date (newest/oldest) or amount (high/low)
- Edit any transaction — amount, date, category, notes
- Delete with confirmation dialog
- Add transactions manually with optional currency selection

### Categories
- Default categories: Food, Travel, Shopping, Bills, Health, Entertainment, General
- Add and rename custom categories

### ML Control Panel
- View real-time model precision for salary and expense classification
- Configure trusted salary senders, account tail numbers, and narration keywords
- Tune confidence thresholds for salary and expense acceptance
- Shadow mode — log predictions without acting on them, for safe evaluation
- Retrain or reset the model from the UI

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material3 |
| Navigation | Jetpack Navigation Compose |
| Local Storage | Room (SQLite) |
| Async | Kotlin Coroutines |
| ML | Custom on-device Naive Bayes (no external SDK) |
| Currency API | Frankfurter (free, no API key required) |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 14 (API 34) |

## Permissions

| Permission | Reason |
|---|---|
| `READ_SMS` | Read bank/card messages from inbox |
| `INTERNET` | Fetch historical exchange rates from Frankfurter |

## Building

### Requirements
- Android Studio Hedgehog or later (or command-line Gradle)
- JDK 21 (`brew install openjdk@21` on macOS)
- Android SDK with API 34 platform

### Steps

```bash
git clone git@github.com:pradeepkumaresan/spend-tracker.git
cd spend-tracker

# Build debug APK
./gradlew assembleDebug

# APK is at:
# app/build/outputs/apk/debug/app-debug.apk
```

If you use a different JDK path, update `org.gradle.java.home` in `gradle.properties`.

### Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK file to your phone and open it (enable "Install from unknown sources" in Settings if prompted).

## How It Works

1. On first launch the app requests SMS permission and imports your inbox
2. A bootstrap Naive Bayes model is trained locally using weak labels derived from heuristics (keyword patterns)
3. Each SMS is classified and — if accepted as a salary or expense — converted to INR and saved
4. You can mark any transaction as "Salary", "Expense", or "Ignore" to correct mistakes; these corrections retrain the model incrementally
5. All data lives entirely on your device — nothing is sent to any server except the Frankfurter API for exchange rates

## Privacy

All transaction data is stored locally in a SQLite database on your device. The only external network call is to `api.frankfurter.app` to look up historical currency exchange rates. No analytics, no tracking, no accounts.

## License

MIT
