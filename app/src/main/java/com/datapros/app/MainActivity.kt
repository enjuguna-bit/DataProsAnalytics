package com.datapros.app

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.datapros.app.billing.SubscriptionHelper
import com.datapros.app.databinding.ActivityMainBinding
import com.datapros.app.utils.UiMotion
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // Track the last animated destination so rapid navigation doesn't re-trigger
    // the same animation twice (e.g. fast taps on the same nav item).
    private var lastAnimatedDestId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val bottomNav: BottomNavigationView = binding.bottomNav
        bottomNav.setupWithNavController(navController)
        UiMotion.bindPress(bottomNav)

        lifecycleScope.launch {
            SubscriptionHelper.refreshSubscriptionStatus(this@MainActivity, forceRefresh = false)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            supportActionBar?.title = destination.label ?: getString(R.string.app_name)
            // Only animate when the destination actually changes to avoid stutter
            // when the same item is re-tapped.
            if (destination.id != lastAnimatedDestId) {
                lastAnimatedDestId = destination.id
                animateScreenEntry()
            }
        }
    }

    private fun animateScreenEntry() {
        binding.navHostFragment
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260L)
            .withStartAction {
                binding.navHostFragment.alpha = 0f
                binding.navHostFragment.translationY = 16f
            }
            .start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_overflow_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_subscription) {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
            return true
        }
        return NavigationUI.onNavDestinationSelected(item, navController) ||
            super.onOptionsItemSelected(item)
    }
}
