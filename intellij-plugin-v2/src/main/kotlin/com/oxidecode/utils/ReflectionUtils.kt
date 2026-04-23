package com.oxidecode.utils

fun tryLoadClass(name: String) = runCatching { Class.forName(name) }.getOrNull()

fun tryMethod(
    clazz: Class<*>?,
    methodName: String,
) = runCatching { clazz?.getMethod(methodName) }.getOrNull()

fun tryMethodWithParams(
    clazz: Class<*>?,
    methodName: String,
    vararg paramTypes: Class<*>?,
) = runCatching {
    val nonNullParams = paramTypes.filterNotNull().toTypedArray()
    clazz?.getMethod(methodName, *nonNullParams)
}.getOrNull()

fun tryInvokeMethod(
    instance: Any?,
    method: java.lang.reflect.Method?,
    vararg args: Any?,
) = runCatching { method?.invoke(instance, *args) }.getOrNull()

fun invokeMethod(
    instance: Any?,
    method: java.lang.reflect.Method?,
    vararg args: Any?,
): Any? = method?.invoke(instance, *args)

fun tryInvokeStaticMethod(
    method: java.lang.reflect.Method?,
    vararg args: Any?,
) = runCatching { method?.invoke(null, *args) }.getOrNull()

fun tryGetStaticMethod(
    clazz: Class<*>?,
    methodName: String,
    vararg paramTypes: Class<*>,
) = runCatching { clazz?.getMethod(methodName, *paramTypes) }.getOrNull()
