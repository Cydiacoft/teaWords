package com.teameow.teawords.algorithm

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Acceptance tests from the specification, at the algorithm layer.
 *
 * A: a user who already knows most of a word book is not forced through it.
 * B: answering harder items correctly raises the ability estimate.
 * C: self-reported known words leave the new-word queue but stay eligible for spot checks.
 * D: repeated wrong answers lower the mastery belief.
 * E: knowing one sense does not mark another sense of the same word as known.
 * G: FSRS derives the next interval from the review outcome, not from a fixed table.
 */
class AlgorithmAcceptanceTest {

    private val now = 1_800_000_000_000L

    private fun unit(
        word: String,
        sense: String = "义项1",
        difficulty: Double = 0.0,
        predicted: Double = 0.5,
        belief: KnowledgeBelief = KnowledgeBelief.unseen(now),
        memory: MemoryState = MemoryState.unseen,
        importance: Double = 1.0,
        selfKnown: Boolean = false,
        dueAt: Long = 0L,
        mode: PracticeMode = PracticeMode.RECALL
    ) = StudyUnit(
        wordId = word.hashCode().toLong(),
        word = word,
        senseId = sense.hashCode().toLong(),
        senseLabel = sense,
        mode = mode,
        difficulty = difficulty,
        importance = importance,
        belief = belief,
        memory = memory,
        predictedMastery = predicted,
        selfReportedKnown = selfKnown,
        dueAtMillis = dueAt
    )

    // --- Test A -------------------------------------------------------------------------

    @Test
    fun `a user who knows most of the book is not forced through it`() {
        val units = (1..1000).map { i ->
            val known = i <= 900
            unit(
                word = "word$i",
                predicted = if (known) 0.95 else 0.10,
                belief = if (known) {
                    KnowledgeBelief(0.95, 0.0, false, 0, 0, now, MasterySource.SELF_REPORT)
                } else {
                    KnowledgeBelief(0.10, 0.0, false, 0, 0, now, MasterySource.SELF_REPORT)
                },
                selfKnown = known,
                importance = 1.0
            )
        }
        val plan = LearningDecisionEngine.decide(
            DecisionInput(units, LearningStrategy.BALANCED, now, limit = 20)
        )
        // The 900 self-reported known words must not fill the plan.
        assertTrue("plan should only target the unknowns, got ${plan.size}", plan.size == 20)
        assertTrue("no self-reported known word should be scheduled for learning",
            plan.none { it.unit.selfReportedKnown })
        assertTrue("every scheduled unit should be an unknown one",
            plan.all { it.unit.predictedMastery < 0.5 })
    }

    // --- Test B -------------------------------------------------------------------------

    @Test
    fun `correct answers on hard items raise the ability estimate`() {
        var estimate = AbilityEstimator.initial
        assertEquals(0.0, estimate.theta, 1e-9)
        repeat(6) {
            estimate = AbilityEstimator.update(estimate, ResponseObservation(1.1, correct = true))
        }
        assertTrue("theta should rise above the prior, was ${estimate.theta}", estimate.theta > 0.5)
        assertTrue("more answers must reduce uncertainty", estimate.standardError < AbilityEstimator.PRIOR_SD)
    }

    @Test
    fun `wrong answers on easy items lower the ability estimate`() {
        var estimate = AbilityEstimator.initial
        repeat(6) {
            estimate = AbilityEstimator.update(estimate, ResponseObservation(-0.3, correct = false))
        }
        assertTrue("theta should fall, was ${estimate.theta}", estimate.theta < -0.3)
    }

    @Test
    fun `a single answer never looks like a precise measurement`() {
        val afterOne = AbilityEstimator.update(
            AbilityEstimator.initial,
            ResponseObservation(0.0, correct = true)
        )
        assertFalse("one answer must not be treated as precise", afterOne.isPreciseEnough)
        assertTrue("interval must be wide", afterOne.interval.endInclusive - afterOne.interval.start > 1.0)
    }

