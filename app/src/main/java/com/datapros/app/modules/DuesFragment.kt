package com.datapros.app.modules

import com.datapros.app.utils.PythonRunner
import org.json.JSONObject

class DuesFragment : BaseModuleFragment() {

    override val moduleTitle    = "Loan Dues Report"
    override val moduleSubtitle = "Premium dues report • Branch & RO breakdown"

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject =
        PythonRunner.runDues(inputPath, outputDir)

    override fun bindStats(stats: JSONObject) {
        val clients  = stats.optInt("total_clients", 0)
        val due      = stats.optDouble("total_due",      0.0)
        val arrears  = stats.optDouble("total_arrears",  0.0)
        val branches = stats.optInt("num_branches",      0)

        setStats(
            v1 = "%,d".format(clients),           l1 = "Clients",
            v2 = "KES %,.0f".format(due),         l2 = "Total Due",
            v3 = "KES %,.0f".format(arrears),     l3 = "Total Arrears",
            v4 = branches.toString(),              l4 = "Branches"
        )
    }
}
