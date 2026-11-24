package com.houxinzhe.pib.agent

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

    val all: List<TaskRecord> get() = tasks.toList()

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

    fun begin(id: String, request: String): TaskRecord = TaskRecord(id, request).also { tasks.add(0, it); trim(); persist(tasks) }

    fun startStep(task: TaskRecord, step: TaskStep) { task.steps.add(step); persist(tasks) }

    fun endStep(task: TaskRecord, step: TaskStep, status: StepStatus, receipt: String) {
        step.status = status; step.receipt = receipt; step.endedAt = System.currentTimeMillis()
        persist(tasks)
    }

    fun finish(task: TaskRecord) { task.finished = true; persist(tasks) }

    fun clear() { tasks.clear(); persist(tasks) }

    private fun trim() { while (tasks.size > 200) tasks.removeAt(tasks.lastIndex) }

    private fun persist(list: List<TaskRecord>) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(list))
        tmp.renameTo(f)
    }
}
