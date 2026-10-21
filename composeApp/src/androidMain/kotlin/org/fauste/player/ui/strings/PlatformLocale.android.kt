package org.fauste.player.ui.strings

import java.util.Locale

actual fun currentLocaleTag(): String = Locale.getDefault().toLanguageTag()
