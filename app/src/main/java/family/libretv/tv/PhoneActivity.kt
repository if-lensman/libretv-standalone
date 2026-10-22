package family.libretv.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.CastOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Touch UI; NAS sessions and protocol implementation are shared with the TV app/library. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PhoneActivity : Activity() {
    companion object {
        private var castOwner: java.lang.ref.WeakReference<PhoneActivity>? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val castCommands = Mutex()
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var notice: TextView
    private lateinit var client: NasClient
    private val prefs by lazy { getSharedPreferences("phone-ui", MODE_PRIVATE) }
    private var sources = JSONArray()
    private var results = JSONArray()
    private var episodes = JSONArray()
    private var selected = JSONObject()
    private var source = JSONObject()
    private var title = ""
    private var query = ""
    private var page = "login"
    private var generation = 0
    private var busy = false
    private var device: DLNACast.Device? = null
    private val posters = PosterLoader()
    private val castPrefs by lazy { getSharedPreferences("phone-cast", MODE_PRIVATE) }
    private var queue: PhoneCastQueue? = null
    private val castTitle get() = queue?.label.orEmpty()
    private var attached = false
    private var knownDevices = emptySet<String>()
    private var detailInfo = JSONObject()
    private var searchJob: Job? = null
    private var searchToken = 0
    private var searching = false
    private var searchCompleted = 0
    private var searchTotal = 0
    private var searchFailed = 0
    private var searchStatus: TextView? = null
    private var resultHeading: TextView? = null
    private var resultCards: LinearLayout? = null
    private var moreResults: Button? = null
    private var renderedResults = 0
    private var shownResults = 30
    private var pendingExport: String? = null
    private var batchRunning = false
    private var batchStopRequested = false
    private val backgroundColor = Color.rgb(12, 20, 29)
    private val panelColor = Color.rgb(24, 37, 50)
    private val accentColor = Color.rgb(107, 226, 195)
    private val secondaryColor = Color.rgb(160, 180, 196)
    private var castDevice: DLNACast.Device? = null
    private var progress: TextView? = null
    private var polling: Job? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DLNACast.init(applicationContext)
        castOwner = java.lang.ref.WeakReference(this)
        try { client = NasClient(this); sources = client.local.sources(); restoreCast(); home() }
        catch(e: Exception) { login(); notice.text = e.message }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun surface(color: Int = panelColor, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(2), dp(5), dp(2), dp(5))
    }
    private fun screen(name: String, heading: String) {
        generation++; busy = false; page = name; polling?.cancel(); progress = null
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(backgroundColor); clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(24))
        }
        scroll.addView(content); setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        content.addView(text(heading, 25f).apply { setTypeface(typeface, Typeface.BOLD) })
        notice = text("", 14f).apply {
            setTextColor(accentColor); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { visibility = if(s.isNullOrEmpty()) View.GONE else View.VISIBLE }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        content.addView(notice)
    }
    private fun section(heading: String? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = surface(); setPadding(dp(12), dp(12), dp(12), dp(12))
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if(heading != null) addView(text(heading, 17f).apply { setTypeface(typeface, Typeface.BOLD) })
    }
    private fun button(label: String, parent: LinearLayout = content, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false; minHeight = dp(48)
        setTextColor(if(primary) backgroundColor else Color.WHITE)
        background = RippleDrawable(ColorStateList.valueOf(0x336BE2C3), surface(if(primary) accentColor else Color.rgb(34, 51, 66), 12), null)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setOnClickListener { if (!busy) action() }
    }
    private fun buttonRow(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun rowButton(row: LinearLayout, label: String, enabled: Boolean = true, action: () -> Unit): Button {
        val b = button(label, row, action = action)
        b.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) }
        b.isEnabled = enabled; b.alpha = if(enabled) 1f else .35f
        return b
    }
    private fun input(hintText: String, value: String, password: Boolean = false, parent: LinearLayout = content) = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(Color.WHITE); setHintTextColor(secondaryColor)
        isSingleLine = true; isSaveEnabled = false; textSize = 16f
        inputType = if(password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(56)))
    }
    private fun poster(parent: LinearLayout, url: String, width: Int, height: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; background = surface(); clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(width, height))
        if(::client.isInitialized) posters.load(this, client, url) else setImageResource(R.drawable.poster_placeholder)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun posterGrid(items: List<JSONObject>, parent: LinearLayout) {
        val columns = if(resources.configuration.fontScale > 1.3f) 1 else if(resources.configuration.screenWidthDp >= 600) 3 else 2
        val width = (resources.configuration.screenWidthDp - 36 - 24 - columns * 8) / columns
        for(chunk in items.chunked(columns)) {
            val row = buttonRow(parent)
            for(item in chunk) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; background = surface(Color.rgb(31, 47, 63), 12)
                    isClickable = true; isFocusable = true; clipToOutline = true
                    foreground = RippleDrawable(ColorStateList.valueOf(0x336BE2C3), null, surface())
                    contentDescription = item.optString("name") + "，" + item.optString("sourceName")
                    setOnClickListener { if(!busy) detail(item) }
                }
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(8), dp(4), dp(4)) })
                poster(card, item.optString("pic"), -1, dp((width * 1.4f).toInt()))
                card.addView(text(item.optString("name"), 16f).apply { maxLines = 2; setPadding(dp(8), dp(8), dp(8), 0); setTypeface(typeface, Typeface.BOLD) })
                card.addView(text(item.optString("sourceName"), 12f).apply { setTextColor(accentColor); maxLines = 1; setPadding(dp(8), dp(4), dp(8), 0) })
                card.addView(text(item.optString("remarks"), 12f).apply { setTextColor(secondaryColor); maxLines = 1; setPadding(dp(8), 0, dp(8), dp(8)) })
            }
            repeat(columns - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
    }
    private fun hero(parent: LinearLayout, name: String, cover: String, subtitle: String) {
        val row = buttonRow(parent)
        poster(row, cover, dp(94), dp(138))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(text(name, 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        info.addView(text(subtitle, 14f).apply { setTextColor(secondaryColor) })
    }
    private fun hideKeyboard() {
        currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
    }
    private fun work(hint: String, block: suspend () -> Unit) {
        if (busy) return
        val ticket = generation; busy = true; notice.text = hint
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(ticket == generation) notice.text = when(e) {
                is NasClient.ApiException -> if(e.status == 401) "登录已失效，请返回重新登录" else "NAS 请求失败（${e.status}）"
                is IllegalArgumentException -> e.message ?: "参数错误"
                else -> e.message ?: "操作失败，请检查源站或接收设备"
            } }
            finally { if(ticket == generation) busy = false }
        }
    }
    private suspend fun request(path: String, body: JSONObject? = null, method: String = if(body == null) "GET" else "POST"): JSONObject =
        withContext(Dispatchers.IO) { client.request(path, body, method) }

    private fun login(automatic: Boolean = false) {
        screen("config", "来源配置")
        content.addView(text("内置来源可启停和删除，也可扫码导入自己的源。无需 NAS。", 14f))
        val box = section("导入订阅 / JSON")
        button("扫码配置源", box, true) { startActivityForResult(android.content.Intent(this,SourceScannerActivity::class.java),412) }
        button("显示来源二维码", box) {
            val payload=try { SourceQrDecoder.sharePayload(JSONObject(client.local.exportSources()).toString()) } catch(e: Exception) { notice.text=e.message; return@button }
            val image=ImageView(this).apply { setImageBitmap(PairingQr.create(payload,720)); adjustViewBounds=true; setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(Color.WHITE) }
            dialog=AlertDialog.Builder(this).setTitle("扫码导入本机来源").setView(image).setPositiveButton("关闭",null).show()
        }
        val config = input("订阅地址或 sources JSON", "", parent = box).apply { isSingleLine = false; minLines = 2; maxLines = 6; layoutParams.height = dp(120) }
        button("导入配置", box, true) {
            hideKeyboard()
            work("正在导入…") {
                val value = config.text.toString()
                val count = withContext(Dispatchers.IO) { client.local.importSources(value) }
                sources = client.local.sources(); login(); notice.text = "已新增 $count 个源"
            }
        }
        button("从文件导入", box) {
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(android.content.Intent.CATEGORY_OPENABLE) }, 410)
        }
        val manual = section("手动添加 CMS 源")
        val name = input("源名称", "", parent = manual)
        val url = input("CMS API 地址", "", parent = manual)
        button("添加来源", manual) {
            val value = JSONObject().put("name", name.text.toString().ifBlank { "自定义源" }).put("url",url.text.toString()).toString()
            work("正在保存…") {
                withContext(Dispatchers.IO) { client.local.importSources(value) }
                sources=client.local.sources(); login(); notice.text="已保存"
            }
        }
        button("导出来源配置", box) {
            pendingExport = client.local.exportSources()
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"; addCategory(android.content.Intent.CATEGORY_OPENABLE); putExtra(android.content.Intent.EXTRA_TITLE,"libretv-sources.json")
            }, 411)
        }
        val saved = section("已保存 · ${sources.length()} 个源")
        for(i in 0 until sources.length()) {
            val item=sources.getJSONObject(i)
            CheckBox(this).apply {
                text = item.optString("name"); setTextColor(Color.WHITE); isChecked = item.optBoolean("enabled",true)
                saved.addView(this)
                setOnCheckedChangeListener { _, enabled ->
                    work("正在保存…") { withContext(Dispatchers.IO) { client.local.setSourceEnabled(item.getString("key"),enabled) }; sources=client.local.sources(); notice.text="已保存" }
                }
            }
            button("删除 · ${item.optString("name")}", saved) {
                dialog = AlertDialog.Builder(this).setMessage("删除 ${item.optString("name")}？重启后不会自动补回。")
                    .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ ->
                        work("正在删除…") { withContext(Dispatchers.IO) { client.local.removeSource(item.getString("key")) }; sources=client.local.sources(); results=JSONArray(); login() }
                    }.show()
            }
        }
        button("返回选片") { home() }
    }
    @Deprecated("Activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==412 && resultCode==RESULT_OK) {
            data?.getStringExtra("sourceConfig")?.let { confirmScannedSources(it) }
        }
        if(requestCode==411) {
            val payload=pendingExport; pendingExport=null
            if(resultCode==RESULT_OK && data?.data!=null && payload!=null) {
                val uri=data.data!!
                work("正在导出…") {
                    withContext(Dispatchers.IO) { (contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入文件")).use { it.write(payload.toByteArray(Charsets.UTF_8)) } }
                    notice.text="配置已导出，可分享给其他设备"
                }
            }
        }
        if(requestCode==410 && resultCode==RESULT_OK && data?.data!=null) {
            val uri=data.data!!
            work("正在读取配置…") {
                val count=withContext(Dispatchers.IO) {
                    val input=contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取文件")
                    client.local.importSources(LocalRepository.read(input,1024*1024))
                }
                sources=client.local.sources(); login(); notice.text="已新增 $count 个源"
            }
        }
    }
    private fun confirmScannedSources(value: String) {
        val preview=if(value.startsWith("http")) "订阅地址：\n$value\n\n确认后才会下载并导入。" else "来源 JSON：\n${value.take(600)}"
        dialog=AlertDialog.Builder(this).setTitle("导入扫码来源？").setMessage(preview)
            .setNegativeButton("取消",null).setPositiveButton("确认导入") { _,_ ->
                work("正在导入扫码来源…") {
                    val count=withContext(Dispatchers.IO) { client.local.importSources(value) }
                    sources=client.local.sources(); login(); notice.text="扫码配置完成，新增 $count 个来源"
                }
            }.show()
    }
    private fun deviceButton(parent: LinearLayout) {
        button(device?.let { "投屏设备 · ${it.name}" } ?: "选择投屏设备", parent) { chooseDevice() }
    }
    private fun home() {
        screen("home", "LibreTV · 选片")
        content.addView(text("独立运行 · 无需 NAS · ${(0 until sources.length()).count { sources.getJSONObject(it).optBoolean("enabled",true) }} / ${sources.length()} 个来源启用", 14f).apply { setTextColor(secondaryColor) })
        val recent = PlaybackStore(this@PhoneActivity).all().optJSONObject(0)
        if(recent != null) {
            val watching=section("继续观看")
            button("${recent.optString("title")} · 第 ${recent.optInt("index")+1} 集", watching, true) {
                detail(JSONObject(recent.getJSONObject("item").toString()).put("favoriteSource",recent.getJSONObject("source")))
            }
        }
        val connection = section()
        deviceButton(connection)
        if(queue != null) button("投屏控制 · $castTitle", connection, true) { controls() }
        val searchBox = section("找点想看的")
        val row = buttonRow(searchBox)
        val keywords = input("搜索电影、剧集", query, parent = row).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }
        fun search() {
            hideKeyboard(); query = keywords.text.toString().trim()
            if(query.isEmpty() || query.length > 100) { notice.text = "请输入 1–100 字片名"; return }
            startSearch()
        }
        button("搜索", row, true) { search() }.layoutParams = LinearLayout.LayoutParams(dp(74), dp(48)).apply { leftMargin = dp(8); topMargin = dp(4) }
        keywords.setOnEditorActionListener { _,action,_ -> if(action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        val links = buttonRow(content)
        rowButton(links, "本机收藏夹") { favorites() }
        rowButton(links, "来源配置") { login() }
        button("播放列表", primary = true) { playlistScreen() }
        button("观看历史") { historyScreen() }
        if(BuildConfig.APPLICATION_ID == "family.libretv.tv") button("应用更新（可选 NAS）") {
            startActivity(android.content.Intent(this,LegacyUpdateActivity::class.java))
        }
        if(query.isNotEmpty()) button(if(searching) "查看搜索进度 · ${results.length()} 项" else "查看搜索结果 · ${results.length()} 项") { searchScreen() }
    }
    private fun startSearch() {
        searchJob?.cancel(); val token = ++searchToken
        val keyword = query
        val selectedSources = JSONArray(sources.toString())
        results = JSONArray(); shownResults = 30; searchCompleted = 0; searchFailed = 0
        searchTotal = (0 until selectedSources.length()).count { selectedSources.getJSONObject(it).optBoolean("enabled", true) }
        searching = searchTotal > 0
        searchScreen()
        if(!searching) { notice.text = "请先在来源配置中至少启用一个来源"; return }
        searchJob = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    client.local.search(keyword, selectedSources) { added, completed, total, failed ->
                        scope.launch(Dispatchers.Main) update@{
                            if(token != searchToken) return@update
                            for(i in 0 until added.length()) results.put(added.getJSONObject(i))
                            searchCompleted = completed; searchTotal = total; searchFailed = failed
                            if(page == "search") updateSearchResults()
                        }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(token == searchToken && page == "search") notice.text = e.message ?: "搜索失败，请重试" }
            finally {
                if(token == searchToken) { searching = false; if(page == "search") updateSearchResults() }
            }
        }
    }
    private fun searchScreen() {
        screen("search", "搜索 · $query")
        val nav = buttonRow(content)
        rowButton(nav, "返回选片") { home() }
        rowButton(nav, "停止搜索", searching) {
            ++searchToken; searchJob?.cancel(); searching = false; updateSearchResults()
        }
        searchStatus = text("").also { content.addView(it) }
        val box = section()
        resultHeading = text("搜索结果",17f).also { box.addView(it) }
        resultCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; box.addView(this) }
        renderedResults = 0
        moreResults = button("再显示 30 项",box) { shownResults += 30; updateSearchResults() }
        updateSearchResults()
    }
    private fun updateSearchResults() {
        if(page != "search") return
        searchStatus?.text = if(searching) "正在搜索 · $searchCompleted / $searchTotal 个来源完成 · $searchFailed 个不可用\n结果会陆续出现，可直接点开观看"
            else "搜索已结束 · $searchCompleted / $searchTotal 个来源完成 · $searchFailed 个不可用"
        resultHeading?.text = if(results.length() == 0) {
            if(searching) "正在等待首个结果…" else "暂无结果，可更换片名或检查来源"
        } else "搜索结果 · ${results.length()}"
        val end = minOf(results.length(),shownResults)
        // Append only: existing cards, scroll position and touch targets stay in place.
        if(end > renderedResults) {
            resultCards?.let { posterGrid((renderedResults until end).map { results.getJSONObject(it) },it) }
            renderedResults = end
        }
        moreResults?.visibility = if(results.length() > shownResults) View.VISIBLE else View.GONE
    }

    private fun detail(item: JSONObject, continuePlayback: Boolean = false) {
        work("正在获取剧集…") {
            source = item.optJSONObject("favoriteSource") ?: (0 until sources.length()).map { sources.getJSONObject(it) }
                .firstOrNull { it.optString("key") == item.optString("sourceKey") } ?: throw IllegalArgumentException("来源已失效，请重新搜索")
            val result = request("/api/detail?id=${NasClient.encode(item.optString("vodId"))}&source=${NasClient.encode(source.toString())}")
            episodes = result.optJSONArray("episodes") ?: JSONArray()
            require(episodes.length()>0) { "暂无可播放剧集" }
            detailInfo = result.optJSONObject("videoInfo") ?: JSONObject()
            selected = item; title = detailInfo.optString("title",item.optString("name"))
            details()
            if(continuePlayback) {
                val saved = PlaybackStore(this@PhoneActivity).find(source,selected)
                val index = saved?.optInt("index",0) ?: 0
                if(index in 0 until episodes.length()) playLocal(index)
                else notice.text = "来源剧集数量已变化，请重新选集"
            }
        }
    }
    private fun details() {
        screen("detail", "影片详情")
        val navigation = buttonRow(content)
        rowButton(navigation, "返回选片") { home() }
        if(query.isNotEmpty()) rowButton(navigation, "搜索结果") { searchScreen() }
        if(queue != null) rowButton(navigation, "投屏控制") { controls() }
        val info = section()
        hero(info, title, detailInfo.optString("cover").ifEmpty { selected.optString("pic") }, "${selected.optString("sourceName")} · 共 ${episodes.length()} 集")
        val favoriteEntries = client.local.favorites("/api/favorites",null,"GET").optJSONArray("items") ?: JSONArray()
        val favoriteId=(0 until favoriteEntries.length()).map { favoriteEntries.getJSONObject(it) }
            .firstOrNull { it.optJSONObject("source")?.optString("url")==source.optString("url") && it.optJSONObject("item")?.optString("vodId")==selected.optString("vodId") }?.optString("id")
        button(if(favoriteId==null) "加入本机收藏" else "取消收藏", info) {
            work("正在保存收藏…") {
                withContext(Dispatchers.IO) {
                    if(favoriteId==null) client.local.favorites("/api/favorites",JSONObject().put("source",source).put("item",selected),"POST")
                    else client.local.favorites("/api/favorites/$favoriteId",null,"DELETE")
                }
                details(); notice.text=if(favoriteId==null) "已加入本机收藏" else "已取消收藏"
            }
        }
        val listed = PlaybackStore(this@PhoneActivity).contains(source,selected)
        button(if(listed) "移出播放列表" else "加入播放列表",info) {
            work("正在保存播放列表…") {
                withContext(Dispatchers.IO) {
                    val store = PlaybackStore(this@PhoneActivity)
                    if(listed) store.remove(PlaybackStore.identity(source,selected)) else store.add(source,selected,title)
                }
                details(); notice.text = if(listed) "已移出播放列表，观看进度保留" else "已加入播放列表"
            }
        }
        val saved=PlaybackStore(this@PhoneActivity).find(source,selected)
        if(saved!=null && saved.optInt("index") in 0 until episodes.length()) {
            val index=saved.optInt("index")
            info.addView(text("上次看到第 ${index+1} 集 · ${clock(saved.optLong("position"))} / ${clock(saved.optLong("duration"))}",14f))
            button("继续播放 · 第 ${index+1} 集", info, true) { playLocal(index) }
        }
        val target = section(); deviceButton(target)
        val box = section("选择剧集 · 共 ${episodes.length()} 集")
        val columns = if(resources.configuration.fontScale > 1.3f) 2 else 3
        for(start in 0 until episodes.length() step columns) {
            val row = buttonRow(box)
            for(i in start until minOf(start + columns, episodes.length())) rowButton(row, "第 ${i+1} 集") {
                AlertDialog.Builder(this).setTitle("第 ${i+1} 集").setItems(arrayOf("手机播放", "投屏到电视")) { _, which ->
                    if(which==0) playLocal(i)
                    else if(device == null) chooseDevice { cast(i) } else cast(i)
                }.show()
            }
            repeat(maxOf(0, start + columns - episodes.length())) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
    }
    private fun playLocal(index: Int) {
        val urls=(0 until episodes.length()).map { episodes.getString(it) }
        if(urls.sumOf { it.length } > 300000) { notice.text="剧集地址过长，请换一个来源"; return }
        startActivity(android.content.Intent(this,DirectPlayerActivity::class.java)
            .putStringArrayListExtra("episodes",ArrayList(urls)).putExtra("index",index).putExtra("title",title)
            .putExtra("source",source.toString()).putExtra("item",selected.toString()))
    }
    private fun historyScreen() {
        screen("history","观看历史");button("返回选片") { home() }
        val history=PlaybackStore(this@PhoneActivity).all()
        if(history.length()==0) content.addView(text("还没有观看记录"))
        for(i in 0 until history.length()) {
            val entry=history.getJSONObject(i)
            button("${entry.optString("title")} · 第 ${entry.optInt("index")+1} 集 · ${clock(entry.optLong("position"))}") {
                detail(JSONObject(entry.getJSONObject("item").toString()).put("favoriteSource",entry.getJSONObject("source")))
            }
        }
        if(history.length()>0) button("清空观看历史") {
            dialog=AlertDialog.Builder(this).setMessage("清空历史列表？收藏、来源和播放列表中的进度会保留。")
                .setNegativeButton("取消",null).setPositiveButton("清空") { _,_ -> PlaybackStore(this@PhoneActivity).clear(); historyScreen() }.show()
        }
    }
    private fun playlistScreen() {
        screen("playlist","播放列表"); button("返回选片") { home() }
        content.addView(text("仅保存在本机 · 开始播放后自动加入，也可在详情页手动添加。移除条目会保留观看历史。",14f))
        val store = PlaybackStore(this@PhoneActivity); val entries = store.playlist()
        if(entries.length()==0) content.addView(text("播放列表为空，先搜索一部想看的影片吧"))
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i); val item=entry.getJSONObject("item"); val src=entry.getJSONObject("source")
            val saved=store.find(src,item); val name=entry.optString("title",item.optString("name"))
            val box=section()
            hero(box,name,item.optString("pic"),src.optString("name"))
            box.addView(text(if(saved==null) "尚未播放" else "上次看到第 ${saved.optInt("index")+1} 集 · ${clock(saved.optLong("position"))} / ${clock(saved.optLong("duration"))}",14f))
            fun queuedItem() = JSONObject(item.toString()).put("favoriteSource",src)
            button(if(saved==null) "开始播放 · $name" else "继续播放 · $name",box,true) { detail(queuedItem(),true) }
            button("查看剧集 · $name",box) { detail(queuedItem()) }
            button("移出列表 · $name",box) {
                work("正在移除…") { withContext(Dispatchers.IO) { store.remove(entry.getString("id")) }; playlistScreen() }
            }
        }
    }
    private fun favorites() {
        try {
            val items = client.local.favorites("/api/favorites",null,"GET").optJSONArray("items") ?: JSONArray()
            screen("favorites","本机收藏夹"); button("返回选片") { home() }
            content.addView(text("收藏仅保存在本机，无需 NAS、账号或服务器。",14f))
            val box = section("${items.length()} 部收藏")
            val entries = (0 until items.length()).map { i ->
                val entry = items.getJSONObject(i); val item = JSONObject(entry.getJSONObject("item").toString())
                val saved=PlaybackStore(this@PhoneActivity).find(entry.getJSONObject("source"),item)
                if(saved!=null) item.put("remarks","第 ${saved.optInt("index")+1} 集 · ${clock(saved.optLong("position"))}")
                item.put("favoriteSource",entry.getJSONObject("source"))
            }
            posterGrid(entries, box)
            for(i in 0 until items.length()) {
                val entry=items.getJSONObject(i); val item=entry.getJSONObject("item"); val src=entry.getJSONObject("source")
                val name=item.optString("name")
                button("加入播放列表 · $name") {
                    work("正在保存播放列表…") { withContext(Dispatchers.IO) { PlaybackStore(this@PhoneActivity).add(src,item,name) }; notice.text="已加入播放列表" }
                }
                button("移除收藏 · $name") {
                    work("正在移除收藏…") { withContext(Dispatchers.IO) { client.local.favorites("/api/favorites/${entry.getString("id")}",null,"DELETE") }; favorites() }
                }
            }
            if(items.length()==0) notice.text = "暂无收藏，在影片详情页点击加入本机收藏"
        } catch(e: Exception) { notice.text = e.message ?: "本机收藏读取失败" }
    }
    private fun chooseDevice(after: (() -> Unit)? = null) {
        work("正在搜索局域网设备（约 8 秒）…") {
            val discovered = castCommands.withLock { DLNACast.search(8000) }
            knownDevices = knownDevices + discovered.map { it.id }
            val devices = ReceiverDiscovery.receivers(discovered)
            if(devices.isEmpty()) { notice.text = "没有找到接收设备。请检查电视果已开机、手机 Wi-Fi 和路由器的设备隔离设置。"; return@work }
            notice.text = "找到 ${devices.size} 个接收设备"
            dialog = AlertDialog.Builder(this@PhoneActivity).setTitle("选择投屏设备")
                .setItems(devices.map { "${it.name} · ${it.address}" }.toTypedArray()) { _,index ->
                    device = devices[index]; notice.text = "已选择 ${devices[index].name}"
                    if(after != null) after()
                    else when(page) { "home" -> home(); "detail" -> details() }
                }.setNegativeButton("取消",null).show()
        }
    }
    private fun restoreCast() {
        if(castPrefs.getString("nas", "") != client.origin.toString()) { queue = null; castDevice = null; attached = false; return }
        if(queue != null) return
        try {
            val data = JSONObject(castPrefs.getString("session", "") ?: "")
            val urls = data.getJSONArray("urls")
            require(urls.length() in 1..2000)
            queue = PhoneCastQueue(data.getString("title"), (0 until urls.length()).map { urls.getString(it) }, data.getInt("index"), data.optString("poster"), data.getJSONObject("item").toString(), data.getJSONObject("source").toString())
            val target = data.getJSONObject("device")
            castDevice = DLNACast.Device(target.getString("id"), target.getString("name"), target.getString("address"), false)
            device = castDevice; attached = false
        } catch(e: Exception) { queue = null; castDevice = null; castPrefs.edit().clear().apply() }
    }
    private suspend fun saveCast() {
        val q = queue ?: return
        val target = castDevice ?: return
        val data = JSONObject().put("title", q.title).put("urls", JSONArray(q.urls)).put("index", q.index).put("poster", q.poster)
            .put("item", JSONObject(q.itemJson)).put("source", JSONObject(q.sourceJson))
            .put("device", JSONObject().put("id", target.id).put("name", target.name).put("address", target.address))
        val origin = client.origin.toString()
        withContext(Dispatchers.IO) { castPrefs.edit().putString("nas", origin).putString("session", data.toString()).commit() }
    }
    private suspend fun resolveTarget(target: DLNACast.Device): DLNACast.Device {
        if(target.id in knownDevices) return target
        val found = DLNACast.search(8000)
        knownDevices = knownDevices + found.map { it.id }
        return found.firstOrNull { it.id == target.id || (it.address == target.address && it.name == target.name) }
            ?: throw IllegalArgumentException("未找到上次投屏的设备，请确认设备开机且仍在同一局域网")
    }
    private suspend fun requestCast(next: PhoneCastQueue, target: DLNACast.Device): DLNACast.Device {
        val url = CastUrl.direct(next.urls[next.index], client.origin)
        val mime = if(java.net.URI(url).path.orEmpty().endsWith(".m3u8",true)) "application/vnd.apple.mpegurl" else "video/mp4"
        return castCommands.withLock {
            val receiver = resolveTarget(target)
            DLNACast.clearProgressCache()
            val accepted = DLNACast.castToDevice(receiver, url, next.label, CastOptions(mimeType = mime))
            require(accepted) { "投屏未成功：连接、播放指令或来源可能不兼容，请重试或更换来源" }
            receiver
        }
    }
    private suspend fun sendQueue(next: PhoneCastQueue, target: DLNACast.Device) {
        val resolved = requestCast(next, target)
        if(queue?.urls != next.urls || queue?.sourceJson != next.sourceJson) castPrefs.edit().remove("batch").apply()
        // Commit the episode only after the receiver accepted it; failed switches preserve the old controls.
        queue = next; castDevice = resolved; device = resolved; attached = true
        saveCast(); controls()
        notice.text = "已发送第 ${next.index+1} 集；请以电视实际播放为准。"
    }
    private fun cast(index: Int) {
        val snapshot = PhoneCastQueue(title, (0 until episodes.length()).map { episodes.getString(it) }, index,
            detailInfo.optString("cover").ifEmpty { selected.optString("pic") }, selected.toString(), source.toString())
        work("正在发送投屏请求…") { sendQueue(snapshot, device ?: throw IllegalArgumentException("请先选择设备")) }
    }
    private fun adjacent(delta: Int) {
        val q = queue ?: return
        val index = q.adjacent(delta) ?: return
        val target = castDevice ?: return
        work("正在切换到第 ${index+1} 集…") { sendQueue(q.at(index), target) }
    }
    private fun queueDetails() {
        val q = queue ?: return
        selected = JSONObject(q.itemJson); source = JSONObject(q.sourceJson); title = q.title
        episodes = JSONArray(q.urls); detailInfo = JSONObject().put("cover", q.poster)
        details()
    }
    private fun batchRecord(q: PhoneCastQueue): JSONObject? = try {
            val record = JSONObject(castPrefs.getString("batch", "") ?: "")
            if(record.optString("title") == q.title && record.optInt("urlsHash") == q.urls.hashCode() && record.optString("source") == q.sourceJson) record else null
    } catch(e: Exception) { null }
    private fun batchSummary(q: PhoneCastQueue): String {
        return try {
            val record = batchRecord(q) ?: return ""
            val count = record.optInt("count")
            val pending = record.optInt("pending", -1)
            when(record.optString("phase")) {
                "done" -> "上次发送完成，已确认发送 $count 集"
                "unconfirmed", "sending" -> "已确认发送 $count 集；第 ${pending+1} 集未能确认，请先检查电视果列表再重试"
                else -> "上次添加已停止，已确认发送 $count 集"
            }
        } catch(e: Exception) { "" }
    }
    private suspend fun writeBatch(q: PhoneCastQueue, phase: String, start: Int, end: Int, count: Int, pending: Int = -1) {
        val record = JSONObject().put("title", q.title).put("urlsHash", q.urls.hashCode()).put("source", q.sourceJson)
            .put("phase", phase).put("start", start).put("end", end).put("count", count).put("pending", pending)
        withContext(Dispatchers.IO) { castPrefs.edit().putString("batch", record.toString()).commit() }
    }
    private fun batchScreen() {
        val snapshot = queue ?: return
        val target = castDevice ?: return
        screen("batch", "批量加入投屏列表")
        val ticket = generation
        val info = section()
        hero(info, snapshot.title, snapshot.poster, "${target.name}\n共 ${snapshot.urls.size} 集")
        info.addView(text("按现有单集投屏方式逐集发送。电视果会保留自己的列表，播放和排序可在电视果 App 中控制。", 14f).apply { setTextColor(secondaryColor) })
        val summary = batchSummary(snapshot)
        if(summary.isNotEmpty()) info.addView(text(summary, 14f).apply { setTextColor(accentColor) })
        val rangeBox = section("选择添加范围")
        val row = buttonRow(rangeBox)
        fun picker(label: String, value: Int): NumberPicker {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(text(label, 14f).apply { setTextColor(secondaryColor) })
            return NumberPicker(this).apply {
                minValue = 1; maxValue = snapshot.urls.size; this.value = value; wrapSelectorWheel = false
                contentDescription = label; column.addView(this, LinearLayout.LayoutParams(-1, dp(132)))
            }
        }
        val start = picker("开始集数", minOf(snapshot.index + 2, snapshot.urls.size))
        val end = picker("结束集数", snapshot.urls.size)
        val selection = text("")
        rangeBox.addView(selection)
        val status = text("发送期间请保持此页面打开；完成后即可离开 App。", 14f)
        rangeBox.addView(status)
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1; progress = 0 }
        rangeBox.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
        var lastRange: IntRange? = null
        val requiresRangeChange = batchRecord(snapshot)?.optString("phase") == "done"
        var rangeTouched = false
        lateinit var begin: Button
        lateinit var stop: Button
        fun updateRange() {
            val valid = start.value <= end.value
            selection.text = if(valid) "第 ${start.value}—${end.value} 集，共 ${end.value-start.value+1} 集" else "开始集数不能大于结束集数"
            begin.isEnabled = valid && !batchRunning && (!requiresRangeChange || rangeTouched) && (start.value-1..end.value-1) != lastRange
            begin.alpha = if(begin.isEnabled) 1f else .35f
        }
        begin = button("开始添加", rangeBox, true) {
            val range = start.value-1..end.value-1
            if(range.isEmpty()) return@button
            lastRange = range
            batchRunning = true; batchStopRequested = false
            start.isEnabled = false; end.isEnabled = false; begin.isEnabled = false; stop.isEnabled = true
            begin.alpha = .35f; stop.alpha = 1f
            progressBar.max = range.count(); progressBar.progress = 0
            window.decorView.keepScreenOn = true
            work("正在准备批量添加…") {
                var confirmed = 0
                var resolved = target
                polling?.cancel()
                try {
                    val result = PhoneCastBatch().run(range, { batchStopRequested }, { index ->
                        writeBatch(snapshot, "sending", range.first, range.last, confirmed, index)
                        status.text = "正在发送第 ${index+1} 集 · 已确认 $confirmed / ${range.count()} 集"
                    }, { index ->
                        resolved = requestCast(snapshot.at(index), resolved)
                        true
                    }, { index, count ->
                        queue = snapshot.at(index); castDevice = resolved; device = resolved; attached = true
                        saveCast()
                        confirmed = count
                        writeBatch(snapshot, "sent", range.first, range.last, count)
                        progressBar.progress = count
                        status.text = "已确认发送 $count / ${range.count()} 集"
                    })
                    val phase = when { result.unconfirmed != null -> "unconfirmed"; result.stopped -> "stopped"; else -> "done" }
                    writeBatch(snapshot, phase, range.first, range.last, result.accepted.size, result.unconfirmed ?: -1)
                    if(ticket == generation) {
                        status.text = when {
                            result.unconfirmed != null -> "已确认发送 ${result.accepted.size} 集；第 ${result.unconfirmed+1} 集未能确认，已停止。请先检查电视果列表，避免重复添加。"
                            result.stopped -> "已停止添加，已确认发送 ${result.accepted.size} 集；不会停止电视果已有的播放。"
                            else -> "发送完成，已确认发送 ${result.accepted.size} 集。请在电视果 App 中查看列表。"
                        }
                        notice.text = if(phase == "done") "批量发送完成" else "批量添加已停止"
                    }
                } finally {
                    batchRunning = false; window.decorView.keepScreenOn = false
                    if(ticket == generation) {
                        start.isEnabled = true; end.isEnabled = true; stop.isEnabled = false; stop.alpha = .35f; updateRange()
                    }
                }
            }
        }
        stop = button("停止添加", rangeBox) { }
        stop.isEnabled = false
        stop.alpha = .35f
        // This one action must remain available while other cast controls are busy.
        stop.setOnClickListener {
            if(batchRunning) {
                batchStopRequested = true; stop.isEnabled = false; stop.alpha = .35f
                status.text = "正在停止添加，等待本次发送结果…"
            }
        }
        start.setOnValueChangedListener { _,_,_ -> rangeTouched = true; updateRange() }
        end.setOnValueChangedListener { _,_,_ -> rangeTouched = true; updateRange() }
        updateRange()
        if(requiresRangeChange) status.text = "上次已发送完成；如需再次添加，请先调整范围，并核对电视果列表以免重复。"
        button("返回投屏控制") { controls() }
        content.addView(text("此操作与逐次点下一集相同，接收器可能切换当前画面。已发送的项目不会由手机撤回，也不会自动重发。", 13f).apply { setTextColor(secondaryColor) })
    }
    private fun controls() {
        val q = queue ?: run { home(); return }
        screen("controls","投屏控制")
        val info = section()
        hero(info, q.title, q.poster, "最近发送 · 第 ${q.index+1} / ${q.urls.size} 集\n${castDevice?.name ?: "未知设备"}")
        progress = text(if(attached) "正在查询设备…" else "上次投屏记录 · 正在连接设备…", 15f)
        info.addView(progress)
        val switch = buttonRow(info)
        rowButton(switch, "上一集", q.adjacent(-1) != null) { adjacent(-1) }
        rowButton(switch, "下一集", q.adjacent(1) != null) { adjacent(1) }
        button("查看选集", info) { queueDetails() }
        button("批量加入投屏列表", info, true) { batchScreen() }
        val actions = section("播放与音量")
        fun command(row: LinearLayout, label: String, action: suspend () -> Boolean) = rowButton(row, label, attached) {
            work("正在发送控制指令…") {
                val ok = castCommands.withLock { action() }
                notice.text = if(ok) "指令已接受，以接收端实际状态为准" else "设备未接受指令，可能不支持此操作"
            }
        }
        val playback = buttonRow(actions)
        command(playback, "播放 / 继续") { DLNACast.play() }
        command(playback, "暂停") { DLNACast.pause() }
        val seek = buttonRow(actions)
        command(seek, "后退 30 秒") { val p=DLNACast.getProgressRealtime() ?: return@command false; DLNACast.seek((p.first-30000).coerceAtLeast(0)) }
        command(seek, "前进 30 秒") { val p=DLNACast.getProgressRealtime() ?: return@command false; DLNACast.seek(if(p.second>0) (p.first+30000).coerceAtMost(p.second) else p.first+30000) }
        actions.addView(text("音量", 14f).apply { setTextColor(secondaryColor) })
        val volume = SeekBar(this).apply { max=100; isEnabled = attached; actions.addView(this) }
        val ticket = generation
        if(attached) scope.launch {
            try { val value=castCommands.withLock { DLNACast.getVolume()?.first }; if(ticket == generation && value!=null) volume.progress=value }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(ticket == generation) notice.text="音量查询暂不可用，其他操作仍可尝试" }
        }
        volume.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { }
            override fun onStartTrackingTouch(bar: SeekBar?) { }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                work("正在调整音量…") { val ok=castCommands.withLock { DLNACast.setVolume(volume.progress) }; notice.text=if(ok) "音量指令已接受" else "设备不支持音量控制或连接已断开" }
            }
        })
        button("停止播放", actions) {
            work("正在停止播放…") {
                val ok = castCommands.withLock { DLNACast.stop() }
                if(ok) { queue = null; castDevice = null; attached = false; castPrefs.edit().clear().apply(); home(); notice.text = "已停止播放" }
                else notice.text = "未能停止设备，请先确认投屏仍在连接"
            }
        }.isEnabled = attached
        button("返回选片（保留投屏）") { home() }
        content.addView(text("切到其他 App 或锁屏不会停止当前视频。手机显示最近发送的集数；电视果自己的播放列表可在电视果 App 中控制。", 13f).apply { setTextColor(secondaryColor) })
        if(attached) startPolling() else {
            work("正在重新连接上次的投屏…") {
                val target = castDevice ?: return@work
                val matched = castCommands.withLock {
                    val receiver = resolveTarget(target)
                    val uri = DLNACast.reconnectQueue(receiver, q.urls)
                    if(uri != null) { castDevice = receiver; queue = q.at(q.urls.indexOf(uri)) }
                    uri != null
                }
                if(ticket != generation) return@work
                attached = matched
                if(matched) { saveCast(); controls(); notice.text = "已恢复控制，未重新开始播放" }
                else { progress?.text = "设备已停止或正在播放其他内容"; notice.text = "未自动接管其他播放。可点选集或上一集、下一集重新投屏。" }
            }
        }
    }
    private fun startPolling() {
        polling?.cancel()
        if(page!="controls" || !attached) return
        polling = scope.launch {
            while(isActive) {
                try {
                    val (state,p) = castCommands.withLock { DLNACast.getPlaybackState() to DLNACast.getProgressRealtime() }
                    val name = when(state) {
                        DLNACast.PlaybackState.PLAYING -> "设备报告：播放中"
                        DLNACast.PlaybackState.PAUSED -> "设备报告：已暂停"
                        DLNACast.PlaybackState.BUFFERING -> "设备报告：缓冲中"
                        DLNACast.PlaybackState.STOPPED -> "设备报告：已停止"
                        DLNACast.PlaybackState.ERROR -> "设备报告：播放错误"
                        else -> "设备尚未报告播放"
                    }
                    progress?.text = name + (p?.let { "\n${clock(it.first)} / ${clock(it.second)}" } ?: "\n进度暂不可用")
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { progress?.text="设备状态查询失败，请检查接收设备" }
                delay(3000)
            }
        }
    }
    private fun clock(ms: Long): String { val s=(ms/1000).coerceAtLeast(0); return "%d:%02d".format(s/60,s%60) }
    override fun onStop() { polling?.cancel(); if(batchRunning) batchStopRequested = true; super.onStop() }
    override fun onStart() { super.onStart(); startPolling() }
    override fun onResume() {
        super.onResume()
        if(::client.isInitialized && !busy) when(page) { "detail" -> details(); "home" -> home(); "playlist" -> playlistScreen(); "favorites" -> favorites() }
    }
    @Deprecated("Legacy Activity back navigation")
    override fun onBackPressed() {
        if(busy) { notice.text="正在操作，请稍候；退出可使用系统主页键"; return }
        when(page) { "batch" -> controls(); "controls" -> home(); "detail" -> if(query.isNotEmpty()) searchScreen() else home(); "search", "favorites", "playlist", "config", "history" -> home(); else -> super.onBackPressed() }
    }
    override fun onDestroy() {
        dialog?.dismiss(); posters.close(); scope.cancel()
        // An old Activity may finish after the replacement has initialized the shared engine.
        if(castOwner?.get() === this) { DLNACast.cleanup(); castOwner = null }
        super.onDestroy()
    }
}
