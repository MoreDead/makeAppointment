# 🔑 Gemini API Setup Guide

## Get Your FREE API Key (2 minutes)

1. **Go to Google AI Studio:**
   - Visit: https://makersuite.google.com/app/apikey
   - Sign in with your Google account

2. **Create API Key:**
   - Click "Create API Key"
   - Select "Create API key in new project" (or use existing)
   - Copy the generated key (looks like: `AIzaSyC...`)

3. **Add to Your App:**
   - Open `/app/src/main/java/com/example/docscanics/MainActivity.kt`
   - Find line: `val apiKey = "YOUR_GEMINI_API_KEY_HERE"`
   - Replace `YOUR_GEMINI_API_KEY_HERE` with your actual key:
   ```kotlin
   val apiKey = "AIzaSyC_your_actual_key_here"
   ```

## ✅ That's It!

Your app will now use AI for much better appointment extraction:
- **15 requests/minute** (free tier)
- **1,500 requests/day** (free tier)
- **Automatic fallback** to local extraction if API fails
- **Much better accuracy** for dates, times, locations

## 🔒 Security Note

For production apps, store the API key more securely (e.g., in BuildConfig or remote config). For testing, hardcoding is fine.

## 🧪 Testing

After adding your API key:
1. **Rebuild the app:** `./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
2. **Test with appointment images**
3. **Watch the logs** to see AI responses: `adb logcat | grep AI`

The button will show "AI Processing..." while the AI analyzes the text!