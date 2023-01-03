package info.nightscout.plugins.di

import dagger.Module
import dagger.android.ContributesAndroidInjector
import info.nightscout.plugins.general.asteroidOS.AsteroidOSFragment

@Module
@Suppress("unused")
abstract class AsteroidOSModule {

    @ContributesAndroidInjector abstract fun contributesAsteroidOSFragment(): AsteroidOSFragment
}