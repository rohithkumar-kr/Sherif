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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDarkMode by authPreferences.isDarkMode.collectAsState(initial = false)
            AIInterviewAppTheme(darkTheme = isDarkMode) {
                AppNavigation(authPreferences)
            }
        }
    }
}

@Composable
fun AppNavigation(authPreferences: AuthPreferences) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(Screen.Splash.route) {
            val isLoggedIn by authPreferences.isLoggedIn.collectAsState(initial = null)
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
            val scope = rememberCoroutineScope()
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    scope.launch { authPreferences.logout() }
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
