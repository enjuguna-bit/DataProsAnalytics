package com.datapros.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.datapros.app.databinding.ActivityOfflineGuideBinding
import com.datapros.app.utils.OfflineGuideManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfflineGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.guideToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.guide_screen_title)

        binding.btnSaveGuideCopy.setOnClickListener {
            saveGuideCopy()
        }

        loadGuideText()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadGuideText() {
        showLoading(true)
        lifecycleScope.launch {
            val guideText = withContext(Dispatchers.IO) {
                OfflineGuideManager.readBundledGuideText(this@OfflineGuideActivity)
            }
            showLoading(false)

            if (guideText.isNullOrBlank()) {
                binding.txtGuideError.visibility = View.VISIBLE
                binding.txtGuideError.text = getString(R.string.guide_content_unavailable)
                binding.txtGuideContent.visibility = View.GONE
                return@launch
            }

            binding.txtGuideError.visibility = View.GONE
            binding.txtGuideContent.visibility = View.VISIBLE
            binding.txtGuideContent.text = guideText
        }
    }

    private fun saveGuideCopy() {
        val guide = OfflineGuideManager.exportBundledGuide(this)
        if (guide == null) {
            showSnackbar(getString(R.string.settings_guide_missing))
            return
        }

        val message = if (guide.savedToDownloads) {
            getString(R.string.guide_copy_saved_downloads, guide.locationLabel)
        } else {
            getString(R.string.guide_copy_saved_local, guide.locationLabel)
        }
        showSnackbar(message)
    }

    private fun showLoading(loading: Boolean) {
        binding.guideProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.txtGuideContent.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
