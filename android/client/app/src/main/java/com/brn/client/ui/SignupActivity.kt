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

class SignupActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var stateStore: ClientStateStore
    private lateinit var api: ControlPlaneApi
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmInput: EditText
    private lateinit var signupButton: Button
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateStore = ClientStateStore(this)
        api = ControlPlaneApi(BuildConfig.CONTROL_PLANE_BASE_URL)

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
            setImageDrawable(ContextCompat.getDrawable(this@SignupActivity, R.drawable.ic_launcher_foreground))
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
        }
        container.addView(icon)
        container.addView(spacer(12))

        container.addView(TextView(this).apply {
            text = "Create Account"
            setTextColor(color(R.color.brn_on_background))
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        container.addView(spacer(4))

        container.addView(TextView(this).apply {
            text = "Join the relay network"
            setTextColor(color(R.color.brn_on_surface_dim))
            textSize = 14f
            gravity = Gravity.CENTER
        })
        container.addView(spacer(48))

        // Email
        container.addView(label("Email"))
        container.addView(spacer(8))
        emailInput = inputField("you@example.com", android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        container.addView(emailInput)
        container.addView(spacer(16))

        // Password
        container.addView(label("Password"))
        container.addView(spacer(8))
        passwordInput = inputField("Minimum 8 characters",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        container.addView(passwordInput)
        container.addView(spacer(16))

        // Confirm password
        container.addView(label("Confirm Password"))
        container.addView(spacer(8))
        confirmInput = inputField("Re-enter password",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        container.addView(confirmInput)
        container.addView(spacer(8))

        // Error
        errorText = TextView(this).apply {
            setTextColor(color(R.color.brn_error))
            textSize = 13f
            visibility = View.GONE
        }
        container.addView(errorText)
        container.addView(spacer(24))

        // Signup button
        signupButton = Button(this).apply {
            text = "Sign Up"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isAllCaps = false
            background = buttonBackground(color(R.color.brn_primary), dp(12).toFloat())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onSignupClicked() }
        }
        container.addView(signupButton)
        container.addView(spacer(16))

        container.addView(TextView(this).apply {
            text = "Already have an account? Log In"
            setTextColor(color(R.color.brn_accent))
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        })

        root.addView(container)
        return root
    }

    private fun onSignupClicked() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val confirm = confirmInput.text.toString()

        if (email.isEmpty() || password.isEmpty()) {
            showError("Please fill in all fields")
            return
        }
        if (password.length < 8) {
            showError("Password must be at least 8 characters")
            return
        }
        if (password != confirm) {
            showError("Passwords do not match")
            return
        }

        signupButton.isEnabled = false
        signupButton.alpha = 0.5f
        errorText.visibility = View.GONE

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.signup(email, password)
                }
                stateStore.userToken = result.token
                stateStore.userId = result.userId
                stateStore.userEmail = email

                startActivity(Intent(this@SignupActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            } catch (e: Exception) {
                showError(e.message ?: "Signup failed")
                signupButton.isEnabled = true
                signupButton.alpha = 1f
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    // ── UI helpers ──

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun spacer(heightDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.brn_on_surface))
        textSize = 14f
    }

    private fun inputField(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        setHintTextColor(color(R.color.brn_on_surface_dim))
        setTextColor(color(R.color.brn_on_surface))
        textSize = 16f
        background = GradientDrawable().apply {
            setColor(color(R.color.brn_surface))
            cornerRadius = dp(8).toFloat()
            setStroke(1, color(R.color.brn_divider))
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        inputType = type
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun buttonBackground(bgColor: Int, radius: Float) = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = radius
    }
}
