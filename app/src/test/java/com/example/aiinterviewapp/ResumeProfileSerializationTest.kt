package com.example.aiinterviewapp

import com.example.aiinterviewapp.domain.model.ResumeEducation
import com.example.aiinterviewapp.domain.model.ResumeExperience
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.ResumeProject
import com.example.aiinterviewapp.domain.model.ResumeQuality
import com.example.aiinterviewapp.domain.model.factualClaims
import com.example.aiinterviewapp.domain.model.hasContent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeProfileSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val fullProfile = ResumeProfile(
        candidateName = "Aarav Sharma",
        candidateEmail = "aarav@example.com",
        candidatePhone = "+91 90000 00000",
        targetRole = "Android Developer",
        summary = "Android developer focused on Compose and Room.",
        technicalSkills = listOf("Kotlin", "Jetpack Compose", "Room", "Android"),
        softSkills = listOf("Communication", "Teamwork"),
        education = listOf(
            ResumeEducation(
                institution = "Anna University",
                degree = "B.Tech",
                field = "Computer Science",
                graduationYear = "2024"
            )
        ),
        workExperience = listOf(
            ResumeExperience(
                company = "Acme",
                role = "Android Intern",
                duration = "6 months",
                description = "Built Compose screens."
            )
        ),
        projects = listOf(
            ResumeProject(
                name = "Japanese Vocabulary App",
                technologies = listOf("Kotlin", "Room"),
                description = "Flashcard app."
            )
        ),
        certifications = listOf("Android Basics"),
        achievements = listOf("Won a hackathon"),
        languages = listOf("English", "Japanese"),
        strengths = listOf("Clear communicator"),
        areasForImprovement = listOf("Add measurable outcomes"),
        missingInformation = listOf("no certifications listed"),
        resumeQuality = ResumeQuality(
            overallRating = "Strong",
            clarityRating = "Adequate",
            impactRating = "Weak",
            notes = "Quantify achievements."
        )
    )

    @Test
    fun `serializes a fully populated profile`() {
        val encoded = json.encodeToString(ResumeProfile.serializer(), fullProfile)

        assertTrue(encoded.contains("Aarav Sharma"))
        assertTrue(encoded.contains("Japanese Vocabulary App"))
        assertTrue(encoded.contains("Anna University"))
        assertTrue(encoded.contains("overallRating"))
    }

    @Test
    fun `deserializes a valid Gemini response into a profile`() {
        val raw = """
            {
              "candidateName": "Aarav Sharma",
              "targetRole": "Android Developer",
              "summary": "Android developer focused on Compose and Room.",
              "technicalSkills": ["Kotlin", "Jetpack Compose", "Room", "Android"],
              "softSkills": ["Communication"],
              "education": [
                {"institution": "Anna University", "degree": "B.Tech", "field": "Computer Science", "graduationYear": "2024"}
              ],
              "workExperience": [
                {"company": "Acme", "role": "Android Intern", "duration": "6 months", "description": "Built Compose screens."}
              ],
              "projects": [
                {"name": "Japanese Vocabulary App", "technologies": ["Kotlin", "Room"], "description": "Flashcard app."}
              ],
              "certifications": ["Android Basics"],
              "achievements": ["Won a hackathon"],
              "languages": ["English", "Japanese"],
              "strengths": ["Clear communicator"],
              "areasForImprovement": ["Add measurable outcomes"],
              "missingInformation": ["no certifications listed"],
              "resumeQuality": {"overallRating": "Strong", "notes": "Quantify achievements."}
            }
        """.trimIndent()

        val profile = json.decodeFromString(ResumeProfile.serializer(), raw)

        assertEquals("Aarav Sharma", profile.candidateName)
        assertEquals("Android Developer", profile.targetRole)
        assertEquals(listOf("Kotlin", "Jetpack Compose", "Room", "Android"), profile.technicalSkills)
        assertEquals("Anna University", profile.education.single().institution)
        assertEquals("B.Tech", profile.education.single().degree)
        assertEquals("Japanese Vocabulary App", profile.projects.single().name)
        assertEquals(listOf("Kotlin", "Room"), profile.projects.single().technologies)
        assertEquals("Acme", profile.workExperience.single().company)
        assertEquals("Strong", profile.resumeQuality.overallRating)
        assertEquals("Quantify achievements.", profile.resumeQuality.notes)
        assertTrue(profile.hasContent)
    }

    @Test
    fun `deserialization tolerates a response with only a few fields`() {
        val profile = json.decodeFromString(
            ResumeProfile.serializer(),
            """{"candidateName":"Someone","technicalSkills":["Kotlin"]}"""
        )

        assertEquals("Someone", profile.candidateName)
        assertEquals(listOf("Kotlin"), profile.technicalSkills)
        assertNull(profile.summary)
        assertTrue(profile.education.isEmpty())
        assertTrue(profile.certifications.isEmpty())
        assertNull(profile.resumeQuality.overallRating)
    }

    @Test
    fun `deserialization ignores unknown fields from Gemini`() {
        val profile = json.decodeFromString(
            ResumeProfile.serializer(),
            """{"candidateName":"Someone","somethingNew":{"nested":true},"anotherOne":42}"""
        )

        assertEquals("Someone", profile.candidateName)
    }

    @Test
    fun `round trip preserves every persisted field`() {
        val encoded = json.encodeToString(ResumeProfile.serializer(), fullProfile)
        val decoded = json.decodeFromString(ResumeProfile.serializer(), encoded)

        assertEquals(fullProfile, decoded)
        assertEquals(fullProfile.factualClaims, decoded.factualClaims)
    }

    @Test
    fun `an empty profile reports no content`() {
        assertFalse(ResumeProfile().hasContent)
        assertTrue(ResumeProfile(summary = " ").hasContent.not())
    }
}
