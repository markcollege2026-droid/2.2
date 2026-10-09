package com.campmeds.app.domain

/** "Mary Ann Smith" -> ("Mary Ann", "Smith"); "Cher" -> ("Cher", ""). Pure, unit-tested. */
fun splitFullName(full: String): Pair<String, String> {
    val parts = full.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (parts.size) {
        0 -> "" to ""
        1 -> parts[0] to ""
        else -> parts.dropLast(1).joinToString(" ") to parts.last()
    }
}
