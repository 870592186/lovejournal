package cn.xtay.lovejournal.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.prolificinteractive.materialcalendarview.CalendarDay
import com.prolificinteractive.materialcalendarview.DayViewDecorator
import com.prolificinteractive.materialcalendarview.DayViewFacade

/**
 * 自定义日历装饰器：将指定日期标为红色圆点背景
 */
class EventDecorator(private val color: Int, dates: Collection<CalendarDay>) : DayViewDecorator {
    private val dates: HashSet<CalendarDay> = HashSet(dates)

    override fun shouldDecorate(day: CalendarDay): Boolean = dates.contains(day)

    override fun decorate(view: DayViewFacade) {
        // 设置红色圆形背景，文字设为白色
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        view.setBackgroundDrawable(drawable)
    }
}