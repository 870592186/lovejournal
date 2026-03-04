package cn.xtay.lovejournal.model.local

import androidx.room.*

@Dao
interface LocationDao {

    // 💖 修复点 1：@Insert 必须返回 Long (代表插入的行ID)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LocationEntity): Long

    // 查询语句本身有明确的返回值 List<LocationEntity>，所以它不会报错
    @Query("SELECT * FROM local_location_logs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentLogs(): List<LocationEntity>

    // 💖 修复点 2：@Delete 必须返回 Int (代表删除的行数)
    @Delete
    suspend fun deleteLogs(logs: List<LocationEntity>): Int

    // 💖 修复点 3：手写的 DELETE 必须返回 Int
    @Query("DELETE FROM local_location_logs")
    suspend fun clearAll(): Int

    // 💖 修复点 4：手写的 DELETE 必须返回 Int
    @Query("""
        DELETE FROM local_location_logs 
        WHERE locationId NOT IN (
            SELECT locationId FROM local_location_logs 
            ORDER BY timestamp DESC LIMIT 100
        )
    """)
    suspend fun trimDatabase(): Int
}