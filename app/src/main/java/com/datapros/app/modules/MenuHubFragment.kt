package com.datapros.app.modules

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.datapros.app.R
import com.datapros.app.utils.UiMotion
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

abstract class MenuHubFragment : Fragment(R.layout.fragment_menu_hub) {

    protected data class HubAction(
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int,
        @DrawableRes val iconRes: Int,
        @IdRes val destinationId: Int
    )

    @get:StringRes
    protected abstract val hubTitleRes: Int

    @get:StringRes
    protected abstract val hubSubtitleRes: Int

    protected abstract val actions: List<HubAction>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tv_hub_title).setText(hubTitleRes)
        view.findViewById<TextView>(R.id.tv_hub_subtitle).setText(hubSubtitleRes)

        val cardIds = intArrayOf(
            R.id.card_action_1,
            R.id.card_action_2,
            R.id.card_action_3,
            R.id.card_action_4,
            R.id.card_action_5
        )
        val titleIds = intArrayOf(
            R.id.tv_action_1_title,
            R.id.tv_action_2_title,
            R.id.tv_action_3_title,
            R.id.tv_action_4_title,
            R.id.tv_action_5_title
        )
        val subtitleIds = intArrayOf(
            R.id.tv_action_1_subtitle,
            R.id.tv_action_2_subtitle,
            R.id.tv_action_3_subtitle,
            R.id.tv_action_4_subtitle,
            R.id.tv_action_5_subtitle
        )
        val buttonIds = intArrayOf(
            R.id.btn_action_1,
            R.id.btn_action_2,
            R.id.btn_action_3,
            R.id.btn_action_4,
            R.id.btn_action_5
        )
        val activeButtons = mutableListOf<View>()

        for (index in cardIds.indices) {
            val action = actions.getOrNull(index)
            val card = view.findViewById<MaterialCardView>(cardIds[index])
            val title = view.findViewById<TextView>(titleIds[index])
            val subtitle = view.findViewById<TextView>(subtitleIds[index])
            val button = view.findViewById<MaterialButton>(buttonIds[index])

            if (action == null) {
                card.isVisible = false
                continue
            }

            card.isVisible = true
            title.setText(action.titleRes)
            subtitle.setText(action.subtitleRes)
            button.setText(action.titleRes)
            button.setIconResource(action.iconRes)
            button.setOnClickListener {
                val navController = findNavController()
                if (navController.currentDestination?.id != action.destinationId) {
                    navController.navigate(action.destinationId)
                }
            }
            activeButtons.add(button)
        }

        UiMotion.bindPress(*activeButtons.toTypedArray())
        UiMotion.runEntryStagger(view)
    }
}
