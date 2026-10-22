package com.farewise.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.farewise.app.data.local.entity.BookingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookingDao {

    @Insert
    suspend fun insert(entity: BookingEntity): Long

    /**
     * All bookings inside a half-open time interval, newest first.
     *
     * Range-scan friendly thanks to `idx_booking_time` so monthly windows
     * are cheap even on a year of history. Reactive Flow so the journal
     * screen updates automatically when a fresh booking is recorded.
     */
    @Query(
        """
        SELECT * FROM booking
        WHERE bookedAtMillis >= :sinceMillis
          AND bookedAtMillis < :untilMillis
        ORDER BY bookedAtMillis DESC
        """
    )
    fun observeBetween(sinceMillis: Long, untilMillis: Long): Flow<List<BookingEntity>>

    /**
     * Recent bookings across all time — used by the journal's "all trips"
     * tab and by the monthly insights to find "most expensive trip ever".
     * Capped at [limit] so we don't ship a huge list to Compose for nothing.
     */
    @Query("SELECT * FROM booking ORDER BY bookedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<BookingEntity>>

    @Query("SELECT COUNT(*) FROM booking")
    suspend fun count(): Int
}
