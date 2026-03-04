package cn.xtay.lovejournal.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.local.LocationEntity
import java.text.SimpleDateFormat
import java.util.*

class LocationLogAdapter(
    private var logs: List<LocationEntity>,
    private val onLogClick: ((LocationEntity) -> Unit)? = null // 💖 兼容可能的点击回调
) : RecyclerView.Adapter<LocationLogAdapter.LogViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    // 💖 恢复被误删的多选集合
    val selectedItems = mutableSetOf<LocationEntity>()

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.log_tv_time)
        val tvType: TextView = view.findViewById(R.id.log_tv_type)
        val tvLatLng: TextView = view.findViewById(R.id.log_tv_latlng)
        val tvAddress: TextView = view.findViewById(R.id.log_tv_address)
        val root: View = view // 根布局，用于点击和改变背景色
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_location_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logs[position]

        // 1. 设置时间
        holder.tvTime.text = timeFormat.format(Date(log.timestamp))

        // 2. 💖 智能化类型匹配：加入 Cache 和 Bug 识别机制
        when {
            log.locationType == 1 -> {
                holder.tvType.text = "GPS"
                holder.tvType.setTextColor(Color.parseColor("#4CAF50")) // 绿色
            }
            log.locationType == 5 -> {
                holder.tvType.text = "WIFI"
                holder.tvType.setTextColor(Color.parseColor("#2196F3")) // 蓝色
            }
            log.locationType == 6 -> {
                holder.tvType.text = "NET"
                holder.tvType.setTextColor(Color.parseColor("#FF9800")) // 橙色
            }
            log.locationType == 2 || log.locationType == 4 -> {
                holder.tvType.text = "Cache"
                holder.tvType.setTextColor(Color.parseColor("#9C27B0")) // 紫色 (警示这是脏缓存)
            }
            log.locationType < 0 -> {
                // 🚨 负数代表高德的 ErrorCode
                val errorCode = -log.locationType
                holder.tvType.text = "BUG($errorCode)"
                holder.tvType.setTextColor(Color.parseColor("#F44336")) // 红色 (醒目报错)
            }
            else -> {
                holder.tvType.text = "LOC(${log.locationType})"
                holder.tvType.setTextColor(Color.parseColor("#999999")) // 灰色 (未知类型兜底)
            }
        }

        // 3. 💖 智能坐标与【精度(accuracy)】显示
        if (log.locationType < 0) {
            holder.tvLatLng.text = "⚠️ 定位失败"
            holder.tvLatLng.setTextColor(Color.parseColor("#F44336"))
        } else {
            val latStr = String.format("%.4f", log.latitude)
            val lngStr = String.format("%.4f", log.longitude)

            // 格式化精度，保留1位小数
            val accuracyStr = if (log.accuracy > 0f) String.format("%.1f", log.accuracy) else "未知"

            // 拼接到坐标后面显示
            holder.tvLatLng.text = "$latStr, $lngStr (精度: ${accuracyStr}m)"
            holder.tvLatLng.setTextColor(Color.parseColor("#666666")) // 恢复正常的深灰色
        }

        // 4. 地址显示 (报错时，这里会显示详细的异常信息)
        holder.tvAddress.text = log.address ?: "未知状态"
        if (log.locationType < 0) {
            holder.tvAddress.setTextColor(Color.parseColor("#F44336")) // 报错信息也标红
        } else {
            holder.tvAddress.setTextColor(Color.parseColor("#333333"))
        }

        // 5. 💖 恢复 UI 变色：如果被选中，背景变成浅蓝色
        val isSelected = selectedItems.contains(log)
        holder.root.setBackgroundColor(if (isSelected) Color.parseColor("#E3F2FD") else Color.TRANSPARENT)

        // 6. 💖 恢复点击勾选逻辑
        holder.root.setOnClickListener {
            if (selectedItems.contains(log)) {
                selectedItems.remove(log)
            } else {
                selectedItems.add(log)
            }
            notifyItemChanged(position)
            onLogClick?.invoke(log)
        }
    }

    override fun getItemCount() = logs.size

    fun updateData(newLogs: List<LocationEntity>) {
        this.logs = newLogs
        notifyDataSetChanged()
    }

    // 💖 恢复全选功能
    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(logs)
        notifyDataSetChanged()
    }

    // 💖 恢复取消全选功能
    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
    }
}