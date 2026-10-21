package org.fauste.player.ui.strings

/**
 * The platform's current locale as a BCP-47 language tag ("es-ES",
 * "en-US"), the one platform fact the i18n seam needs. The Android
 * actual reads [java.util.Locale.getDefault], which honours the system
 * per-app language preferences (Android 13+) for a plain ComponentActivity;
 * the honest limit is that the app has no in-UI language switcher — the
 * system setting (or the OS locale) decides, and a language change
 * applies on the next activity creation.
 */
expect fun currentLocaleTag(): String
