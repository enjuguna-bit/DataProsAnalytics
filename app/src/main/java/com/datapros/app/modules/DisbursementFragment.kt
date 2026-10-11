package com.datapros.app.modules

import com.datapros.app.utils.PythonRunner
import org.json.JSONObject

class DisbursementFragment : BaseModuleFragment() {

    override val moduleTitle = "Disbursement Report"
    override val moduleSubtitle = "Split names, normalize phones, and calculate weekly and daily installments"

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject =
        PythonRunner.runDisbursement(inputPath, outputDir)

    override fun bindStats(stats: JSONObject) {
        val loans = stats.optInt("total_loans", 0)
        val olb = stats.optDouble("total_olb", 0.0)
        val weekly = stats.optDouble("total_weekly_installment", 0.0)
        val daily = stats.optDouble("total_daily_installment", 0.0)

        setStats(
            v1 = "%,d".format(loans), l1 = "Loans",
            v2 = "KES %,.0f".format(olb), l2 = "Total OLB",
            v3 = "KES %,.0f".format(weekly), l3 = "Weekly Total",
            v4 = "KES %,.0f".format(daily), l4 = "Daily Total"
        )
    }
}
