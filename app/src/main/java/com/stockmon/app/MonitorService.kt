package com.stockmon.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务：后台持续拉东方财富行情，越阈值直接弹高优先级 + 锁屏可见 的系统通知。
 * 支持涨跌幅 + 资金极速流（主力净流入占比）报警；
 * 突破后持续报警（每 ~20s 重报），点「停止报警」静音但每再同向变动 1% 续报，点「解除」彻底复位（回到阈值内才重新武装）。
 * 配合系统「忽略电池优化」后可长期存活（锁屏绝不漏报）。
 */
class MonitorService : Service() {

    companion object {
        const val CHANNEL_FG = "fg_channel"
        const val CHANNEL_ALERT = "alert_channel"
        const val NOTIF_FG_ID = 1
        const val ACTION_STOP = "com.stockmon.app.STOP_ALARM"
        const val ACTION_DISMISS = "com.stockmon.app.DISMISS_ALARM"
        const val EXTRA_SECID = "secid"
        const val EXTRA_CODE = "code"
        const val STEP_PCT = 1.0           // 停止后每再同向变动达到该百分比续报
        const val RE_ALARM_MS = 20000L     // 持续报警重报间隔
        const val US_THR = 0.8             // 美股三大指数涨跌幅报警阈值
        const val AUTO_FUND_MIN = 2.0      // 自动检测：占比绝对值需≥2% 才视为有效异动
        const val AUTO_FUND_RAPID = 1.5    // 自动检测：占比两次轮询变动≥1.5pp = 急速涌动
        val US_IDX = listOf("100.DJIA", "100.NDX", "100.SPX")
        val NEWS_BULL = listOf("利好","大涨","暴涨","涨停","上调","增持","回购","中标","签约","订单","降准","降息","增长","突破","新高","扭亏","获批","政策利好","央行","放量","上调评级","超预期","加仓")
        val NEWS_BEAR = listOf("利空","大跌","暴跌","跌停","下调","减持","退市","暴雷","亏损","警示","风险","处罚","立案","暂停","黑天鹅","制裁","关税","投诉","召回","停产","下调评级","违规")
    }

