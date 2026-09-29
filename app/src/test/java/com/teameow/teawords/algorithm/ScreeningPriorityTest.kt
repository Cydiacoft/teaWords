package com.teameow.teawords.algorithm

import org.junit.Assert.*
import org.junit.Test

/**
 * The screening redesign.
 *
 * The problem being tested: a 4500-word word book must not have to be judged word by word, and study
 * must not be blocked behind finishing that judgement.
 */
class ScreeningPriorityTest {

    private fun candidates(count: Int, difficulty: (Int) -> Double) =
        (1..count).map { ScreenCandidate("word$it", difficulty(it)) }

    /**
     * A word book with a realistic difficulty spread.
     *
     * The bands can only save work when the book spans a range: if every word sits at the same
     * difficulty, a given ability lands them all in the same band. Real word books span easy high
     * frequency words through hard low frequency ones, so the fixture does too.
     */
    private fun realisticBook(count: Int) = candidates(count) { i ->
        when {
            i % 10 == 0 -> -1.2
            i % 3 == 0 -> 0.8
            else -> 0.0
        }
    }

    @Test
    fun `without a diagnostic the queue is still ordered rather than flat`() {
        val plan = ScreeningPriority.plan(AbilityEstimator.initial, candidates(100) { it / 20.0 })
        // With no answers every word carries the same predicted probability, so nothing may be
        // claimed as known; but the queue must still be usable.
        assertEquals(0, plan.skipAsKnown.size)
        assertTrue("some words must be queued for judgement", plan.needsScreening.isNotEmpty())
        assertTrue("plan must cover every candidate", plan.total == 100)
    }

    @Test
    fun `a confident ability estimate collapses the screening queue`() {
        // A strong user: theta comfortably above the easiest words in this book.
        var ability = AbilityEstimator.initial
        repeat(14) { ability = AbilityEstimator.update(ability, ResponseObservation(1.5, correct = true)) }

        val plan = ScreeningPriority.plan(ability, realisticBook(4500))
        assertTrue("a strong user must have words predicted known, got ${plan.skipAsKnown.size}",
            plan.skipAsKnown.size > 100)
        assertTrue("only a fraction should need manual judgement, got ${plan.needsScreening.size}",
            plan.needsScreening.size < plan.total / 2)
        assertTrue("the saving must be reported, got ${plan.savedFraction}", plan.savedFraction > 0.5)
        assertEquals("every candidate must be classified", 4500, plan.total)
    }

    @Test
    fun `a weak user gets unknown words queued for learning`() {
        // Weak relative to this book: words are queued to learn instead of being asked about one by
        // one, and nothing may be claimed as already known.
        val plan = ScreeningPriority.plan(established(-1.0), realisticBook(4500))
        assertTrue("a weak user must have words queued to learn directly, got ${plan.learnDirectly.size}",
            plan.learnDirectly.size > 500)
        assertEquals("a weak user should not be told anything is known", 0, plan.skipAsKnown.size)
    }

    @Test
    fun `a strong user is never asked to learn what they already know`() {
        val plan = ScreeningPriority.plan(established(2.0), realisticBook(4500))
        assertEquals("a strong user must not be given words to learn",
            0, plan.learnDirectly.size)
        assertTrue("a strong user must have words skipped", plan.skipAsKnown.size > 1000)
    }

    @Test
    fun `only the uncertain band is worth a tap`() {
        var ability = AbilityEstimator.initial
        repeat(10) { ability = AbilityEstimator.update(ability, ResponseObservation(0.0, correct = true)) }
        val pool = candidates(200) { -2.0 + it * 0.02 }
        val worth = ScreeningPriority.worthScreening(ability, pool)
        assertTrue("something must be worth asking", worth.isNotEmpty())
        worth.forEach { (_, outcome) ->
            assertEquals(ScreeningPriority.Band.UNCERTAIN, outcome.band)
            assertTrue(outcome.probabilityKnown in ScreeningPriority.UNKNOWN_THRESHOLD..ScreeningPriority.KNOWN_THRESHOLD)
        }
    }

    @Test
    fun `screening value peaks where the answer changes the plan`() {
        val ability = AbilityEstimator.initial
        val borderline = ScreenCandidate("borderline", 0.0)
        val obvious = ScreenCandidate("obvious", -4.0)
        val borderlineValue = ScreeningPriority.classify(ability, borderline).screeningValue
        val obviousValue = ScreeningPriority.classify(ability, obvious).screeningValue
        assertTrue("a decisive word must outrank an obvious one ($borderlineValue vs $obviousValue)",
            borderlineValue > obviousValue)
    }

