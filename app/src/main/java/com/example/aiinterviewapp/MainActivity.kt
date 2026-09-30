package com.example.aiinterviewapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aiinterviewapp.data.local.datastore.AuthPreferences
import com.example.aiinterviewapp.data.local.datastore.SessionStore
import com.example.aiinterviewapp.data.remote.SessionExpiryNotifier
import com.example.aiinterviewapp.domain.repository.AuthRepository
import com.example.aiinterviewapp.ui.navigation.Screen
import com.example.aiinterviewapp.ui.screens.create.CreateInterviewScreen
import com.example.aiinterviewapp.ui.screens.history.HistoryScreen
import com.example.aiinterviewapp.ui.screens.home.HomeScreen
import com.example.aiinterviewapp.ui.screens.interview.InterviewScreen
import com.example.aiinterviewapp.ui.screens.interview.InterviewViewModel
import com.example.aiinterviewapp.ui.screens.login.LoginScreen
import com.example.aiinterviewapp.ui.screens.profile.ProfileScreen
import com.example.aiinterviewapp.ui.screens.resume.ResumeManagerScreen
import com.example.aiinterviewapp.ui.screens.settings.SettingsScreen
import com.example.aiinterviewapp.ui.screens.splash.SplashScreen
import com.example.aiinterviewapp.ui.theme.AIInterviewAppTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var authPreferences: AuthPreferences
    @Inject lateinit var sessionStore: SessionStore
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var sessionExpiryNotifier: SessionExpiryNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDarkMode by authPreferences.isDarkMode.collectAsState(initial = false)
            AIInterviewAppTheme(darkTheme = isDarkMode) {
                AppNavigation(authPreferences, sessionStore, authRepository, sessionExpiryNotifier)
            }
        }
    }
}

@Composable
fun AppNavigation(
    authPreferences: AuthPreferences,
    sessionStore: SessionStore,
    authRepository: AuthRepository,
    sessionExpiryNotifier: SessionExpiryNotifier
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // The one place a dead session is turned into navigation.
    //
    // Collected here, at the navigation root, rather than in whichever screen
    // happened to make the failing call. Three AI operations can each hit an
    // expired session, and a per-screen handler is a dead-end waiting for the
    // fourth one. It also has to sit above the NavHost so a `popUpTo(0)` from
    // the response does not tear down the collector that is handling it.
    //
    // The notifier is latched, so the several requests that will all fail at
    // once produce exactly one sign-out, and `reset` re-arms it after sign-in.
    LaunchedEffect(Unit) {
        sessionExpiryNotifier.expiries.collect {
            scope.launch {
                authRepository.signOut()
            }
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Screen.Splash.route) {
            // Null while the stored session is still being read, so the splash
            // screen waits rather than flashing the login screen. A session that
            // has already expired reads as false and the user is asked to sign
            // in again, which is the behaviour a boolean flag could not express
            // (RULE 5).
            val isLoggedIn by sessionStore.isSignedIn.collectAsState(initial = null)
            SplashScreen(
                isLoggedIn = isLoggedIn,
                onNavigateToAuth = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(onLoginSuccess = {
                // A new session exists, so the expiry signal can be armed again.
                // Without this the latch stays closed and the *next* expiry
                // happens silently -- the exact dead-end this replaced, one
                // sign-in later.
                sessionExpiryNotifier.reset()
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            })
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToCreate = { navController.navigate(Screen.CreateInterview.route) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToResume = { navController.navigate(Screen.Resume.route) },
                onNavigateToReport = { id -> navController.navigate(Screen.Report.createRoute(id)) },
                onResumeInterview = { interviewId ->
                    navController.navigate(Screen.ResumeInterview.createRoute(interviewId))
                }
            )
        }

        composable(Screen.Resume.route) {
            ResumeManagerScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CreateInterview.route) {
            CreateInterviewScreen(
                onBack = { navController.popBackStack() },
                onStartInterview = { role, type, difficulty, experience, count ->
                    navController.navigate(
                        Screen.Interview.createRoute(role, type, difficulty, experience, count)
                    )
                }
            )
        }

        composable(
            route = Screen.Interview.route,
            arguments = listOf(
                navArgument("role") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType },
                navArgument("difficulty") { type = NavType.StringType },
                navArgument("experience") { type = NavType.StringType },
                navArgument("count") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val role = args?.getString("role")?.let(Uri::decode) ?: "Android Developer"
            val type = args?.getString("type")?.let(Uri::decode) ?: "Technical"
            val difficulty = args?.getString("difficulty")?.let(Uri::decode) ?: "Medium"
            val experience = args?.getString("experience")?.let(Uri::decode) ?: "Fresher"
            val count = args?.getString("count")?.let(Uri::decode)?.toIntOrNull() ?: 5

            val viewModel: InterviewViewModel = hiltViewModel()
            LaunchedEffect(Unit) {
                viewModel.startInterview(role, type, difficulty, experience, count)
            }

            InterviewScreen(
                viewModel = viewModel,
                onNavigateToReport = { interviewId ->
                    navController.navigate(Screen.Report.createRoute(interviewId)) {
                        popUpTo(Screen.Interview.route) { inclusive = true }
                    }
                },
                onQuit = { navController.popBackStack() },
                totalQuestions = count
            )
        }

        composable(
            route = Screen.ResumeInterview.route,
            arguments = listOf(navArgument("interviewId") { type = NavType.StringType })
        ) { backStackEntry ->
            val interviewId = backStackEntry.arguments?.getString("interviewId") ?: ""
            val viewModel: InterviewViewModel = hiltViewModel()
            LaunchedEffect(Unit) {
                viewModel.resumeInterview(interviewId)
            }

            InterviewScreen(
                viewModel = viewModel,
                onNavigateToReport = { id ->
                    navController.navigate(Screen.Report.createRoute(id)) {
                        popUpTo(Screen.ResumeInterview.route) { inclusive = true }
                    }
                },
                onQuit = { navController.popBackStack() }
            )
        }

        composable(Screen.History.route) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onNavigateToReport = { id -> navController.navigate(Screen.Report.createRoute(id)) }
            )
        }

        composable(Screen.Profile.route) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    // Sign-out destroys the session and the cached Google
                    // account choice, but leaves the user's own interviews and
                    // resume on the device. They come back scoped to the same
                    // user id if that person signs in again, and stay invisible
                    // to anyone else (RULE 6, RULE 10).
                    scope.launch { authRepository.signOut() }
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0)
                    }
                }
            )
        }
        
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                authPreferences = authPreferences
            )
        }

        composable(
            route = Screen.Report.route,
            arguments = listOf(navArgument("interviewId") { type = NavType.StringType })
        ) { backStackEntry ->
            val interviewId = backStackEntry.arguments?.getString("interviewId") ?: ""
            com.example.aiinterviewapp.ui.screens.report.ReportScreen(
                interviewId = interviewId,
                onBack = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
