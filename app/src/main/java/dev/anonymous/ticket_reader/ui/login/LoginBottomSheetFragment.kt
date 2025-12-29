package dev.anonymous.ticket_reader.ui.login

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color // Added this import
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.anonymous.ticket_reader.R
import java.util.Locale

class LoginBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var webLoginView: WebView
    private lateinit var progressBar: ProgressBar

    private var username: String? = null
    private var password: String? = null

    private var listener: OnDismissListener? = null

    fun setOnDismissListener(listener: OnDismissListener) {
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
        progressBar = view.findViewById(R.id.progressBar)
        setupWebView()
        return view
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet =
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
                ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)

        // Set the custom drawable with rounded corners
        bottomSheet.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.bottom_sheet_background)

        val layoutParams = bottomSheet.layoutParams
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        bottomSheet.layoutParams = layoutParams

        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (username != null && password != null) {
            showWebLoginPage()
        } else {
            Toast.makeText(requireContext(), "تم فقدان اليوزرنيم او الباسورد", Toast.LENGTH_SHORT)
                .show()
            dismiss()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webLoginView.settings.javaScriptEnabled = true
        webLoginView.settings.domStorageEnabled = true
        webLoginView.setBackgroundColor(Color.TRANSPARENT)
        webLoginView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            @Suppress("unused")
            fun hideLoader() {
                Toast.makeText(requireContext(), "جار تسجيل الدخول...", Toast.LENGTH_SHORT).show()
            }
        }, "Android")
    }

    @Suppress("DEPRECATION")
    private fun getRouterIPv4(context: Context): String? {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifiManager.dhcpInfo ?: return null

        val gateway = dhcp.gateway
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            gateway and 0xff,
            gateway shr 8 and 0xff,
            gateway shr 16 and 0xff,
            gateway shr 24 and 0xff
        )
    }

    private fun showWebLoginPage() {
        val routerIp = getRouterIPv4(requireContext())

        if (routerIp == null) {
            Toast.makeText(requireContext(), "⚠ لا يمكن العثور على Gateway IP", Toast.LENGTH_LONG)
                .show()
            return
        }

        Log.d("LOGIN_FRAGMENT", "Router IP: $routerIp")

        val loginUrl = "http://$routerIp/"

        webLoginView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                if (url.contains("status", true) || url.contains("success", true)) {
                    progressBar.visibility = View.GONE
                }

                if (url.contains("login", true) || url.contains("hotspot", true)) {
                    checkLoginFieldsAndInjectCredentials(view)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val message = error?.description.toString()
                Log.e("WEBVIEW_ERROR", "onReceivedError: $message")
                showErrorHtml("❌ فشل الوصول إلى الصفحة\n$message")
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                println("onReceivedHttpError: ${errorResponse?.reasonPhrase}")
                super.onReceivedHttpError(view, request, errorResponse)
            }

        }

        webLoginView.loadUrl(loginUrl)
    }

    private fun checkLoginFieldsAndInjectCredentials(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
                const u = document.querySelector('input[name="username"],input[name="Username"],input[name="اسم المستخدم"], input[name="user"], input[type="text"]');
                const p = document.querySelector('input[name="password"],input[name="Password"],input[name="كلمة المرور"],input[name="كلمة السر"],input[type="password"]');
                const b = [...document.querySelectorAll('button, input[type=submit], input[type=button]')]
                              .find(x => (x.innerText || x.value || "").toLowerCase().includes("login")
                                      || (x.innerText || x.value || "").toLowerCase().includes("connect")
                                      || (x.innerText || x.value || "").includes("تسجيل الدخول")
                                      || (x.innerText || x.value || "").includes("دخول"));

                if (!u && !p) return "NO_USER_PASS";
                if (!u) return "NO_USERNAME";
                if (!p) return "NO_PASSWORD";
                if (!b) return "NO_BUTTON";

                u.value = "$username";
                p.value = "$password";
                setTimeout(() => {
                    b.click();
                    Android.hideLoader();
                }, 1000);

            return "DONE";
            })();
            """
        ) { result ->

            when (result.replace("\"", "")) {

                "NO_USER_PASS" -> showErrorHtml("❌ الصفحة لا تحتوي حقول تسجيل دخول")

                "NO_USERNAME" -> showErrorHtml("❌ لا يوجد حقل Username في صفحة الراوتر")

                "NO_PASSWORD" -> showErrorHtml("❌ لا يوجد حقل Password في صفحة الراوتر")

                "NO_BUTTON" -> showErrorHtml("❌ لا يوجد زر Login في صفحة الراوتر")
            }
        }
    }

    private fun showErrorHtml(message: String) {
        webLoginView.loadData(
            """
        <html>
        <head>
            <meta charset="UTF-8">
            <style>
                body { font-family: sans-serif; text-align: center; padding: 40px; background: #fafafa; color: #333; }
                .box { padding: 20px; border-radius: 10px; background: #fff; box-shadow: 0 0 10px #ddd; display: inline-block; }
                h2 { color: #c62828; }
            </style>
        </head>
        <body>
            <div class="box">
                <h2>⚠️ فشل تحميل الصفحة</h2>
                <p>$message</p>
            </div>
        </body>
        </html>
        """.trimIndent(),
            "text/html",
            "UTF-8"
        )
        progressBar.visibility = View.GONE
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        listener?.onDialogDismissed()
    }

    override fun onDestroyView() {
        webLoginView.apply {
            loadUrl("about:blank")
            removeJavascriptInterface("Android")
            stopLoading()
            clearHistory()
            clearCache(true)
            destroy()
        }
        super.onDestroyView()
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