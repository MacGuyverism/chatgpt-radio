# Briefsona development

Read DESIGN.md before changing product behavior. Keep development on the feature branch until the test broadcast has been reviewed and approved. Use RADIO_TEST_ONLY=1 for development broadcasts. Keep production credentials in GitHub Actions secrets and use the public Supabase project origin in Android.

Run backend tests from the repository root with `python -m unittest discover -s tests -v`. Run Android tests and assemble the debug APK with `gradle -p android testDebugUnitTest assembleDebug` using Gradle 8.10.2, JDK 17 and Android SDK 35.
