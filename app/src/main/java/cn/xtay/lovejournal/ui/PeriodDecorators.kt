package cn.xtay.lovejournal.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.prolificinteractive.materialcalendarview.CalendarDay
import com.prolificinteractive.materialcalendarview.DayViewDecorator
import com.prolificinteractive.materialcalendarview.DayViewFacade

/**
 * 修改项：通用高亮装饰器
 * 用于渲染：经期红区（加粗）和扩散红区（不加粗）
 */
class HighlightDecorator(
    private val color: Int,
    dates: Collection<CalendarDay>,
    private val isBold: Boolean
) : DayViewDecorator {

    private val dates = HashSet(dates)

    override fun shouldDecorate(day: CalendarDay): Boolean = dates.contains(day)

    override fun decorate(view: DayViewFacade) {
        // 设置背景色（红或粉）
        view.setBackgroundDrawable(ColorDrawable(color))

        if (isBold) {
            // “来了”当天：加粗 + 白色文字
            view.addSpan(StyleSpan(Typeface.BOLD))
            view.addSpan(ForegroundColorSpan(Color.WHITE))
        }
    }
}

/**
 * 修改项：预测期装饰器
 * 增加了 color 参数，支持从 Fragment 动态传值
 */
class PredictionDecorator(
    private val color: Int,
    dates: Collection<CalendarDay>
) : DayViewDecorator {

    private val dates = HashSet(dates)

    override fun shouldDecorate(day: CalendarDay): Boolean = dates.contains(day)

    override fun decorate(view: DayViewFacade) {
        // 设置预测期的浅粉色背景
        view.setBackgroundDrawable(ColorDrawable(color))
    }
}