    // --- Test C -------------------------------------------------------------------------

    @Test
    fun `self-reported known words leave the queue but stay eligible for spot checks`() {
        val selfKnown = unit(
            word = "issue", predicted = 0.62, selfKnown = true,
            belief = KnowledgeBelief(0.80, 0.0, false, 0, 0, now, MasterySource.SELF_REPORT),
            importance = 1.0
        )
        val plan = LearningDecisionEngine.decide(
            DecisionInput(listOf(selfKnown), LearningStrategy.BALANCED, now, limit = 20)
        )
        // Predicted mastery 0.62 is below the balanced skip threshold (0.85): a spot check is due,
        // but never a full teaching task.
        assertEquals(1, plan.size)
        assertEquals(StudyAction.VALIDATE, plan.first().action)

        // With high predicted mastery there is no teaching or validation work; the word leaves the
        // queue entirely. With nothing else to do the engine may offer it as a low-priority spot
        // check, which is the "occasional sampling" the spec asks for — never a learning task.
        val confidentlyKnown = selfKnown.copy(predictedMastery = 0.93)
        val idlePlan = LearningDecisionEngine.decide(
            DecisionInput(listOf(confidentlyKnown), LearningStrategy.BALANCED, now, limit = 20)
        )
        assertTrue("a confidently known word must never be scheduled for learning",
            idlePlan.none { it.action == StudyAction.LEARN })
        assertTrue("only a spot check may remain, got ${idlePlan.map { it.action }}",
            idlePlan.all { it.action == StudyAction.VALIDATE })
        assertTrue("at most one spot check", idlePlan.size <= 1)

        // When there is real work to do, the known word is not scheduled at all.
        val withWork = LearningDecisionEngine.decide(
            DecisionInput(
                listOf(confidentlyKnown, selfKnown.copy(senseId = 99L, predictedMastery = 0.20, selfReportedKnown = false)),
                LearningStrategy.BALANCED, now, limit = 20
            )
        )
        assertTrue("the known word must not appear when real gaps exist",
            withWork.none { it.unit.senseId == confidentlyKnown.senseId })
    }

    // --- Test D -------------------------------------------------------------------------

    @Test
    fun `repeated wrong answers lower the mastery belief`() {
        var belief = KnowledgeBelief.unseen(now)
        val start = belief.mastery
        repeat(3) {
            belief = BayesianKnowledgeTracing.observe(
                belief, correct = false, context = ResponseContext.NORMAL, now = now
            )
        }
        assertTrue("mastery should fall from $start, was ${belief.mastery}", belief.mastery < start)
        assertFalse("wrong answers must not verify a word", belief.verifiedByTest)
    }

    @Test
    fun `only a run of correct answers verifies a word`() {
        var belief = KnowledgeBelief.unseen(now)
        repeat(BayesianKnowledgeTracing.VERIFY_STREAK - 1) {
            belief = BayesianKnowledgeTracing.observe(belief, true, ResponseContext.NORMAL, now)
        }
        assertFalse("two correct answers are not verification", belief.verifiedByTest)
        belief = BayesianKnowledgeTracing.observe(belief, true, ResponseContext.NORMAL, now)
        assertTrue("three consecutive correct answers verify", belief.verifiedByTest)
        // A single later miss must clear the verified flag.
        belief = BayesianKnowledgeTracing.observe(belief, false, ResponseContext.NORMAL, now)
        assertFalse("a miss clears verification", belief.verifiedByTest)
    }

    @Test
    fun `a revealed answer is not evidence of knowledge`() {
        val before = KnowledgeBelief.fromSelfReport("UNKNOWN", now)
        val after = BayesianKnowledgeTracing.observe(before, correct = false, context = ResponseContext.REVEALED, now = now)
        assertEquals("revealing must not count as a test answer", before.totalAnswers, after.totalAnswers)
        assertEquals("revealing must not change mastery", before.mastery, after.mastery, 1e-9)
    }

