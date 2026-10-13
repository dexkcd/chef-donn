package ph.chefdonn.receipts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnTextField
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.ExpenseCategory
import java.util.UUID

/**
 * Add, rename, archive. Categories with receipts are archived rather than removed
 * (the hub enforces it), so past months keep their buckets.
 */
@Composable
internal fun CategoriesScreen(
    book: AppState,
    onSave: (List<ExpenseCategory>) -> Unit,
    onBack: () -> Unit,
) {
    val c = donnColors
    val categories = book.expenseCategories
    val counts = remember(book) {
        book.receipts.values.filter { !it.deleted }.groupingBy { it.spec.categoryId }.eachCount()
    }
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    /** Same rule as the hub, checked here so the mistake is shown before it's sent. */
    fun nameProblem(name: String, exceptId: String?): String? = when {
        name.isBlank() -> "Enter a name."
        categories.any { it.id != exceptId && !it.archived && it.name.trim().equals(name.trim(), ignoreCase = true) } ->
            "There's already a category called ${name.trim()}."
        else -> null
    }

    Column(
        Modifier.fillMaxSize().padding(DonnDimens.space5),
        verticalArrangement = Arrangement.spacedBy(DonnDimens.space4),
    ) {
        Row(
            Modifier.widthIn(max = 720.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3),
        ) {
            DonnTextField(newName, { newName = it; error = null }, placeholder = "New category, e.g. Rice", modifier = Modifier.weight(1f))
            DonnButton("Add", size = ButtonSize.MD, onClick = {
                error = nameProblem(newName, null)
                if (error == null) {
                    onSave(categories + ExpenseCategory("cat-" + UUID.randomUUID(), newName.trim()))
                    newName = ""
                }
            })
            DonnButton("Done", size = ButtonSize.MD, variant = ButtonVariant.SECONDARY, onClick = onBack)
        }
        error?.let { DonnText("✕  $it", style = DonnType.bodySemibold, color = c.accentRed) }

        LazyColumn(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
            items(categories, key = { it.id }) { cat ->
                val used = counts[cat.id] ?: 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = DonnDimens.tapTargetComfortable)
                        .background(if (cat.archived) c.surfaceSunken else c.surface, RoundedCornerShape(DonnDimens.radiusMd))
                        .border(DonnDimens.borderThick, c.borderStrong, RoundedCornerShape(DonnDimens.radiusMd))
                        .padding(horizontal = DonnDimens.space4, vertical = DonnDimens.space2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3),
                ) {
                    if (editingId == cat.id) {
                        DonnTextField(editName, { editName = it; error = null }, modifier = Modifier.weight(1f))
                        DonnButton("Save", size = ButtonSize.MD, onClick = {
                            error = nameProblem(editName, cat.id)
                            if (error == null) {
                                onSave(categories.map { if (it.id == cat.id) it.copy(name = editName.trim()) else it })
                                editingId = null
                            }
                        })
                        DonnButton("Cancel", size = ButtonSize.MD, variant = ButtonVariant.GHOST, onClick = { editingId = null })
                    } else {
                        Column(Modifier.weight(1f)) {
                            DonnText(cat.name, style = DonnType.bodySemibold, color = if (cat.archived) c.textSecondary else c.textPrimary)
                            DonnText(
                                (if (cat.archived) "ARCHIVED · " else "") + if (used == 1) "1 receipt" else "$used receipts",
                                style = DonnType.caption,
                                color = c.textSecondary,
                            )
                        }
                        DonnButton("Rename", size = ButtonSize.MD, variant = ButtonVariant.SECONDARY, onClick = {
                            editingId = cat.id
                            editName = cat.name
                        })
                        DonnButton(
                            if (cat.archived) "Restore" else "Archive",
                            size = ButtonSize.MD,
                            variant = ButtonVariant.SECONDARY,
                            onClick = {
                                // Restoring must not create a second active category with the same name.
                                error = if (cat.archived) nameProblem(cat.name, cat.id) else null
                                if (error == null) {
                                    onSave(categories.map { if (it.id == cat.id) it.copy(archived = !it.archived) else it })
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
