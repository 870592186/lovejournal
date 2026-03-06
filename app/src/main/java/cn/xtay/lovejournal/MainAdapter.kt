package cn.xtay.lovejournal

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
// --- 显式导入每一个 Fragment，确保路径绝对正确 ---
import cn.xtay.lovejournal.ui.HomeFragment    // 💖 导入我们刚刚新建的爱心页
import cn.xtay.lovejournal.ui.MapFragment
import cn.xtay.lovejournal.ui.PeriodFragment
import cn.xtay.lovejournal.ui.DeviceFragment
import cn.xtay.lovejournal.ui.SettingFragment

class MainAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    // 💖 告诉系统我们现在一共有 5 个页面了！
    override fun getItemCount(): Int = 5

    // 根据用户点击的位置(0,1,2,3,4)，返回对应的页面
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> HomeFragment()   // 💖 第 0 个：爱心首页
            1 -> MapFragment()    // 第 1 个：定位
            2 -> PeriodFragment() // 第 2 个：姨妈
            3 -> DeviceFragment() // 第 3 个：设备
            4 -> SettingFragment()// 第 4 个：设置
            else -> HomeFragment()// 兜底处理
        }
    }
}