package ph.chefdonn.receipts.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.chefdonn.client.PhotoSync
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnTextField
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.EditReceipt
import ph.chefdonn.domain.Receipt
import ph.chefdonn.domain.ReceiptSpec
import ph.chefdonn.domain.RecordReceipt
import ph.chefdonn.receipts.CategorySuggester
import ph.chefdonn.receipts.ReceiptTextParser
import ph.chefdonn.receipts.centsToInput
import ph.chefdonn.receipts.parsePesosToCents
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * New or edit. Photograph the receipt (one or more pages), let OCR fill vendor /
 * date / total, confirm the category, save. Every OCR value is only a pre-fill:
 * the person holding the paper confirms it.
 *
 * Ids (receipt, command, photos) are created once per form and saved with it, so a
 * retried save can never file the same receipt twice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptForm(
    formKey: String,
    receiptId: String,
    existing: Receipt?,
    book: AppState,
    photos: PhotoSync,
    deviceId: String,
    deviceName: String,
    submit: (Command) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spec = existing?.spec

    val commandId by rememberSaveable(formKey) { mutableStateOf(UUID.randomUUID().toString()) }
    var photoIdsText by rememberSaveable(formKey) { mutableStateOf(spec?.photoIds.orEmpty().joinToString(",")) }
    var vendor by rememberSaveable(formKey) { mutableStateOf(spec?.vendor.orEmpty()) }
    var date by rememberSaveable(formKey) { mutableStateOf(spec?.purchasedOn ?: LocalDate.now().toString()) }
    var dateTouched by rememberSaveable(formKey) { mutableStateOf(existing != null) }
    var total by rememberSaveable(formKey) { mutableStateOf(spec?.totalCents?.let(::centsToInput).orEmpty()) }
    var categoryId by rememberSaveable(formKey) { mutableStateOf(spec?.categoryId) }
    var suggestedId by rememberSaveable(formKey) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(formKey) { mutableStateOf(spec?.note.orEmpty()) }
    var ocrStatus by rememberSaveable(formKey) { mutableStateOf<String?>(null) }
    var captureUri by rememberSaveable(formKey) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val photoIds = splitIds(photoIdsText)
    val savedPhotoIds = spec?.photoIds.orEmpty().toSet()

    // Suggestion for a typed vendor (OCR picks one on its own when it reads a photo).
    val vendorHint = remember(vendor, book) { CategorySuggester.suggest(vendor, emptyList(), book) }
    val hintedId = suggestedId ?: vendorHint

    fun applyOcr(rows: List<String>) {
        val guess = ReceiptTextParser.parse(rows, LocalDate.now())
        if (vendor.isBlank()) guess.vendor?.let { vendor = it }
        if (total.isBlank()) guess.totalCents?.let { total = centsToInput(it) }
        if (!dateTouched) guess.purchasedOn?.let { date = it.toString() }
        if (categoryId == null) {
            CategorySuggester.suggest(vendor, rows, book)?.let {
                categoryId = it
                suggestedId = it
            }
        }
        val read = listOfNotNull(
            guess.vendor?.let { "vendor" },
            guess.purchasedOn?.let { "date" },
            guess.totalCents?.let { "total" },
        )
        ocrStatus = if (read.isEmpty()) {
            "Couldn't read this receipt — fill in the fields below."
        } else {
            "Read ${read.joinToString(", ")} from the photo — check them against the paper."
        }
    }

    fun addPhoto(source: Uri) {
        scope.launch {
            busy = "Reading receipt…"
            error = null
            val photoId = UUID.randomUUID().toString()
            val bitmap = runCatching {
                withContext(Dispatchers.IO) {
                    ReceiptImages.prepare(context, source, ReceiptImages.draftFile(context, photoId))
                }
            }.getOrNull()
            if (bitmap == null) {
                error = "Couldn't open that photo. Try again."
            } else {
                photoIdsText = (splitIds(photoIdsText) + photoId).joinToString(",")
                val rows = runCatching { readReceiptRows(bitmap) }.getOrDefault(emptyList())
                bitmap.recycle()
                applyOcr(rows)
            }
            busy = null
        }
    }

    fun removeDraft(photoId: String) {
        photoIdsText = (splitIds(photoIdsText) - photoId).joinToString(",")
        ReceiptImages.draftFile(context, photoId).delete()
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        captureUri = null
        if (ok && uri != null) addPhoto(Uri.parse(uri))
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addPhoto(uri)
    }

    fun launchCamera() {
        val uri = ReceiptImages.newCaptureUri(context)
        captureUri = uri.toString()
        runCatching { takePicture.launch(uri) }.onFailure {
            captureUri = null
            error = "Couldn't open the camera — choose from the gallery instead."
        }
    }

    fun launchGallery() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun save() {
        if (busy != null) return
        val cents = parsePesosToCents(total)
        val chosen = categoryId
        error = when {
            vendor.isBlank() -> "Enter the vendor."
            cents == null || cents <= 0 -> "Enter the total, e.g. 1250.50."
            !isIsoDate(date) -> "Date must look like 2026-10-12."
            chosen == null -> "Pick a category."
            else -> null
        }
        if (error != null || cents == null || chosen == null) return
        // Claimed before launching: a double tap must not run a second save that finds
        // the drafts already moved and re-queues this commandId without its photos.
        busy = "Saving…"
        scope.launch {
            // Photos go into the durable outbox before the command that names them.
            val kept = try {
                photoIds.filter { id ->
                    if (id in savedPhotoIds) return@filter true
                    val draft = ReceiptImages.draftFile(context, id)
                    val bytes = withContext(Dispatchers.IO) { draft.takeIf { it.isFile }?.readBytes() } ?: return@filter false
                    photos.enqueue(id, bytes)
                    withContext(Dispatchers.IO) { draft.delete() }
                    true
                }
            } catch (e: java.io.IOException) {
                error = "Couldn't store the photo on this device (${e.message}). Nothing was saved."
                busy = null
                return@launch
            }
            val receipt = ReceiptSpec(receiptId, vendor.trim(), cents, date.trim(), chosen, kept, note.trim())
            submit(
                if (existing == null) RecordReceipt(commandId, deviceId, deviceName, receipt)
                else EditReceipt(commandId, deviceId, deviceName, receipt)
            )
            busy = null
            onDone()
        }
    }

    fun discard() {
        photoIds.filter { it !in savedPhotoIds }.forEach { ReceiptImages.draftFile(context, it).delete() }
        onDone()
    }

    val loadPhoto: suspend (String) -> ByteArray? = { id ->
        withContext(Dispatchers.IO) { ReceiptImages.draftFile(context, id).takeIf { it.isFile }?.readBytes() }
            ?: photos.fetch(id)
    }

    val photoPane: @Composable ColumnScope.() -> Unit = {
        if (photoIds.isEmpty()) {
            DonnText("PHOTO", style = DonnType.labelLg, color = donnColors.textSecondary)
            DonnButton("Take Photo", onClick = ::launchCamera, enabled = busy == null, modifier = Modifier.fillMaxWidth())
            DonnButton(
                "Choose from Gallery",
                onClick = ::launchGallery,
                enabled = busy == null,
                variant = ButtonVariant.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            DonnText(
                "No photo? Fill in the fields — a receipt can be filed without one.",
                style = DonnType.caption,
                color = donnColors.textSecondary,
            )
        } else {
            DonnText(
                if (photoIds.size == 1) "PHOTO" else "PHOTOS · ${photoIds.size} PAGES",
                style = DonnType.labelLg,
                color = donnColors.textSecondary,
            )
            photoIds.forEach { id ->
                ReceiptPhoto(id, loadPhoto, Modifier.fillMaxWidth().aspectRatio(3f / 4f), targetEdge = 1000)
                if (id !in savedPhotoIds) {
                    DonnButton(
                        "Remove Page",
                        onClick = { removeDraft(id) },
                        enabled = busy == null,
                        size = ButtonSize.MD,
                        variant = ButtonVariant.SECONDARY,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                DonnButton("Add Page", onClick = ::launchCamera, enabled = busy == null, size = ButtonSize.MD, variant = ButtonVariant.SECONDARY)
                DonnButton("From Gallery", onClick = ::launchGallery, enabled = busy == null, size = ButtonSize.MD, variant = ButtonVariant.SECONDARY)
            }
        }
    }

    val fieldsPane: @Composable ColumnScope.() -> Unit = {
        busy?.let { DonnText(it, style = DonnType.bodySemibold, color = donnColors.accentGreen) }
        ocrStatus?.let { DonnText(it, style = DonnType.body, color = donnColors.textSecondary) }

        FieldLabel("Vendor")
        DonnTextField(vendor, { vendor = it }, placeholder = "e.g. Aling Nena's stall", modifier = Modifier.fillMaxWidth())

        FieldLabel("Date on receipt")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
            DonnTextField(
                date,
                { date = it.trim(); dateTouched = true },
                placeholder = "2026-10-12",
                textStyle = DonnType.mono,
                modifier = Modifier.weight(1f),
            )
            DonnButton("Today", size = ButtonSize.MD, variant = ButtonVariant.SECONDARY, onClick = {
                date = LocalDate.now().toString(); dateTouched = true
            })
            DonnButton("Yesterday", size = ButtonSize.MD, variant = ButtonVariant.SECONDARY, onClick = {
                date = LocalDate.now().minusDays(1).toString(); dateTouched = true
            })
        }

        FieldLabel("Total (₱)")
        DonnTextField(
            total,
            { total = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
            placeholder = "0.00",
            textStyle = DonnType.monoLg,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("Category")
        val choices = book.expenseCategories.filter { !it.archived || it.id == spec?.categoryId }
        if (choices.isEmpty()) {
            DonnText("No categories yet — the hub adds the defaults when it starts.", style = DonnType.body, color = donnColors.textSecondary)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2),
            verticalArrangement = Arrangement.spacedBy(DonnDimens.space2),
        ) {
            choices.forEach { cat ->
                CategoryChip(
                    title = cat.name,
                    selected = categoryId == cat.id,
                    tag = if (cat.id == hintedId) "Suggested" else null,
                    onClick = { categoryId = cat.id },
                )
            }
        }

        FieldLabel("Note (optional)")
        DonnTextField(note, { note = it }, placeholder = "e.g. for Saturday catering", singleLine = false, modifier = Modifier.fillMaxWidth())

        error?.let { DonnText("✕  $it", style = DonnType.bodySemibold, color = donnColors.accentRed) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
            DonnButton(
                if (existing == null) "Discard" else "Cancel",
                onClick = ::discard,
                enabled = busy == null,
                variant = ButtonVariant.SECONDARY,
                modifier = Modifier.weight(1f),
            )
            DonnButton(
                if (existing == null) "Save Receipt" else "Save Changes",
                onClick = ::save,
                enabled = busy == null,
                modifier = Modifier.weight(2f),
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize().padding(DonnDimens.space5), horizontalArrangement = Arrangement.spacedBy(DonnDimens.space6)) {
                Column(
                    Modifier.weight(0.42f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DonnDimens.space3),
                    content = photoPane,
                )
                Column(
                    Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(DonnDimens.space3),
                    content = fieldsPane,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DonnDimens.space5),
                verticalArrangement = Arrangement.spacedBy(DonnDimens.space3),
            ) {
                photoPane()
                fieldsPane()
            }
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    DonnText(text.uppercase(), style = DonnType.labelLg, color = donnColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
}

private fun splitIds(text: String): List<String> = text.split(',').filter { it.isNotBlank() }

private fun isIsoDate(s: String): Boolean = try {
    LocalDate.parse(s.trim())
    true
} catch (_: DateTimeParseException) {
    false
}
