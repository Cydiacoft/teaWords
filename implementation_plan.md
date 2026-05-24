# Implementation Plan - teaWords Android Client (Linguistic Utility System)

A minimalist Android application for querying English definitions, translations, and word levels. Re-imagined as a "pure tool" with a lower-center search layout for easy thumb access, clean and restrained result formatting, and offline CET-4/CET-6 vocabulary level markers.

## User Review Required

> [!IMPORTANT]
> **Design Adjustments:**
> 1. **Focus Shifted Downward:** On the main page, the search input field is shifted to the middle-lower region of the screen, making it extremely easy to reach with one hand.
> 2. **Lexicon & Level Badges:** Search result headers will display level badges (e.g. `CET-4 / 四级`, `CET-6 / 六级`) and the source of the definition (e.g., `茶词本地`, `在线英汉`).
> 3. **Minimalist Aesthetic:** Follows the "Linguistic Utility System" spec with a monochromatic theme, light-gray surfaces, functional blue accents, and generous vertical rhythm.

## Proposed Changes

We will create a brand new Android project inside `d:\Projects\teaWords`.

### 1. Build and Assets Setup
We will copy the Gradle wrapper files and core configuration from `D:\Android Studio\teatalk` to `d:\Projects\teaWords`.
We will download the raw CET-4 and CET-6 word lists from GitHub during the setup phase and store them under the assets folder.

*   **[NEW] `app/src/main/assets/cet4.txt`**: Raw list of CET-4 words for offline lookup.
*   **[NEW] `app/src/main/assets/cet6.txt`**: Raw list of CET-6 words for offline lookup.
*   **[NEW] `settings.gradle.kts`**: Set root project name to `"teaWords"`.
*   **[NEW] `app/build.gradle.kts`**: Set applicationId to `com.tea.teawords` and add necessary dependencies (OkHttp, Gson, Navigation-Compose, Security-Crypto, DataStore, Lifecycle).
*   **[NEW] `app/src/main/AndroidManifest.xml`**: Application configuration with Internet permissions.

### 2. Core Source Code (Kotlin)

We will organize code under `com.tea.teawords` package.

#### [NEW] [DatabaseHelper.kt](file:///d:/Projects/teaWords/app/src/main/java/com/tea/teawords/data/DatabaseHelper.kt)
A SQLite database helper to persist search history and the vocabulary notebook.
- **Table `history`**: `id (INTEGER PRIMARY KEY)`, `word (TEXT UNIQUE)`, `translation (TEXT)`, `timestamp (INTEGER)`
- **Table `vocabulary`**: `id (INTEGER PRIMARY KEY)`, `word (TEXT UNIQUE)`, `phonetic (TEXT)`, `definition (TEXT)`, `timestamp (INTEGER)`

#### [NEW] [WordLevelProvider.kt](file:///d:/Projects/teaWords/app/src/main/java/com/tea/teawords/data/WordLevelProvider.kt)
Helper to load `cet4.txt` and `cet6.txt` from assets into two `HashSet<String>`s on startup for O(1) level checking:
- `fun getLevels(word: String): List<String>` (returns "四级" and/or "六级" if matched)

#### [NEW] [Models.kt](file:///d:/Projects/teaWords/app/src/main/java/com/tea/teawords/data/Models.kt)
Data classes matching the Free Dictionary API response:
- `WordResponse`: Word, phonetic, phonetics (with audio), meanings (part of speech, definitions, example).
- `TranslationResponse`: Translated text from MyMemory API.

#### [NEW] [DictionaryApi.kt](file:///d:/Projects/teaWords/app/src/main/java/com/tea/teawords/data/DictionaryApi.kt)
Handles API calls using OkHttp and parses responses with Gson:
- `lookupWord(word: String, callback: (WordResponse?) -> Unit)`
- `translate(text: String, from: String, to: String, callback: (String?) -> Unit)`

#### [NEW] [MainActivity.kt](file:///d:/Projects/teaWords/app/src/main/java/com/tea/teawords/MainActivity.kt)
Sets up Jetpack Compose, the database helper, word level provider, and the theme.

#### [NEW] [Theme.kt](file:///d:/Projects/teaWords/app/src/main/java/com/tea/teawords/ui/theme/Theme.kt)
Defines the visual system (Linguistic Utility System):
- Monochromatic palette: Background (`#F8F9FA`), Surface Container Lowest (`#FFFFFF`), Border Outline Variant (`#E5E7EB`), Text Primary (`#111827`), Text Outline (`#76777D`), Accent Color (`#3B82F6` for chips and active buttons).
- Corner radius: `8dp` for inputs and buttons, `16dp` for sheets and content cards.
- Font: Inter.

#### [NEW] [MainScreen.kt](file:///d:/Projects/teaWords/app/src/main/java/com/tea/teawords/ui/screens/MainScreen.kt)
Main layout containing:
- **Home View**:
  - Top header: Subtle app name `teawords` with menu and profile icons.
  - Middle-lower section:
    - Large Title: `茶词`
    - Subtitle: `专注翻译，见字如面`
    - Large search bar: 56dp height, pill shape, centered, with placeholder "键入单词或长句...", leading search icon, trailing microphone icon.
  - Lower section:
    - Recently queried timeline list with a "Clear" (清空) button. Clicking an item triggers instant lookup.
- **Search Result View**:
  - Restrained, clean layout focused on core translation.
  - Header: Back button, Level Badges (e.g. `CET-4 / 四级`, `CET-6 / 六级`), and definition source (e.g., `茶词本地` / `在线英汉`).
  - Title: Word, Phonetic text, Audio play icon.
  - Star/Favorite button to save to the notebook.
  - Definitions list: Grouped by part of speech in blue uppercase text (e.g., `NOUN`, `VERB`).
  - Clean English definition and Chinese translations.
  - Synonyms and related words listed as chips.
  - Sentence translation card (if user inputs a phrase/sentence or dictionary API fails).
- **Notebook View**:
  - Starred words list with swiping/delete actions.
  - Mini review flashcard mode.
- **History View**:
  - Unified history query list.

## Verification Plan

### Automated Build Verification
We will run `./gradlew assembleDebug` in the workspace directory `d:\Projects\teaWords` to compile the app and verify there are no compilation errors.

### Manual Verification
1. We will verify the CET-4/CET-6 list parsing and local check outputs.
2. The user can deploy the built APK to an Android device or emulator to test real-world lookups, audio playback, translations, history management, and vocabulary cards.
