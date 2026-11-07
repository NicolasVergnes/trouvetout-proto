package app.trouvetout.android.ui.purchase

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.billing.ProductKind
import app.trouvetout.android.billing.StoreProduct
import app.trouvetout.android.ui.theme.AppTheme
import app.trouvetout.core.licensing.LicenseStatus
import java.text.DateFormat
import java.util.Date

/** Wires the ViewModel; the screen itself is pure. */
@Composable
fun PurchaseRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as App
    val viewModel: PurchaseViewModel = viewModel { PurchaseViewModel(app) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    PurchaseScreen(
        state = state,
        onBuy = { kind -> activity?.let { viewModel.buy(it, kind) } },
        onRestore = viewModel::restore,
        onBack = onBack,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

/** U11: clear and never in the way; everything else in the app goes on working whatever is chosen here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseScreen(
    state: PurchaseUiState,
    onBuy: (ProductKind) -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.purchase_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.status?.let { Status(it) }
            if (state.status?.purchased != true) Offers(state, onBuy)
            OutlinedButton(onClick = onRestore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.purchase_restore))
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.purchase_later))
            }
            if (state.busy) Busy()
        }
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            text = { Text(stringResource(message.textRes())) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun Status(status: LicenseStatus) {
    val end =
        remember(status.trialEndMillis) {
            DateFormat.getDateInstance(DateFormat.LONG).format(Date(status.trialEndMillis))
        }
    when {
        status.purchased ->
            Text(stringResource(R.string.purchase_done_text), style = MaterialTheme.typography.bodyLarge)
        status.canAddItems ->
            Text(stringResource(R.string.purchase_running_text, end), style = MaterialTheme.typography.bodyLarge)
        else -> {
            Text(
                stringResource(R.string.purchase_ended_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.purchase_ended_text), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Offers(
    state: PurchaseUiState,
    onBuy: (ProductKind) -> Unit,
) {
    state.products.forEach { product ->
        Button(
            onClick = { onBuy(product.kind) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (product.kind == ProductKind.LIFETIME) R.string.purchase_lifetime else R.string.purchase_yearly,
                    product.price,
                ),
            )
        }
    }
    if (state.loading) Busy()
    if (!state.loading && state.products.isEmpty()) {
        Text(stringResource(R.string.purchase_unavailable), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Busy() {
    val label = stringResource(R.string.settings_busy)
    CircularProgressIndicator(Modifier.semantics { contentDescription = label })
}

private fun PurchaseMessage.textRes(): Int = when (this) {
    PurchaseMessage.PENDING -> R.string.purchase_pending
    PurchaseMessage.NOTHING_TO_RESTORE -> R.string.purchase_nothing_to_restore
    PurchaseMessage.RESTORED -> R.string.purchase_restored
    PurchaseMessage.UNAVAILABLE -> R.string.purchase_unavailable
}

@Preview(showBackground = true)
@Preview(showBackground = true, fontScale = 2f)
@Composable
private fun PurchaseScreenEndedPreview() {
    AppTheme {
        PurchaseScreen(
            PurchaseUiState(
                status = LicenseStatus(purchased = false, trialEndMillis = 0L, canAddItems = false),
                products =
                    listOf(StoreProduct(ProductKind.LIFETIME, "10,00 €"), StoreProduct(ProductKind.YEARLY, "2,00 €")),
                loading = false,
            ),
            onBuy = {},
            onRestore = {},
            onBack = {},
            onDismissMessage = {},
        )
    }
}
