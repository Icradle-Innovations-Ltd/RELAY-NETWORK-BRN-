package com.brn.client.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.brn.client.BuildConfig
import com.brn.client.R
import com.brn.client.net.ControlPlaneApi
import com.brn.client.state.ClientStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var stateStore: ClientStateStore
    private lateinit var api: ControlPlaneApi
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateStore = ClientStateStore(this)
        api = ControlPlaneApi(BuildConfig.CONTROL_PLANE_BASE_URL)

        if (stateStore.isLoggedIn) {
            navigateToMain()
            return
        }

        window.statusBarColor = color(R.color.brn_status_bar)
        window.navigationBarColor = color(R.color.brn_background)
        setContentView(buildUI())
    }

    private fun buildUI(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(color(R.color.brn_background))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(80), dp(32), dp(32))
        }

        // Icon
        val icon = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@LoginActivity, R.drawable.ic_launcher_foreground))
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        }
        container.addView(icon)
        container.addView(spacer(12))

        // Title
        container.addView(TextView(this).apply {
            text = "BRN Client"
            setTextColor(color(R.color.brn_on_background))
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        container.addView(spacer(4))

        container.addView(TextView(this).apply {
            text = "Share bandwidth, earn rewards"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 14f
            gravity = Gravity.CENTER
        })
        container.addView(spacer(48))

        // Email field
        container.addView(TextView(this).apply {
            text = "Email"
            setTextColor(color(R.color.brn_on_surface))
            textSize = 14f
        })
        container.addView(spacer(8))

        emailInput = EditText(this).apply {
            hint = "you@example.com"
            setHintTextColor(color(R.color.brn_on_surface_dim))
            setTextColor(color(R.color.brn_on_surface))
            textSize = 16f
            background = inputBackground()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(emailInput)
        container.addView(spacer(16))

        // Password field
        container.addView(TextView(this).apply {
            text = "Password"
            setTextColor(color(R.color.brn_on_surface))
            textSize = 14f
        })
        container.addView(spacer(8))

        passwordInput = EditText(this).apply {
            hint = "Enter password"
            setHintTextColor(color(R.color.brn_on_surface_dim))
            setTextColor(color(R.color.brn_on_surface))
            textSize = 16f
            background = inputBackground()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(passwordInput)
        container.addView(spacer(8))

        // Error text
        errorText = TextView(this).apply {
            setTextColor(color(R.color.brn_error))
            textSize = 13f
            visibility = View.GONE
        }
        container.addView(errorText)
        container.addView(spacer(24))

        // Login button
        loginButton = Button(this).apply {
            text = "Log In"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            background = buttonBackground(color(R.color.brn_accent_dark), dp(12).toFloat())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onLoginClicked() }
        }
        container.addView(loginButton)
        container.addView(spacer(16))

        // Signup link
        container.addView(TextView(this).apply {
            text = "Don't have an account? Sign Up"
            setTextColor(color(R.color.brn_accent))
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener {
                startActivity(Intent(this@LoginActivity, SignupActivity::class.java))
            }
        })

        root.addView(container)
        return root
    }

    private fun onLoginClicked() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            showError("Please enter email and password")
            return
        }

        loginButton.isEnabled = false
        loginButton.alpha = 0.5f
        errorText.visibility = View.GONE

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.login(email, password)
                }
                stateStore.userToken = result.token
                stateStore.userId = result.userId
                stateStore.userEmail = email
                navigateToMain()
            } catch (e: Exception) {
                showError(e.message ?: "Login failed")
                loginButton.isEnabled = true
                loginButton.alpha = 1f
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ── UI helpers ──

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun spacer(heightDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun inputBackground() = GradientDrawable().apply {
        setColor(color(R.color.brn_surface))
        cornerRadius = dp(8).toFloat()
        setStroke(1, color(R.color.brn_divider))
    }

    private fun buttonBackground(bgColor: Int, radius: Float) = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radius
    }
}
