package com.example.domain.model

enum class DiagnosticStepStatus {
    SUCCESS,
    WARNING,
    ERROR,
    SKIPPED
}

enum class FeedFormat(val label: String) {
    RSS_2_0("RSS 2.0"),
    ATOM("Atom"),
    RDF("RSS 1.0 / RDF"),
    UNKNOWN("Неизвестный формат")
}

data class DiagnosticStep(
    val id: String,
    val title: String,
    val status: DiagnosticStepStatus,
    val summary: String,
    val technicalDetails: String? = null
)

data class SourceDiagnosticReport(
    val sourceId: Long,
    val sourceTitle: String,
    val sourceUrl: String,
    val isSourceEnabled: Boolean,
    val checkedAt: Long,
    val overallStatus: DiagnosticStepStatus,
    val failedStageTitle: String? = null,
    val httpCode: Int? = null,
    val finalUrl: String? = null,
    val contentType: String? = null,
    val responseTimeMs: Long? = null,
    val responseSizeBytes: Long? = null,
    val feedFormat: FeedFormat = FeedFormat.UNKNOWN,
    val fetchedItemsCount: Int = 0,
    val processableItemsCount: Int = 0,
    val latestFeedPubDate: Long? = null,
    val latestFeedRawPubDate: String? = null,
    val itemsWithValidDatesCount: Int = 0,
    val itemsWithoutValidDatesCount: Int = 0,
    val storedInDbCount: Int = 0,
    val wouldInsertNewCount: Int = 0,
    val alreadyInDbSameSourceCount: Int = 0,
    val guidCollisionOtherSourceCount: Int = 0,
    val wouldBeTrimmedCount: Int = 0,
    val visibleInFeedCount: Int = 0,
    val steps: List<DiagnosticStep> = emptyList()
)
