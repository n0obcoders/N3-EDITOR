package com.nitinshukla.notebook.core.template

import com.nitinshukla.notebook.core.model.NoteType
import com.nitinshukla.notebook.core.model.Template
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object DefaultTemplates {
    val all: List<Template> = listOf(
        Template(
            name = "Meeting notes",
            title = "Meeting: {{date}}",
            content = "Attendees:\n- \n\nAgenda:\n1. \n\nNotes:\n\n\nDecisions:\n- \n\nAction items:\n- [ ] ",
            isBuiltIn = true,
        ),
        Template(
            name = "Daily journal",
            title = "Journal: {{date}}",
            content = "How I feel today:\n\n\nThree things I'm grateful for:\n1. \n2. \n3. \n\nWhat happened:\n\n\nTomorrow I want to:\n",
            isBuiltIn = true,
        ),
        Template(
            name = "To-do list",
            title = "To do",
            content = "[ ] \n[ ] \n[ ] ",
            type = NoteType.CHECKLIST,
            isBuiltIn = true,
        ),
        Template(
            name = "Study notes",
            title = "Study: ",
            content = "Topic:\nSource:\n\nKey ideas:\n- \n\nQuestions:\n- \n\nSummary:\n",
            isBuiltIn = true,
        ),
        Template(
            name = "Project planning",
            title = "Project: ",
            content = "Goal:\n\nScope:\n- In: \n- Out: \n\nMilestones:\n1. \n\nRisks:\n- \n\nNext steps:\n- [ ] ",
            isBuiltIn = true,
        ),
        Template(
            name = "Shopping list",
            title = "Shopping",
            content = "[ ] Milk\n[ ] Bread\n[ ] Eggs\n[ ] Fruit",
            type = NoteType.CHECKLIST,
            isBuiltIn = true,
        ),
        Template(
            name = "Quick ideas",
            title = "Ideas",
            content = "- \n- \n- ",
            isBuiltIn = true,
        ),
        Template(
            name = "Habit tracking",
            title = "Habits: week of {{date}}",
            content = "[ ] Exercise\n[ ] Read 20 minutes\n[ ] Drink water\n[ ] Sleep by 11pm\n[ ] No phone at dinner",
            type = NoteType.CHECKLIST,
            isBuiltIn = true,
        ),
    )

    /** Replaces {{date}} placeholders with today's date. */
    fun expand(text: String, date: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String =
        text.replace("{{date}}", date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale)))
}