    @Test
    fun `a very fast correct answer is treated as a possible guess`() {
        val base = KnowledgeBelief(0.5, 0.0, false, 0, 0, now, MasterySource.SELF_REPORT)
        val normal = BayesianKnowledgeTracing.observe(base, true, ResponseContext.NORMAL, now)
        val fast = BayesianKnowledgeTracing.observe(base, true, ResponseContext.FAST, now)
        assertTrue("a fast answer must raise mastery less than a normal one",
            fast.mastery < normal.mastery)
    }

    // --- Test E -------------------------------------------------------------------------

    @Test
    fun `knowing one sense does not mark another sense of the same word as known`() {
        val wordId = 42L
        val senseA = StudyUnit(
            wordId = wordId, word = "issue", senseId = 1L, senseLabel = "问题",
            mode = PracticeMode.RECALL, difficulty = 0.0, importance = 1.0,
            belief = KnowledgeBelief(0.95, 1.0, true, 3, 3, now, MasterySource.TEST_EVIDENCE),
            memory = MemoryState(5.0, 10.0, now, 3, 0),
            predictedMastery = 0.95, selfReportedKnown = false, dueAtMillis = 0L
        )
        val senseB = senseA.copy(
            senseId = 2L, senseLabel = "发行",
            belief = KnowledgeBelief.unseen(now), predictedMastery = 0.30,
            memory = MemoryState.unseen
        )
        assertTrue(senseA.belief.verifiedByTest)
        assertFalse(senseB.belief.verifiedByTest)
        assertTrue("the two senses must carry independent beliefs",
            senseA.belief.mastery - senseB.belief.mastery > 0.5)

        // Sense B has never been judged, so the engine asks for a screening tap before teaching.
        val plan = LearningDecisionEngine.decide(
            DecisionInput(listOf(senseA, senseB), LearningStrategy.BALANCED, now, limit = 20)
        )
        assertEquals("only the unlearned sense should be scheduled", 1, plan.size)
        assertEquals("the scheduled unit must be the unknown sense", 2L, plan.first().unit.senseId)

        // Once sense B has been judged unknown, it becomes a teaching candidate while A stays put.
        val judgedB = senseB.copy(
            belief = KnowledgeBelief(0.12, 0.4, false, 0, 1, now, MasterySource.TEST_EVIDENCE),
            predictedMastery = 0.12
        )
        val teachingPlan = LearningDecisionEngine.decide(
            DecisionInput(listOf(senseA, judgedB), LearningStrategy.BALANCED, now, limit = 20)
        )
        assertEquals(1, teachingPlan.size)
        assertEquals(2L, teachingPlan.first().unit.senseId)
        assertEquals(StudyAction.LEARN, teachingPlan.first().action)
    }

    // --- Test G -------------------------------------------------------------------------

    @Test
    fun `the scheduler derives the next interval from the review outcome`() {
        // A word that has been reviewed a few times: the interesting behaviour starts here, because
        // the first review of a word only creates its initial stability.
        val reviewed = MemoryState(difficulty = 5.0, stability = 10.0, lastReview = now, reps = 3, lapses = 0)
        val interval = FsrsScheduler.nextIntervalDays(reviewed, 0.9)!!
        assertTrue("a stability of 10 days must schedule well beyond a day, got $interval", interval > 5.0)

        // Wait until half a day past the scheduled interval: the target retention has just passed,
        // which is the point where a successful review earns more stability.
        val justOverdue = now + (interval * 86_400_000.0 * 1.05).toLong()
        val onTime = FsrsScheduler.review(reviewed, ReviewGrade.GOOD, justOverdue)
        val grown = FsrsScheduler.nextIntervalDays(onTime, 0.9)!!
        assertTrue("interval must grow after a successful review ($interval -> $grown)", grown > interval)

        // Failing it must shorten the interval, not follow a fixed table.
        val failed = FsrsScheduler.review(onTime, ReviewGrade.AGAIN, justOverdue + 86_400_000L)
        val shrunk = FsrsScheduler.nextIntervalDays(failed, 0.9)!!
        assertTrue("interval must shrink after a lapse ($grown -> $shrunk)", shrunk < grown)
        assertEquals("a lapse is counted", 1, failed.lapses)

        // Two reviews at the same moment differ only by grade: EASY must schedule further than HARD.
        val easy = FsrsScheduler.review(reviewed, ReviewGrade.EASY, justOverdue)
        val hard = FsrsScheduler.review(reviewed, ReviewGrade.HARD, justOverdue)
        assertTrue("EASY must schedule further out than HARD",
            FsrsScheduler.nextIntervalDays(easy, 0.9)!! > FsrsScheduler.nextIntervalDays(hard, 0.9)!!)
    }

