package app.trouvetout.android.ui.item

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.trouvetout.android.App
import app.trouvetout.android.R
import app.trouvetout.android.notif.ReminderNotifications
import app.trouvetout.android.notif.rememberNotificationAsk
import app.trouvetout.core.domain.model.Reminder
import app.trouvetout.core.domain.model.ReminderKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What the reminders section of the item sheet can do (E2, E6). */
data class RemindersActions(
    val onAdd: (Long) -> Unit,
    val onRemove: (String) -> Unit,
    val onChecking: (Boolean) -> Unit,
    val onChecked: () -> Unit,
)

/**
 * E2 / E6 on the item sheet: dated reminders (date then time, moments to come only), the « check every six months »
 * switch and its « checked » button. The notification permission (API 33+) is asked after the first reminder, with
 * an explanation; refused, the reminders are kept and the section says that no notification will come.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersSection(
    reminders: List<Reminder>,
    enabled: Boolean,
    actions: RemindersActions,
    now: Long = System.currentTimeMillis(),
) {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(ReminderNotifications.enabled(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = ReminderNotifications.enabled(context)
        onPauseOrDispose {}
    }
    val afterAdding = rememberNotificationAsk { granted -> notificationsOn = granted }
    var picking by rememberSaveable { mutableStateOf(false) }
    val dated = reminders.filter { it.kind == ReminderKind.DATE }
    val check = reminders.filter { it.kind == ReminderKind.CHECK }.minByOrNull { it.fireAt }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.reminders_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        for (reminder in dated) {
            val millis = reminder.fireAt.toEpochMilliseconds()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val text = moment(context, millis)
                Text(
                    if (millis <= now) stringResource(R.string.reminder_past, text) else text,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.onRemove(reminder.id) }, enabled = enabled) {
                    Text(stringResource(R.string.reminder_remove))
                }
            }
        }
        OutlinedButton(onClick = { picking = true }, enabled = enabled) { Text(stringResource(R.string.reminder_add)) }
        CheckRow(check != null, enabled) { on ->
            actions.onChecking(on)
            if (on) afterAdding()
        }
        if (check != null) {
            val millis = check.fireAt.toEpochMilliseconds()
            val text = moment(context, millis, withTime = false)
            Text(
                if (millis <= now) {
                    stringResource(R.string.reminder_check_due, text)
                } else {
                    stringResource(R.string.reminder_check_next, text)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = actions.onChecked, enabled = enabled) {
                Text(stringResource(R.string.reminder_checked))
            }
        }
        if (reminders.isNotEmpty() && !notificationsOn) {
            Text(stringResource(R.string.reminder_notifications_off), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = {
                    (context.applicationContext as App).expectReturn()
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ) { Text(stringResource(R.string.reminder_open_settings)) }
        }
    }

    if (picking) {
        MomentPicker(
            onPick = { millis ->
                picking = false
                actions.onAdd(millis)
                afterAdding()
            },
            onDismiss = { picking = false },
        )
    }
}

/** E6 switch, label and switch read as one element by TalkBack. */
@Composable
private fun CheckRow(
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.reminder_check_switch))
            Text(stringResource(R.string.reminder_check_hint), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A day (today or later) then a time; a moment already past is refused with a message. The date picker gives the
 * day at midnight UTC: it is read in UTC, then combined with the time in the phone's zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentPicker(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val dates =
        rememberDatePickerState(
            initialSelectedDateMillis = todayUtc,
            selectableDates =
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayUtc

                    override fun isSelectableYear(year: Int): Boolean = year >= today.year
                },
        )
    val time = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = DateFormat.is24HourFormat(context))
    var day by rememberSaveable { mutableStateOf<Long?>(null) }
    var past by rememberSaveable { mutableStateOf(false) }
    val chosenDay = day
    if (chosenDay == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { day = dates.selectedDateMillis }, enabled = dates.selectedDateMillis != null) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = dates) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.reminder_time_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimePicker(state = time)
                    if (past) Text(stringResource(R.string.reminder_in_past), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = Instant.ofEpochMilli(chosenDay).atZone(ZoneOffset.UTC).toLocalDate()
                        val millis =
                            date
                                .atTime(LocalTime.of(time.hour, time.minute))
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli()
                        if (millis <= System.currentTimeMillis()) past = true else onPick(millis)
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun moment(
    context: android.content.Context,
    millis: Long,
    withTime: Boolean = true,
): String {
    val flags =
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR or
            (if (withTime) DateUtils.FORMAT_SHOW_TIME else 0)
    return DateUtils.formatDateTime(context, millis, flags)
}
