package com.tea.teawords.data

import android.util.Log
import kotlinx.coroutines.*

class TimerManager(scope: CoroutineScope) {
    private var timerJob: Job? = null
    private val scope = scope
    private var timeRemainingMs = 0L
    private var totalTimeMs = 0L
    private var isRunning = false
    
    var onTick: ((remainingSeconds: Int) -> Unit)? = null
    var onTimeout: (() -> Unit)? = null

    /**
     * 开始计时，时间单位为秒
     */
    fun startTimer(durationSeconds: Int) {
        timeRemainingMs = (durationSeconds * 1000).toLong()
        totalTimeMs = timeRemainingMs
        isRunning = true
        
        timerJob?.cancel()
        timerJob = scope.launch {
            try {
                while (timeRemainingMs > 0 && isRunning) {
                    delay(100) // 每 100ms 检查一次
                    timeRemainingMs -= 100
                    onTick?.invoke((timeRemainingMs / 1000).toInt())
                }
                
                if (isRunning) {
                    isRunning = false
                    onTimeout?.invoke()
                }
            } catch (e: CancellationException) {
                Log.d("TimerManager", "Timer cancelled")
            }
        }
    }

    /**
     * 计算推荐的时间（每题 10 秒 * 难度系数）
     */
    fun calculateRecommendedTime(problemCount: Int, difficulty: Int = 2): Int {
        val baseTimePerProblem = 10
        val difficultyMultiplier = difficulty * 1.0f / 2 // 难度 2 为基准 (1.0)
        return (problemCount * baseTimePerProblem * difficultyMultiplier).toInt()
    }

    /**
     * 暂停计时
     */
    fun pauseTimer() {
        isRunning = false
    }

    /**
     * 恢复计时
     */
    fun resumeTimer() {
        if (!isRunning && timeRemainingMs > 0) {
            isRunning = true
            startTimer((timeRemainingMs / 1000).toInt())
        }
    }

    /**
     * 停止计时
     */
    fun stopTimer() {
        isRunning = false
        timerJob?.cancel()
        timeRemainingMs = 0
    }

    /**
     * 获取剩余时间（秒）
     */
    fun getTimeRemaining(): Int = (timeRemainingMs / 1000).toInt()

    /**
     * 获取进度百分比 (0-100)
     */
    fun getProgress(): Float {
        return if (totalTimeMs > 0) {
            ((totalTimeMs - timeRemainingMs).toFloat() / totalTimeMs) * 100
        } else {
            0f
        }
    }

    /**
     * 检查是否超时
     */
    fun isTimeUp(): Boolean = timeRemainingMs <= 0

    /**
     * 检查是否还有时间
     */
    fun hasTimeRemaining(): Boolean = timeRemainingMs > 0
}