    @Test
    fun `retrievability stays a probability`() {
        // Far past the stability the probability must decay towards zero, never leave [0,1].
        assertTrue(FsrsScheduler.retrievabilityAt(10.0, 0.0) in 0.0..1.0)
        assertTrue(FsrsScheduler.retrievabilityAt(10.0, 1.0) in 0.0..1.0)
        assertTrue(FsrsScheduler.retrievabilityAt(10.0, 1000.0) in 0.0..1.0)
        assertEquals("at zero elapsed time recall is certain", 1.0, FsrsScheduler.retrievabilityAt(10.0, 0.0), 1e-9)
        // The defining property of FSRS stability: at t = S the target retention of 0.9 is reached.
        assertEquals("R(S) must equal the 0.9 anchor", 0.9, FsrsScheduler.retrievabilityAt(10.0, 10.0), 1e-6)
        val longGap = FsrsScheduler.retrievabilityAt(10.0, 1000.0)
        assertTrue("a long gap must decay substantially, got $longGap", longGap < 0.7)
        assertTrue("retrievability must decrease over time",
            FsrsScheduler.retrievabilityAt(10.0, 1.0) > FsrsScheduler.retrievabilityAt(10.0, 20.0))
    }

    @Test
    fun `the interval follows elapsed time rather than a fixed table`() {
        val reviewed = MemoryState(difficulty = 5.0, stability = 10.0, lastReview = now, reps = 3, lapses = 0)
        val interval = FsrsScheduler.nextIntervalDays(reviewed, 0.9)!!
        // Reviewing again immediately adds nothing: nothing had been forgotten.
        val immediate = FsrsScheduler.review(reviewed, ReviewGrade.GOOD, now)
        // Just past the due date the model sees real forgetting, so the same grade earns stability.
        val nearDue = FsrsScheduler.review(
            reviewed, ReviewGrade.GOOD, now + (interval * 86_400_000.0 * 1.05).toLong()
        )
        val overdue = FsrsScheduler.review(
            reviewed, ReviewGrade.GOOD, now + (interval * 86_400_000.0 * 1.8).toLong()
        )
        assertTrue(
            "a review past the due date must add more stability than an immediate repeat " +
                "(${immediate.stability} vs ${nearDue.stability})",
            nearDue.stability > immediate.stability + 0.05
        )
        assertTrue(
            "a very overdue review must add more stability than a just-due one " +
                "(${nearDue.stability} vs ${overdue.stability})",
            overdue.stability > nearDue.stability
        )
    }

    @Test
    fun `an unscheduled word has no invented memory state`() {
        assertFalse(FsrsScheduler.isReady(MemoryState.unseen))
        assertNull("never-reviewed words must not get a review date",
            FsrsScheduler.nextIntervalDays(MemoryState.unseen, 0.9))
        assertNull("never-reviewed words have no retrievability",
            MemoryState.unseen.retrievability(now))
    }

