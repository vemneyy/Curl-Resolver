package com.desperatio.curlresolver

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Dns
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : AppCompatActivity() {

    private lateinit var ipEdit: EditText
    private lateinit var domainsEdit: EditText
    private lateinit var startBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DomainStatusAdapter

    private val modelItems = mutableListOf<DomainStatus>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipEdit = findViewById(R.id.ipEditText)
        domainsEdit = findViewById(R.id.domainsEditText)
        startBtn = findViewById(R.id.startButton)
        clearBtn = findViewById(R.id.clearButton)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        recyclerView = findViewById(R.id.recyclerView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = DomainStatusAdapter()
        recyclerView.adapter = adapter

        clearBtn.setOnClickListener {
            domainsEdit.setText("")
            modelItems.clear()
            adapter.replaceAll(modelItems)
            progressBar.progress = 0
            progressText.text = "0/0"
        }

        startBtn.setOnClickListener { startChecks() }
    }

    private fun startChecks() {
        val ipStr = ipEdit.text?.toString()?.trim().orEmpty()
        if (ipStr.isEmpty()) {
            toast("Укажите IP")
            return
        }

        val domains = domainsEdit.text?.toString()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()

        if (domains.isEmpty()) {
            toast("Укажите домены (каждый с новой строки)")
            return
        }

        modelItems.clear()
        modelItems += domains.map { DomainStatus(it, Status.PENDING, null, null, null) }
        adapter.replaceAll(modelItems)

        progressBar.progress = 0
        progressBar.max = 100
        progressText.text = getString(R.string.progress_format, 0, modelItems.size)
        setUiEnabled(false)

        lifecycleScope.launch {
            try {
                val ip = withContext(Dispatchers.Default) { InetAddress.getByName(ipStr) }
                val client = buildOkHttpClient(ip)

                var completed = 0
                for (domain in domains) {
                    mutateItem(domain) { it.copy(state = Status.RUNNING, code = null, error = null, durationMs = null) }

                    val start = SystemClock.elapsedRealtime()
                    val result = withContext(Dispatchers.IO) { doRequest(client, domain) }
                    val duration = SystemClock.elapsedRealtime() - start

                    when (result) {
                        is CallResult.Success ->
                            mutateItem(domain) { it.copy(state = Status.SUCCESS, code = result.code, error = null, durationMs = duration) }
                        is CallResult.Failure ->
                            mutateItem(domain) { it.copy(state = Status.FAIL, code = null, error = result.message, durationMs = duration) }
                    }

                    completed += 1
                    val pct = (completed * 100f / modelItems.size).toInt()
                    progressBar.progress = pct
                    progressText.text = getString(R.string.progress_format, completed, modelItems.size)
                }

            } catch (e: UnknownHostException) {
                toast("Неверный IP: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: Exception) {
                toast("Ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setUiEnabled(true)
            }
        }
    }

    private fun mutateItem(domain: String, transform: (DomainStatus) -> DomainStatus) {
        val idx = modelItems.indexOfFirst { it.domain.equals(domain, ignoreCase = true) }
        if (idx >= 0) {
            modelItems[idx] = transform(modelItems[idx])
            adapter.replaceAll(modelItems)
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        startBtn.isEnabled = enabled
        clearBtn.isEnabled = enabled
        ipEdit.isEnabled = enabled
        domainsEdit.isEnabled = enabled
    }

    private fun buildOkHttpClient(ip: InetAddress): OkHttpClient {
        val (sslSocketFactory, trustManager) = UnsafeSSL.makeUnsafeSSLSocketFactory()

        val fixedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(ip)
        }

        return OkHttpClient.Builder()
            .callTimeout(2, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(fixedDns)
            .build()
    }

    private fun doRequest(client: OkHttpClient, domain: String): CallResult {
        val req = Request.Builder()
            .url("https://$domain/")
            .header("User-Agent", "AndroidCurlProbe/1.0")
            .get()
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                CallResult.Success(resp.code)
            }
        } catch (_: InterruptedIOException) {
            CallResult.Failure("таймаут/прерывание")
        } catch (e: SSLHandshakeException) {
            CallResult.Failure("SSL ошибка: ${e.message ?: "handshake"}")
        } catch (e: Exception) {
            CallResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

data class DomainStatus(
    val domain: String,
    val state: Status,
    val code: Int?,
    val error: String?,
    val durationMs: Long?
)

enum class Status { PENDING, RUNNING, SUCCESS, FAIL }

sealed class CallResult {
    data class Success(val code: Int) : CallResult()
    data class Failure(val message: String) : CallResult()
}

object UnsafeSSL {
    fun makeUnsafeSSLSocketFactory(): Pair<SSLSocketFactory, X509TrustManager> {
        val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val tm = trustAllCerts[0] as X509TrustManager
        return sslContext.socketFactory to tm
    }
}
