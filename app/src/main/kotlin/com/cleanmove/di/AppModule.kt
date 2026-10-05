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
import com.cleanmove.data.geocontrol.static.GeocontrolStaticApi
import com.cleanmove.data.geocontrol.static.createGeocontrolStaticHttpClient
import com.cleanmove.data.local.AppDatabase
import com.cleanmove.data.local.CatalogDao
import com.cleanmove.data.local.GeoDao
import com.cleanmove.data.repository.DataStoreFavorites
import com.cleanmove.data.repository.EstimatedArrivalsRepository
import com.cleanmove.data.repository.GeoStaticCatalogRepository
import com.cleanmove.data.repository.LineCatalogRepository
import com.cleanmove.data.repository.LineCodeResolver
import com.cleanmove.data.repository.LiveArrivalsRepository
import com.cleanmove.domain.EstimatedArrivals
import com.cleanmove.domain.FavoritesStore
import com.cleanmove.domain.LineCatalog
import com.cleanmove.domain.LiveArrivals
import com.cleanmove.domain.StopCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import java.time.ZonedDateTime
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

    // Added while implementing Task 9 (StopDetailViewModel is the first @HiltViewModel to
    // depend on these — Dagger's graph validation runs even for unit tests, so this wiring
    // couldn't wait for Task 11 as originally planned). Task 11 should treat these as already
    // done rather than re-adding them.
    //
    // GeocontrolStaticSource is built inline (not as its own @Provides) because its package,
    // com.cleanmove.data.geocontrol.static, has "static" as a segment — a reserved Java
    // keyword. Dagger's generated Java factories can compile code that merely *calls into*
    // Kotlin types from that package, but a factory whose own signature needs to import a
    // type from that package (as a distinct binding) fails javac with "<identifier> expected".
    // Keeping GeocontrolStaticSource out of the Dagger graph avoids ever generating that import.
    @Provides fun geoDao(db: AppDatabase): GeoDao = db.geoDao()

    @Provides @Singleton
    fun stopCatalog(dao: GeoDao): StopCatalog =
        GeoStaticCatalogRepository(GeocontrolStaticApi(createGeocontrolStaticHttpClient()), dao)

    @Provides
    fun lineCodeResolver(stopCatalog: StopCatalog, lineCatalog: LineCatalog): LineCodeResolver =
        LineCodeResolver(stopCatalog, lineCatalog)

    @Provides @Singleton
    fun liveArrivals(source: GeocontrolSource, resolver: LineCodeResolver, stopCatalog: StopCatalog): LiveArrivals =
        LiveArrivalsRepository(source, resolver, stopCatalog)

    @Provides @Singleton
    fun estimatedArrivals(
        stopCatalog: StopCatalog,
        geoDao: GeoDao,
        resolver: LineCodeResolver,
        lineCatalog: LineCatalog,
    ): EstimatedArrivals = EstimatedArrivalsRepository(stopCatalog, geoDao, resolver, lineCatalog)

    @Provides
    fun stopDetailClock(): () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")) }
}
