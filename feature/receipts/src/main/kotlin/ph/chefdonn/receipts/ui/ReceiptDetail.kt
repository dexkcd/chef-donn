package ph.chefdonn.receipts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.chefdonn.client.PhotoSync
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDialog
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnTextField
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.SyncTag
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.designsystem.formatPesos
import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.Receipt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RECORDED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun ReceiptDetail(
    receipt: Receipt,
    book: AppState,
    queued: Boolean,
    pendingPhotos: Set<String>,
    photos: PhotoSync,
    onEdit: () -> Unit,
    onDelete: (reason: String) -> Unit,
    onBack: () -> Unit,
) {
    val spec = receipt.spec
    val c = donnColors
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteReason by remember { mutableStateOf("") }
    val category = book.expenseCategory(spec.categoryId)

    val photoPane: @Composable ColumnScope.() -> Unit = {
        if (spec.photoIds.isEmpty()) {
            DonnText("Filed without a photo.", style = DonnType.body, color = c.textSecondary)
        }
        spec.photoIds.forEach { id ->
            ReceiptPhoto(id, photos::fetch, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            if (id in pendingPhotos) SyncTag("Photo pending")
        }
    }

    val infoPane: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
            DonnText(spec.vendor, style = DonnType.displayLg.copy(fontSize = 30.sp), modifier = Modifier.weight(1f, fill = false))
            if (queued) SyncTag("Queued")
        }
        DonnText(formatPesos(spec.totalCents), style = DonnType.monoLg.copy(fontSize = 36.sp))

        FieldLabel("Date on receipt")
        DonnText(spec.purchasedOn, style = DonnType.monoBold)
        FieldLabel("Category")
        DonnText(
            (category?.name ?: "—") + if (category?.archived == true) " (archived)" else "",
            style = DonnType.bodyMd,
        )
        if (spec.note.isNotBlank()) {
            FieldLabel("Note")
            DonnText(spec.note, style = DonnType.body)
        }
        FieldLabel("Recorded")
        DonnText(
            "${receipt.recordedBy} · " + RECORDED_AT.format(Instant.ofEpochMilli(receipt.recordedAt).atZone(ZoneId.systemDefault())),
            style = DonnType.mono.copy(fontSize = 14.sp),
            color = c.textSecondary,
        )

        Row(Modifier.fillMaxWidth().padding(top = DonnDimens.space4), horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
            DonnButton("Back", onClick = onBack, variant = ButtonVariant.SECONDARY, size = ButtonSize.MD, modifier = Modifier.weight(1f))
            DonnButton("Edit", onClick = onEdit, size = ButtonSize.MD, modifier = Modifier.weight(1f))
            DonnButton("Delete", onClick = { confirmDelete = true }, variant = ButtonVariant.DANGER, size = ButtonSize.MD, modifier = Modifier.weight(1f))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize().padding(DonnDimens.space5), horizontalArrangement = Arrangement.spacedBy(DonnDimens.space6)) {
                Column(
                    Modifier.weight(0.5f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DonnDimens.space3),
                    content = photoPane,
                )
                Column(
                    Modifier.weight(0.5f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DonnDimens.space2),
                    content = infoPane,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DonnDimens.space5),
                verticalArrangement = Arrangement.spacedBy(DonnDimens.space2),
            ) {
                infoPane()
                photoPane()
            }
        }
    }

    if (confirmDelete) {
        DonnDialog(
            title = "Delete this receipt?",
            body = "${spec.vendor} · ${formatPesos(spec.totalCents)} leaves the receipt book and this month's totals on every Office device.",
            confirmText = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete(deleteReason.trim().ifEmpty { "No reason given" })
            },
            onDismiss = { confirmDelete = false },
            content = {
                DonnTextField(deleteReason, { deleteReason = it }, placeholder = "Reason (e.g. duplicate)", modifier = Modifier.fillMaxWidth())
            },
        )
    }
}
