package com.datapros.app.modules

import com.datapros.app.R

class PerformanceHubFragment : MenuHubFragment() {

    override val hubTitleRes = R.string.title_performance
    override val hubSubtitleRes = R.string.hub_performance_subtitle

    override val actions = listOf(
        HubAction(
            titleRes = R.string.nav_branch,
            subtitleRes = R.string.hub_action_branch,
            iconRes = R.drawable.ic_nav_branch,
            destinationId = R.id.nav_branch
        ),
        HubAction(
            titleRes = R.string.nav_ro,
            subtitleRes = R.string.hub_action_ro,
            iconRes = R.drawable.ic_nav_dormant,
            destinationId = R.id.nav_ro
        ),
        HubAction(
            titleRes = R.string.nav_mtd_pivot,
            subtitleRes = R.string.hub_action_mtd_pivot,
            iconRes = R.drawable.ic_nav_collections,
            destinationId = R.id.nav_mtd_pivot
        ),
        HubAction(
            titleRes = R.string.nav_new_loans_arrears,
            subtitleRes = R.string.hub_action_new_loans_arrears,
            iconRes = R.drawable.ic_nav_arrears,
            destinationId = R.id.nav_new_loans_arrears
        )
    )
}
