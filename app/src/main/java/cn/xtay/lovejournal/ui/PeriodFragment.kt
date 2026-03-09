package cn.xtay.lovejournal.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.PeriodRecord
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.net.WebSocketManager
import cn.xtay.lovejournal.util.UserPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.prolificinteractive.materialcalendarview.CalendarDay
import com.prolificinteractive.materialcalendarview.MaterialCalendarView
import org.threeten.bp.LocalDate
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PeriodFragment : Fragment() {

    private lateinit var calendarView: MaterialCalendarView
    private lateinit var tvPrediction: TextView
    private lateinit var tvDateLabel: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var btnLeft: MaterialButton
    private lateinit var btnRight: MaterialButton

    private lateinit var tvSelectedMemo: TextView
    private lateinit var btnEditMemo: MaterialButton
    private lateinit var llMemoList: LinearLayout

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private var selectedLocalDate: LocalDate = LocalDate.now()
    private var allRecords = mutableListOf<PeriodRecord>()
    private val redDaysSet = mutableSetOf<CalendarDay>()
    private val startDaysSet = mutableSetOf<LocalDate>()

    private var isUpdatingUI = false
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isOperationLocked = false
    private var lastFetchTime = 0L

    // 💖 核心升级：前台页面专用的极速内存监听器
    private val wsListener = object : WebSocketManager.MessageListener {
        override fun onCommandReceived(command: String, data: String) {
            if (command == "sync_period") {
                fetchData(isSilent = true)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        com.jakewharton.threetenabp.AndroidThreeTen.init(requireContext())
        val view = inflater.inflate(R.layout.fragment_period, container, false)

        calendarView = view.findViewById(R.id.calendar_view)
        tvPrediction = view.findViewById(R.id.tv_prediction_text)
        tvDateLabel = view.findViewById(R.id.tv_date_label)
        toggleGroup = view.findViewById(R.id.toggle_status_group)
        btnLeft = view.findViewById(R.id.btn_status_left)
        btnRight = view.findViewById(R.id.btn_status_right)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)
        tvSelectedMemo = view.findViewById(R.id.tv_selected_memo)
        btnEditMemo = view.findViewById(R.id.btn_edit_memo)
        llMemoList = view.findViewById(R.id.ll_memo_list)

        val todayDay = CalendarDay.from(selectedLocalDate)
        calendarView.selectedDate = todayDay
        calendarView.setCurrentDate(todayDay, false)
        tvDateLabel.text = "当前选中：$selectedLocalDate"

        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        swipeRefreshLayout.setColorSchemeColors(typedValue.data)

        swipeRefreshLayout.setOnRefreshListener {
            fetchData(isSilent = false)
        }

        calendarView.setOnDateChangedListener { _, date, _ ->
            selectedLocalDate = LocalDate.of(date.year, date.month, date.day)
            tvDateLabel.text = "当前选中：$selectedLocalDate"
            refreshUIState()
        }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingUI || !isChecked) return@addOnButtonCheckedListener

            if (UserPrefs.getPartnerId(requireContext()) <= 0) {
                Toast.makeText(context, "请先绑定另一半才能开启甜蜜记录哦~", Toast.LENGTH_SHORT).show()
                refreshUIState()
                return@addOnButtonCheckedListener
            }

            val isStartDay = startDaysSet.contains(selectedLocalDate)
            val inRedZone = redDaysSet.contains(CalendarDay.from(selectedLocalDate))

            val status = when {
                isStartDay -> if (checkedId == R.id.btn_status_right) -99 else 1
                inRedZone -> if (checkedId == R.id.btn_status_right) 2 else 1
                else -> if (checkedId == R.id.btn_status_left) 1 else 0
            }

            if (status == -99) {
                showDeleteBlockDialog()
                return@addOnButtonCheckedListener
            }

            if (inRedZone && checkedId == R.id.btn_status_left) {
                refreshUIState()
                return@addOnButtonCheckedListener
            }

            executeLocalAction(status)
        }

        val longClick = View.OnLongClickListener {
            if (UserPrefs.getPartnerId(requireContext()) <= 0) {
                Toast.makeText(context, "请先绑定另一半才能开启甜蜜记录哦~", Toast.LENGTH_SHORT).show()
                return@OnLongClickListener true
            }

            if (isOperationLocked) {
                Toast.makeText(context, "操作频繁，请稍等2s后再操作", Toast.LENGTH_SHORT).show()
            } else {
                showDeleteBlockDialog()
            }
            true
        }
        btnLeft.setOnLongClickListener(longClick)
        btnRight.setOnLongClickListener(longClick)

        btnEditMemo.setOnClickListener {
            if (UserPrefs.getPartnerId(requireContext()) <= 0) {
                Toast.makeText(context, "绑定另一半后，一起记录专属纪念日吧~", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showMemoEditDialog()
        }

        return view
    }

    private fun showMemoEditDialog() {
        val dateStr = selectedLocalDate.toString()
        val currentRec = allRecords.find { it.date == dateStr }
        val currentMemo = currentRec?.memo ?: ""
        val currentStatus = currentRec?.status ?: 0

        val input = EditText(requireContext()).apply {
            setText(currentMemo)
            hint = "例如：结婚纪念日、第一次约会..."
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置 ${selectedLocalDate.year}年${selectedLocalDate.monthValue}月${selectedLocalDate.dayOfMonth}日")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newMemo = input.text.toString().trim()
                saveMemoData(dateStr, currentStatus, newMemo)
            }
            .setNegativeButton("取消", null)

        if (currentMemo.isNotEmpty()) {
            builder.setNeutralButton("删除纪念日") { _, _ -> saveMemoData(dateStr, currentStatus, "") }
        }

        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.parseColor("#FF5252"))
    }

    private fun saveMemoData(dateStr: String, status: Int, newMemo: String) {
        if (isOperationLocked) return
        isOperationLocked = true

        allRecords.removeAll { it.date == dateStr }
        if (status != 0 || newMemo.isNotEmpty()) {
            allRecords.add(PeriodRecord(dateStr, status, newMemo))
        }

        UserPrefs.saveLocalPeriods(requireContext(), Gson().toJson(allRecords))
        calculateAndRender()

        val uid = UserPrefs.getUserId(requireContext())
        if (uid != -1) {
            uploadSingleRecord(dateStr, status, newMemo)
        } else {
            isOperationLocked = false
        }
    }

    private fun executeLocalAction(status: Int) {
        if (isOperationLocked) {
            Toast.makeText(context, "操作频繁，请稍等2s后再操作", Toast.LENGTH_SHORT).show()
            refreshUIState()
            return
        }

        val dateStr = selectedLocalDate.toString()
        val uid = UserPrefs.getUserId(requireContext())
        isOperationLocked = true

        val existingMemo = allRecords.find { it.date == dateStr }?.memo ?: ""

        if (status == 2) {
            val belongsToStart = allRecords
                .filter { it.status == 1 }
                .mapNotNull { try { LocalDate.parse(it.date) to it } catch(e: Exception) { null } }
                .filter { !it.first.isAfter(selectedLocalDate) }
                .maxByOrNull { it.first.toEpochDay() }

            belongsToStart?.let { startPair ->
                val nextStart = allRecords
                    .filter { it.status == 1 }
                    .mapNotNull { try { LocalDate.parse(it.date) } catch(e: Exception) { null } }
                    .filter { it.isAfter(startPair.first) }
                    .minByOrNull { it.toEpochDay() }

                val oldEndsInThisCycle = allRecords.filter {
                    it.status == 2 &&
                            it.date != dateStr &&
                            it.date >= startPair.second.date &&
                            (nextStart == null || it.date < nextStart.toString())
                }

                oldEndsInThisCycle.forEach { old ->
                    allRecords.remove(old)
                    val safeOldMemo = old.memo ?: ""

                    if (safeOldMemo.isNotEmpty()) {
                        allRecords.add(PeriodRecord(old.date, 0, safeOldMemo))
                    }
                    if (uid != -1 && UserPrefs.getPartnerId(requireContext()) > 0) {
                        NetworkClient.getApi(requireContext()).updatePeriod(
                            action = "update_period", userId = uid, date = old.date, status = 0, memo = safeOldMemo
                        ).enqueue(object : Callback<UserResponse> {
                            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {}
                            override fun onFailure(call: Call<UserResponse>, t: Throwable) {}
                        })
                    }
                }
            }
        }

        allRecords.removeAll { it.date == dateStr }
        if (status != 0 || existingMemo.isNotEmpty()) {
            allRecords.add(PeriodRecord(dateStr, status, existingMemo))
        }

        calculateAndRender()
        UserPrefs.saveLocalPeriods(requireContext(), Gson().toJson(allRecords))

        if (uid != -1 && UserPrefs.getPartnerId(requireContext()) > 0) {
            uploadSingleRecord(dateStr, status, existingMemo)
        } else {
            syncHandler.postDelayed({ isOperationLocked = false }, 2000)
        }
    }

    private fun uploadSingleRecord(date: String, status: Int, memo: String) {
        val uid = UserPrefs.getUserId(requireContext())
        NetworkClient.getApi(requireContext()).updatePeriod(
            action = "update_period", userId = uid, date = date, status = status, memo = memo
        ).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                syncHandler.postDelayed({ isOperationLocked = false }, 2000)
                notifyPartnerToRefresh()
            }
            override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                Toast.makeText(context, "同步失败", Toast.LENGTH_SHORT).show()
                syncHandler.postDelayed({ isOperationLocked = false }, 2000)
            }
        })
    }

    private fun notifyPartnerToRefresh() {
        val partnerId = UserPrefs.getPartnerId(requireContext())
        if (partnerId > 0 && WebSocketManager.isConnected) {
            WebSocketManager.sendMessage("send_to_partner", partnerId, "sync_period")
        }
    }

    private fun fetchData(isSilent: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < 1000) {
            swipeRefreshLayout.isRefreshing = false
            return
        }

        val uid = UserPrefs.getUserId(requireContext())
        val pid = UserPrefs.getPartnerId(requireContext())
        if (uid != -1 && pid > 0) {

            if (!isSilent && swipeRefreshLayout.isRefreshing) {
                Toast.makeText(context, "正在同步另一半的最新数据...", Toast.LENGTH_SHORT).show()
            }

            lastFetchTime = now
            if (!swipeRefreshLayout.isRefreshing && !isSilent) swipeRefreshLayout.isRefreshing = true

            NetworkClient.getApi(requireContext()).getPeriods(action = "get_periods", userId = uid, partnerId = pid)
                .enqueue(object : Callback<UserResponse> {
                    override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                        swipeRefreshLayout.isRefreshing = false
                        val res = response.body()
                        if (res?.status == "success") {
                            allRecords = (res.periods_data ?: emptyList()).toMutableList()
                            UserPrefs.saveLocalPeriods(requireContext(), Gson().toJson(allRecords))
                            calculateAndRender()
                        }
                    }
                    override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                        swipeRefreshLayout.isRefreshing = false
                        loadFromLocalCache()
                    }
                })
        } else {
            swipeRefreshLayout.isRefreshing = false
            allRecords.clear()
            UserPrefs.saveLocalPeriods(requireContext(), "[]")
            calculateAndRender()
        }
    }

    private fun loadFromLocalCache() {
        val localJson = UserPrefs.getLocalPeriods(requireContext())
        if (!localJson.isNullOrEmpty()) {
            allRecords = Gson().fromJson(localJson, object : TypeToken<MutableList<PeriodRecord>>() {}.type)
        }
        calculateAndRender()
    }

    private fun calculateAndRender() {
        redDaysSet.clear()
        startDaysSet.clear()
        val sorted = allRecords.sortedBy { it.date }
        sorted.forEach { rec ->
            try {
                val base = LocalDate.parse(rec.date)
                if (rec.status == 1) {
                    startDaysSet.add(base)
                    for (i in 0..5) redDaysSet.add(CalendarDay.from(base.plusDays(i.toLong())))
                } else if (rec.status == 2) {
                    for (i in 1..5) redDaysSet.remove(CalendarDay.from(base.plusDays(i.toLong())))
                }
            } catch (e: Exception) {}
        }

        if (UserPrefs.getPartnerId(requireContext()) <= 0) {
            tvPrediction.text = "等待甜蜜连接..."
        } else {
            val last = sorted.lastOrNull { it.status == 1 }
            if (last != null) {
                try {
                    val next = LocalDate.parse(last.date).plusDays(28)
                    tvPrediction.text = "预计下次：${next.monthValue}月${next.dayOfMonth}日"
                } catch (e: Exception) {}
            } else {
                tvPrediction.text = "暂无预测数据"
            }
        }

        calendarView.removeDecorators()
        calendarView.addDecorators(HighlightDecorator(Color.parseColor("#FF5252"), redDaysSet, true))
        calendarView.invalidateDecorators()
        refreshUIState()
    }

    private fun refreshUIState() {
        isUpdatingUI = true
        val day = CalendarDay.from(selectedLocalDate)
        if (startDaysSet.contains(selectedLocalDate)) btnRight.text = "没来"
        else if (redDaysSet.contains(day)) btnRight.text = "走了"
        else btnRight.text = "没来"

        toggleGroup.clearChecked()
        val dateStr = selectedLocalDate.toString()
        val rec = allRecords.find { it.date == dateStr }
        val targetId = when {
            rec?.status == 2 -> R.id.btn_status_right
            startDaysSet.contains(selectedLocalDate) -> R.id.btn_status_left
            redDaysSet.contains(day) -> R.id.btn_status_left
            rec?.status == 1 -> R.id.btn_status_left
            else -> R.id.btn_status_right
        }
        toggleGroup.check(targetId)

        val currentMemo = rec?.memo ?: ""
        if (currentMemo.isNotEmpty()) {
            tvSelectedMemo.text = "💖 $currentMemo"
            btnEditMemo.text = "修改"
        } else {
            tvSelectedMemo.text = "暂无纪念日"
            btnEditMemo.text = "添加"
        }

        refreshMemoList()

        isUpdatingUI = false
    }

    private fun refreshMemoList() {
        llMemoList.removeAllViews()
        val dateStr = selectedLocalDate.toString()

        val memoRecords = allRecords
            .filter { !it.memo.isNullOrEmpty() && it.date != dateStr }
            .sortedByDescending { it.date }

        if (memoRecords.isEmpty()) {
            llMemoList.visibility = View.GONE
            return
        }
        llMemoList.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val paddingH = (16 * density).toInt()
        val paddingV = (14 * density).toInt()

        val typedValueText = android.util.TypedValue()
        requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValueText, true)

        for (rec in memoRecords) {
            val itemView = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val parts = rec.date.split("-")
                val dateDisplay = if (parts.size == 3) "${parts[1]}月${parts[2]}日" else rec.date
                text = "• $dateDisplay  -  ${rec.memo ?: ""}"

                textSize = 15f
                setTextColor(typedValueText.data)

                setPadding(paddingH, paddingV, paddingH, paddingV)

                val typedValueBg = android.util.TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValueBg, true)
                setBackgroundResource(typedValueBg.resourceId)

                setOnClickListener {
                    try {
                        val parsedDate = LocalDate.parse(rec.date)
                        calendarView.selectedDate = CalendarDay.from(parsedDate)
                        calendarView.setCurrentDate(CalendarDay.from(parsedDate), true)
                        selectedLocalDate = parsedDate
                        tvDateLabel.text = "当前选中：$selectedLocalDate"
                        refreshUIState()
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            llMemoList.addView(itemView)
        }
    }

    private fun deepClearBlock() {
        if (isOperationLocked) return
        val startPair = allRecords
            .filter { it.status == 1 }
            .mapNotNull { try { LocalDate.parse(it.date) to it } catch(e: Exception) { null } }
            .filter { !it.first.isAfter(selectedLocalDate) }
            .maxByOrNull { it.first.toEpochDay() } ?: return

        val startDate = startPair.first

        val endRec = allRecords
            .filter { it.status == 2 }
            .mapNotNull { try { LocalDate.parse(it.date) to it } catch(e: Exception) { null } }
            .filter { it.first.isAfter(startDate) }
            .minByOrNull { it.first.toEpochDay() }

        isOperationLocked = true

        val startMemo = startPair.second.memo ?: ""
        allRecords.remove(startPair.second)
        if (startMemo.isNotEmpty()) allRecords.add(PeriodRecord(startPair.second.date, 0, startMemo))

        var endMemo = ""
        endRec?.let {
            endMemo = it.second.memo ?: ""
            allRecords.remove(it.second)
            if (endMemo.isNotEmpty()) allRecords.add(PeriodRecord(it.second.date, 0, endMemo))
        }

        calculateAndRender()
        UserPrefs.saveLocalPeriods(requireContext(), Gson().toJson(allRecords))

        val uid = UserPrefs.getUserId(requireContext())
        if (uid != -1 && UserPrefs.getPartnerId(requireContext()) > 0) {
            NetworkClient.getApi(requireContext()).updatePeriod(
                action = "update_period", userId = uid, date = startDate.toString(), status = 0, memo = startMemo
            ).enqueue(object : Callback<UserResponse>{
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    if (endRec != null) {
                        NetworkClient.getApi(requireContext()).updatePeriod(
                            action = "update_period", userId = uid, date = endRec.first.toString(), status = 0, memo = endMemo
                        ).enqueue(object : Callback<UserResponse>{
                            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                                syncHandler.postDelayed({ isOperationLocked = false }, 2000)
                                notifyPartnerToRefresh()
                            }
                            override fun onFailure(call: Call<UserResponse>, t: Throwable) { syncHandler.postDelayed({ isOperationLocked = false }, 2000) }
                        })
                    } else {
                        syncHandler.postDelayed({ isOperationLocked = false }, 2000)
                        notifyPartnerToRefresh()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) { syncHandler.postDelayed({ isOperationLocked = false }, 2000) }
            })
        } else { syncHandler.postDelayed({ isOperationLocked = false }, 2000) }
    }

    private fun showDeleteBlockDialog() {
        AlertDialog.Builder(requireContext()).setTitle("清理记录").setMessage("确定要清理这整段经期记录吗？(不会删除你的纪念日)")
            .setPositiveButton("确定") { _, _ -> deepClearBlock() }
            .setNegativeButton("取消") { _, _ -> refreshUIState() }.show()
    }

    override fun onResume() {
        super.onResume()
        fetchData(isSilent = true)
        // 🚀 挂载纯粹的内存监听，告别广播时代！
        WebSocketManager.addListener(wsListener)
    }

    override fun onPause() {
        super.onPause()
        isOperationLocked = false
        // 🚀 离开页面立刻卸载监听，防内存泄露！
        WebSocketManager.removeListener(wsListener)
    }
}