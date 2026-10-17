package com.nitinshukla.notebook.core.util

/** Source of the current time, injectable so business rules can be tested. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