    @Test
    fun `higher target retention yields a shorter interval`() {
        val reviewed = MemoryState(difficulty = 5.0, stability = 10.0, lastReview = now, reps = 3, lapses = 0)
        val efficient = FsrsScheduler.nextIntervalDays(reviewed, 0.85)!!
        val coverage = FsrsScheduler.nextIntervalDays(reviewed, 0.94)!!
        assertTrue("retention 0.94 must schedule sooner than 0.85 ($coverage vs $efficient)",
            coverage < efficient)
    }

    @Test
    fun `the first review of a word produces a real schedule`() {
        val first = FsrsScheduler.review(MemoryState.unseen, ReviewGrade.GOOD, now)
        assertTrue("a first review must create a real memory state", FsrsScheduler.isReady(first))
        val interval = FsrsScheduler.nextIntervalDays(first, 0.9)!!
        // FSRS initialises stability from the grade (w0..w3). For GOOD that is w2 = 3.71 days, which
        // turns into a few days at the default 0.9 retention — not a fixed table, and not zero.
        assertTrue("first interval must be a few days, got $interval", interval in 1.0..30.0)
        // The four initial grades must be ordered: AGAIN < HARD < GOOD < EASY.
        val byGrade = ReviewGrade.values().map {
            FsrsScheduler.nextIntervalDays(FsrsScheduler.review(MemoryState.unseen, it, now), 0.9)!!
        }
        assertEquals("initial intervals must be ordered by grade, got $byGrade",
            byGrade.sorted(), byGrade)
    }

    @Test
    fun `the scheduler is never presented as personalised`() {
        val readiness = SchedulerReadiness.of(reviewsRecorded = 50)
        assertFalse(readiness.personalised)
        assertTrue("the note must state the parameters are defaults",
            readiness.note.contains("默认参数"))
    }

    // --- Difficulty prior ---------------------------------------------------------------

    @Test
    fun `difficulty priors are ordered by word book and never claim calibration`() {
        val cet4 = DifficultyPrior.forWord("able", "cet4")
        val cet6 = DifficultyPrior.forWord("abolish", "cet6")
        val outside = DifficultyPrior.forWord("ubiquitous", "")
        assertTrue(cet4.value < cet6.value)
        assertTrue(cet6.value < outside.value)
        listOf(cet4, cet6, outside).forEach {
            assertFalse("priors must not claim to be calibrated IRT parameters", it.calibrated)
        }
    }

    @Test
    fun `active recall is harder than recognition for the same word`() {
        val base = DifficultyPrior.forWord("able", "cet4")
        val recognition = DifficultyPrior.adjustForMode(base, PracticeMode.RECOGNITION)
        val recall = DifficultyPrior.adjustForMode(base, PracticeMode.RECALL)
        val spelling = DifficultyPrior.adjustForMode(base, PracticeMode.SPELLING)
        assertTrue(recognition.value < recall.value)
        assertTrue(recall.value < spelling.value)
    }

    // --- CAT ----------------------------------------------------------------------------

    @Test
    fun `CAT targets items near the current ability and stops when precise`() {
        // A realistic pool: 24 items spread across the logit range.
        val candidates = (-6..18 step 1).map {
            DiagnosticItem(wordId = it.toLong(), word = "w$it", difficulty = it / 3.0)
        }
        var state = CatState()
        assertEquals("the first item should sit at the prior mean",
            0.0, CatSelector.selectNext(state, candidates)!!.difficulty, 0.5)

        // Answer everything correctly: the estimate rises and the test stops on precision.
        var guard = 0
        while (!state.isFinished && guard++ < CatSelector.DEFAULT_MAX_ITEMS + 5) {
            val item = CatSelector.selectNext(state, candidates) ?: break
            state = CatSelector.answer(state, item, correct = true, elapsedMillis = 5_000, candidates = candidates)
        }
        assertNotNull("the test must reach a stop reason", state.stopReason)
        assertEquals("precision must be the reason it stopped", CatStopReason.PRECISION_REACHED, state.stopReason)
        // Answering a mix of correct and incorrect items settles above the prior mean.
        assertTrue("the estimate should have risen above the prior, was ${state.ability.theta}",
            state.ability.theta > 0.2)
        assertTrue("a diagnostic run must stay short, took ${state.answeredCount}",
            state.answeredCount <= CatSelector.DEFAULT_MAX_ITEMS)
        assertTrue("no item is asked twice",
            state.answered.map { it.item.wordId }.toSet().size == state.answered.size)
    }

