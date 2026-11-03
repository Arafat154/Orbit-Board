package org.futo.inputmethod.latin.uix.actions

import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.OneHandedMode
import org.futo.inputmethod.latin.uix.OneHandedModeSetting
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSettingBlocking

/** Toolbar button: each tap goes off -> right-hand -> left-hand -> off. */
val OneHandedAction = Action(
    icon = R.drawable.ic_one_handed,
    name = R.string.one_handed_action_title,
    windowImpl = null,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        val next = OneHandedMode.next(context.getSettingBlocking(OneHandedModeSetting))
        context.setSettingBlocking(OneHandedModeSetting.key, next)
    },
)
