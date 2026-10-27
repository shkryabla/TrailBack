package com.trailback.app.data.db
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
@Database(
    entities = [EntryPoint::class, TrackPoint::class, MarkedPlace::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryPointDao(): EntryPointDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun markedPlaceDao(): MarkedPlaceDao
    companion object {
        @Volatile
        private var instance: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "trailback.db"
                )
                    // НОВОЕ (предрелизный аудит): раньше здесь не было вообще
                    // никакой стратегии миграции — первое же будущее изменение
                    // схемы (новая колонка/таблица) гарантированно крашило бы
                    // приложение при апгрейде у ВСЕХ действующих пользователей
                    // (Room выбрасывает IllegalStateException, если не находит
                    // явную Migration для скачка версии). Это не гипотетический
                    // риск — схема этого проекта уже менялась несколько раз за
                    // одну эту сессию правок.
                    //
                    // fallbackToDestructiveMigration() — осознанный компромисс,
                    // а не "на будущее разберёмся": при несовпадении версии Room
                    // просто пересоздаёт БД с нуля вместо краша. Это приемлемо
                    // ИМЕННО в этом проекте, потому что есть независимый путь
                    // восстановления данных — BackupManager.restoreIfPresent()
                    // (вызывается из MapActivity при каждом холодном старте,
                    // пока backupFolderUri уже выбран) тихо смержит данные из
                    // SAF-бэкапа обратно в только что пересозданную пустую БД.
                    // Без этой подстраховки откат к destructive был бы просто
                    // потерей данных вместо краша — не лучше по сути.
                    //
                    // Как только появится первое реальное изменение схемы —
                    // заменить это на explicit addMigrations(MIGRATION_1_2, ...)
                    // с реальным ALTER TABLE, чтобы не терять данные на каждом
                    // обновлении приложения, а не только на переустановке.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}