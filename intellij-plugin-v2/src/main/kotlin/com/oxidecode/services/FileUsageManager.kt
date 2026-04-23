package com.oxidecode.services

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.time.Instant

@State(
    name = "FileUsageManager",
    storages = [Storage("OxideCodeFileUsage.xml")],
)
@Service(Service.Level.PROJECT)
class FileUsageManager : PersistentStateComponent<FileUsageManager> {
    private var fileUsages: MutableMap<String, FileUsageMetaData> = mutableMapOf()

    data class FileUsageMetaData(
        var count: Int = 0,
        var timestamps: MutableList<Long> = mutableListOf(),
    )

    fun addOrRefreshUsage(fileName: String) {
        val metadata = fileUsages.getOrPut(fileName) { FileUsageMetaData() }
        metadata.count++
        metadata.timestamps.add(Instant.now().toEpochMilli())

        // Keep only last 10 timestamps
        if (metadata.timestamps.size > 10) {
            metadata.timestamps = metadata.timestamps.takeLast(10).toMutableList()
        }
    }

    fun getUsages(): Map<String, FileUsageMetaData> = fileUsages

    override fun getState(): FileUsageManager = this

    override fun loadState(state: FileUsageManager) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): FileUsageManager = project.getService(FileUsageManager::class.java)
    }
}
