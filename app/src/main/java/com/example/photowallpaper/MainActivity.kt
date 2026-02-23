package com.example.photowallpaper

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.photowallpaper.ui.MainScreen
import com.example.photowallpaper.ui.MainViewModel
import com.example.photowallpaper.ui.theme.PhotoWallpaperTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = viewModel.authManager.handleSignInResult(result.data)
            viewModel.onSignInSuccess(account.email)
        } catch (e: Exception) {
            Log.e("MainActivity", "Sign-in failed", e)
            viewModel.onSignInFailed(e.message ?: "Unknown error")
        }
    }

    private val recoveryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // After recovery, try to check status and load photos again
        viewModel.checkSignInStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    state.recoveryIntent?.let { intent ->
                        viewModel.onRecoveryIntentHandled()
                        recoveryLauncher.launch(intent)
                    }
                }
            }
        }

        setContent {
            PhotoWallpaperTheme {
                MainScreen(
                    viewModel = viewModel,
                    onSignInClick = {
                        signInLauncher.launch(viewModel.authManager.signInIntent)
                    }
                )
            }
        }
    }
}
