package com.nitinshukla.notebook.ui.navigation

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val TEMPLATES = "templates"
    const val ONBOARDING = "onboarding"
    const val EDITOR = "note/{noteId}?type={type}&template={template}"
    const val REVISIONS = "note/{noteId}/revisions"
    const val DRAWING = "note/{noteId}/drawing"
    const val CODE_WORKSPACE = "workspace/{workspace}"
    const val PCAP = "pcap"

    const val WORKSPACE_PYTHON = "python"
    const val WORKSPACE_CODE = "code"

    fun workspace(name: String) = "workspace/$name"

    fun editor(noteId: Long, type: String? = null, templateId: Long? = null): String =
        "note/$noteId?type=${type.orEmpty()}&template=${templateId ?: 0}"

    fun revisions(noteId: Long) = "note/$noteId/revisions"
    fun drawing(noteId: Long) = "note/$noteId/drawing"
}
