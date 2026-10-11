package com.datapros.app.utils

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * PythonRunner – thin Kotlin wrapper around Chaquopy.
 *
 * Improvements over v1:
 *  - [isReady] lets callers guard against calling before Python is fully started.
 *  - [ping] provides a lightweight health-check (useful in integration tests /
 *    first-run diagnostics).
 *  - [ensureStarted] swallows and logs startup failures rather than crashing the
 *    calling thread, returning a boolean indicating success.
 *  - [callRaw] / [call] now explicitly guard against Python not being started and
 *    return a structured error object rather than throwing an unhandled exception.
 */
object PythonRunner {

    private const val TAG = "PythonRunner"

    /** True once [Python.start] has completed successfully. */
    @Volatile private var started = false

    /**
     * Starts the Chaquopy Python runtime if it has not already been started.
     * Safe to call multiple times; subsequent calls are no-ops.
     *
     * @return `true` if Python is (or was already) started successfully.
     */
    fun ensureStarted(context: Context): Boolean {
        if (started) return true
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            started = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Python runtime", e)
            false
        }
    }

    /** Returns `true` if the Python runtime is running and ready to handle calls. */
    fun isReady(): Boolean = started && Python.isStarted()

    /**
     * Quick round-trip call to verify the Python bridge is alive.
     * Returns `true` on success, `false` on any failure.
     */
    fun ping(context: Context): Boolean {
        ensureStarted(context)
        return try {
            val result = call("ping")
            result.optBoolean("pong", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun python(): Python = Python.getInstance()

    // ── Module entry points ──────────────────────────────────────────────────

    fun runArrears(inputPath: String, outputDir: String): JSONObject =
        call("run_arrears", inputPath, outputDir)

    fun runLoanTracker(
        reportPath: String,
        outputDir: String
    ): JSONObject = call("run_loan_tracker", reportPath, outputDir)

    fun runDues(inputPath: String, outputDir: String): JSONObject =
        call("run_dues", inputPath, outputDir)

    fun runDisbursement(inputPath: String, outputDir: String): JSONObject =
        call("run_disbursement", inputPath, outputDir)

    fun getCollectionOfficers(sodPath: String): List<String> =
        callList("get_collection_officers", sodPath)

    fun runCollections(
        sodPath:      String,
        currentPath:  String,
        outputDir:    String,
        targetsInput: String = ""
    ): JSONObject = call("run_collections", sodPath, currentPath, outputDir, targetsInput)

    fun getDormantBranches(inputPath: String): List<String> =
        callList("get_dormant_branches", inputPath)

    fun runDormant(
        inputPath:         String,
        outputDir:         String,
        processAllBranches: Boolean = true,
        selectedBranch:     String  = "",
        removeDuplicates:   Boolean = true,
        fillNa:             Boolean = true,
        addFormatting:      Boolean = true
    ): JSONObject = call(
        "run_dormant",
        inputPath,
        outputDir,
        processAllBranches.toString(),
        selectedBranch,
        removeDuplicates.toString(),
        fillNa.toString(),
        addFormatting.toString()
    )

    fun runBranch(
        incomePath: String,
        crPath:     String,
        disbPath:   String,
        outputDir:  String
    ): JSONObject = call("run_branch", incomePath, crPath, disbPath, outputDir)

    fun runMtd(inputPath: String, outputDir: String): JSONObject =
        call("run_mtd", inputPath, outputDir)

    fun getMtdPivotOfficers(inputPath: String): List<String> =
        callList("get_mtd_pivot_officers", inputPath)

    fun runMtdPivot(
        inputPath:        String,
        outputDir:        String,
        dayHeader:        String = "D19",
        newTargetsInput:  String = "",
        mtdTargetsInput:  String = "",
        dailyTargetsInput: String = ""
    ): JSONObject = call(
        "run_mtd_pivot",
        inputPath,
        outputDir,
        dayHeader,
        newTargetsInput,
        mtdTargetsInput,
        dailyTargetsInput
    )

    fun runRo(
        incomePath:         String,
        collectionRatePath: String,
        disbursementPath:   String,
        outputDir:          String
    ): JSONObject = call("run_ro", incomePath, collectionRatePath, disbursementPath, outputDir)

    fun runSodCrPortfolio(
        officerInputPath: String,
        detailInputPath: String,
        outputDir: String
    ): JSONObject = call("run_sod_cr_portfolio", officerInputPath, detailInputPath, outputDir)

    fun getMtdCrOfficers(inputPath: String): List<String> =
        callList("get_mtd_cr_officers", inputPath)

    fun runMtdCrPortfolio(
        inputPath:    String,
        outputDir:    String,
        targetsInput: String = ""
    ): JSONObject = call("run_mtd_cr_portfolio", inputPath, outputDir, targetsInput)

    fun getMtdIncomePortfolios(inputPath: String): List<String> =
        callList("get_mtd_income_portfolios", inputPath)

    fun runMtdIncomeAnalysis(
        inputPath:         String,
        outputDir:         String,
        dayInput:          String = "1",
        dailyTargetsInput: String = ""
    ): JSONObject = call("run_mtd_income_analysis", inputPath, outputDir, dayInput, dailyTargetsInput)

    /**
     * Cross-reference this month's disbursement file against the current arrears
     * list and produce a flagged Excel report.
     */
    fun runNewLoansInArrears(
        disbursementPath: String,
        arrearsPath:      String,
        outputDir:        String
    ): JSONObject = call("run_new_loans_in_arrears", disbursementPath, arrearsPath, outputDir)

    fun runDcrTrendAnalyser(
        morningPath: String,
        currentPath: String,
        outputDir: String
    ): JSONObject = call("run_dcr_trend_analyser", morningPath, currentPath, outputDir)

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Call a Python function and parse its JSON string return value.
     *
     * Returns a structured error object when:
     *  - Python is not yet started (call [ensureStarted] first)
     *  - the Python function raises an exception
     *  - the return value cannot be parsed as JSON
     */
    private fun call(functionName: String, vararg args: String): JSONObject {
        if (!isReady()) {
            Log.e(TAG, "call($functionName) invoked before Python was started")
            return errorObject("Python runtime not started. Please restart the app.")
        }
        return try {
            JSONObject(callRaw(functionName, *args))
        } catch (e: PyException) {
            Log.e(TAG, "PyException in $functionName", e)
            errorObject(e.message ?: "Unknown Python error")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in $functionName", e)
            errorObject(e.message ?: "Unknown error")
        }
    }

    /**
     * Call a Python function that returns a JSON array and convert to a
     * de-duplicated, non-blank list of strings. Returns an empty list on failure.
     */
    private fun callList(functionName: String, vararg args: String): List<String> {
        if (!isReady()) return emptyList()
        return try {
            val arr = JSONArray(callRaw(functionName, *args))
            (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun callRaw(functionName: String, vararg args: String): String =
        python().getModule("main").callAttr(functionName, *args).toString()

    private fun errorObject(message: String): JSONObject =
        JSONObject().apply {
            put("success", false)
            put("error", message)
        }
}
