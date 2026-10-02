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
        prefs = getSharedPreferences("pet", MODE_PRIVATE)

        val pad = (d * 16).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFF4F5F7.toInt())
        }

        // ── 标题卡 ──
        col.addView(card(0xFF1B2233.toInt(), 20f, true).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_launcher_fg)
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
                        text = "安卓外壳 · 形象/动作/配色来自上游"
                        textSize = 12f
                        setTextColor(0xFF9FB3D9.toInt())
                        setPadding(0, (d * 4).toInt(), 0, 0)
                    })
                })
            })
        })
            addView(TextView(this@MainActivity).apply {
                text = "安卓外壳 · 形象/动作/配色都来自上游 Coopanion"
                textSize = 12f
                setTextColor(0xFF9FB3D9.toInt())
                setPadding(0, (d * 6).toInt(), 0, 0)
            })
        })

        // ── 权限与启动 ──
        col.addView(section("① 权限与启动"))
        col.addView(card().apply {
            addView(pill("\uD83D\uDCE6  授予悬浮窗权限", false) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            })
            addView(pill("\u25B6  启动桌宠", true) {
                startForegroundService(Intent(this@MainActivity, PetService::class.java))
            })
            addView(pill("\u25A0  停止桌宠", false) {
                stopService(Intent(this@MainActivity, PetService::class.java))
            })
            addView(pill("\uD83D\uDC57  装扮（换形象 / 配色）", false) {
                startActivity(Intent(this@MainActivity, DressActivity::class.java))
            })
            addView(pill("\uD83D\uDD14  通知权限设置", false) {
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

        col.addView(card().apply {
            addView(pill("\uD83D\uDCBE  保存并重启桌宠", true) {
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
                    .apply()
                stopService(Intent(this@MainActivity, PetService::class.java))
                startForegroundService(Intent(this@MainActivity, PetService::class.java))
                toast("已保存，桌宠重启中…")
            })
            addView(pill("\uD83E\uDDF9  清空聊天记忆", false) {
                Brain(this@MainActivity).clearMemory()
                toast("记忆已清空")
            })
        })

        // ── 使用说明 ──
        col.addView(section("③ 怎么玩"))
        col.addView(card().apply {
            addView(hint("• 桌面上三个悬浮钮：🐾/🖐 切模式、💬 打开输入条\n" +
                    "• 拖到屏幕左右边缘会自动藏成小竖条，点竖条弹回来\n" +
                    "• 摸她、拎起来甩、绕圈 —— 都是上游原版交互\n" +
                    "• 语音：点 💬 后用输入法自带的麦克风"))
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
                intArrayOf(0xFF33558F.toInt(), 0xFF1B2233.toInt())).apply { cornerRadius = d * radius }
            else GradientDrawable().apply { cornerRadius = d * radius; setColor(bg) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (d * 12).toInt() }
        }

    private fun section(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding((d * 4).toInt(), (d * 6).toInt(), 0, (d * 6).toInt())
        }

    private fun hint(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF4B5563.toInt())
            setLineSpacing(d * 5, 1f)
        }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else 0xFF2C5FD8.toInt())
            background = GradientDrawable().apply {
                cornerRadius = d * 14
                if (primary) setColor(0xFF2C5FD8.toInt())
                else {
                    setColor(0xFFFFFFFF.toInt())
                    setStroke((d * 1.2f).toInt(), 0xFFC9D6F2.toInt())
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
            setTextColor(0xFF6B7280.toInt())
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
