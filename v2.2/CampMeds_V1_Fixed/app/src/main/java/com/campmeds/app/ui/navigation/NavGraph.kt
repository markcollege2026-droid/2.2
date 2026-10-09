package com.campmeds.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.campmeds.app.CampMedsApp
import com.campmeds.app.auth.Session
import com.campmeds.app.ui.addmedication.AddEditMedicationScreen
import com.campmeds.app.ui.dosehistory.DoseHistoryScreen
import com.campmeds.app.ui.duetoday.TodaysDueScreen
import com.campmeds.app.ui.export.ExportScreen
import com.campmeds.app.ui.login.LoginScreen
import com.campmeds.app.ui.patientdetail.PatientDetailScreen
import com.campmeds.app.ui.patientlist.PatientListScreen

object Routes {
    const val LOGIN = "login"
    const val PATIENT_LIST = "patients"
    const val TODAYS_DUE = "today"
    const val EXPORT = "export"
    const val PATIENT_DETAIL = "patients/{patientId}"
    const val DOSE_HISTORY = "patients/{patientId}/history"
    const val ADD_EDIT_MEDICATION = "patients/{patientId}/medication?medicationId={medicationId}"

    fun patientDetail(patientId: String) = "patients/$patientId"
    fun doseHistory(patientId: String) = "patients/$patientId/history"
    fun addMedication(patientId: String) = "patients/$patientId/medication"
    fun editMedication(patientId: String, medicationId: String) =
        "patients/$patientId/medication?medicationId=$medicationId"
}

@Composable
fun CampMedsNavHost(app: CampMedsApp) {
    val navController: NavHostController = rememberNavController()

    // The login session lives only in process memory, but the navigation back stack can be restored by
    // Android after the process was killed. That used to leave the user on e.g. a patient screen with NO
    // session, where every permission-gated control (such as the green + Add Medication button) silently
    // vanished until the app was fully closed and reopened. Whenever there is no session, go to Login.
    val sessionUser by Session.currentUser.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(sessionUser, currentRoute) {
        if (sessionUser == null && currentRoute != null && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    NavHost(navController = navController, startDestination = Routes.LOGIN) {

        composable(Routes.LOGIN) {
            LoginScreen(
                repository = app.repository,
                onLoggedIn = {
                    navController.navigate(Routes.TODAYS_DUE) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.TODAYS_DUE) {
            TodaysDueScreen(
                repository = app.repository,
                onOpenPatients = { navController.navigate(Routes.PATIENT_LIST) },
                onOpenExport = { navController.navigate(Routes.EXPORT) },
                onAddMedication = { patientId -> navController.navigate(Routes.addMedication(patientId)) },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PATIENT_LIST) {
            PatientListScreen(
                repository = app.repository,
                onOpenPatient = { patientId -> navController.navigate(Routes.patientDetail(patientId)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PATIENT_DETAIL,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { backStackEntry ->
            val patientId = backStackEntry.arguments?.getString("patientId").orEmpty()
            PatientDetailScreen(
                repository = app.repository,
                patientId = patientId,
                onAddMedication = { navController.navigate(Routes.addMedication(patientId)) },
                onEditMedication = { medId -> navController.navigate(Routes.editMedication(patientId, medId)) },
                onOpenHistory = { navController.navigate(Routes.doseHistory(patientId)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.DOSE_HISTORY,
            arguments = listOf(navArgument("patientId") { type = NavType.StringType })
        ) { backStackEntry ->
            val patientId = backStackEntry.arguments?.getString("patientId").orEmpty()
            DoseHistoryScreen(
                repository = app.repository,
                patientId = patientId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.ADD_EDIT_MEDICATION,
            arguments = listOf(
                navArgument("patientId") { type = NavType.StringType },
                navArgument("medicationId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val patientId = backStackEntry.arguments?.getString("patientId").orEmpty()
            val medicationId = backStackEntry.arguments?.getString("medicationId")
            AddEditMedicationScreen(
                repository = app.repository,
                patientId = patientId,
                medicationId = medicationId,
                onDone = { navController.popBackStack() }
            )
        }

        composable(Routes.EXPORT) {
            ExportScreen(
                repository = app.repository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
