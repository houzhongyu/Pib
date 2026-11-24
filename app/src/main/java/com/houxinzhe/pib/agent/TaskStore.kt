package com.houxinzhe.pib.agent

import com.houxinzhe.pib.data.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class StepStatus(val label: String) {
    RUNNING("执行中"), DONE("完成"), FAILED("失败"), DENIED("已拒绝"), UNCERTAIN("结果待核对")
}

@Serializable
data class TaskStep(
    val tool: String,
    val title: String,
    val risk: Risk,
    val arguments: String,
    var status: StepStatus = StepStatus.RUNNING,
    var receipt: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    var endedAt: Long? = null,
)

@Serializable
data class TaskRecord(
    val id: String,
    val request: String,
    val createdAt: Long = System.currentTimeMillis(),
    val steps: MutableList<TaskStep> = mutableListOf(),
    var finished: Boolean = false,
)

/**
 * Receipt ledger. Every step is written to disk *before* the tool runs and again after,
 * so a crash or process kill during a write leaves a visible "待核对" mark instead of a
 * silent guess.
 */
class TaskStore(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val tasks: MutableList<TaskRecord> = load()

    val all: List<TaskRecord>
        @Synchronized get() = tasks.map { t -> t.copy(steps = t.steps.map { it.copy() }.toMutableList()) }

    private fun load(): MutableList<TaskRecord> {
        val f = file ?: return mutableListOf()
        if (!f.exists()) return mutableListOf()
        val list = runCatching { json.decodeFromString<MutableList<TaskRecord>>(f.readText()) }.getOrElse { mutableListOf() }
        var changed = false
        list.forEach { t ->
            t.steps.filter { it.status == StepStatus.RUNNING }.forEach { s ->
                s.status = if (s.risk.writes) StepStatus.UNCERTAIN else StepStatus.FAILED
                s.receipt = if (s.risk.writes) "执行时应用被中断，这一步是否生效需要你核对。" else "执行时中断。"
                changed = true
            }
            if (!t.finished && t.steps.none { it.status == StepStatus.RUNNING }) { t.finished = true; changed = true }
        }
        if (changed) persist(list)
        return list
    }

    @Synchronized
    fun begin(id: String, request: String): TaskRecord =
        TaskRecord(id, request).also { tasks.add(0, it); trim(); persist(tasks) }

    @Synchronized
    fun startStep(task: TaskRecord, step: TaskStep) {
        task.steps.add(step)
        persist(tasks)
    }

    @Synchronized
    fun endStep(task: TaskRecord, step: TaskStep, status: StepStatus, receipt: String) {
        step.status = status
        step.receipt = receipt
        step.endedAt = System.currentTimeMillis()
        persist(tasks)
    }

    @Synchronized
    fun finish(task: TaskRecord) {
        task.finished = true
        persist(tasks)
    }

    @Synchronized
    fun clear() {
        tasks.clear()
        persist(tasks)
    }

    private fun trim() {
        while (tasks.size > 200) tasks.removeAt(tasks.lastIndex)
    }

    private fun persist(list: List<TaskRecord>) {
        val f = file ?: return
        AtomicFile.writeText(f, json.encodeToString(list))
    }
}
