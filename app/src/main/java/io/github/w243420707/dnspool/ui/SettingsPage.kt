package io.github.w243420707.dnspool.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.w243420707.dnspool.AppSettings
import io.github.w243420707.dnspool.Dimens
import io.github.w243420707.dnspool.DnsMode
import io.github.w243420707.dnspool.Host
import io.github.w243420707.dnspool.IpUtil
import io.github.w243420707.dnspool.MEDIUM
import io.github.w243420707.dnspool.MainActivity
import io.github.w243420707.dnspool.Page
import io.github.w243420707.dnspool.PortParser
import io.github.w243420707.dnspool.R
import io.github.w243420707.dnspool.RecordMode
import io.github.w243420707.dnspool.SlotComment
import io.github.w243420707.dnspool.UiSnapshot
import io.github.w243420707.dnspool.actionButton
import io.github.w243420707.dnspool.color
import io.github.w243420707.dnspool.dp
import io.github.w243420707.dnspool.groupChevron
import io.github.w243420707.dnspool.groupHeader
import io.github.w243420707.dnspool.groupSummary
import io.github.w243420707.dnspool.para
import io.github.w243420707.dnspool.rounded
import io.github.w243420707.dnspool.rowSwitch
import io.github.w243420707.dnspool.rowValue
import io.github.w243420707.dnspool.setTextIfChanged
import io.github.w243420707.dnspool.settingRow
import io.github.w243420707.dnspool.switchRow
import io.github.w243420707.dnspool.vSpace

/**
 * 设置页：三个可折叠分组 + 危险区。
 *
 * 写入路径只有一条：host.commit { it.copy(...) }。绝不整体构造 AppSettings，
 * 否则 zoneId / zoneName 会被空值覆盖，整轮写入全部失败。
 */
class SettingsPage(private val activity: MainActivity) : Page {

    private val ctx = activity
    private val host: Host = activity

    private val updaters = mutableListOf<(AppSettings) -> Unit>()
    private val conditions = mutableListOf<(AppSettings) -> Unit>()
    private val heads = mutableListOf<LinearLayout>()
    private val bodies = mutableListOf<LinearLayout>()
    private val expanded = booleanArrayOf(true, false, false)

    private lateinit var box: LinearLayout
    private var current: AppSettings = io.github.w243420707.dnspool.StateRepository.get(activity).settings()
    private var lastSettings: AppSettings? = null
    private var lastOk = -1

    override val view: View = build()

    /* ------------------------------ 骨架 ------------------------------ */

