package com.echostack.sdk

data class Configuration(
    val apiKey: String,
    val serverURL: String,
    val logLevel: LogLevel
) {
    val installURL: String get() = "$serverURL/v1/sdk/install"
    val eventsURL: String get() = "$serverURL/v1/sdk/events"
}

enum class LogLevel(val value: Int) {
    NONE(0),
    ERROR(1),
    WARNING(2),
    DEBUG(3)
}
