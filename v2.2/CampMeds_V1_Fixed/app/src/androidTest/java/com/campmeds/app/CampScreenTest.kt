package com.campmeds.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.auth.Session
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.*
import com.campmeds.app.repository.CampMedsRepository
import com.campmeds.app.ui.common.*
import com.campmeds.app.ui.duetoday.*
import com.campmeds.app.ui.patientlist.PatientListScreen
import com.campmeds.app.ui.settings.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class CampScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var repo: CampMedsRepository
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,AppDatabase::class.java).build()
        repo=CampMedsRepository(db)
        Session.login(repo.createUser("Admin",Role.ADMIN,"1234"))
    }
    @After fun cleanup() { Session.logout(); db.close() }
    private fun visible(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun seed() = runBlocking {
        for (name in listOf("Junior","Senior")) {
            repo.saveDivision(Division(name,if(name=="Junior") "#008577" else "#3F51B5"))
            val p=Patient(name="$name camper",firstName="$name camper",division=name)
            repo.savePatient(p)
            repo.saveMedicationEdit(null,Medication(patientId=p.id,ndc="",name="Medication",strength="1",form="tablet",
                dosageInstructions="1",scheduleTimes=listOf(LocalTime.MIDNIGHT),startDate=LocalDate.now()))
        }
    }
    @Test fun homeHasNoMedicationCreationAndFiltersDivisionsAndConfirmsBulkHold() {
        seed()
        compose.setContent { MaterialTheme { TodaysDueScreen(repo,{}, {}, {}, {}, {}) } }
        visible("Junior camper")
        compose.onNodeWithText("Add Medication").assertDoesNotExist()
        compose.onNodeWithText("Division: All divisions ▾").performClick()
        compose.onAllNodesWithText("Junior",useUnmergedTree=true).onLast().performClick()
        compose.onNodeWithText("Senior camper").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("Hold selected patients' due doses (1)").performClick()
        visible("Confirm bulk hold")
        assertTrue(runBlocking { db.doseLogDao().getAll().isEmpty() })
        compose.onNodeWithText("Confirm hold").performClick()
        compose.waitUntil(10_000) { runBlocking { db.doseLogDao().getAll().size == 1 } }
        assertEquals(DoseStatus.HELD,runBlocking { db.doseLogDao().getAll().single().status })
    }
    @Test fun patientListFiltersDivisionAndRestoresAll() {
        seed()
        compose.setContent { MaterialTheme { PatientListScreen(repo,{}, {}) } }
        visible("Junior camper")
        compose.onNodeWithText("Division: All divisions ▾").performClick()
        compose.onAllNodesWithText("Senior",useUnmergedTree=true).onLast().performClick()
        compose.onNodeWithText("Junior camper").assertDoesNotExist()
        compose.onNodeWithText("Senior camper").assertExists()
        compose.onNodeWithText("Division: Senior ▾").performClick()
        compose.onNodeWithText("All divisions").performClick()
        compose.onNodeWithText("Junior camper").assertExists()
    }
    @Test fun settingsHasOnlyOneNamedTimeCreationActionAndNoOldAddTime() {
        compose.setContent { MaterialTheme { SettingsScreen(repo,{}) } }
        compose.onNodeWithText("Add time").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create named administration time"))
        compose.onAllNodesWithText("Create named administration time").assertCountEquals(1)
        compose.onNodeWithText("Create named administration time").performClick()
        compose.onNodeWithText("Add administration time").assertExists()
    }
    @Test fun sessionDropdownCopiesBothDatesButSelectionIsNotLiveBound() {
        val session=CampSession(name="First session",startDate=LocalDate.of(2026,7,1),endDate=LocalDate.of(2026,7,21))
        var selected: CampSession? = null
        compose.setContent { MaterialTheme { SessionPresetField(listOf(session)) { selected=it } } }
        compose.onNodeWithText("Choose named camp session ▾").performClick()
        compose.onNodeWithText("First session: 2026-07-01 — 2026-07-21").performClick()
        assertEquals(session.startDate,selected!!.startDate); assertEquals(session.endDate,selected!!.endDate)
    }
    @Test fun smallScannerKeepsManualVisibleForDeniedErrorAndLivePreviewAtLargeFont() {
        var state by mutableStateOf(0)
        var clicks=0
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp,360.dp)) {
                        MedicationScannerPanel("the medication label",state>=2,state==1,if(state==2) "Camera failed" else null,
                            { clicks++ },{}, {},{}) { Text("Preview content") }
                    }
                }
            }
        }
        for (i in 0..3) {
            compose.runOnIdle { state=i }
            compose.onNodeWithTag("verify-manually").assertIsDisplayed().performClick()
        }
        assertEquals(4,clicks)
        compose.onNodeWithTag("camera-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("verify-manually").assertIsDisplayed()
        val manual=compose.onNodeWithTag("verify-manually").fetchSemanticsNode().boundsInRoot
        val preview=compose.onNodeWithTag("camera-preview").fetchSemanticsNode().boundsInRoot
        assertTrue("Preview must not cover manual control",preview.top>=manual.bottom)
        val panel=compose.onNodeWithTag("scanner-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("Preview stays clipped inside panel",preview.bottom<=panel.bottom)
    }
    @Test fun manualVerificationRemainsPinnedAboveLongMedicationInstructions() {
        var clicked=false
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(320.dp,360.dp)) {
                MedicationSheetBody(onManual = { clicked=true }) {
                    repeat(30) { Text("Long prescription instructions line $it") }
                    MedicationScannerPanel("label",true,false,null,{}, {}, {}, {}, showManual=false) { Text("Preview") }
                }
            }
        } }
        compose.onNodeWithTag("sticky-verify-manually").assertIsDisplayed().performClick()
        assertTrue(clicked)
        compose.onNodeWithTag("camera-preview").performScrollTo()
        compose.onNodeWithTag("sticky-verify-manually").assertIsDisplayed()
    }

}
