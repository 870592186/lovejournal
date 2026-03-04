package cn.xtay.lovejournal.model.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 💖 本地定位日志表
 * tableName 指定了数据库里表的名字
 */
@Entity(tableName = "local_location_logs")
data class LocationEntity(

    // 1. 本地唯一标识：自增 ID，避开 'id' 命名，防止未来与服务端 ID 混淆
    @PrimaryKey(autoGenerate = true)
    val locationId: Long = 0,

    // 2. 核心位置数据
    val latitude: Double,   // 纬度
    val longitude: Double,  // 经度
    val address: String?,   // 详细地址（可能为空，所以用 String?）

    // 3. 时间戳：记录这次定位发生的真实时间（毫秒）
    val timestamp: Long,

    // 4. 定位类型：非常重要！
    // 高德返回：1代表GPS，5/6代表Wifi/基站。
    val locationType: Int,

    // 5. 精度：单位是米。GPS定位精度通常在 5-50米 之间
    val accuracy: Float,

    // 6. 备注：预留字段，可以存一下当时是“亮屏触发”还是“息搏触发”
    val remark: String? = null
)