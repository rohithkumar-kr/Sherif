package com.example.aiinterviewapp

import com.example.aiinterviewapp.data.remote.ResumeGrounding
import com.example.aiinterviewapp.domain.model.ResumeEducation
import com.example.aiinterviewapp.domain.model.ResumeProfile
import com.example.aiinterviewapp.domain.model.ResumeProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeGroundingTest {

    private val androidResume = """
        Aarav Sharma
        Android Developer
        Skills: Kotlin, Jetpack Compose, Room, Android Studio
        Project: Japanese Vocabulary App built with Kotlin and Room
        Education: B.Tech Computer Science, Anna University, 2024
    """.trimIndent()

    @Test
    fun `keeps all four technologies that appear in the resume`() {
        val profile = ResumeProfile(
            technicalSkills = listOf("Kotlin", "Jetpack Compose", "Room", "Android")
        )

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertEquals(4, grounded.technicalSkills.size)
        assertTrue(grounded.technicalSkills.containsAll(listOf("Kotlin", "Jetpack Compose", "Room", "Android")))
        assertTrue("nothing should have been rejected", report.unsupportedClaims.isEmpty())
    }

    @Test
    fun `drops technologies that are absent from the resume`() {
        val profile = ResumeProfile(
            technicalSkills = listOf("Kotlin", "Spring Boot", "Kubernetes", "AWS", "Room")
        )

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertTrue(grounded.technicalSkills.contains("Kotlin"))
        assertTrue(grounded.technicalSkills.contains("Room"))
        assertFalse(grounded.technicalSkills.contains("Spring Boot"))
        assertFalse(grounded.technicalSkills.contains("Kubernetes"))
        assertFalse(grounded.technicalSkills.contains("AWS"))
        assertTrue(report.unsupportedClaims.containsAll(listOf("Spring Boot", "Kubernetes", "AWS")))
    }

    @Test
    fun `does not satisfy a claim from a word that merely contains it`() {
        val resume = "I always ship on time and enjoy the work."
        val profile = ResumeProfile(technicalSkills = listOf("AWS", "C", "R"))

        val (grounded, _) = ResumeGrounding.ground(profile, resume)

        assertTrue(
            "substring matching must not fabricate skills: ${grounded.technicalSkills}",
            grounded.technicalSkills.isEmpty()
        )
    }

    @Test
    fun `keeps a project stated in the resume and does not substitute another`() {
        val profile = ResumeProfile(
            projects = listOf(
                ResumeProject(
                    name = "Japanese Vocabulary Android Application",
                    technologies = listOf("Kotlin", "Room")
                ),
                ResumeProject(name = "Blockchain Trading Bot", technologies = listOf("Solidity"))
            )
        )

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertEquals(1, grounded.projects.size)
        assertTrue(grounded.projects.single().name!!.contains("Japanese Vocabulary"))
        assertFalse(grounded.projects.any { it.name!!.contains("Blockchain") })
        assertTrue(report.unsupportedClaims.contains("Blockchain Trading Bot"))
    }

    @Test
    fun `keeps stated education and drops an invented institution`() {
        val profile = ResumeProfile(
            education = listOf(
                ResumeEducation(
                    institution = "Anna University",
                    degree = "B.Tech",
                    field = "Computer Science"
                ),
                ResumeEducation(
                    institution = "Stanford University",
                    degree = "B.Tech",
                    field = "Computer Science"
                )
            )
        )

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertEquals("only the real institution may survive", 1, grounded.education.count { it.institution != null })
        assertEquals("Anna University", grounded.education.first { it.institution != null }.institution)
        assertTrue(
            "no unrelated institution may be recorded",
            grounded.education.none { it.institution == "Stanford University" }
        )
        assertTrue(report.unsupportedClaims.contains("Stanford University"))
    }

    @Test
    fun `drops an education entry whose every field is unsupported`() {
        val profile = ResumeProfile(
            education = listOf(
                ResumeEducation(institution = "Stanford University", degree = "PhD")
            )
        )

        val (grounded, _) = ResumeGrounding.ground(profile, androidResume)

        assertTrue(grounded.education.isEmpty())
    }

    @Test
    fun `project technologies are grounded individually`() {
        val profile = ResumeProfile(
            projects = listOf(
                ResumeProject(
                    name = "Japanese Vocabulary App",
                    technologies = listOf("Kotlin", "Docker", "Terraform")
                )
            )
        )

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertEquals(listOf("Kotlin"), grounded.projects.single().technologies)
        assertTrue(report.unsupportedClaims.containsAll(listOf("Docker", "Terraform")))
    }

    @Test
    fun `interpretive assessment survives grounding`() {
        val profile = ResumeProfile(
            summary = "A developer who is still early in their career.",
            strengths = listOf("Shows enthusiasm"),
            areasForImprovement = listOf("No measurable impact", "Missing project links"),
            missingInformation = listOf("no certifications listed"),
            resumeQuality = com.example.aiinterviewapp.domain.model.ResumeQuality(
                overallRating = "Needs work",
                notes = "Add metrics."
            )
        )

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertEquals("A developer who is still early in their career.", grounded.summary)
        assertEquals(listOf("Shows enthusiasm"), grounded.strengths)
        assertEquals(2, grounded.areasForImprovement.size)
        assertEquals(listOf("no certifications listed"), grounded.missingInformation)
        assertEquals("Needs work", grounded.resumeQuality.overallRating)
        assertTrue(report.unsupportedClaims.isEmpty())
    }

    @Test
    fun `contact details are left untouched because they are not skills`() {
        val profile = ResumeProfile(
            candidateEmail = "aarav@example.com",
            candidatePhone = "+91 90000 00000"
        )

        val (grounded, _) = ResumeGrounding.ground(profile, androidResume)

        assertEquals("aarav@example.com", grounded.candidateEmail)
        assertEquals("+91 90000 00000", grounded.candidatePhone)
    }

    @Test
    fun `blank claims are discarded without being reported as unsupported`() {
        val profile = ResumeProfile(technicalSkills = listOf("", "   ", "Kotlin"))

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertEquals(listOf("Kotlin"), grounded.technicalSkills)
        assertTrue(report.unsupportedClaims.isEmpty())
    }

    @Test
    fun `a target role absent from the resume is dropped`() {
        val profile = ResumeProfile(
            targetRole = "Android Developer",
            candidateName = "Aarav Sharma"
        )

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertEquals("Aarav Sharma", grounded.candidateName)
        assertEquals("Android Developer", grounded.targetRole)
        assertTrue(report.unsupportedClaims.isEmpty())
    }

    @Test
    fun `a candidate name that is absent from the resume is dropped`() {
        val profile = ResumeProfile(candidateName = "Somebody Else")

        val (grounded, report) = ResumeGrounding.ground(profile, androidResume)

        assertNull(grounded.candidateName)
        assertEquals(listOf("Somebody Else"), report.unsupportedClaims)
    }
}
