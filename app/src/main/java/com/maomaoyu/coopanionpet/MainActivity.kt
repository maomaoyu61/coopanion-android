package com.maomaoyu.coopanionpet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 设置页：卡片式布局，蓝白配色，尽量少手写 XML。 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        // Android 13+ 通知权限也要运行时申请，否则前台服务通知不显示
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
        prefs = getSharedPreferences("pet", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "没给通知权限：部分手机会因此把桌宠后台杀掉，建议在「通知权限设置」里允许", Toast.LENGTH_LONG).show()
        }
        val pad = (d * 16).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFEEF1FB.toInt())
        }

        // ── 标题卡 ──
        col.addView(card(0xFF1B2233.toInt(), 20f, true).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_avatar)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        cornerRadius = d * 12
                        setColor(0xFFFFFFFF.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams((d * 46).toInt(), (d * 46).toInt())
                        .apply { rightMargin = (d * 12).toInt() }
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = "Coopanion 桌宠"
                        textSize = 20f
                        setTextColor(Color.WHITE)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "形象与动作来自上游 Coopanion"
                        textSize = 12f
                        setTextColor(0xFF9FB3D9.toInt())
                        setPadding(0, (d * 4).toInt(), 0, 0)
                    })
                })
            })
        })
        // ── 权限与启动 ──
        col.addView(section("① 权限与启动"))
        col.addView(card().apply {
            addView(pill("授予悬浮窗权限", false) {
                // 各家 ROM 的这个页面不一样，逐级降级
                val ok = try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))); true
                } catch (e: Exception) { false }
                if (!ok) {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                    catch (e2: Exception) { toast("请手动到：设置 → 应用 → 显示在其他应用上层") }
                }
            })
            addView(pill("启动桌宠", true) {
                startForegroundService(Intent(this@MainActivity, PetService::class.java))
            })
            addView(pill("停止桌宠", false) {
                stopService(Intent(this@MainActivity, PetService::class.java))
            })
            addView(pill("装扮（换形象 / 配色）", false) {
                startActivity(Intent(this@MainActivity, DressActivity::class.java))
            })
            addView(pill("后台保活（忽略省电限制）", false) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    catch (e2: Exception) { toast("请手动到：设置 → 电池 → 应用省电策略 → 无限制") }
                }
            })
            addView(pill("应用详情（开自启动 / 后台运行）", false) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    toast("打不开系统设置，请手动到 设置 → 应用 里找 Coopanion 桌宠")
                }
            })
            addView(pill("通知权限设置", false) {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(i)
            })
        })

        // ── 聊天设置 ──
        col.addView(section("② 聊天设置（默认 DeepSeek）"))
        val eBase = field(col, "接口地址（留空 = DeepSeek 官方）", "api_base", Brain.DEFAULT_BASE)
        val eKey = field(col, "API Key（sk- 开头，只存在手机本地）", "api_key", "sk-...", true)
        val eModel = field(col, "模型名（留空 = deepseek-chat）", "api_model", Brain.DEFAULT_MODEL)
        val personas = listOf(
            "女仆（默认）" to Brain.DEFAULT_PERSONA,
            "傲娇" to "你是Q版鲸鱼娘「大肥鱼」，性格傲娇嘴硬：嘴上嫌弃主人、其实很在意，回话短、爱用「哼」「才不是」这种口癖，20字以内。",
            "温柔姐姐" to "你是Q版鲸鱼娘「大肥鱼」，性格温柔体贴像姐姐：说话软软的、会关心主人累不累，回话短，25字以内。",
            "毒舌吐槽" to "你是Q版鲸鱼娘「大肥鱼」，性格毒舌爱吐槽但没恶意：会调侃主人、偶尔损两句，回话短、有梗，25字以内。",
            "自定义" to "")
        val personaField = field(col, "人设（可编辑，选预设会自动填）", "persona", Brain.DEFAULT_PERSONA)
        val sp = android.widget.Spinner(this)
        sp.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, personas.map { it.first })
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val txt = personas[position].second
                if (txt.isNotEmpty() && personaField.text.toString() != txt) personaField.setText(txt)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        col.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "人设一键切换"
                textSize = 12f
                setTextColor(0xFF6B74A8.toInt())
            })
            addView(sp)
        })

        col.addView(card().apply {
            addView(pill("保存并重启桌宠", true) {
                var base = eBase.text.toString().trim()
                var key = eKey.text.toString().trim()
                if (key.isEmpty() && base.startsWith("sk-")) {
                    key = base; base = ""
                    toast("检测到 Key 填在了地址栏，已自动纠正 ✓")
                }
                prefs.edit()
                    .putString("api_base", base)
                    .putString("api_key", key)
                    .putString("api_model", eModel.text.toString().trim())
                    .putString("persona", personaField.text.toString().trim())
                    .apply()
                stopService(Intent(this@MainActivity, PetService::class.java))
                startForegroundService(Intent(this@MainActivity, PetService::class.java))
                toast("已保存，桌宠重启中…")
            })
            addView(pill("导出配置到剪贴板", false) { exportConfig() })
            addView(pill("从剪贴板导入配置", false) { importConfig() })
            addView(pill("清空聊天记忆", false) {
                Brain(this@MainActivity).clearMemory()
                toast("记忆已清空")
            })
        })

        // ── 使用说明 ──
        // ── 外观 ──
        col.addView(section("③ 外观"))
        val scaleLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF6B74A8.toInt())
        }
        val seek = SeekBar(this).apply {
            max = 9
            progress = (((prefs.getFloat("scale", 1f) - 0.6f) * 10f).toInt()).coerceIn(0, 9)
            setPadding(0, (d * 4).toInt(), 0, 0)
        }
        scaleLabel.text = "桌宠大小：" + String.format("%.1f", 0.6f + seek.progress / 10f) + "×"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val v = 0.6f + value / 10f
                scaleLabel.text = "桌宠大小：" + String.format("%.1f", v) + "×"
                prefs.edit().putFloat("scale", v).apply()
                PetService.instance?.applyScale(v.toDouble())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val soundBox = CheckBox(this).apply {
            text = "音效（她走动/说话的音效）"
            textSize = 14f
            isChecked = prefs.getBoolean("sound", true)
            setTextColor(0xFF2A3876.toInt())
            setPadding(0, (d * 8).toInt(), 0, 0)
        }
        soundBox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("sound", checked).apply()
            PetService.instance?.applySound(checked)
        }
        val rateLabel = TextView(this).apply { textSize = 13f; setTextColor(0xFF6B74A8.toInt()) }
        val rateSeek = SeekBar(this).apply {
            max = 15
            progress = ((prefs.getFloat("tts_rate", 1f) - 0.5f) * 10f).toInt().coerceIn(0, 15)
        }
        rateLabel.text = "语速：" + String.format("%.1f", 0.5f + rateSeek.progress / 10f) + "×"
        rateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val v = 0.5f + value / 10f
                rateLabel.text = "语速：" + String.format("%.1f", v) + "×"
                prefs.edit().putFloat("tts_rate", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val pitchLabel = TextView(this).apply { textSize = 13f; setTextColor(0xFF6B74A8.toInt()) }
        val pitchSeek = SeekBar(this).apply {
            max = 15
            progress = ((prefs.getFloat("tts_pitch", 1f) - 0.5f) * 10f).toInt().coerceIn(0, 15)
        }
        pitchLabel.text = "音高：" + String.format("%.1f", 0.5f + pitchSeek.progress / 10f) + "×"
        pitchSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                val v = 0.5f + value / 10f
                pitchLabel.text = "音高：" + String.format("%.1f", v) + "×"
                prefs.edit().putFloat("tts_pitch", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        col.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "气泡样式"
                textSize = 12f
                setTextColor(0xFF6B74A8.toInt())
            })
            addView(android.widget.Spinner(this@MainActivity).apply {
                adapter = android.widget.ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("只显示网页气泡（推荐）", "只显示原生气泡（自带·白）", "两个都要"))
                // 默认 = 网页气泡（更流畅）
                val curB = prefs.getString("bubble_mode", "page")
                setSelection(if (curB == "native") 1 else if (curB == "both") 2 else 0)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        val want = when (position) { 1 -> "native"; 2 -> "both"; else -> "page" }
                        if (prefs.getString("bubble_mode", "page") == want) return
                        prefs.edit().putString("bubble_mode", want).apply()
                        PetService.instance?.reloadPet()
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            })
        })
        col.addView(card().apply {
            addView(scaleLabel)
            addView(seek)
            addView(soundBox)
            addView(rateLabel)
            addView(rateSeek)
            addView(pitchLabel)
            addView(pitchSeek)
            addView(pill("试听语音", false) { PetService.instance?.testSpeak() })
            addView(pill("测试动作（她会跳一下 + 眨眼）", false) { PetService.instance?.testActions() })
        })

        // ── 陪伴 ──
        col.addView(section("④ 陪伴"))
        val petAliveLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF4759AD.toInt())
            text = "桌宠连线：未知"
        }
        this.petAliveLabel = petAliveLabel
        val dshStateLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF4759AD.toInt())
            text = "电脑联动：检查中…"
        }
        this.dshStateLabel = dshStateLabel
        val swLink = CheckBox(this).apply {
            text = "跟着 DSH 状态变表情（干活时会冒问号）"
            textSize = 14f
            isChecked = prefs.getBoolean("dsh_link", true)
            setTextColor(0xFF2A3876.toInt())
        }
        val swCelebrate = CheckBox(this).apply {
            text = "一轮干完她庆祝一下"
            textSize = 14f
            isChecked = prefs.getBoolean("dsh_celebrate", true)
            setTextColor(0xFF2A3876.toInt())
        }
        val swIdle = CheckBox(this).apply {
            text = "她主动搭话（15 分钟没人理她）"
            textSize = 14f
            isChecked = prefs.getBoolean("idle_chat", false)
            setTextColor(0xFF2A3876.toInt())
        }
        for (cb in listOf(swLink, swCelebrate, swIdle)) {
            cb.setOnCheckedChangeListener { v, checked ->
                when (v) {
                    swLink -> prefs.edit().putBoolean("dsh_link", checked).apply()
                    swCelebrate -> prefs.edit().putBoolean("dsh_celebrate", checked).apply()
                    else -> prefs.edit().putBoolean("idle_chat", checked).apply()
                }
            }
        }
        val affLabel = TextView(this).apply {
            text = "亲密度：" + prefs.getInt("affinity", 0) +
                "　心情：" + prefs.getInt("mood", 70) + "/100（聊天和摸头都会涨）"
            textSize = 12f
            setTextColor(0xFF6B74A8.toInt())
        }
        val remindLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6B74A8.toInt())
            text = "番茄钟：未设置"
        }
        fun showRemind() {
            val at = prefs.getLong("remind_at", 0L)
            remindLabel.text = if (at > System.currentTimeMillis())
                "番茄钟：还剩 " + ((at - System.currentTimeMillis()) / 60000 + 1) + " 分钟"
            else "番茄钟：未设置"
        }
        showRemind()
        col.addView(card().apply {
            addView(swLink)
            addView(swCelebrate)
            addView(swIdle)
            addView(CheckBox(this@MainActivity).apply {
                text = "省电模式（息屏时暂停她的动画）"
                textSize = 14f
                isChecked = prefs.getBoolean("power_save", true)
                setTextColor(0xFF2A3876.toInt())
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("power_save", c).apply() }
            })
            addView(CheckBox(this@MainActivity).apply {
                text = "横屏（看全屏视频）时自动收起悬浮钮和状态条"
                textSize = 13f
                isChecked = prefs.getBoolean("landscape_hide", true)
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("landscape_hide", c).apply() }
            })
          addView(CheckBox(this@MainActivity).apply {
                text = "微信/QQ 有消息时她提醒我（需通知使用权，只看 App 名不看内容）"
                textSize = 14f
                isChecked = prefs.getBoolean("notify_pet", false)
                setTextColor(0xFF2A3876.toInt())
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("notify_pet", c).apply() }
            })
            addView(pill("去开启通知使用权", false) {
                try {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                } catch (e: Exception) {
                    toast("这台手机没有这个设置页")
                }
            })
            addView(petAliveLabel)
            addView(TextView(this@MainActivity).apply {
                text = "状态条位置（也可以直接拖动那条状态条，轻点它收起）"
                textSize = 12f
                setTextColor(0xFF6B74A8.toInt())
            })
            addView(android.widget.Spinner(this@MainActivity).apply {
                adapter = android.widget.ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("脚下（默认）", "头顶", "不显示"))
                val cur = prefs.getString("status_pos", "feet")
                setSelection(if (cur == "head") 1 else if (cur == "off") 2 else 0)
                // 跳过初始化时自动触发的那次回调，否则会把"拖动记住的自定义位置"冲掉
                var statusCbFirst = true
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        // 只跳过初始化那一次回调；之后**哪怕选的是同一个值**也要恢复显示
                        if (statusCbFirst) { statusCbFirst = false; return }
                        val want = when (position) { 1 -> "head"; 2 -> "off"; else -> "feet" }
                        prefs.edit().putString("status_pos", want)
                            .putBoolean("status_hidden", false)
                            .apply()
                        PetService.instance?.applyStatusPlacement()
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            })
            addView(pill("恢复显示状态条", false) {
                prefs.edit().putString("status_pos", "feet")
                    .putBoolean("status_hidden", false)
                    .apply()
                PetService.instance?.applyStatusPlacement()
                toast("状态条已恢复到屏幕底部")
            })
            addView(dshStateLabel)
            addView(pill("重新检测 DSH 连接", false) { refreshDsh() })
            addView(affLabel)
            addView(remindLabel)
            for ((mins, label) in listOf(15 to "15 分钟", 25 to "25 分钟", 45 to "45 分钟")) {
                addView(pill("提醒我 " + label, false) {
                    prefs.edit()
                        .putLong("remind_at", System.currentTimeMillis() + mins * 60000L)
                        .putString("remind_label", label + "到啦")
                        .apply()
                    showRemind()
                    toast("好的，" + label + "后叫你")
                })
            }
            addView(pill("复制诊断信息（发给开发者）", false) {
                try {
                    val nl = System.lineSeparator()
                    val sb = StringBuilder("CoopanionPet 诊断信息" + nl)
                    sb.append("版本: " + packageManager.getPackageInfo(packageName, 0).versionName + nl)
                    sb.append("系统: Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")" + nl)
                    sb.append("机型: " + Build.MANUFACTURER + " " + Build.MODEL + nl)
                    try {
                        val wv = android.webkit.WebView.getCurrentWebViewPackage()
                        sb.append("WebView 版本: " + (wv?.versionName ?: "未知") + nl)
                    } catch (_: Exception) {
                        sb.append("WebView 版本: 读不到" + nl)
                    }
                    sb.append("悬浮窗权限: " + (if (Settings.canDrawOverlays(this@MainActivity)) "有" else "没有") + nl)
                    val notifOk = Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
                    sb.append("通知权限: " + (if (notifOk) "有" else "没有") + nl)
                    try {
                        val pm = getSystemService(android.os.PowerManager::class.java)
                        sb.append("忽略电池优化: " + (if (pm.isIgnoringBatteryOptimizations(packageName)) "是" else "否") + nl)
                    } catch (_: Exception) {
                    }
                    sb.append("桌宠服务: " + (if (PetService.instance != null) "运行中" else "未运行") + nl)
                    sb.append("桌宠连线: " + (if (PetService.instance?.isPetAlive() == true) "正常" else "断开") + nl)
                    sb.append("TTS: " + (if (prefs.getBoolean("tts_ok", false)) "就绪" else "未知") + nl)
                    sb.append((PetService.instance?.pageInfo() ?: "网页: 服务未运行") + nl)
                    sb.append("电脑联动(DSH播报器): " + (dshStateLabel?.text ?: "未知") + nl)
                    sb.append("最近日志: " + (PetService.instance?.logTail(4) ?: "(服务未运行)") + nl)
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", sb.toString()))
                    toast("诊断信息已复制，粘贴发给开发者即可")
                } catch (e: Exception) {
                    toast("生成失败：" + e.javaClass.simpleName)
                }
            })
            addView(pill("查看聊天记录", false) {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java))
            })
            addView(pill("取消提醒", false) {
                prefs.edit().putLong("remind_at", 0L).apply()
                showRemind()
                toast("已取消")
            })
        })

        col.addView(section("⑤ 怎么玩"))
        col.addView(card().apply {
            addView(hint(
                "【基本玩法】\n" +
                "• 悬浮钮：点「摸」= 跟她玩（摸头 / 拖拽 / 甩飞）；点「用」= 触摸穿透，正常用手机\n" +
                "• 把钮拖到屏幕边缘会收成小胶囊，点一下弹回来\n" +
                "• 双击她 = 弹出输入框（贴键盘上方，可用输入法自带的麦克风说话）\n" +
                "• 摸头四下她会回你一句；长按她出菜单\n" +
                "\n【她会做动作】\n" +
                "• 说话时会自己眨眼、点头、跳、坐下、转圈……（会照 AI 给的标记演）\n" +
                "• 想马上验证：右边「③ 外观 → 测试动作」点一下，她会跳一下再眨个眼\n" +
                "• 觉得动作少，可以在人设里加一句：多用【开心】【跳】这类标记\n" +
                "\n【气泡与状态条】\n" +
                "• 气泡样式在「③ 外观」里选，「只显示网页气泡」最流畅（默认）\n" +
                "• 状态条可以直接拖到任意位置，轻点它收起；「④ 陪伴」里也能切 脚下 / 头顶 / 不显示\n" +
                "• 状态条内容来自电脑端联动（显示当前任务和用时）；电脑端没跑时它不显示\n" +
                "\n【没填 API Key 也能玩】\n" +
                "• 内置离线台词库，她会用预设台词回应；想 AI 聊天就在「② 聊天设置」填自己的 Key\n" +
                "• 换配色 / 换形象：点上面的「装扮」，那是原版页面，最全\n" +
                "\n【一定要做的系统设置】（不做她会莫名消失）\n" +
                "• 点上面的「后台保活」+「应用详情」→ 打开自启动、省电策略设成「无限制」\n" +
                "• Android 13 以上记得允许通知\n" +
                "\n【出问题怎么办】\n" +
                "• 「④ 陪伴 → 复制诊断信息」→ 把那段文字发给开发者，就能远程定位问题\n" +
                "• 她整个身体靠 WebGL2 渲染；若头发/身体缺失，先去应用商店更新「Android System WebView」\n" +
                "• 那条状态条是开发者功能，只在电脑端联动跑着时才有内容；没内容不影响任何功能，也不用告诉谁 —— 断了自己会恢复（重启一次 DSH 即可）"))
        })

        setContentView(ScrollView(this).apply { addView(col) },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /* ---------- 小工具 ---------- */

    private fun card(bg: Int = Color.WHITE, radius: Float = 16f, gradient: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 14).toInt(), (d * 12).toInt(), (d * 14).toInt(), (d * 12).toInt())
            background = if (gradient) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF4759AD.toInt(), 0xFF2A3876.toInt())).apply { cornerRadius = d * radius }
            else GradientDrawable().apply {
                cornerRadius = d * radius
                setColor(bg)
                setStroke((d * 1.2f).toInt(), 0xFFDCE3F7.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (d * 12).toInt() }
        }

    private fun section(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(0xFF6B74A8.toInt())
            setPadding((d * 4).toInt(), (d * 6).toInt(), 0, (d * 6).toInt())
        }

    private fun hint(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF5A6390.toInt())
            setLineSpacing(d * 5, 1f)
        }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else 0xFF4759AD.toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 14
                if (primary) setColor(0xFF4759AD.toInt())
                else {
                    setColor(0xFFFFFFFF.toInt())
                    setStroke((d * 1.2f).toInt(), 0xFFB9C4EE.toInt())
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (d * 8).toInt() }
            stateListAnimator = null
            setOnClickListener { onClick() }
        }

    private fun field(parent: LinearLayout, label: String, key: String, hintText: String,
                        secret: Boolean = false): EditText {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((d * 14).toInt(), (d * 10).toInt(), (d * 14).toInt(), (d * 10).toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 16
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (d * 12).toInt() }
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF6B74A8.toInt())
        })
        val et = EditText(this).apply {
            this.hint = hintText
            setText(prefs.getString(key, ""))
            textSize = 15f
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        box.addView(et)
        parent.addView(box)
        return et
    }

    /** 把配置（含 API Key）复制到剪贴板，重装或换机后粘回来即可。 */
    private fun exportConfig() {
        try {
            val o = org.json.JSONObject()
            for (k in listOf("api_base", "api_key", "api_model", "persona")) {
                o.put(k, prefs.getString(k, ""))
            }
            o.put("scale", prefs.getFloat("scale", 1f).toDouble())
            o.put("sound", prefs.getBoolean("sound", true))
            o.put("tts_rate", prefs.getFloat("tts_rate", 1f).toDouble())
            o.put("tts_pitch", prefs.getFloat("tts_pitch", 1f).toDouble())
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("coopanion", o.toString()))
            toast("配置已复制到剪贴板（含 Key，别外发）")
        } catch (e: Exception) {
            toast("导出失败：" + e.javaClass.simpleName)
        }
    }

    /** 从剪贴板恢复配置。 */
    private fun importConfig() {
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val s = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val o = org.json.JSONObject(s)
            val e = prefs.edit()
            for (k in listOf("api_base", "api_key", "api_model", "persona")) {
                if (o.has(k)) e.putString(k, o.getString(k))
            }
            if (o.has("scale")) e.putFloat("scale", o.getDouble("scale").toFloat())
            if (o.has("sound")) e.putBoolean("sound", o.getBoolean("sound"))
            if (o.has("tts_rate")) e.putFloat("tts_rate", o.getDouble("tts_rate").toFloat())
            if (o.has("tts_pitch")) e.putFloat("tts_pitch", o.getDouble("tts_pitch").toFloat())
            e.apply()
            toast("配置已导入 ✓ 点「保存并重启桌宠」生效")
        } catch (e: Exception) {
            toast("剪贴板里没有可用的配置")
        }
    }

    /** 探测 DSH 状态播报器是否在跑（它决定她能不能跟着我干活变脸）。 */
    private fun refreshDsh() {
        val label = dshStateLabel ?: return
        label.text = "电脑联动：检查中…"
        Thread({
            val msg = try {
                val c = (java.net.URL("http://127.0.0.1:8755/").openConnection()
                    as java.net.HttpURLConnection)
                c.connectTimeout = 900
                c.readTimeout = 900
                val txt = c.inputStream.bufferedReader().readText()
                c.disconnect()
                val o = org.json.JSONObject(txt)
                when (o.optString("state")) {
                    "idle" -> "电脑联动：已连接 ✓（闲着）"
                    "done" -> "电脑联动：已连接 ✓（刚干完一轮）"
                    else -> "电脑联动：已连接 ✓（" + o.optString("text") + "）"
                }
            } catch (e: Exception) {
                "电脑联动：未连接（开发者功能，重启一次 DSH 会自动恢复；其他人忽略即可）"
            }
            runOnUiThread { label.text = msg }
            runOnUiThread {
                petAliveLabel?.text = if (PetService.instance?.isPetAlive() == true)
                    "桌宠连线：✓ 正常"
                else "桌宠连线：✗ 已断开（会自动重载；若一直断开请重启桌宠）"
            }
        }, "dshprobe").start()
    }

    private var dshStateLabel: TextView? = null
    private var petAliveLabel: TextView? = null

    override fun onResume() {
        super.onResume()
        dshStateLabel?.let { refreshDsh() }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}