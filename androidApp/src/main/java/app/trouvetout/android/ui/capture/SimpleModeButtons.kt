package app.trouvetout.android.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.trouvetout.android.R
import app.trouvetout.android.ui.theme.AppTheme

/** I7: the simplified home, two large buttons: put something away (voice, or the keyboard) and find it. */
@Composable
fun SimpleModeButtons(
    onStore: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onStore, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("simple_store")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.simple_store), style = MaterialTheme.typography.headlineSmall)
            }
        }
        OutlinedButton(
            onClick = onFind,
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("simple_find"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.simple_find), style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Preview(showBackground = true, fontScale = 2f)
@Composable
private fun SimpleModeButtonsPreview() {
    AppTheme { SimpleModeButtons(onStore = {}, onFind = {}) }
}
