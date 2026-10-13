package com.campmeds.app.ui.addmedication

import com.campmeds.app.data.entity.AdministrationTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class SuggestedGroupNameTest {

    private val breakfast = AdministrationTime(id = "b", name = "Breakfast", time = LocalTime.of(8, 0))
    private val dinner = AdministrationTime(id = "d", name = "Dinner", time = LocalTime.of(18, 0))

    @Test
    fun prefillIsTheSelectedTimeName() {
        assertEquals("Breakfast", suggestedGroupName(listOf(breakfast, dinner), listOf("b")))
    }

    @Test
    fun severalSelectedTimesAreJoinedInListOrder() {
        assertEquals("Breakfast + Dinner", suggestedGroupName(listOf(breakfast, dinner), listOf("d", "b")))
    }

    @Test
    fun nothingSelectedGivesNoSuggestion() {
        assertEquals("", suggestedGroupName(listOf(breakfast, dinner), emptyList()))
    }
}
