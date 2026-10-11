package com.datapros.app.modules

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.datapros.app.R
import com.datapros.app.billing.UsageGate
import com.datapros.app.utils.FileUtils
import com.datapros.app.utils.PythonRunner
import com.datapros.app.utils.UiMotion
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cross-references this month's disbursements against the current arrears list
 * to surface loans that defaulted immediately after being disbursed.
 *
 * Produces a 4-sheet Excel report:
 *   Sheet 1 – Full match list, colour-coded by DaysInArrears
 *   Sheet 2 – Breakdown by Sales Rep
 *   Sheet 3 – Maturity status breakdown
 *   Sheet 4 – Top 10 worst cases
 */
class NewLoansInArrearsFragment : Fragment(R.layout.fragment_new_loans_arrears) {

    private var disbPath:   String? = null
    private var arrearsPath: String? = null
    private var outputFile:  String? = null

    private lateinit var disbLauncher:   ActivityResultLauncher<Intent>
    private lateinit var arrearsLauncher: ActivityResultLauncher<Intent>

    // Views
    private lateinit var tvDisbFile:    TextView
    private lateinit var tvArrearsFile: TextView
    private lateinit var btnSelectDisb:    MaterialButton
    private lateinit var btnSelectArrears: MaterialButton
    private lateinit var btnProcess:    MaterialButton
    private lateinit var btnExport:     MaterialButton
    private lateinit var btnShare:      MaterialButton
    private lateinit var progress:      LinearProgressIndicator
    private lateinit var tvStatus:      TextView
    private lateinit var cardStats:     MaterialCardView
    private lateinit var layoutExport:  View

    // Stat TextViews
    private lateinit var tvStat1Value: TextView
    private lateinit var tvStat2Value: TextView
    private lateinit var tvStat3Value: TextView
    private lateinit var tvStat4Value: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonRunner.ensureStarted(requireContext())

        disbLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { handleDisbPicked(it) }
            }
        }

        arrearsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { handleArrearsPicked(it) }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvDisbFile      = view.findViewById(R.id.tv_disb_file)
        tvArrearsFile   = view.findViewById(R.id.tv_arrears_file)
        btnSelectDisb   = view.findViewById(R.id.btn_select_disb)
        btnSelectArrears = view.findViewById(R.id.btn_select_arrears)
        btnProcess      = view.findViewById(R.id.btn_process)
        btnExport       = view.findViewById(R.id.btn_export)
        btnShare        = view.findViewById(R.id.btn_share)
        progress        = view.findViewById(R.id.progress_bar)
        tvStatus        = view.findViewById(R.id.tv_status)
        cardStats       = view.findViewById(R.id.card_stats)
        layoutExport    = view.findViewById(R.id.layout_export_buttons)

        tvStat1Value = view.findViewById(R.id.tv_stat1_value)
        tvStat2Value = view.findViewById(R.id.tv_stat2_value)
        tvStat3Value = view.findViewById(R.id.tv_stat3_value)
        tvStat4Value = view.findViewById(R.id.tv_stat4_value)

        btnSelectDisb.setOnClickListener   { disbLauncher.launch(filePicker()) }
        btnSelectArrears.setOnClickListener { arrearsLauncher.launch(filePicker()) }
        btnProcess.setOnClickListener       { process() }
        btnExport.setOnClickListener        { openReport() }
        btnShare.setOnClickListener         { shareReport() }

        UiMotion.bindPress(btnSelectDisb, btnSelectArrears, btnProcess, btnExport, btnShare)
        UiMotion.runEntryStagger(view)
    }

    private fun filePicker(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }

    private fun handleDisbPicked(uri: Uri) {
        val path = FileUtils.uriToPath(requireContext(), uri) ?: return
        disbPath = path
        tvDisbFile.text = path.substringAfterLast('/')
        updateProcessButton()
    }

    private fun handleArrearsPicked(uri: Uri) {
        val path = FileUtils.uriToPath(requireContext(), uri) ?: return
        arrearsPath = path
        tvArrearsFile.text = path.substringAfterLast('/')
        updateProcessButton()
    }

    private fun updateProcessButton() {
        btnProcess.isEnabled = disbPath != null && arrearsPath != null
    }

    private fun process() {
        val disb    = disbPath    ?: return
        val arrears = arrearsPath ?: return
        if (!UsageGate.ensureAllowed(this)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PythonRunner.runNewLoansInArrears(
                        disbursementPath = disb,
                        arrearsPath      = arrears,
                        outputDir        = FileUtils.outputDir(requireContext())
                    )
                }
                if (isAdded && view != null) handleResult(result)
            } catch (e: Exception) {
                if (isAdded && view != null) showError(e.message ?: "Processing failed")
            } finally {
                if (isAdded && view != null) setLoading(false)
            }
        }
    }

    private fun handleResult(result: JSONObject) {
        if (!result.optBoolean("success", false)) {
            showError(result.optString("error", "Unknown error"))
            return
        }

        UsageGate.recordSuccess(this)
        outputFile = result.optString("output_file").takeIf { it.isNotEmpty() }

        val stats = result.optJSONObject("stats")
        if (stats != null) {
            val disbursed  = stats.optInt("total_disbursed", 0)
            val matched    = stats.optInt("matched", 0)
            val matchPct   = stats.optDouble("match_pct", 0.0)
            val maxDays    = stats.optDouble("max_days_in_arrears", 0.0)

            tvStat1Value.text = "%,d".format(disbursed)
            tvStat2Value.text = "%,d".format(matched)
            tvStat3Value.text = "%.1f%%".format(matchPct)
            tvStat4Value.text = "%.0f".format(maxDays)

            cardStats.isVisible = true
            UiMotion.reveal(cardStats)
        }

        if (outputFile != null) {
            layoutExport.isVisible = true
            UiMotion.reveal(layoutExport)
            showStatus(
                if ((stats?.optInt("matched", 0) ?: 0) == 0)
                    "No new loans found in arrears — portfolio looks clean."
                else
                    "Report ready. ${stats?.optInt("matched", 0)} loan(s) flagged."
            )
        } else {
            // matched == 0, no file written
            showStatus(result.optString("message", "No matches found."))
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.isVisible    = loading
        tvStatus.isVisible    = loading
        btnProcess.isEnabled  = !loading && disbPath != null && arrearsPath != null
        btnSelectDisb.isEnabled    = !loading
        btnSelectArrears.isEnabled = !loading
        if (loading) tvStatus.text = getString(R.string.processing)
    }

    private fun showStatus(msg: String) {
        tvStatus.text      = msg
        tvStatus.isVisible = true
    }

    private fun showError(msg: String) {
        val prefixed = "Error: $msg"
        showStatus(prefixed)
        Snackbar.make(requireView(), prefixed, Snackbar.LENGTH_LONG).show()
    }

    private fun openReport() {
        val path = outputFile ?: return
        runCatching { startActivity(FileUtils.openFile(requireContext(), path)) }
            .onFailure {
                Snackbar.make(requireView(), "No app found to open .xlsx files", Snackbar.LENGTH_LONG).show()
            }
    }

    private fun shareReport() {
        val path = outputFile ?: return
        startActivity(
            Intent.createChooser(FileUtils.shareFile(requireContext(), path), "Share Report via…")
        )
    }
}
