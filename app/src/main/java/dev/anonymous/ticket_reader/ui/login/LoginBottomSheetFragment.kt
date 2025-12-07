package dev.anonymous.ticket_reader.ui.login

import android.content.Context
import android.content.DialogInterface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.anonymous.ticket_reader.R
import java.util.Locale

class LoginBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var webLoginView: WebView
    private var username: String? = null
    private var password: String? = null

    private var listener:OnDismissListener? = null

    fun setOnDismissListener(listener:OnDismissListener) {
        this.listener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            username = it.getString(ARG_USERNAME)
            password = it.getString(ARG_PASSWORD)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_login_bottom_sheet, container, false)
        webLoginView = view.findViewById(R.id.webLoginView)
        setupWebView()
        return view
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)

        // Set the custom drawable with rounded corners
        bottomSheet.background = ContextCompat.getDrawable(requireContext(), R.drawable.bottom_sheet_background)

        val layoutParams = bottomSheet.layoutParams
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        bottomSheet.layoutParams = layoutParams

        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (username != null && password != null) {
            showWebLoginPage(username!!, password!!)
        } else {
            Toast.makeText(requireContext(), "Username or password missing", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun setupWebView() {
        webLoginView.settings.javaScriptEnabled = true
        webLoginView.settings.domStorageEnabled = true
    }

    @Suppress("DEPRECATION")
    private fun getRouterIPv4(context: Context): String? {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifiManager.dhcpInfo ?: return null

        val gateway = dhcp.gateway
        return String.Companion.format(
            Locale.US,
            "%d.%d.%d.%d",
            gateway and 0xff,
            gateway shr 8 and 0xff,
            gateway shr 16 and 0xff,
            gateway shr 24 and 0xff
        )
    }

    private fun showWebLoginPage(username: String, password: String) {
        val routerIp = getRouterIPv4(requireContext())

        if (routerIp == null) {
            Toast.makeText(requireContext(), "⚠ لا يمكن العثور على Gateway IP", Toast.LENGTH_LONG).show()
            return
        }

        Log.d("LOGIN_FRAGMENT", "Router IP: $routerIp")

        val loginUrl = "http://$routerIp/"

        webLoginView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.contains("login", true) || url.contains("hotspot", true)) {
                    injectCredentials(username, password)
                }

                if (url.contains("status", true) || url.contains("success", true)) {
                    view.stopLoading()
                    view.loadUrl("https://www.google.com")
                }
            }
        }

        webLoginView.loadUrl(loginUrl)
    }

    private fun injectCredentials(username: String, password: String) {
        val script = """
        (function() {
            const fill = (selector, value) => {
                const el = document.querySelector(selector);
                if (el) { el.value = value; el.dispatchEvent(new Event('input')); }
            };

            fill('input[name="username"]', '$username');
            fill('input[name="user"]', '$username');
            fill('input[type="text"]', '$username');

            fill('input[name="password"]', '$password');
            fill('input[type="password"]', '$password');

            const buttons = document.querySelectorAll('button, input[type=submit], input[type=button]');
            for (let b of buttons) {
                if (
                    (b.innerText || b.value || "").toLowerCase().includes("login") ||
                    (b.innerText || b.value || "").includes("Connect") ||
                    (b.innerText || b.value || "").includes("دخول")
                ) {
                    setTimeout(() => b.click(), 1000);
                    break;
                }
            }
        })();
    """.trimIndent()

        webLoginView.evaluateJavascript(script, null)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        listener?.onDialogDismissed()
    }

    interface OnDismissListener {
        fun onDialogDismissed()
    }

    companion object {
        private const val ARG_USERNAME = "username"
        private const val ARG_PASSWORD = "password"

        fun newInstance(username: String, password: String): LoginBottomSheetFragment {
            val fragment = LoginBottomSheetFragment()
            val args = Bundle()
            args.putString(ARG_USERNAME, username)
            args.putString(ARG_PASSWORD, password)
            fragment.arguments = args
            return fragment
        }
    }
}