    private var handler: Handler? = null
    private var thread: HandlerThread? = null
    private var running = true
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StockMonitor::lock")
        wakeLock?.setReferenceCounted(false)
        val filter = IntentFilter().apply { addAction(ACTION_STOP); addAction(ACTION_DISMISS) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmReceiver, filter)
        }
    }

    private val alarmReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val secid = intent?.getStringExtra(EXTRA_SECID) ?: return
            val state = readActive()
            val st = state[secid] ?: return
            when (intent.action) {
                ACTION_STOP -> {
                    st.stopped = true
                    st.stepBase = st.lastPrice   // 从停止时的价格起算下一个 1% 步长
                    st.fundRef = st.lastFundPct  // 从停止时的占比起算资金续报
                    state[secid] = st
                    writeActive(state)
                }
                ACTION_DISMISS -> {
                    // 彻底复位：需回到阈值内才会重新武装
                    state[secid] = AlarmState(armed = false)
                    writeActive(state)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(secid.hashCode())
                }
            }
            Log.i("MonitorService", "${intent.action} : $secid")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_FG_ID, buildFgNotification("后台实时盯盘 · 锁屏也会报警"))
        if (thread == null) {
            thread = HandlerThread("MonitorThread").also { it.start() }
            handler = Handler(thread!!.looper)
            scheduleTick(0)
        }
        return START_STICKY
    }

    private fun scheduleTick(delayMs: Long) {
        handler?.postDelayed({ tick() }, delayMs)
    }

    private fun tick() {
        if (!running) return
        var interval = 5
        try {
            val cfg = readConfig()
            if (cfg != null) {
                val settings = cfg.optJSONObject("settings")
                interval = settings?.optInt("interval", 5) ?: 5
                val sound = settings?.optBoolean("sound", true) ?: true
                val vib = settings?.optBoolean("vibrate", true) ?: true
                val push = settings?.optBoolean("push", true) ?: true
                val stocks = cfg.optJSONArray("stocks")
                if (stocks != null && stocks.length() > 0) {
                val secids = ArrayList<String>()
                for (i in 0 until stocks.length()) {
                    val s = stocks.getJSONObject(i)
                    val secid = s.optString("secid", "")
                    if (secid.isNotEmpty()) secids.add(secid)
                }
                secids.addAll(US_IDX)            // 美股三大指数也拉取并报警
                val map = fetchQuotes(secids)
                    val state = readActive()
                    val now = System.currentTimeMillis()
                    for (i in 0 until stocks.length()) {
                        val s = stocks.getJSONObject(i)
                        val secid = s.optString("secid", "")
                        val code = s.optString("code", secid)
                        val name = s.optString("name", code)
                        val up = s.optDouble("up", 0.0)
                        val down = s.optDouble("down", 0.0)
                        val fund = s.optDouble("fund", 0.0)
                        val q = map[secid] ?: map[code] ?: continue
                        val price = q.first
                        val pct = q.second
                        val fundPct = q.third
                        val st = state[secid] ?: AlarmState()

                        // 资金自动检测基线（首轮仅建立基线，不判定）
                        if (!st.fundSeen) { st.lastFundPct = fundPct; st.fundSeen = true }

                        // 资金流触发判定：手动阈值 或 自动急速检测
                        val autoFund = fund <= 0
                        var fundInOk = false
                        var fundOutOk = false
                        if (fundPct != null) {
                            if (autoFund) {
                                val dpct = Math.abs(fundPct - st.lastFundPct)
                                val extreme = Math.abs(fundPct) >= AUTO_FUND_MIN && dpct >= AUTO_FUND_RAPID
                                if (extreme) { if (fundPct > 0) fundInOk = true else fundOutOk = true }
                            } else {
                                if (fundPct >= fund) fundInOk = true
                                else if (fundPct <= -fund) fundOutOk = true
                            }
                        }
                        st.lastFundPct = fundPct
                        st.lastPrice = price

                        val upOk = up > 0 && pct >= up
                        val downOk = down > 0 && pct <= -down
                        val breach = upOk || downOk || fundInOk || fundOutOk

                        if (!(st.up || st.down || st.fundIn || st.fundOut)) {
                            // 未报警：首次触发
                            if (breach && st.armed) {
                                st.up = upOk; st.down = downOk; st.fundIn = fundInOk; st.fundOut = fundOutOk
                                st.dir = if (upOk) 1 else if (downOk) -1 else if (fundInOk) 1 else -1
                                st.stepBase = price; st.fundRef = fundPct ?: 0.0; st.stopped = false; st.armed = false; st.last = 0
                                notifyBreach(name, code, secid, st, pct, fundPct, sound, vib, push)
                            } else if (!breach) {
                                st.armed = true
                            }
                        } else {
                            // 已报警
                            if (!st.stopped) {
                                if (!breach) {
                                    // 回到阈值内：复位并重新武装（与网页一致）
                                    st.up = false; st.down = false; st.fundIn = false; st.fundOut = false
                                    st.armed = true; st.stopped = false; st.last = 0L
                                } else if (st.last == 0L) {
                                    st.last = now
                                } else if (now - st.last >= RE_ALARM_MS) {
                                    st.last = now
                                    notifyBreach(name, code, secid, st, pct, fundPct, sound, vib, push)
                                }
                            } else {
                                // 停止后：价格每再同向变动 STEP_PCT% 或 资金继续同向急速 → 续报
                                val move = if (st.stepBase != 0.0) (price - st.stepBase) / st.stepBase * 100 * st.dir else 0.0
                                val fmove = if (fundPct != null) (fundPct - st.fundRef) * st.dir else 0.0
                                if (move >= STEP_PCT || fmove >= AUTO_FUND_RAPID) {
                                    st.stepBase = price
                                    st.fundRef = fundPct ?: 0.0
                                    st.stopped = false
                                    st.last = now
                                    notifyBreach(name, code, secid, st, pct, fundPct, sound, vib, push)
                                }
                            }
                        }
                        state[secid] = st
                    }
                    writeActive(state)
                    // 美股三大指数涨跌幅报警（≥US_THR%，回到阈值内复位）
                    val usState = readUsState()
                    for (u in US_IDX) {
                        val q = map[u] ?: continue
                        val pct = q.second
                        val st = usState[u] ?: AlarmState()
                        val breach = Math.abs(pct) >= US_THR
                        if (!(st.up || st.down)) {
                            if (breach && st.armed) {
                                st.up = pct > 0; st.down = pct < 0; st.armed = false
                                notifyUs(u, pct, sound, vib, push)
                            } else if (!breach) {
                                st.armed = true
                            }
                        } else if (!breach) {
                            st.up = false; st.down = false; st.armed = true
                        }
                        usState[u] = st
                    }
                    writeUsState(usState)
                    // 个股重大消息报警（仅针对所监控个股）
                    checkNewsNative(stocks, sound, vib, push)
                }
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "tick error", e)
        } finally {
            updateFg("后台实时盯盘 · 下次刷新约 ${interval}s 后")
            val delay = (if (interval < 2) 2 else interval) * 1000L
            scheduleTick(delay)
        }
    }

    private fun readConfig(): JSONObject? {
        return try {
            val txt = openFileInput("monitor_config.json").bufferedReader().use { it.readText() }
            JSONObject(txt)
        } catch (e: Exception) { null }
    }

    private fun readActive(): MutableMap<String, AlarmState> {
        val map = HashMap<String, AlarmState>()
        return try {
            val txt = openFileInput("active_alarms.json").bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            val it = j.keys()
            while (it.hasNext()) {
                val k = it.next()
                val o = j.getJSONObject(k)
                map[k] = AlarmState(
                    up = o.optBoolean("up", false),
                    down = o.optBoolean("down", false),
                    fundIn = o.optBoolean("fundIn", false),
                    fundOut = o.optBoolean("fundOut", false),
                    dir = o.optInt("dir", 1),
                    stepBase = o.optDouble("stepBase", 0.0),
                    lastPrice = o.optDouble("lastPrice", 0.0),
                    stopped = o.optBoolean("stopped", false),
                    armed = o.optBoolean("armed", true),
                    last = o.optLong("last", 0L),
                    lastFundPct = o.optDouble("lastFundPct", 0.0),
                    fundRef = o.optDouble("fundRef", 0.0),
                    fundSeen = o.optBoolean("fundSeen", false)
                )
            }
            map
        } catch (e: Exception) { map }
    }

    private fun writeActive(state: Map<String, AlarmState>) {
        try {
            val j = JSONObject()
            state.forEach { (k, v) ->
                j.put(k, JSONObject().put("up", v.up).put("down", v.down)
                    .put("fundIn", v.fundIn).put("fundOut", v.fundOut)
                    .put("dir", v.dir).put("stepBase", v.stepBase)
                    .put("lastPrice", v.lastPrice).put("stopped", v.stopped)
                    .put("armed", v.armed).put("last", v.last)
                    .put("lastFundPct", v.lastFundPct).put("fundRef", v.fundRef)
                    .put("fundSeen", v.fundSeen))
            }
            openFileOutput("active_alarms.json", Context.MODE_PRIVATE).use {
                it.write(j.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) { }
    }

    // 返回 code(或 secid) -> (price, pct, fundPct)
    private fun fetchQuotes(secids: List<String>): Map<String, Triple<Double, Double, Double>> {
        val result = HashMap<String, Triple<Double, Double, Double>>()
        if (secids.isEmpty()) return result
        var conn: HttpURLConnection? = null
        try {
            wakeLock?.acquire(10000)
            val url = "https://push2delay.eastmoney.com/api/qt/ulist.np/get?secids=" +
                    secids.joinToString(",") +
                    "&fields=f2,f3,f4,f12,f14,f62,f184&pn=1&pz=${secids.size}&forcedelay=1"
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            reader.use { r -> var line: String?; while (r.readLine().also { line = it } != null) sb.append(line) }
            val json = JSONObject(sb.toString())
            val data = json.optJSONObject("data")
            val diff = data?.optJSONArray("diff") ?: JSONArray()
            for (i in 0 until diff.length()) {
                val d = diff.optJSONObject(i) ?: continue
                val code = d.optString("f12", "")
                if (code.isEmpty()) continue
                val price = d.optDouble("f2", 0.0) / 100.0
                val pct = d.optDouble("f3", 0.0) / 100.0
                val fundPct = d.optDouble("f184", 0.0) / 100.0
                result[code] = Triple(price, pct, fundPct)
                val market = d.optString("f13", "")
                if (market.isNotEmpty()) result["$market.$code"] = Triple(price, pct, fundPct)
            }
        } catch (e: Exception) {
            Log.e("MonitorService", "fetch error", e)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        return result
    }

    private fun pickType(st: AlarmState): String {
        return when {
            st.up -> "up"
            st.down -> "down"
            st.fundIn -> "fundIn"
            else -> "fundOut"
        }
    }

    // ====== 美股三大指数报警 ======
    private fun notifyUs(secid: String, pct: Double, sound: Boolean, vib: Boolean, push: Boolean) {
        if (!push) return
        val name = when (secid) { "100.DJIA" -> "道指"; "100.NDX" -> "纳指"; else -> "标普" }
        val dir = if (pct > 0) "涨" else "跌"
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("📊 美股指数报警 · $name")
            .setContentText("${dir}幅 ${"%.2f".format(pct)}% 超过 $US_THR%")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        if (sound) builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        if (vib) builder.setVibrate(longArrayOf(0, 300, 150, 300))
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((secid + "us").hashCode(), builder.build())
    }

    private fun readUsState(): MutableMap<String, AlarmState> {
        val map = HashMap<String, AlarmState>()
        return try {
            val txt = openFileInput("us_alarms.json").bufferedReader().use { it.readText() }
            val j = JSONObject(txt); val it = j.keys()
            while (it.hasNext()) {
                val k = it.next(); val o = j.getJSONObject(k)
                map[k] = AlarmState(up = o.optBoolean("up", false), down = o.optBoolean("down", false), armed = o.optBoolean("armed", true))
            }
            map
        } catch (e: Exception) { map }
    }

    private fun writeUsState(state: Map<String, AlarmState>) {
        try {
            val j = JSONObject()
            state.forEach { (k, v) -> j.put(k, JSONObject().put("up", v.up).put("down", v.down).put("armed", v.armed)) }
            openFileOutput("us_alarms.json", Context.MODE_PRIVATE).use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) { }
    }

    // ====== 个股重大消息报警（仅针对所监控个股） ======
    data class NewsItem(val docid: String, val title: String, val intro: String, val intime: Long)

    private fun fetchNews(): List<NewsItem>? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = "https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2509&num=25&page=1&callback=smcb"
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.requestMethod = "GET"
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            reader.use { r -> var line: String?; while (r.readLine().also { line = it } != null) sb.append(line) }
            var s = sb.toString().trim()
            if (s.startsWith("smcb(")) s = s.substring(5)
            if (s.endsWith(")")) s = s.substring(0, s.length - 1)
            val json = JSONObject(s)
            val dataArr = json.optJSONObject("result")?.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<NewsItem>()
            for (i in 0 until dataArr.length()) {
                val d = dataArr.optJSONObject(i) ?: continue
                out.add(NewsItem(d.optString("docid", ""), d.optString("title", ""), d.optString("intro", ""), d.optLong("intime", 0L)))
            }
            out
        } catch (e: Exception) { null } finally { try { conn?.disconnect() } catch (e: Exception) { } }
    }

    private fun checkNewsNative(stocks: JSONArray, sound: Boolean, vib: Boolean, push: Boolean) {
        if (stocks.length() == 0) return
        val items = fetchNews() ?: return
        val names = (0 until stocks.length()).map { stocks.getJSONObject(it).optString("name", "") }.filter { it.isNotEmpty() }
        var last = readNewsLast()
        val seen = HashSet<String>()
        var newLast = last
        for (it in items) {
            if (it.intime <= last) continue
            if (!seen.add(it.docid)) continue
            if (it.intime > newLast) newLast = it.intime
            val txt = it.title + it.intro
            val isBull = NEWS_BULL.any { txt.contains(it) }
            val isBear = NEWS_BEAR.any { txt.contains(it) }
            if (!isBull && !isBear) continue
            val matched = names.filter { txt.contains(it) }
            if (matched.isEmpty()) continue
            val kind = if (isBull && isBear) "利好/利空" else if (isBull) "利好" else "利空"
            for (name in matched) notifyNews(name, kind, it.title, sound, vib, push)
        }
        if (newLast > last) writeNewsLast(newLast)
    }

    private fun notifyNews(name: String, kind: String, title: String, sound: Boolean, vib: Boolean, push: Boolean) {
        if (!push) return
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("📢 $name $kind 消息")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        if (sound) builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        if (vib) builder.setVibrate(longArrayOf(0, 300, 150, 300))
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((name + kind + title).hashCode(), builder.build())
    }

    private fun readNewsLast(): Long {
        return try { openFileInput("news_last.txt").bufferedReader().use { it.readText().toLong() } } catch (e: Exception) { 0L }
    }
    private fun writeNewsLast(v: Long) {
        try { openFileOutput("news_last.txt", Context.MODE_PRIVATE).use { it.write(v.toString().toByteArray(Charsets.UTF_8)) } } catch (e: Exception) { }
    }

    private fun notifyBreach(name: String, code: String, secid: String, st: AlarmState, pct: Double, fundPct: Double, sound: Boolean, vib: Boolean, push: Boolean) {
        if (!push) return
        val type = pickType(st)
        val (title, body) = when (type) {
            "up" -> "📈 涨幅预警" to "$name（$code）涨幅 +${"%.2f".format(pct)}%"
            "down" -> "📉 跌幅预警" to "$name（$code）跌幅 ${"%.2f".format(pct)}%"
            "fundIn" -> "💰 资金极速流入" to "$name（$code）主力净流入占比 +${"%.2f".format(fundPct)}%"
            else -> "💸 资金极速流出" to "$name（$code）主力净流入占比 ${"%.2f".format(fundPct)}%"
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, (code + type).hashCode(),
            launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(ACTION_STOP).setPackage(packageName)
            .putExtra(EXTRA_SECID, secid).putExtra(EXTRA_CODE, code)
        val stopPi = PendingIntent.getBroadcast(
            this, (code + "stop").hashCode(), stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = Intent(ACTION_DISMISS).setPackage(packageName)
            .putExtra(EXTRA_SECID, secid).putExtra(EXTRA_CODE, code)
        val dismissPi = PendingIntent.getBroadcast(
            this, (code + "dismiss").hashCode(), dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body + if (st.stopped) "（已停，监控中）" else "")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(false)
            .addAction(R.drawable.ic_notify, "停止报警", stopPi)
            .addAction(R.drawable.ic_notify, "解除", dismissPi)
        if (sound) builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        if (vib) builder.setVibrate(longArrayOf(0, 300, 150, 300, 150, 500))
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(secid.hashCode(), builder.build())
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val fg = NotificationChannel(CHANNEL_FG, "监控常驻", NotificationManager.IMPORTANCE_LOW).apply {
                description = "股票监控后台运行常驻通知"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(fg)
            val alert = NotificationChannel(CHANNEL_ALERT, "涨跌报警", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "股票涨跌幅/资金流达到阈值的报警"
                setShowBadge(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowBubbles(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            mgr.createNotificationChannel(alert)
        }
    }

    private fun buildFgNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_FG)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🐂 股票监控运行中")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .build()
    }

    private fun updateFg(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_FG_ID, buildFgNotification(text))
    }

    override fun onDestroy() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        try { unregisterReceiver(alarmReceiver) } catch (e: Exception) { }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class AlarmState(
        var up: Boolean = false,
        var down: Boolean = false,
        var fundIn: Boolean = false,
        var fundOut: Boolean = false,
        var dir: Int = 1,
        var stepBase: Double = 0.0,
        var lastPrice: Double = 0.0,
        var stopped: Boolean = false,
        var armed: Boolean = true,
        var last: Long = 0L,
        var lastFundPct: Double = 0.0,
        var fundRef: Double = 0.0,
        var fundSeen: Boolean = false
    )
}
