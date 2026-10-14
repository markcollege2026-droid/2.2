package com.campmeds.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campmeds.app.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampSessionMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    @Test fun version6To7KeepsLegacyValuesHistoryAndPresets() {
        val name = "migration-6-7"
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO patients (id,name,firstName,lastName,isArchived,bunk,counselor,division) VALUES ('p','Camper','Camper','',0,'Cabin, East','Smith, Jane','Junior')")
            execSQL("INSERT INTO patients (id,name,firstName,lastName,isArchived) VALUES ('empty','Empty','Empty','',1)")
            execSQL("INSERT INTO camp_settings (id,startDates,endDates,bunkLabel,counselorLabel,divisionLabel) VALUES (1,'2026-07-01','2026-07-31','Cabins','Leaders','Division')")
            execSQL("INSERT INTO dose_logs (id,medicationId,scheduledFor,status,loggedAt,wasOverride,heldReason) VALUES ('log','legacy-med','2026-07-15T08:00','HELD','2026-07-15T08:01',0,'Original reason')")
            close()
        }
        helper.runMigrationsAndValidate(name, 7, true, MIGRATION_6_7).apply {
            query("SELECT bunk,bunks,counselor,counselors,division FROM patients WHERE id='p'").use {
                assertTrue(it.moveToFirst()); assertEquals("Cabin, East", it.getString(0))
                assertEquals("Cabin, East", org.json.JSONArray(it.getString(1)).getString(0))
                assertEquals(1, org.json.JSONArray(it.getString(1)).length())
                assertEquals("Smith, Jane", org.json.JSONArray(it.getString(3)).getString(0))
                assertEquals("Junior", it.getString(4))
            }
            query("SELECT bunks,counselors,isArchived FROM patients WHERE id='empty'").use {
                assertTrue(it.moveToFirst()); assertEquals("[]", it.getString(0)); assertEquals("[]", it.getString(1)); assertEquals(1, it.getInt(2))
            }
            query("SELECT startDates,bunkLabel,allowCounselorOverride FROM camp_settings").use {
                assertTrue(it.moveToFirst()); assertEquals("2026-07-01", it.getString(0)); assertEquals("Cabins", it.getString(1)); assertEquals(1,it.getInt(2))
            }
            query("SELECT status,heldReason,isPrn FROM dose_logs WHERE id='log'").use {
                assertTrue(it.moveToFirst()); assertEquals("HELD", it.getString(0)); assertEquals("Original reason", it.getString(1)); assertEquals(0,it.getInt(2))
            }
            close()
        }
    }
}
