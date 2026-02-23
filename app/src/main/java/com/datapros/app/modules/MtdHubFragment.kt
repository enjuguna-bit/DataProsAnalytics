package com.datapros.app.modules

import com.datapros.app.R

class MtdHubFragment : MenuHubFragment() {

    override val hubTitleRes = R.string.title_mtd_hub
    override val hubSubtitleRes = R.string.hub_mtd_subtitle

    override val actions = listOf(
        HubAction(
            titleRes = R.string.nav_mtd,
            subtitleRes = R.string.hub_action_mtd,
            iconRes = R.drawable.ic_nav_dues,
            destinationId = R.id.nav_mtd
        ),
        HubAction(
            titleRes = R.string.nav_mtd_income,
            subtitleRes = R.string.hub_action_mtd_income,
            iconRes = R.drawable.ic_nav_collections,
            destinationId = R.id.nav_mtd_income
        )
    )
}
