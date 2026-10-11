package com.datapros.app.modules

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CollectionsFragment : Fragment(R.layout.fragment_collections) {

    private var sodPath:     String? = null
    private var currentPath: String? = null
    private var outputFile:  String? = null

    private lateinit var sodLauncher:     ActivityResultLauncher<Intent>
    private lateinit var currentLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PythonRunner.ensureStarted(requireContext())

        sodLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    FileUtils.uriToPath(requireContext(), uri, "input")?.let { path ->
                        sodPath = path
                        view?.findViewById<TextView>(R.id.tv_sod_file)?.text =
                            path.substringAfterLast('/')
                        updateProcessButton()
                    }
                }
            }
        }

        currentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    FileUtils.uriToPath(requireContext(), uri, "input")?.let { path ->
                        currentPath = path
                        view?.findViewById<TextView>(R.id.tv_current_file)?.text =
                            path.substringAfterLast('/')
                        updateProcessButton()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.btn_select_sod).setOnClickListener {
            sodLauncher.launch(filePicker())
        }
        view.findViewById<MaterialButton>(R.id.btn_select_current).setOnClickListener {
            currentLauncher.launch(filePicker())
        }
        view.findViewById<MaterialButton>(R.id.btn_process).setOnClickListener { process() }
        view.findViewById<MaterialButton>(R.id.btn_export).setOnClickListener { openReport() }
        view.findViewById<MaterialButton>(R.id.btn_share).setOnClickListener  { shareReport() }

        UiMotion.bindPress(
            view.findViewById(R.id.btn_select_sod),
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

    private fun updateProcessButton() {
        view?.findViewById<MaterialButton>(R.id.btn_process)?.isEnabled =
            sodPath != null && currentPath != null
    }

    private fun process() {
        val sod = sodPath ?: return
        val cur = currentPath ?: return
        if (!UsageGate.ensureAllowed(this)) return
        setLoading(true)
        lifecycleScope.launch {
            try {
                val officers = withContext(Dispatchers.IO) {
                    PythonRunner.getCollectionOfficers(sod)
                }
                if (!isAdded || view == null) return@launch
                showTargetsDialog(officers) { targetsInput ->
                    runCollectionsWithTargets(sod, cur, targetsInput)
                }
            } catch (e: Exception) {
                if (isAdded && view != null) {
                    val err = e.message ?: "Failed to load officers"
                    showStatus("Error: $err")
                    Snackbar.make(requireView(), "Error: $err", Snackbar.LENGTH_LONG).show()
                }
            } finally {
                if (isAdded && view != null) {
                    setLoading(false)
                }
            }
        }
    }

    private fun runCollectionsWithTargets(sod: String, cur: String, targetsInput: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val outputDir = FileUtils.outputDir(requireContext())
                val result = withContext(Dispatchers.IO) {
                    PythonRunner.runCollections(
                        sodPath = sod,
                        currentPath = cur,
                        outputDir = outputDir,
                        targetsInput = targetsInput
                    )
                }
                if (!isAdded || view == null) return@launch
                handleResult(result)
            } catch (e: Exception) {
                if (isAdded && view != null) {
                    val err = e.message ?: "Collections processing failed"
                    showStatus("Error: $err")
                    Snackbar.make(requireView(), "Error: $err", Snackbar.LENGTH_LONG).show()
                }
            } finally {
                if (isAdded && view != null) {
                    setLoading(false)
                }
            }
        }
    }

    private fun showTargetsDialog(officers: List<String>, onSubmit: (String) -> Unit) {
        if (!isAdded) return

        val prefill = officers.joinToString("\n") { "$it=" }
        val input = EditText(requireContext()).apply {
            minLines = 8
            maxLines = 16
            hint = "Officer Name=Target Amount"
            setText(prefill)
            setSelection(text?.length ?: 0)
        }

        val scroll = ScrollView(requireContext()).apply { addView(input) }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Collection Targets")
            .setMessage(
                "Enter targets as Officer=Amount (one per line).\n" +
                    "Example: John Doe=5000\n" +
                    "Use Skip to apply zero targets."
            )
            .setView(scroll)
            .setNegativeButton("Skip") { _, _ -> onSubmit("") }
            .setPositiveButton("Continue") { _, _ ->
                onSubmit(input.text?.toString().orEmpty())
            }
            .setNeutralButton("Cancel") { _, _ ->
                showStatus("Processing cancelled")
            }
            .show()
    }

    private fun handleResult(result: JSONObject) {
        val root = view ?: return
        if (result.optBoolean("success", false)) {
            UsageGate.recordSuccess(this)
            outputFile = result.optString("output_file")

            val chartFile = result.optString("chart_file")
            if (chartFile.isNotEmpty()) {
                val bmp = BitmapFactory.decodeFile(chartFile)
                if (bmp != null) {
                    root.findViewById<ImageView>(R.id.iv_chart).setImageBitmap(bmp)
                    root.findViewById<MaterialCardView>(R.id.card_chart).apply {
                        isVisible = true
                        UiMotion.reveal(this)
                    }
                }
            }

            val stats = result.optJSONObject("stats")
            if (stats != null) {
                root.findViewById<TextView>(R.id.tv_stat1_value).text =
                    "KES %,.0f".format(stats.optDouble("bucket_1_15", 0.0))
                root.findViewById<TextView>(R.id.tv_stat2_value).text =
                    "KES %,.0f".format(stats.optDouble("bucket_16_30", 0.0))
                root.findViewById<TextView>(R.id.tv_stat3_value).text =
                    "KES %,.0f".format(stats.optDouble("bucket_31_180", 0.0))
                root.findViewById<MaterialCardView>(R.id.card_stats).apply {
                    isVisible = true
                    UiMotion.reveal(this)
                }
            }

            root.findViewById<View>(R.id.layout_export_buttons).apply {
                isVisible = true
                UiMotion.reveal(this)
            }
            showStatus("Collections report ready.")
        } else {
            val err = result.optString("error", "Unknown error")
            showStatus("Error: $err")
            Snackbar.make(root, "Error: $err", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        view?.apply {
            findViewById<LinearProgressIndicator>(R.id.progress_bar).isVisible = loading
            findViewById<TextView>(R.id.tv_status).isVisible = loading
            findViewById<MaterialButton>(R.id.btn_process).isEnabled = !loading
            if (loading) findViewById<TextView>(R.id.tv_status).text = getString(com.datapros.app.R.string.processing)
        }
    }

    private fun showStatus(msg: String) {
        view?.apply {
            val tv = findViewById<TextView>(R.id.tv_status)
            tv.text = msg
            tv.isVisible = true
        }
    }

    private fun openReport() {
        val path = outputFile ?: return
        try {
            startActivity(FileUtils.openFile(requireContext(), path))
        } catch (e: Exception) {
            view?.let {
                Snackbar.make(it, "No app found to open .xlsx files", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun shareReport() {
        val path = outputFile ?: return
        startActivity(Intent.createChooser(
            FileUtils.shareFile(requireContext(), path), "Share Report via..."))
    }
}

