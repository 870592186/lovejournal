package cn.xtay.lovejournal

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
// --- 显式导入每一个 Fragment，确保路径绝对正确 ---
import cn.xtay.lovejournal.ui.MapFragment
import cn.xtay.lovejournal.ui.PeriodFragment
import cn.xtay.lovejournal.ui.DeviceFragment
import cn.xtay.lovejournal.ui.SettingFragment

class MainAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    // 告诉系统我们一共有 4 个页面
    override fun getItemCount(): Int = 4

    // 根据用户点击的位置(0,1,2,3)，返回对应的页面
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MapFragment()    // 第一个：定位
            1 -> PeriodFragment() // 第二个：姨妈
            2 -> DeviceFragment() // 第三个：设备
            3 -> SettingFragment() // 第四个：设置
            else -> MapFragment() // 兜底处理
        }
    }
}