    @Test
    fun `CAT reports running out of words instead of looping forever`() {
        val small = (1..4).map { DiagnosticItem(it.toLong(), "w$it", it.toDouble()) }
        var state = CatState()
        var guard = 0
        while (!state.isFinished && guard++ < 10) {
            val item = CatSelector.selectNext(state, small) ?: break
            state = CatSelector.answer(state, item, correct = true, elapsedMillis = 2_000, candidates = small)
        }
        assertEquals(4, state.answeredCount)
        assertEquals(CatStopReason.EXHAUSTED, state.stopReason)
    }

    @Test
    fun `CAT never repeats an item and can be stopped by the user`() {
        val candidates = (1..10).map {
            DiagnosticItem(wordId = it.toLong(), word = "w$it", difficulty = it / 3.0)
        }
        var state = CatState()
        repeat(3) {
            val item = CatSelector.selectNext(state, candidates)!!
            state = CatSelector.answer(state, item, correct = it % 2 == 0, elapsedMillis = 3_000)
        }
        assertEquals(3, state.answeredCount)
        val stopped = CatSelector.stop(state)
        assertTrue(stopped.isFinished)
        assertEquals(CatStopReason.USER_STOPPED, stopped.stopReason)
        // Stopping early must preserve the answers collected so far.
        assertEquals(3, stopped.answered.size)
    }

    @Test
    fun `CAT does not waste questions on items already well known`() {
        val known = DiagnosticItem(1L, "knownalready", 0.0, existingEvidence = 0.9)
        val fresh = DiagnosticItem(2L, "fresh", 0.0, existingEvidence = 0.0)
        val state = CatState()
        assertEquals(fresh.wordId, CatSelector.selectNext(state, listOf(known, fresh))!!.wordId)
    }

    // --- Predicted vs verified -----------------------------------------------------------

    @Test
    fun `predicted mastery is discounted while ability is still uncertain`() {
        val weak = AbilityEstimator.initial
        val predicted = PredictedConfidence.predictedMastery(weak, difficulty = -2.0)
        // With no answers there is no ability signal at all, so the prediction is a coin flip rather
        // than a claim. It must never be the mastery prior of 0.30, which would cap every prediction
        // below the "known" threshold and make skipping impossible.
        assertEquals("with no answers the prediction must carry no information",
            0.5, predicted, 1e-9)

        var strong = AbilityEstimator.initial
        repeat(10) { strong = AbilityEstimator.update(strong, ResponseObservation(-1.0, correct = true)) }
        val predictedStrong = PredictedConfidence.predictedMastery(strong, difficulty = -2.0)
        assertTrue("with evidence the prediction must move", predictedStrong > predicted)
        assertTrue("prediction stays a probability", predictedStrong in 0.0..1.0)
        // Uncertainty is expressed in logit space: a weaker estimate gives a milder prediction.
        val weakWithFewAnswers = AbilityEstimator.update(
            AbilityEstimator.initial, ResponseObservation(-1.0, correct = true)
        )
        assertTrue("a thinner estimate must predict less strongly",
            PredictedConfidence.predictedMastery(weakWithFewAnswers, -2.0) < predictedStrong)
    }

    @Test
    fun `predicted and verified are different quantities`() {
        val predicted = BayesianKnowledgeTracing.PRIOR_MASTERY
        val verified = KnowledgeBelief.unseen(now)
        assertFalse("a prior is not a verification", verified.verifiedByTest)
        assertNotEquals(MasterySource.TEST_EVIDENCE, verified.masterySource)
        assertTrue(abs(predicted - verified.mastery) < 1e-9)
    }
}
