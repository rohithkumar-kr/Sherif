package com.example.aiinterviewapp.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Home : Screen("home")
    object CreateInterview : Screen("create_interview")
    object Interview : Screen("interview/{role}/{type}/{difficulty}/{experience}/{count}") {
        fun createRoute(
            role: String,
            type: String,
            difficulty: String,
            experience: String,
            count: Int
        ): String = "interview/${Uri.encode(role)}/${Uri.encode(type)}/${Uri.encode(difficulty)}/${Uri.encode(experience)}/$count"
    }
    object ResumeInterview : Screen("resume_interview/{interviewId}") {
        fun createRoute(interviewId: String) = "resume_interview/$interviewId"
    }
    object Report : Screen("report/{interviewId}") {
        fun createRoute(interviewId: String) = "report/$interviewId"
    }
    object History : Screen("history")
    object Profile : Screen("profile")
    object Settings : Screen("settings")
    object Resume : Screen("resume")
}
