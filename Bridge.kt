package app.awfi

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * الجسر بين واجهة الويب (index.html) وخصائص أندرويد الحقيقية.
 * الواجهة تستدعي call(method, argsJson, id) والنتائج تعود عبر window.__awfi(id, json).
 */
@Suppress("DEPRECATION")
class Bridge(private val act: MainActivity, private val web: WebView) {
    private val ctx: Context = act.applicationContext
    private val pool = Executors.newCachedThreadPool()
    private val hostRe = Regex("^[A-Za-z0-9._:\\-]{1,253}$")

    private fun emit(id: Int, o: JSONObject) {
        val s = JSONObject.quote(o.toString())
        web.post { web.evaluateJavascript("window.__awfi($id,$s)", null) }
    }

    private fun ev(id: Int, k: String, v: Any?) {
        emit(id, JSONObject().put("done", false).put("k", k).put("v", v ?: JSONObject.NULL))
    }

    @JavascriptInterface
    fun call(method: String, args: String, id: Int) {
        pool.execute {
            val a = try { JSONObject(args) } catch (e: Exception) { JSONObject() }
            val res: Any? = try {
                dispatch(method, a, id)
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: e.javaClass.simpleName)
            }
            emit(id, JSONObject().put("done", true).put("result", res ?: JSONObject.NULL))
        }
    }

    private fun dispatch(m: String, a: JSONObject, id: Int): Any? = when (m) {
        "wifiInfo" -> wifiInfo()
        "scan" -> scan()
        "ping" -> ping(id, a.getString("host"), a.optInt("count", 4), !a.optBoolean("quiet", false))
        "trace" -> trace(id, a.getString("host"))
        "dns" -> dns(a.getString("host"))
        "ports" -> ports(id, a.getString("host"), a.getJSONArray("ports"))
        "wol" -> wol(a.getString("mac"))
        "devices" -> devices(id)
        "speed" -> speed(id)
        "usage" -> usage(a.optInt("days", 1))
        "requestPerms" -> { act.askPerms(); JSONObject().put("ok", true) }
        "openUsageSettings" -> {
            startExternal(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            JSONObject().put("ok", true)
        }
        "openUrl" -> {
            val u = a.getString("url")
            if (u.startsWith("http://") || u.startsWith("https://")) {
                startExternal(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
            }
            JSONObject().put("ok", true)
        }
        else -> JSONObject().put("error", "unknown method")
    }

    private fun startExternal(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) { }
    }

    private fun checkHost(h: String) {
        if (!hostRe.matches(h)) throw IllegalArgumentException("مضيف غير صالح")
    }

    private fun wifi(): WifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun ipStr(i: Int) = "${i and 255}.${(i shr 8) and 255}.${(i shr 16) and 255}.${(i shr 24) and 255}"

    private fun chan(f: Int): Int = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2412) / 5 + 1
        f in 5000..5895 -> (f - 5000) / 5
        f >= 5955 -> (f - 5950) / 5
        else -> 0
    }

    private fun secOf(c: String): String = when {
        c.contains("SAE") || c.contains("WPA3") -> "WPA3"
        c.contains("WPA2") || c.contains("RSN") -> "WPA2"
        c.contains("WPA") -> "WPA"
        c.contains("WEP") -> "WEP"
        else -> "OPEN"
    }

    private fun wifiInfo(): JSONObject {
        val wm = wifi()
        val o = JSONObject()
        if (!wm.isWifiEnabled) return o.put("enabled", false)
        val i = wm.connectionInfo
        val d = wm.dhcpInfo
        val f = i.frequency
        o.put("enabled", true)
            .put("ssid", i.ssid?.trim('"') ?: "")
            .put("bssid", i.bssid ?: "")
            .put("rssi", i.rssi)
            .put("link", i.linkSpeed)
            .put("freq", f)
            .put("channel", chan(f))
            .put("ip", ipStr(d.ipAddress))
            .put("gw", ipStr(d.gateway))
            .put("dns", ipStr(d.dns1))
            .put("mask", ipStr(d.netmask))
        return o
    }

    private fun scan(): JSONObject {
        val wm = wifi()
        if (!wm.isWifiEnabled) return JSONObject().put("error", "الواي فاي متوقف")
        try { wm.startScan() } catch (e: SecurityException) {
            return JSONObject().put("error", "إذن الموقع مطلوب لفحص الشبكات")
        }
        Thread.sleep(2500)
        val list = try { wm.scanResults } catch (e: SecurityException) {
            return JSONObject().put("error", "إذن الموقع مطلوب لفحص الشبكات")
        }
        val arr = JSONArray()
        for (r in list) {
            val caps = r.capabilities ?: ""
            arr.put(
                JSONObject()
                    .put("ssid", r.SSID ?: "")
                    .put("bssid", r.BSSID ?: "")
                    .put("level", r.level)
                    .put("freq", r.frequency)
                    .put("channel", chan(r.frequency))
                    .put("sec", secOf(caps))
                    .put("wps", caps.contains("WPS"))
            )
        }
        return JSONObject().put("list", arr)
    }

    private fun ping(id: Int, host: String, count: Int, stream: Boolean): JSONObject {
        checkHost(host)
        val c = count.coerceIn(1, 10)
        val p = ProcessBuilder("/system/bin/ping", "-c", "$c", "-W", "2", host)
            .redirectErrorStream(true).start()
        val times = ArrayList<Double>()
        val re = Regex("time=([0-9.]+)")
        p.inputStream.bufferedReader().forEachLine { l ->
            if (stream) ev(id, "line", l)
            re.find(l)?.let { times.add(it.groupValues[1].toDouble()) }
        }
        p.waitFor()
        return JSONObject()
            .put("avg", if (times.isEmpty()) -1.0 else times.average())
            .put("recv", times.size)
            .put("sent", c)
            .put("loss", (c - times.size) * 100 / c)
    }

    private fun trace(id: Int, host: String): JSONObject {
        checkHost(host)
        val target = InetAddress.getByName(host).hostAddress ?: host
        val fromRe = Regex("[Ff]rom ((?:\\d{1,3}\\.){3}\\d{1,3})")
        val timeRe = Regex("time=([0-9.]+)")
        var reached = false
        for (ttl in 1..20) {
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", "-t", "$ttl", target)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            val ip = fromRe.find(out)?.groupValues?.get(1)
            val ms = timeRe.find(out)?.groupValues?.get(1)?.toDouble()
            reached = out.contains("bytes from")
            ev(id, "hop", JSONObject().put("ttl", ttl).put("ip", ip ?: "*").put("ms", ms ?: -1.0))
            if (reached) break
        }
        return JSONObject().put("reached", reached)
    }

    private fun dns(host: String): JSONObject {
        checkHost(host)
        val t = System.nanoTime()
        val all = InetAddress.getAllByName(host)
        val ms = (System.nanoTime() - t) / 1e6
        val arr = JSONArray()
        for (a in all) {
            arr.put(JSONObject().put("type", if (a is Inet6Address) "AAAA" else "A").put("addr", a.hostAddress))
        }
        return JSONObject().put("ms", ms).put("records", arr)
    }

    private fun ports(id: Int, host: String, ps: JSONArray): JSONObject {
        checkHost(host)
        val list = (0 until minOf(ps.length(), 100)).map { ps.getInt(it) }.filter { it in 1..65535 }
        val ex = Executors.newFixedThreadPool(16)
        val tasks = list.map { p ->
            Callable {
                val ok = try {
                    Socket().use { s -> s.connect(InetSocketAddress(host, p), 900) }
                    true
                } catch (e: Exception) {
                    false
                }
                ev(id, "port", JSONObject().put("port", p).put("open", ok))
                ok
            }
        }
        val res = ex.invokeAll(tasks)
        ex.shutdown()
        return JSONObject().put("open", res.count { it.get() })
    }

    private fun wol(mac: String): JSONObject {
        val hex = mac.replace(Regex("[:\\-]"), "")
        if (!hex.matches(Regex("[0-9A-Fa-f]{12}"))) throw IllegalArgumentException("عنوان MAC غير صالح")
        val m = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val pkt = ByteArray(102)
        for (i in 0 until 6) pkt[i] = 0xFF.toByte()
        for (i in 1..16) System.arraycopy(m, 0, pkt, i * 6, 6)
        val d = wifi().dhcpInfo
        val bc = (d.ipAddress and d.netmask) or d.netmask.inv()
        val targets = listOf(ipStr(bc), "255.255.255.255")
        DatagramSocket().use { s ->
            s.broadcast = true
            for (t in targets) for (port in intArrayOf(9, 7)) {
                s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(t), port))
            }
        }
        return JSONObject().put("ok", true).put("target", ipStr(bc))
    }

    private fun readArp(): Map<String, String> = try {
        File("/proc/net/arp").readLines().drop(1).mapNotNull {
            val p = it.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[3] != "00:00:00:00:00:00" && p[2] == "0x2") p[0] to p[3].uppercase() else null
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private val oui = mapOf(
        "00:50:56" to "VMware", "00:0C:29" to "VMware", "B8:27:EB" to "Raspberry Pi",
        "DC:A6:32" to "Raspberry Pi", "E4:5F:01" to "Raspberry Pi", "00:17:88" to "Philips Hue",
        "3C:5A:B4" to "Google", "F4:F5:D8" to "Google", "44:65:0D" to "Amazon", "FC:65:DE" to "Amazon"
    )

    private fun vendor(mac: String?): String {
        if (mac == null) return ""
        val first = mac.substring(0, 2).toInt(16)
        if (first and 2 != 0) return "عنوان عشوائي (خصوصية)"
        return oui[mac.substring(0, 8)] ?: ""
    }

    private fun devices(id: Int): JSONObject {
        val d = wifi().dhcpInfo
        if (d.ipAddress == 0) throw IllegalStateException("غير متصل بشبكة واي فاي")
        val base = ipStr(d.ipAddress).substringBeforeLast('.')
        val seen = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val done = AtomicInteger()
        val ex = Executors.newFixedThreadPool(48)
        fun report(ip: String, arp: Map<String, String>) {
            if (seen.putIfAbsent(ip, true) != null) return
            val a = InetAddress.getByName(ip)
            val h = try { a.hostName } catch (e: Exception) { ip }
            val mac = arp[ip]
            ev(id, "dev", JSONObject().put("ip", ip).put("host", if (h == ip) "" else h)
                .put("mac", mac ?: "").put("vendor", vendor(mac)))
        }
        val tasks = (1..254).map { i ->
            Callable {
                val ip = "$base.$i"
                try {
                    if (InetAddress.getByName(ip).isReachable(500)) report(ip, readArp())
                } catch (_: Exception) {
                }
                val n = done.incrementAndGet()
                if (n % 20 == 0) ev(id, "progress", n)
                Unit
            }
        }
        ex.invokeAll(tasks)
        ex.shutdown()
        val arp = readArp()
        for ((ip, _) in arp) if (ip.startsWith("$base.")) try { report(ip, arp) } catch (_: Exception) { }
        return JSONObject().put("count", seen.size).put("arp", arp.isNotEmpty())
    }

    private fun speed(id: Int): JSONObject {
        val base = "https://speed.cloudflare.com"
        ev(id, "phase", "ping")
        val pts = ArrayList<Double>()
        repeat(5) {
            val t = System.nanoTime()
            val c = URL("$base/__down?bytes=0").openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000
            c.inputStream.use { s -> s.readBytes() }
            c.disconnect()
            pts.add((System.nanoTime() - t) / 1e6)
        }
        val rest = pts.drop(1)
        val ping = rest.average()
        val jitter = rest.zipWithNext { a, b -> Math.abs(a - b) }.average()

        ev(id, "phase", "down")
        val dc = URL("$base/__down?bytes=50000000").openConnection() as HttpURLConnection
        dc.connectTimeout = 5000; dc.readTimeout = 8000
        val buf = ByteArray(65536)
        var total = 0L
        val st = System.nanoTime()
        var last = st
        dc.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                val now = System.nanoTime()
                if (now - last > 200_000_000L) {
                    ev(id, "mbps", total * 8 / 1e6 / ((now - st) / 1e9)); last = now
                }
                if (now - st > 8_000_000_000L) break
            }
        }
        dc.disconnect()
        val down = total * 8 / 1e6 / ((System.nanoTime() - st) / 1e9)

        ev(id, "phase", "up")
        val size = 10_000_000
        val chunk = ByteArray(65536)
        var sent = 0L
        val st2 = System.nanoTime()
        var last2 = st2
        try {
            val u = URL("$base/__up").openConnection() as HttpURLConnection
            u.requestMethod = "POST"; u.doOutput = true
            u.connectTimeout = 5000; u.readTimeout = 15000
            u.setFixedLengthStreamingMode(size)
            u.outputStream.use { o ->
                while (sent < size) {
                    val n = minOf(chunk.size.toLong(), size - sent).toInt()
                    o.write(chunk, 0, n); sent += n
                    val now = System.nanoTime()
                    if (now - last2 > 200_000_000L) {
                        ev(id, "mbps", sent * 8 / 1e6 / ((now - st2) / 1e9)); last2 = now
                    }
                }
            }
            u.responseCode
            u.disconnect()
        } catch (_: Exception) {
        }
        val up = sent * 8 / 1e6 / ((System.nanoTime() - st2) / 1e9)
        return JSONObject().put("down", down).put("up", up).put("ping", ping).put("jitter", jitter)
    }

    private fun usage(days: Int): JSONObject {
        val aops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            aops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else
            aops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return JSONObject().put("perm", false)

        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - days.coerceIn(1, 30) * 86_400_000L
        val map = HashMap<Int, Long>()
        val st = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, start, end)
        val b = NetworkStats.Bucket()
        while (st.hasNextBucket()) {
            st.getNextBucket(b)
            map[b.uid] = (map[b.uid] ?: 0L) + b.rxBytes + b.txBytes
        }
        st.close()
        val pm = ctx.packageManager
        fun label(uid: Int): String {
            val n = pm.getPackagesForUid(uid)
            if (n.isNullOrEmpty()) return when (uid) { 0 -> "النظام"; 1000 -> "نظام أندرويد"; else -> "UID $uid" }
            return try { pm.getApplicationLabel(pm.getApplicationInfo(n[0], 0)).toString() } catch (e: Exception) { n[0] }
        }
        val arr = JSONArray()
        var total = 0L
        for ((uid, bytes) in map.entries.sortedByDescending { it.value }) {
            total += bytes
            if (arr.length() < 10 && bytes > 0) arr.put(JSONObject().put("name", label(uid)).put("bytes", bytes))
        }
        return JSONObject().put("perm", true).put("apps", arr).put("total", total)
    }
}
