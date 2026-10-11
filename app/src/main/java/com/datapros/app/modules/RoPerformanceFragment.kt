package com.datapros.app.modules

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
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

class RoPerformanceFragment : Fragment(R.layout.fragment_ro) {

    private var incomePath:          String? = null
    private var collectionRatePath:  String? = null
    private var disbursementPath:    String? = null
    private var outputFile:          String? = null

    private lateinit var incomeLauncher:       ActivityResultLauncher<Intent>
    private lateinit var collectionLauncher:   ActivityResultLauncher<Intent>
    private lateinit var disbursementLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonRunner.ensureStarted(requireContext())

        incomeLauncher = registerPicker { path ->
            incomePath = path
            view?.findViewById<TextView>(R.id.tv_income_file)?.text = path.substringAfterLast('/')
            updateProcessButton()
        }
        collectionLauncher = registerPicker { path ->
            collectionRatePath = path
            view?.findViewById<TextView>(R.id.tv_cr_file)?.text = path.substringAfterLast('/')
            updateProcessButton()
        }
        disbursementLauncher = registerPicker { path ->
            disbursementPath = path
            view?.findViewById<TextView>(R.id.tv_disb_file)?.text = path.substringAfterLast('/')
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

        view.findViewById<MaterialButton>(R.id.btn_income).setOnClickListener { incomeLauncher.launch(filePicker()) }
        view.findViewById<MaterialButton>(R.id.btn_cr).setOnClickListener     { collectionLauncher.launch(filePicker()) }
        view.findViewById<MaterialButton>(R.id.btn_disb).setOnClickListener   { disbursementLauncher.launch(filePicker()) }
        view.findViewById<MaterialButton>(R.id.btn_process).setOnClickListener { process() }
        view.findViewById<MaterialButton>(R.id.btn_export).setOnClickListener  { openReport() }
        view.findViewById<MaterialButton>(R.id.btn_share).setOnClickListener   { shareReport() }

        UiMotion.bindPress(
            view.findViewById(R.id.btn_income),
            view.findViewById(R.id.btn_cr),
            view.findViewById(R.id.btn_disb),
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

    private fun updateProcessButton() {
        view?.findViewById<MaterialButton>(R.id.btn_process)?.isEnabled =
            incomePath != null && collectionRatePath != null && disbursementPath != null
    }

    private fun process() {
        val income      = incomePath         ?: return
        val collection  = collectionRatePath ?: return
        val disbursement = disbursementPath  ?: return
        if (!UsageGate.ensureAllowed(this)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PythonRunner.runRo(
                        incomePath         = income,
                        collectionRatePath = collection,
                        disbursementPath   = disbursement,
                        outputDir          = FileUtils.outputDir(requireContext())
                    )
                }
                if (isAdded && view != null) handleResult(result)
            } catch (e: Exception) {
                if (isAdded && view != null) {
                    val msg = e.message ?: "RO processing failed"
                    showStatus("Error: $msg")
                    Snackbar.make(requireView(), "Error: $msg", Snackbar.LENGTH_LONG).show()
                }
            } finally {
                if (isAdded && view != null) setLoading(false)
            }
        }
    }

    private fun handleResult(result: JSONObject) {
        if (result.optBoolean("success", false)) {
            UsageGate.recordSuccess(this)
            outputFile = result.optString("output_file")

            result.optString("chart_file").takeIf { it.isNotEmpty() }?.let { chartFile ->
                BitmapFactory.decodeFile(chartFile)?.let { bmp ->
                    requireView().findViewById<ImageView>(R.id.iv_chart).setImageBitmap(bmp)
                    requireView().findViewById<MaterialCardView>(R.id.card_chart).apply {
                        isVisible = true; UiMotion.reveal(this)
                    }
                }
            }

            result.optJSONObject("stats")?.let { stats ->
                requireView().apply {
                    findViewById<TextView>(R.id.tv_stat1_value).text =
                        stats.optInt("ro_count", stats.optInt("total_ros", 0)).toString()
                    findViewById<TextView>(R.id.tv_stat2_value).text =
                        "KES %,.0f".format(stats.optDouble("total_income", 0.0))
                    findViewById<TextView>(R.id.tv_stat3_value).text =
                        "%.1f".format(stats.optDouble("avg_position_score", 0.0))
                    findViewById<MaterialCardView>(R.id.card_stats).apply {
                        isVisible = true; UiMotion.reveal(this)
                    }
                }
            }

            requireView().findViewById<View>(R.id.layout_export_buttons).apply {
                isVisible = true; UiMotion.reveal(this)
            }
            showStatus("RO report ready.")
        } else {
            val err = result.optString("error", "Unknown error")
            showStatus("Error: $err")
            Snackbar.make(requireView(), "Error: $err", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        view?.apply {
            findViewById<LinearProgressIndicator>(R.id.progress_bar).isVisible = loading
            val tv = findViewById<TextView>(R.id.tv_status)
            tv.isVisible = loading
            if (loading) tv.text = getString(R.string.processing)
            findViewById<MaterialButton>(R.id.btn_process).isEnabled = !loading
        }
    }

    private fun showStatus(msg: String) {
        view?.apply {
            val tv = findViewById<TextView>(R.id.tv_status)
            tv.text = msg; tv.isVisible = true
        }
    }

    private fun openReport() {
        val path = outputFile ?: return
        runCatching { startActivity(FileUtils.openFile(requireContext(), path)) }
            .onFailure { Snackbar.make(requireView(), "No app found to open .xlsx files", Snackbar.LENGTH_LONG).show() }
    }

    private fun shareReport() {
        val path = outputFile ?: return
        startActivity(Intent.createChooser(FileUtils.shareFile(requireContext(), path), "Share Report via…"))
    }
}
