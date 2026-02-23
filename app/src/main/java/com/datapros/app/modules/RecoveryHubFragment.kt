package com.datapros.app.modules

import com.datapros.app.R

class RecoveryHubFragment : MenuHubFragment() {

    override val hubTitleRes = R.string.title_recovery
    override val hubSubtitleRes = R.string.hub_recovery_subtitle

    override val actions = listOf(
        HubAction(
            titleRes = R.string.nav_arrears,
            subtitleRes = R.string.hub_action_arrears,
            iconRes = R.drawable.ic_nav_arrears,
            destinationId = R.id.nav_arrears
        ),
        HubAction(
            titleRes = R.string.nav_dues,
            subtitleRes = R.string.hub_action_dues,
            iconRes = R.drawable.ic_nav_dues,
            destinationId = R.id.nav_dues
        ),
        HubAction(
            titleRes = R.string.nav_collections,
            subtitleRes = R.string.hub_action_collections,
            iconRes = R.drawable.ic_nav_collections,
            destinationId = R.id.nav_collections
        )
    )
}