    private fun build(): View {
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(24))
        }
        scroll.addView(box)
        fillCommon(startGroup(0, "常用"))
        fillCloudflare(startGroup(1, "Cloudflare"))
        fillAdvanced(startGroup(2, "高级"))
        box.addView(ctx.vSpace(10))
        box.addView(buildDanger(), LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = px(Dimens.PAGE_PAD)
            rightMargin = px(Dimens.PAGE_PAD)
        })
        // 首帧先用默认值铺一遍，避免等 250ms 的第一次 refresh 期间出现空值行
        updaters.forEach { it(current) }
        conditions.forEach { it(current) }
        return scroll
    }

    private fun startGroup(index: Int, title: String): LinearLayout {
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded[index]) View.VISIBLE else View.GONE
        }
        val head = ctx.groupHeader(
            title, summary(index, current, lastOk.coerceAtLeast(0)), expanded[index]
        ) { toggle(index) }
        heads += head
        bodies += body
        box.addView(head)
        box.addView(body)
        return body
    }

    private fun toggle(index: Int) {
        expanded[index] = !expanded[index]
        bodies.getOrNull(index)?.visibility = if (expanded[index]) View.VISIBLE else View.GONE
        heads.getOrNull(index)?.groupChevron()
            ?.setTextIfChanged(if (expanded[index]) "▾" else "▸")
    }

    private fun summary(index: Int, s: AppSettings, ok: Int): String = when (index) {
        0 -> "${PortParser.format(s.ports).ifEmpty { "端口未填" }} · N=${s.slotCount}"
        1 -> "${if (s.cfToken.isBlank()) "Token 未填" else "Token 已填"} · ${s.slotCount}/${s.recordLimit}"
        else -> "并发 ${s.probeConcurrency} · 超时 ${s.connectTimeoutMs}ms"
    }

    /* ------------------------------ 行工厂 ------------------------------ */

    private fun px(v: Int): Int = ctx.dp(v)

    private fun value(row: LinearLayout, text: (AppSettings) -> String) {
        val tv = row.rowValue()
        updaters.add { s -> tv?.setTextIfChanged(text(s)) }
    }

    private fun condition(row: LinearLayout, show: (AppSettings) -> Boolean) {
        row.visibility = if (show(current)) View.VISIBLE else View.GONE
        conditions.add { s -> row.visibility = if (show(s)) View.VISIBLE else View.GONE }
    }

    private fun intRow(
        label: String,
        title: String,
        min: Int,
        max: Int,
        suffix: String = "",
        get: (AppSettings) -> Int,
        write: (AppSettings, Int) -> AppSettings
    ): LinearLayout {
        val row = ctx.settingRow(label, "", onClick = {
            Dialogs.editText(
                activity, title, get(current).toString(), hint = "$min..$max", numeric = true,
                validate = { raw ->
                    val n = raw.toIntOrNull()
                    when {
                        n == null -> "只能填数字"
                        n < min || n > max -> "范围 $min..$max"
                        else -> null
                    }
                }
            ) { raw -> host.commit { write(it, raw.toIntOrNull() ?: get(it)) } }
        })
        value(row) { s -> "${get(s)}$suffix" }
        return row
    }

    private fun textRow(
        label: String,
        title: String,
        hint: String,
        get: (AppSettings) -> String,
        show: (AppSettings) -> String,
        validate: (String) -> String? = { null },
        write: (AppSettings, String) -> AppSettings
    ): LinearLayout {
        val row = ctx.settingRow(label, "", onClick = {
            Dialogs.editText(activity, title, get(current), hint = hint, validate = validate) { raw ->
                host.commit { write(it, raw) }
            }
        })
        value(row, show)
        return row
    }

    /* ------------------------------ 常用 ------------------------------ */

    private fun fillCommon(g: LinearLayout) {
        g.addView(textRow(
            "端口", "端口", "443,80",
            get = { it.portsRaw },
            show = { s -> PortParser.format(s.ports).ifEmpty { "未填" } },
            validate = { raw -> if (PortParser.parse(raw).isEmpty()) "至少填一个 1..65535 的端口" else null },
            write = { s, raw -> s.copy(portsRaw = PortParser.format(PortParser.parse(raw))) }
        ))
        g.addView(intRow("槽位数量 N", "槽位数量", 1, 2000, get = { it.slotCount }) { s, n ->
            s.copy(slotCount = n)
        })
        g.addView(intRow("连续失败阈值", "连续失败阈值", 1, 10, " 次", { it.failThreshold }) { s, n ->
            s.copy(failThreshold = n)
        })
        g.addView(intRow("两轮间隔", "两轮间隔", 0, 3_600_000, " ms", { it.minLoopIntervalMs.toInt() }) { s, n ->
            s.copy(minLoopIntervalMs = n.toLong())
        })
        g.addView(ctx.settingRow("域名列表", "", onClick = {
            Dialogs.editDomains(activity, current.domainsRaw) { raw -> host.commit { it.copy(domainsRaw = raw) } }
        }).also { row -> value(row) { s -> "${s.domainsRaw.lines().count { it.isNotBlank() }} 行" } })

        val restart = ctx.actionButton("重启守护") { host.restartService() }.apply {
            background = ctx.rounded(ctx.color(R.color.surface2), 8)
            setTextColor(ctx.color(R.color.accent))
            layoutParams = LinearLayout.LayoutParams(-1, px(40)).apply {
                setMargins(px(Dimens.PAGE_PAD), px(6), px(Dimens.PAGE_PAD), px(6))
            }
        }
        g.addView(restart)
    }

    /* ------------------------------ Cloudflare ------------------------------ */

    private fun fillCloudflare(g: LinearLayout) {
        // Token 永不回显：输入框留空，右值只说已填/未填
        val token = ctx.settingRow("API Token", "", onClick = {
            Dialogs.editText(
                activity, "API Token", "", hint = "粘贴新 Token，不回显原值",
                validate = { raw -> if (raw.isBlank()) "Token 不能为空" else null }
            ) { raw -> host.commit { it.copy(cfToken = raw.trim()) } }
        })
        value(token) { s -> if (s.cfToken.isBlank()) "未填" else "已填 ·••••" }
        g.addView(token)

        g.addView(textRow(
            "根域名", "根域名", "example.com",
            get = { it.rootDomain },
            show = { s -> s.rootDomain.ifBlank { "未填" } },
            validate = { raw ->
                val clean = IpUtil.cleanHost(raw)
                if (clean.isBlank() || !clean.contains('.')) "填根域名，如 example.com" else null
            },
            write = { s, raw -> s.copy(rootDomain = IpUtil.cleanHost(raw)) }
        ))

        val mode = ctx.settingRow("记录模式", "", onClick = {
            val modes = RecordMode.entries
            Dialogs.pick(activity, "记录模式", modes.map { it.label }, current.mode.ordinal) { i ->
                modes.getOrNull(i)?.let { m -> host.commit { it.copy(mode = m) } }
            }
        })
        value(mode) { it.mode.label }
        g.addView(mode)

        g.addView(textRow(
            "共享主机名", "共享主机名", "cf",
            get = { it.sharedHost },
            show = { s -> s.sharedHost.ifBlank { "—" } },
            validate = { raw -> if (raw.isBlank()) "主机名不能为空" else null },
            write = { s, raw -> s.copy(sharedHost = raw.trim()) }
        ).also { condition(it) { s -> s.mode == RecordMode.SHARED_HOST } })

        g.addView(textRow(
            "槽位名前缀", "槽位名前缀", "n",
            get = { it.slotPrefix },
            show = { s -> s.slotPrefix.ifBlank { "—" } },
            validate = { raw -> if (raw.isBlank() || raw.contains('.')) "填不带点前缀，如 n" else null },
            write = { s, raw -> s.copy(slotPrefix = raw.trim()) }
        ).also { condition(it) { s -> s.mode == RecordMode.SLOT_SUBDOMAIN } })

        g.addView(intRow("TTL 秒", "TTL 秒", 60, 86400, get = { it.ttlSec }) { s, n -> s.copy(ttlSec = n) })
        g.addView(intRow("记录条数上限", "记录条数上限", 1, 1000, get = { it.recordLimit }) { s, n ->
            s.copy(recordLimit = n)
        })

        val proxy = ctx.switchRow(
            "Cloudflare 代理（橙云）", "IP 池必须保持灰云，一般别开", current.proxied
        ) { on -> host.commit { it.copy(proxied = on) } }
        updaters.add { s ->
            proxy.rowSwitch()?.let { if (it.isChecked != s.proxied) it.isChecked = s.proxied }
        }
        g.addView(proxy)
    }

    /* ------------------------------ 高级 ------------------------------ */

    private fun fillAdvanced(g: LinearLayout) {
        g.addView(intRow("并发数", "并发数", 1, 128, get = { it.probeConcurrency }) { s, n ->
            s.copy(probeConcurrency = n)
        })
        g.addView(intRow("连接超时", "连接超时", 200, 10000, " ms", { it.connectTimeoutMs }) { s, n ->
            s.copy(connectTimeoutMs = n)
        })
        g.addView(intRow("每端口尝试", "每端口尝试", 1, 5, " 次", { it.attemptsPerPort }) { s, n ->
            s.copy(attemptsPerPort = n)
        })
        g.addView(intRow("每轮修复上限", "每轮修复上限", 0, 50, " 条", { it.maxRepairsPerCycle }) { s, n ->
            s.copy(maxRepairsPerCycle = n)
        })
        g.addView(intRow("单轮探测预算", "单轮探测预算", 5000, 600000, " ms", { it.maxProbeBudgetMs.toInt() }) { s, n ->
            s.copy(maxProbeBudgetMs = n.toLong())
        })

        val dns = ctx.settingRow("DNS 方式", "", onClick = {
            val modes = DnsMode.entries
            Dialogs.pick(activity, "DNS 方式", modes.map { it.label }, current.dnsMode.ordinal) { i ->
                modes.getOrNull(i)?.let { m -> host.commit { it.copy(dnsMode = m) } }
            }
        })
        value(dns) { it.dnsMode.label }
        g.addView(dns)

        g.addView(textRow(
            "UDP 服务器", "UDP 服务器", "223.5.5.5,119.29.29.29",
            get = { it.udpServersRaw },
            show = { it.udpServersRaw },
            validate = { raw -> if (raw.isBlank()) "至少填一个 DNS 服务器" else null },
            write = { s, raw -> s.copy(udpServersRaw = raw.trim()) }
        ).also { condition(it) { s -> s.dnsMode == DnsMode.UDP } })

        g.addView(textRow(
            "DoH 地址", "DoH 地址", "https://dns.alidns.com/dns-query",
            get = { it.dohUrl },
            show = { it.dohUrl },
            validate = { raw -> if (!raw.startsWith("http")) "必须是 http(s) 地址" else null },
            write = { s, raw -> s.copy(dohUrl = raw.trim()) }
        ).also { condition(it) { s -> s.dnsMode == DnsMode.DOH } })

        g.addView(intRow("DNS 超时", "DNS 超时", 200, 10000, " ms", { it.dnsTimeoutMs }) { s, n ->
            s.copy(dnsTimeoutMs = n)
        })
        g.addView(intRow("写间隔", "写间隔", 0, 5000, " ms", { it.writeMinIntervalMs.toInt() }) { s, n ->
            s.copy(writeMinIntervalMs = n.toLong())
        })
        g.addView(ctx.para("改完需重启守护才生效", Dimens.LABEL, R.color.sub).apply {
            alpha = 0.8f
            setPadding(px(Dimens.PAGE_PAD), 0, px(Dimens.PAGE_PAD), px(6))
        })

        g.addView(ctx.settingRow("预览差异（不写入）", "", onClick = { host.preview() }))
    }

    /* ------------------------------ 危险区 ------------------------------ */

    private fun buildDanger(): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.rounded(ctx.color(R.color.surface), 12, ctx.color(R.color.danger))
            setPadding(px(14), px(14), px(14), px(6))
        }
        card.addView(ctx.para("危险操作", Dimens.BODY, R.color.danger).apply { typeface = MEDIUM })
        card.addView(ctx.para(
            "只删备注以 ${SlotComment.PREFIX} 开头的记录，别人的记录不动。",
            Dimens.LABEL, R.color.sub
        ).apply { setPadding(0, px(4), 0, px(4)) })
        val row = ctx.settingRow("删除我管理的记录", "", danger = true, onClick = { host.cleanup() })
        row.setPadding(0, 0, 0, 0)
        card.addView(row)
        return card
    }

    /* ------------------------------ 渲染 ------------------------------ */

    override fun render(snap: UiSnapshot) {
        val s = snap.settings
        if (s == lastSettings && snap.okCount == lastOk) return
        lastSettings = s
        lastOk = snap.okCount
        current = s
        updaters.forEach { it(s) }
        conditions.forEach { it(s) }
        heads.forEachIndexed { i, head ->
            head.groupSummary()?.setTextIfChanged(summary(i, s, snap.okCount))
        }
    }
}
