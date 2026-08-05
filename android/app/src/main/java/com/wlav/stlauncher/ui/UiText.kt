package com.wlav.stlauncher.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * A piece of user-facing text that has not been resolved yet.
 *
 * Presenters return this instead of a `String` so that they stay free of
 * [Context] and remain assertable under the project's plain JUnit setup — a
 * `@StringRes` value is just an `Int` at test time.
 */
sealed interface UiText {
    /** Text that genuinely comes from outside the app, such as a URL or a server message. */
    data class Raw(
        val value: String,
    ) : UiText

    /** A string resource, optionally formatted. Arguments may themselves be [UiText]. */
    data class Resource(
        @StringRes val id: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    companion object {
        fun of(
            @StringRes id: Int,
            vararg arguments: Any,
        ): Resource = Resource(id, arguments.toList())
    }
}

fun UiText.resolve(context: Context): String =
    when (this) {
        is UiText.Raw -> value
        is UiText.Resource ->
            if (arguments.isEmpty()) {
                context.getString(id)
            } else {
                val resolved =
                    arguments.map { argument ->
                        if (argument is UiText) argument.resolve(context) else argument
                    }
                context.getString(id, *resolved.toTypedArray())
            }
    }
