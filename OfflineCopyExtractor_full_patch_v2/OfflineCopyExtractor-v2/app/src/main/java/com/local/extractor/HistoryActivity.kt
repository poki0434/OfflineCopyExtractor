package com.local.extractor

import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {

    private lateinit var historyDb: HistoryDb
    private lateinit var listContainer: LinearLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var multiBar: LinearLayout
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnMultiToggle: TextView

    private val categories = listOf("全部", "链接", "视频", "音频", "文章", "图片")
    private var currentCategory = "全部"
    private var isMultiSelectMode = false
    private val selectedIds = mutableSetOf<Long>()
    private var currentFilteredItems = listOf<HistoryItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyDb = HistoryDb(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 38, 32, 24)
            elevation = 4f
        }

        val btnBack = TextView(this).apply {
            this.text = "← 返回"
            textSize = 15f
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(0, 8, 24, 8)
            setOnClickListener {
                if (isMultiSelectMode) exitMultiSelect() else finish()
            }
        }

        val titleView = TextView(this).apply {
            this.text = "提取历史记录"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F2329"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnMultiToggle = TextView(this).apply {
            this.text = "多选"
            textSize = 14f
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(20, 8, 20, 8)
            setOnClickListener {
                if (isMultiSelectMode) exitMultiSelect() else enterMultiSelect()
            }
        }

        val btnClear = TextView(this).apply {
            this.text = "清空"
            textSize = 14f
            setTextColor(Color.parseColor("#D93025"))
            setPadding(16, 8, 0, 8)
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("确认清空")
                    .setMessage("确定要删除全部历史提取记录吗？")
                    .setPositiveButton("清空") { _, _ ->
                        historyDb.clearAll()
                        exitMultiSelect()
                        renderList()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        topBar.addView(btnBack)
        topBar.addView(titleView)
        topBar.addView(btnMultiToggle)
        topBar.addView(btnClear)
        root.addView(topBar)

        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            setPadding(24, 8, 24, 20)
        }
        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScroll.addView(tabContainer)
        root.addView(tabScroll)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 40)
        }
        scrollView.addView(listContainer)
        root.addView(scrollView)

        multiBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 20, 32, 24)
            elevation = 12f
            visibility = View.GONE
        }

        val btnSelectAll = TextView(this).apply {
            this.text = "全选"
            textSize = 14f
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(8, 12, 24, 12)
            setOnClickListener {
                if (selectedIds.size == currentFilteredItems.size) {
                    selectedIds.clear()
                } else {
                    selectedIds.addAll(currentFilteredItems.map { it.id })
                }
                renderList()
            }
        }

        tvSelectedCount = TextView(this).apply {
            this.text = "已选 0 项"
            textSize = 13f
            setTextColor(Color.parseColor("#646A73"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnBatchDelete = Button(this).apply {
            this.text = "删除"
            textSize = 13f
            setTextColor(Color.parseColor("#D93025"))
            setBackgroundColor(Color.parseColor("#FDECEC"))
            setPadding(28, 0, 28, 0)
            setOnClickListener {
                if (selectedIds.isEmpty()) return@setOnClickListener
                selectedIds.forEach { historyDb.deleteById(it) }
                exitMultiSelect()
                renderList()
                Toast.makeText(this@HistoryActivity, "已删除选中记录", Toast.LENGTH_SHORT).show()
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, 1)
        }

        val btnBatchExport = Button(this).apply {
            this.text = "导出文档"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setPadding(32, 0, 32, 0)
            setOnClickListener {
                val chosen = currentFilteredItems.filter { selectedIds.contains(it.id) }
                DocumentExporter.showExportDialog(this@HistoryActivity, chosen)
            }
        }

        multiBar.addView(btnSelectAll)
        multiBar.addView(tvSelectedCount)
        multiBar.addView(btnBatchDelete)
        multiBar.addView(spacer)
        multiBar.addView(btnBatchExport)
        root.addView(multiBar)

        setContentView(root)
        renderTabs()
        renderList()
    }

    private fun enterMultiSelect(initialId: Long? = null) {
        isMultiSelectMode = true
        selectedIds.clear()
        if (initialId != null) selectedIds.add(initialId)
        btnMultiToggle.text = "取消多选"
        multiBar.visibility = View.VISIBLE
        renderList()
    }

    private fun exitMultiSelect() {
        isMultiSelectMode = false
        selectedIds.clear()
        btnMultiToggle.text = "多选"
        multiBar.visibility = View.GONE
        renderList()
    }

    private fun renderTabs() {
        tabContainer.removeAllViews()
        for (cat in categories) {
            val active = (cat == currentCategory)
            val chip = TextView(this).apply {
                this.text = cat
                textSize = 13f
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) Color.WHITE else Color.parseColor("#4E5969"))
                setPadding(32, 12, 32, 12)
                background = GradientDrawable().apply {
                    setColor(if (active) Color.parseColor("#1A73E8") else Color.parseColor("#F2F3F5"))
                    cornerRadius = 40f
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = 16 }

                setOnClickListener {
                    currentCategory = cat
                    selectedIds.clear()
                    renderTabs()
                    renderList()
                }
            }
            tabContainer.addView(chip)
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val all = historyDb.getAll()
        currentFilteredItems = if (currentCategory == "全部") {
            all
        } else {
            all.filter { it.type.contains(currentCategory) }
        }

        tvSelectedCount.text = "已选 ${selectedIds.size} 项"

        if (currentFilteredItems.isEmpty()) {
            val emptyView = TextView(this).apply {
                this.text = "当前分类下暂无历史记录"
                textSize = 14f
                setTextColor(Color.parseColor("#8F959E"))
                gravity = Gravity.CENTER
                setPadding(0, 220, 0, 0)
            }
            listContainer.addView(emptyView)
            return
        }

        for (item in currentFilteredItems) {
            val isChecked = selectedIds.contains(item.id)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 24, 32, 24)
                background = GradientDrawable().apply {
                    setColor(if (isChecked) Color.parseColor("#EBF3FE") else Color.WHITE)
                    cornerRadius = 24f
                    if (isChecked) setStroke(3, Color.parseColor("#1A73E8"))
                }
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }
            }

            if (isMultiSelectMode) {
                val cb = CheckBox(this).apply {
                    this.isChecked = isChecked
                    isClickable = false
                    setPadding(0, 0, 16, 0)
                }
                card.addView(cb)
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val timeTitle = TextView(this).apply {
                this.text = "${item.time}  ·  ${item.type}"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1F2329"))
            }

            val sourcePreview = TextView(this).apply {
                this.text = item.sourceInfo
                textSize = 12f
                setTextColor(Color.parseColor("#86909C"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 8, 0, 6)
            }

            val contentPreview = TextView(this).apply {
                this.text = item.content
                textSize = 13.5f
                setTextColor(Color.parseColor("#373C43"))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            textCol.addView(timeTitle)
            textCol.addView(sourcePreview)
            textCol.addView(contentPreview)
            card.addView(textCol)

            card.setOnClickListener {
                if (isMultiSelectMode) {
                    if (selectedIds.contains(item.id)) selectedIds.remove(item.id) else selectedIds.add(item.id)
                    renderList()
                } else {
                    showDetailDialog(item)
                }
            }

            card.setOnLongClickListener {
                if (!isMultiSelectMode) {
                    enterMultiSelect(item.id)
                }
                true
            }

            listContainer.addView(card)
        }
    }

    private fun showDetailDialog(item: HistoryItem) {
        val rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }

        val timeLine = TextView(this).apply {
            this.text = "识别时间：${item.time}   |   ${item.type}"
            textSize = 11.5f
            setTextColor(Color.parseColor("#86909C"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(4, 0, 4, 16)
        }
        rootBox.addView(timeLine)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
        }

        val cardsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun buildCopyableCard(title: String, body: String, isMain: Boolean): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 22, 28, 22)
                background = GradientDrawable().apply {
                    setColor(if (isMain) Color.parseColor("#F7F8FA") else Color.parseColor("#F0F6FF"))
                    cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 20 }

                val header = TextView(this@HistoryActivity).apply {
                    this.text = "$title（长按此板块复制）"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#1A73E8"))
                    setPadding(0, 0, 0, 8)
                }

                val contentTv = TextView(this@HistoryActivity).apply {
                    this.text = body
                    textSize = if (isMain) 14.5f else 12.5f
                    setTextColor(Color.parseColor("#1F2329"))
                    setLineSpacing(6f, 1.15f)
                }

                val longClickAction = View.OnLongClickListener {
                    copyToClipboard(body, "已复制「$title」")
                    true
                }
                setOnLongClickListener(longClickAction)
                header.setOnLongClickListener(longClickAction)
                contentTv.setOnLongClickListener(longClickAction)

                addView(header)
                addView(contentTv)
            }
        }

        cardsBox.addView(buildCopyableCard("原始链接 & 口令", item.sourceInfo, isMain = false))
        cardsBox.addView(buildCopyableCard("识别的内容", item.content, isMain = true))
        scroll.addView(cardsBox)
        rootBox.addView(scroll)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 4)
        }

        val btnExport = TextView(this).apply {
            this.text = "导出文档"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F9D58"))
            setPadding(12, 16, 20, 16)
            setOnClickListener {
                DocumentExporter.showExportDialog(this@HistoryActivity, listOf(item))
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val btnCopyAll = TextView(this).apply {
            this.text = "复制全部"
            textSize = 13.5f
            setTextColor(Color.parseColor("#4E5969"))
            setPadding(20, 16, 24, 16)
            setOnClickListener {
                val allText = "识别时间：${item.time}\n原始链接：${item.sourceInfo}\n\n${item.content}"
                copyToClipboard(allText, "已复制全部内容")
            }
        }

        val btnCopyMain = TextView(this).apply {
            this.text = "一键复制正文"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A73E8"))
                cornerRadius = 16f
            }
            setPadding(28, 16, 28, 16)
            setOnClickListener {
                copyToClipboard(item.content, "已一键复制识别正文")
            }
        }

        bottomBar.addView(btnExport)
        bottomBar.addView(spacer)
        bottomBar.addView(btnCopyAll)
        bottomBar.addView(btnCopyMain)
        rootBox.addView(bottomBar)

        AlertDialog.Builder(this)
            .setView(rootBox)
            .show()
    }

    private fun copyToClipboard(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("history", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
