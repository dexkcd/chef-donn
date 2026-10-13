package ph.chefdonn.receipts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.SyncTag
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.designsystem.formatPesos
import ph.chefdonn.domain.AppState
import ph.chefdonn.domain.Receipt
import ph.chefdonn.receipts.receiptMonth
import java.time.YearMonth
import java.util.Locale
import java.time.format.TextStyle as DateTextStyle

/** Month view: total, per-category totals (tap to filter), and the receipts themselves. */
@Composable
internal fun ReceiptBook(
    book: AppState,
    queuedIds: Set<String>,
    pendingPhotos: Set<String>,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val thisMonth = YearMonth.now()
    var monthKey by rememberSaveable { mutableStateOf(thisMonth.toString()) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthKey)
    val summary = remember(book, monthKey, filter) { book.receiptMonth(month, filter) }

    fun goTo(m: YearMonth) {
        monthKey = m.toString()
        filter = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        Column(
            Modifier.fillMaxSize().padding(DonnDimens.space5),
            verticalArrangement = Arrangement.spacedBy(DonnDimens.space4),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3),
            ) {
                DonnButton("‹", size = ButtonSize.MD, variant = ButtonVariant.SECONDARY, onClick = { goTo(month.minusMonths(1)) })
                DonnText(
                    monthLabel(month),
                    style = DonnType.display.copy(fontSize = 24.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(220.dp),
                )
                DonnButton(
                    "›",
                    size = ButtonSize.MD,
                    variant = ButtonVariant.SECONDARY,
                    enabled = month < thisMonth,
                    onClick = { goTo(month.plusMonths(1)) },
                )
                if (wide) {
                    Spacer(Modifier.weight(1f))
                    DonnButton("New Receipt", onClick = onNew)
                }
            }
            if (!wide) DonnButton("New Receipt", onClick = onNew, modifier = Modifier.fillMaxWidth())

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space3)) {
                DonnText("MONTH TOTAL", style = DonnType.labelLg, color = donnColors.textSecondary)
                DonnText(formatPesos(summary.totalCents), style = DonnType.monoLg.copy(fontSize = 30.sp))
            }

            if (summary.byCategory.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2),
                ) {
                    CategoryChip(
                        title = "All",
                        detail = "${summary.byCategory.sumOf { it.count }} receipts",
                        selected = filter == null,
                        onClick = { filter = null },
                    )
                    summary.byCategory.forEach { total ->
                        val id = total.category.id
                        CategoryChip(
                            title = total.category.name,
                            detail = "${formatPesos(total.totalCents)} · ${total.count}",
                            selected = filter == id,
                            onClick = { filter = if (filter == id) null else id },
                        )
                    }
                }
            }

            if (summary.receipts.isEmpty()) {
                DonnText(
                    "No receipts dated ${monthLabel(month).lowercase().replaceFirstChar { it.uppercase() }}. " +
                        "Tap New Receipt to photograph one.",
                    style = DonnType.body,
                    color = donnColors.textSecondary,
                )
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
                items(summary.receipts, key = { it.spec.receiptId }) { receipt ->
                    ReceiptRow(
                        receipt = receipt,
                        categoryName = book.expenseCategory(receipt.spec.categoryId)?.name ?: "—",
                        queued = receipt.spec.receiptId in queuedIds,
                        photoPending = receipt.spec.photoIds.any { it in pendingPhotos },
                        onClick = { onOpen(receipt.spec.receiptId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(
    receipt: Receipt,
    categoryName: String,
    queued: Boolean,
    photoPending: Boolean,
    onClick: () -> Unit,
) {
    val c = donnColors
    val pages = receipt.spec.photoIds.size
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DonnDimens.tapTargetComfortable)
            .background(c.surface, RoundedCornerShape(DonnDimens.radiusMd))
            .border(DonnDimens.borderThick, c.borderStrong, RoundedCornerShape(DonnDimens.radiusMd))
            .clickable(onClick = onClick)
            .padding(horizontal = DonnDimens.space4, vertical = DonnDimens.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DonnDimens.space4),
    ) {
        // MM-DD of the ISO date; the month is already in the header.
        DonnText(receipt.spec.purchasedOn.drop(5), style = DonnType.monoBold, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            DonnText(receipt.spec.vendor, style = DonnType.bodySemibold, maxLines = 1)
            DonnText(
                categoryName + " · " + when (pages) {
                    0 -> "no photo"
                    1 -> "1 photo"
                    else -> "$pages photos"
                },
                style = DonnType.caption,
                color = c.textSecondary,
                maxLines = 1,
            )
        }
        if (queued) SyncTag("Queued")
        if (photoPending) SyncTag("Photo pending")
        DonnText(formatPesos(receipt.spec.totalCents), style = DonnType.monoBold)
    }
}

/**
 * Category selector chip. Selection is shown by fill AND a check glyph, never by
 * color alone. [tag] marks the OCR/vendor-history suggestion.
 */
@Composable
internal fun CategoryChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tag: String? = null,
) {
    val c = donnColors
    val fg = if (selected) c.textInverse else c.textPrimary
    Column(
        modifier
            .defaultMinSize(minHeight = DonnDimens.tapTargetMin)
            .background(if (selected) c.accentGreen else c.surface, RoundedCornerShape(DonnDimens.radiusMd))
            .border(DonnDimens.borderThick, if (selected) c.accentGreen else c.borderStrong, RoundedCornerShape(DonnDimens.radiusMd))
            .clickable(onClick = onClick)
            .padding(horizontal = DonnDimens.space4, vertical = DonnDimens.space2),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DonnDimens.space2)) {
            if (selected) DonnText("✓", style = DonnType.bodyBold, color = fg)
            DonnText(title, style = DonnType.bodySemibold, color = fg)
            if (tag != null) DonnText("★ " + tag.uppercase(), style = DonnType.label, color = if (selected) fg else c.textSecondary)
        }
        if (detail != null) DonnText(detail, style = DonnType.mono.copy(fontSize = 13.sp), color = fg)
    }
}

internal fun monthLabel(month: YearMonth): String =
    month.month.getDisplayName(DateTextStyle.FULL, Locale.ENGLISH).uppercase() + " " + month.year
