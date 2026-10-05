package com.cleanmove.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.cleanmove.data.ceturb.CeturbApi
import com.cleanmove.data.ceturb.CeturbSource
import com.cleanmove.data.ceturb.createCeturbHttpClient
import com.cleanmove.data.geocontrol.GeocontrolApi
import com.cleanmove.data.geocontrol.GeocontrolSource
import com.cleanmove.data.geocontrol.createGeocontrolHttpClient
import com.cleanmove.data.local.AppDatabase
import com.cleanmove.data.local.CatalogDao
import com.cleanmove.data.repository.DataStoreFavorites
import com.cleanmove.data.repository.LineCatalogRepository
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.LineCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun ceturbSource(): CeturbSource = CeturbApi(createCeturbHttpClient())

    // Unvalidated against a live server — see docs/spike-findings.md. Not yet consumed by
    // any ViewModel; wiring it into the UI/ArrivalMerger needs the stop/route catalog
    // (linhaId -> Line.code join) that a later task builds.
    @Provides @Singleton
    fun geocontrolSource(): GeocontrolSource = GeocontrolApi(createGeocontrolHttpClient())

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "clean-move.db")
            .fallbackToDestructiveMigration() // the database is a disposable cache of remote data
            .build()

    @Provides fun catalogDao(db: AppDatabase): CatalogDao = db.catalogDao()

    @Provides @Singleton
    fun lineCatalog(source: CeturbSource, dao: CatalogDao): LineCatalog = LineCatalogRepository(source, dao)

    @Provides @Singleton
    fun favorites(@ApplicationContext context: Context): FavoritesStore = DataStoreFavorites(context.favoritesDataStore)
}
