package com.datapros.app.utils

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * PythonRunner – thin Kotlin wrapper around Chaquopy.
 * Ensures Python is started once and provides typed result helpers.
 */
object PythonRunner {

    fun ensureStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    private fun python(): Python = Python.getInstance()

    // ── Module entry points ──────────────────────────────────────────────────

    fun runArrears(inputPath: String, outputDir: String): JSONObject {
        return call("run_arrears", inputPath, outputDir)
    }

    fun runDues(inputPath: String, outputDir: String): JSONObject {
        return call("run_dues", inputPath, outputDir)
    }

    fun getCollectionOfficers(sodPath: String): List<String> {
        return try {
            val raw = callRaw("get_collection_officers", sodPath)
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun runCollections(
        sodPath: String,
        currentPath: String,
        outputDir: String,
        targetsInput: String = ""
    ): JSONObject {
        return call("run_collections", sodPath, currentPath, outputDir, targetsInput)
    }

    fun getDormantBranches(inputPath: String): List<String> {
        return try {
            val raw = callRaw("get_dormant_branches", inputPath)
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun runDormant(
        inputPath: String,
        outputDir: String,
        processAllBranches: Boolean = true,
        selectedBranch: String = "",
        removeDuplicates: Boolean = true,
        fillNa: Boolean = true,
        addFormatting: Boolean = true
    ): JSONObject {
        return call(
            "run_dormant",
            inputPath,
            outputDir,
            processAllBranches.toString(),
            selectedBranch,
            removeDuplicates.toString(),
            fillNa.toString(),
            addFormatting.toString()
        )
    }

    fun runBranch(incomePath: String, crPath: String, disbPath: String, outputDir: String): JSONObject {
        return call("run_branch", incomePath, crPath, disbPath, outputDir)
    }

    fun runMtd(inputPath: String, outputDir: String): JSONObject {
        return call("run_mtd", inputPath, outputDir)
    }

    fun getMtdPivotOfficers(inputPath: String): List<String> {
        return try {
            val raw = callRaw("get_mtd_pivot_officers", inputPath)
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun runMtdPivot(
        inputPath: String,
        outputDir: String,
        dayHeader: String = "D19",
        newTargetsInput: String = "",
        mtdTargetsInput: String = "",
        dailyTargetsInput: String = ""
    ): JSONObject {
        return call(
            "run_mtd_pivot",
            inputPath,
            outputDir,
            dayHeader,
            newTargetsInput,
            mtdTargetsInput,
            dailyTargetsInput
        )
    }

    fun runRo(
        incomePath: String,
        collectionRatePath: String,
        disbursementPath: String,
        outputDir: String
    ): JSONObject {
        return call("run_ro", incomePath, collectionRatePath, disbursementPath, outputDir)
    }

    fun runSodCrPortfolio(inputPath: String, outputDir: String): JSONObject {
        return call("run_sod_cr_portfolio", inputPath, outputDir)
    }

    fun getMtdCrOfficers(inputPath: String): List<String> {
        return try {
            val raw = callRaw("get_mtd_cr_officers", inputPath)
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun runMtdCrPortfolio(
        inputPath: String,
        outputDir: String,
        targetsInput: String = ""
    ): JSONObject {
        return call("run_mtd_cr_portfolio", inputPath, outputDir, targetsInput)
    }

    fun getMtdIncomePortfolios(inputPath: String): List<String> {
        return try {
            val raw = callRaw("get_mtd_income_portfolios", inputPath)
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun runMtdIncomeAnalysis(
        inputPath: String,
        outputDir: String,
        dayInput: String = "1",
        dailyTargetsInput: String = ""
    ): JSONObject {
        return call("run_mtd_income_analysis", inputPath, outputDir, dayInput, dailyTargetsInput)
    }

    // ── Internal helper ──────────────────────────────────────────────────────

    private fun call(functionName: String, vararg args: String): JSONObject {
        return try {
            val result = callRaw(functionName, *args)
            JSONObject(result)
        } catch (e: PyException) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Unknown Python error")
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Unknown error")
            }
        }
    }

    private fun callRaw(functionName: String, vararg args: String): String {
        val main = python().getModule("main")
        return main.callAttr(functionName, *args).toString()
    }
}
