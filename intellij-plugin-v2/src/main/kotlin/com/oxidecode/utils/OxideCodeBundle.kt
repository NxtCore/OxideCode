package com.oxidecode.utils

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

@NonNls
private const val BUNDLE = "messages.oxidecode"

object OxideCodeBundle {
    private fun getBundle(): DynamicBundle = DynamicBundle(OxideCodeBundle::class.java, BUNDLE)

    @JvmStatic
    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = getBundle().getMessage(key, *params)

    @JvmStatic
    fun messagePointer(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): Supplier<@Nls String> = getBundle().getLazyMessage(key, *params)
}
