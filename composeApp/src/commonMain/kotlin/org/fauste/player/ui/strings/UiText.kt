package org.fauste.player.ui.strings

/**
 * A user-facing string as it travels through the state layer: either a
 * bundle [Key] (with its Fluent arguments) or the [Raw] text of something
 * the app does not own — an engine or repository message, a track title.
 * The state layer stays LOCALE-INDEPENDENT (a snapshot asserts equal on
 * any host); the render layer resolves [render] against the composition's
 * [Strings], which is the only place a locale enters.
 */
sealed interface UiText {
    /** A bundle message id plus its arguments; resolved at render time. */
    data class Key(
        val key: String,
        val args: List<Pair<String, Any?>> = emptyList(),
    ) : UiText

    /** Text the app renders as-is: engine/repository messages, imported paths. */
    data class Raw(
        val text: String,
    ) : UiText
}

/** Resolves this text against [strings] — the render layer's one entry point. */
fun UiText.render(strings: Strings): String =
    when (this) {
        is UiText.Key -> strings.get(key, *args.toTypedArray())
        is UiText.Raw -> text
    }
