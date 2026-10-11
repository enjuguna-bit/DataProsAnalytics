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

class DcrTrendAnalyserFragment : Fragment(R.layout.fragment_dcr_trend_analyser) {

    private var morningPath: String? = null
    private var currentPath: String? = null
    private var outputFile: String? = null

    private lateinit var morningLauncher: ActivityResultLauncher<Intent>
    private lateinit var currentLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonRunner.ensureStarted(requireContext())

        morningLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { handleMorningPicked(it) }
            }
        }

        currentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { handleCurrentPicked(it) }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btn_select_morning).setOnClickListener {
            morningLauncher.launch(filePicker())
        }
        view.findViewById<MaterialButton>(R.id.btn_select_current).setOnClickListener {
            currentLauncher.launch(filePicker())
        }
        view.findViewById<MaterialButton>(R.id.btn_process).setOnClickListener { process() }
        view.findViewById<MaterialButton>(R.id.btn_export).setOnClickListener { openReport() }
        view.findViewById<MaterialButton>(R.id.btn_share).setOnClickListener { shareReport() }

        UiMotion.bindPress(
            view.findViewById(R.id.btn_select_morning),
            view.findViewById(R.id.btn_select_current),
            view.findViewById(R.id.btn_process),
            view.findViewById(R.id.btn_export),
            view.findViewById(R.id.btn_share)
        )
        UiMotion.runEntryStagger(view)
    }

    private fun filePicker(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }

    private fun handleMorningPicked(uri: Uri) {
        val path = FileUtils.uriToPath(requireContext(), uri, "input") ?: return
        morningPath = path
        view?.findViewById<TextView>(R.id.tv_morning_file)?.text = path.substringAfterLast('/')
        updateProcessButton()
    }

    private fun handleCurrentPicked(uri: Uri) {
        val path = FileUtils.uriToPath(requireContext(), uri, "input") ?: return
        currentPath = path
        view?.findViewById<TextView>(R.id.tv_current_file)?.text = path.substringAfterLast('/')
        updateProcessButton()
    }

    private fun updateProcessButton() {
        view?.findViewById<MaterialButton>(R.id.btn_process)?.isEnabled =
            morningPath != null && currentPath != null
    }

    private fun process() {
        val morning = morningPath ?: return
        val current = currentPath ?: return
        if (!UsageGate.ensureAllowed(this)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PythonRunner.runDcrTrendAnalyser(
                        morningPath = morning,
                        currentPath = current,
                        outputDir = FileUtils.outputDir(requireContext())
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
        val root = view ?: return
        if (!result.optBoolean("success", false)) {
            showError(result.optString("error", "Unknown error"))
            return
        }

        UsageGate.recordSuccess(this)
        outputFile = result.optString("output_file").takeIf { it.isNotBlank() }

        result.optString("chart_file").takeIf { it.isNotBlank() }?.let { chartFile ->
            BitmapFactory.decodeFile(chartFile)?.let { bmp ->
                root.findViewById<ImageView>(R.id.iv_chart).setImageBitmap(bmp)
                root.findViewById<MaterialCardView>(R.id.card_chart).apply {
                    isVisible = true
                    UiMotion.reveal(this)
                }
            }
        }

        result.optJSONObject("stats")?.let { stats ->
            root.findViewById<TextView>(R.id.tv_stat1_value).text =
                "%,d".format(stats.optInt("total_portfolios", 0))
            root.findViewById<TextView>(R.id.tv_stat2_value).text =
                "%,d".format(stats.optInt("improved_count", 0))
            root.findViewById<TextView>(R.id.tv_stat3_value).text =
                "%,d".format(stats.optInt("declined_count", 0))
            root.findViewById<TextView>(R.id.tv_stat4_value).text =
                "%.2f%%".format(stats.optDouble("avg_change_pct", 0.0))
            root.findViewById<MaterialCardView>(R.id.card_stats).apply {
                isVisible = true
                UiMotion.reveal(this)
            }
        }

        root.findViewById<View>(R.id.layout_export_buttons).apply {
            isVisible = true
            UiMotion.reveal(this)
        }
        showStatus("DCR trend report ready.")
    }

    private fun setLoading(loading: Boolean) {
        view?.apply {
            findViewById<LinearProgressIndicator>(R.id.progress_bar).isVisible = loading
            findViewById<TextView>(R.id.tv_status).isVisible = loading
            findViewById<MaterialButton>(R.id.btn_process).isEnabled = !loading &&
                morningPath != null &&
                currentPath != null
            findViewById<MaterialButton>(R.id.btn_select_morning).isEnabled = !loading
            findViewById<MaterialButton>(R.id.btn_select_current).isEnabled = !loading
            if (loading) findViewById<TextView>(R.id.tv_status).text = getString(R.string.processing)
        }
    }

    private fun showStatus(msg: String) {
        view?.apply {
            val tv = findViewById<TextView>(R.id.tv_status)
            tv.text = msg
            tv.isVisible = true
        }
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
