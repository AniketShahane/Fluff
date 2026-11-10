package app.fluff.core

import app.fluff.core.importer.FitbodCsv
import app.fluff.core.importer.FitbodExercises
import app.fluff.core.importer.FitbodFormatException
import app.fluff.core.importer.FitbodImporter
import app.fluff.core.model.SetKind
import app.fluff.core.model.WeightUnit
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class FitbodImportTest {
    private val appHeader = "Date,Exercise,Reps,Weight(kg),Duration(s),Distance(m),Incline,Resistance,isWarmup,Note,multiplier"
    private val supportHeader = "date,exercise_name,Reps,weight_kg,duration_seconds,distance_meters,Incline,Resistance,isWarmup"

    private val sample = """
        $appHeader
        2025-01-19 09:37:32 +0000,Back Squat, 5,60.0,0.0,0.0,0.0,0.0,true,, 1.0
        2025-01-19 09:37:32 +0000,Back Squat, 5,100.0,0.0,0.0,0.0,0.0,false,"felt heavy, stop at 8", 1.0
        2025-01-19 09:37:32 +0000,Back Squat, 5,100.0,0.0,0.0,0.0,0.0,false,, 1.0
        2025-01-19 09:37:32 +0000,Dumbbell Bench Press, 10,30.0,0.0,0.0,0.0,0.0,false,, 2.0
        2025-01-19 09:37:32 +0000,Smith Machine Squat, 8,80.0,0.0,0.0,0.0,0.0,false,, 1.0
        2025-01-19 09:37:32 +0000,Plank, 0,0.0,45.0,0.0,0.0,0.0,false,, 0.0
        2025-01-19 09:37:32 +0000,Back Squat, 0,0.0,0.0,0.0,0.0,0.0,false,, 1.0
        2025-01-21 18:05:00 +0000,Pull Up, 8,0.0,0.0,0.0,0.0,0.0,false,, 1.0
        2025-01-21 18:05:00 +0000,Elliptical, 0,0.0,600.0,804.67,0.0,0.0,false,, 0.0
    """.trimIndent()

    @Test fun parsesTheAppExport() {
        val export = FitbodCsv.parse("﻿" + sample.replace("\n", "\r\n"))
        assertThat(export.format).isEqualTo("Fitbod app export")
        assertThat(export.unitFromHeader).isEqualTo(WeightUnit.KG)
        assertThat(export.rows).hasSize(9)
        val first = export.rows.first()
        assertThat(first.exercise).isEqualTo("Back Squat")
        assertThat(first.time).isEqualTo(Instant.parse("2025-01-19T09:37:32Z"))
        assertThat(first.reps).isEqualTo(5)
        assertThat(first.warmup).isTrue()
        assertThat(export.rows[5].durationSec).isEqualTo(45)
    }

    @Test fun groupsWorkoutsMapsExercisesAndKeepsUnknownOnesAsHistory() {
        val plan = FitbodImporter.plan(FitbodCsv.parse(sample), zone = ZoneOffset.UTC)
        assertThat(plan.workouts).hasSize(2)
        val day1 = plan.workouts[0]
        assertThat(day1.date).isEqualTo(LocalDate.of(2025, 1, 19))
        // The all-zero squat row is dropped; order follows the file.
        assertThat(day1.sets.map { it.exerciseId }).containsExactly(
            "back_squat", "back_squat", "back_squat", "db_bench_press", "fitbod:Smith Machine Squat", "plank",
        ).inOrder()
        assertThat(day1.sets.first().kind).isEqualTo(SetKind.WARMUP)
        assertThat(day1.sets.map { it.setIndex }.take(3)).containsExactly(0, 1, 2).inOrder()
        assertThat(day1.sets.last().durationSec).isEqualTo(45)
        assertThat(day1.sets.last().weightKg).isNull()
        // Bodyweight pull-up keeps added weight empty.
        assertThat(plan.workouts[1].sets.first { it.exerciseId == "pull_up" }.weightKg).isNull()
        assertThat(plan.historyOnly.map { it.fitbodName }).containsExactly("Smith Machine Squat", "Elliptical")
        assertThat(plan.heaviest).isEqualTo("Back Squat" to 100.0)
        assertThat(plan.firstDate).isEqualTo(LocalDate.of(2025, 1, 19))
    }

    @Test fun convertsPoundsWhenAsked() {
        val plan = FitbodImporter.plan(FitbodCsv.parse(sample), unit = WeightUnit.LB, zone = ZoneOffset.UTC)
        assertThat(plan.workouts[0].sets[1].weightKg).isEqualTo(45.36)
        val lbHeader = FitbodCsv.parse(sample.replace("Weight(kg)", "Weight(lbs)"))
        assertThat(lbHeader.unitFromHeader).isEqualTo(WeightUnit.LB)
    }

    @Test fun readsTheSupportExportWithDateOnlyAndConversionNoise() {
        val text = "$supportHeader\n2025-06-23,Seated Leg Curl,5,31.751465900000003,,,,,false\n2025-06-23,Farmer's Walk,,20.41165665,,18.288,,,false\n"
        val export = FitbodCsv.parse(text)
        assertThat(export.format).isEqualTo("Fitbod support export")
        assertThat(export.rows.all { it.dateOnly }).isTrue()
        val plan = FitbodImporter.plan(export, zone = ZoneId.of("America/Los_Angeles"))
        assertThat(plan.workouts).hasSize(1)
        assertThat(plan.workouts[0].date).isEqualTo(LocalDate.of(2025, 6, 23))
        assertThat(plan.workouts[0].sets.map { it.exerciseId }).containsExactly("leg_curl", "farmer_carry").inOrder()
        assertThat(plan.workouts[0].sets[0].weightKg).isEqualTo(31.75)
    }

    @Test fun datesWithOffsetsAndWithoutTimes() {
        assertThat(FitbodCsv.parseDate("2023-10-24 18:24:59 +0200")!!.first).isEqualTo(Instant.parse("2023-10-24T16:24:59Z"))
        assertThat(FitbodCsv.parseDate("2023-10-24 18:24:59 -0500")!!.first).isEqualTo(Instant.parse("2023-10-24T23:24:59Z"))
        assertThat(FitbodCsv.parseDate("2023-10-24", ZoneOffset.UTC)).isEqualTo(Instant.parse("2023-10-24T12:00:00Z") to true)
        assertThat(FitbodCsv.parseDate("24/10/2023")).isNull()
        assertThat(FitbodCsv.parseDate("2023-13-24 10:00:00 +0000")).isNull()
    }

    @Test fun rejectsFilesThatAreNotFitbodExports() {
        assertThrows(FitbodFormatException::class.java) { FitbodCsv.parse("") }
        val e = assertThrows(FitbodFormatException::class.java) { FitbodCsv.parse("Workout,Name\n1,2\n") }
        assertThat(e.message).contains("missing Date")
        val bad = assertThrows(FitbodFormatException::class.java) { FitbodCsv.parse("$appHeader\nyesterday,Back Squat,5,100,0,0,0,0,false,,1\n") }
        assertThat(bad.message).contains("Row 2")
    }

    @Test fun matchingIgnoresCasePunctuationAndRejectsNearMisses() {
        assertThat(FitbodExercises.match("barbell bench press")).isEqualTo("bench_press")
        assertThat(FitbodExercises.match("Farmers Walk")).isEqualTo("farmer_carry")
        assertThat(FitbodExercises.match("Pull-Up")).isEqualTo("pull_up")
        assertThat(FitbodExercises.match("Assisted Pull Up")).isNull()
        assertThat(FitbodExercises.match("Smith Machine Bench Press")).isNull()
        assertThat(FitbodExercises.idFor("Zottman Curl")).isEqualTo("fitbod:Zottman Curl")
    }

    @Test fun quotedFieldsKeepCommasAndLineBreaks() {
        assertThat(FitbodCsv.splitCsv("\"a, b\",\"say \"\"hi\"\"\",\"two\nlines\",x\n\n")).containsExactly(listOf("a, b", "say \"hi\"", "two\nlines", "x"))
    }
}
