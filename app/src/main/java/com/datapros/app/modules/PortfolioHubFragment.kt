package com.datapros.app.modules

import com.datapros.app.R

class PortfolioHubFragment : MenuHubFragment() {

    override val hubTitleRes = R.string.title_portfolio
    override val hubSubtitleRes = R.string.hub_portfolio_subtitle

    override val actions = listOf(
        HubAction(
            titleRes = R.string.nav_dormant,
            subtitleRes = R.string.hub_action_dormant,
            iconRes = R.drawable.ic_nav_dormant,
            destinationId = R.id.nav_dormant
        ),
        HubAction(
            titleRes = R.string.nav_sod_cr,
            subtitleRes = R.string.hub_action_sod_cr,
            iconRes = R.drawable.ic_nav_collections,
            destinationId = R.id.nav_sod_cr
        ),
        HubAction(
            titleRes = R.string.nav_mtd_cr,
            subtitleRes = R.string.hub_action_mtd_cr,
            iconRes = R.drawable.ic_nav_branch,
            destinationId = R.id.nav_mtd_cr
        )
    )
}
