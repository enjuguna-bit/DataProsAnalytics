package com.datapros.app.modules

import android.app.Activity
import android.content.Intent
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

class LoanTrackerFragment : Fragment(R.layout.fragment_loan_tracker) {

    private var reportPath: String? = null
    private var outputFile: String? = null

    private lateinit var reportLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonRunner.ensureStarted(requireContext())

        reportLauncher = registerPicker { path ->
            reportPath = path
            view?.findViewById<TextView>(R.id.tv_report_file)?.text = path.substringAfterLast('/')
            clearResults()
            updateProcessButton()
        }
    }

    private fun registerPicker(onResult: (String) -> Unit): ActivityResultLauncher<Intent> {
        return registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    FileUtils.uriToPath(requireContext(), uri, "input")?.let(onResult)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btn_report).setOnClickListener {
            reportLauncher.launch(reportPickerIntent())
        }
        view.findViewById<MaterialButton>(R.id.btn_process).setOnClickListener { process() }
        view.findViewById<MaterialButton>(R.id.btn_export).setOnClickListener { openReport() }
        view.findViewById<MaterialButton>(R.id.btn_share).setOnClickListener { shareReport() }

        UiMotion.bindPress(
            view.findViewById(R.id.btn_report),
            view.findViewById(R.id.btn_process),
            view.findViewById(R.id.btn_export),
            view.findViewById(R.id.btn_share)
        )
        UiMotion.runEntryStagger(view)
    }

    private fun reportPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }

    private fun updateProcessButton() {
        view?.findViewById<MaterialButton>(R.id.btn_process)?.isEnabled =
            reportPath != null
    }

    private fun process() {
        val report = reportPath ?: return
        if (!UsageGate.ensureAllowed(this)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PythonRunner.runLoanTracker(report, FileUtils.outputDir(requireContext()))
                }
                if (isAdded && view != null) handleResult(result)
            } catch (e: Exception) {
                if (isAdded && view != null) {
                    val message = e.message ?: getString(R.string.error_processing_failed)
                    showError(message)
                }
            } finally {
                if (isAdded && view != null) setLoading(false)
            }
        }
    }

    private fun handleResult(result: JSONObject) {
        if (!result.optBoolean("success", false)) {
            showError(result.optString("error", getString(R.string.error_unknown)))
            return
        }

        UsageGate.recordSuccess(this)
        outputFile = result.optString("output_file")

        val stats = result.optJSONObject("stats")
        if (stats != null) {
            bindStats(stats)
            requireView().findViewById<MaterialCardView>(R.id.card_stats).apply {
                isVisible = true
                UiMotion.reveal(this)
            }

            val asOfDate = stats.optString("as_of_date")
            val rowsWithErrors = stats.optInt("rows_with_errors", 0)
            val statusText = if (rowsWithErrors > 0) {
                getString(R.string.loan_tracker_ready_with_errors, asOfDate, rowsWithErrors)
            } else {
                getString(R.string.loan_tracker_ready, asOfDate)
            }
            showStatus(statusText)
        } else {
            showStatus(getString(R.string.report_ready))
        }

        requireView().findViewById<View>(R.id.layout_export_buttons).apply {
            isVisible = true
            UiMotion.reveal(this)
        }
    }

    private fun bindStats(stats: JSONObject) {
        requireView().apply {
            findViewById<TextView>(R.id.tv_stat1_value).text =
                "%,d".format(stats.optInt("loans_processed", 0))
            findViewById<TextView>(R.id.tv_stat2_value).text =
                "%,d".format(stats.optInt("loans_in_arrears", 0))
            findViewById<TextView>(R.id.tv_stat3_value).text =
                "KES %,.0f".format(stats.optDouble("total_catchup_amount", 0.0))
            findViewById<TextView>(R.id.tv_stat4_value).text =
                "%,d".format(stats.optInt("rows_with_errors", 0))
        }
    }

    private fun setLoading(loading: Boolean) {
        view?.apply {
            findViewById<LinearProgressIndicator>(R.id.progress_bar).isVisible = loading
            val statusView = findViewById<TextView>(R.id.tv_status)
            statusView.isVisible = loading
            if (loading) statusView.text = getString(R.string.processing)

            findViewById<MaterialButton>(R.id.btn_report).isEnabled = !loading
            findViewById<MaterialButton>(R.id.btn_process).isEnabled =
                !loading && reportPath != null
        }
    }

    private fun showStatus(message: String) {
        view?.apply {
            val statusView = findViewById<TextView>(R.id.tv_status)
            statusView.text = message
            statusView.isVisible = true
        }
    }

    private fun showError(message: String) {
        val prefixed = getString(R.string.error_prefix, message)
        showStatus(prefixed)
        Snackbar.make(requireView(), prefixed, Snackbar.LENGTH_LONG).show()
    }

    private fun clearResults() {
        view?.findViewById<MaterialCardView>(R.id.card_stats)?.isVisible = false
        view?.findViewById<View>(R.id.layout_export_buttons)?.isVisible = false
        view?.findViewById<TextView>(R.id.tv_status)?.isVisible = false
        outputFile = null
    }

    private fun openReport() {
        val path = outputFile ?: return
        runCatching { startActivity(FileUtils.openFile(requireContext(), path)) }
            .onFailure {
                Snackbar.make(
                    requireView(),
                    getString(R.string.error_no_xlsx_viewer),
                    Snackbar.LENGTH_LONG
                ).show()
            }
    }

    private fun shareReport() {
        val path = outputFile ?: return
        startActivity(
            Intent.createChooser(
                FileUtils.shareFile(requireContext(), path),
                getString(R.string.share_report_chooser_title)
            )
        )
    }
}
