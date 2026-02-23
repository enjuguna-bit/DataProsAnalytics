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

/**
 * Base fragment shared by single-file modules (Arrears, Dues, Dormant, MTD, RO).
 * Subclasses override [moduleTitle], [moduleSubtitle], [runProcessor], and [bindStats].
 */
abstract class BaseModuleFragment : Fragment(R.layout.fragment_single_file) {

    abstract val moduleTitle: String
    abstract val moduleSubtitle: String

    protected var selectedFilePath: String? = null
    protected var lastOutputFile:   String? = null

    private lateinit var fileLauncher: ActivityResultLauncher<Intent>

    // Views
    private lateinit var tvTitle:    TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvFileName: TextView
    private lateinit var btnSelect:  MaterialButton
    private lateinit var btnProcess: MaterialButton
    private lateinit var progress:   LinearProgressIndicator
    private lateinit var tvStatus:   TextView
    private lateinit var cardStats:  MaterialCardView
    private lateinit var cardChart:  MaterialCardView
    private lateinit var ivChart:    ImageView
    private lateinit var layoutExport: View
    private lateinit var btnExport:  MaterialButton
    private lateinit var btnShare:   MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonRunner.ensureStarted(requireContext())

        fileLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { onFileSelected(it) }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTitle    = view.findViewById(R.id.tv_module_title)
        tvSubtitle = view.findViewById(R.id.tv_module_subtitle)
        tvFileName = view.findViewById(R.id.tv_file_name)
        btnSelect  = view.findViewById(R.id.btn_select_file)
        btnProcess = view.findViewById(R.id.btn_process)
        progress   = view.findViewById(R.id.progress_bar)
        tvStatus   = view.findViewById(R.id.tv_status)
        cardStats  = view.findViewById(R.id.card_stats)
        cardChart  = view.findViewById(R.id.card_chart)
        ivChart    = view.findViewById(R.id.iv_chart)
        layoutExport = view.findViewById(R.id.layout_export_buttons)
        btnExport  = view.findViewById(R.id.btn_export)
        btnShare   = view.findViewById(R.id.btn_share)

        tvTitle.text    = moduleTitle
        tvSubtitle.text = moduleSubtitle

        btnSelect.setOnClickListener { launchFilePicker() }
        btnProcess.setOnClickListener { process() }
        btnExport.setOnClickListener { openReport() }
        btnShare.setOnClickListener  { shareReport() }

        UiMotion.bindPress(btnSelect, btnProcess, btnExport, btnShare)
        UiMotion.runEntryStagger(view)
    }

    protected open fun filePickerMimeTypes(): Array<String> = arrayOf(
        "text/csv",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, filePickerMimeTypes())
        }
        fileLauncher.launch(intent)
    }

    private fun onFileSelected(uri: Uri) {
        val path = FileUtils.uriToPath(requireContext(), uri) ?: return
        selectedFilePath = path
        tvFileName.text  = path.substringAfterLast('/')
        btnProcess.isEnabled = true
    }

    protected open fun process() {
        val path = selectedFilePath ?: return
        if (!UsageGate.ensureAllowed(this)) return
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runProcessor(path, FileUtils.outputDir(requireContext()))
            }
            setLoading(false)
            handleResult(result)
        }
    }

    /**
     * Subclasses call the appropriate PythonRunner function and return the JSONObject.
     */
    abstract suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject

    /**
     * Subclasses populate the 4 stat views from the result's "stats" JSON object.
     */
    abstract fun bindStats(stats: JSONObject)

    protected fun handleResult(result: JSONObject) {
        if (result.optBoolean("success", false)) {
            UsageGate.recordSuccess(this)
            lastOutputFile = result.optString("output_file")
            val chartFile  = result.optString("chart_file")

            // Show chart
            if (chartFile.isNotEmpty()) {
                val bmp = BitmapFactory.decodeFile(chartFile)
                if (bmp != null) {
                    ivChart.setImageBitmap(bmp)
                    cardChart.isVisible = true
                    UiMotion.reveal(cardChart)
                }
            }

            // Bind stats
            val stats = result.optJSONObject("stats")
            if (stats != null) {
                bindStats(stats)
                cardStats.isVisible = true
                UiMotion.reveal(cardStats)
            }

            layoutExport.isVisible = true
            UiMotion.reveal(layoutExport)
            showStatus("Report ready.")
        } else {
            val err = result.optString("error", "Unknown error")
            showStatus("Error: $err")
            Snackbar.make(requireView(), "Error: $err", Snackbar.LENGTH_LONG).show()
        }
    }

    // Helpers for stat TextViews

    protected fun view(): View = requireView()

    protected fun statValue(id: Int): TextView = requireView().findViewById(id)
    protected fun statLabel(id: Int): TextView = requireView().findViewById(id)

    protected fun setStats(
        v1: String, l1: String,
        v2: String, l2: String,
        v3: String, l3: String,
        v4: String, l4: String
    ) {
        requireView().apply {
            (this.findViewById<TextView>(R.id.tv_stat1_value)).text = v1
            (this.findViewById<TextView>(R.id.tv_stat1_label)).text = l1
            (this.findViewById<TextView>(R.id.tv_stat2_value)).text = v2
            (this.findViewById<TextView>(R.id.tv_stat2_label)).text = l2
            (this.findViewById<TextView>(R.id.tv_stat3_value)).text = v3
            (this.findViewById<TextView>(R.id.tv_stat3_label)).text = l3
            (this.findViewById<TextView>(R.id.tv_stat4_value)).text = v4
            (this.findViewById<TextView>(R.id.tv_stat4_label)).text = l4
        }
    }

    // UI state

    protected fun setLoading(loading: Boolean) {
        progress.isVisible   = loading
        tvStatus.isVisible   = loading
        btnProcess.isEnabled = !loading
        btnSelect.isEnabled  = !loading
        if (loading) tvStatus.text = getString(R.string.processing)
    }

    protected fun showStatus(msg: String) {
        tvStatus.text    = msg
        tvStatus.isVisible = true
    }

    // Open / Share

    private fun openReport() {
        val path = lastOutputFile ?: return
        try {
            startActivity(FileUtils.openFile(requireContext(), path))
        } catch (e: Exception) {
            Snackbar.make(requireView(), "No app found to open .xlsx files", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun shareReport() {
        val path = lastOutputFile ?: return
        startActivity(Intent.createChooser(
            FileUtils.shareFile(requireContext(), path), "Share Report via..."))
    }
}

