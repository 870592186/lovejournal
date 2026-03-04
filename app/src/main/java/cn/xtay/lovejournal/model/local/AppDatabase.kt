package cn.xtay.lovejournal.model.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 💖 本地数据库大楼
 * entities: 声明包含哪些表
 * version: 数据库版本，以后改表结构需要升级版本号
 */
@Database(entities = [LocationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // 绑定我们的管家
    abstract fun locationDao(): LocationDao

    companion object {
        // Volatile 保证了多个线程访问时数据的同步性
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 💖 获取数据库的唯一出口 (单例模式)
         */
        fun getDatabase(context: Context): AppDatabase {
            // 如果已经建好了，直接返回；没建好，就排队（synchronized）建一个
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "love_journal_local_db" // 手机里的数据库文件名
                )
                    // 简单起见，如果版本不对直接清空重建（防止开发阶段频繁改字段报错）
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}