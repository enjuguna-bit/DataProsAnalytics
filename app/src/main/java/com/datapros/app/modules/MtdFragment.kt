package com.datapros.app.modules

import com.datapros.app.utils.PythonRunner
import org.json.JSONObject

class MtdFragment : BaseModuleFragment() {

    override val moduleTitle    = "MTD Unpaid Dues"
    override val moduleSubtitle = "Month-to-date collection rate • Branch due vs paid"

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject =
        PythonRunner.runMtd(inputPath, outputDir)

    override fun bindStats(stats: JSONObject) {
        val due        = stats.optDouble("total_due",    0.0)
        val paid       = stats.optDouble("total_paid",   0.0)
        val unpaid     = stats.optDouble("total_unpaid", 0.0)
        val rate       = stats.optDouble("collection_rate_pct", 0.0)

        setStats(
            v1 = "KES %,.0f".format(due),          l1 = "Total Due",
            v2 = "KES %,.0f".format(paid),         l2 = "Total Paid",
            v3 = "KES %,.0f".format(unpaid),        l3 = "Unpaid",
            v4 = "%.1f%%".format(rate),             l4 = "Collection Rate"
        )
    }
}
