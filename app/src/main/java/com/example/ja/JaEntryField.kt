package com.example.ja

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** A navigable field first; only an explicit OK/tap creates an editable text-input connection. */
@Composable
internal fun JaEntryField(
    value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,
    label:@Composable (() -> Unit)?=null,singleLine:Boolean=true,
    keyboardOptions:KeyboardOptions=KeyboardOptions.Default,
    keyboardActions:KeyboardActions=KeyboardActions.Default,
    colors:TextFieldColors=OutlinedTextFieldDefaults.colors(),
    initiallyEditing:Boolean=false
){
    var editing by remember { mutableStateOf(initiallyEditing) }
    var focused by remember { mutableStateOf(false) }
    var hadEditFocus by remember { mutableStateOf(false) }
    val navigation=remember { FocusRequester() }
    val input=remember { FocusRequester() }
    val keyboard=LocalSoftwareKeyboardController.current
    var restore by remember { mutableStateOf(false) }
    fun finish(){keyboard?.hide();editing=false;hadEditFocus=false;restore=true}
    LaunchedEffect(editing){if(editing){withFrameNanos{};input.requestFocus();keyboard?.show()}}
    LaunchedEffect(restore){if(restore){withFrameNanos{};navigation.requestFocus();restore=false}}
    BackHandler(editing){finish()}
    Box(modifier.focusRequester(navigation).onFocusChanged{focused=it.isFocused}
        .then(if(focused&&!editing)Modifier.border(2.dp,Color.White,RoundedCornerShape(4.dp)) else Modifier)
        .clickable(enabled=!editing){editing=true}) {
        if(editing)OutlinedTextField(value,onValueChange,Modifier.fillMaxWidth().focusRequester(input)
            .onFocusChanged{event->
                if(event.isFocused)hadEditFocus=true
                else if(hadEditFocus){keyboard?.hide();editing=false;hadEditFocus=false}
            },label=label,singleLine=singleLine,keyboardOptions=keyboardOptions,
            keyboardActions=KeyboardActions(
                onSearch={keyboardActions.onSearch?.invoke(this);finish()},
                onDone={keyboardActions.onDone?.invoke(this);finish()},
                onNext={keyboardActions.onNext?.invoke(this)?:defaultKeyboardAction(ImeAction.Next)},
                onPrevious={keyboardActions.onPrevious?.invoke(this)?:defaultKeyboardAction(ImeAction.Previous)},
                onGo={keyboardActions.onGo?.invoke(this);finish()},
                onSend={keyboardActions.onSend?.invoke(this);finish()}),colors=colors)
        else OutlinedTextField(value,{},Modifier.fillMaxWidth(),enabled=false,readOnly=true,label=label,singleLine=singleLine,
            colors=OutlinedTextFieldDefaults.colors(disabledTextColor=Color.White,disabledBorderColor=Color.LightGray,
                disabledLabelColor=Color.LightGray,disabledPlaceholderColor=Color.LightGray))
    }
}
