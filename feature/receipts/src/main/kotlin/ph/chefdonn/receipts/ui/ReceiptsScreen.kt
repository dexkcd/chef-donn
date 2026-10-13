package ph.chefdonn.receipts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.client.PhotoSync
import ph.chefdonn.designsystem.ButtonSize
import ph.chefdonn.designsystem.ButtonVariant
import ph.chefdonn.designsystem.DonnButton
import ph.chefdonn.designsystem.DonnDimens
import ph.chefdonn.designsystem.DonnSurface
import ph.chefdonn.designsystem.DonnText
import ph.chefdonn.designsystem.DonnType
import ph.chefdonn.designsystem.StaffHeader
import ph.chefdonn.designsystem.donnColors
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.DeleteReceipt
import ph.chefdonn.domain.UpdateExpenseCategories
import ph.chefdonn.receipts.pendingReceiptIds
import ph.chefdonn.receipts.withPendingReceipts
import java.util.UUID

/**
 * The Office role's home: the receipt book. Day scheme, works fully off mesh —
 * receipts filed at the market queue (with their photos) and sync on return.
 */
@Composable
fun ReceiptsScreen(
    deviceId: String,
    deviceName: String,
    client: ChefDonnClient,
    photos: PhotoSync,
    submit: (Command) -> Unit,
) {
    val state by client.state.collectAsState()
    val pending by client.pendingCommands.collectAsState()
    val pendingPhotos by photos.pendingIds.collectAsState()

    // Hub state + this device's queued receipt commands, via the hub's own rules.
    val book = remember(state, pending) { state.withPendingReceipts(pending, System.currentTimeMillis()) }
    val queuedIds = remember(pending) { pendingReceiptIds(pending) }

    // A string route so the open form survives the camera app evicting this process.
    var route by rememberSaveable { mutableStateOf(BOOK) }
    BackHandler(enabled = route != BOOK) { route = BOOK }

    DonnSurface(night = false) {
        Column(Modifier.fillMaxSize()) {
            StaffHeader(
                title = when {
                    route == CATEGORIES -> "Categories"
                    route.startsWith(NEW) -> "New Receipt"
                    route.startsWith(EDIT) -> "Edit Receipt"
                    else -> "Receipts"
                },
                client = client,
                actions = if (route == BOOK) {
                    {
                        DonnButton(
                            "Categories",
                            size = ButtonSize.MD,
                            variant = ButtonVariant.SECONDARY,
                            onClick = { route = CATEGORIES },
                        )
                    }
                } else {
                    null
                },
            )

            Box(Modifier.weight(1f)) {
                when {
                    route == BOOK -> ReceiptBook(
                        book = book,
                        queuedIds = queuedIds,
                        pendingPhotos = pendingPhotos,
                        onNew = { route = NEW + UUID.randomUUID() },
                        onOpen = { route = DETAIL + it },
                    )

                    route == CATEGORIES -> CategoriesScreen(
                        book = book,
                        onSave = { categories ->
                            submit(UpdateExpenseCategories(UUID.randomUUID().toString(), deviceId, deviceName, categories))
                        },
                        onBack = { route = BOOK },
                    )

                    route.startsWith(NEW) -> ReceiptForm(
                        formKey = route,
                        receiptId = route.removePrefix(NEW),
                        existing = null,
                        book = book,
                        photos = photos,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        submit = submit,
                        // Back to the list, where the new receipt shows up tagged QUEUED.
                        onDone = { route = BOOK },
                    )

                    route.startsWith(EDIT) || route.startsWith(DETAIL) -> {
                        val id = route.removePrefix(EDIT).removePrefix(DETAIL)
                        val found = book.receipts[id]
                        val receipt = found?.takeIf { !it.deleted }
                        when {
                            receipt == null -> Gone(
                                message = if (found != null) "This receipt was deleted." else "Receipt not found.",
                                onBack = { route = BOOK },
                            )

                            route.startsWith(EDIT) -> ReceiptForm(
                                formKey = route,
                                receiptId = id,
                                existing = receipt,
                                book = book,
                                photos = photos,
                                deviceId = deviceId,
                                deviceName = deviceName,
                                submit = submit,
                                onDone = { route = DETAIL + id },
                            )

                            else -> ReceiptDetail(
                                receipt = receipt,
                                book = book,
                                queued = id in queuedIds,
                                pendingPhotos = pendingPhotos,
                                photos = photos,
                                onEdit = { route = EDIT + id },
                                onDelete = { reason ->
                                    submit(DeleteReceipt(UUID.randomUUID().toString(), deviceId, deviceName, id, reason))
                                    route = BOOK
                                },
                                onBack = { route = BOOK },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Gone(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(DonnDimens.space6), verticalArrangement = Arrangement.spacedBy(DonnDimens.space4)) {
        DonnText(message, style = DonnType.bodyMd, color = donnColors.textSecondary)
        DonnButton("Back to Receipts", onClick = onBack, size = ButtonSize.MD, variant = ButtonVariant.SECONDARY)
    }
}

private const val BOOK = "book"
private const val CATEGORIES = "categories"
private const val NEW = "new:"
private const val EDIT = "edit:"
private const val DETAIL = "detail:"
