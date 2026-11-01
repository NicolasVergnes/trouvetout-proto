package app.trouvetout.android.ui.lock

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * The privacy switch « keep the keyboard from learning what I type » (lot 8, ADR-023): every text field of [content]
 * asks the keyboard not to learn (`IME_FLAG_NO_PERSONALIZED_LEARNING`, honoured by Gboard and most keyboards: no
 * personal dictionary, no learning of the place names and first names typed). A request the keyboard may ignore.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val noLearning =
                PlatformTextInputMethodRequest { outAttributes ->
                    request.createInputConnection(outAttributes).also {
                        outAttributes.imeOptions =
                            outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
                }
            nextHandler.startInputMethod(noLearning)
        },
        content = content,
    )
}
