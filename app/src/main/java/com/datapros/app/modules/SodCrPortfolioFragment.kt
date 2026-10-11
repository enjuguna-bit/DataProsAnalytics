package com.datapros.app.modules

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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

class SodCrPortfolioFragment : Fragment(R.layout.fragment_sod_cr_portfolio) {

    private var officerPath: String? = null
    private var detailPath: String? = null
    private var outputFile: String? = null
    private lateinit var officerLauncher: ActivityResultLauncher<Intent>
    private lateinit var detailLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonRunner.ensureStarted(requireContext())
        officerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { handlePicked(it, true) }
        }
        detailLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { handlePicked(it, false) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.btn_select_officer).setOnClickListener { officerLauncher.launch(filePicker()) }
        view.findViewById<MaterialButton>(R.id.btn_select_detail).setOnClickListener { detailLauncher.launch(filePicker()) }
        view.findViewById<MaterialButton>(R.id.btn_process).setOnClickListener { process() }
        view.findViewById<MaterialButton>(R.id.btn_export).setOnClickListener { openReport() }
        view.findViewById<MaterialButton>(R.id.btn_share).setOnClickListener { shareReport() }
        UiMotion.bindPress(view.findViewById(R.id.btn_select_officer), view.findViewById(R.id.btn_select_detail), view.findViewById(R.id.btn_process), view.findViewById(R.id.btn_export), view.findViewById(R.id.btn_share))
        UiMotion.runEntryStagger(view)
    }

    private fun filePicker(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }

    private fun handlePicked(uri: Uri, officer: Boolean) {
        val path = FileUtils.uriToPath(requireContext(), uri, "input") ?: return
        if (officer) {
            officerPath = path
            view?.findViewById<TextView>(R.id.tv_officer_file)?.text = path.substringAfterLast('/')
        } else {
            detailPath = path
            view?.findViewById<TextView>(R.id.tv_detail_file)?.text = path.substringAfterLast('/')
        }
        view?.findViewById<MaterialButton>(R.id.btn_process)?.isEnabled = officerPath != null && detailPath != null
    }

    private fun process() {
        val officer = officerPath ?: return
        val detail = detailPath ?: return
        if (!UsageGate.ensureAllowed(this)) return
        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { PythonRunner.runSodCrPortfolio(officer, detail, FileUtils.outputDir(requireContext())) }
                if (isAdded && view != null) handleResult(result)
            } catch (e: Exception) {
                if (isAdded && view != null) showError(e.message ?: "Processing failed")
            } finally {
                if (isAdded && view != null) setLoading(false)
            }
        }
    }

    private fun handleResult(result: JSONObject) {
        val root = view ?: return
        if (!result.optBoolean("success", false)) { showError(result.optString("error", "Unknown error")); return }
        UsageGate.recordSuccess(this)
        outputFile = result.optString("output_file").takeIf { it.isNotBlank() }
        result.optString("chart_file").takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it)?.let { bmp -> root.findViewById<ImageView>(R.id.iv_chart).setImageBitmap(bmp); root.findViewById<MaterialCardView>(R.id.card_chart).isVisible = true } }
        result.optJSONObject("stats")?.let { stats ->
            root.findViewById<TextView>(R.id.tv_stat1_value).text = "KES %,.0f".format(stats.optDouble("total_clean_amount", 0.0))
            root.findViewById<TextView>(R.id.tv_stat2_value).text = "KES %,.0f".format(stats.optDouble("total_clean_still_collectible", 0.0))
            root.findViewById<TextView>(R.id.tv_stat3_value).text = "%.1f%%".format(stats.optDouble("current_branch_cr", 0.0))
            root.findViewById<TextView>(R.id.tv_stat4_value).text = "%.1f%%".format(stats.optDouble("potential_branch_eod_cr", 0.0))
            root.findViewById<MaterialCardView>(R.id.card_stats).isVisible = true
        }
        root.findViewById<View>(R.id.layout_export_buttons).isVisible = true
        showStatus("SOD CR clean-account opportunity report ready.")
    }

    private fun setLoading(loading: Boolean) {
        view?.apply {
            findViewById<LinearProgressIndicator>(R.id.progress_bar).isVisible = loading
            findViewById<TextView>(R.id.tv_status).isVisible = loading
            findViewById<MaterialButton>(R.id.btn_process).isEnabled = !loading && officerPath != null && detailPath != null
            findViewById<MaterialButton>(R.id.btn_select_officer).isEnabled = !loading
            findViewById<MaterialButton>(R.id.btn_select_detail).isEnabled = !loading
        }
    }

    private fun showStatus(message: String) { view?.findViewById<TextView>(R.id.tv_status)?.apply { text = message; isVisible = true } }
    private fun showError(message: String) { showStatus("Error: $message"); view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() } }
    private fun openReport() { outputFile?.let { startActivity(FileUtils.openFile(requireContext(), it)) } }
    private fun shareReport() { outputFile?.let { startActivity(Intent.createChooser(FileUtils.shareFile(requireContext(), it), "Share Report via...")) } }
}