    @Test
    fun `the queue is ordered so the most decisive words come first`() {
        var ability = AbilityEstimator.initial
        repeat(8) { ability = AbilityEstimator.update(ability, ResponseObservation(0.5, correct = true)) }
        val plan = ScreeningPriority.plan(ability, candidates(300) { -2.0 + it * 0.02 })
        val values = plan.needsScreening.map { it.second.screeningValue }
        assertEquals("queue must be sorted by screening value", values.sortedDescending(), values)
    }

    @Test
    fun `predicted words are labelled as predictions, never as verified knowledge`() {
        var ability = AbilityEstimator.initial
        repeat(10) { ability = AbilityEstimator.update(ability, ResponseObservation(1.0, correct = true)) }
        val plan = ScreeningPriority.plan(ability, candidates(50) { it * 0.1 })
        plan.skipAsKnown.forEach { (_, outcome) ->
            assertTrue("a skipped word carries a probability", outcome.probabilityKnown >= ScreeningPriority.KNOWN_THRESHOLD)
        }
        // The summary must say the numbers come from an estimate, not from tests.
        val note = ScreeningPriority.describe(ability, plan)
        assertTrue("summary must mention the ability estimate: $note", note.contains("能力估计"))
    }

    /**
     * A synthetic, fully-evidenced estimate.
     *
     * The banding rules must be tested against a *known* ability, not against whatever the estimator
     * happens to produce from a short session: the estimator is deliberately anchored to its prior, so
     * a dozen answers do not move it far. Estimator behaviour has its own directional test below.
     */
    private fun established(theta: Double) = AbilityEstimate(
        theta = theta,
        standardError = 0.3,
        itemsAnswered = 20,
        totalInformation = 11.1
    )

    @Test
    fun `a 4500 word book does not have to be judged word by word`() {
        // A user who is comfortable with this word book: theta one logit above the CET-4 baseline.
        // Roughly half the book is then decided by the estimate, and the rest still gets a real
        // decision (taught or asked about) rather than being silently dropped.
        val plan = ScreeningPriority.plan(established(1.0), realisticBook(4500))
        assertTrue(
            "the word book must not require a tap per word: ${plan.needsScreening.size} of ${plan.total}",
            plan.needsScreening.size < plan.total
        )
        assertTrue(
            "a substantial share must be decided by the estimate, got ${(plan.savedFraction * 100).toInt()}%",
            plan.savedFraction > 0.3
        )
        assertTrue("words the estimate is unsure about must still be asked",
            plan.needsScreening.isNotEmpty())
    }

    @Test
    fun `banding responds to the ability estimate`() {
        // Extreme abilities legitimately put the whole book in one band; the interesting range is the
        // middle, where the same word book produces different plans for different users.
        val plans = listOf(-2.0, -1.0, 0.0, 1.0, 2.0).associateWith { ScreeningPriority.plan(established(it), realisticBook(4500)) }

        assertTrue("a strong user must have words skipped as known",
            plans.getValue(2.0).skipAsKnown.isNotEmpty())
        assertEquals("a beginner must not have words claimed as known",
            0, plans.getValue(-2.0).skipAsKnown.size)
        assertTrue("a beginner must have words queued to learn directly",
            plans.getValue(-2.0).learnDirectly.isNotEmpty())

        // The plan must change as ability changes, and the amount that needs manual judgement must
        // shrink as the user gets stronger.
        val uncertain = listOf(-2.0, -1.0, 0.0, 1.0, 2.0).map { plans.getValue(it).needsScreening.size }
        assertTrue("the queue size must respond to ability, got $uncertain",
            uncertain.distinct().size > 1)
        assertTrue("a stronger user must never need more manual judgement than a weaker one: $uncertain",
            uncertain.last() <= uncertain.first())
    }

    @Test
    fun `the estimator moves in the right direction before the bands are trusted`() {
        // Directional check on the estimator itself, separate from the banding thresholds.
        val allCorrect = AbilityEstimator.initial.let { start ->
            (1..12).fold(start) { acc, _ -> AbilityEstimator.update(acc, ResponseObservation(0.0, correct = true)) }
        }
        val allWrong = AbilityEstimator.initial.let { start ->
            (1..12).fold(start) { acc, _ -> AbilityEstimator.update(acc, ResponseObservation(0.0, correct = false)) }
        }
        assertTrue("correct answers must raise theta above the prior", allCorrect.theta > 0.2)
        assertTrue("wrong answers must lower theta below the prior", allWrong.theta < -0.2)
        assertTrue("the spread must be large enough to band words",
            allCorrect.theta - allWrong.theta > 0.8)
    }
}
