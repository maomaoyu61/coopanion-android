package com.maomaoyu.coopanionpet

/**
 * 安全边界（硬约束，AI 绕不过去）。
 *
 * 三层拦截，全部在**动作执行层**，不依赖 AI 自觉：
 *   ① 应用级：当前前台应用命中"支付/银行/金融"名单 → 一切动作与读屏全部拒绝
 *   ② 界面级：当前界面文字里出现支付敏感词 → 拒绝
 *   ③ 元素级：要点的控件文字含敏感词、或要输入的是密码框 → 拒绝
 *
 * 拒绝时统一回一句话，并写进日志（留痕）。
 */
object PetSafety {

    /** 应用级：整机拒绝（包名或应用名命中任一关键词） */
    private val APP_WORDS = listOf(
        "银行", "bank", "支付", "pay", "钱包", "wallet", "支付宝", "alipay", "银联", "unionpay",
        "云闪付", "数字人民币", "证券", "股票", "基金", "理财", "借贷", "贷款", "信用", "征信",
        "保险", "分期", "花呗", "借呗", "微粒贷", "京东金融", "度小满", "财付通", "tenpay",
        "网商银行", "微众银行", "工商银行", "建设银行", "农业银行", "中国银行", "招商银行",
        "交通银行", "邮储", "民生银行", "兴业银行", "浦发", "中信银行", "光大银行", "广发",
        "平安银行", "华夏银行", "北京银行", "上海银行", "农商", "信用社", "交易所", "炒股"
    )

    /** 界面级：当前界面出现这些字 → 拒绝动作 */
    private val SCREEN_WORDS = listOf(
        "支付", "付款", "转账", "收款", "红包", "钱包", "余额", "银行卡", "信用卡", "储蓄卡",
        "免密", "扣款", "充值", "提现", "账单", "还款", "额度", "分期", "借贷", "贷款",
        "支付密码", "验证码", "短信验证", "收款码", "付款码", "立即支付", "确认支付", "去支付",
        "提交订单", "确认付款", "确认转账", "指纹支付", "面容支付", "人脸识别", "刷脸"
    )

    /** 元素级：要点的控件文字含这些字 → 拒绝点它 */
    private val TAP_WORDS = SCREEN_WORDS + listOf(
        "删除", "卸载", "格式化", "恢复出厂", "注销", "冻结", "挂失", "解绑", "退出登录"
    )

    const val REFUSE = "这个我不能碰（涉及支付/银行/账户安全的操作我不做）"

    fun deniedApp(pkg: String?, label: String?): Boolean {
        val s = ((pkg ?: "") + " " + (label ?: "")).lowercase()
        if (s.isBlank()) return false
        return APP_WORDS.any { s.contains(it.lowercase()) }
    }

    fun sensitiveScreen(text: String?): Boolean {
        val s = text ?: return false
        return SCREEN_WORDS.any { s.contains(it) }
    }

    fun sensitiveTap(text: String?): Boolean {
        val s = text ?: return false
        return TAP_WORDS.any { s.contains(it) }
    }

    fun reason(pkg: String?, label: String?): String = when {
        deniedApp(pkg, label) -> REFUSE + "（当前是「" + (label ?: pkg ?: "?") + "」）"
        else -> REFUSE
    }
}
