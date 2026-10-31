package xyz.mederi.ui

/** 设备内阅读上下文。未发消息的新对话按项目分开，不以null与真实会话混存。 */
internal sealed interface ReaderOwner {
    data class Session(val id: String) : ReaderOwner
    data class Draft(val projectId: String?) : ReaderOwner
}

/** 仅保存用户打开意图；Core正文仍由VM实时投影，不写回这份本地状态。 */
internal data class ReaderPresentationState(
    val items: List<ArtifactItem> = emptyList(),
    val activeId: String? = null,
    val pendingFileOpen: PendingFileOpen? = null,
) {
    val activeItem: ArtifactItem? get() = items.find { it.id == activeId } ?: items.lastOrNull()

    fun open(item: ArtifactItem): ReaderPresentationState {
        val index = items.indexOfFirst { it.id == item.id }
        val next = items.toMutableList().apply { if (index < 0) add(item) else set(index, item) }
        return copy(items = next.toList(), activeId = item.id, pendingFileOpen = null)
    }

    fun select(id: String): ReaderPresentationState =
        if (items.any { it.id == id }) copy(activeId = id, pendingFileOpen = null) else this
}

/** 阅读器纯View契约：activeItem已应用当前会话的实时文档投影。 */
internal data class ReaderPanelState(
    val items: List<ArtifactItem>,
    val activeItem: ArtifactItem?,
    val reportState: ReportLoadState = ReportLoadState.Idle,
)
