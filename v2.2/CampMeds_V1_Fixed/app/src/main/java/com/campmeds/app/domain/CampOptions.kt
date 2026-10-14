package com.campmeds.app.domain

import com.campmeds.app.data.entity.*

fun cleanNames(values: List<String>): List<String> = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Re-evaluate only when a bunk is selected, never when editing a shared definition in Settings. */
fun counselorsForBunks(selected: List<String>, options: List<BunkOption>): List<String> =
    cleanNames(selected.flatMap { name -> options.firstOrNull { it.name == name }?.counselors.orEmpty() })

fun Patient.matchesDivision(selected: String?): Boolean = selected == null || division == selected
fun validDivisionColor(color: String): Boolean = color.matches(Regex("#[0-9a-fA-F]{6}"))
