package com.datapros.app.modules

import com.datapros.app.utils.PythonRunner
import org.json.JSONObject

class ArrearsFragment : BaseModuleFragment() {

    override val moduleTitle    = "Arrears Dashboard"
    override val moduleSubtitle = "Executive report • Grouped by Sales Rep & bucket"

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject =
        PythonRunner.runArrears(inputPath, outputDir)

    override fun bindStats(stats: JSONObject) {
        val clients  = stats.optInt("total_clients",   0)
        val arrears  = stats.optDouble("total_arrears", 0.0)
        val balance  = stats.optDouble("total_loan_balance", 0.0)
        val reps     = stats.optInt("unique_sales_reps", 0)

        setStats(
            v1 = "%,d".format(clients),                l1 = "Clients",
            v2 = "KES %,.0f".format(arrears),          l2 = "Total Arrears",
            v3 = "KES %,.0f".format(balance),          l3 = "Loan Balance",
            v4 = reps.toString(),                       l4 = "Sales Reps"
        )
    }
}
